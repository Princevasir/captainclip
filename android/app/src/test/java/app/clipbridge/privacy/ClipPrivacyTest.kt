package app.clipbridge.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipPrivacyTest {
    private val dirty = "https://ex.com/a?utm_source=x&id=7"
    private val clean = "https://ex.com/a?id=7"

    @Test fun codeFromMacIsSensitiveNotRecorded() =
        assertEquals(
            Incoming("482913", putOnClipboard = true, sensitive = true, recordInHistory = false),
            ClipPrivacy.incoming("482913", null, cleanLinks = true, macToClipboard = true, includeFromMac = true),
        )

    @Test fun linkCleanedWhenOn() =
        assertEquals(
            Incoming(clean, putOnClipboard = true, sensitive = false, recordInHistory = true),
            ClipPrivacy.incoming(dirty, null, cleanLinks = true, macToClipboard = true, includeFromMac = true),
        )

    @Test fun linkUntouchedWhenOffOrHtml() {
        assertEquals(
            Incoming(dirty, putOnClipboard = true, sensitive = false, recordInHistory = true),
            ClipPrivacy.incoming(dirty, null, cleanLinks = false, macToClipboard = true, includeFromMac = true),
        )
        assertEquals(
            Incoming(dirty, putOnClipboard = true, sensitive = false, recordInHistory = true),
            ClipPrivacy.incoming(dirty, "<a href=\"x\">x</a>", cleanLinks = true, macToClipboard = true, includeFromMac = true),
        )
    }

    @Test fun offRecordsEvenWithoutIncludeFromMac() =
        assertEquals(
            Incoming("hello", putOnClipboard = false, sensitive = false, recordInHistory = true),
            ClipPrivacy.incoming("hello", null, cleanLinks = true, macToClipboard = false, includeFromMac = false),
        )

    @Test fun offAndCodeIsDropped() =
        assertEquals(
            Incoming("482913", putOnClipboard = false, sensitive = true, recordInHistory = false),
            ClipPrivacy.incoming("482913", null, cleanLinks = true, macToClipboard = false, includeFromMac = false),
        )

    @Test fun outgoingCodeIsSensitive() =
        assertEquals(Outgoing("482913", sensitive = true), ClipPrivacy.outgoing("482913", null, cleanLinks = true))

    @Test fun outgoingLinkCleaned() {
        assertEquals(Outgoing(clean, sensitive = false), ClipPrivacy.outgoing(dirty, null, cleanLinks = true))
        assertEquals(Outgoing(dirty, sensitive = false), ClipPrivacy.outgoing(dirty, null, cleanLinks = false))
        assertEquals(Outgoing(dirty, sensitive = false), ClipPrivacy.outgoing(dirty, "<b>x</b>", cleanLinks = true))
    }

    @Test fun macCopyNotKeptByDefault() =
        assertEquals(
            Incoming("hello", putOnClipboard = true, sensitive = false, recordInHistory = false),
            ClipPrivacy.incoming("hello", null, cleanLinks = true, macToClipboard = true, includeFromMac = false),
        )

    @Test fun codeSensitiveWhatever() {
        assertEquals(Outgoing("482913", sensitive = true), ClipPrivacy.outgoing("482913", null, cleanLinks = false))
        assertEquals(
            Incoming("482913", putOnClipboard = true, sensitive = true, recordInHistory = false),
            ClipPrivacy.incoming("482913", "<b>482913</b>", cleanLinks = true, macToClipboard = true, includeFromMac = true),
        )
    }
}
