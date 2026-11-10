import ClipBridgeCore

func menuTextTests() {
    check(MenuText.status(.connected(device: "Alex's Galaxy S25"), paused: false) == "Connected to Alex's Galaxy S25",
          "connected status names the phone")
    check(MenuText.status(.waiting, paused: false) == "Waiting for your phone…", "waiting status")
    check(MenuText.status(.connected(device: "x"), paused: true) == "Paused — nothing is synced", "paused wins over connected")
    check(MenuText.detail(.connected(device: "x"), paused: false, bluetooth: false) == "Same Wi-Fi · end-to-end encrypted",
          "Wi-Fi detail line")
    check(MenuText.detail(.connected(device: "x"), paused: false, bluetooth: true) == "Via Bluetooth · end-to-end encrypted",
          "Bluetooth detail line says Bluetooth")
    check(MenuText.detail(.waiting, paused: false, bluetooth: false) == "Open ClipBridge on your phone — same Wi-Fi, or nearby with Bluetooth",
          "waiting detail mentions both ways to connect")
    check(MenuText.detail(.connected(device: "x"), paused: true, bluetooth: true) == nil, "no detail while paused")
    check(MenuText.offer("report.pdf") == "Ready to paste on phone: report.pdf", "offer line")
    check(MenuText.offer(String(repeating: "a", count: 80)).count <= 64, "long file names are shortened in the menu")
}
