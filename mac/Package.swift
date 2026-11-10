// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "ClipBridge",
    platforms: [.macOS(.v13)],
    targets: [
        .target(name: "ClipBridgeCore"),
        .executableTarget(name: "selftest", dependencies: ["ClipBridgeCore"]),
        .executableTarget(name: "probe", dependencies: ["ClipBridgeCore"]),
        .executableTarget(name: "ClipBridge", dependencies: ["ClipBridgeCore"]),
        .executableTarget(name: "iconmaker", dependencies: ["ClipBridgeCore"]),
    ]
)
