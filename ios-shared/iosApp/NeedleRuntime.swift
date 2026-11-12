import Foundation
import CryptoKit
import Needle
import SharedKit

/// Cactus Needle 3 on the phone: the same pinned engine and weights as Android.
/// The engine owns one process-global, non-thread-safe model, so every call runs on one queue.
final class NeedleRuntime: NSObject, NeedleDriver {
    private static let url = URL(string: "https://huggingface.co/Cactus-Compute/needle3/resolve/c1fc4d4cb32993156a880ceb8ff171b03b1f166a/needle3.cact")!
    private static let sha256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38"
    private let queue = DispatchQueue(label: "meowmba.needle", qos: .userInitiated)
    private var weights: Data?          // needle_load reads the bytes in place; keep them alive.
    private var schema: Int?
    private var failure: String?
    private var downloading = false
    private var session: URLSession?

    private var file: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("needle3.cact")
    }

    func status() -> String {
        if downloading { return "downloading" }
        if let failure { return "failed: " + failure }
        return FileManager.default.fileExists(atPath: file.path) ? "ready" : "missing"
    }

    func download(onProgress: @escaping (KotlinFloat) -> Void, onDone: @escaping (KotlinBoolean, String) -> Void) {
        guard !downloading else { return }
        downloading = true; failure = nil
        let finish: (Bool, String) -> Void = { ok, msg in
            DispatchQueue.main.async {
                self.downloading = false
                if !ok { self.failure = msg }
                onDone(KotlinBoolean(value: ok), msg)
            }
        }
        let delegate = Progress(onProgress: { p in DispatchQueue.main.async { onProgress(KotlinFloat(value: p)) } }) { tmp, error in
            guard let tmp else { return finish(false, "Download failed: \(error?.localizedDescription ?? "no file")") }
            guard let data = try? Data(contentsOf: tmp, options: .mappedIfSafe),
                  SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == Self.sha256 else {
                return finish(false, "The download didn't match Needle 3's published checksum, so it was discarded.")
            }
            try? FileManager.default.removeItem(at: self.file)
            do { try FileManager.default.moveItem(at: tmp, to: self.file); finish(true, "") }
            catch { finish(false, "Couldn't save Needle: \(error.localizedDescription)") }
        }
        session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
        session?.downloadTask(with: Self.url).resume()
    }

    func delete() {
        queue.sync { weights = nil; schema = nil; needle_reset() }
        try? FileManager.default.removeItem(at: file)
        failure = nil
    }

    func route(systemPrompt: String, toolsJson: String, input: String, onResult: @escaping (String?) -> Void) {
        queue.async {
            let out = self.complete(systemPrompt, toolsJson, input)
#if DEBUG
            let log = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("needle_last.txt")
            try? "\(input)\n→ \(out ?? "nil")\n".write(to: log, atomically: true, encoding: .utf8)
#endif
            DispatchQueue.main.async { onResult(out) }
        }
    }

    private func complete(_ system: String, _ tools: String, _ input: String) -> String? {
        if weights == nil {
            guard let data = try? Data(contentsOf: file, options: .alwaysMapped) else { return nil }
            let loaded = data.withUnsafeBytes { raw in
                needle_load(raw.bindMemory(to: UInt8.self).baseAddress, UInt64(raw.count))
            }
            guard loaded == 0 else { failure = "Needle couldn't load its weights (\(loaded))."; return nil }
            weights = data; schema = nil
        }
        let key = (system + tools).hashValue
        if schema != key {
            // Non-negative = cached-prefix token count.
            guard needle_init(system, tools, nil) >= 0 else { return nil }
            schema = key
        }
        var buffer = [CChar](repeating: 0, count: 16_384)
        let n = needle_complete(input, 256, &buffer, Int32(buffer.count))
        guard n >= 0 else { return nil }
        return String(cString: buffer)
    }


    private final class Progress: NSObject, URLSessionDownloadDelegate {
        let onProgress: (Float) -> Void
        let onDone: (URL?, Error?) -> Void
        private var done = false
        init(onProgress: @escaping (Float) -> Void, onDone: @escaping (URL?, Error?) -> Void) {
            self.onProgress = onProgress; self.onDone = onDone
        }
        func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten w: Int64, totalBytesExpectedToWrite t: Int64) {
            if t > 0 { onProgress(Float(w) / Float(t)) }
        }
        func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
            // The system deletes [location] when this returns, so keep a copy first.
            let keep = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try? FileManager.default.moveItem(at: location, to: keep)
            done = true; onDone(keep, nil); s.finishTasksAndInvalidate()
        }
        func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            if !done { done = true; onDone(nil, error); s.finishTasksAndInvalidate() }
        }
    }
}
