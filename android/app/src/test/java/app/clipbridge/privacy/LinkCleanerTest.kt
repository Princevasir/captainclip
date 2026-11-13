package app.clipbridge.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCleanerTest {
    @Test fun removesTrackers() = assertEquals("https://ex.com/a?id=7", LinkCleaner.clean("https://ex.com/a?utm_source=x&id=7&fbclid=ab"))

    @Test fun dropsEmptyQuery() = assertEquals("https://ex.com/a", LinkCleaner.clean("https://ex.com/a?utm_medium=mail&gclid=1"))

    @Test fun keepsFragmentAndOrder() = assertEquals("https://ex.com/p?b=2&a=1#top", LinkCleaner.clean("https://ex.com/p?b=2&utm_campaign=z&a=1#top"))

    @Test fun keepsEncoding() = assertEquals("https://ex.com/s?q=a%20b", LinkCleaner.clean("https://ex.com/s?q=a%20b&igshid=9"))

    @Test fun trimsAroundUrl() = assertEquals("https://ex.com/a", LinkCleaner.clean("  https://ex.com/a?mc_eid=1  "))

    @Test fun leavesOtherTextAlone() = listOf(
        "see https://ex.com/a?utm_source=x",
        "mailto:a@b.c?utm_source=x",
        "ftp://ex.com/?utm_source=x",
        "https://ex.com/a?id=7",
        "  https://ex.com/a?id=7  ",
    ).forEach { assertEquals(it, LinkCleaner.clean(it)) }

    @Test fun allListedParams() = listOf(
        "utm_source", "fbclid", "gclid", "gbraid", "wbraid", "dclid", "msclkid",
        "mc_cid", "mc_eid", "igshid", "yclid", "_hsenc", "_hsmi",
    ).forEach { assertEquals(it, "https://ex.com/a?id=7", LinkCleaner.clean("https://ex.com/a?$it=1&id=7")) }

    @Test fun matchesNamesCaseSensitively() = "https://ex.com/a?UTM_source=1&FBCLID=2".let { assertEquals(it, LinkCleaner.clean(it)) }

    @Test fun keepsCaseOfSchemeAndRest() = assertEquals("HTTPS://Ex.com/A?Id=7", LinkCleaner.clean("HTTPS://Ex.com/A?Id=7&fbclid=1"))

    @Test fun keepsFragmentWhenQueryEmptied() = assertEquals("https://ex.com/a#top", LinkCleaner.clean("https://ex.com/a?fbclid=1#top"))

    @Test fun removesBareParamWithoutValue() = assertEquals("https://ex.com/a?id=7", LinkCleaner.clean("https://ex.com/a?fbclid&id=7"))

    @Test fun keepsLookAlikeNames() = "https://ex.com/a?notfbclid=1&utmost=2&xutm_source=3".let { assertEquals(it, LinkCleaner.clean(it)) }

    @Test fun ignoresQuestionMarkAfterHash() = "https://ex.com/a#x?utm_source=1".let { assertEquals(it, LinkCleaner.clean(it)) }

    @Test fun untrimmedInputWithNothingToRemoveIsReturnedAsIs() = "  https://ex.com/a  ".let { assertEquals(it, LinkCleaner.clean(it)) }

    @Test fun trailingAmpersandAfterTracker() = assertEquals("https://ex.com/a", LinkCleaner.clean("https://ex.com/a?utm_x=1&"))

    @Test fun valuelessUtmParam() = assertEquals("https://ex.com/a?id=7", LinkCleaner.clean("https://ex.com/a?utm_source&id=7"))

    @Test fun leadingEmptySegmentWithNothingToRemoveIsUntouched() = "https://ex.com/a?&id=7".let { assertEquals(it, LinkCleaner.clean(it)) }
}
