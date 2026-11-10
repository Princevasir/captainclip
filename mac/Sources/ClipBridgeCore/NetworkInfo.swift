import Darwin
import Foundation

public enum NetworkInfo {
    /// The Mac's LAN IPv4 address (prefers en0, the usual Wi-Fi interface).
    public static func primaryIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        var candidates: [(name: String, ip: String)] = []
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  (ifa.ifa_flags & UInt32(IFF_UP)) != 0,
                  (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") || ip.hasPrefix("127.") { continue }
            candidates.append((String(cString: ifa.ifa_name), ip))
        }
        return candidates.first { $0.name == "en0" }?.ip
            ?? candidates.first { $0.name.hasPrefix("en") }?.ip
            ?? candidates.first?.ip
    }
}
