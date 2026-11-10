package app.clipbridge.sync

import app.clipbridge.core.PairingInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Talks to the real running Mac app. Skipped unless CLIPBRIDGE_URI is set, e.g.
 * CLIPBRIDGE_URI="clipbridge://pair?..." ./gradlew testDebugUnitTest --tests '*MacInterop*'
 * Sends CLIPBRIDGE_SEND (if set) to the Mac and waits for one clip from the Mac if CLIPBRIDGE_EXPECT is set.
 */
class MacInteropTest {
    @Test fun talksToRealMacApp() {
        val uri = System.getenv("CLIPBRIDGE_URI")
        assumeTrue("CLIPBRIDGE_URI not set", uri != null)
        val info = PairingInfo.parse(uri!!)!!
        val connected = LinkedBlockingQueue<Unit>()
        val clips = LinkedBlockingQueue<String>()
        val conn = Connection(info.host, info.port, info.key, "kotlin-interop", object : Connection.Listener {
            override fun onConnected() { connected.put(Unit) }
            override fun onClip(text: String) { clips.put(text) }
            override fun onClosed() {}
        })
        conn.start()
        assertNotNull("connect to Mac", connected.poll(5, TimeUnit.SECONDS))
        System.getenv("CLIPBRIDGE_SEND")?.let { conn.send(it); Thread.sleep(1500) }
        System.getenv("CLIPBRIDGE_EXPECT")?.let { assertEquals(it, clips.poll(10, TimeUnit.SECONDS)) }
        conn.close()
    }
}
