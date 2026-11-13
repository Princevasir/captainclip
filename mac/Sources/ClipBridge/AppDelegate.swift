import AppKit
import ClipBridgeCore
import ServiceManagement
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    static let port: UInt16 = 52001

    private var statusItem: NSStatusItem!
    private let statusLine = NSMenuItem(title: "Starting…", action: nil, keyEquivalent: "")
    private let detailLine = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let offerLine = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let bluetoothLine = NSMenuItem(title: "Bluetooth fallback: starting…", action: nil, keyEquivalent: "")
    private var bleServer: BLEServer?
    private let pauseItem = NSMenuItem(title: "Pause syncing", action: nil, keyEquivalent: "")
    private let resumeItem = NSMenuItem(title: "Resume now", action: #selector(resumeNow), keyEquivalent: "")
    private var pauseTimer: Timer?
    private let loginItem = NSMenuItem(title: "Start CaptainClip at login", action: #selector(toggleLogin), keyEquivalent: "")
    private var bridge: Bridge?
    private var server: Server?
    private var pairing: (macId: String, key: Data)!
    private var lastStatus: Server.Status = .waiting
    private let keyStore = KeyStore()
    private let pairWindow = PairWindow()

    func applicationDidFinishLaunching(_ notification: Notification) {
        pairing = keyStore.load() ?? newPairing()
        buildMenu()
        startBridge()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert]) { _, _ in }
        if !UserDefaults.standard.bool(forKey: "pairedOnce") {
            showPairing()
        }
    }

    private func newPairing() -> (macId: String, key: Data) {
        let macId = (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let key = ClipCrypto.randomKey()
        keyStore.save(macId: macId, key: key)
        return (macId, key)
    }

    // MARK: Menu

    private func buildMenu() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = BrandIcon.menuBar()
        statusItem.button?.toolTip = "CaptainClip"
        let menu = NSMenu()
        menu.delegate = self
        [statusLine, detailLine, offerLine].forEach { $0.isEnabled = false }
        detailLine.isHidden = true
        offerLine.isHidden = true
        menu.addItem(statusLine)
        menu.addItem(detailLine)
        menu.addItem(offerLine)
        bluetoothLine.isEnabled = false
        menu.addItem(bluetoothLine)
        menu.addItem(.separator())
        menu.addItem(item("Pair phone…", #selector(showPairing), key: "p"))
        let pauseMenu = NSMenu()
        for (index, choice) in PauseChoice.allCases.enumerated() {
            let choiceItem = item(choice.title, #selector(pause(_:)))
            choiceItem.tag = index
            pauseMenu.addItem(choiceItem)
        }
        pauseItem.submenu = pauseMenu
        menu.addItem(pauseItem)
        resumeItem.target = self
        resumeItem.isHidden = true
        menu.addItem(resumeItem)
        menu.addItem(item("Open Downloads", #selector(openDownloads), key: "d"))
        menu.addItem(.separator())
        loginItem.target = self
        menu.addItem(loginItem)
        menu.addItem(item("Reset pairing…", #selector(resetPairing)))
        menu.addItem(.separator())
        menu.addItem(item("About CaptainClip", #selector(showAbout)))
        menu.addItem(withTitle: "Quit CaptainClip", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        statusItem.menu = menu
    }

    private func item(_ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = self
        return item
    }

    func menuWillOpen(_ menu: NSMenu) {
        loginItem.state = SMAppService.mainApp.status == .enabled ? .on : .off
        renderStatus()
    }

    private func renderStatus() {
        let paused = bridge?.isPaused ?? false
        pauseItem.isHidden = paused
        resumeItem.isHidden = !paused
        let color: NSColor
        switch (paused, lastStatus) {
        case (true, _): color = .systemGray
        case (false, .connected): color = .systemGreen
        case (false, .waiting): color = .systemOrange
        }
        let title = NSMutableAttributedString(string: "●  ", attributes: [.foregroundColor: color, .font: NSFont.menuFont(ofSize: 12)])
        let timed = bridge?.pausedUntil.flatMap { MenuText.pausedUntil($0, now: Date(), time: Self.shortTime) }
        title.append(NSAttributedString(string: timed ?? MenuText.status(lastStatus, paused: paused),
                                        attributes: [.font: NSFont.menuFont(ofSize: 0), .foregroundColor: NSColor.labelColor]))
        statusLine.attributedTitle = title
        let detail = MenuText.detail(lastStatus, paused: paused, bluetooth: server?.activeIsBluetooth ?? false)
        detailLine.isHidden = detail == nil
        if let detail {
            detailLine.attributedTitle = NSAttributedString(string: "     " + detail, attributes: [
                .font: NSFont.menuFont(ofSize: 11), .foregroundColor: NSColor.secondaryLabelColor])
        }
        statusItem.button?.appearsDisabled = paused || lastStatus == .waiting
    }

    private static func shortTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        formatter.dateStyle = .none
        return formatter.string(from: date)
    }

    // MARK: Bridge

    private func startBridge() {
        let server = Server(key: pairing.key, macId: pairing.macId, preferredPort: Self.port)
        let bridge = Bridge(server: server, watcher: PasteboardWatcher())
        bridge.onStatus = { [weak self] status in self?.show(status) }
        bridge.onReceived = { [weak self] text in self?.notifyReceived(text) }
        bridge.onFileReceived = { [weak self] url in self?.notifyFile(url) }
        bridge.onImageReceived = { [weak self] _ in self?.notifyImage() }
        bridge.onOfferChanged = { [weak self] name in
            self?.offerLine.title = name.map { MenuText.offer($0) } ?? ""
            self?.offerLine.isHidden = name == nil
        }
        do {
            try bridge.start()
            show(.waiting)
        } catch {
            statusLine.title = "Couldn't start: \(error.localizedDescription)"
        }
        self.server = server
        self.bridge = bridge
        schedulePauseTimer()
        bleServer?.stop()
        let ble = BLEServer(server: server, macId: pairing.macId)
        ble.onState = { [weak self] state in
            self?.bluetoothLine.title = switch state {
            case .ready: "Bluetooth fallback: ready"
            case .starting: "Bluetooth fallback: starting…"
            case .poweredOff: "Bluetooth fallback: Bluetooth is off"
            case .notAllowed: "Bluetooth fallback: not allowed (System Settings → Privacy & Security → Bluetooth)"
            case .unsupported: "Bluetooth fallback: unavailable"
            }
        }
        bleServer = ble
    }

    private func show(_ status: Server.Status) {
        lastStatus = status
        if case .connected = status {
            UserDefaults.standard.set(true, forKey: "pairedOnce")
            pairWindow.close()
        }
        renderStatus()
    }

    // MARK: Actions

    @objc private func showPairing() {
        let info = PairingInfo(macId: pairing.macId, key: pairing.key,
                               host: NetworkInfo.primaryIPv4() ?? "0.0.0.0",
                               port: Int(server?.port ?? Self.port))
        pairWindow.show(uri: info.uri, address: "\(info.host):\(info.port)")
    }

    @objc private func pause(_ sender: NSMenuItem) {
        guard let bridge else { return }
        let choice = PauseChoice.allCases[sender.tag]
        bridge.pausedUntil = PauseWindow.until(choice, now: bridge.now())
        bridge.paused = bridge.pausedUntil == nil
        schedulePauseTimer()
        renderStatus()
    }

    @objc private func resumeNow() {
        bridge?.resumed()
        schedulePauseTimer()
        renderStatus()
    }

    /// Re-checks a timed pause every 30 s so the menu and icon follow its end; runs only while one is set.
    private func schedulePauseTimer() {
        pauseTimer?.invalidate()
        pauseTimer = nil
        guard bridge?.pausedUntil != nil else { return }
        pauseTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self, let bridge = self.bridge, let until = bridge.pausedUntil else { return }
            if bridge.now() >= until { self.resumeNow() } else { self.renderStatus() }
        }
    }

    @objc private func openDownloads() {
        NSWorkspace.shared.open(FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0])
    }

    @objc private func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            alert("Couldn't change the login setting",
                  "Move CaptainClip.app to your Applications folder and try again.\n\n\(error.localizedDescription)")
        }
        loginItem.state = SMAppService.mainApp.status == .enabled ? .on : .off
    }

    @objc private func resetPairing() {
        NSApp.activate(ignoringOtherApps: true)
        let confirm = NSAlert()
        confirm.messageText = "Reset pairing?"
        confirm.informativeText = "CaptainClip will make a new key. Your phone stops syncing until you scan the new QR code."
        confirm.addButton(withTitle: "Reset")
        confirm.addButton(withTitle: "Cancel")
        confirm.alertStyle = .warning
        guard confirm.runModal() == .alertFirstButtonReturn else { return }
        bridge?.stop()
        pairing = newPairing()
        UserDefaults.standard.set(false, forKey: "pairedOnce")
        startBridge()
        showPairing()
    }

    @objc private func showAbout() {
        NSApp.activate(ignoringOtherApps: true)
        NSApp.orderFrontStandardAboutPanel(options: [
            .applicationIcon: BrandIcon.app(side: 256),
            .credits: NSAttributedString(string: "Share your clipboard and small files between this Mac and your Android phone on the same Wi-Fi. End-to-end encrypted, nothing leaves your network."),
        ])
    }

    private func alert(_ title: String, _ text: String) {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = title
        a.informativeText = text
        a.runModal()
    }

    // MARK: Notifications

    private func notifyFile(_ url: URL) {
        let content = UNMutableNotificationContent()
        content.title = "\(url.lastPathComponent) from phone"
        content.body = "Saved in Downloads — press ⌘V to paste it"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "file", content: content, trigger: nil))
    }

    private func notifyImage() {
        let content = UNMutableNotificationContent()
        content.title = "Image from phone"
        content.body = "Ready to paste — press ⌘V"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "image", content: content, trigger: nil))
    }

    private func notifyReceived(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = "Copied from phone"
        content.body = ReceivedNotice.body(for: text)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "clip", content: content, trigger: nil))
    }
}
