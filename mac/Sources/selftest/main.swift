import Foundation

do {
    try cryptoTests()
    try frameTests()
    try messageTests()
    try pairingTests()
    loopGuardTests()
    try serverTests()
    try wrongKeyTests()
    try idleTimeoutTests()
    try fixedPortTests()
    try handshakeTests()
    noticeTests()
    watcherTests()
    keyStoreTests()
    networkInfoTests()
    try bridgeTests()
    try textLimitTests()
    try bridgeSkipsHugeCopy()
} catch {
    failures += 1
    print("FAIL: unexpected error \(error)")
}

print(failures == 0 ? "ALL PASS (\(passes) checks)" : "\(failures) FAILED, \(passes) passed")
exit(failures == 0 ? 0 : 1)
