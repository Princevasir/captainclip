import Foundation
import Network

/// One encrypted, framed connection. Any decrypt/parse/size error closes it.
/// Handshake: the server (Mac) first sends a plaintext 16-byte challenge frame; every later frame
/// is sealed with AAD bound to that challenge and to the direction of travel.
/// All callbacks run on `queue`.
final class Session {
    enum Role { case server, client }

    static let challengeBytes = 16

    let transport: Transport
    var isBluetooth: Bool { transport.isBluetooth }
    private let crypto: ClipCrypto
    private let queue: DispatchQueue
    private let role: Role
    private let decoder = FrameDecoder()
    private var closed = false
    private var challenge: Data?

    var onReady: (() -> Void)?
    /// Client only: the challenge arrived, so sending is now possible.
    var onChallenge: (() -> Void)?
    var onMessage: ((Message) -> Void)?
    var onClose: (() -> Void)?
    var lastSeen = Date()

    // File chunks are paced: at most `maxChunksInFlight` are handed to the network at once, so any
    // control/text message sent meanwhile goes out right behind them instead of behind the whole file.
    private static let maxChunksInFlight = 2
    private var chunkIterator: AnyIterator<Message>?
    private var chunksInFlight = 0
    private var chunkCompletion: ((Bool) -> Void)?

    private var inbound: Direction { role == .server ? .phoneToMac : .macToPhone }
    private var outbound: Direction { role == .server ? .macToPhone : .phoneToMac }

    init(transport: Transport, crypto: ClipCrypto, queue: DispatchQueue, role: Role) {
        self.transport = transport
        self.crypto = crypto
        self.queue = queue
        self.role = role
    }

    func start() {
        transport.onReady = { [weak self] in self?.onReady?() }
        transport.onClose = { [weak self] in self?.close() }
        transport.onData = { [weak self] data in self?.receive(data) }
        transport.start(queue: queue)
        if role == .server {
            var bytes = [UInt8](repeating: 0, count: Self.challengeBytes)
            _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            let challenge = Data(bytes)
            self.challenge = challenge
            if let frame = try? FrameCodec.encode(challenge) { sendRaw(frame) }
        }
    }

    private func receive(_ data: Data) {
        guard !closed else { return }
        do {
            for payload in try decoder.append(data) {
                try handle(payload)
                if closed { return }
            }
        } catch {
            close()
        }
    }

    private func handle(_ payload: Data) throws {
        guard let challenge else {
            // Client: the very first frame is the server's plaintext challenge.
            guard role == .client, payload.count == Self.challengeBytes else { throw MessageError.invalid }
            self.challenge = payload
            onChallenge?()
            return
        }
        let plain = try crypto.open(payload, aad: ClipCrypto.sessionAAD(direction: inbound, challenge: challenge))
        let message = try Message.decode(plain)
        lastSeen = Date()
        onMessage?(message)
    }

    /// Returns false if closed, before the handshake, or the message can't be framed.
    @discardableResult
    func send(_ message: Message) -> Bool {
        guard !closed, let challenge,
              let plain = try? message.encoded(),
              let box = try? crypto.seal(plain, aad: ClipCrypto.sessionAAD(direction: outbound, challenge: challenge)),
              let frame = try? FrameCodec.encode(box)
        else { return false }
        sendRaw(frame)
        return true
    }

    /// Streams messages (file chunks + end) with pacing. Must be called on `queue`.
    /// Completion is called once: true when all were handed off and acknowledged by the network stack.
    func sendChunked(_ messages: AnySequence<Message>, completion: @escaping (Bool) -> Void) {
        guard !closed, chunkIterator == nil else { completion(false); return }
        chunkIterator = AnyIterator(messages.makeIterator())
        chunkCompletion = completion
        pumpChunks()
    }

    /// Stops a running chunk stream (frames already handed to the network still go out).
    func cancelChunked() {
        guard chunkIterator != nil || chunkCompletion != nil else { return }
        finishChunks(false)
    }

    private func pumpChunks() {
        while chunksInFlight < Self.maxChunksInFlight, let iterator = chunkIterator {
            guard let message = iterator.next() else {
                chunkIterator = nil
                if chunksInFlight == 0 { finishChunks(true) }
                return
            }
            guard !closed, let challenge,
                  let plain = try? message.encoded(),
                  let box = try? crypto.seal(plain, aad: ClipCrypto.sessionAAD(direction: outbound, challenge: challenge)),
                  let frame = try? FrameCodec.encode(box)
            else { finishChunks(false); return }
            chunksInFlight += 1
            transport.send(frame) { [weak self] error in
                guard let self else { return }
                self.chunksInFlight -= 1
                if error != nil { self.finishChunks(false); self.close(); return }
                if self.chunkIterator == nil {
                    if self.chunksInFlight == 0 { self.finishChunks(true) }
                } else {
                    self.pumpChunks()
                }
            }
        }
    }

    private func finishChunks(_ ok: Bool) {
        chunkIterator = nil
        let completion = chunkCompletion
        chunkCompletion = nil
        completion?(ok)
    }

    private func sendRaw(_ frame: Data) {
        transport.send(frame) { [weak self] error in
            if error != nil { self?.close() }
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        finishChunks(false)
        transport.cancel()
        onClose?()
    }
}
