import ClipBridgeCore
import Foundation

private func object(_ m: Message) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: m.encoded())) as? [String: Any] ?? [:]
}

func richMessageTests() throws {
    let rich = Message.clip(id: "c1", text: "Hello", ts: 1, html: "<b>Hello</b>")
    check(object(rich)["html"] as? String == "<b>Hello</b>", "clip carries html")
    check(try Message.decode(rich.encoded()) == rich, "clip with html roundtrips")
    check(object(.clip(id: "c2", text: "plain", ts: 1))["html"] == nil, "plain clip has no html key")
    check(try Message.decode(Data(#"{"id":"c3","t":"clip","text":"old","ts":5}"#.utf8)) == .clip(id: "c3", text: "old", ts: 5, html: nil),
          "clip JSON without html still decodes")

    let image = Message.fileStart(id: "f", name: "s.png", mime: "image/png", size: 3, sha256: "x", purpose: "clipboard")
    check(object(image)["purpose"] as? String == "clipboard", "file-start carries purpose")
    check(try Message.decode(image.encoded()) == image, "file-start with purpose roundtrips")
    check(object(.fileStart(id: "f", name: "a", mime: "m", size: 1, sha256: "x"))["purpose"] == nil, "default purpose is not written")
    let old = try Message.decode(Data(#"{"id":"f","mime":"m","name":"a","sha256":"x","size":1,"t":"file-start"}"#.utf8))
    check(old == .fileStart(id: "f", name: "a", mime: "m", size: 1, sha256: "x", purpose: "file"), "missing purpose means file")

    let code = Message.clip(id: "1", text: "482913", ts: 1, sensitive: true)
    check(object(code)["sensitive"] as? Bool == true, "sensitive clip carries the flag")
    check(try Message.decode(code.encoded()) == code, "sensitive clip roundtrips")
    check(object(.clip(id: "1", text: "hi", ts: 1))["sensitive"] == nil, "false sensitive is not written")
    check(try Message.decode(Data(#"{"id":"1","t":"clip","text":"482913","ts":1}"#.utf8))
          == .clip(id: "1", text: "482913", ts: 1), "clip JSON without sensitive means not sensitive")
    check(try Message.decode(Data(#"{"t":"clip","id":"1","text":"482913","ts":1,"sensitive":true}"#.utf8))
          == .clip(id: "1", text: "482913", ts: 1, sensitive: true), "fixed wire JSON decodes as sensitive")
}
