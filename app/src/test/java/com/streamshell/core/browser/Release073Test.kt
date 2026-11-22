package com.streamshell.core.browser

import com.streamshell.core.diag.DiagnosticsSummary
import com.streamshell.core.media.MediaScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.3: fixes from the log of 2026-11-22. */
class Release073Test {
    @Test fun fullscreenSwipeIsTakenFromThePageEarly() {
        val lock = 21f // ~8 dp
        assertNull(PlayerOpen.swipeLock(2f, -10f, lock)) // a tap's jitter or the very start: undecided, the page keeps it
        assertEquals(true, PlayerOpen.swipeLock(4f, -22f, lock)) // up at ~8 dp: ours before YouTube moves the player
        assertEquals(true, PlayerOpen.swipeLock(-10f, 25f, lock)) // down, a little slanted
        assertEquals(false, PlayerOpen.swipeLock(30f, 8f, lock)) // sideways: YouTube's
        assertNull(PlayerOpen.swipeLock(18f, 24f, lock)) // too slanted: not yet
    }

    @Test fun doubleTapJumpsAtOnceAndAddsUpWhileSettling() {
        val b = SeekBatch(busyMs = 350)
        assertEquals(10, b.add(10, now = 1000)) // the second tap of the series: jump now
        assertNull(b.add(10, now = 1200)) // while it settles: added up
        assertNull(b.add(10, now = 1300))
        assertTrue(b.waiting)
        assertEquals(1350L, b.readyAt())
        assertNull(b.due(now = 1340))
        assertEquals(20, b.due(now = 1350)) // one jump for the two
        assertFalse(b.waiting)
        assertNull(b.due(now = 2000))
        assertEquals(-10, b.add(-10, now = 2000)) // settled: the next tap jumps at once again
        assertNull(b.add(10, now = 2100))
        assertNull(b.add(-10, now = 2200)) // forward and back cancel out
        assertNull(b.due(now = 2400))
    }

    @Test fun settleResultIsParsed() {
        assertEquals(-36, MediaScripts.parseSettle("\"moved|-36|0\""))
        assertNull(MediaScripts.parseSettle("\"ok\""))
        assertNull(MediaScripts.parseSettle("\"none\""))
        assertNull(MediaScripts.parseSettle(null))
        assertTrue(MediaScripts.FULLSCREEN_SETTLE.contains("fullscreenElement"))
    }

    @Test fun liveChatFrameKeepsItsSwipes() {
        val js = YouTubeLiveChat.INSTALL
        assertTrue(js.contains("overscroll-behavior:contain"))
        assertTrue(js.contains(YouTubeLiveChat.CHAT_TOUCH))
        assertTrue(js.contains("e.origin!=='https://www.youtube.com'")) // the page only listens to YouTube's chat frame
    }

    @Test fun zeroFailedIsNoError() {
        val lines = listOf(
            "21:25:34.231 notify · background check: 175 channels · feeds 175 read, 0 failed · new 5 · shown 5",
            "21:55:54.536 notify · background check: 175 channels · feeds 170 read, 5 failed · new 0 · shown 0",
            "11:41:23.091 tab 7 · page finished (home) after 1230 ms · requests 33, blocked 2, video streams refused 0, failed 0"
        )
        val s = DiagnosticsSummary.of(lines)
        assertTrue(s, s.contains("errors 1"))
    }
}
