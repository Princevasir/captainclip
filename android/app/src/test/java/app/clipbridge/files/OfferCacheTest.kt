package app.clipbridge.files

import app.clipbridge.core.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfferCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private val cache by lazy { OfferCache(tmp.newFolder("cache")) { now } }
    private val offer = Message.Offer("o1", "report.pdf", "application/pdf", 3, 1)
    private fun fetched(name: String = "report.pdf") = tmp.newFile(name).also { it.writeText("pdf") }

    @Test fun currentOfferAvailableForTenMinutes() {
        cache.set(offer)
        now += 9 * 60_000
        assertEquals(offer, cache.current())
        now += 2 * 60_000
        assertNull("expired after 10 minutes", cache.current())
    }

    @Test fun cachedCopyServedOnlyForCurrentOffer() {
        cache.set(offer)
        val f = fetched()
        assertTrue(cache.store("o1", f))
        assertEquals(f, cache.cachedFile("o1"))
        assertNull(cache.cachedFile("other"))
    }

    @Test fun fileFetchedIntoCacheFolderSurvivesBeingStored() {
        // The provider and Save fetch straight into fetchDir, then store — the file must still exist.
        cache.set(offer)
        val f = java.io.File(cache.fetchDir, "report.pdf").also { it.writeText("pdf") }
        assertTrue(cache.store("o1", f))
        assertTrue("stored file was deleted", f.exists())
        assertEquals(f, cache.cachedFile("o1"))
    }

    @Test fun storingANewCopyDeletesOnlyThePreviousOne() {
        cache.set(offer)
        val first = java.io.File(cache.fetchDir, "a.pdf").also { it.writeText("1") }
        cache.store("o1", first)
        val second = java.io.File(cache.fetchDir, "b.pdf").also { it.writeText("2") }
        cache.store("o1", second)
        assertFalse(first.exists()); assertTrue(second.exists())
    }

    @Test fun cachedCopyDeletedWhenOfferCleared() {
        cache.set(offer); val f = fetched(); cache.store("o1", f)
        cache.clear()
        assertFalse(f.exists()); assertNull(cache.current())
    }

    @Test fun cachedCopyDeletedWhenNextOfferArrives() {
        cache.set(offer); val f = fetched(); cache.store("o1", f)
        cache.set(Message.Offer("o2", "b.pdf", "application/pdf", 3, 2))
        assertFalse(f.exists()); assertNull(cache.cachedFile("o1"))
    }

    @Test fun cachedCopyDeletedWhenExpired() {
        cache.set(offer); val f = fetched(); cache.store("o1", f)
        now += 11 * 60_000
        assertNull(cache.cachedFile("o1"))
        assertFalse(f.exists())
    }

    @Test fun storingForStaleOfferDeletesTheFile() {
        cache.set(offer)
        val f = fetched()
        assertFalse(cache.store("stale", f))
        assertFalse(f.exists())
    }

    @Test fun purgeAllEmptiesTheFolder() {
        cache.set(offer); cache.store("o1", fetched())
        cache.purgeAll()
        assertNull(cache.cachedFile("o1"))
    }

    @Test fun sameOfferAgainIsNotNew() {
        // A reconnect re-sends the current offer; the phone must not re-place it over newer phone copies.
        assertTrue(cache.set(offer))
        assertFalse(cache.set(offer))
        assertTrue(cache.set(Message.Offer("o2", "b.pdf", "application/pdf", 3, 2)))
    }

    @Test fun reportsTimeSinceOffer() {
        cache.set(offer)
        now += 1_500
        assertEquals(1_500L, cache.msSinceOffer())
    }
}

class PreviewGuardTest {
    private val blocked = setOf("com.samsung.android.honeyboard", "com.android.systemui")

    @Test fun pasteIntoGmailIsAllowed() {
        assertTrue(PreviewGuard.allow(PreviewGuard.Method.OPEN_FILE, "com.google.android.gm", blocked, 5_000, sizeHint = false))
    }

    @Test fun thumbnailRequestsAreRefused() {
        assertFalse(PreviewGuard.allow(PreviewGuard.Method.OPEN_TYPED_ASSET, "com.google.android.gm", blocked, 5_000, sizeHint = false))
        assertFalse(PreviewGuard.allow(PreviewGuard.Method.OPEN_FILE, "com.google.android.gm", blocked, 5_000, sizeHint = true))
    }

    @Test fun keyboardAndSystemClipboardUiAreRefused() {
        assertFalse(PreviewGuard.allow(PreviewGuard.Method.OPEN_FILE, "com.samsung.android.honeyboard", blocked, 5_000, sizeHint = false))
        assertFalse(PreviewGuard.allow(PreviewGuard.Method.OPEN_FILE, "com.android.systemui", blocked, 5_000, sizeHint = false))
    }

    @Test fun opensInTheFirstSecondAfterTheOfferAreRefused() {
        assertFalse(PreviewGuard.allow(PreviewGuard.Method.OPEN_FILE, "com.google.android.gm", blocked, 999, sizeHint = false))
        assertTrue(PreviewGuard.allow(PreviewGuard.Method.OPEN_FILE, "com.google.android.gm", blocked, 1_000, sizeHint = false))
    }

    @Test fun unknownCallerIsAllowedAfterTheFirstSecond() {
        assertTrue(PreviewGuard.allow(PreviewGuard.Method.OPEN_FILE, null, blocked, 2_000, sizeHint = false))
    }
}
