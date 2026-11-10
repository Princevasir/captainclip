import Foundation
import Network

/// One encrypted, framed connection. Any decrypt/parse/size error closes it.
/// All callbacks run on `queue`.
final class Session {
    let connection: NWConnection
    private let crypto: ClipCrypto
    private let queue: DispatchQueue
    private let decoder = FrameDecoder()
    private var closed = false

    var onReady: (() -> Void)?
    var onMessage: ((Message) -> Void)?
    var onClose: (() -> Void)?
    var lastSeen = Date()

    init(connection: NWConnection, crypto: ClipCrypto, queue: DispatchQueue) {
        self.connection = connection
        self.crypto = crypto
        self.queue = queue
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.onReady?()
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        connection.start(queue: queue)
        receive()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                do {
                    for payload in try self.decoder.append(data) {
                        let message = try Message.decode(try self.crypto.open(payload))
                        self.lastSeen = Date()
                        self.onMessage?(message)
                        if self.closed { return }
                    }
                } catch {
                    self.close()
                    return
                }
            }
            if isComplete || error != nil {
                self.close()
                return
            }
            self.receive()
        }
    }

    /// Returns false if closed or the message can't be framed (e.g. too large).
    @discardableResult
    func send(_ message: Message) -> Bool {
        guard !closed,
              let plain = try? message.encoded(),
              let box = try? crypto.seal(plain),
              let frame = try? FrameCodec.encode(box)
        else { return false }
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            if error != nil { self?.close() }
        })
        return true
    }

    func close() {
        guard !closed else { return }
        closed = true
        connection.cancel()
        onClose?()
    }
}
