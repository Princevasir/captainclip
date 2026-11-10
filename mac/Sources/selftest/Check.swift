import Foundation

nonisolated(unsafe) var failures = 0
nonisolated(unsafe) var passes = 0

func check(_ condition: Bool, _ name: String) {
    if condition { passes += 1 } else { failures += 1; print("FAIL: \(name)") }
}

func checkThrows(_ name: String, _ body: () throws -> Void) {
    do { try body(); check(false, name + " (did not throw)") } catch { check(true, name) }
}

func bytes(_ range: ClosedRange<UInt8>) -> Data { Data(range) }

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
