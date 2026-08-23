package com.dav3.immichframe.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dav3.immichframe.domain.model.AlbumSyncState
import com.dav3.immichframe.domain.repository.MediaCacheRepository
import com.dav3.immichframe.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val mediaCacheRepository: MediaCacheRepository,
) {
    private val workName = "periodic_media_cache_sync"

    /**
     * Schedule (or cancel) periodic background sync based on user settings.
     * Call this from a coroutine — it reads DataStore values (suspend).
     *
     * This low-bandwidth build enforces an hourly floor; the default is six
     * hours even though WorkManager itself permits shorter intervals.
     */
    suspend fun schedulePeriodicSync() {
        val settings = settingsRepository.slideshowSettings.first()
        if (!settings.autoSync) {
            cancelPeriodicSync()
            return
        }

        val albumIds = settingsRepository.selectedAlbumIds.first()
        if (albumIds.isEmpty()) return

        // Full metadata scans are deliberately limited to hourly or slower on
        // metered frame devices. The default is six hours.
        val intervalMinutes = settings.syncIntervalMinutes.toLong().coerceAtLeast(60)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putStringArray(MediaCacheWorker.KEY_ALBUM_IDS, albumIds.toTypedArray())
            .putBoolean(MediaCacheWorker.KEY_INCREMENTAL, true)
            .build()

        val workRequest =
            PeriodicWorkRequestBuilder<MediaCacheWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest,
        )
    }

    /**
     * Cancel any scheduled periodic sync.
     */
    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(workName)
    }

    /**
     * Trigger an immediate one-time sync.
     */
    fun syncNow(albumIds: List<String>): Boolean {
        if (albumIds.isEmpty()) return false

        val inputData = Data.Builder()
            .putStringArray(MediaCacheWorker.KEY_ALBUM_IDS, albumIds.toTypedArray())
            .putBoolean(MediaCacheWorker.KEY_INCREMENTAL, true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<MediaCacheWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MediaCacheWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest,
        )
        return true
    }

    /**
     * Enqueue a foreground-triggered sync only when at least one selected
     * album is older than the configured interval. Reopening the slideshow
     * must not cause another full metadata scan on a metered connection.
     */
    suspend fun syncIfStale(albumIds: List<String>) {
        if (albumIds.isEmpty()) return
        val settings = settingsRepository.slideshowSettings.first()
        val states = mediaCacheRepository.getAllAlbumSyncStates().getOrElse { emptyList() }
        if (
            isAnyAlbumSyncStale(
                albumIds = albumIds,
                states = states,
                intervalMinutes = settings.syncIntervalMinutes,
                now = System.currentTimeMillis(),
            )
        ) {
            syncNow(albumIds)
        }
    }

    val syncProgress = mediaCacheRepository.syncProgress
}

internal fun isAnyAlbumSyncStale(
    albumIds: List<String>,
    states: List<AlbumSyncState>,
    intervalMinutes: Int,
    now: Long,
): Boolean {
    val staleAfterMillis = intervalMinutes.coerceAtLeast(60) * 60_000L
    val statesByAlbum = states.associateBy(AlbumSyncState::albumId)
    return albumIds.any { albumId ->
        val lastSyncedAt = statesByAlbum[albumId]?.lastSyncedAt ?: 0L
        now - lastSyncedAt >= staleAfterMillis
    }
}
