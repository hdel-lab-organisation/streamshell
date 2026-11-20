package com.streamshell.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dark Liquid Glass palette and the panel status ([CarTheme]); restored 2026-11-20. */
class CarThemeTest {
    @Test fun palette() {
        assertEquals(0xFF080B14.toInt(), CarTheme.BACKGROUND)
        assertEquals(0xFF171F32.toInt(), CarTheme.GLASS)
        assertEquals(0xFF8B5CF6.toInt(), CarTheme.VIOLET)
        assertEquals(0xFF42D9F5.toInt(), CarTheme.CYAN)
        assertEquals(0x1AFFFFFF, CarTheme.BORDER)
    }

    @Test fun status() {
        assertEquals("Nothing playing", CarTheme.status(playing = true, hasMedia = false, covered = false))
        assertEquals("Playing · sound only while driving", CarTheme.status(playing = true, hasMedia = true, covered = true))
        assertEquals("Playing", CarTheme.status(playing = true, hasMedia = true, covered = false))
        assertEquals("Paused", CarTheme.status(playing = false, hasMedia = true, covered = true))
    }

    @Test fun withAlpha() {
        assertEquals(0x668B5CF6, CarViews.withAlpha(CarTheme.VIOLET, 0x66))
    }
}
