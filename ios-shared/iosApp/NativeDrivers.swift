import AVFoundation
import CoreMotion
import MediaPlayer
import MapKit
import Network
import Security
import CryptoKit
import CoreLocation
import EventKit
import Foundation
import PDFKit
import PhotosUI
import SharedKit
import Speech
import UIKit
import UniformTypeIdentifiers
import Vision
import VisionKit

/// Registers the native capabilities the shared Kotlin code asks for through `PlatformDrivers`.
enum NativeDrivers {
    static func install() {
        let drivers = PlatformDrivers.shared
        drivers.speechIn = SpeechInput()
        drivers.speechOut = SpeechOutput()
        drivers.onDeviceTts = TotoKokoroTts()
        drivers.face = FaceTracking()
        drivers.share = ShareSheet()
        drivers.photos = PhotoPicker()
        drivers.location = Location()
        drivers.attach = AttachmentPicker()
        drivers.apps = AppLauncher()
        drivers.calendar = CalendarAccess()
        drivers.status = DeviceStatus()
        drivers.scanner = CodeScanner()
        drivers.music = AppleMusic()
        drivers.places = NearbyStores()
        drivers.clipboard = Clipboard()
        drivers.toto = NativeTotoSensorDriver.shared
        drivers.looi = LooiTcpLink()
        drivers.looiBle = LooiBleLink()
        DesktopBridge.shared.http = PinnedHttp()
        DesktopBridge.shared.secrets = KeychainStore()
        DesktopBridge.shared.refresh()
        TotoRobotBridge.shared.startAutoConnect()
        Terminal.shared.driver = SshTerminal()
#if DEBUG
        // Tooling only: a seeded test key moves into the Keychain and the file is deleted.
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("meowmba")
        let seed = docs.appendingPathComponent("debug_ssh_seed")
        if let text = try? String(contentsOf: seed, encoding: .utf8), let split = text.firstIndex(of: "\n") {
            KeychainStore().writeSecret(key: "ssh_" + String(text[..<split]), value: String(text[text.index(after: split)...]))
            try? FileManager.default.removeItem(at: seed)
        }
        DemoHooks.shared.enabled = true
#endif
        Needle.shared.driver = NeedleRuntime()
    }

    /// Kotlin callbacks update shared state; delivering them on the main queue keeps ordering simple.
    static func main(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }

    static func topController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes.first as? UIWindowScene
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

// MARK: - Speech to text

/// Tap-to-talk dictation: Apple's recognizer, on-device when the locale supports it. Capture ends by
/// itself after a short silence (or when `stop()` is called), then one final transcript is delivered.
final class SpeechInput: NSObject, SpeechInputDriver {
    private let engine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer? = SFSpeechRecognizer(locale: Locale.current) ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silence: Timer?
    private var latest = ""
    private var finished = true
    private var onFinal: ((String) -> Void)?

    func requestPermission(onResult: @escaping (KotlinBoolean, String?) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else {
                NativeDrivers.main { onResult(KotlinBoolean(value: false),
                    "Speech recognition is off for Meowmba. Turn it on in Settings › Privacy & Security › Speech Recognition.") }
                return
            }
            let reply: (Bool) -> Void = { granted in
                NativeDrivers.main {
                    onResult(KotlinBoolean(value: granted), granted ? nil :
                        "Microphone access is off for Meowmba. Turn it on in Settings › Privacy & Security › Microphone.")
                }
            }
            if #available(iOS 17.0, *) { AVAudioApplication.requestRecordPermission(completionHandler: reply) }
            else { AVAudioSession.sharedInstance().requestRecordPermission(reply) }
        }
    }

    func start(onPartial: @escaping (String) -> Void, onFinal: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        NativeDrivers.main { [self] in
            teardown()
            // Tap-to-talk owns the microphone while it runs; continuous listening resumes in teardown().
            ContinuousSpeechListener.shared.setPaused(.dictation, true)
            guard let recognizer, recognizer.isAvailable else {
                ContinuousSpeechListener.shared.setPaused(.dictation, false)
                onError("Speech recognition isn't available right now. Check your connection or language settings."); return
            }
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker, .allowBluetoothHFP])
                try session.setActive(true, options: .notifyOthersOnDeactivation)
                let request = SFSpeechAudioBufferRecognitionRequest()
                request.shouldReportPartialResults = true
                if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
                request.addsPunctuation = true
                self.request = request
                let input = engine.inputNode
                let format = input.outputFormat(forBus: 0)
                guard format.sampleRate > 0, format.channelCount > 0 else {
                    teardown()
                    onError("No microphone is available."); return
                }
                input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
                engine.prepare()
                try engine.start()
                latest = ""; finished = false; self.onFinal = onFinal
                task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                    NativeDrivers.main {
                        guard let self, !self.finished else { return }
                        if let result {
                            self.latest = result.bestTranscription.formattedString
                            onPartial(self.latest)
                            self.armSilenceTimer()
                            if result.isFinal { self.finish() }
                        } else if error != nil {
                            // "No speech detected" and cancellations end quietly with whatever was heard.
                            self.finish()
                        }
                    }
                }
                armSilenceTimer(initial: true)
            } catch {
                teardown()
                onError("The microphone couldn't start: \(error.localizedDescription)")
            }
        }
    }

    func stop() { NativeDrivers.main { [self] in request?.endAudio(); finish() } }

    private func armSilenceTimer(initial: Bool = false) {
        silence?.invalidate()
        // Give people time to start talking, then end 1.4s after they stop.
        silence = Timer.scheduledTimer(withTimeInterval: initial ? 6 : 1.4, repeats: false) { [weak self] _ in
            self?.request?.endAudio(); self?.finish()
        }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        let heard = latest
        let deliver = onFinal
        onFinal = nil
        teardown()
        deliver?(heard)
    }

    private func teardown() {
        silence?.invalidate(); silence = nil
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        task?.cancel(); task = nil
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        ContinuousSpeechListener.shared.setPaused(.dictation, false)
    }
}

// MARK: - Continuous listening (mounted Toto phone)

/// Always-on, on-device speech recognition while the Toto robot bridge is connected. The robot's own
/// small ASR mishears ("Hey Toto" → "Hey Cutro"), so the mounted iPhone transcribes and the bridge
/// receives only FINAL utterances (via `SpeechTranscriptSink`, POSTed by Kotlin).
///
/// Endpointing: an utterance ends when Apple marks the result final, or when the partial text has
/// not changed for 0.9 s; a fresh request starts immediately so listening is continuous while the
/// ended request drains (briefly) for Apple's final text + confidence. Tasks are recycled every ~50 s.
/// Errors, interruptions and route changes restart with a 0.3–1.0 s backoff, and a watchdog revives
/// anything that silently died. Tap-to-talk dictation and spoken replies pause listening.
/// Everything here runs on the main thread except the audio tap.
final class ContinuousSpeechListener: NSObject {
    enum PauseReason: Hashable { case dictation, speaking }

    static let shared = ContinuousSpeechListener()

    private static let contextual = ["Toto", "Hey Toto", "Toto come here", "Toto follow me", "Toto stop",
                                     "Toto look at me", "Toto go explore", "Toto go home"]
    private static let silenceEndpoint: TimeInterval = 0.9
    private static let recycleInterval: TimeInterval = 50
    private static let drainGrace: TimeInterval = 0.45
    /// Benign recognizer endings: 1110 no speech, 216/301 cancelled, 203 retry, 1101 local service hiccup.
    private static let benignCodes: Set<Int> = [1110, 216, 301, 203, 1101, 1107]

    /// One recognition request/task; becomes "draining" after endpointing until its final arrives.
    private final class Segment {
        let id: Int
        let request: SFSpeechAudioBufferRecognitionRequest
        var task: SFSpeechRecognitionTask?
        var text = ""
        var startedMs: Int64 = 0
        var endedMs: Int64 = 0
        var draining = false
        var emitted = false
        var graceTimer: Timer?
        init(id: Int, request: SFSpeechAudioBufferRecognitionRequest) { self.id = id; self.request = request }
    }

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private var engine: AVAudioEngine?
    private var engineUsesVoiceProcessing = false
    private var voiceProcessingBroken = false
    private var engineObserver: NSObjectProtocol?
    private let tapLock = NSLock()
    private var tapRequest: SFSpeechAudioBufferRecognitionRequest?

    private var sink: SpeechTranscriptSink?
    private var enabled = false
    private var permissionDenied = false
    private var paused = Set<PauseReason>()
    private var speakingPausedAt: Date?
    private var active: Segment?
    private var draining: [Int: Segment] = [:]
    private var nextSegmentId = 0
    private var silenceTimer: Timer?
    private var recycleTimer: Timer?
    private var watchdog: Timer?
    private var restartWork: DispatchWorkItem?
    private var consecutiveErrors = 0
    private var lastAudioConfigAt = Date.distantPast
    private var lastFinalKey = ""
    private var lastFinalAt = Date.distantPast
    private var lastStatus = ""
    private var observersInstalled = false

    // MARK: Public control

    func setEnabled(_ on: Bool, sink newSink: SpeechTranscriptSink?) {
        NativeDrivers.main { [self] in
            if on {
                if let newSink { sink = newSink }
                enabled = true
                installObservers()
                startWatchdog()
                report(false, "starting…")
                ensurePermissions { [weak self] granted in
                    guard let self, self.enabled else { return }
                    self.permissionDenied = !granted
                    if granted { self.startIfPossible() }
                    else { self.report(false, "mic/speech permission denied (Settings › Privacy)") }
                }
            } else {
                enabled = false
                watchdog?.invalidate(); watchdog = nil
                stopAll(deactivateSession: paused.isEmpty)
                report(false, "Mic off")
                sink = nil
            }
        }
    }

    /// Tap-to-talk dictation and spoken replies own the audio session while they run.
    func setPaused(_ reason: PauseReason, _ on: Bool) {
        NativeDrivers.main { [self] in
            if on {
                let inserted = paused.insert(reason).inserted
                if reason == .speaking { speakingPausedAt = Date() }
                stopAll(deactivateSession: false)
                if enabled && inserted { report(false, reason == .dictation ? "paused (dictation)" : "paused (speaking)") }
            } else {
                guard paused.remove(reason) != nil else { return }
                if reason == .speaking { speakingPausedAt = nil }
                if enabled { scheduleRestart(after: 0.35, full: true) }
            }
        }
    }

    // MARK: Lifecycle

    private func canRun() -> Bool {
        // Safety net: a spoken reply whose delegate never fired must not mute listening forever.
        if let since = speakingPausedAt, Date().timeIntervalSince(since) > 60 {
            speakingPausedAt = nil
            paused.remove(.speaking)
        }
        return enabled && paused.isEmpty && !permissionDenied && UIApplication.shared.applicationState != .background
    }

    private func startIfPossible() {
        guard canRun() else { return }
        guard let recognizer, recognizer.isAvailable else {
            report(false, "recognizer unavailable · retrying")
            scheduleRestart(after: 1.0, full: false)
            return
        }
        if engine?.isRunning != true {
            stopAll(deactivateSession: false)
            guard startAudio() else {
                report(false, "microphone busy · retrying")
                scheduleRestart(after: 1.0, full: true)
                return
            }
        }
        if active == nil { startSegment() }
        report(true, "listening")
    }

    private func ensurePermissions(_ done: @escaping (Bool) -> Void) {
        let mic: () -> Void = {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: done(true)
            case .denied: done(false)
            default: AVAudioApplication.requestRecordPermission { ok in NativeDrivers.main { done(ok) } }
            }
        }
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized: mic()
        case .notDetermined:
            SFSpeechRecognizer.requestAuthorization { status in NativeDrivers.main { status == .authorized ? mic() : done(false) } }
        default: done(false)
        }
    }

    // MARK: Audio

    private func startAudio() -> Bool {
        stopAudio()
        let session = AVAudioSession.sharedInstance()
        do {
            lastAudioConfigAt = Date()
            try session.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .defaultToSpeaker, .allowBluetoothHFP])
            try session.setActive(true)
        } catch {
            NSLog("[TotoSpeech] audio session error: \(error.localizedDescription)")
            return false
        }
        // First try with voice processing (noise suppression vs. robot motor noise), then without.
        for useVP in (voiceProcessingBroken ? [false] : [true, false]) {
            let candidate = AVAudioEngine()
            let input = candidate.inputNode
            var vpOn = false
            if useVP {
                do {
                    try input.setVoiceProcessingEnabled(true)
                    vpOn = true
                    input.voiceProcessingOtherAudioDuckingConfiguration =
                        AVAudioVoiceProcessingOtherAudioDuckingConfiguration(enableAdvancedDucking: false, duckingLevel: .min)
                } catch {
                    NSLog("[TotoSpeech] voice processing unavailable: \(error.localizedDescription)")
                    continue
                }
            }
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                if vpOn { try? input.setVoiceProcessingEnabled(false) }
                continue
            }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                self?.appendFromTap(buffer)
            }
            candidate.prepare()
            do {
                try candidate.start()
            } catch {
                NSLog("[TotoSpeech] engine start failed (vp=\(vpOn)): \(error.localizedDescription)")
                input.removeTap(onBus: 0)
                if vpOn { try? input.setVoiceProcessingEnabled(false) }
                continue
            }
            engine = candidate
            engineUsesVoiceProcessing = vpOn
            lastAudioConfigAt = Date()
            engineObserver = NotificationCenter.default.addObserver(
                forName: .AVAudioEngineConfigurationChange, object: candidate, queue: .main
            ) { [weak self] _ in
                guard let self, self.canRun(), self.engine === candidate, self.engine?.isRunning != true else { return }
                self.scheduleRestart(after: 0.5, full: true)
            }
            NSLog("[TotoSpeech] listening (voice processing \(vpOn ? "on" : "off"), \(format.sampleRate) Hz × \(format.channelCount))")
            return true
        }
        return false
    }

    private func stopAudio() {
        if let token = engineObserver { NotificationCenter.default.removeObserver(token); engineObserver = nil }
        guard let old = engine else { return }
        engine = nil
        old.inputNode.removeTap(onBus: 0)
        if old.isRunning { old.stop() }
        if engineUsesVoiceProcessing { try? old.inputNode.setVoiceProcessingEnabled(false) }
        engineUsesVoiceProcessing = false
    }

    private func appendFromTap(_ buffer: AVAudioPCMBuffer) {
        tapLock.lock()
        let request = tapRequest
        tapLock.unlock()
        request?.append(buffer)
    }

    private func setTapRequest(_ request: SFSpeechAudioBufferRecognitionRequest?) {
        tapLock.lock()
        tapRequest = request
        tapLock.unlock()
    }

    // MARK: Recognition segments

    private func startSegment() {
        silenceTimer?.invalidate(); silenceTimer = nil
        recycleTimer?.invalidate(); recycleTimer = nil
        guard let recognizer else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        request.taskHint = .dictation
        request.contextualStrings = Self.contextual
        request.addsPunctuation = true
        nextSegmentId += 1
        let seg = Segment(id: nextSegmentId, request: request)
        active = seg
        setTapRequest(request)
        seg.task = recognizer.recognitionTask(with: request) { [weak self, weak seg] result, error in
            NativeDrivers.main {
                guard let self, let seg else { return }
                self.handle(seg, result: result, error: error)
            }
        }
        let id = seg.id
        recycleTimer = makeTimer(Self.recycleInterval) { [weak self] in
            guard let self, self.active?.id == id else { return }
            self.rollOver()
        }
    }

    private func handle(_ seg: Segment, result: SFSpeechRecognitionResult?, error: Error?) {
        let wasActive = seg === active
        if let result {
            if wasActive { consecutiveErrors = 0 }
            let text = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty && text != seg.text {
                let now = Self.nowMs()
                if seg.startedMs == 0 { seg.startedMs = now }
                // A draining segment only refines what was already said; keep its end time.
                if !seg.draining { seg.endedMs = now }
                seg.text = text
                if wasActive { armSilenceTimer(for: seg) }
            }
            guard result.isFinal else { return }
            emit(seg, text: text.isEmpty ? seg.text : text, confidence: Self.confidence(of: result))
            retire(seg)
            if wasActive {
                active = nil
                setTapRequest(nil)
                if canRun() && engine?.isRunning == true { startSegment() } else { scheduleRestart(after: 0.3, full: false) }
            }
            return
        }
        // Ended without a final result: deliver what was heard, then restart with backoff.
        emit(seg, text: seg.text, confidence: nil)
        retire(seg)
        guard wasActive else { return }
        active = nil
        setTapRequest(nil)
        silenceTimer?.invalidate(); silenceTimer = nil
        recycleTimer?.invalidate(); recycleTimer = nil
        let nsError = error as NSError?
        let benign = nsError.map { Self.benignCodes.contains($0.code) } ?? true
        if !benign {
            consecutiveErrors += 1
            NSLog("[TotoSpeech] recognizer error \(nsError?.domain ?? "") \(nsError?.code ?? 0): \(nsError?.localizedDescription ?? "")")
            if consecutiveErrors >= 4 && engineUsesVoiceProcessing { voiceProcessingBroken = true }
        }
        let delay = benign ? 0.3 : min(1.0, 0.3 * pow(2.0, Double(max(0, consecutiveErrors - 1))))
        scheduleRestart(after: delay, full: consecutiveErrors >= 3)
    }

    private func armSilenceTimer(for seg: Segment) {
        silenceTimer?.invalidate()
        let id = seg.id
        silenceTimer = makeTimer(Self.silenceEndpoint) { [weak self] in
            guard let self, self.active?.id == id else { return }
            self.rollOver()
        }
    }

    /// Endpoint (silence) or recycle: hand the audio to a fresh request at once, let the old one drain.
    private func rollOver() {
        silenceTimer?.invalidate(); silenceTimer = nil
        guard let seg = active else { startIfPossible(); return }
        active = nil
        if canRun() && engine?.isRunning == true { startSegment() } else { setTapRequest(nil) }
        if seg.text.isEmpty {
            seg.task?.cancel()
            retire(seg)
        } else {
            seg.draining = true
            draining[seg.id] = seg
            seg.request.endAudio()
            seg.graceTimer = makeTimer(Self.drainGrace) { [weak self, weak seg] in
                guard let self, let seg else { return }
                self.emit(seg, text: seg.text, confidence: nil)
                seg.task?.cancel()
                self.retire(seg)
            }
        }
        if active == nil { startIfPossible() }
    }

    private func retire(_ seg: Segment) {
        seg.graceTimer?.invalidate(); seg.graceTimer = nil
        draining[seg.id] = nil
    }

    private func emit(_ seg: Segment, text: String, confidence: Double?) {
        guard !seg.emitted else { return }
        let clean = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(500))
        guard !clean.isEmpty else { return }
        seg.emitted = true
        let key = clean.lowercased().filter { $0.isLetter || $0.isNumber || $0 == " " }
        let now = Date()
        if key == lastFinalKey && now.timeIntervalSince(lastFinalAt) < 2.0 { return }
        lastFinalKey = key
        lastFinalAt = now
        let started = seg.startedMs > 0 ? seg.startedMs : Self.nowMs()
        let ended = max(started, seg.endedMs)
        let face = NativeTotoSensorDriver.shared.freshFaceDetected()
        NSLog("[TotoSpeech] final: \"\(clean)\" conf=\(confidence.map { String(format: "%.2f", $0) } ?? "nil") face=\(face)")
        sink?.onFinalTranscript(text: clean, confidence: confidence ?? -1, startedMs: started, endedMs: ended, faceDetected: face)
    }

    private func stopAll(deactivateSession: Bool) {
        restartWork?.cancel(); restartWork = nil
        silenceTimer?.invalidate(); silenceTimer = nil
        recycleTimer?.invalidate(); recycleTimer = nil
        setTapRequest(nil)
        if let seg = active {
            active = nil
            emit(seg, text: seg.text, confidence: nil)
            seg.task?.cancel()
            retire(seg)
        }
        for seg in Array(draining.values) {
            emit(seg, text: seg.text, confidence: nil)
            seg.task?.cancel()
            retire(seg)
        }
        stopAudio()
        if deactivateSession {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private func scheduleRestart(after delay: TimeInterval, full: Bool) {
        restartWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.restartWork = nil
            guard self.canRun() else { return }
            if full { self.stopAll(deactivateSession: false) }
            self.startIfPossible()
        }
        restartWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0.3, delay), execute: work)
    }

    private func startWatchdog() {
        guard watchdog == nil else { return }
        watchdog = makeTimer(3.0, repeats: true) { [weak self] in
            guard let self, self.canRun(), self.restartWork == nil else { return }
            if self.engine?.isRunning != true || self.active == nil { self.startIfPossible() }
        }
    }

    // MARK: System notifications

    private func installObservers() {
        guard !observersInstalled else { return }
        observersInstalled = true
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
            guard let self, self.enabled, self.paused.isEmpty else { return }
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw.flatMap({ AVAudioSession.InterruptionType(rawValue: $0) }) == .began {
                self.stopAll(deactivateSession: false)
                self.report(false, "interrupted")
                self.scheduleRestart(after: 1.0, full: true)
            } else {
                self.scheduleRestart(after: 0.6, full: true)
            }
        }
        center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
            guard let self, self.canRun(), Date().timeIntervalSince(self.lastAudioConfigAt) > 1.5 else { return }
            let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            switch raw.flatMap({ AVAudioSession.RouteChangeReason(rawValue: $0) }) {
            case .categoryChange:
                if AVAudioSession.sharedInstance().category != .playAndRecord || self.engine?.isRunning != true {
                    self.scheduleRestart(after: 0.6, full: true)
                }
            case .newDeviceAvailable, .oldDeviceUnavailable, .override, .wakeFromSleep, .noSuitableRouteForCategory:
                self.scheduleRestart(after: 0.6, full: true)
            default:
                if self.engine?.isRunning != true { self.scheduleRestart(after: 0.6, full: true) }
            }
        }
        center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main) { [weak self] _ in
            guard let self, self.enabled else { return }
            self.stopAll(deactivateSession: false)
            self.scheduleRestart(after: 1.0, full: true)
        }
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, self.enabled else { return }
            self.stopAll(deactivateSession: false)
            self.report(false, "paused (background)")
        }
        center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, self.enabled else { return }
            self.scheduleRestart(after: 0.5, full: self.engine?.isRunning != true)
        }
    }

    // MARK: Helpers

    private func report(_ listening: Bool, _ status: String) {
        let key = "\(listening)|\(status)"
        guard key != lastStatus else { return }
        lastStatus = key
        sink?.onListeningStatus(listening: listening, status: status)
    }

    /// Main-run-loop timer in common modes so it still fires while the UI is scrolling.
    private func makeTimer(_ interval: TimeInterval, repeats: Bool = false, _ block: @escaping () -> Void) -> Timer {
        let timer = Timer(timeInterval: interval, repeats: repeats) { _ in block() }
        RunLoop.main.add(timer, forMode: .common)
        return timer
    }

    private static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    private static func confidence(of result: SFSpeechRecognitionResult) -> Double? {
        let values = result.bestTranscription.segments.map { Double($0.confidence) }.filter { $0 > 0 }
        guard !values.isEmpty else { return nil }
        return min(1, max(0, values.reduce(0, +) / Double(values.count)))
    }
}

// MARK: - Spoken replies

final class SpeechOutput: NSObject, SpeechOutputDriver, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    private let synth = AVSpeechSynthesizer()
    private var player: AVAudioPlayer?
    private var done: (() -> Void)?

    override init() {
        super.init()
        synth.delegate = self
    }

    /// The best installed voice for the user's language: premium, then enhanced, then default.
    private lazy var voice: AVSpeechSynthesisVoice? = {
        let language = AVSpeechSynthesisVoice.currentLanguageCode()
        let voices = AVSpeechSynthesisVoice.speechVoices().filter { $0.language == language }
        return voices.first { $0.quality == .premium } ?? voices.first { $0.quality == .enhanced }
            ?? AVSpeechSynthesisVoice(language: language)
    }()

    func speak(text: String, onDone: @escaping () -> Void) {
        NativeDrivers.main { [self] in
            stopPlayback()
            if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
            let previous = done; done = nil; previous?()
            // Continuous listening releases the mic while the phone talks (and so never hears itself).
            ContinuousSpeechListener.shared.setPaused(.speaking, true)
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try? AVAudioSession.sharedInstance().setActive(true)
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = voice
            utterance.rate = AVSpeechUtteranceDefaultSpeechRate
            done = onDone
            synth.speak(utterance)
        }
    }

    func playWav(bytes: KotlinByteArray, onDone: @escaping () -> Void) {
        NativeDrivers.main { [self] in
            stopPlayback()
            if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
            let previous = done; done = nil; previous?()
            ContinuousSpeechListener.shared.setPaused(.speaking, true)
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try? AVAudioSession.sharedInstance().setActive(true)
            var copy = Data(count: Int(bytes.size))
            for i in 0..<Int(bytes.size) {
                copy[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
            }
            do {
                let audio = try AVAudioPlayer(data: copy)
                audio.delegate = self
                done = onDone
                player = audio
                audio.play()
            } catch {
                ContinuousSpeechListener.shared.setPaused(.speaking, false)
                onDone()
            }
        }
    }

    func stop() {
        NativeDrivers.main { [self] in
            stopPlayback()
            if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        }
    }

    private func stopPlayback() {
        player?.stop()
        player = nil
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        NativeDrivers.main { self.complete() }
    }

    private func complete() {
        let callback = done; done = nil
        player = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if !synth.isSpeaking { ContinuousSpeechListener.shared.setPaused(.speaking, false) }
        callback?()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { NativeDrivers.main { self.complete() } }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { NativeDrivers.main { self.complete() } }
}

// MARK: - Face tracking (avatar attention)

/// Front camera → Vision face rectangles, a few times a second. Shares the single front-camera
/// capture session with `NativeTotoSensorDriver.shared` so avatar attention and Toto robot vision
/// never interrupt each other's `AVCaptureSession`.
final class FaceTracking: NSObject, FaceTrackingDriver {
    func requestPermission(onResult: @escaping (KotlinBoolean, String?) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: onResult(KotlinBoolean(value: true), nil)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                NativeDrivers.main { onResult(KotlinBoolean(value: granted), granted ? nil : "Camera not allowed") }
            }
        default: onResult(KotlinBoolean(value: false), "Camera not allowed")
        }
    }

    func start(sink: FaceSink) {
        NativeTotoSensorDriver.shared.attachFaceSink(sink)
        NativeTotoSensorDriver.shared.setCameraEnabled(enabled: true)
        NativeDrivers.main { sink.onStatus(label: "Looking for you") }
    }

    func stop() {
        NativeTotoSensorDriver.shared.attachFaceSink(nil)
    }
}

// MARK: - Share sheet

final class ShareSheet: NSObject, ShareDriver {
    func shareText(text: String, subject: String) {
        NativeDrivers.main {
            guard let top = NativeDrivers.topController() else { return }
            let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            sheet.setValue(subject, forKey: "subject")
            sheet.popoverPresentationController?.sourceView = top.view
            sheet.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
            top.present(sheet, animated: true)
        }
    }
}

// MARK: - Photo picker (backgrounds)

/// PHPicker needs no photo-library permission: only the picture the person chooses is shared.
final class PhotoPicker: NSObject, PhotoPickerDriver, PHPickerViewControllerDelegate {
    private var onPicked: ((String?) -> Void)?

    func pickImage(onPicked: @escaping (String?) -> Void) {
        NativeDrivers.main { [self] in
            guard let top = NativeDrivers.topController() else { onPicked(nil); return }
            self.onPicked = onPicked
            var config = PHPickerConfiguration()
            config.filter = .images
            config.selectionLimit = 1
            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self
            top.present(picker, animated: true)
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        let callback = onPicked; onPicked = nil
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            callback?(nil); return
        }
        provider.loadObject(ofClass: UIImage.self) { object, _ in
            let path = (object as? UIImage).flatMap(Self.store)
            NativeDrivers.main { callback?(path) }
        }
    }

    /// Saved beside the other app data as a bounded JPEG, so the background survives restarts.
    private static func store(_ image: UIImage) -> String? {
        let limit: CGFloat = 2048
        let scale = min(1, limit / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let resized = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = resized.jpegData(compressionQuality: 0.88) else { return nil }
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("meowmba/backgrounds", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // Remove earlier picks so replaced backgrounds do not accumulate.
        (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil))?.forEach { try? FileManager.default.removeItem(at: $0) }
        let url = folder.appendingPathComponent("picked-\(Int(Date().timeIntervalSince1970)).jpg")
        return (try? data.write(to: url, options: .atomic)) != nil ? url.path : nil
    }
}

// MARK: - Location (weather)

/// One city-level fix for the weather, asking for when-in-use permission the first time.
final class Location: NSObject, LocationDriver, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var pending: [(Double, Double) -> Void] = []
    private var failures: [(String) -> Void] = []

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    func requestFix(onFix: @escaping (KotlinDouble, KotlinDouble) -> Void, onError: @escaping (String) -> Void) {
        NativeDrivers.main { [self] in
            if let recent = manager.location, recent.timestamp.timeIntervalSinceNow > -15 * 60 {
                onFix(KotlinDouble(value: recent.coordinate.latitude), KotlinDouble(value: recent.coordinate.longitude)); return
            }
            pending.append { onFix(KotlinDouble(value: $0), KotlinDouble(value: $1)) }
            failures.append(onError)
            switch manager.authorizationStatus {
            case .notDetermined: manager.requestWhenInUseAuthorization()
            case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
            default: fail("I need location access to check the weather where you are. You can also type a city on the Weather page.")
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard !pending.isEmpty else { return }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        case .denied, .restricted: fail("I need location access to check the weather where you are. You can also type a city on the Weather page.")
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        let callbacks = pending; pending = []; failures = []
        callbacks.forEach { $0(fix.coordinate.latitude, fix.coordinate.longitude) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        fail("I don't have a location fix yet, so I can't check the weather here.")
    }

    private func fail(_ message: String) {
        let callbacks = failures; pending = []; failures = []
        callbacks.forEach { $0(message) }
    }
}

// MARK: - Attachments (chat / Flow "+")

/// Camera, photo library or Files. Text is read on the phone — Vision OCR for pictures and scanned
/// PDFs, PDFKit's text layer, plain text files directly — and nothing is uploaded here.
final class AttachmentPicker: NSObject, AttachmentDriver, PHPickerViewControllerDelegate,
    UIImagePickerControllerDelegate, UINavigationControllerDelegate, UIDocumentPickerDelegate {
    private var onRead: ((String, String, String) -> Void)?
    private var onCancel: (() -> Void)?
    private var onError: ((String) -> Void)?
    private let work = DispatchQueue(label: "meow.attach.read", qos: .userInitiated)

    func pick(kind: String, onRead: @escaping (String, String, String) -> Void, onCancel: @escaping () -> Void, onError: @escaping (String) -> Void) {
        NativeDrivers.main { [self] in
            guard let top = NativeDrivers.topController() else { onCancel(); return }
            self.onRead = onRead; self.onCancel = onCancel; self.onError = onError
            switch kind {
            case "PHOTO":
                guard UIImagePickerController.isSourceTypeAvailable(.camera) else { finishError("This device has no camera."); return }
                let camera = UIImagePickerController()
                camera.sourceType = .camera
                camera.delegate = self
                top.present(camera, animated: true)
            case "IMAGE":
                var config = PHPickerConfiguration()
                config.filter = .images
                config.selectionLimit = 1
                let picker = PHPickerViewController(configuration: config)
                picker.delegate = self
                top.present(picker, animated: true)
            default:
                let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
                picker.delegate = self
                top.present(picker, animated: true)
            }
        }
    }

    // Camera
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        guard let image = info[.originalImage] as? UIImage else { finishCancel(); return }
        readImage(image, name: "Photo")
    }
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true); finishCancel() }

    // Photo library
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else { finishCancel(); return }
        let name = provider.suggestedName ?? "Picture"
        provider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
            guard let image = object as? UIImage else { self?.finishError("That picture couldn't be opened."); return }
            self?.readImage(image, name: name)
        }
    }

    // Files
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { finishCancel(); return }
        work.async { [self] in
            let type = UTType(filenameExtension: url.pathExtension) ?? .data
            let name = url.lastPathComponent
            if type.conforms(to: .image), let image = UIImage(contentsOfFile: url.path) {
                readImage(image, name: name); return
            }
            var text = ""
            if type.conforms(to: .pdf), let pdf = PDFDocument(url: url) {
                text = pdf.string ?? ""
                if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { text = Self.ocrPages(pdf) }
            } else if type.conforms(to: .text) || type.conforms(to: .json) || type.conforms(to: .commaSeparatedText) || type.conforms(to: .xml) {
                text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
            }
            finishRead(name, type.preferredMIMEType ?? "application/octet-stream", text)
        }
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finishCancel() }

    private func readImage(_ image: UIImage, name: String) {
        work.async { [self] in finishRead(name, "image/jpeg", Self.ocr(image.cgImage)) }
    }

    static func ocr(_ image: CGImage?) -> String {
        guard let image else { return "" }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        try? VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
    }

    /// Scanned PDFs have no text layer: render the first pages and read them like photos.
    private static func ocrPages(_ pdf: PDFDocument) -> String {
        (0..<min(pdf.pageCount, 5)).compactMap { index -> String? in
            guard let page = pdf.page(at: index) else { return nil }
            let bounds = page.bounds(for: .mediaBox)
            let scale = 1600 / max(bounds.width, bounds.height)
            let image = page.thumbnail(of: CGSize(width: bounds.width * scale, height: bounds.height * scale), for: .mediaBox)
            return ocr(image.cgImage)
        }.joined(separator: "\n")
    }

    private func finishRead(_ name: String, _ mime: String, _ text: String) {
        NativeDrivers.main { [self] in let cb = onRead; clear(); cb?(name, mime, String(text.prefix(8000))) }
    }
    private func finishCancel() { NativeDrivers.main { [self] in let cb = onCancel; clear(); cb?() } }
    private func finishError(_ message: String) { NativeDrivers.main { [self] in let cb = onError; clear(); cb?(message) } }
    private func clear() { onRead = nil; onCancel = nil; onError = nil }
}

// MARK: - Other apps

/// Opens other iPhone apps by URL scheme and asks iOS which declared schemes have an app installed.
final class AppLauncher: NSObject, AppLauncherDriver {
    func installedSchemes(schemes: [String]) -> [String] {
        schemes.filter { scheme in
            guard let url = URL(string: scheme + "://") else { return false }
            return Thread.isMainThread ? UIApplication.shared.canOpenURL(url)
                : DispatchQueue.main.sync { UIApplication.shared.canOpenURL(url) }
        }
    }
    func open(url: String, onResult: @escaping (KotlinBoolean) -> Void) {
        NativeDrivers.main {
            let target = url == "app-settings:" ? URL(string: UIApplication.openSettingsURLString) : URL(string: url)
            guard let target else { onResult(KotlinBoolean(value: false)); return }
            UIApplication.shared.open(target, options: [:]) { ok in NativeDrivers.main { onResult(KotlinBoolean(value: ok)) } }
        }
    }
}

// MARK: - Calendar & Reminders

final class CalendarAccess: NSObject, CalendarDriver {
    private let store = EKEventStore()

    func hasAccess() -> Bool {
        let e = EKEventStore.authorizationStatus(for: .event)
        if #available(iOS 17.0, *) { return e == .fullAccess }
        return e == .authorized
    }

    func requestAccess(onResult: @escaping (KotlinBoolean, String?) -> Void) {
        let denied = "Calendar or Reminders access is off for Meowmba. Turn it on in Settings › Privacy & Security."
        let finish: (Bool) -> Void = { ok in NativeDrivers.main { onResult(KotlinBoolean(value: ok), ok ? nil : denied) } }
        if #available(iOS 17.0, *) {
            store.requestFullAccessToEvents { events, _ in
                self.store.requestFullAccessToReminders { reminders, _ in finish(events && reminders) }
            }
        } else {
            store.requestAccess(to: .event) { events, _ in
                self.store.requestAccess(to: .reminder) { reminders, _ in finish(events && reminders) }
            }
        }
    }

    func addReminder(title: String, dueMs: Int64, onDone: @escaping (KotlinBoolean, String) -> Void) {
        let reminder = EKReminder(eventStore: store)
        reminder.title = title
        reminder.calendar = store.defaultCalendarForNewReminders()
        if dueMs > 0 {
            let date = Date(timeIntervalSince1970: TimeInterval(dueMs) / 1000)
            reminder.dueDateComponents = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            reminder.addAlarm(EKAlarm(absoluteDate: date))
        }
        do { try store.save(reminder, commit: true); NativeDrivers.main { onDone(KotlinBoolean(value: true), "") } }
        catch { NativeDrivers.main { onDone(KotlinBoolean(value: false), "I couldn't save that reminder: \(error.localizedDescription)") } }
    }

    func addEvent(title: String, startMs: Int64, minutes: Int32, onDone: @escaping (KotlinBoolean, String) -> Void) {
        let event = EKEvent(eventStore: store)
        event.title = title
        event.startDate = Date(timeIntervalSince1970: TimeInterval(startMs) / 1000)
        event.endDate = event.startDate.addingTimeInterval(TimeInterval(minutes) * 60)
        event.calendar = store.defaultCalendarForNewEvents
        do { try store.save(event, span: .thisEvent, commit: true); NativeDrivers.main { onDone(KotlinBoolean(value: true), "") } }
        catch { NativeDrivers.main { onDone(KotlinBoolean(value: false), "I couldn't add that to your calendar: \(error.localizedDescription)") } }
    }

    func events(fromMs: Int64, toMs: Int64, onResult: @escaping ([String]) -> Void) {
        let from = Date(timeIntervalSince1970: TimeInterval(fromMs) / 1000)
        let to = Date(timeIntervalSince1970: TimeInterval(toMs) / 1000)
        let found = store.events(matching: store.predicateForEvents(withStart: from, end: to, calendars: nil))
            .filter { !$0.isAllDay }
            .sorted { $0.startDate < $1.startDate }
            .map { "\(Int64($0.startDate.timeIntervalSince1970 * 1000))|\($0.title ?? "Event")" }
        NativeDrivers.main { onResult(found) }
    }

    func reminders(untilMs: Int64, onResult: @escaping ([String]) -> Void) {
        let until = Date(timeIntervalSince1970: TimeInterval(untilMs) / 1000)
        let predicate = store.predicateForIncompleteReminders(withDueDateStarting: nil, ending: until, calendars: nil)
        store.fetchReminders(matching: predicate) { found in
            let lines = (found ?? []).compactMap { r -> (Date, String)? in
                guard let due = r.dueDateComponents?.date ?? r.dueDateComponents.flatMap({ Calendar.current.date(from: $0) }) else { return nil }
                return (due, "\(Int64(due.timeIntervalSince1970 * 1000))|\(r.calendarItemIdentifier)|\(r.title ?? "Reminder")")
            }.sorted { $0.0 < $1.0 }.map { $0.1 }
            NativeDrivers.main { onResult(lines) }
        }
    }

    func cancelEvent(startMs: Int64, title: String, onDone: @escaping (KotlinBoolean, String) -> Void) {
        let start = Date(timeIntervalSince1970: TimeInterval(startMs) / 1000)
        let window = store.predicateForEvents(withStart: start.addingTimeInterval(-60), end: start.addingTimeInterval(60), calendars: nil)
        let matches = store.events(matching: window).filter { $0.title == title && abs($0.startDate.timeIntervalSince(start)) < 1 }
        guard let event = matches.first, matches.count == 1 else {
            return NativeDrivers.main { onDone(KotlinBoolean(value: false), matches.isEmpty
                ? "I couldn't find “\(title)” at that time any more, so nothing was canceled."
                : "More than one “\(title)” starts then, so nothing was canceled.") }
        }
        guard event.calendar.allowsContentModifications else {
            return NativeDrivers.main { onDone(KotlinBoolean(value: false), "“\(title)” is in a read-only calendar (\(event.calendar.title)), so it can't be canceled here.") }
        }
        do {
            try store.remove(event, span: .thisEvent, commit: true)
            // Verify: the occurrence must be gone before we say so.
            let gone = store.events(matching: window).allSatisfy { !($0.title == title && abs($0.startDate.timeIntervalSince(start)) < 1) }
            NativeDrivers.main { onDone(KotlinBoolean(value: gone), gone ? "Canceled “\(title)” for this occurrence only. The rest of the series is unchanged."
                                                                    : "iOS accepted the change but the event is still there. Check your calendar.") }
        } catch {
            NativeDrivers.main { onDone(KotlinBoolean(value: false), "I couldn't cancel it: \(error.localizedDescription)") }
        }
    }

    func completeReminder(id: String, onDone: @escaping (KotlinBoolean) -> Void) {
        guard let r = store.calendarItem(withIdentifier: id) as? EKReminder else { return NativeDrivers.main { onDone(KotlinBoolean(value: false)) } }
        r.isCompleted = true
        let ok = (try? store.save(r, commit: true)) != nil
        NativeDrivers.main { onDone(KotlinBoolean(value: ok)) }
    }
}

final class DeviceStatus: NSObject, DeviceStatusDriver {
    override init() { super.init(); DispatchQueue.main.async { UIDevice.current.isBatteryMonitoringEnabled = true } }
    func batteryPercent() -> Int32 {
        let level = UIDevice.current.batteryLevel
        return level < 0 ? -1 : Int32((level * 100).rounded())
    }
    func charging() -> Bool { [.charging, .full].contains(UIDevice.current.batteryState) }
    private var observer: NSObjectProtocol?
    func observeCharging(onChange: @escaping (KotlinBoolean) -> Void) {
        NativeDrivers.main { [self] in
            UIDevice.current.isBatteryMonitoringEnabled = true
            if let observer { NotificationCenter.default.removeObserver(observer) }
            observer = NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
                // Let a MagSafe puck settle before deciding (Android waits 750 ms too).
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { onChange(KotlinBoolean(value: self?.charging() ?? false)) }
            }
        }
    }
}


// MARK: - QR / barcode scanner (Android QrScannerActivity)

final class CodeScanner: NSObject, CodeScannerDriver {
    private var session: ScanSession?

    func scan(onResult: @escaping (String?) -> Void, onError: @escaping (String) -> Void) {
        DispatchQueue.main.async { [self] in
            MainActor.assumeIsolated {
                guard DataScannerViewController.isSupported else { return onError("This iPhone can't scan codes live. Take a photo of the code with + instead.") }
                guard DataScannerViewController.isAvailable else { return onError("Camera access is off for Meowmba. Turn it on in Settings › Meowmba › Camera.") }
                let s = ScanSession { [weak self] value in self?.session = nil; onResult(value) }
                session = s
                s.present()
            }
        }
    }
}

@MainActor
private final class ScanSession: NSObject, DataScannerViewControllerDelegate, UIAdaptivePresentationControllerDelegate {
    private var done: ((String?) -> Void)?
    private var nav: UINavigationController?
    init(done: @escaping (String?) -> Void) { self.done = done }

    func present() {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode()], qualityLevel: .balanced,
                                           recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false, isHighlightingEnabled: true)
        vc.delegate = self
        vc.navigationItem.title = "Scan a code"
        vc.navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.finish(nil) })
        let nav = UINavigationController(rootViewController: vc)
        nav.presentationController?.delegate = self
        self.nav = nav
        NativeDrivers.topController()?.present(nav, animated: true) { try? vc.startScanning() }
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        for item in addedItems { if case .barcode(let code) = item, let value = code.payloadStringValue { return finish(value) } }
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
        if case .barcode(let code) = item { finish(code.payloadStringValue) }
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        let cb = done; done = nil; cb?(nil)
    }

    private func finish(_ value: String?) {
        guard let cb = done else { return }
        done = nil
        UINotificationFeedbackGenerator().notificationOccurred(value == nil ? .warning : .success)
        nav?.dismiss(animated: true) { cb(value) }
    }
}


// MARK: - Apple Music now playing (Android RoomMediaWatcher, iOS scope)

final class AppleMusic: NSObject, MusicDriver {
    private var player: MPMusicPlayerController { MPMusicPlayerController.systemMusicPlayer }

    func hasAccess() -> Bool { MPMediaLibrary.authorizationStatus() == .authorized }

    func requestMusicAccess(onResult: @escaping (KotlinBoolean) -> Void) {
        MPMediaLibrary.requestAuthorization { status in
            NativeDrivers.main { onResult(KotlinBoolean(value: status == .authorized)) }
        }
    }

    func nowPlaying() -> String? {
        guard hasAccess() else { return nil }
        var result: String?
        let read = { [self] in
            guard let item = player.nowPlayingItem else { return }
            let playing = player.playbackState == .playing ? "1" : "0"
            result = [item.title ?? "Untitled", item.artist ?? "", playing].map { $0.replacingOccurrences(of: "|", with: "/") }.joined(separator: "|")
        }
        if Thread.isMainThread { read() } else { DispatchQueue.main.sync(execute: read) }
        return result
    }

    func togglePlay() { NativeDrivers.main { [self] in player.playbackState == .playing ? player.pause() : player.play() } }
    func next() { NativeDrivers.main { [self] in player.skipToNextItem() } }
}


// MARK: - Nearby stores (Android StoreShoppingTool's place lookup, via Apple Maps)

final class NearbyStores: NSObject, PlacesDriver, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var done: ((String?, [String], String?) -> Void)?

    func nearbyStores(onResult: @escaping (String?, [String], String?) -> Void) {
        NativeDrivers.main { [self] in
            done = onResult
            manager.delegate = self
            manager.desiredAccuracy = kCLLocationAccuracyBest
            switch manager.authorizationStatus {
            case .notDetermined: manager.requestWhenInUseAuthorization()
            case .denied, .restricted: finish(nil, [], "Location access is off for Meowmba.")
            default: manager.requestLocation()
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard done != nil else { return }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        case .denied, .restricted: finish(nil, [], "Location access is off for Meowmba.")
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { finish(nil, [], error.localizedDescription) }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, done != nil else { return }
        let fix = "\(loc.coordinate.latitude)|\(loc.coordinate.longitude)|\(loc.horizontalAccuracy)|\(Int64(loc.timestamp.timeIntervalSince1970 * 1000))|\(loc.speed)"
        let request = MKLocalPointsOfInterestRequest(center: loc.coordinate, radius: 250)
        request.pointOfInterestFilter = MKPointOfInterestFilter(including: [.store, .foodMarket, .pharmacy, .bakery, .cafe, .gasStation])
        MKLocalSearch(request: request).start { [weak self] response, _ in
            let places = (response?.mapItems ?? []).compactMap { item -> String? in
                guard let name = item.name else { return nil }
                let c = item.placemark.coordinate
                return "\(name.replacingOccurrences(of: "|", with: " "))|\(c.latitude)|\(c.longitude)"
            }
            self?.finish(fix, places, nil)
        }
    }

    private func finish(_ fix: String?, _ places: [String], _ error: String?) {
        NativeDrivers.main { [self] in let cb = done; done = nil; cb?(fix, places, error) }
    }
}


// MARK: - UDP link for Cozmo (Android binds a DatagramSocket to Cozmo's Wi-Fi)

final class UdpLink: NSObject, UdpDriver {
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "meowmba.cozmo.udp")

    func open(host: String, port: Int32, onReceive: @escaping (KotlinByteArray) -> Void, onFailed: @escaping (String) -> Void) {
        let params = NWParameters.udp
        // Keep robot traffic on Wi-Fi; chat can stay on cellular while joined to Cozmo's network.
        params.requiredInterfaceType = .wifi
        let c = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: UInt16(port))!, using: params)
        c.stateUpdateHandler = { state in
            switch state {
            case .failed(let error): onFailed("Cozmo link failed: \(error.localizedDescription)")
            case .waiting(let error): onFailed("Join Cozmo's Wi-Fi in Settings, then tap Connect. (\(error.localizedDescription))")
            default: break
            }
        }
        func receive() {
            c.receiveMessage { data, _, _, error in
                if let data, !data.isEmpty { onReceive(UdpLink.kotlin(data)) }
                if error == nil { receive() }
            }
        }
        connection = c
        c.start(queue: queue)
        receive()
    }

    func send(bytes: KotlinByteArray) {
        let count = Int(bytes.size)
        var data = Data(count: count)
        data.withUnsafeMutableBytes { raw in
            let p = raw.bindMemory(to: Int8.self)
            for i in 0..<count { p[i] = bytes.get(index: Int32(i)) }
        }
        connection?.send(content: data, completion: .idempotent)
    }

    func close() { connection?.cancel(); connection = nil }

    static func data(_ bytes: KotlinByteArray) -> Data {
        let count = Int(bytes.size)
        var data = Data(count: count)
        data.withUnsafeMutableBytes { raw in
            let p = raw.bindMemory(to: Int8.self)
            for i in 0..<count { p[i] = bytes.get(index: Int32(i)) }
        }
        return data
    }

    static func kotlin(_ data: Data) -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(data.count))
        data.withUnsafeBytes { raw in
            let p = raw.bindMemory(to: Int8.self)
            for i in 0..<data.count { out.set(index: Int32(i), value: p[i]) }
        }
        return out
    }
}


// MARK: - Desktop bridge transport (Android DesktopBridgeManager: pinned self-signed TLS)

final class PinnedHttp: NSObject, PinnedHttpDriver {
    final class SessionEntry: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
        private struct TaskContext {
            let maxBytes: Int
            let result: (KotlinInt, KotlinByteArray?, String?) -> Void
            var received = Data()
            var status = 0
            var failure: String?
        }

        let expected: String
        private let lock = NSLock()
        private var tasks: [Int: TaskContext] = [:]
        lazy var session: URLSession = {
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 15
            config.timeoutIntervalForResource = 30
            config.httpMaximumConnectionsPerHost = 4
            config.waitsForConnectivity = false
            config.httpShouldSetCookies = false
            config.urlCache = nil
            return URLSession(configuration: config, delegate: self, delegateQueue: nil)
        }()

        init(_ expected: String) {
            self.expected = expected.lowercased()
            super.init()
        }

        func start(_ request: URLRequest, maxBytes: Int, result: @escaping (KotlinInt, KotlinByteArray?, String?) -> Void) {
            let task = session.dataTask(with: request)
            lock.lock()
            tasks[task.taskIdentifier] = TaskContext(maxBytes: maxBytes, result: result)
            lock.unlock()
            task.resume()
        }

        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
                  let trust = challenge.protectionSpace.serverTrust,
                  let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else {
                return completionHandler(.cancelAuthenticationChallenge, nil)
            }
            let der = SecCertificateCopyData(leaf) as Data
            let fingerprint = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
            // Pairing pins the exact private server certificate, including self-signed certificates.
            if fingerprint == expected {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            lock.lock()
            tasks[task.taskIdentifier]?.failure = "redirect refused"
            lock.unlock()
            completionHandler(nil)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let expectedLen = response.expectedContentLength
            var allow = true
            lock.lock()
            if var ctx = tasks[dataTask.taskIdentifier] {
                ctx.status = code
                if expectedLen > Int64(ctx.maxBytes) {
                    ctx.failure = "response too large"
                    allow = false
                }
                tasks[dataTask.taskIdentifier] = ctx
            }
            lock.unlock()
            completionHandler(allow ? .allow : .cancel)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            var shouldCancel = false
            lock.lock()
            if var ctx = tasks[dataTask.taskIdentifier] {
                if data.count <= ctx.maxBytes - ctx.received.count {
                    ctx.received.append(data)
                } else {
                    ctx.failure = "response too large"
                    shouldCancel = true
                }
                tasks[dataTask.taskIdentifier] = ctx
            }
            lock.unlock()
            if shouldCancel { dataTask.cancel() }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            lock.lock()
            guard let ctx = tasks.removeValue(forKey: task.taskIdentifier) else {
                lock.unlock()
                return
            }
            lock.unlock()
            let message: String?
            if let explicit = ctx.failure {
                message = explicit
            } else if let nsErr = error as NSError? {
                if nsErr.domain == NSURLErrorDomain && nsErr.code == NSURLErrorCancelled {
                    message = "certificate pin mismatch"
                } else {
                    message = "pinned HTTPS request failed"
                }
            } else {
                message = nil
            }
            let payload = message == nil ? UdpLink.kotlin(ctx.received) : nil
            let code = Int32(message == nil ? ctx.status : 0)
            NativeDrivers.main { ctx.result(KotlinInt(value: code), payload, message) }
        }
    }

    private static let poolLock = NSLock()
    private static var pool: [String: SessionEntry] = [:]

    static func entry(for certSha256: String) -> SessionEntry {
        let key = certSha256.lowercased()
        poolLock.lock()
        defer { poolLock.unlock() }
        if let existing = pool[key] { return existing }
        if pool.count >= 4 {
            for (_, old) in pool { old.session.finishTasksAndInvalidate() }
            pool.removeAll()
        }
        let created = SessionEntry(key)
        pool[key] = created
        return created
    }

    func post(host: String, port: Int32, path: String, body: KotlinByteArray, certSha256: String,
              headers: [String: String], timeoutMs: Int32, onResult: @escaping (KotlinInt, KotlinByteArray?, String?) -> Void) {
        guard certSha256.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil,
              host.range(of: "^[A-Za-z0-9.:-]+$", options: .regularExpression) != nil,
              (1...65535).contains(port), path.hasPrefix("/"), !path.hasPrefix("//"),
              !path.contains("\r"), !path.contains("\n"),
              headers.allSatisfy({ !$0.key.contains(where: { $0.isNewline }) && !$0.value.contains(where: { $0.isNewline }) }) else {
            return onResult(KotlinInt(value: 0), nil, "invalid pinned HTTPS configuration")
        }
        let hostPart = host.contains(":") ? "[\(host)]" : host
        guard let url = URL(string: "https://\(hostPart):\(port)\(path)") else {
            return onResult(KotlinInt(value: 0), nil, "invalid pinned HTTPS address")
        }
        let timeout = TimeInterval(min(max(timeoutMs, 1000), 30000)) / 1000
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        request.httpBody = UdpLink.data(body)
        let maxBytes = path == "/chat" ? 64 * 1024 : 4 * 1024 * 1024
        Self.entry(for: certSha256).start(request, maxBytes: maxBytes, result: onResult)
    }

    func randomBytes(count: Int32) -> KotlinByteArray {
        var bytes = [UInt8](repeating: 0, count: Int(count))
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return UdpLink.kotlin(Data(bytes))
    }
}

final class KeychainStore: NSObject, SecretStoreDriver {
    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.meowmba.toto.merchtable", kSecAttrAccount as String: key]
    }
    func readSecret(key: String) -> String? {
        var q = query(key); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    func writeSecret(key: String, value: String) {
        SecItemDelete(query(key) as CFDictionary)
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
    func deleteSecret(key: String) { SecItemDelete(query(key) as CFDictionary) }
}


final class Clipboard: NSObject, ClipboardDriver {
    func readText() -> String? { UIPasteboard.general.string }
    func writeText(text: String) { UIPasteboard.general.string = text }
}

final class NativeTotoSensorDriver: NSObject, TotoSensorDriver, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureDepthDataOutputDelegate {
    static let shared = NativeTotoSensorDriver()

    private let motionManager = CMMotionManager()
    private let motionQueue = OperationQueue()
    private let videoQueue = DispatchQueue(label: "com.meowmba.toto.camera", qos: .userInitiated)
    private let lock = NSLock()

    private var sensorsRunning = false
    private var cameraRunning = false
    private var captureSession: AVCaptureSession?
    private var faceSink: FaceSink?
    private var lastFaceSeenTime: TimeInterval = 0.0

    // IMU & Proximity state
    private var pitchDeg: Double = 0.0
    private var rollDeg: Double = 0.0
    private var yawDeg: Double = 0.0
    private var joltG: Double = 0.0
    private var lastTapPeakTime: TimeInterval = 0.0
    private var doubleTapUntilTime: TimeInterval = 0.0
    private var proximityNear: Bool = false

    // Vision Face, TrueDepth 3D & Optical Clearance state
    private var lastVisionTime: TimeInterval = 0.0
    private var lastTrueDepthTime: TimeInterval = 0.0
    private var faceDetected: Bool = false
    private var faceBearingDeg: Double = 0.0
    private var facePitchDeg: Double = 0.0
    private var faceDistM: Double = 0.0
    private var optLeftMm: Int = 1200
    private var optCenterMm: Int = 1200
    private var optRightMm: Int = 1200
    private var tdLeftMm: Int = 1200
    private var tdCenterMm: Int = 1200
    private var tdRightMm: Int = 1200
    private var loomingTtcS: Double? = nil
    private var prevCenterBlocks: [Double] = []
    private var prevCenterGrad: Double = 0.0
    private var prevFrameTime: TimeInterval = 0.0
    private var latestJpegData: Data?
    private var lastFrameProcessTime: TimeInterval = 0.0
    private var lastFaceOrientation: CGImagePropertyOrientation? = nil
    private var missedFaceFrames: Int = 0
    private let ciContext = CIContext()

    override init() {
        super.init()
        motionQueue.name = "com.meowmba.toto.motion"
        motionQueue.maxConcurrentOperationCount = 1
        NotificationCenter.default.addObserver(
            forName: UIDevice.proximityStateDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            let near = UIDevice.current.proximityState
            self.lock.lock()
            self.proximityNear = near
            self.lock.unlock()
        }
    }

    func attachFaceSink(_ sink: FaceSink?) {
        lock.lock()
        self.faceSink = sink
        lock.unlock()
    }

    func request(
        method: String,
        host: String,
        port: Int32,
        path: String,
        body: KotlinByteArray?,
        certSha256: String,
        headers: [String: String],
        timeoutMs: Int32,
        onResult: @escaping (KotlinInt, KotlinByteArray?, String?) -> Void
    ) {
        guard certSha256.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil,
              host.range(of: "^[A-Za-z0-9.:-]+$", options: .regularExpression) != nil,
              (1...65535).contains(port), path.hasPrefix("/"), !path.hasPrefix("//") else {
            return onResult(KotlinInt(value: 0), nil, "invalid pinned HTTPS configuration")
        }
        let hostPart = host.contains(":") ? "[\(host)]" : host
        guard let url = URL(string: "https://\(hostPart):\(port)\(path)") else {
            return onResult(KotlinInt(value: 0), nil, "invalid pinned HTTPS address")
        }
        let timeout = TimeInterval(min(max(timeoutMs, 500), 15000)) / 1000.0
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method.uppercased()
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        if let body = body {
            req.httpBody = UdpLink.data(body)
        }
        PinnedHttp.entry(for: certSha256).start(req, maxBytes: 512 * 1024, result: onResult)
    }

    func setSensorsEnabled(enabled: Bool) {
        NativeDrivers.main { [weak self] in
            UIDevice.current.isProximityMonitoringEnabled = enabled
            let near = enabled ? UIDevice.current.proximityState : false
            if let self = self {
                self.lock.lock()
                self.proximityNear = near
                self.lock.unlock()
            }
            if enabled {
                UIApplication.shared.isIdleTimerDisabled = true
            }
        }
        lock.lock()
        let already = sensorsRunning
        sensorsRunning = enabled
        lock.unlock()

        if enabled && !already {
            if motionManager.isDeviceMotionAvailable {
                motionManager.deviceMotionUpdateInterval = 1.0 / 20.0
                motionManager.startDeviceMotionUpdates(to: motionQueue) { [weak self] motion, _ in
                    guard let self = self, let m = motion else { return }
                    let now = ProcessInfo.processInfo.systemUptime
                    let p = m.attitude.pitch * 180.0 / .pi
                    let r = m.attitude.roll * 180.0 / .pi
                    let y = m.attitude.yaw * 180.0 / .pi
                    let ua = m.userAcceleration
                    let g = sqrt(ua.x * ua.x + ua.y * ua.y + ua.z * ua.z)
                    self.lock.lock()
                    self.pitchDeg = p
                    self.rollDeg = r
                    self.yawDeg = y
                    self.joltG = max(self.joltG * 0.65, g)
                    if g >= 0.35 && g <= 1.45 {
                        if now - self.lastTapPeakTime >= 0.12 && now - self.lastTapPeakTime <= 0.65 {
                            self.doubleTapUntilTime = now + 1.5
                        }
                        self.lastTapPeakTime = now
                    }
                    self.lock.unlock()
                }
            }
        } else if !enabled && already {
            motionManager.stopDeviceMotionUpdates()
        }
    }

    func setCameraEnabled(enabled: Bool) {
        if enabled {
            NativeDrivers.main {
                UIApplication.shared.isIdleTimerDisabled = true
            }
        }
        videoQueue.async { [weak self] in
            guard let self = self else { return }
            if enabled {
                self.lock.lock()
                let running = self.cameraRunning && (self.captureSession?.isRunning == true)
                self.lock.unlock()
                if running { return }

                let status = AVCaptureDevice.authorizationStatus(for: .video)
                if status == .notDetermined {
                    AVCaptureDevice.requestAccess(for: .video) { granted in
                        if granted { self.setCameraEnabled(enabled: true) }
                    }
                    return
                }
                guard status == .authorized else { return }

                let session = AVCaptureSession()
                session.beginConfiguration()
                session.sessionPreset = .vga640x480
                let device = AVCaptureDevice.default(.builtInTrueDepthCamera, for: .video, position: .front)
                    ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
                    ?? AVCaptureDevice.default(for: .video)
                guard let cam = device, let input = try? AVCaptureDeviceInput(device: cam), session.canAddInput(input) else {
                    session.commitConfiguration()
                    return
                }
                session.addInput(input)

                let output = AVCaptureVideoDataOutput()
                output.alwaysDiscardsLateVideoFrames = true
                output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                output.setSampleBufferDelegate(self, queue: self.videoQueue)
                guard session.canAddOutput(output) else {
                    session.commitConfiguration()
                    return
                }
                session.addOutput(output)

                // Attach front TrueDepth 3D depth output when supported by hardware
                let depthOut = AVCaptureDepthDataOutput()
                depthOut.isFilteringEnabled = true
                depthOut.alwaysDiscardsLateDepthData = true
                depthOut.setDelegate(self, callbackQueue: self.videoQueue)
                if session.canAddOutput(depthOut) {
                    session.addOutput(depthOut)
                    if cam.activeFormat.supportedDepthDataFormats.isEmpty {
                        if let fmt = cam.formats.first(where: { f in
                            let dims = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
                            return !f.supportedDepthDataFormats.isEmpty && dims.width >= 320 && dims.width <= 1280
                        }), let dFmt = fmt.supportedDepthDataFormats.first(where: {
                            let sub = CMFormatDescriptionGetMediaSubType($0.formatDescription)
                            return sub == kCVPixelFormatType_DepthFloat32 || sub == kCVPixelFormatType_DepthFloat16
                        }) ?? fmt.supportedDepthDataFormats.first {
                            if (try? cam.lockForConfiguration()) != nil {
                                cam.activeFormat = fmt
                                cam.activeDepthDataFormat = dFmt
                                cam.unlockForConfiguration()
                            }
                        }
                    } else if let dFmt = cam.activeFormat.supportedDepthDataFormats.first(where: {
                        let sub = CMFormatDescriptionGetMediaSubType($0.formatDescription)
                        return sub == kCVPixelFormatType_DepthFloat32 || sub == kCVPixelFormatType_DepthFloat16
                    }) ?? cam.activeFormat.supportedDepthDataFormats.first {
                        if (try? cam.lockForConfiguration()) != nil {
                            cam.activeDepthDataFormat = dFmt
                            cam.unlockForConfiguration()
                        }
                    }
                }

                session.commitConfiguration()
                session.startRunning()
                self.lock.lock()
                self.captureSession = session
                self.cameraRunning = true
                self.lock.unlock()
            } else {
                self.lock.lock()
                let hasSink = (self.faceSink != nil)
                if hasSink {
                    self.lock.unlock()
                    return
                }
                let session = self.captureSession
                self.captureSession = nil
                self.cameraRunning = false
                self.latestJpegData = nil
                self.faceDetected = false
                self.lock.unlock()
                session?.stopRunning()
            }
        }
    }

    func depthDataOutput(_ output: AVCaptureDepthDataOutput, didOutput depthData: AVDepthData, timestamp: CMTime, connection: AVCaptureConnection) {
        let converted: AVDepthData
        if depthData.depthDataType != kCVPixelFormatType_DepthFloat32 {
            converted = depthData.converting(toDepthDataType: kCVPixelFormatType_DepthFloat32)
        } else {
            converted = depthData
        }
        let dMap = converted.depthDataMap
        CVPixelBufferLockBaseAddress(dMap, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(dMap, .readOnly) }

        let w = CVPixelBufferGetWidth(dMap)
        let h = CVPixelBufferGetHeight(dMap)
        let bpr = CVPixelBufferGetBytesPerRow(dMap)
        guard let base = CVPixelBufferGetBaseAddress(dMap), w >= 12, h >= 12 else { return }

        var zVals: [[Int]] = [[], [], []]
        let yStart = max(1, h / 5)
        let yEnd = min(h - 1, (h * 4) / 5)
        let stepY = max(1, (yEnd - yStart) / 14)
        let stepX = max(1, w / 21)

        for y in stride(from: yStart, to: yEnd, by: stepY) {
            let rowPtr = base.advanced(by: y * bpr).assumingMemoryBound(to: Float32.self)
            for x in stride(from: stepX, to: w - stepX, by: stepX) {
                let dMeters = rowPtr[x]
                if dMeters.isFinite && dMeters >= 0.05 && dMeters <= 3.8 {
                    let mm = Int((Double(dMeters) * 1000.0).rounded())
                    // Front camera depth buffer is mirrored horizontally relative to robot left/right
                    let z = x < w / 3 ? 0 : (x < (w * 2) / 3 ? 1 : 2)
                    zVals[z].append(mm)
                }
            }
        }

        var zOut = [1200, 1200, 1200]
        var validAny = false
        for z in 0..<3 {
            if zVals[z].count >= 4 {
                let sorted = zVals[z].sorted()
                // Use 20th-percentile closest obstacle distance in each zone
                let idx = max(0, min(sorted.count - 1, sorted.count / 5))
                zOut[z] = max(60, min(3200, sorted[idx]))
                validAny = true
            }
        }
        guard validAny else { return }
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        self.tdLeftMm = zOut[0]
        self.tdCenterMm = zOut[1]
        self.tdRightMm = zOut[2]
        self.lastTrueDepthTime = now
        lock.unlock()
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        let elapsed = now - lastFrameProcessTime
        if elapsed < 0.18 {
            lock.unlock()
            return
        }
        lastFrameProcessTime = now
        let curPitch = self.pitchDeg
        let curRoll = self.rollDeg
        lock.unlock()

        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }

        let width = CVPixelBufferGetWidth(pb)
        let height = CVPixelBufferGetHeight(pb)
        let bpr = CVPixelBufferGetBytesPerRow(pb)
        var zMm = [1200, 1200, 1200]
        var newTtcS: Double? = nil

        if let base = CVPixelBufferGetBaseAddress(pb), width >= 48, height >= 48 {
            let ptr = base.assumingMemoryBound(to: UInt8.self)
            // Build a 15x10 block-averaged luminance grid to eliminate pixel shot noise
            let cols = 15
            let rows = 10
            var grid = Array(repeating: 0.0, count: cols * rows)
            let cellW = max(2, width / cols)
            let cellH = max(2, height / rows)

            for r in 0..<rows {
                for c in 0..<cols {
                    var sumL = 0.0
                    var cnt = 0
                    let y0 = r * cellH + cellH / 4
                    let y1 = min(height - 1, (r + 1) * cellH - cellH / 4)
                    let x0 = c * cellW + cellW / 4
                    let x1 = min(width - 1, (c + 1) * cellW - cellW / 4)
                    let sy = max(1, (y1 - y0) / 3)
                    let sx = max(1, (x1 - x0) / 3)
                    for y in stride(from: y0, through: max(y0, y1), by: sy) {
                        for x in stride(from: x0, through: max(x0, x1), by: sx) {
                            let off = y * bpr + x * 4
                            let b = Double(ptr[off])
                            let g = Double(ptr[off + 1])
                            let red = Double(ptr[off + 2])
                            sumL += 0.299 * red + 0.587 * g + 0.114 * b
                            cnt += 1
                        }
                    }
                    grid[r * cols + c] = sumL / Double(max(1, cnt))
                }
            }

            // Compute per-zone (Left: cols 0..4, Center: cols 5..9, Right: cols 10..14)
            // mean luminance, macro standard deviation, and structural block gradient
            var centerBlocks: [Double] = []
            var centerGrad = 0.0
            for z in 0..<3 {
                let cStart = z * 5
                let cEnd = cStart + 5
                var vals: [Double] = []
                var gradSum = 0.0
                var gradCnt = 0
                for r in 1..<(rows - 1) {
                    for c in cStart..<cEnd {
                        let v = grid[r * cols + c]
                        vals.append(v)
                        if z == 1 { centerBlocks.append(v) }
                        if c + 1 < cEnd {
                            gradSum += abs(v - grid[r * cols + (c + 1)])
                            gradCnt += 1
                        }
                        if r + 1 < rows - 1 {
                            gradSum += abs(v - grid[(r + 1) * cols + c])
                            gradCnt += 1
                        }
                    }
                }
                let meanL = vals.reduce(0.0, +) / Double(max(1, vals.count))
                let variance = vals.reduce(0.0) { $0 + ($1 - meanL) * ($1 - meanL) } / Double(max(1, vals.count))
                let stdL = sqrt(variance)
                let meanGrad = gradSum / Double(max(1, gradCnt))
                if z == 1 { centerGrad = meanGrad }

                // Estimate optical clearance (mm) from macro structural gradient + contrast:
                // Close walls (dark, white, or painted) have very low block gradient and low macro contrast.
                if meanL < 18.0 && meanGrad < 2.8 {
                    zMm[z] = 170
                } else if meanGrad < 1.9 && stdL < 9.5 {
                    zMm[z] = 240
                } else if meanGrad < 2.9 && stdL < 14.5 {
                    zMm[z] = 310
                } else if meanGrad < 4.2 && stdL < 19.5 {
                    zMm[z] = 430
                } else {
                    zMm[z] = min(1800, max(470, Int((330.0 + meanGrad * 70.0 + stdL * 8.0).rounded())))
                }
            }

            // Temporal looming / optical expansion TTC on center zone
            lock.lock()
            let prevB = self.prevCenterBlocks
            let prevG = self.prevCenterGrad
            let dt = now - self.prevFrameTime
            self.prevCenterBlocks = centerBlocks
            self.prevCenterGrad = centerGrad
            self.prevFrameTime = now
            lock.unlock()

            if prevB.count == centerBlocks.count && !centerBlocks.isEmpty && dt >= 0.08 && dt <= 1.2 {
                var diffSum = 0.0
                for i in 0..<centerBlocks.count {
                    diffSum += abs(centerBlocks[i] - prevB[i])
                }
                let meanDiff = diffSum / Double(centerBlocks.count)
                let ratePerSec = meanDiff / dt
                // If approaching a low-detail wall with rapid optical change, estimate TTC in seconds
                if zMm[1] <= 460 && ratePerSec >= 18.0 {
                    let estTtc = max(0.25, min(2.5, Double(zMm[1]) / (ratePerSec * 14.0)))
                    newTtcS = estTtc
                } else if prevG > 3.5 && centerGrad < prevG * 0.72 && zMm[1] <= 440 {
                    newTtcS = 0.72
                }
            }
        }

        // Vision Face Detection across candidate orientations (supports portrait & landscape cradle mounting)
        var foundFace = false
        var bearing = 0.0
        var pitch = 0.0
        var dist = 0.0
        var uiFaceX: Float = 0.5
        var uiFaceY: Float = 0.5

        let baseCandidates: [CGImagePropertyOrientation]
        if abs(curRoll) > abs(curPitch) + 15.0 {
            baseCandidates = curRoll >= 0 ? [.up, .down, .leftMirrored, .rightMirrored] : [.down, .up, .leftMirrored, .rightMirrored]
        } else {
            baseCandidates = [.leftMirrored, .up, .rightMirrored, .down]
        }
        lock.lock()
        let lockedOrient = self.lastFaceOrientation
        let missStreak = self.missedFaceFrames
        lock.unlock()

        // Locked: probe the orientation that last matched (cheap steady-state tracking), and after a
        // short miss streak sweep the rest. Unlocked: sweep every candidate each frame — probing only
        // one orientation per frame left re-acquisition taking seconds when the phone's mount angle
        // made the IMU-preferred orientation wrong (LOOI head tilt), so the face never locked.
        var probes: [CGImagePropertyOrientation] = []
        if let pref = lockedOrient {
            probes.append(pref)
            if missStreak >= 2 {
                probes.append(contentsOf: baseCandidates.filter { $0 != pref })
            }
        } else {
            probes = baseCandidates
        }

        var matchedOrient: CGImagePropertyOrientation? = nil
        for orient in probes {
            let req = VNDetectFaceRectanglesRequest()
            let handler = VNImageRequestHandler(cvPixelBuffer: pb, orientation: orient, options: [:])
            if (try? handler.perform([req])) != nil,
               let results = req.results,
               let best = results.max(by: { $0.boundingBox.width * $0.boundingBox.height < $1.boundingBox.width * $1.boundingBox.height }) {
                let box = best.boundingBox
                let midX = Double(box.midX)
                let midY = Double(box.midY)
                // Vision normalized coordinates have origin at bottom-left (midY > 0.5 is upper half of image)
                // Front camera only: flip X so a person moving left is looked
                // at to the left (sensor/Vision space is not selfie-mirrored).
                bearing = (0.5 - midX) * 58.0
                pitch = (midY - 0.5) * 44.0
                let ratio = max(0.04, Double(max(box.width, box.height)))
                dist = max(0.22, min(3.5, 0.22 / ratio))
                uiFaceX = Float(1.0 - midX)
                uiFaceY = Float(1.0 - midY)
                foundFace = true
                matchedOrient = orient
                break
            }
        }
        lock.lock()
        if let matchedOrient = matchedOrient {
            self.lastFaceOrientation = matchedOrient
            self.missedFaceFrames = 0
        } else {
            self.missedFaceFrames = min(10_000, self.missedFaceFrames + 1)
            if self.missedFaceFrames >= 12 {
                self.lastFaceOrientation = nil
            }
        }
        lock.unlock()

        // If a face is clearly seen at normal conversational distance, avoid false blank-wall trigger from background
        if foundFace && dist > 0.0 {
            let faceMm = Int((dist * 1000.0).rounded())
            if faceMm >= 480 {
                zMm[1] = max(zMm[1], min(1400, faceMm))
            } else {
                zMm[1] = min(zMm[1], max(180, faceMm))
            }
        }

        // Feed on-screen avatar eye attention & FaceTracking sink
        lock.lock()
        if foundFace {
            self.lastFaceSeenTime = now
        }
        let sinceFace = now - self.lastFaceSeenTime
        let sink = self.faceSink
        lock.unlock()

        if foundFace {
            NativeDrivers.main {
                FaceAttention.shared.update(x: uiFaceX, y: uiFaceY)
                sink?.onFace(x: uiFaceX, y: uiFaceY)
            }
        } else if sinceFace > 1.2 {
            NativeDrivers.main {
                FaceAttention.shared.clear()
                sink?.onNoFace()
            }
        }

        // Encode JPEG (~1.1 Hz or when consumed)
        var jpeg: Data?
        lock.lock()
        let needJpeg = (self.latestJpegData == nil)
        lock.unlock()
        if needJpeg {
            let ciImage = CIImage(cvPixelBuffer: pb)
            if let cgImage = ciContext.createCGImage(ciImage, from: CGRect(x: 0, y: 0, width: width, height: height)) {
                let uiImage = UIImage(cgImage: cgImage)
                if let data = uiImage.jpegData(compressionQuality: 0.68), data.count <= 350_000 {
                    jpeg = data
                }
            }
        }

        lock.lock()
        self.lastVisionTime = now
        self.faceDetected = foundFace
        self.faceBearingDeg = bearing
        self.facePitchDeg = pitch
        self.faceDistM = dist
        self.optLeftMm = zMm[0]
        self.optCenterMm = zMm[1]
        self.optRightMm = zMm[2]
        self.loomingTtcS = newTtcS
        if let jpeg = jpeg { self.latestJpegData = jpeg }
        lock.unlock()
    }

    func popLatestCameraJpeg() -> KotlinByteArray? {
        lock.lock()
        let data = latestJpegData
        latestJpegData = nil
        lock.unlock()
        guard let d = data else { return nil }
        return UdpLink.kotlin(d)
    }

    /// Continuous on-device listening for the mounted phone; finals go to Kotlin via `sink`.
    func setSpeechListening(enabled: Bool, sink: SpeechTranscriptSink?) {
        ContinuousSpeechListener.shared.setEnabled(enabled, sink: sink)
    }

    /// The camera's latest face detection, only if the vision frame is fresh (same rule as `snapshotJson`).
    func freshFaceDetected() -> Bool {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        let fresh = lastVisionTime > 0 && (now - lastVisionTime) <= 2.5
        let det = faceDetected
        lock.unlock()
        return fresh && det
    }

    func snapshotJson(mounted: Bool) -> String {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        let proxNear = proximityNear
        let p = pitchDeg
        let r = rollDeg
        let y = yawDeg
        let jg = joltG
        joltG *= 0.5
        let tap = now <= doubleTapUntilTime ? "double_tap" : nil
        let visFresh = lastVisionTime > 0 && (now - lastVisionTime) <= 2.5
        let tdFresh = lastTrueDepthTime > 0 && (now - lastTrueDepthTime) <= 1.8
        let fDet = faceDetected
        let fBear = faceBearingDeg
        let fPitch = facePitchDeg
        let fDist = faceDistM
        let lOpt = optLeftMm
        let cOpt = optCenterMm
        let rOpt = optRightMm
        let lTd = tdLeftMm
        let cTd = tdCenterMm
        let rTd = tdRightMm
        let ttc = loomingTtcS
        lock.unlock()

        // Fuse TrueDepth 3D millimeter depth (when active) with optical wall-clearance
        let lMm = tdFresh ? min(lTd, max(280, lOpt)) : lOpt
        let cMm = tdFresh ? min(cTd, max(280, cOpt)) : cOpt
        let rMm = tdFresh ? min(rTd, max(280, rOpt)) : rOpt

        var imu: [String: Any] = [
            "pitch_deg": (p * 10.0).rounded() / 10.0,
            "roll_deg": (r * 10.0).rounded() / 10.0,
            "compass_yaw_deg": (y * 10.0).rounded() / 10.0,
            "jolt_g": (jg * 100.0).rounded() / 100.0,
        ]
        if let tap = tap { imu["tap_gesture"] = tap }

        var guardDict: [String: Any] = [
            "proximity_available": true,
            "proximity_near": proxNear,
        ]
        if mounted {
            let anyFresh = visFresh || tdFresh
            let minZoneMm = min(cMm, min(lMm, rMm) + 40)
            let effMm = proxNear ? 60 : (anyFresh ? minZoneMm : 1200)
            guardDict["screen_distance_mm"] = effMm
            guardDict["left_clearance_mm"] = anyFresh ? lMm : effMm
            guardDict["center_clearance_mm"] = effMm
            guardDict["right_clearance_mm"] = anyFresh ? rMm : effMm
            if let ttc = ttc, anyFresh {
                guardDict["looming_ttc_s"] = (ttc * 100.0).rounded() / 100.0
            }
        }

        var visionDict: [String: Any] = [
            "face_detected": visFresh && fDet,
            "looking_at_toto": visFresh && fDet,
            "face_bearing_deg": visFresh ? (fBear * 10.0).rounded() / 10.0 : 0.0,
            "face_pitch_deg": visFresh ? (fPitch * 10.0).rounded() / 10.0 : 0.0,
        ]
        if visFresh && fDet && fDist > 0 {
            visionDict["face_dist_m"] = (fDist * 100.0).rounded() / 100.0
        }

        let sensorSum = String(format: "Proximity: %@ · pitch %.0f° roll %.0f° yaw %.0f°",
                               proxNear ? "near" : "far", p, r, y)
        let depthTag = tdFresh ? "TrueDepth+Opt" : "Optical"
        let visionSum: String
        if !visFresh && !tdFresh {
            visionSum = "Camera vision: starting front camera…"
        } else if fDet {
            visionSum = String(format: "Face tracked: bearing %+.1f° · pitch %+.1f° · ~%.2fm · %@ %dmm",
                               fBear, fPitch, fDist, depthTag, cMm)
        } else {
            visionSum = String(format: "No face · %@ wall clearance L:%d C:%d R:%d mm",
                               depthTag, lMm, cMm, rMm)
        }

        let root: [String: Any] = [
            "mounted": mounted,
            "imu": imu,
            "screen_guard": guardDict,
            "vision": visionDict,
            "sensor_summary": sensorSum,
            "vision_summary": visionSum,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: []),
              let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }
}

/// On-device Kokoro (sherpa-onnx) for Toto's original merch voice.
/// The int8 pack is downloaded on first run into Application Support (same
/// archive as Android). The iPhone's built-in voice is only used if install
/// or synthesis fails. Lion is not used.
final class TotoKokoroTts: OnDeviceTtsDriver {
    private let queue = DispatchQueue(label: "meow.toto.kokoro", qos: .userInitiated)
    private let ttsLock = NSLock()
    private var tts: SherpaOnnxOfflineTtsWrapper?
    // Keep path strings alive for the C API pointers passed at engine creation.
    private var modelPath = ""
    private var voicesPath = ""
    private var tokensPath = ""
    private var dataDir = ""

    var ready: Bool { TotoKokoroPack.ready }

    init() {
        // Download/verify the pack and load the ~126 MB model once, off the main thread.
        // Failures stay non-fatal: render() returns nil and Kotlin falls back to Apple voice.
        queue.async { [self] in
            do {
                try TotoKokoroPack.install { _, _ in }
                if TotoKokoroPack.ready { _ = try self.engine() }
            } catch {
                NSLog("[TotoSpeech] Kokoro warmup failed (will fall back to Apple voice): \(error.localizedDescription)")
            }
        }
    }

    func render(text: String, voiceId: String, speaker: Int32, speed: Float, onResult: @escaping (KotlinByteArray?) -> Void) {
        let spoken = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !spoken.isEmpty else { onResult(nil); return }
        queue.async { [self] in
            do {
                if !TotoKokoroPack.ready {
                    try TotoKokoroPack.install { _, _ in }
                }
                let wav = try self.synthesize(text: spoken, speaker: Int(speaker), speed: speed)
                onResult(TotoKokoroPack.kotlinBytes(wav))
            } catch {
                onResult(nil)
            }
        }
    }

    private func engine() throws -> SherpaOnnxOfflineTtsWrapper {
        ttsLock.lock()
        defer { ttsLock.unlock() }
        if let tts { return tts }
        guard TotoKokoroPack.ready else {
            throw TotoKokoroPack.PackError.message("Download the local voice pack first")
        }
        let root = TotoKokoroPack.packDir
        let int8 = root.appendingPathComponent("model.int8.onnx")
        let fp32 = root.appendingPathComponent("model.onnx")
        if TotoKokoroPack.fileSize(int8) > 80_000_000 {
            modelPath = int8.path
        } else if TotoKokoroPack.fileSize(fp32) > 80_000_000 {
            modelPath = fp32.path
        } else {
            throw TotoKokoroPack.PackError.message("Download the local voice pack first")
        }
        let voicesURL = root.appendingPathComponent("voices.bin")
        // Repair an older 58-speaker voices.bin before sherpa can abort the process.
        try TotoKokoroPack.ensureBlends()
        guard TotoKokoroPack.voicesSpeakerCount(voicesURL) == TotoKokoroPack.officialSpeakers else {
            throw TotoKokoroPack.PackError.message("On-phone voice pack is the wrong size; using Apple voice")
        }
        voicesPath = voicesURL.path
        tokensPath = root.appendingPathComponent("tokens.txt").path
        dataDir = root.appendingPathComponent("espeak-ng-data", isDirectory: true).path
        let kokoro = sherpaOnnxOfflineTtsKokoroModelConfig(
            model: modelPath,
            voices: voicesPath,
            tokens: tokensPath,
            dataDir: dataDir,
            lexicon: "",
            lang: "en-GB-x-rp"
        )
        let model = sherpaOnnxOfflineTtsModelConfig(kokoro: kokoro, numThreads: 2, debug: 0, provider: "cpu")
        var cfg = sherpaOnnxOfflineTtsConfig(model: model, maxNumSentences: 1, silenceScale: 0.15)
        let wrapper = withUnsafePointer(to: &cfg) { SherpaOnnxOfflineTtsWrapper(config: $0) }
        guard wrapper.tts != nil else {
            throw TotoKokoroPack.PackError.message("Could not start Toto's on-phone voice")
        }
        tts = wrapper
        return wrapper
    }

    private func synthesize(text: String, speaker: Int, speed: Float) throws -> Data {
        let engine = try engine()
        let runSpeed = max(0.5, min(2.0, speed))
        let audio = engine.generate(text: text, sid: TotoKokoroPack.sherpaSpeaker(speaker), speed: runSpeed)
        let samples = audio.samples
        guard !samples.isEmpty else { throw TotoKokoroPack.PackError.message("Local voice returned no audio") }
        return TotoKokoroPack.pcmWav(samples: samples, sampleRate: audio.sampleRate)
    }
}

