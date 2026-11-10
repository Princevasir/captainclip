import ClipBridgeCore
import Foundation

private func json(_ m: Message) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: m.encoded())) as? [String: Any] ?? [:]
}

func fileMessageTests() throws {
    let cases: [(Message, String)] = [
        (.offer(id: "o1", name: "report.pdf", mime: "application/pdf", size: 1234, ts: 99), "offer"),
        (.offerClear, "offer-clear"),
        (.fetch(id: "o1"), "fetch"),
        (.fileStart(id: "f1", name: "a.pdf", mime: "application/pdf", size: 3, sha256: String(repeating: "a", count: 64)), "file-start"),
        (.fileChunk(id: "f1", seq: 0, data: Data([0, 1, 2])), "file-chunk"),
        (.fileEnd(id: "f1"), "file-end"),
        (.fileError(id: "f1", reason: "expired"), "file-error"),
    ]
    for (message, type) in cases {
        check(json(message)["t"] as? String == type, "\(type) has t=\(type)")
        check((try? Message.decode(message.encoded())) == message, "\(type) roundtrips")
    }
    let offer = json(.offer(id: "o1", name: "report.pdf", mime: "application/pdf", size: 1234, ts: 99))
    check(offer["name"] as? String == "report.pdf" && (offer["size"] as? NSNumber)?.intValue == 1234, "offer carries name and size")
    check(json(.fileChunk(id: "f1", seq: 0, data: Data([0, 1, 2])))["data"] as? String == "AAEC", "chunk data is base64")

    check(try Message.decode(Data(#"{"t":"future-thing"}"#.utf8)) == .unknown(type: "future-thing"),
          "unknown message types decode to .unknown instead of failing")
    checkThrows("chunk with invalid base64 rejected") {
        _ = try Message.decode(Data(#"{"t":"file-chunk","id":"f","seq":0,"data":"***"}"#.utf8))
    }
    checkThrows("offer missing size rejected") {
        _ = try Message.decode(Data(#"{"t":"offer","id":"o","name":"n","mime":"m","ts":1}"#.utf8))
    }
}
