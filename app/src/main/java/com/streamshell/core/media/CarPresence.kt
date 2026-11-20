package com.streamshell.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether StreamShell's Android Auto session is running (0.6.5). While it is, the car screen's page is the one that
 * plays: the phone's pages are silenced (no media tab, all media paused), so phone and car do not fight over the sound
 * (pause / play flicker in the car).
 */
object CarPresence {
    /** "No media tab" for the phone while the car plays. */
    const val NO_TAB = -1L

    private val state = MutableStateFlow(false)
    val active: StateFlow<Boolean> = state.asStateFlow()

    fun set(on: Boolean) {
        state.value = on
    }
}
