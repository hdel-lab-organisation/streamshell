package com.streamshell.core.browser

import com.streamshell.core.media.MediaPolicy
import com.streamshell.core.media.MediaState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.5: fixes from the log of 2026-11-25 17:51. */
class Release075Test {
    @Test fun fullscreenEndsBeforeBackgroundPlayback() {
        assertTrue(MediaPolicy.leaveFullscreenForBackground(inPip = false, backgroundEnabled = true, media = MediaState.PLAYING, fullscreen = true))
        assertFalse(MediaPolicy.leaveFullscreenForBackground(inPip = true, backgroundEnabled = true, media = MediaState.PLAYING, fullscreen = true)) // PiP keeps it
        assertFalse(MediaPolicy.leaveFullscreenForBackground(inPip = false, backgroundEnabled = false, media = MediaState.PLAYING, fullscreen = true)) // paused anyway
        assertFalse(MediaPolicy.leaveFullscreenForBackground(inPip = false, backgroundEnabled = true, media = MediaState.PAUSED, fullscreen = true))
        assertFalse(MediaPolicy.leaveFullscreenForBackground(inPip = false, backgroundEnabled = true, media = MediaState.PLAYING, fullscreen = false))
    }

    @Test fun coldStartWindow() {
        assertTrue(PlayerOpen.coldStart(createdAt = 1000, now = 1500))
        assertTrue(PlayerOpen.coldStart(createdAt = 1000, now = 6999))
        assertFalse(PlayerOpen.coldStart(createdAt = 1000, now = 7000)) // an ordinary tap later on
        assertFalse(PlayerOpen.coldStart(createdAt = 1000, now = 500))
    }
}
