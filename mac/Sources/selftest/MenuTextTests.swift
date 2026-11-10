import ClipBridgeCore

func menuTextTests() {
    check(MenuText.status(.connected(device: "Alex's Galaxy S25"), paused: false) == "Connected to Alex's Galaxy S25",
          "connected status names the phone")
    check(MenuText.status(.waiting, paused: false) == "Waiting for your phone…", "waiting status")
    check(MenuText.status(.connected(device: "x"), paused: true) == "Paused — nothing is synced", "paused wins over connected")
    check(MenuText.offer("report.pdf") == "Ready to paste on phone: report.pdf", "offer line")
    check(MenuText.offer(String(repeating: "a", count: 80)).count <= 64, "long file names are shortened in the menu")
}
