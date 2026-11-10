import Foundation
import ClipBridgeCore

/// Thread-safe recorder for callback values.
final class Recorder<T> {
    private let lock = NSLock()
    private var items: [T] = []
    func add(_ item: T) { lock.withLock { items.append(item) } }
    var all: [T] { lock.withLock { items } }
}

/// Spins the run loop until `condition` is true or `timeout` passes.
func waitUntil(_ timeout: TimeInterval = 3, _ condition: () -> Bool) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return true }
        RunLoop.current.run(until: Date().addingTimeInterval(0.02))
    }
    return condition()
}

func makeServer(key: Data, idleTimeout: TimeInterval = 60) throws -> (Server, Recorder<String>, Recorder<Server.Status>) {
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false, idleTimeout: idleTimeout)
    let clips = Recorder<String>(), statuses = Recorder<Server.Status>()
    server.onClip = { clips.add($0) }
    server.onStatus = { statuses.add($0) }
    try server.start()
    return (server, clips, statuses)
}

func makeClient(_ server: Server, key: Data, pingInterval: TimeInterval = 25) -> (Client, Recorder<String>, Recorder<Client.State>) {
    let info = PairingInfo(macId: "0123456789abcdef", key: key, host: "127.0.0.1", port: Int(server.port ?? 0))
    let client = Client(info: info, device: "probe", pingInterval: pingInterval)
    let clips = Recorder<String>(), states = Recorder<Client.State>()
    client.onClip = { clips.add($0) }
    client.onState = { states.add($0) }
    client.start()
    return (client, clips, states)
}

func serverTests() throws {
    let key = ClipCrypto.randomKey()
    let (server, serverClips, statuses) = try makeServer(key: key)
    defer { server.stop() }
    check(waitUntil { server.port != nil }, "server gets a listening port")

    let (client, clientClips, _) = makeClient(server, key: key)
    check(waitUntil { statuses.all.last == .connected(device: "probe") }, "server sees hello from probe")

    client.send(text: "x")
    check(waitUntil { serverClips.all == ["x"] }, "clip from client reaches server")

    check(server.send(text: "y"), "server.send returns true when connected")
    check(waitUntil { clientClips.all == ["y"] }, "clip from server reaches client")

    check(!server.send(text: String(repeating: "a", count: 1_100_000)), "oversized text is refused")
    check(waitUntil { statuses.all.last == .connected(device: "probe") }, "still connected after refusing big text")

    let (second, _, _) = makeClient(server, key: key)
    check(waitUntil { client.state == .closed }, "a new phone connection replaces the old one")
    second.stop()
    check(waitUntil { statuses.all.last == .waiting }, "server returns to waiting when phone leaves")
    client.stop()
}

func wrongKeyTests() throws {
    let (server, serverClips, statuses) = try makeServer(key: ClipCrypto.randomKey())
    defer { server.stop() }
    _ = waitUntil { server.port != nil }
    let (intruder, _, _) = makeClient(server, key: ClipCrypto.randomKey())
    intruder.send(text: "evil")
    check(waitUntil { intruder.state == .closed }, "wrong-key client is disconnected")
    check(serverClips.all.isEmpty, "wrong-key clip is never delivered")
    check(!statuses.all.contains { if case .connected = $0 { return true } else { return false } },
          "wrong-key client never counts as connected")
}

func idleTimeoutTests() throws {
    let key = ClipCrypto.randomKey()
    let (server, _, statuses) = try makeServer(key: key, idleTimeout: 0.5)
    defer { server.stop() }
    _ = waitUntil { server.port != nil }

    let (silent, _, _) = makeClient(server, key: key, pingInterval: 100)
    check(waitUntil { silent.state == .closed }, "silent phone is dropped after idle timeout")
    silent.stop()

    let (chatty, _, _) = makeClient(server, key: key, pingInterval: 0.1)
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }
    RunLoop.current.run(until: Date().addingTimeInterval(1.2))
    check(chatty.state == .connected, "pinging phone stays connected past idle timeout")
    chatty.stop()
}
