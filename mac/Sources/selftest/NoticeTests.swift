import ClipBridgeCore

func noticeTests() {
    let body = ReceivedNotice.body(for: "my bank PIN is 4821")
    check(!body.contains("4821") && !body.contains("PIN"), "notification never shows the copied text")
    check(body.contains("19"), "notification says how many characters arrived")
}
