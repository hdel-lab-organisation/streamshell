package com.streamshell.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Car screen size and layout rules ([CarDisplay]); restored 2026-11-20 together with the 0.7.1 source. */
class CarLayoutTest {
    @Test fun ds7GetsAPhoneLikeHeight() {
        // DS7: 1200×600 px, the host says 240 dpi → StreamShell uses 200 dpi = 960×480 dp.
        val dpi = CarDisplay.optimalDpi(1200, 600, 240)
        assertEquals(200, dpi)
        assertEquals(960, CarDisplay.dp(1200, dpi))
        assertEquals(480, CarDisplay.dp(600, dpi))
    }

    @Test fun optimalDpiKeepsAMinimumWidthAndLimits() {
        // Narrow screen: the width limit wins.
        val narrow = CarDisplay.optimalDpi(800, 600, 160)
        assertTrue(CarDisplay.dp(800, narrow) >= CarDisplay.MIN_WIDTH_DP)
        assertEquals(CarDisplay.MAX_DPI, CarDisplay.optimalDpi(10_000, 10_000, 160))
        assertEquals(CarDisplay.MIN_DPI, CarDisplay.optimalDpi(300, 200, 160))
        assertEquals(160, CarDisplay.optimalDpi(0, 0, 160))
    }

    @Test fun insetsFromTheVisibleArea() {
        // DS7: visible 18,132 – 1182,582 → top band 132 px is the header.
        val i = CarDisplay.insets(1200, 600, 18, 132, 1182, 582)
        assertEquals(CarDisplay.Insets(18, 132, 18, 18), i)
        // Thin frame drawn over (edge to edge), the header band stays.
        assertEquals(CarDisplay.Insets(0, 132, 0, 0), CarDisplay.edgeToEdge(i, 48))
        // A host panel over one side never takes more than half the screen.
        assertEquals(600, CarDisplay.insets(1200, 600, 1000, 0, 1200, 600).left)
        assertEquals(CarDisplay.Insets(0, 0, 0, 0), CarDisplay.insets(0, 0, 0, 0, 0, 0))
    }

    @Test fun clockAndProgress() {
        assertEquals("1:05", CarDisplay.clock(65.0))
        assertEquals("1:02:03", CarDisplay.clock(3723.0))
        assertEquals("", CarDisplay.clock(Double.NaN))
        assertEquals(500, CarDisplay.progress(30.0, 60.0))
        assertEquals(0, CarDisplay.progress(10.0, Double.NaN))
        assertEquals(CarDisplay.PROGRESS_MAX, CarDisplay.progress(90.0, 60.0))
    }

    @Test fun scrollScriptDirection() {
        assertTrue(CarDisplay.scrollScript(-1).contains("top:-1*"))
        assertTrue(CarDisplay.scrollScript(1).contains("top:1*"))
    }

    // 0.7.0, option C: adaptive navigation

    @Test fun navPlacementByAspect() {
        assertEquals(CarDisplay.NavPlacement.SIDE, CarDisplay.navPlacement(960, 480)) // DS7, 2:1
        assertEquals(CarDisplay.NavPlacement.SIDE, CarDisplay.navPlacement(800, 500)) // exactly 1.6:1
        assertEquals(CarDisplay.NavPlacement.BOTTOM, CarDisplay.navPlacement(640, 480)) // 4:3
        assertEquals(CarDisplay.NavPlacement.BOTTOM, CarDisplay.navPlacement(600, 900)) // portrait
        assertEquals(CarDisplay.NavPlacement.MENU, CarDisplay.navPlacement(480, 400)) // very small
        assertEquals(CarDisplay.NavPlacement.MENU, CarDisplay.navPlacement(900, 300))
    }

    @Test fun navItemsFollowThePage() {
        val feed = CarDisplay.navItems(videoPage = false, covered = false)
        assertTrue(feed.containsAll(listOf(CarDisplay.NavItem.HOME, CarDisplay.NavItem.SEARCH, CarDisplay.NavItem.BACK, CarDisplay.NavItem.SCROLL_UP, CarDisplay.NavItem.SCROLL_DOWN)))
        val video = CarDisplay.navItems(videoPage = true, covered = false)
        assertEquals(listOf(CarDisplay.NavItem.BACK, CarDisplay.NavItem.HOME, CarDisplay.NavItem.FORWARD, CarDisplay.NavItem.YOUTUBE, CarDisplay.NavItem.MUSIC), video)
        val covered = CarDisplay.navItems(videoPage = true, covered = true)
        assertEquals(listOf(CarDisplay.NavItem.HOME, CarDisplay.NavItem.YOUTUBE, CarDisplay.NavItem.MUSIC), covered)
        // Driving: no scrolling, no search, no page navigation through the covered page.
        assertFalse(covered.any { it == CarDisplay.NavItem.SEARCH || it == CarDisplay.NavItem.SCROLL_DOWN || it == CarDisplay.NavItem.BACK })
    }
}
