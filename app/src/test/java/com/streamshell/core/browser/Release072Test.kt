package com.streamshell.core.browser

import com.streamshell.core.diag.DiagnosticsSummary
import com.streamshell.core.media.MediaPolicy
import com.streamshell.core.media.MediaState
import com.streamshell.feature.browser.PlayerBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.2: fixes from the logs of 2026-11-06 and 2026-11-19. */
class Release072Test {
    @Test fun doubleTapSidesAndSeries() {
        assertEquals(false, PlayerOpen.tapSide(100f, 900f))
        assertEquals(true, PlayerOpen.tapSide(800f, 900f))
        assertNull(PlayerOpen.tapSide(450f, 900f)) // middle: play / pause stays YouTube's
        assertNull(PlayerOpen.tapSide(10f, 0f))
        assertTrue(PlayerOpen.continuesSeries(lastUp = 1000, downAt = 1300, lastSide = true, side = true))
        assertFalse(PlayerOpen.continuesSeries(lastUp = 1000, downAt = 1300, lastSide = false, side = true)) // other side
        assertFalse(PlayerOpen.continuesSeries(lastUp = 1000, downAt = 1000 + PlayerOpen.SERIES_GAP_MS + 1, lastSide = true, side = true))
        assertFalse(PlayerOpen.continuesSeries(lastUp = 0, downAt = 100, lastSide = true, side = true)) // first tap
        assertFalse(PlayerOpen.continuesSeries(lastUp = 1000, downAt = 1100, lastSide = null, side = null))
    }

    @Test fun fullscreenRestartIsUndoneOnlyWhenClearlyBehind() {
        assertEquals(4.0, PlayerOpen.restartTarget(0.3, 4.0)!!, 0.0) // started again at 0
        assertNull(PlayerOpen.restartTarget(3.5, 4.0)) // normal
        assertNull(PlayerOpen.restartTarget(0.0, 1.5)) // too early to tell
    }

    @Test fun musicTrackSwipeArea() {
        val d = 2.5f
        val h = 2000f
        val coverBottom = 900f
        // Cover and title (log 2026-11-06: swipes below the cover did nothing).
        assertTrue(PlayerBar.inTrackSwipeArea(400f, h, coverBottom, 1000f, d, bar = false))
        assertTrue(PlayerBar.inTrackSwipeArea(coverBottom + 80 * d, h, coverBottom, 1000f, d, bar = false))
        // The progress bar below stays for seeking.
        assertFalse(PlayerBar.inTrackSwipeArea(coverBottom + 140 * d, h, coverBottom, 1000f, d, bar = false))
        // Player collapsed: Music's own bar at the bottom only.
        assertTrue(PlayerBar.inTrackSwipeArea(h - 30 * d, h, -1f, 1000f, d, bar = true))
        assertFalse(PlayerBar.inTrackSwipeArea(h / 2, h, -1f, 1000f, d, bar = true))
    }

    @Test fun noPictureInPictureForFeedPreviews() {
        assertFalse(MediaPolicy.shouldAutoPip(true, true, false, MediaState.PLAYING, music = false, feed = true))
        assertTrue(MediaPolicy.shouldAutoPip(true, true, false, MediaState.PLAYING, music = false, feed = false))
        assertTrue(MediaPolicy.shouldAutoPip(true, true, true, MediaState.PLAYING, music = false, feed = true)) // fullscreen
    }

    @Test fun summaryCountsRealErrorsAndSeekWaits() {
        val lines = listOf(
            "11:41:23.091 tab 7 · page finished (home) after 1230 ms · requests 33, blocked 2, video streams refused 0, failed 0",
            "11:41:24.091 tab 7 · page finished (home) after 1230 ms · requests 33, blocked 2, video streams refused 0, failed 3",
            "11:41:25.000 tab 8 · page error: x is undefined",
            "11:45:20.139 tab 8 · seek wait 312 ms",
            "11:45:21.139 tab 8 · buffering 42 ms"
        )
        val s = DiagnosticsSummary.of(lines)
        assertTrue(s, s.contains("errors 2"))
        assertTrue(s, s.contains("seek waits: 1, 312 ms"))
        assertTrue(s, s.contains("buffering: 1 times, 42 ms"))
    }

    @Test fun musicTapScript() {
        val on = MusicSite.tapScript(trace = true)
        assertTrue(on.startsWith("(function(){window.__ssMusicTrace=true;"))
        assertTrue(MusicSite.tapScript(trace = false).contains("__ssMusicTrace=false"))
        // Only title links inside Music's list entries, only after Music itself did nothing.
        assertTrue(on.contains("ytmusic-responsive-list-item-renderer,ytmusic-two-row-item-renderer"))
        assertTrue(on.contains("if(d.clicked||location.href!==href)return;"))
        assertTrue(on.contains("," + MusicSite.TAP_FALLBACK_MS + ");"))
    }
}
