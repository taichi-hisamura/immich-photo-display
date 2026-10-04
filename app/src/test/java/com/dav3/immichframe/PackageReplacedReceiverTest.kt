package com.dav3.immichframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageReplacedReceiverTest {
    @Test
    fun `configured frame resumes outside display sleep window`() {
        assertTrue(shouldResumeAfterPackageReplace(configured = true, scheduledSleeping = false))
    }

    @Test
    fun `unconfigured frame does not open after update`() {
        assertFalse(shouldResumeAfterPackageReplace(configured = false, scheduledSleeping = false))
    }

    @Test
    fun `scheduled sleeping frame stays asleep after update`() {
        assertFalse(shouldResumeAfterPackageReplace(configured = true, scheduledSleeping = true))
    }

    @Test
    fun `background launch requires overlay permission`() {
        assertTrue(canLaunchFrameFromBackground(hasOverlayPermission = true))
        assertFalse(canLaunchFrameFromBackground(hasOverlayPermission = false))
    }
}
