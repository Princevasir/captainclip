import ClipBridgeCore
import Foundation

func centralLinksTests() {
    final class Link { let name: String; var closed = false; init(_ n: String) { name = n } }
    var made = 0
    let links = CentralLinks<String, Link>(make: { key in made += 1; return Link("\(key)#\(made)") }, close: { $0.closed = true })

    let phone = links.subscribed("phone")
    let stranger = links.subscribed("scanner")
    check(!phone.closed, "another Bluetooth device subscribing does not drop the phone's link")
    check(links.link(for: "phone") === phone && links.link(for: "scanner") === stranger, "writes route to each device's own link")
    links.unsubscribed("scanner")
    check(stranger.closed && !phone.closed && links.link(for: "scanner") == nil, "a device leaving closes only its own link")
    let again = links.subscribed("phone")
    check(phone.closed && again !== phone, "the same phone subscribing again replaces its old link")
    links.closeAll()
    check(again.closed && links.all.isEmpty, "closeAll closes every link")
}
