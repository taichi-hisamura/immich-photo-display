package com.dav3.immichframe.data.sync

import com.dav3.immichframe.domain.model.AlbumSyncState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
    @Test
    fun `recent successful sync suppresses foreground scan`() {
        val now = 10 * HOUR
        assertFalse(
            isAnyAlbumSyncStale(
                albumIds = listOf("family"),
                states = listOf(AlbumSyncState(albumId = "family", lastSyncedAt = now - HOUR)),
                intervalMinutes = 360,
                now = now,
            ),
        )
    }

    @Test
    fun `missing album state triggers sync`() {
        assertTrue(
            isAnyAlbumSyncStale(
                albumIds = listOf("family", "common"),
                states = listOf(AlbumSyncState(albumId = "family", lastSyncedAt = 9 * HOUR)),
                intervalMinutes = 360,
                now = 10 * HOUR,
            ),
        )
    }

    @Test
    fun `one hour minimum applies to foreground scan`() {
        val now = 10 * HOUR
        assertFalse(
            isAnyAlbumSyncStale(
                albumIds = listOf("family"),
                states = listOf(AlbumSyncState(albumId = "family", lastSyncedAt = now - 30 * MINUTE)),
                intervalMinutes = 5,
                now = now,
            ),
        )
        assertTrue(
            isAnyAlbumSyncStale(
                albumIds = listOf("family"),
                states = listOf(AlbumSyncState(albumId = "family", lastSyncedAt = now - HOUR)),
                intervalMinutes = 5,
                now = now,
            ),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
