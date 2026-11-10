import CoreBluetooth
import Foundation
import os

let bleLog = Logger(subsystem: "app.clipbridge.mac", category: "ble")

/// UUIDs shared with the Android app.
public enum BLEIds {
    public static let service = CBUUID(string: "6C1B0001-8C4F-4E5A-9B8E-5A1D3C2B7F10")
    public static let toMac = CBUUID(string: "6C1B0002-8C4F-4E5A-9B8E-5A1D3C2B7F10")
    public static let toPhone = CBUUID(string: "6C1B0003-8C4F-4E5A-9B8E-5A1D3C2B7F10")
    /// Advertised local name: the pairing tag hex (8 chars), so the phone can pick its own Mac. Kept short: macOS
    /// drops the name unless it fits beside the 128-bit service UUID in one 31-byte advertisement.
    public static func localName(macId: String) -> String { PairingTag.of(macId: macId).hexString }
}

/// Bluetooth fallback, Mac side: a BLE peripheral exposing one GATT service. When the paired phone subscribes,
/// the link becomes a `Transport` handed to the same `Server` (same handshake and encryption as Wi-Fi).
public final class BLEServer: NSObject, CBPeripheralManagerDelegate {
    public enum State: Equatable { case starting, ready, poweredOff, notAllowed, unsupported }

    public var onState: ((State) -> Void)?
    public private(set) var state: State = .starting

    private let server: Server
    private let macId: String
    private let queue = DispatchQueue(label: "clipbridge.ble")
    private var manager: CBPeripheralManager!
    private var toPhone: CBMutableCharacteristic?
    private lazy var links = CentralLinks<UUID, BLEPeripheralTransport>(make: { [unowned self] id in
        BLEPeripheralTransport(manager: self.manager, characteristic: self.toPhone!, central: self.centrals[id]!, bleQueue: self.queue)
    }, close: { $0.remoteClosed() })
    private var centrals: [UUID: CBCentral] = [:]

    public init(server: Server, macId: String) {
        self.server = server
        self.macId = macId
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: queue)
    }

    public func stop() {
        queue.async {
            self.manager.stopAdvertising()
            self.manager.removeAllServices()
            self.links.closeAll()
            self.centrals.removeAll()
        }
    }

    private func set(_ new: State) {
        state = new
        DispatchQueue.main.async { self.onState?(new) }
    }

    // MARK: CBPeripheralManagerDelegate (on `queue`)

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn: publish()
        case .poweredOff: links.closeAll(); centrals.removeAll(); set(.poweredOff)
        case .unauthorized: links.closeAll(); centrals.removeAll(); set(.notAllowed)
        case .unsupported: set(.unsupported)
        default: break
        }
    }

    private func publish() {
        let toMac = CBMutableCharacteristic(type: BLEIds.toMac, properties: [.writeWithoutResponse, .write],
                                            value: nil, permissions: [.writeable])
        let toPhone = CBMutableCharacteristic(type: BLEIds.toPhone, properties: [.notify], value: nil, permissions: [.readable])
        let service = CBMutableService(type: BLEIds.service, primary: true)
        service.characteristics = [toMac, toPhone]
        self.toPhone = toPhone
        manager.removeAllServices()
        manager.add(service)
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else { set(.unsupported); return }
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [BLEIds.service],
                                  CBAdvertisementDataLocalNameKey: BLEIds.localName(macId: macId)])
    }

    public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        set(error == nil ? .ready : .unsupported)
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                                  didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == BLEIds.toPhone, toPhone != nil else { return }
        // Each device gets its own link; only one that completes the encrypted handshake replaces the phone's session.
        centrals[central.identifier] = central
        bleLog.info("subscribe \(central.identifier, privacy: .public) max=\(central.maximumUpdateValueLength)")
        server.accept(transport: links.subscribed(central.identifier))
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                                  didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == BLEIds.toPhone else { return }
        bleLog.info("unsubscribe \(central.identifier, privacy: .public)")
        links.unsubscribed(central.identifier)
        centrals[central.identifier] = nil
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == BLEIds.toMac, let value = request.value,
               let link = links.link(for: request.central.identifier) {
                link.received(value)
            } else {
                bleLog.info("write dropped from \(request.central.identifier, privacy: .public) len=\(request.value?.count ?? -1)")
            }
        }
        if let first = requests.first { peripheral.respond(to: first, withResult: .success) }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        links.all.forEach { $0.pump() }
    }
}

/// One phone's BLE link as a byte-stream Transport. Outgoing bytes are cut to the central's notification size and
/// sent with back-pressure (waits for `peripheralManagerIsReady` when the radio queue is full).
final class BLEPeripheralTransport: Transport {
    let isBluetooth = true
    var onReady: (() -> Void)?
    var onData: ((Data) -> Void)?
    var onClose: (() -> Void)?

    let central: CBCentral
    private let manager: CBPeripheralManager
    private let characteristic: CBMutableCharacteristic
    private let bleQueue: DispatchQueue
    private var sessionQueue: DispatchQueue?
    private var outbox: [(packets: [Data], completion: (Error?) -> Void)] = []  // on bleQueue
    private var closed = false

    init(manager: CBPeripheralManager, characteristic: CBMutableCharacteristic, central: CBCentral, bleQueue: DispatchQueue) {
        self.manager = manager
        self.characteristic = characteristic
        self.central = central
        self.bleQueue = bleQueue
    }

    func start(queue: DispatchQueue) {
        sessionQueue = queue
        queue.async { self.onReady?() }
    }

    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        bleQueue.async {
            guard !self.closed else { self.sessionQueue?.async { completion(NSError(domain: "ClipBridge.BLE", code: 1)) }; return }
            let mtu = max(20, self.central.maximumUpdateValueLength)
            self.outbox.append((Packetizer.split(data, mtu: mtu), completion))
            self.pump()
        }
    }

    /// Sends as many queued packets as the radio accepts (on bleQueue).
    func pump() {
        while !closed, !outbox.isEmpty {
            if outbox[0].packets.isEmpty {
                let done = outbox.removeFirst().completion
                sessionQueue?.async { done(nil) }
                continue
            }
            let packet = outbox[0].packets[0]
            guard manager.updateValue(packet, for: characteristic, onSubscribedCentrals: [central]) else { return }
            outbox[0].packets.removeFirst()
        }
    }

    func received(_ value: Data) {
        sessionQueue?.async { if !self.closed { self.onData?(value) } }
    }

    func cancel() {
        bleLog.info("transport cancelled")
        bleQueue.async { self.closed = true; self.outbox.removeAll() }
    }

    func remoteClosed() {
        bleQueue.async {
            guard !self.closed else { return }
            self.closed = true
            self.outbox.removeAll()
            self.sessionQueue?.async { self.onClose?() }
        }
    }
}
