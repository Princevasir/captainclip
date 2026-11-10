import AppKit
import ClipBridgeCore
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate {
    static let port: UInt16 = 52001

    private var statusItem: NSStatusItem!
    private let statusLine = NSMenuItem(title: "Starting…", action: nil, keyEquivalent: "")
    private let offerLine = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let pauseItem = NSMenuItem(title: "Pause syncing", action: #selector(togglePause), keyEquivalent: "")
    private var bridge: Bridge?
    private var server: Server?
    private var pairing: (macId: String, key: Data)!
    private let keyStore = KeyStore()
    private let pairWindow = PairWindow()

    func applicationDidFinishLaunching(_ notification: Notification) {
        pairing = keyStore.load() ?? newPairing()
        buildMenu()
        startBridge()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert]) { _, _ in }
        if keyStore.load() != nil, !UserDefaults.standard.bool(forKey: "pairedOnce") {
            showPairing()
        }
    }

    private func newPairing() -> (macId: String, key: Data) {
        let macId = (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let key = ClipCrypto.randomKey()
        keyStore.save(macId: macId, key: key)
        return (macId, key)
    }

    private func buildMenu() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "doc.on.clipboard", accessibilityDescription: "ClipBridge")
        let menu = NSMenu()
        statusLine.isEnabled = false
        menu.addItem(statusLine)
        offerLine.isEnabled = false
        offerLine.isHidden = true
        menu.addItem(offerLine)
        menu.addItem(.separator())
        menu.addItem(withTitle: "Pair phone…", action: #selector(showPairing), keyEquivalent: "p").target = self
        pauseItem.target = self
        menu.addItem(pauseItem)
        menu.addItem(withTitle: "Reset pairing (new key)", action: #selector(resetPairing), keyEquivalent: "").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit ClipBridge", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        statusItem.menu = menu
    }

    private func startBridge() {
        let server = Server(key: pairing.key, macId: pairing.macId, preferredPort: Self.port)
        let bridge = Bridge(server: server, watcher: PasteboardWatcher())
        bridge.onStatus = { [weak self] status in self?.show(status) }
        bridge.onReceived = { [weak self] text in self?.notifyReceived(text) }
        bridge.onFileReceived = { [weak self] url in self?.notifyFile(url) }
        bridge.onOfferChanged = { [weak self] name in
            self?.offerLine.title = name.map { "Ready to paste on phone: \($0)" } ?? ""
            self?.offerLine.isHidden = name == nil
        }
        do {
            try bridge.start()
            show(.waiting)
        } catch {
            statusLine.title = "Error: \(error.localizedDescription)"
        }
        self.server = server
        self.bridge = bridge
    }

    private func show(_ status: Server.Status) {
        switch status {
        case .waiting:
            statusLine.title = "Waiting for phone…"
            statusItem.button?.appearsDisabled = true
        case .connected(let device):
            statusLine.title = "Connected: \(device)"
            statusItem.button?.appearsDisabled = false
            UserDefaults.standard.set(true, forKey: "pairedOnce")
            pairWindow.close()
        }
    }

    @objc private func showPairing() {
        let info = PairingInfo(macId: pairing.macId, key: pairing.key,
                               host: NetworkInfo.primaryIPv4() ?? "0.0.0.0",
                               port: Int(server?.port ?? Self.port))
        pairWindow.show(uri: info.uri, subtitle: "Mac at \(info.host):\(info.port)")
    }

    @objc private func togglePause() {
        guard let bridge else { return }
        bridge.paused.toggle()
        pauseItem.state = bridge.paused ? .on : .off
    }

    @objc private func resetPairing() {
        bridge?.stop()
        pairing = newPairing()
        UserDefaults.standard.set(false, forKey: "pairedOnce")
        startBridge()
        showPairing()
    }

    private func notifyFile(_ url: URL) {
        let content = UNMutableNotificationContent()
        content.title = "\(url.lastPathComponent) from phone"
        content.body = "Saved in Downloads — press ⌘V to paste it"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "file", content: content, trigger: nil))
    }

    private func notifyReceived(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = "Copied from phone"
        content.body = ReceivedNotice.body(for: text)
        let request = UNNotificationRequest(identifier: "clip", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
