import Foundation
import ClipBridgeCore

func frameTests() throws {
    let a = Data("first".utf8), b = Data("second!".utf8)
    let f1 = try FrameCodec.encode(a)
    check(f1.prefix(4) == Data([0, 0, 0, 5]), "frame header is 4-byte big-endian length")

    let stream = f1 + (try FrameCodec.encode(b))
    let decoder = FrameDecoder()
    var out: [Data] = []
    out += try decoder.append(stream.subdata(in: 0..<3))
    out += try decoder.append(stream.subdata(in: 3..<9))
    out += try decoder.append(stream.subdata(in: 9..<stream.count))
    check(out == [a, b], "decoder yields both payloads from split chunks")

    checkThrows("decoder rejects length 1_048_577") {
        _ = try FrameDecoder().append(Data([0x00, 0x10, 0x00, 0x01]))
    }
    checkThrows("decoder rejects length 0") { _ = try FrameDecoder().append(Data([0, 0, 0, 0])) }
    checkThrows("encode rejects payload over 1 MiB") {
        _ = try FrameCodec.encode(Data(count: FrameCodec.maxPayload + 1))
    }
}

func messageTests() throws {
    let clip = Message.clip(id: "abc", text: "hi \"there\"", ts: 1_700_000_000_000)
    let json = String(decoding: try clip.encoded(), as: UTF8.self)
    check(json.contains(#""t":"clip""#), "clip JSON has t=clip")
    check(try Message.decode(Data(json.utf8)) == clip, "clip roundtrips")

    check(try Message.decode(Data(#"{"t":"ping"}"#.utf8)) == .ping, "ping decodes")
    check(try Message.decode(Data(#"{"t":"hello","device":"Pixel"}"#.utf8)) == .hello(device: "Pixel"),
          "hello decodes")
    check(try Message.decode(Data(#"{"t":"nope"}"#.utf8)) == .unknown(type: "nope"), "unknown type is tolerated (v2)")
    checkThrows("garbage rejected") { _ = try Message.decode(Data("not json".utf8)) }
}
