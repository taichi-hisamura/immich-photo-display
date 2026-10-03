package com.dav3.immichframe.data.remote

import com.dav3.immichframe.domain.model.PermissionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PermissionProbeTest {
    @Test
    fun `successful endpoint grants its permission`() = runBlocking {
        assertEquals(PermissionStatus.Granted, probePermission { })
    }

    @Test
    fun `network failure is unknown rather than a false grant`() = runBlocking {
        assertEquals(
            PermissionStatus.Unknown,
            probePermission { throw IOException("offline") },
        )
    }

    @Test
    fun `only forbidden response is treated as denied`() {
        assertEquals(PermissionStatus.Denied, permissionStatusForHttpCode(403))
        assertEquals(PermissionStatus.Unknown, permissionStatusForHttpCode(401))
        assertEquals(PermissionStatus.Unknown, permissionStatusForHttpCode(500))
    }
}
