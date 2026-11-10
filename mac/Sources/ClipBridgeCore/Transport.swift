import Foundation
import Network

/// A reliable, ordered byte stream the encrypted session runs over: TCP on Wi-Fi, or a BLE link.
/// All callbacks are delivered on the queue passed to `start`.
public protocol Transport: AnyObject {
    /// True for the Bluetooth link (slower; larger files are refused over it).
    var isBluetooth: Bool { get }
    var onReady: (() -> Void)? { get set }
    var onData: ((Data) -> Void)? { get set }
    /// Called once when the stream ends or fails.
    var onClose: (() -> Void)? { get set }
    func start(queue: DispatchQueue)
    /// `completion(nil)` once the bytes are handed to the network/radio (used for pacing).
    func send(_ data: Data, completion: @escaping (Error?) -> Void)
    func cancel()
}

/// TCP transport over Network.framework.
public final class TCPTransport: Transport {
    public let isBluetooth = false
    public var onReady: (() -> Void)?
    public var onData: ((Data) -> Void)?
    public var onClose: (() -> Void)?
    private let connection: NWConnection
    private var closed = false

    public init(_ connection: NWConnection) {
        self.connection = connection
    }

    public func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.onReady?()
            case .failed, .cancelled: self?.finish()
            default: break
            }
        }
        connection.start(queue: queue)
        receive()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty { self.onData?(data) }
            if self.closed { return }
            if isComplete || error != nil { self.finish(); return }
            self.receive()
        }
    }

    public func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        connection.send(content: data, completion: .contentProcessed { completion($0) })
    }

    public func cancel() {
        closed = true
        connection.cancel()
    }

    private func finish() {
        guard !closed else { return }
        closed = true
        onClose?()
    }
}
