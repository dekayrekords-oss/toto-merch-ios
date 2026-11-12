import CoreBluetooth
import Foundation
import SharedKit
import UIKit

/// Bluetooth LE GATT client for LOOI, matching meowmba-app LooiBleLink / 736f030.
/// Scan names containing "LOOI", connect, discover standard 16-bit chars
/// (fe00, ff02, fed0, fed1, fed2, fed9). Status is Connected on didConnect,
/// then Ready once characteristics are found (or a discovery timeout fires).
/// Motor writes stay off until setWritesEnabled(true). Stop writes zeros to
/// ff02/fed0 and a neutral neck, and is allowed even when writes are off.
final class LooiBleLink: NSObject, LooiBleDriver, CBCentralManagerDelegate, CBPeripheralDelegate {
    private static let scanTimeout: TimeInterval = 12
    private static let keepAlive: TimeInterval = 30
    private static let discoveryTimeout: TimeInterval = 8
    private static let driveWatchdog: TimeInterval = 1.5
    private static let lastIdKey = "meowmba.looi.lastPeripheralId"
    private static let serviceUuidsKey = "meowmba.looi.serviceUuids"
    private static let logTag = "[LOOI]"

    private let queue = DispatchQueue(label: "meowmba.looi.ble")
    private let stateLock = NSLock()
    private var snapConnected = false
    private var snapReady = false
    private var snapWritesEnabled = false
    private var snapHasCommand = false
    private var snapHasMotorBoost = false
    private var snapHasMotorSlow = false
    private var snapHasNeck = false
    private var snapHasLight = false
    private var pendingNeckByte: UInt8?
    private var neckFlushScheduled = false

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var isConnectedFlag = false
    private var isReadyFlag = false
    private var commandFound = false
    private var writesEnabled = false
    private var commandChar: CBCharacteristic?
    private var motorBoostChar: CBCharacteristic?
    private var motorSlowChar: CBCharacteristic?
    private var neckChar: CBCharacteristic?
    private var lightChar: CBCharacteristic?
    private var sensorChar: CBCharacteristic?
    private var servicesRemaining = 0
    private var discoveryFinished = false
    private var scanTimer: DispatchWorkItem?
    private var discoveryTimer: DispatchWorkItem?
    private var keepAliveTimer: DispatchSourceTimer?
    private var driveWatchdogTimer: DispatchWorkItem?
    private var known: [UUID: CBPeripheral] = [:]
    private var reportedIds = Set<UUID>()
    private var connectId: UUID?
    private var autoConnect = false
    private var onDevice: ((String, String) -> Void)?
    private var onStatus: ((String) -> Void)?
    private var onScanDone: ((KotlinBoolean, String) -> Void)?
    private var onConnectResult: ((KotlinBoolean, String) -> Void)?
    private var connectReported = false
    private var scanReported = false
    private var startedConnect = false
    private var backgroundObservers: [NSObjectProtocol] = []

    var connected: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return snapConnected
    }

    private func syncFastSnapshotLocked() {
        stateLock.lock()
        snapConnected = isConnectedFlag
        snapReady = isReadyFlag
        snapWritesEnabled = writesEnabled
        snapHasCommand = commandChar != nil
        snapHasMotorBoost = motorBoostChar != nil
        snapHasMotorSlow = motorSlowChar != nil
        snapHasNeck = neckChar != nil
        snapHasLight = lightChar != nil
        if !isConnectedFlag || !writesEnabled {
            pendingNeckByte = nil
        }
        stateLock.unlock()
    }

    override init() {
        super.init()
        let center = NotificationCenter.default
        let resign = center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.handleLifecycleStop("App left the foreground — STOP sent, Motors OFF")
        }
        let background = center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.handleLifecycleStop("App backgrounded — STOP sent, Motors OFF")
        }
        backgroundObservers = [resign, background]
        queue.setSpecific(key: Self.queueKey, value: 1)
    }

    deinit {
        backgroundObservers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    @discardableResult
    private func ensureCentral() -> CBCentralManager {
        if let existing = central { return existing }
        let created = CBCentralManager(
            delegate: self,
            queue: queue,
            options: [CBCentralManagerOptionShowPowerAlertKey: true]
        )
        central = created
        log("created CBCentralManager state=\(created.state.rawValue)")
        return created
    }

    func scan(onDevice: @escaping (String, String) -> Void, onStatus: @escaping (String) -> Void, onDone: @escaping (KotlinBoolean, String) -> Void) {
        queue.async {
            self.ensureCentral()
            self.stopScanLocked(report: false)
            self.onDevice = onDevice
            self.onStatus = onStatus
            self.onScanDone = onDone
            self.scanReported = false
            self.reportedIds.removeAll()
            self.autoConnect = false
            self.log("scan() requested, central.state=\(self.central?.state.rawValue ?? -1)")
            self.status("Scanning for LOOI…")
            self.startScanLocked()
        }
    }

    func stopScan() {
        queue.async { self.stopScanLocked(report: true, message: "Scan stopped") }
    }

    func connect(id: String, onStatus: @escaping (String) -> Void, onResult: @escaping (KotlinBoolean, String) -> Void) {
        queue.async {
            self.ensureCentral()
            // Drop a previous session, but do not cancel the target we are about to adopt.
            let target = UUID(uuidString: id.trimmingCharacters(in: .whitespacesAndNewlines))
            if self.peripheral != nil, self.peripheral?.identifier != target {
                self.sendStopLocked()
                self.disconnectLocked(report: false)
            }
            self.onStatus = onStatus
            self.onConnectResult = onResult
            self.connectReported = false
            self.startedConnect = false
            self.commandFound = false
            self.isReadyFlag = false
            self.discoveryFinished = false
            self.servicesRemaining = 0
            self.clearCharacteristics()
            self.log("connect(id=\(id.isEmpty ? "<auto>" : id)), central.state=\(self.central?.state.rawValue ?? -1)")
            if id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                self.autoConnect = true
                self.connectId = nil
                self.reportedIds.removeAll()
                self.status("Connecting…")
                self.startScanLocked()
            } else if let uuid = target {
                self.autoConnect = false
                self.connectId = uuid
                self.status("Connecting…")
                self.connectKnownOrRetrieve(uuid)
            } else {
                self.reportConnect(false, "Invalid LOOI identifier")
            }
        }
    }

    func disconnect() {
        queue.async {
            self.log("disconnect()")
            self.sendStopLocked()
            self.writesEnabled = false
            self.syncFastSnapshotLocked()
            self.disconnectLocked(report: false)
            NativeDrivers.main { LooiRobotBridge.shared.onNativeStopped(reason: "LOOI · disconnected") }
        }
    }

    func setWritesEnabled(enabled: Bool) {
        stateLock.lock()
        snapWritesEnabled = enabled
        if !enabled { pendingNeckByte = nil }
        stateLock.unlock()
        queue.async {
            self.writesEnabled = enabled && self.isReadyFlag
            self.syncFastSnapshotLocked()
            self.log("writesEnabled=\(self.writesEnabled) (requested=\(enabled), ready=\(self.isReadyFlag))")
            if !self.writesEnabled { self.sendStopLocked() }
        }
    }

    func writeCommand(bytes: KotlinByteArray) -> Bool {
        guard canQueueWrite(check: { $0.snapHasCommand }) else { return false }
        let payload = data(from: bytes)
        queue.async { _ = self.writeLocked(self.commandChar, payload, driving: true, label: "fe00") }
        return true
    }

    func writeMotorBoost(bytes: KotlinByteArray) -> Bool {
        guard canQueueWrite(check: { $0.snapHasMotorBoost }) else { return false }
        let payload = data(from: bytes)
        queue.async { _ = self.writeLocked(self.motorBoostChar, payload, driving: true, label: "ff02") }
        return true
    }

    func writeMotorSlow(bytes: KotlinByteArray) -> Bool {
        guard canQueueWrite(check: { $0.snapHasMotorSlow }) else { return false }
        let payload = data(from: bytes)
        queue.async { _ = self.writeLocked(self.motorSlowChar, payload, driving: true, label: "fed0") }
        return true
    }

    func writeNeck(value: Int32) -> Bool {
        let clamped = UInt8(max(0, min(255, Int(value))))
        var shouldSchedule = false
        stateLock.lock()
        let allowed = snapWritesEnabled && snapConnected && snapReady && snapHasNeck
        if allowed {
            pendingNeckByte = clamped
            if !neckFlushScheduled {
                neckFlushScheduled = true
                shouldSchedule = true
            }
        }
        stateLock.unlock()
        guard allowed else { return false }
        if shouldSchedule {
            queue.async { self.flushPendingNeckLocked() }
        }
        return true
    }

    func writeHeadlight(on: Bool) -> Bool {
        guard canQueueWrite(check: { $0.snapHasLight }) else { return false }
        let payload = Data([on ? 0x03 : 0x00])
        queue.async { _ = self.writeLocked(self.lightChar, payload, driving: false, label: "fed2") }
        return true
    }

    private func canQueueWrite(check: (LooiBleLink) -> Bool) -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return snapWritesEnabled && snapConnected && snapReady && check(self)
    }

    private func flushPendingNeckLocked() {
        stateLock.lock()
        let next = pendingNeckByte
        pendingNeckByte = nil
        neckFlushScheduled = false
        stateLock.unlock()
        guard let byte = next else { return }
        _ = writeLocked(neckChar, Data([byte]), driving: false, label: "fed1")
    }

    func sendStop() -> Bool {
        stateLock.lock()
        pendingNeckByte = nil
        stateLock.unlock()
        var ok = false
        if DispatchQueue.getSpecific(key: Self.queueKey) != nil {
            ok = sendStopLocked()
        } else {
            queue.sync { ok = self.sendStopLocked() }
        }
        return ok
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log("central state=\(central.state.rawValue) (\(Self.stateMessage(central.state)))")
        if central.state == .poweredOn {
            adoptAlreadyConnectedLocked()
            let waitingToScan = (onScanDone != nil && !scanReported) || (autoConnect && !startedConnect && onConnectResult != nil && !connectReported)
            if waitingToScan, !central.isScanning {
                log("state is poweredOn; starting scan")
                central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            }
            return
        }
        let reason = Self.stateMessage(central.state)
        if onScanDone != nil { self.stopScanLocked(report: true, message: reason) }
        if onConnectResult != nil, !isConnectedFlag { self.reportConnect(false, reason) }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        known[peripheral.identifier] = peripheral
        let advertised = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        guard Self.matchesLooiName(peripheral.name, advertised) else { return }
        let name = peripheral.name ?? advertised ?? "LOOI"
        log("didDiscover name=\(name) advertised=\(advertised ?? "-") id=\(peripheral.identifier) rssi=\(RSSI)")
        reportFound(peripheral, name: name)
        if autoConnect, !startedConnect {
            startedConnect = true
            connectId = peripheral.identifier
            status("Found \(name). Connecting…")
            stopScanLocked(report: false)
            connectTo(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === self.peripheral else {
            log("didConnect ignored for unexpected peripheral \(peripheral.identifier)")
            return
        }
        log("didConnect name=\(peripheral.name ?? "-") id=\(peripheral.identifier) — link up")
        isConnectedFlag = true
        isReadyFlag = false
        commandFound = false
        discoveryFinished = false
        syncFastSnapshotLocked()
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Self.lastIdKey)
        status("Connected. Discovering LOOI services…")
        scheduleDiscoveryTimeout()
        peripheral.delegate = self
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let detail = error?.localizedDescription ?? "failed"
        log("didFailToConnect \(detail)")
        isConnectedFlag = false
        syncFastSnapshotLocked()
        reportConnect(false, "LOOI Bluetooth connection failed — \(detail)")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }
        log("didDisconnect error=\(error?.localizedDescription ?? "none")")
        isConnectedFlag = false
        isReadyFlag = false
        writesEnabled = false
        stopKeepAlive()
        cancelDiscoveryTimeout()
        cancelDriveWatchdog()
        if !connectReported {
            reportConnect(false, "LOOI disconnected" + (error.map { " — \($0.localizedDescription)" } ?? ""))
        }
        self.peripheral = nil
        clearCharacteristics()
        syncFastSnapshotLocked()
        NativeDrivers.main { LooiRobotBridge.shared.onNativeStopped(reason: "LOOI disconnected") }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === self.peripheral else { return }
        if let error {
            log("didDiscoverServices error=\(error.localizedDescription)")
            status("Connected, but service discovery failed — \(error.localizedDescription)")
            finishDiscovery(timeout: false)
            return
        }
        let services = peripheral.services ?? []
        log("didDiscoverServices count=\(services.count) uuids=\(services.map { $0.uuid.uuidString }.joined(separator: ","))")
        if services.isEmpty {
            status("Connected, but no GATT services were found")
            finishDiscovery(timeout: false)
            return
        }
        servicesRemaining = services.count
        status("Connected. Discovering characteristics…")
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === self.peripheral else { return }
        if let error {
            log("didDiscoverCharacteristics error=\(error.localizedDescription) service=\(service.uuid)")
        }
        let chars = service.characteristics ?? []
        log("characteristics for \(service.uuid.uuidString): \(chars.map { $0.uuid.uuidString }.joined(separator: ","))")
        var serviceOwnsLooiChar = false
        for ch in chars {
            switch ch.uuid {
            case Self.charCommand:
                commandChar = ch; commandFound = true; serviceOwnsLooiChar = true; log("bound fe00 command")
            case Self.charMotorBoost:
                motorBoostChar = ch; serviceOwnsLooiChar = true; log("bound ff02 motor boost")
            case Self.charMotorSlow:
                motorSlowChar = ch; serviceOwnsLooiChar = true; log("bound fed0 motor slow")
            case Self.charNeck:
                neckChar = ch; serviceOwnsLooiChar = true; log("bound fed1 neck")
            case Self.charHeadlight:
                lightChar = ch; serviceOwnsLooiChar = true; log("bound fed2 light")
            case Self.charSensors:
                sensorChar = ch; serviceOwnsLooiChar = true; log("bound fed9 sensors")
                if ch.properties.contains(.notify) || ch.properties.contains(.indicate) {
                    peripheral.setNotifyValue(true, for: ch)
                }
                if ch.properties.contains(.read) {
                    peripheral.readValue(for: ch)
                }
            default:
                break
            }
        }
        if serviceOwnsLooiChar {
            rememberServiceUuid(service.uuid)
        }
        syncFastSnapshotLocked()
        servicesRemaining = max(0, servicesRemaining - 1)
        if servicesRemaining == 0 {
            finishDiscovery(timeout: false)
        }
    }

    private func rememberServiceUuid(_ uuid: CBUUID) {
        let key = uuid.uuidString
        var saved = UserDefaults.standard.stringArray(forKey: Self.serviceUuidsKey) ?? []
        if !saved.contains(key) {
            saved.append(key)
            if saved.count > 8 { saved.removeFirst(saved.count - 8) }
            UserDefaults.standard.set(saved, forKey: Self.serviceUuidsKey)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        log("notify \(characteristic.uuid.uuidString) error=\(error?.localizedDescription ?? "ok")")
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            log("value \(characteristic.uuid.uuidString) error=\(error.localizedDescription)")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        log("didWrite \(characteristic.uuid.uuidString) error=\(error?.localizedDescription ?? "ok")")
    }

    private func finishDiscovery(timeout: Bool) {
        guard !discoveryFinished else { return }
        discoveryFinished = true
        cancelDiscoveryTimeout()
        isConnectedFlag = true
        if commandFound || neckChar != nil {
            isReadyFlag = true
            syncFastSnapshotLocked()
            startKeepAlive()
            let name = peripheral?.name ?? "LOOI"
            let msg = "Ready · LOOI connected (Bluetooth LE) · \(name)"
            log("Ready (\(timeout ? "after timeout" : "chars found"))")
            status(msg)
            reportConnect(true, msg)
        } else {
            isReadyFlag = false
            writesEnabled = false
            syncFastSnapshotLocked()
            let msg = timeout
                ? "Connected, but discovery timed out before LOOI channels (fe00/fed1) were found. Close the official LOOI app and tap Reconnect."
                : "Connected, but LOOI channels (fe00/fed1) were not found."
            log(msg)
            status(msg)
            // Link is up — do not report failure or drop the connection, but keep ready=false so Motors stay gated.
            reportConnect(true, msg)
        }
    }

    private func startScanLocked() {
        let central = ensureCentral()
        switch central.state {
        case .poweredOn:
            break
        case .unauthorized:
            reportScanOrConnect(false, "Bluetooth permission is off for Meow Merch. Enable it in Settings › Privacy & Security › Bluetooth.")
            return
        case .poweredOff:
            reportScanOrConnect(false, "Bluetooth is off. Turn it on, then Scan again.")
            return
        case .unsupported:
            reportScanOrConnect(false, "This iPhone does not support Bluetooth LE.")
            return
        default:
            status("Waiting for Bluetooth…")
            scheduleScanTimeout()
            return
        }
        adoptAlreadyConnectedLocked()
        if autoConnect, startedConnect { return }
        log("scanForPeripherals(withServices: nil)")
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        scheduleScanTimeout()
    }

    private func adoptAlreadyConnectedLocked() {
        guard let central, central.state == .poweredOn else { return }
        let savedStrings = UserDefaults.standard.stringArray(forKey: Self.serviceUuidsKey) ?? []
        let candidateStrings = Array(Set(savedStrings + ["180A", "180F", "FFE0", "FFF0", "FED0", "FE00"]))
        let services = candidateStrings.map { CBUUID(string: $0) }
        let already = central.retrieveConnectedPeripherals(withServices: services)
        log("retrieveConnectedPeripherals count=\(already.count)")
        for p in already {
            known[p.identifier] = p
            p.delegate = self
            let name = p.name ?? ""
            let label = name.isEmpty ? "LOOI (already connected)" : name
            let matches = Self.matchesLooiName(p.name, nil)
            log("already-connected name=\(label) id=\(p.identifier) matches=\(matches)")
            if matches {
                reportFound(p, name: label)
                if autoConnect, !startedConnect {
                    startedConnect = true
                    connectId = p.identifier
                    status("Found already-connected \(label). Connecting…")
                    stopScanLocked(report: false)
                    connectTo(p)
                    return
                }
            }
        }
        if let saved = UserDefaults.standard.string(forKey: Self.lastIdKey), let uuid = UUID(uuidString: saved) {
            let retrieved = central.retrievePeripherals(withIdentifiers: [uuid])
            log("retrieve last id=\(saved) count=\(retrieved.count)")
            for p in retrieved {
                known[p.identifier] = p
                p.delegate = self
                let name = p.name ?? "LOOI"
                reportFound(p, name: name)
                if let connectId, connectId == uuid, !startedConnect {
                    connectTo(p)
                    return
                }
            }
        }
    }

    private func scheduleScanTimeout() {
        scanTimer?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            if self.autoConnect, !self.startedConnect {
                self.stopScanLocked(report: false)
                self.reportConnect(false, "No LOOI in range. Power the base, fully close the official LOOI app, then Scan and Connect.")
            } else if self.onScanDone != nil, !self.scanReported {
                let found = !self.reportedIds.isEmpty
                self.stopScanLocked(report: true, message: found ? "Scan complete. Tap a LOOI, then Connect." : "No LOOI in range. Power the base, close the official LOOI app if it is open, then Scan again.")
            }
        }
        scanTimer = work
        queue.asyncAfter(deadline: .now() + Self.scanTimeout, execute: work)
    }

    private func scheduleDiscoveryTimeout() {
        cancelDiscoveryTimeout()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.discoveryFinished else { return }
            self.log("discovery timed out after \(Self.discoveryTimeout)s, commandFound=\(self.commandFound)")
            self.finishDiscovery(timeout: true)
        }
        discoveryTimer = work
        queue.asyncAfter(deadline: .now() + Self.discoveryTimeout, execute: work)
    }

    private func cancelDiscoveryTimeout() {
        discoveryTimer?.cancel()
        discoveryTimer = nil
    }

    private func stopScanLocked(report: Bool, message: String = "") {
        scanTimer?.cancel()
        scanTimer = nil
        if let central, central.state == .poweredOn { central.stopScan() }
        if report { reportScan(message.isEmpty ? "Scan stopped" : message) }
    }

    private func connectKnownOrRetrieve(_ uuid: UUID) {
        adoptAlreadyConnectedLocked()
        if let p = known[uuid] ?? ensureCentral().retrievePeripherals(withIdentifiers: [uuid]).first {
            connectTo(p)
            return
        }
        autoConnect = true
        connectId = uuid
        status("Scanning to find that LOOI…")
        startScanLocked()
    }

    private func connectTo(_ peripheral: CBPeripheral) {
        self.peripheral = peripheral
        peripheral.delegate = self
        startedConnect = true
        log("connectTo name=\(peripheral.name ?? "-") id=\(peripheral.identifier) state=\(peripheral.state.rawValue)")
        ensureCentral().connect(peripheral, options: nil)
        if peripheral.state == .connected {
            // Already attached to this phone — Core Bluetooth may not re-fire didConnect.
            log("peripheral already connected; starting discovery immediately")
            isConnectedFlag = true
            syncFastSnapshotLocked()
            status("Connected. Discovering LOOI services…")
            scheduleDiscoveryTimeout()
            peripheral.discoverServices(nil)
        }
    }

    private func disconnectLocked(report: Bool) {
        stopScanLocked(report: false)
        stopKeepAlive()
        cancelDiscoveryTimeout()
        cancelDriveWatchdog()
        isConnectedFlag = false
        isReadyFlag = false
        writesEnabled = false
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
        clearCharacteristics()
        syncFastSnapshotLocked()
        autoConnect = false
        startedConnect = false
        if report { reportConnect(false, "LOOI · disconnected") }
    }

    private func startKeepAlive() {
        stopKeepAlive()
        guard let sensorChar, sensorChar.properties.contains(.read) else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.keepAlive, repeating: Self.keepAlive)
        timer.setEventHandler { [weak self] in
            guard let self, self.isConnectedFlag, let p = self.peripheral, let ch = self.sensorChar else { return }
            p.readValue(for: ch)
        }
        keepAliveTimer = timer
        timer.resume()
    }

    private func stopKeepAlive() {
        keepAliveTimer?.cancel()
        keepAliveTimer = nil
    }

    @discardableResult
    private func writeLocked(_ characteristic: CBCharacteristic?, _ data: Data, driving: Bool, label: String) -> Bool {
        guard writesEnabled else {
            log("write \(label) skipped — Motors OFF")
            return false
        }
        guard isConnectedFlag, isReadyFlag, let peripheral, let characteristic else {
            log("write \(label) skipped — not ready")
            return false
        }
        let type: CBCharacteristicWriteType = characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        peripheral.writeValue(data, for: characteristic, type: type)
        log("write \(label) \(data.count) bytes type=\(type == .withoutResponse ? "withoutResponse" : "withResponse")")
        if driving { armDriveWatchdog() }
        return true
    }

    @discardableResult
    private func sendStopLocked() -> Bool {
        cancelDriveWatchdog()
        stateLock.lock()
        pendingNeckByte = nil
        stateLock.unlock()
        guard let peripheral, isConnectedFlag else {
            log("STOP skipped — not connected")
            return false
        }
        log("STOP: zero ff02/fed0 + neutral fed1")
        var sent = false
        let zeros = Data(repeating: 0, count: 4)
        if let ch = motorBoostChar {
            let type: CBCharacteristicWriteType = ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            peripheral.writeValue(zeros, for: ch, type: type)
            sent = true
        }
        if let ch = motorSlowChar {
            let type: CBCharacteristicWriteType = ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            peripheral.writeValue(zeros, for: ch, type: type)
            sent = true
        }
        if let ch = neckChar {
            let type: CBCharacteristicWriteType = ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            peripheral.writeValue(Data([0x80]), for: ch, type: type)
            sent = true
        }
        return sent
    }

    private func armDriveWatchdog() {
        cancelDriveWatchdog()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.writesEnabled, self.isConnectedFlag else { return }
            self.log("drive watchdog 1.5s — sending STOP")
            self.sendStopLocked()
        }
        driveWatchdogTimer = work
        queue.asyncAfter(deadline: .now() + Self.driveWatchdog, execute: work)
    }

    private func cancelDriveWatchdog() {
        driveWatchdogTimer?.cancel()
        driveWatchdogTimer = nil
    }

    private func handleLifecycleStop(_ reason: String) {
        stateLock.lock()
        snapWritesEnabled = false
        pendingNeckByte = nil
        stateLock.unlock()
        queue.async {
            self.log(reason)
            self.sendStopLocked()
            self.writesEnabled = false
            self.syncFastSnapshotLocked()
            NativeDrivers.main { LooiRobotBridge.shared.onNativeStopped(reason: reason) }
        }
    }

    private func reportFound(_ peripheral: CBPeripheral, name: String) {
        if reportedIds.contains(peripheral.identifier) { return }
        reportedIds.insert(peripheral.identifier)
        let id = peripheral.identifier.uuidString
        NativeDrivers.main { self.onDevice?(name, id) }
    }

    private func clearCharacteristics() {
        commandChar = nil
        motorBoostChar = nil
        motorSlowChar = nil
        neckChar = nil
        lightChar = nil
        sensorChar = nil
        syncFastSnapshotLocked()
    }

    private func data(from bytes: KotlinByteArray) -> Data {
        var out = Data(count: Int(bytes.size))
        for i in 0..<Int(bytes.size) {
            out[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        return out
    }

    private func status(_ text: String) {
        log("status: \(text)")
        NativeDrivers.main { self.onStatus?(text) }
    }

    private func reportScan(_ message: String) {
        if scanReported { return }
        scanReported = true
        log("scan done: \(message)")
        NativeDrivers.main { self.onScanDone?(KotlinBoolean(value: true), message) }
        onScanDone = nil
    }

    private func reportConnect(_ ok: Bool, _ message: String) {
        if connectReported { return }
        connectReported = true
        log("reportConnect ok=\(ok) \(message)")
        NativeDrivers.main { self.onConnectResult?(KotlinBoolean(value: ok), message) }
        onConnectResult = nil
    }

    private func reportScanOrConnect(_ ok: Bool, _ message: String) {
        if onConnectResult != nil { reportConnect(ok, message) }
        else { stopScanLocked(report: true, message: message) }
    }

    private func log(_ message: String) {
        NSLog("%@ %@", Self.logTag, message)
    }

    private static let queueKey = DispatchSpecificKey<UInt8>()

    private static func matchesLooiName(_ deviceName: String?, _ advertised: String?) -> Bool {
        [deviceName, advertised].compactMap { $0 }.contains { $0.range(of: "LOOI", options: .caseInsensitive) != nil }
    }

    private static func stateMessage(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOff: return "Bluetooth is off. Turn it on, then Scan again."
        case .unauthorized: return "Bluetooth permission is off for Meow Merch. Enable it in Settings › Privacy & Security › Bluetooth."
        case .unsupported: return "This iPhone does not support Bluetooth LE."
        case .resetting: return "Bluetooth is resetting. Try Scan again in a moment."
        case .poweredOn: return "poweredOn"
        default: return "Bluetooth is not ready yet."
        }
    }

    private static func uuid16(_ short: String) -> CBUUID { CBUUID(string: short) }
    private static let charCommand = uuid16("FE00")
    private static let charMotorBoost = uuid16("FF02")
    private static let charMotorSlow = uuid16("FED0")
    private static let charNeck = uuid16("FED1")
    private static let charHeadlight = uuid16("FED2")
    private static let charSensors = uuid16("FED9")
}
