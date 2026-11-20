package com.streamshell.car

/**
 * Dark Liquid Glass — StreamShell's car design system (0.6.6). Colours, sizes and the split rule are plain values and
 * pure functions (unit-tested); the views that use them are built in [CarViews]. Android Auto gives a navigation app a
 * drawing surface, not Compose or blur: "glass" is layered dark surfaces with thin light borders and one restrained
 * violet → cyan gradient for the active state and the main control.
 */
object CarTheme {
    const val BACKGROUND = 0xFF080B14.toInt()
    const val BACKGROUND_2 = 0xFF101626.toInt()
    const val GLASS = 0xFF171F32.toInt()
    const val ELEVATED = 0xFF202B42.toInt()
    const val VIOLET = 0xFF8B5CF6.toInt()
    const val CYAN = 0xFF42D9F5.toInt()
    const val TEXT = 0xFFF5F7FF.toInt()
    const val TEXT_2 = 0xFFA7B0C5.toInt()
    const val MUTED = 0xFF69758C.toInt()
    const val BORDER = 0x1AFFFFFF
    const val PRESSED = 0x2EFFFFFF

    /** YouTube Music's own, restrained red (its tab and its rail icon when active). */
    const val MUSIC_RED = 0xFFE2464F.toInt()

    // Sizes (dp) — the density is chosen per screen by [CarDisplay.optimalDpi], so these stay the same physical size.
    const val RADIUS_CARD = 22
    const val RADIUS_CONTROL = 16
    const val GAP = 12
    const val RAIL = 72
    const val PLAY_BUTTON = 76
    const val CONTROL_BUTTON = 60
    const val TITLE_SP = 20f
    const val BODY_SP = 15f
    const val LABEL_SP = 13f

    /** Status line of the panel (pure). */
    fun status(playing: Boolean, hasMedia: Boolean, covered: Boolean): String = when {
        !hasMedia -> "Nothing playing"
        covered && playing -> "Playing · sound only while driving"
        playing -> "Playing"
        else -> "Paused"
    }
}
