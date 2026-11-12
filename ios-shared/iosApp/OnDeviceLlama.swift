import Foundation
import CryptoKit
import UIKit
import SharedKit
import llama

/// A single bounded inference job, entirely inside this process. No localhost server.
final class OnDeviceLlama: NSObject, OnDeviceDriver {
    private let queue = DispatchQueue(label: "meow.local.inference", qos: .userInitiated)
    private let lock = NSLock()
    private var stopped = false
    private var busy = false
    private var download: URLSessionDownloadTask?
    private var progressObservation: NSKeyValueObservation?
    // The selected catalog model (Kotlin OnDeviceCatalog); Qwen3 0.6B until `select` says otherwise.
    private var byteCount: Int64 = 639446688
    private var digest = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
    private var remoteURL = URL(string: "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf")!
    private static let folder: URL = {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Models", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var url = folder; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return folder
    }()
    private var modelURL = OnDeviceLlama.folder.appendingPathComponent("Qwen3-0.6B-Q8_0.gguf")

    func select(fileName: String, url: String, bytes: Int64, sha256: String) {
        lock.lock(); defer { lock.unlock() }
        guard let remote = URL(string: url), !fileName.contains("/") else { return }
        modelURL = Self.folder.appendingPathComponent(fileName)
        remoteURL = remote; byteCount = bytes; digest = sha256.lowercased()
    }
    func installedFiles() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: Self.folder.path)) ?? []).filter { $0.hasSuffix(".gguf") }
    }
    func delete(fileName: String) {
        guard !fileName.contains("/") else { return }
        try? FileManager.default.removeItem(at: Self.folder.appendingPathComponent(fileName))
    }
    override init() {
        super.init()
        llama_backend_init()
        NotificationCenter.default.addObserver(self, selector: #selector(background),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(background),
            name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    }
    @objc private func background() { cancel() }
    func isInstalled() -> Bool {
        let size = (try? FileManager.default.attributesOfItem(atPath: modelURL.path)[.size] as? NSNumber)?.int64Value
        return size == byteCount
    }
    func install(onProgress: @escaping (KotlinFloat) -> Void,
                 onComplete: @escaping () -> Void, onError: @escaping (String) -> Void) {
        lock.lock()
        if download != nil { lock.unlock(); onError("A model download is already running."); return }
        if isInstalled() { lock.unlock(); onComplete(); return }
        let url = remoteURL; let expectedBytes = byteCount; let expectedDigest = digest; let target = modelURL
        let task = URLSession.shared.downloadTask(with: url) { [weak self] temporary, response, error in
            guard let self else { return }
            defer { self.lock.lock(); self.download = nil; self.progressObservation = nil; self.lock.unlock() }
            do {
                if let error { throw error }
                guard let temporary, let response = response as? HTTPURLResponse,
                      response.statusCode == 200 else { throw LocalFailure.message("Model download failed. Please retry.") }
                let handle = try FileHandle(forReadingFrom: temporary); defer { try? handle.close() }
                var hash = SHA256(); var count: Int64 = 0
                while let data = try handle.read(upToCount: 1_048_576), !data.isEmpty {
                    hash.update(data: data); count += Int64(data.count)
                }
                let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
                guard count == expectedBytes && digest == expectedDigest else {
                    throw LocalFailure.message("Model integrity check failed. The download was not installed.")
                }
                if FileManager.default.fileExists(atPath: target.path) {
                    _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
                } else {
                    try FileManager.default.moveItem(at: temporary, to: target)
                }
                var destination = target; var values = URLResourceValues(); values.isExcludedFromBackup = true
                try destination.setResourceValues(values)
                onComplete()
            } catch { onError(error.localizedDescription) }
        }
        download = task
        progressObservation = task.progress.observe(\.fractionCompleted) { value, _ in
            onProgress(KotlinFloat(float: Float(value.fractionCompleted)))
        }
        lock.unlock(); task.resume()
    }
    func cancel() { lock.lock(); stopped = true; lock.unlock() }
    func unload() { cancel() } // Each turn releases all model/context memory below.
    private func check() throws {
        lock.lock(); let cancelled = stopped; lock.unlock()
        if cancelled { throw LocalFailure.message("Generation stopped.") }
        if ProcessInfo.processInfo.thermalState == .serious || ProcessInfo.processInfo.thermalState == .critical {
            throw LocalFailure.message("The phone is warm. Let it cool before trying again.")
        }
    }
    func generate(prompt: String, onToken: @escaping (String) -> Void,
                  onComplete: @escaping () -> Void, onError: @escaping (String) -> Void) {
        lock.lock()
        guard !busy else { lock.unlock(); onError("The local model is already answering."); return }
        busy = true; stopped = false; lock.unlock()
        queue.async { [self] in
            let failure: Error?
            do { try infer(prompt, onToken: onToken); failure = nil }
            catch { failure = error }
            lock.lock(); busy = false; lock.unlock()
            if let failure { onError(failure.localizedDescription) } else { onComplete() }
        }
    }
    private func infer(_ prompt: String, onToken: (String) -> Void) throws {
        try check()
        guard isInstalled() else { throw LocalFailure.message("Download the selected model in LLM first.") }
        var modelParams = llama_model_default_params()
#if targetEnvironment(simulator)
        modelParams.n_gpu_layers = 0
#else
        modelParams.n_gpu_layers = 99
#endif
        guard let model = llama_model_load_from_file(modelURL.path, modelParams) else {
            throw LocalFailure.message("Unable to load the local model. Check available memory.")
        }
        defer { llama_model_free(model) }
        var params = llama_context_default_params()
        params.n_ctx = 2048; params.n_batch = 128; params.n_ubatch = 128
        params.n_threads = 2; params.n_threads_batch = 2
        guard let context = llama_init_from_model(model, params) else { throw LocalFailure.message("Insufficient memory for local inference.") }
        defer { llama_free(context) }
        let vocab = llama_model_get_vocab(model)!
        let framed = Self.frame(prompt, model: model, qwen3: modelURL.lastPathComponent.hasPrefix("Qwen3"))
        let needed = framed.withCString { -llama_tokenize(vocab, $0, Int32(framed.utf8.count), nil, 0, true, true) }
        guard needed > 0 else { throw LocalFailure.message("Unable to tokenize the prompt.") }
        var tokens = [llama_token](repeating: 0, count: Int(needed))
        let count = framed.withCString { llama_tokenize(vocab, $0, Int32(framed.utf8.count), &tokens, needed, true, true) }
        guard count > 0 else { throw LocalFailure.message("Unable to tokenize the prompt.") }
        tokens = Array(tokens.prefix(Int(count)))
        if tokens.count > 1792 { tokens = Array(tokens.prefix(256)) + Array(tokens.suffix(1536)) }
        var batch = llama_batch_init(128, 0, 1); defer { llama_batch_free(batch) }
        func evaluate(_ ids: ArraySlice<llama_token>, position: Int) throws {
            try check(); batch.n_tokens = Int32(ids.count)
            for (i, token) in ids.enumerated() {
                batch.token[i] = token; batch.pos[i] = Int32(position + i)
                batch.n_seq_id[i] = 1; batch.seq_id[i]![0] = 0
                batch.logits[i] = i == ids.count - 1 ? 1 : 0
            }
            guard llama_decode(context, batch) == 0 else { throw LocalFailure.message("The local model could not evaluate this turn.") }
        }
        for offset in stride(from: 0, to: tokens.count, by: 128) {
            try evaluate(tokens[offset..<min(offset + 128, tokens.count)], position: offset)
        }
        let sampler = llama_sampler_chain_init(llama_sampler_chain_default_params())!
        defer { llama_sampler_free(sampler) }
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20))
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.8, 1))
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7))
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(UInt32.random(in: 0..<UInt32.max)))
        var pending = Data(); let start = Date()
        for i in 0..<256 {
            try check()
            if Date().timeIntervalSince(start) > 120 { throw LocalFailure.message("Local reply time limit reached.") }
            let token = llama_sampler_sample(sampler, context, -1)
            if llama_vocab_is_eog(vocab, token) { break }
            var bytes = [CChar](repeating: 0, count: 256)
            var length = llama_token_to_piece(vocab, token, &bytes, Int32(bytes.count), 0, false)
            if length < 0 {
                bytes = [CChar](repeating: 0, count: Int(-length))
                length = llama_token_to_piece(vocab, token, &bytes, Int32(bytes.count), 0, false)
            }
            if length > 0 { pending.append(contentsOf: bytes.prefix(Int(length)).map { UInt8(bitPattern: $0) }) }
            if let text = String(data: pending, encoding: .utf8) { onToken(text); pending.removeAll(keepingCapacity: true) }
            try evaluate([token][...], position: tokens.count + i)
        }
    }
}
extension OnDeviceLlama {
    /// The model's own chat template from its GGUF metadata (Llama, Gemma, Phi, SmolLM, Qwen…).
    /// Qwen3 keeps thinking off for short mobile replies, as before.
    static func frame(_ prompt: String, model: OpaquePointer, qwen3: Bool) -> String {
        if qwen3 {
            return "<|im_start|>user\n" + prompt + "\n/no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        }
        guard let template = llama_model_chat_template(model, nil) else {
            return "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n"
        }
        return "user".withCString { role in
            prompt.withCString { content in
                var message = llama_chat_message(role: role, content: content)
                var buffer = [CChar](repeating: 0, count: prompt.utf8.count * 2 + 512)
                var length = llama_chat_apply_template(template, &message, 1, true, &buffer, Int32(buffer.count))
                if length > Int32(buffer.count) {
                    buffer = [CChar](repeating: 0, count: Int(length) + 1)
                    length = llama_chat_apply_template(template, &message, 1, true, &buffer, Int32(buffer.count))
                }
                guard length > 0 else { return prompt }
                return String(decoding: buffer.prefix(Int(length)).map { UInt8(bitPattern: $0) }, as: UTF8.self)
            }
        }
    }
}
private enum LocalFailure: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
