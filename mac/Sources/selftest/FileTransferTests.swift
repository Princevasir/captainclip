import ClipBridgeCore
import Foundation

func tempDir(_ tag: String) -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("clipbridge-\(tag)-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

func randomFile(_ dir: URL, name: String, bytes count: Int) -> URL {
    var data = Data(count: count)
    data.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
    let url = dir.appendingPathComponent(name)
    try! data.write(to: url)
    return url
}

private func partFiles(_ dir: URL) -> [String] {
    ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".clipbridge-part") }
}

func fileTransferTests() throws {
    check(FileLimit.maxBytes == 26_214_400 && FileLimit.chunkBytes == 196_608, "file limits match spec")
    let src = tempDir("src"), dst = tempDir("dst")
    defer { try? FileManager.default.removeItem(at: src); try? FileManager.default.removeItem(at: dst) }

    // Roundtrip ~600 KB (4 chunks)
    let original = randomFile(src, name: "report.pdf", bytes: 600_000)
    let sender = try FileSender(id: "f1", url: original, name: "report.pdf", mime: "application/pdf")
    let chunks = Array(sender.chunks())
    check(chunks.count == 4, "600 KB file is sent as 4 chunks")
    let receiver = try FileReceiver(start: sender.start, directory: dst)
    var result: URL?
    for m in chunks + [sender.end] { result = try receiver.accept(m) ?? result }
    check(result?.lastPathComponent == "report.pdf", "received file keeps its name")
    check(result.flatMap { try? Data(contentsOf: $0) } == (try? Data(contentsOf: original)), "received bytes are identical")
    check(partFiles(dst).isEmpty, "no temp file left after success")

    // Same name again → unique name
    let again = try FileReceiver(start: sender.start, directory: dst)
    var second: URL?
    for m in Array(sender.chunks()) + [sender.end] { second = try again.accept(m) ?? second }
    check(second?.lastPathComponent == "report (1).pdf", "existing name gets ' (1)' suffix")

    // Out-of-order chunk → error, temp removed
    let r2 = try FileReceiver(start: sender.start, directory: dst)
    checkThrows("out-of-order chunk rejected") { _ = try r2.accept(chunks[1]) }
    check(partFiles(dst).isEmpty, "temp file removed after out-of-order chunk")

    // Corrupted data → sha mismatch at end
    let r3 = try FileReceiver(start: sender.start, directory: dst)
    for (i, m) in chunks.enumerated() {
        if i == 2, case .fileChunk(let id, let seq, var data) = m {
            data[0] ^= 0xff
            _ = try r3.accept(.fileChunk(id: id, seq: seq, data: data))
        } else { _ = try r3.accept(m) }
    }
    checkThrows("checksum mismatch rejected") { _ = try r3.accept(sender.end) }
    check(partFiles(dst).isEmpty, "temp file removed after checksum mismatch")

    // More bytes than declared
    let small = try FileReceiver(start: .fileStart(id: "s", name: "s.bin", mime: "x", size: 10, sha256: String(repeating: "0", count: 64)), directory: dst)
    checkThrows("more bytes than declared rejected") { _ = try small.accept(.fileChunk(id: "s", seq: 0, data: Data(count: 11))) }

    // Abort removes temp
    let r4 = try FileReceiver(start: sender.start, directory: dst)
    _ = try r4.accept(chunks[0])
    check(partFiles(dst).count == 1, "partial transfer has a temp file")
    r4.abort()
    check(partFiles(dst).isEmpty, "abort removes temp file")

    // Hostile names stay inside the directory
    let evil = try FileReceiver(start: .fileStart(id: "e", name: "../../evil.sh", mime: "x", size: 1,
        sha256: "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"), directory: dst)
    _ = try evil.accept(.fileChunk(id: "e", seq: 0, data: Data([0x00])))
    let evilURL = try evil.accept(.fileEnd(id: "e"))
    check(evilURL?.deletingLastPathComponent().standardizedFileURL == dst.standardizedFileURL, "hostile name stays in the download folder")
    check(!(evilURL?.lastPathComponent.hasPrefix(".") ?? true), "hostile name is not hidden")

    // Too large
    let big = src.appendingPathComponent("big.bin")
    FileManager.default.createFile(atPath: big.path, contents: nil)
    let h = try FileHandle(forWritingTo: big); try h.truncate(atOffset: UInt64(FileLimit.maxBytes + 1)); try h.close()
    checkThrows("file over 25 MB rejected by sender") { _ = try FileSender(id: "b", url: big, name: "big.bin", mime: "x") }
    checkThrows("receiver rejects declared size over 25 MB") {
        _ = try FileReceiver(start: .fileStart(id: "b", name: "b", mime: "x", size: Int64(FileLimit.maxBytes + 1), sha256: ""), directory: dst)
    }
}
