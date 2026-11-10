package app.clipbridge.files

import app.clipbridge.core.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfferSetTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private val cache by lazy { OfferCache(tmp.newFolder("cache")) { now } }
    private val set = listOf(
        Message.Offer("a", "a.pdf", "application/pdf", 3, 9),
        Message.Offer("b", "b.docx", "application/msword", 4, 9),
        Message.Offer("c", "c.png", "image/png", 5, 9),
    )

    @Test fun parsesMacOffersMessage() {
        val mac = """{"items":[{"id":"a","mime":"application/pdf","name":"a.pdf","size":3},{"id":"b","mime":"application/msword","name":"b.docx","size":4}],"t":"offers","ts":9}"""
        val parsed = Message.parse(mac) as Message.Offers
        assertEquals(listOf(set[0], set[1]), parsed.offers)
        assertEquals(parsed, Message.parse(parsed.toJson()))
    }

    @Test fun cacheHoldsTheWholeSet() {
        assertTrue(cache.setAll(set))
        assertEquals(set, cache.currentAll())
        assertEquals("a", cache.current()?.id)
        assertEquals(set[1], cache.offer("b"))
    }

    @Test fun eachFileKeepsItsOwnCachedCopy() {
        cache.setAll(set)
        val fa = java.io.File(cache.fetchDir, "a.pdf").also { it.writeText("a") }
        val fb = java.io.File(cache.fetchDir, "b.docx").also { it.writeText("b") }
        assertTrue(cache.store("a", fa)); assertTrue(cache.store("b", fb))
        assertEquals(fa, cache.cachedFile("a")); assertEquals(fb, cache.cachedFile("b"))
        assertTrue(fa.exists() && fb.exists())
    }

    @Test fun clearAndExpiryApplyToTheWholeSet() {
        cache.setAll(set)
        now += 11 * 60_000
        assertTrue(cache.currentAll().isEmpty())
        cache.setAll(set.map { it.copy(id = it.id + "2") })
        cache.clear()
        assertNull(cache.offer("a2"))
    }

    @Test fun theSameSetResentIsNotNew() {
        assertTrue(cache.setAll(set))
        assertFalse(cache.setAll(set))
    }
}
