import Foundation
import Network

/// Mac side: listens on TCP, optionally advertises `_clipbridge._tcp`, keeps one active phone.
/// Callbacks run on a private queue — hop to main before touching UI.
public final class Server {
    public enum Status: Equatable {
        case waiting
        case connected(device: String)
    }

    public static let serviceType = "_clipbridge._tcp"

    public var onClip: ((String) -> Void)?
    public var onStatus: ((Status) -> Void)?

    private let crypto: ClipCrypto
    private let macId: String
    private let advertise: Bool
    private let idleTimeout: TimeInterval
    private let queue = DispatchQueue(label: "clipbridge.server")
    private var listener: NWListener?
    private var pending: [ObjectIdentifier: Session] = [:]
    private var active: Session?
    private var idleTimer: DispatchSourceTimer?
    private let portLock = NSLock()
    private var listeningPort: UInt16?

    public var port: UInt16? { portLock.withLock { listeningPort } }

    public init(key: Data, macId: String, advertise: Bool = true, idleTimeout: TimeInterval = 60) {
        self.crypto = try! ClipCrypto(key: key)  // callers pass keys from ClipCrypto.randomKey / Keychain
        self.macId = macId
        self.advertise = advertise
        self.idleTimeout = idleTimeout
    }

    public func start() throws {
        let listener = try NWListener(using: .tcp, on: .any)
        if advertise {
            listener.service = NWListener.Service(name: "ClipBridge-\(macId.prefix(6))", type: Self.serviceType)
        }
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state, let self {
                self.portLock.withLock { self.listeningPort = listener.port?.rawValue }
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: queue)
        self.listener = listener
        startIdleTimer()
    }

    public func stop() {
        queue.sync {
            idleTimer?.cancel()
            listener?.cancel()
            pending.values.forEach { $0.close() }
            active?.close()
        }
    }

    /// Sends text to the connected phone. False if none is connected or the text is too large.
    @discardableResult
    public func send(text: String) -> Bool {
        queue.sync {
            guard let active else { return false }
            let ts = Int64(Date().timeIntervalSince1970 * 1000)
            return active.send(.clip(id: UUID().uuidString, text: text, ts: ts))
        }
    }

    private func accept(_ connection: NWConnection) {
        let session = Session(connection: connection, crypto: crypto, queue: queue)
        let id = ObjectIdentifier(session)
        pending[id] = session
        session.onMessage = { [weak self, weak session] message in
            guard let self, let session else { return }
            self.handle(message, from: session)
        }
        session.onClose = { [weak self, weak session] in
            guard let self, let session else { return }
            self.pending[ObjectIdentifier(session)] = nil
            if self.active === session {
                self.active = nil
                self.onStatus?(.waiting)
            }
        }
        session.start()
    }

    private func handle(_ message: Message, from session: Session) {
        if active === session {
            if case .clip(_, let text, _) = message { onClip?(text) }
            return
        }
        // First message from a new connection must be hello.
        guard case .hello(let device) = message else {
            session.close()
            return
        }
        pending[ObjectIdentifier(session)] = nil
        let old = active
        active = session
        old?.onClose = nil
        old?.close()
        onStatus?(.connected(device: device))
    }

    private func startIdleTimer() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        let interval = min(idleTimeout / 2, 5)
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let cutoff = Date().addingTimeInterval(-self.idleTimeout)
            for session in Array(self.pending.values) + [self.active].compactMap({ $0 })
            where session.lastSeen < cutoff {
                session.close()
            }
        }
        timer.resume()
        idleTimer = timer
    }
}
