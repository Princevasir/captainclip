import Foundation
import Network

/// Phone-side protocol client, used by the `probe` tool and the self-test.
/// (The Android app has its own Kotlin implementation of the same protocol.)
public final class Client {
    public enum State: Equatable {
        case connecting, connected, closed
    }

    public var onClip: ((String) -> Void)?
    public var onState: ((State) -> Void)?

    private let info: PairingInfo
    private let device: String
    private let pingInterval: TimeInterval
    private let queue = DispatchQueue(label: "clipbridge.client")
    private var session: Session?
    private var pingTimer: DispatchSourceTimer?
    private let stateLock = NSLock()
    private var currentState: State = .connecting

    public var state: State { stateLock.withLock { currentState } }

    public init(info: PairingInfo, device: String, pingInterval: TimeInterval = 25) {
        self.info = info
        self.device = device
        self.pingInterval = pingInterval
    }

    public func start() {
        let connection = NWConnection(
            host: NWEndpoint.Host(info.host),
            port: NWEndpoint.Port(rawValue: UInt16(info.port))!,
            using: .tcp)
        let session = Session(connection: connection, crypto: try! ClipCrypto(key: info.key), queue: queue, role: .client)
        session.onChallenge = { [weak self, weak session] in
            guard let self, let session else { return }
            session.send(.hello(device: self.device))
        }
        session.onMessage = { [weak self] message in
            guard let self else { return }
            switch message {
            case .hello:
                // The Mac proved it has the key: now we're really connected.
                if self.state != .connected {
                    self.setState(.connected)
                    self.startPing()
                }
            case .clip(_, let text, _):
                self.onClip?(text)
            default:
                break  // ping, unknown, and file messages handled elsewhere
            }
        }
        session.onClose = { [weak self] in
            self?.pingTimer?.cancel()
            self?.setState(.closed)
        }
        self.session = session
        queue.async { session.start() }
    }

    public func send(text: String) {
        queue.async { [weak self] in
            let ts = Int64(Date().timeIntervalSince1970 * 1000)
            self?.session?.send(.clip(id: UUID().uuidString, text: text, ts: ts))
        }
    }

    public func stop() {
        queue.sync { session?.close() }
    }

    private func setState(_ state: State) {
        stateLock.withLock { currentState = state }
        onState?(state)
    }

    private func startPing() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + pingInterval, repeating: pingInterval)
        timer.setEventHandler { [weak self] in self?.session?.send(.ping) }
        timer.resume()
        pingTimer = timer
    }
}
