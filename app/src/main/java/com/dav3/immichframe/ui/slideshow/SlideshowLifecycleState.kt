package com.dav3.immichframe.ui.slideshow

import androidx.lifecycle.Lifecycle

/**
 * Lifecycle state used by the slideshow playback effects.
 *
 * The generation is intentionally incremented for every ON_RESUME event,
 * including resumes where the active flag was already true. Android launchers
 * can bring the existing activity to the foreground without changing the
 * Compose destination state; the generation makes that foreground transition
 * an explicit restart signal for the timer and its watchdog.
 */
internal data class SlideshowLifecycleState(
    val isScreenActive: Boolean,
    val resumeGeneration: Long = 0L,
)

internal fun SlideshowLifecycleState.onLifecycleEvent(
    event: Lifecycle.Event,
): SlideshowLifecycleState = when (event) {
    Lifecycle.Event.ON_START,
    Lifecycle.Event.ON_RESUME,
    -> copy(
        isScreenActive = true,
        resumeGeneration = if (event == Lifecycle.Event.ON_RESUME) {
            resumeGeneration + 1L
        } else {
            resumeGeneration
        },
    )

    Lifecycle.Event.ON_STOP -> copy(isScreenActive = false)
    else -> this
}
