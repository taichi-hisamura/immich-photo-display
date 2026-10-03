package com.dav3.immichframe.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class MediaCacheWorkerErrorTest {
    @Test
    fun `sync error exposes the root network cause`() {
        val error = IllegalStateException("batch failed", IOException("connection reset"))

        assertEquals("connection reset", error.toSyncErrorMessage())
    }
}
