package com.dav3.immichframe.domain.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherHelperTest {
    @Test
    fun `accepts an available and held Home role`() {
        assertTrue(isHomeRoleHeld(roleAvailable = true, roleHeld = true))
    }

    @Test
    fun `rejects an unheld Home role`() {
        assertFalse(isHomeRoleHeld(roleAvailable = true, roleHeld = false))
    }

    @Test
    fun `rejects an unavailable Home role`() {
        assertFalse(isHomeRoleHeld(roleAvailable = false, roleHeld = true))
    }
}
