package com.streamshell.core.browser

import com.streamshell.core.diag.DiagnosticsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.4: fixes from the log of 2026-11-25 (white pages, jumps, a closed mini player working on). */
class Release074Test {
    @Test fun barChangesInsideTheLoadedPage() {
        val home = "https://m.youtube.com/"
        val subs = "https://m.youtube.com/feed/subscriptions"
        assertEquals(BarNav.How.IN_PAGE, BarNav.how("https://m.youtube.com/@SomeChannel", home))
        assertEquals(BarNav.How.IN_PAGE, BarNav.how(home, subs))
        assertEquals(BarNav.How.TOP, BarNav.how("https://m.youtube.com", home)) // already home: to the top
        assertEquals(BarNav.How.TOP, BarNav.how("https://m.youtube.com/feed/subscriptions/", subs))
        assertEquals(BarNav.How.LOAD, BarNav.how(null, home)) // nothing loaded yet
        assertEquals(BarNav.How.LOAD, BarNav.how("https://music.youtube.com/", "https://music.youtube.com/explore")) // Music: as before
        assertEquals(BarNav.How.LOAD, BarNav.how("https://m.youtube.com/shorts/aaaaaaaaaaa", home)) // Shorts' own player page
        assertEquals(BarNav.How.LOAD, BarNav.how(home, "https://m.youtube.com/shorts/"))
        assertEquals(BarNav.How.LOAD, BarNav.how("https://example.com/", home))
    }

    @Test fun secondTapWithinHalfASecondIsIgnored() {
        val home = "https://m.youtube.com/"
        assertTrue(BarNav.repeated(home, 19_326, home, 19_894)) // the two loads of 16:45:19
        assertFalse(BarNav.repeated(home, 10_000, home, 11_600))
        assertFalse(BarNav.repeated("https://m.youtube.com/feed/subscriptions", 19_326, home, 19_894))
    }

    @Test fun fallbackOnlyWhenYouTubeDidNotFollow() {
        val subs = "https://m.youtube.com/feed/subscriptions"
        assertTrue(BarNav.needsLoad("/", "/", subs)) // still home after 2.5 s: load it
        assertFalse(BarNav.needsLoad("/feed/subscriptions", "/", subs)) // arrived
        assertFalse(BarNav.needsLoad("/@SomeChannel", "/", subs)) // the user went on elsewhere meanwhile: leave it
        assertFalse(BarNav.needsLoad(null, "/", subs))
        assertEquals("/results?search_query=x", BarNav.pathOf("https://m.youtube.com/results?search_query=x"))
        assertEquals("/feed/library", BarNav.parseCheck("\"/feed/library\""))
        assertNull(BarNav.parseCheck("null"))
    }

    @Test fun playerTabPagesGoToTheInstance() {
        assertTrue(YouTubePlayerLinks.accepts("https://m.youtube.com/@SomeChannel"))
        assertTrue(YouTubePlayerLinks.accepts("https://m.youtube.com/channel/UCabc/videos"))
        assertTrue(YouTubePlayerLinks.accepts("https://m.youtube.com/results?search_query=x"))
        assertTrue(YouTubePlayerLinks.accepts("https://m.youtube.com/playlist?list=PL1"))
        assertFalse(YouTubePlayerLinks.accepts("https://m.youtube.com/watch?v=aaaaaaaaaaa")) // the player's own
        assertFalse(YouTubePlayerLinks.accepts("https://m.youtube.com/shorts/aaaaaaaaaaa"))
        assertFalse(YouTubePlayerLinks.accepts("https://example.com/@x"))
        assertFalse(YouTubePlayerLinks.accepts("http://m.youtube.com/@x"))
    }

    @Test fun emptyPagesInTheSummary() {
        val s = DiagnosticsSummary.of(
            listOf(
                "16:45:05.000 tab 8 · page empty for 2 s on /@ch (still)",
                "16:45:09.500 tab 8 · page empty 4500 ms on /@ch",
                "16:46:00.000 tab 7 · page empty 2100 ms on /"
            )
        )
        assertTrue(s, s.contains("empty pages: 2, longest 4500 ms"))
        assertFalse(DiagnosticsSummary.of(listOf("16:45:05.000 tab 8 · page visible (home) after 500 ms")).contains("empty pages"))
    }
}
