package app.clipbridge.sync

import app.clipbridge.core.PairingInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingChangeTest {
    private fun pairing(id: String, host: String = "192.168.1.5") = PairingInfo(id, ByteArray(32) { 1 }, host, 50000)

    @Test fun firstPairingIsANewMac() = assertTrue(PairingChange.isNewMac(null, pairing("aaaa")))

    @Test fun differentMacIdIsANewMac() = assertTrue(PairingChange.isNewMac(pairing("aaaa"), pairing("bbbb")))

    @Test fun sameMacReScannedIsNotANewMac() =
        assertFalse("re-pairing the same Mac (e.g. after its Reset pairing) keeps the address and what waits",
            PairingChange.isNewMac(pairing("aaaa"), pairing("aaaa", host = "10.0.0.7")))
}
