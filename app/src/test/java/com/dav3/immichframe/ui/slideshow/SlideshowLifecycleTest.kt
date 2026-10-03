package com.dav3.immichframe.ui.slideshow

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowLifecycleTest {
    @Test
    fun resumeAlwaysAdvancesGenerationEvenWhenAlreadyActive() {
        val initial = SlideshowLifecycleState(isScreenActive = true)

        val afterFirstResume = initial.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val afterSecondResume = afterFirstResume.onLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertTrue(afterFirstResume.isScreenActive)
        assertEquals(1L, afterFirstResume.resumeGeneration)
        assertTrue(afterSecondResume.isScreenActive)
        assertEquals(2L, afterSecondResume.resumeGeneration)
    }

    @Test
    fun stopDisablesPlaybackWithoutChangingResumeGeneration() {
        val active = SlideshowLifecycleState(isScreenActive = true, resumeGeneration = 4L)

        val stopped = active.onLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertFalse(stopped.isScreenActive)
        assertEquals(4L, stopped.resumeGeneration)
    }
}
