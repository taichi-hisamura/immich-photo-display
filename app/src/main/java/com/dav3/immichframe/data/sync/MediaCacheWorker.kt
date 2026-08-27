package com.dav3.immichframe.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.dav3.immichframe.data.local.MediaCacheRepositoryImpl
import com.dav3.immichframe.domain.model.AlbumSyncState
import com.dav3.immichframe.domain.model.Asset
import com.dav3.immichframe.domain.model.AssetType
import com.dav3.immichframe.domain.model.CachedAsset
import com.dav3.immichframe.domain.model.SyncProgress
import com.dav3.immichframe.domain.repository.ImmichRepository
import com.dav3.immichframe.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class MediaCacheWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaCacheRepository: MediaCacheRepositoryImpl,
    private val immichRepository: ImmichRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {
    private val previewDownloader = PreviewAssetDownloader()

    /**
     * Work created by an older app version can remain queued after an update.
     * Some of those requests were expedited, which makes WorkManager ask this
     * worker for foreground information on Android 10. Returning a valid
     * notification lets the old work complete or be replaced instead of
     * crashing the entire app.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val albumIds = inputData.getStringArray(KEY_ALBUM_IDS)?.toList() ?: emptyList()
            if (albumIds.isEmpty()) return@withLock ListenableWorker.Result.success()

            try {
                performFullSync(albumIds)
                ListenableWorker.Result.success()
            } catch (_: Exception) {
                mediaCacheRepository.updateSyncProgress(
                    SyncProgress(
                        albumIds = albumIds,
                        currentAlbum = albumIds.lastOrNull().orEmpty(),
                        phase = SyncProgress.Phase.ERROR,
                        currentAsset = "Check the connection and try again.",
                    ),
                )
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.failure()
                }
            }
        }
    }

    private suspend fun performFullSync(albumIds: List<String>) {
        // A fallback photo may be the only image still painted while an empty
        // album is reconciled. Restore this persisted lease before deletions,
        // including after an app process restart.
        settingsRepository.fallbackAssetId.first()?.let(mediaCacheRepository::retainAssetForDisplay)
        val confirmedEmptyAlbumIds = immichRepository.getAlbums()
            .getOrNull()
            ?.filter { it.assetCount == 0 }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()

        mediaCacheRepository.updateSyncProgress(
            SyncProgress(
                albumIds = albumIds,
                currentAlbum = albumIds.firstOrNull().orEmpty(),
                phase = SyncProgress.Phase.FETCHING_METADATA,
            ),
        )

        val goneAlbums = mutableListOf<String>()
        val syncErrors = mutableListOf<Throwable>()
        for (albumId in albumIds) {
            mediaCacheRepository.updateSyncProgress(
                SyncProgress(
                    albumIds = albumIds,
                    currentAlbum = albumId,
                    phase = SyncProgress.Phase.FETCHING_METADATA,
                    currentAsset = "Fetching complete album metadata...",
                ),
            )

            immichRepository.getAlbumAssets(albumId).fold(
                onSuccess = { remoteAssets ->
                    downloadAndReconcile(
                        albumId = albumId,
                        albumIds = albumIds,
                        remoteAssets = remoteAssets,
                        emptyAlbumConfirmed = albumId in confirmedEmptyAlbumIds,
                    )
                },
                onFailure = { error ->
                    if (isAlbumGone(error)) {
                        mediaCacheRepository.clearAlbum(albumId)
                        goneAlbums += albumId
                    } else {
                        syncErrors += error
                    }
                    // Pagination, network, and server failures preserve cache
                    // and trigger WorkManager retry after other albums finish.
                },
            )
        }

        if (goneAlbums.isNotEmpty() && goneAlbums.size == albumIds.size) {
            settingsRepository.setSelectedAlbumIds(emptyList())
        }
        if (syncErrors.isNotEmpty()) {
            throw SyncFailedException(syncErrors)
        }
        mediaCacheRepository.updateSyncProgress(
            SyncProgress(
                albumIds = albumIds,
                currentAlbum = albumIds.lastOrNull().orEmpty(),
                phase = SyncProgress.Phase.COMPLETE,
            ),
        )
    }

    private suspend fun downloadAndReconcile(
        albumId: String,
        albumIds: List<String>,
        remoteAssets: List<Asset>,
        emptyAlbumConfirmed: Boolean,
    ) {
        val remoteImages = selectSyncableImages(remoteAssets)
        mediaCacheRepository.updateSyncProgress(
            SyncProgress(
                albumIds = albumIds,
                currentAlbum = albumId,
                phase = SyncProgress.Phase.DOWNLOADING,
                totalAssets = remoteImages.size,
                currentAsset = "Synchronizing ${remoteImages.size} preview images...",
            ),
        )

        val syncState = mediaCacheRepository.getAlbumSyncState(albumId).getOrElse {
            AlbumSyncState(albumId = albumId)
        }
        val cachedAssets = mediaCacheRepository.getCachedAssets(albumId).getOrElse { emptyList() }

        // A corrupt physical file invalidates every album membership for that
        // asset. Removing the parent row cascades the memberships safely.
        val corruptIds = cachedAssets.filterNot(::isValidCacheFile).map(CachedAsset::id)
        if (corruptIds.isNotEmpty()) {
            mediaCacheRepository.removeAssets(corruptIds)
        }

        // This fork is image-only. Remove any original video left by an
        // upstream build, without deleting a file still referenced elsewhere.
        val cachedVideos = cachedAssets.filter { it.type == AssetType.VIDEO }
        if (cachedVideos.isNotEmpty()) {
            mediaCacheRepository.removeAlbumAssets(albumId, cachedVideos.map(CachedAsset::id))
        }

        val validCachedImages = cachedAssets.filter {
            it.id !in corruptIds && it.type == AssetType.IMAGE
        }
        val remoteIds = remoteImages.map(Asset::id).toSet()

        // A search response is authoritative when it has entries. A zero-item
        // response is authoritative only when Immich's separate album
        // metadata also says the album contains zero assets. This prevents a
        // transient search failure from erasing the local frame cache.
        if (remoteAssets.isNotEmpty() || emptyAlbumConfirmed) {
            val removedIds = validCachedImages.filter { it.id !in remoteIds }.map(CachedAsset::id)
            mediaCacheRepository.removeAlbumAssets(albumId, removedIds)
        }

        val downloadErrors = mutableListOf<Throwable>()
        remoteImages.forEachIndexed { index, asset ->
            mediaCacheRepository.updateSyncProgress(
                SyncProgress(
                    albumIds = albumIds,
                    currentAlbum = albumId,
                    phase = SyncProgress.Phase.DOWNLOADING,
                    totalAssets = remoteImages.size,
                    processedAssets = index + 1,
                    currentAsset = "Synchronizing ${asset.id.take(8)}...",
                ),
            )

            val albumCached = validCachedImages.find { it.id == asset.id }
            when {
                !shouldDownloadPreview(asset, albumCached) -> Unit
                albumCached != null -> downloadAndStore(albumId, asset).onFailure(downloadErrors::add)
                else -> linkSharedOrDownload(albumId, asset).onFailure(downloadErrors::add)
            }
        }

        if (downloadErrors.isNotEmpty()) {
            throw SyncFailedException(downloadErrors)
        }

        mediaCacheRepository.updateAlbumSyncState(
            syncState.copy(
                lastSyncedAt = System.currentTimeMillis(),
                assetCount = remoteImages.size,
            ),
        )
    }

    private suspend fun linkSharedOrDownload(
        albumId: String,
        asset: Asset,
    ): kotlin.Result<Unit> {
        val shared = mediaCacheRepository.getCachedAsset(asset.id)
        return if (shared != null && shared.lastModified == asset.lastModified && isValidCacheFile(shared)) {
            runCatching {
                mediaCacheRepository.upsertAssets(listOf(shared.copy(albumId = albumId)))
            }
        } else {
            downloadAndStore(albumId, asset)
        }
    }

    private suspend fun downloadAndStore(
        albumId: String,
        asset: Asset,
    ): kotlin.Result<Unit> = downloadAsset(albumId, asset).map { cached ->
        mediaCacheRepository.upsertAssets(listOf(cached))
    }

    private suspend fun downloadAsset(
        albumId: String,
        asset: Asset,
    ): kotlin.Result<CachedAsset> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settingsRepository.apiKey.first()
            val serverUrl = settingsRepository.serverUrl.first()
            val cacheDir = File(mediaCacheRepository.cacheDir)
            val filePath = File(cacheDir, asset.id)
            val fileSize = previewDownloader.download(serverUrl, asset.id, apiKey, filePath)

            // The preview is sufficient for both slideshow display and local
            // color/selection thumbnails. Remove the redundant legacy file.
            File(cacheDir, "${asset.id}_thumb").delete()

            CachedAsset(
                id = asset.id,
                albumId = albumId,
                type = AssetType.IMAGE,
                filePath = filePath.absolutePath,
                thumbnailPath = filePath.absolutePath,
                fileSize = fileSize,
                checksum = null,
                lastModified = asset.lastModified,
                cachedAt = System.currentTimeMillis(),
                originalMimeType = asset.originalMimeType,
            )
        }
    }

    private fun createForegroundNotification() = run {
        ensureForegroundNotificationChannel()
        NotificationCompat.Builder(applicationContext, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Immich Media Frame")
            .setContentText("Synchronizing media cache")
            .setOngoing(true)
            .build()
    }

    private fun ensureForegroundNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Immich Media Frame sync",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val WORK_NAME = "media_cache_sync"
        const val KEY_ALBUM_IDS = "albumIds"
        const val KEY_INCREMENTAL = "incremental"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val FOREGROUND_CHANNEL_ID = "media_cache_sync"
        private val syncMutex = Mutex()
    }
}

internal fun selectSyncableImages(remoteAssets: List<Asset>): List<Asset> = remoteAssets.filter { it.type == AssetType.IMAGE }

internal fun shouldDownloadPreview(
    remote: Asset,
    cached: CachedAsset?,
): Boolean = cached == null || cached.lastModified != remote.lastModified

private fun isValidCacheFile(cached: CachedAsset): Boolean {
    val file = File(cached.filePath)
    return file.exists() && file.length() > 0L && file.length() == cached.fileSize
}

private fun isAlbumGone(throwable: Throwable): Boolean {
    val message = throwable.message.orEmpty()
    return message.contains("404") || message.contains("Not Found", ignoreCase = true)
}

private class SyncFailedException(
    errors: List<Throwable>,
) : Exception("Media cache synchronization failed for ${errors.size} operation(s)", errors.firstOrNull())
