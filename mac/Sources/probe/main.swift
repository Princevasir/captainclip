import ClipBridgeCore
import Foundation

// Usage: probe "<clipbridge://pair?...>" [send "<text>"]
// Acts like a phone: connects, prints every clip from the Mac, optionally sends one.
let args = CommandLine.arguments
guard args.count >= 2, let info = PairingInfo(uri: args[1]) else {
    FileHandle.standardError.write(Data("usage: probe <pairing-uri> [send <text>]\n".utf8))
    exit(2)
}

let client = Client(info: info, device: "probe")
client.onClip = { print("received: \($0)"); fflush(stdout) }
client.onState = { state in
    print("state: \(state)"); fflush(stdout)
    if state == .closed { exit(1) }
    if state == .connected, args.count >= 4, args[2] == "send" {
        client.send(text: args[3])
        print("sent: \(args[3])"); fflush(stdout)
    }
}
client.start()
RunLoop.main.run()
