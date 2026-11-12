import Foundation
import CryptoKit
import SharedKit

/// Downloads, verifies, unpacks, and blends the official sherpa-onnx Kokoro int8 pack
/// the same way Android `LocalVoicePack` does. Lives under Application Support.
enum TotoKokoroPack {
    static let packName = "kokoro-int8-multi-lang-v1_0"
    static let archiveURL = URL(string: "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2")!
    static let sha256 = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"
    static let archiveBytes: Int64 = 132_353_410
    static let officialSpeakers = 54
    static let styleFrames = 510
    static let styleDim = 256

    private static let installLock = NSLock()

    static var packDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("voice_models", isDirectory: true)
            .appendingPathComponent(packName, isDirectory: true)
    }

    static var ready: Bool {
        let root = packDir
        let model = fileSize(root.appendingPathComponent("model.int8.onnx"))
        let voices = fileSize(root.appendingPathComponent("voices.bin"))
        let tokens = FileManager.default.fileExists(atPath: root.appendingPathComponent("tokens.txt").path)
        let espeak = FileManager.default.fileExists(atPath: root.appendingPathComponent("espeak-ng-data", isDirectory: true).path)
        let verified = (try? String(contentsOf: root.appendingPathComponent(".verified"), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) == sha256
        let speakers = voicesSpeakerCount(root.appendingPathComponent("voices.bin"))
        // sherpa-onnx fatals if voices.bin is not exactly officialSpeakers long.
        return verified && model > 80_000_000 && voices > 20_000_000 && tokens && espeak && speakers == officialSpeakers
    }

    private static var packFilesPresent: Bool {
        let root = packDir
        let model = fileSize(root.appendingPathComponent("model.int8.onnx")) > 80_000_000
            || fileSize(root.appendingPathComponent("model.onnx")) > 80_000_000
        let voices = voicesSpeakerCount(root.appendingPathComponent("voices.bin")) >= officialSpeakers
        let tokens = FileManager.default.fileExists(atPath: root.appendingPathComponent("tokens.txt").path)
        let espeak = FileManager.default.fileExists(atPath: root.appendingPathComponent("espeak-ng-data", isDirectory: true).path)
        return model && voices && tokens && espeak
    }

    static func fileSize(_ url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value) ?? 0
    }

    private static var speakerBytes: Int { styleFrames * styleDim * 4 }

    static func voicesSpeakerCount(_ voices: URL) -> Int {
        let len = fileSize(voices)
        guard len > 0, len % Int64(speakerBytes) == 0 else { return 0 }
        return Int(len / Int64(speakerBytes))
    }

    struct BlendPart { let speaker: Int; let weight: Float }
    struct Blend { let id: String; let parts: [BlendPart] }

    /// Must stay in lockstep with TotoVoiceConfig.CANDIDATES on both apps.
    static let blends: [Blend] = [
        Blend(id: "toto_british_baritone_bright", parts: [
            BlendPart(speaker: 27, weight: 0.7767),
            BlendPart(speaker: 26, weight: 0.0742),
            BlendPart(speaker: 24, weight: 0.0291),
            BlendPart(speaker: 25, weight: 0.12),
        ]),
        Blend(id: "toto_warm_shopkeeper", parts: [
            BlendPart(speaker: 24, weight: 0.42),
            BlendPart(speaker: 16, weight: 0.33),
            BlendPart(speaker: 15, weight: 0.25),
        ]),
        Blend(id: "toto_bright_helper", parts: [
            BlendPart(speaker: 18, weight: 0.40),
            BlendPart(speaker: 12, weight: 0.30),
            BlendPart(speaker: 26, weight: 0.30),
        ]),
        Blend(id: "toto_steady_host", parts: [
            BlendPart(speaker: 27, weight: 0.45),
            BlendPart(speaker: 17, weight: 0.30),
            BlendPart(speaker: 11, weight: 0.25),
        ]),
    ]

    /// Official slots that hold Toto blends. sherpa-onnx requires voices.bin to contain
    /// exactly [officialSpeakers] style vectors; appending extras aborts the process
    /// ("Corrupted --kokoro-voices ... Expected #floats: 7050240, actual: 7572480").
    static var blendSlotStart: Int { officialSpeakers - blends.count }

    /// Map a Kotlin TotoVoiceConfig speaker id (official + blend index) onto a 54-slot file.
    static func sherpaSpeaker(_ speaker: Int) -> Int {
        if speaker >= officialSpeakers {
            let index = speaker - officialSpeakers
            if index >= 0 && index < blends.count { return blendSlotStart + index }
            return max(0, officialSpeakers - 1)
        }
        if speaker < 0 { return 0 }
        return speaker
    }

    static func ensureBlends() throws {
        let voices = packDir.appendingPathComponent("voices.bin")
        let official = officialSpeakers
        let count = voicesSpeakerCount(voices)
        guard count >= official else {
            throw PackError.message("voices.bin is not a \(official)-speaker Kokoro pack")
        }
        let data = try Data(contentsOf: voices)
        let officialBytes = official * speakerBytes
        guard data.count >= officialBytes else { throw PackError.message("voices.bin is truncated") }
        // Always blend from the first official 54 vectors, even if a previous
        // build appended extra speakers or already overwrote the reserved slots.
        let officialData = data.prefix(officialBytes)
        var styles = Array(repeating: [Float](repeating: 0, count: styleFrames * styleDim), count: official)
        officialData.withUnsafeBytes { raw in
            let floats = raw.bindMemory(to: UInt8.self)
            for s in 0..<official {
                let offset = s * speakerBytes
                for i in 0..<(styleFrames * styleDim) {
                    let o = offset + i * 4
                    let le = UInt32(floats[o])
                        | (UInt32(floats[o + 1]) << 8)
                        | (UInt32(floats[o + 2]) << 16)
                        | (UInt32(floats[o + 3]) << 24)
                    styles[s][i] = Float(bitPattern: le)
                }
            }
        }
        var out = Data(officialData)
        for (b, recipe) in blends.enumerated() {
            var mixed = [Float](repeating: 0, count: styleFrames * styleDim)
            var weight: Float = 0
            for part in recipe.parts {
                guard part.speaker >= 0 && part.speaker < official else {
                    throw PackError.message("Unknown source speaker \(part.speaker)")
                }
                // Source styles must come from the original official vectors, not
                // from slots we may already have overwritten with a previous blend.
                let src = styles[part.speaker]
                for i in mixed.indices { mixed[i] += src[i] * part.weight }
                weight += part.weight
            }
            guard weight > 0 else { throw PackError.message("Empty blend \(recipe.id)") }
            if abs(weight - 1) > 0.001 {
                for i in mixed.indices { mixed[i] /= weight }
            }
            var buf = Data(count: mixed.count * 4)
            buf.withUnsafeMutableBytes { raw in
                for (i, v) in mixed.enumerated() {
                    var bits = v.bitPattern.littleEndian
                    withUnsafeBytes(of: &bits) { raw.baseAddress!.advanced(by: i * 4).copyMemory(from: $0.baseAddress!, byteCount: 4) }
                }
            }
            let slot = blendSlotStart + b
            let start = slot * speakerBytes
            out.replaceSubrange(start..<(start + speakerBytes), with: buf)
        }
        try out.write(to: voices, options: .atomic)
        guard voicesSpeakerCount(voices) == official else { throw PackError.message("Could not write Toto voice blends") }
    }

    enum PackError: LocalizedError {
        case message(String)
        var errorDescription: String? {
            switch self {
            case .message(let s): return s
            }
        }
    }

    /// Downloads (if needed), unpacks, verifies, and blends. Safe to call from a background queue.
    static func install(progress: @escaping (Float, String) -> Void) throws {
        installLock.lock()
        defer { installLock.unlock() }
        if ready {
            try ensureBlends()
            return
        }
        // An earlier build appended blend speakers, which sherpa-onnx treats as a
        // fatal voices.bin corruption. Repair that file in place and skip a re-download.
        if packFilesPresent {
            try? ensureBlends()
            if ready { return }
        }
        let fm = FileManager.default
        let cache = fm.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory
        let archive = cache.appendingPathComponent("\(packName).download.tar.bz2")
        let tarURL = cache.appendingPathComponent("\(packName).download.tar")
        let staging = cache.appendingPathComponent("voice-stage-\(UUID().uuidString)", isDirectory: true)
        defer {
            try? fm.removeItem(at: archive)
            try? fm.removeItem(at: tarURL)
            try? fm.removeItem(at: staging)
        }
        progress(0.02, "Downloading Toto's on-phone voice…")
        let (tmp, response) = try URLSession.shared.downloadFile(from: archiveURL, expectedBytes: archiveBytes, sha256: sha256) { fraction in
            progress(0.02 + fraction * 0.78, "Downloading Toto's on-phone voice…")
        }
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw PackError.message("Voice download failed. Please retry.")
        }
        try fm.replaceOrMove(tmp, to: archive)
        progress(0.82, "Unpacking verified on-phone voice…")
        let rc = archive.path.withCString { inPath in
            tarURL.path.withCString { outPath in
                toto_bunzip2(inPath, outPath)
            }
        }
        guard rc == 0 else { throw PackError.message("Could not decompress the voice pack (\(rc))") }
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        try untar(tarURL, into: staging)
        let unpacked = staging.appendingPathComponent(packName, isDirectory: true)
        let modelOk = fileSize(unpacked.appendingPathComponent("model.int8.onnx")) > 80_000_000
            || fileSize(unpacked.appendingPathComponent("model.onnx")) > 80_000_000
        guard modelOk, fileSize(unpacked.appendingPathComponent("voices.bin")) > 20_000_000 else {
            throw PackError.message("Voice pack is incomplete")
        }
        try sha256.data(using: .utf8)!.write(to: unpacked.appendingPathComponent(".verified"), options: .atomic)
        let destParent = packDir.deletingLastPathComponent()
        try fm.createDirectory(at: destParent, withIntermediateDirectories: true)
        if fm.fileExists(atPath: packDir.path) {
            try fm.removeItem(at: packDir)
        }
        try fm.moveItem(at: unpacked, to: packDir)
        var dest = packDir
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? dest.setResourceValues(values)
        try ensureBlends()
        guard ready else { throw PackError.message("Installed voices did not validate") }
        progress(1, "Toto's on-phone voice is ready")
    }

    static func untar(_ tarURL: URL, into dest: URL) throws {
        let data = try Data(contentsOf: tarURL)
        let block = 512
        var offset = 0
        var written: Int64 = 0
        var entries = 0
        let destCanon = dest.standardizedFileURL.path
        while offset + block <= data.count {
            let header = data.subdata(in: offset..<(offset + block))
            offset += block
            if header.allSatisfy({ $0 == 0 }) { break }
            func field(_ start: Int, _ len: Int) -> String {
                let slice = header[start..<(start + len)]
                let bytes = slice.prefix { $0 != 0 && $0 != 0x20 }
                return String(bytes: bytes, encoding: .ascii) ?? ""
            }
            let name = field(0, 100)
            if name.isEmpty { continue }
            let sizeStr = field(124, 12).trimmingCharacters(in: .whitespacesAndNewlines)
            let size = Int(sizeStr, radix: 8) ?? 0
            let typeFlag = header[156]
            let prefix = field(345, 155)
            let fullName = prefix.isEmpty ? name : "\(prefix)/\(name)"
            let padded = (size + block - 1) / block * block
            entries += 1
            guard entries <= 10_000 else { throw PackError.message("Unexpected voice archive entry") }
            if typeFlag == UInt8(ascii: "L") || typeFlag == UInt8(ascii: "x") || typeFlag == UInt8(ascii: "g") || typeFlag == UInt8(ascii: "X") {
                offset += padded
                continue
            }
            guard fullName.hasPrefix("\(packName)/") else {
                throw PackError.message("Unexpected voice archive entry")
            }
            guard !fullName.hasPrefix("/"), !fullName.split(separator: "/").contains("..") else {
                throw PackError.message("Unsafe archive path")
            }
            let target = dest.appendingPathComponent(fullName)
            let targetPath = target.standardizedFileURL.path
            guard targetPath.hasPrefix(destCanon + "/") else {
                throw PackError.message("Unsafe archive path")
            }
            if typeFlag == UInt8(ascii: "5") || (typeFlag == 0 && fullName.hasSuffix("/")) {
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
            } else if typeFlag == 0 || typeFlag == UInt8(ascii: "0") {
                guard size >= 0, written + Int64(size) <= 700_000_000 else {
                    throw PackError.message("Voice archive is too large")
                }
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                let payload = data.subdata(in: offset..<(offset + size))
                try payload.write(to: target)
                written += Int64(size)
            } else if typeFlag == UInt8(ascii: "1") || typeFlag == UInt8(ascii: "2") {
                throw PackError.message("Voice archive links are not allowed")
            }
            offset += padded
        }
    }

    static func pcmWav(samples: [Float], sampleRate: Int32) -> Data {
        let n = samples.count
        var ints = [Int16](repeating: 0, count: n)
        for i in 0..<n {
            let clipped = max(-1, min(1, samples[i]))
            ints[i] = Int16(clipped * 32767)
        }
        let dataSize = UInt32(n * 2)
        var out = Data()
        func ascii(_ s: String) { out.append(contentsOf: s.utf8) }
        func u16(_ v: UInt16) { var le = v.littleEndian; withUnsafeBytes(of: &le) { out.append(contentsOf: $0) } }
        func u32(_ v: UInt32) { var le = v.littleEndian; withUnsafeBytes(of: &le) { out.append(contentsOf: $0) } }
        ascii("RIFF"); u32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(1); u32(UInt32(sampleRate)); u32(UInt32(sampleRate) * 2); u16(2); u16(16)
        ascii("data"); u32(dataSize)
        ints.withUnsafeBytes { out.append(contentsOf: $0) }
        return out
    }

    static func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(data.count))
        data.withUnsafeBytes { raw in
            let p = raw.bindMemory(to: Int8.self)
            for i in 0..<data.count { out.set(index: Int32(i), value: p[i]) }
        }
        return out
    }
}

private extension FileManager {
    func replaceOrMove(_ src: URL, to dest: URL) throws {
        if fileExists(atPath: dest.path) {
            _ = try replaceItemAt(dest, withItemAt: src)
        } else {
            try moveItem(at: src, to: dest)
        }
    }
}

private extension URLSession {
    func downloadFile(from url: URL, expectedBytes: Int64, sha256: String, progress: @escaping (Float) -> Void) throws -> (URL, URLResponse) {
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<(URL, URLResponse), Error>!
        let task = downloadTask(with: url) { tmp, response, error in
            defer { semaphore.signal() }
            if let error { result = .failure(error); return }
            guard let tmp, let response else {
                result = .failure(TotoKokoroPack.PackError.message("Voice download failed. Please retry."))
                return
            }
            do {
                let handle = try FileHandle(forReadingFrom: tmp)
                defer { try? handle.close() }
                var hash = SHA256()
                var count: Int64 = 0
                while let chunk = try handle.read(upToCount: 1_048_576), !chunk.isEmpty {
                    hash.update(data: chunk)
                    count += Int64(chunk.count)
                    if expectedBytes > 0 { progress(min(1, Float(count) / Float(expectedBytes))) }
                }
                let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
                guard count == expectedBytes, digest == sha256 else {
                    throw TotoKokoroPack.PackError.message("Voice download failed checksum verification")
                }
                // URLSession may delete the download temp after this handler returns.
                let kept = FileManager.default.temporaryDirectory.appendingPathComponent("kokoro-\(UUID().uuidString).tar.bz2")
                try FileManager.default.copyItem(at: tmp, to: kept)
                result = .success((kept, response))
            } catch {
                result = .failure(error)
            }
        }
        task.resume()
        semaphore.wait()
        return try result.get()
    }
}
