import Citadel
import Crypto
import Foundation
import NIOCore
import NIOSSH
import SharedKit

/// Android's JSch terminal on iOS: an SSH PTY shell via Citadel (SwiftNIO SSH).
/// The server's host key is pinned on first connect; a changed key is refused.
final class SshTerminal: NSObject, SshDriver {
    private final class Session {
        var task: Task<Void, Never>?
        var writer: TTYStdinWriter?
        var client: SSHClient?
    }
    private var sessions: [String: Session] = [:]
    private let lock = NSLock()

    private final class Pin: NIOSSHClientServerAuthenticationDelegate {
        let expected: String
        let seen: (String) -> Void
        init(expected: String, seen: @escaping (String) -> Void) { self.expected = expected.lowercased(); self.seen = seen }
        func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
            let fingerprint = SHA256.hash(data: Data(String(openSSHPublicKey: hostKey).utf8)).map { String(format: "%02x", $0) }.joined()
            if expected.isEmpty { seen(fingerprint); validationCompletePromise.succeed(()) }
            else if fingerprint == expected { validationCompletePromise.succeed(()) }
            else { validationCompletePromise.fail(SshError("The server's host key changed since it was pinned — refusing to connect.")) }
        }
    }

    struct SshError: Error, CustomStringConvertible { let description: String; init(_ d: String) { description = d } }

    private func session(_ id: String) -> Session? { lock.lock(); defer { lock.unlock() }; return sessions[id] }

    func open(id: String, host: String, port: Int32, user: String, password: String, privateKey: String,
              expectedFingerprint: String, onEvent: @escaping (String, String) -> Void) {
        let emit: (String, String) -> Void = { kind, payload in DispatchQueue.main.async { onEvent(kind, payload) } }
        let s = Session()
        lock.lock(); sessions[id] = s; lock.unlock()
        s.task = Task {
            do {
                let auth: SSHAuthenticationMethod
                if !privateKey.isEmpty {
                    if let key = try? Curve25519.Signing.PrivateKey(sshEd25519: privateKey) { auth = .ed25519(username: user, privateKey: key) }
                    else if let key = try? Insecure.RSA.PrivateKey(sshRsa: privateKey) { auth = .rsa(username: user, privateKey: key) }
                    else { throw SshError("That private key couldn't be read. Use an unencrypted OpenSSH ed25519 or RSA key.") }
                } else { auth = .passwordBased(username: user, password: password) }
                let validator = SSHHostKeyValidator.custom(Pin(expected: expectedFingerprint) { emit("hostkey", $0) })
                let client = try await SSHClient.connect(host: host, port: Int(port), authenticationMethod: auth,
                                                         hostKeyValidator: validator, reconnect: .never, connectTimeout: .seconds(15))
                s.client = client
                emit("connected", "")
                let request = SSHChannelRequestEvent.PseudoTerminalRequest(wantReply: true, term: "xterm", terminalCharacterWidth: 80,
                    terminalRowHeight: 24, terminalPixelWidth: 0, terminalPixelHeight: 0, terminalModes: .init([.ECHO: 1]))
                try await client.withPTY(request) { inbound, outbound in
                    s.writer = outbound
                    for try await chunk in inbound {
                        switch chunk {
                        case .stdout(let buffer), .stderr(let buffer): emit("output", String(buffer: buffer))
                        }
                    }
                }
                try? await client.close()
                emit("closed", "session ended")
            } catch is CancellationError {
                emit("closed", "disconnected")
            } catch {
                emit("error", SshTerminal.describe(error))
            }
            self.lock.lock(); self.sessions[id] = nil; self.lock.unlock()
        }
    }

    func write(id: String, text: String) {
        guard let s = session(id), let writer = s.writer else { return }
        Task { try? await writer.write(ByteBuffer(string: text)) }
    }

    func resize(id: String, cols: Int32, rows: Int32) {
        guard let writer = session(id)?.writer else { return }
        Task { try? await writer.changeSize(cols: Int(cols), rows: Int(rows), pixelWidth: 0, pixelHeight: 0) }
    }

    func close(id: String) {
        guard let s = session(id) else { return }
        Task { try? await s.client?.close(); s.task?.cancel() }
    }

    private static func describe(_ error: Error) -> String {
        let text = String(describing: error)
        if text.contains("allAuthenticationOptionsFailed") || text.contains("authentication") { return "Sign-in was refused. Check the user name and password or key." }
        if text.contains("connectTimeout") || text.contains("timeout") { return "The server didn't answer. Check the address and that SSH is on." }
        if text.contains("connectionRefused") || text.contains("Connection refused") { return "The server refused the connection (is SSH running on that port?)." }
        return text.count > 240 ? String(text.prefix(240)) + "…" : text
    }
}
