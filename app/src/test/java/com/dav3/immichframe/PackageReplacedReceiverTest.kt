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
    fun `background launch accepts overlay permission or default launcher role`() {
        assertTrue(canLaunchFrameFromBackground(hasOverlayPermission = true, isDefaultLauncher = false))
        assertTrue(canLaunchFrameFromBackground(hasOverlayPermission = false, isDefaultLauncher = true))
        assertFalse(canLaunchFrameFromBackground(hasOverlayPermission = false, isDefaultLauncher = false))
    }
}
