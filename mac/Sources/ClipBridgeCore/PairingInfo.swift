import Foundation

/// What the Mac's QR code carries:
/// clipbridge://pair?v=1&id=<macId>&key=<base64url, no padding>&host=<IPv4>&port=<int>
public struct PairingInfo: Equatable {
    public let macId: String
    public let key: Data
    public let host: String
    public let port: Int

    public init(macId: String, key: Data, host: String, port: Int) {
        self.macId = macId
        self.key = key
        self.host = host
        self.port = port
    }

    public var uri: String {
        "clipbridge://pair?v=1&id=\(macId)&key=\(Self.base64url(key))&host=\(host)&port=\(port)"
    }

    public init?(uri: String) {
        guard let components = URLComponents(string: uri),
              components.scheme == "clipbridge", components.host == "pair"
        else { return nil }
        var params: [String: String] = [:]
        for item in components.queryItems ?? [] { params[item.name] = item.value }
        guard params["v"] == "1",
              let macId = params["id"], !macId.isEmpty,
              let keyText = params["key"], let key = Self.fromBase64url(keyText), key.count == 32,
              let host = params["host"], !host.isEmpty,
              let portText = params["port"], let port = Int(portText), (1...65535).contains(port)
        else { return nil }
        self.init(macId: macId, key: key, host: host, port: port)
    }

    static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func fromBase64url(_ text: String) -> Data? {
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s)
    }
}
