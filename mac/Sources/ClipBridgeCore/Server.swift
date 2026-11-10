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
    private let macName = Host.current().localizedName ?? "Mac"

    public var onClip: ((String) -> Void)?
    /// Like onClip, with the copy's HTML when it has one.
    public var onRichClip: ((String, String?) -> Void)?
    public var onStatus: ((Status) -> Void)?
    /// File-related messages from the connected phone (fetch, file-start/chunk/end/error). Runs on the server queue.
    public var onMessage: ((Message) -> Void)?

    private let crypto: ClipCrypto
    private let macId: String
    private let advertise: Bool
    private let idleTimeout: TimeInterval
    private let preferredPort: UInt16
    private let queue = DispatchQueue(label: "clipbridge.server")
    private var listener: NWListener?
    private var pending: [ObjectIdentifier: Session] = [:]
    private var active: Session?
    private var idleTimer: DispatchSourceTimer?
    private let portLock = NSLock()
    private var listeningPort: UInt16?

    public var port: UInt16? { portLock.withLock { listeningPort } }

    /// `preferredPort` 0 = any port; a busy preferred port falls back to any port.
    public init(key: Data, macId: String, advertise: Bool = true, idleTimeout: TimeInterval = 60,
                preferredPort: UInt16 = 0) {
        self.crypto = try! ClipCrypto(key: key)  // callers pass keys from ClipCrypto.randomKey / Keychain
        self.macId = macId
        self.advertise = advertise
        self.idleTimeout = idleTimeout
        self.preferredPort = preferredPort
    }

    public func start() throws {
        try startListener(port: preferredPort == 0 ? .any : NWEndpoint.Port(rawValue: preferredPort)!)
        startIdleTimer()
    }

    private func startListener(port: NWEndpoint.Port) throws {
        let listener = try NWListener(using: .tcp, on: port)
        if advertise {
            listener.service = NWListener.Service(name: "ClipBridge-\(macId.prefix(6))", type: Self.serviceType)
        }
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.portLock.withLock { self.listeningPort = listener.port?.rawValue }
            case .failed where port != .any:
                listener.cancel()
                try? self.startListener(port: .any)
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(transport: TCPTransport(connection)) }
        listener.start(queue: queue)
        self.listener = listener
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
    public func send(text: String, html: String? = nil) -> Bool {
        guard TextLimit.fits(text) else { return false }
        return queue.sync {
            guard let active else { return false }
            let ts = Int64(Date().timeIntervalSince1970 * 1000)
            return active.send(.clip(id: UUID().uuidString, text: text, ts: ts, html: RichText.fitting(text: text, html: html)))
        }
    }

    /// True when the connected phone is on the Bluetooth link.
    public var activeIsBluetooth: Bool { queue.sync { active?.isBluetooth ?? false } }

    /// Sends any message to the connected phone. False if none is connected.
    @discardableResult
    public func send(message: Message) -> Bool {
        queue.sync { active?.send(message) ?? false }
    }

    /// Streams file chunks (+ end) to the phone with pacing; text sent meanwhile is not held up.
    public func sendStream(_ messages: AnySequence<Message>, completion: @escaping (Bool) -> Void) {
        queue.async { [weak self] in
            guard let active = self?.active else { completion(false); return }
            active.sendChunked(messages, completion: completion)
        }
    }

    /// Sends a file to the phone: file-start + paced chunks + end. Files are sent one at a time in the order asked
    /// (apps open every file of a set at once); `cancelStream` drops the running one and the queue.
    public func sendFile(start: Message, stream: AnySequence<Message>, completion: @escaping (Bool) -> Void = { _ in }) {
        queue.async { [weak self] in
            guard let self, let active = self.active else { completion(false); return }
            self.fileQueue.append((ObjectIdentifier(active), start, stream, completion))
            if !self.fileSending { self.sendNextFile() }
        }
    }

    private var fileQueue: [(session: ObjectIdentifier, start: Message, stream: AnySequence<Message>, done: (Bool) -> Void)] = []
    private var fileSending = false

    private func sendNextFile() {
        guard !fileQueue.isEmpty else { fileSending = false; return }
        let next = fileQueue.removeFirst()
        guard let active, ObjectIdentifier(active) == next.session else { next.done(false); sendNextFile(); return }
        fileSending = true
        active.send(next.start)
        active.sendChunked(next.stream) { [weak self] ok in
            next.done(ok)
            self?.sendNextFile()
        }
    }

    /// Stops any file stream to the phone and drops queued ones (e.g. the offer was replaced or cleared).
    public func cancelStream() {
        queue.async { [weak self] in
            guard let self else { return }
            let dropped = self.fileQueue
            self.fileQueue.removeAll()
            dropped.forEach { $0.done(false) }
            self.active?.cancelChunked()
        }
    }

    /// Accepts a phone over any transport (TCP from the listener, or a Bluetooth link). Newest phone wins.
    public func accept(transport: Transport) {
        queue.async { [weak self] in self?.acceptOnQueue(transport) }
    }

    private func acceptOnQueue(_ transport: Transport) {
        let session = Session(transport: transport, crypto: crypto, queue: queue, role: .server)
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
                bleLog.info("active session closed (bluetooth=\(session.isBluetooth))")
                self.active = nil
                self.onStatus?(.waiting)
            }
        }
        session.start()
    }

    private func handle(_ message: Message, from session: Session) {
        if active === session {
            switch message {
            case .clip(_, let text, _, let html) where TextLimit.fits(text):
                onClip?(text)
                onRichClip?(text, RichText.fitting(text: text, html: html))
            case .ping: session.send(.ping)  // lets the phone know the Mac is alive
            case .fetch, .fileStart, .fileChunk, .fileEnd, .fileError: onMessage?(message)
            default: break
            }
            return
        }
        // First message from a new connection must be hello.
        guard case .hello(let device) = message else {
            session.close()
            return
        }
        pending[ObjectIdentifier(session)] = nil
        // Prove we hold the key too, so the phone only shows "Connected" for the real Mac.
        session.send(.hello(device: macName))
        let old = active
        active = session
        old?.onClose = nil
        old?.close()
        bleLog.info("connected bluetooth=\(session.isBluetooth)")
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
