import Foundation
import Network
import SharedKit

/// Line-protocol LOOI body over TCP (S / E / PING), matching meowmba-app LooiProtocol.
final class LooiTcpLink: LooiTcpDriver {
    private let queue = DispatchQueue(label: "meowmba.looi.tcp")
    private var connection: NWConnection?
    private var isConnectedFlag = false
    private var reported = false

    var connected: Bool { isConnectedFlag }

    func connect(host: String, port: Int32, onResult: @escaping (KotlinBoolean, String) -> Void) {
        disconnect()
        reported = false
        guard let portNW = NWEndpoint.Port(rawValue: UInt16(clamping: UInt(port))) else {
            NativeDrivers.main { onResult(KotlinBoolean(value: false), "Invalid port") }
            return
        }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: portNW, using: .tcp)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.isConnectedFlag = true
                self.reportOnce(onResult, ok: true, status: "LOOI body @ \(host):\(port)")
            case .failed(let error):
                self.isConnectedFlag = false
                self.connection = nil
                self.reportOnce(onResult, ok: false,
                    status: "LOOI unreachable (\(host):\(port)) — \(error.localizedDescription)")
            case .cancelled:
                self.isConnectedFlag = false
            default:
                break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self, !self.isConnectedFlag else { return }
            self.reportOnce(onResult, ok: false, status: "LOOI unreachable (\(host):\(port)) — timed out")
            self.disconnect()
        }
    }

    func disconnect() {
        isConnectedFlag = false
        connection?.cancel()
        connection = nil
    }

    func sendLine(line: String) {
        guard isConnectedFlag, let connection else { return }
        var data = Data(line.utf8)
        if !line.hasSuffix("\n") { data.append(0x0a) }
        connection.send(content: data, completion: .contentProcessed { _ in })
    }

    private func reportOnce(_ onResult: @escaping (KotlinBoolean, String) -> Void, ok: Bool, status: String) {
        queue.async {
            if self.reported { return }
            self.reported = true
            NativeDrivers.main { onResult(KotlinBoolean(value: ok), status) }
        }
    }
}
