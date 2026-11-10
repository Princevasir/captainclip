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
    /// Every message other than hello/clip/ping (offers, file transfer messages…).
    public var onMessage: ((Message) -> Void)?

    private var expected: [String: (dir: URL, completion: (Result<URL, Error>) -> Void)] = [:]
    private var receivers: [String: FileReceiver] = [:]

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
            case .clip(_, let text, _, _):
                self.onClip?(text)
            case .ping, .unknown:
                break
            default:
                self.handleFileMessage(message)
                self.onMessage?(message)
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

    public func send(message: Message) {
        queue.async { [weak self] in self?.session?.send(message) }
    }

    /// Get ready to receive file `id` into `dir` (e.g. before sending `fetch`).
    public func expectFile(id: String, into dir: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        queue.async { self.expected[id] = (dir, completion) }
    }

    /// Sends a file to the Mac: file-start, then paced chunks + file-end.
    public func sendFile(url: URL, id: String = UUID().uuidString, mime: String = "application/octet-stream",
                         completion: @escaping (Bool) -> Void = { _ in }) {
        queue.async { [weak self] in
            guard let self, let session = self.session,
                  let sender = try? FileSender(id: id, url: url, name: url.lastPathComponent, mime: mime)
            else { completion(false); return }
            session.send(sender.start)
            session.sendChunked(sender.stream(), completion: completion)
        }
    }

    private func handleFileMessage(_ message: Message) {
        switch message {
        case .fileStart(let id, _, _, _, _, _):
            guard let exp = expected[id] else { return }
            do { receivers[id] = try FileReceiver(start: message, directory: exp.dir) }
            catch { expected[id] = nil; exp.completion(.failure(error)) }
        case .fileChunk(let id, _, _), .fileEnd(let id):
            guard let receiver = receivers[id], let exp = expected[id] else { return }
            do {
                if let url = try receiver.accept(message) {
                    receivers[id] = nil; expected[id] = nil
                    exp.completion(.success(url))
                }
            } catch {
                receivers[id] = nil; expected[id] = nil
                exp.completion(.failure(error))
            }
        case .fileError(let id, let reason):
            receivers[id]?.abort(); receivers[id] = nil
            if let exp = expected.removeValue(forKey: id) {
                exp.completion(.failure(NSError(domain: "ClipBridge", code: 1, userInfo: [NSLocalizedDescriptionKey: reason])))
            }
        default:
            break
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
