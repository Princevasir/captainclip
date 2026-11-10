package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.FileLimit
import app.clipbridge.core.Message
import app.clipbridge.core.PairingInfo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class SyncLoopFileTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = ClipCrypto.randomKey()
    private val uploadDir by lazy { tmp.newFolder("mac-downloads") }
    private val mac by lazy { MultiFakeMac(key, uploadDir = uploadDir) }
    private val offers = CopyOnWriteArrayList<Message.Offer?>()
    private val statuses = CopyOnWriteArrayList<SyncLoop.Status>()
    private val loop by lazy {
        SyncLoop(
            pairing = { PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port) },
            locate = { null },
            device = "test-phone",
            callbacks = object : SyncLoop.Callbacks {
                override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
                override fun onClip(text: String) {}
                override fun onOffer(offer: Message.Offer?) { offers.add(offer) }
            },
            backoff = Backoff(longArrayOf(50)),
            pingIntervalMs = 500,
        )
    }

    @After fun tearDown() { loop.stop(); mac.close() }

    private fun connected() {
        loop.start()
        val end = System.currentTimeMillis() + 3_000
        while (statuses.lastOrNull() != SyncLoop.Status.CONNECTED && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(SyncLoop.Status.CONNECTED, statuses.lastOrNull())
    }

    private fun parts() = tmp.root.walkTopDown().filter { it.name.endsWith(".clipbridge-part") }.toList()

    @Test fun offerAndClearReachCallbacks() {
        connected()
        val offer = Message.Offer("o1", "report.pdf", "application/pdf", 10, 1)
        mac.send(offer)
        mac.send(Message.OfferClear)
        val end = System.currentTimeMillis() + 2_000
        while (offers.size < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(listOf(offer, null), offers.toList())
    }

    @Test fun fetchReturnsVerifiedFile() {
        val bytes = Random(1).nextBytes(700_000)
        mac.served["o1"] = bytes
        connected()
        val file = loop.fetch("o1", tmp.newFolder("cache"))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun concurrentFetchesOfSameOfferShareOneTransfer() {
        mac.served["o1"] = Random(2).nextBytes(500_000)
        connected()
        val dir = tmp.newFolder("cache")
        val results = CopyOnWriteArrayList<ByteArray>()
        val threads = List(2) { Thread { results.add(loop.fetch("o1", dir).readBytes()) }.apply { start() } }
        threads.forEach { it.join(10_000) }
        assertEquals(2, results.size)
        assertEquals("fetches on the wire", 1, mac.received.count { it is Message.Fetch })
    }

    @Test fun refusedFetchThrowsWithReason() {
        mac.refused["o1"] = "expired"
        connected()
        try { loop.fetch("o1", tmp.newFolder("cache")); fail() } catch (e: FileFetchException) { assertEquals("expired", e.reason) }
    }

    @Test fun connectionDropMidFetchThrowsAndLeavesNoPartialFile() {
        mac.served["o1"] = Random(3).nextBytes(1_000_000)
        mac.dropAfterChunks = 2
        connected()
        try { loop.fetch("o1", tmp.newFolder("cache"), timeoutMs = 5_000); fail() } catch (e: FileFetchException) { assertEquals("io", e.reason) }
        assertTrue("partial temp files: ${parts()}", parts().isEmpty())
    }

    @Test fun fetchWhileDisconnectedFailsFast() {
        try { loop.fetch("o1", tmp.newFolder("cache"), timeoutMs = 1_000); fail() } catch (e: FileFetchException) { assertEquals("not-connected", e.reason) }
    }

    @Test fun sendFileArrivesIntactWithEndAfterAllChunks() {
        val bytes = Random(4).nextBytes(2_000_000)  // 11 chunks: file-end must not overtake them
        connected()
        assertTrue(loop.sendFile("scan.pdf", "application/pdf", bytes.size.toLong()) { ByteArrayInputStream(bytes) })
        val end = System.currentTimeMillis() + 5_000
        while (mac.uploads.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("scan.pdf", mac.uploads.single().name)
        assertArrayEquals(bytes, mac.uploads.single().readBytes())
    }

    @Test fun sendFileOverLimitIsRefused() {
        connected()
        assertFalse(loop.sendFile("big.mov", "video/quicktime", FileLimit.MAX_BYTES + 1) { ByteArrayInputStream(ByteArray(0)) })
    }
}
