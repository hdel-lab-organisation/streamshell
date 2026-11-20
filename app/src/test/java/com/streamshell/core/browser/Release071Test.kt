package com.streamshell.core.browser

import com.streamshell.core.media.MediaPolicy
import com.streamshell.core.media.MediaState
import com.streamshell.core.sponsor.SponsorBlockApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.1 (log of 2026-11-03 08:03): YouTube Music's player page was never recognised. */
class Release071Test {
    @Test fun musicPlayerPageIsRecognised() {
        val url = "https://music.youtube.com/watch?v=abcdefghijk&list=RDAMVM"
        assertEquals("abcdefghijk", MusicSite.playerVideoId(url))
        assertNull(SponsorBlockApi.videoIdOf(url)) // the cause: Music is left out there on purpose
        assertNull(MusicSite.playerVideoId("https://music.youtube.com/library"))
        assertNull(MusicSite.playerVideoId("https://m.youtube.com/watch?v=abcdefghijk")) // YouTube, not Music
        assertNull(MusicSite.playerVideoId("https://music.youtube.com/watch?v=bad"))
        assertNull(MusicSite.playerVideoId(null))
    }

    @Test fun noPictureInPictureForMusic() {
        assertTrue(MediaPolicy.shouldAutoPip(true, true, false, MediaState.PLAYING, music = false))
        assertFalse(MediaPolicy.shouldAutoPip(true, true, false, MediaState.PLAYING, music = true))
        assertFalse(MediaPolicy.shouldAutoPip(true, true, true, MediaState.PLAYING, music = true))
    }
}

class Release071OpenTest {
    @Test fun closedVideoIsNotReopenedRightAway() {
        assertTrue(PlayerOpen.justClosed("abc", "abc", 1000, 2000))
        assertFalse(PlayerOpen.justClosed("abc", "abc", 1000, 1000 + PlayerOpen.CLOSED_GRACE_MS + 1))
        assertFalse(PlayerOpen.justClosed("abc", "xyz", 1000, 1500))
        assertFalse(PlayerOpen.justClosed("abc", null, 1000, 1500))
    }

    @Test fun startupOnlyFromARecentTap() {
        assertEquals(711L, PlayerOpen.startupMs(1000, 1711))
        assertNull(PlayerOpen.startupMs(1000, 1000 + 319_604)) // the 2026-11-05 log
        assertNull(PlayerOpen.startupMs(2000, 1000))
    }

    @Test fun shortsPageKind() {
        assertEquals("shorts", PageKind.of("https://m.youtube.com/shorts/abcdefghijk"))
        assertEquals("video", PageKind.of("https://m.youtube.com/watch?v=abcdefghijk"))
    }
}
