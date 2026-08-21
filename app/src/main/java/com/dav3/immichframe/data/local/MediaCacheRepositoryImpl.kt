package com.dav3.immichframe.data.local

import android.content.Context
import androidx.room.withTransaction
import com.dav3.immichframe.domain.model.AlbumSyncState
import com.dav3.immichframe.domain.model.CachedAsset
import com.dav3.immichframe.domain.model.SyncProgress
import com.dav3.immichframe.domain.repository.MediaCacheRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCacheRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaCacheRepository {
    private val db = MediaCacheDatabase.getDatabase(context)

    override val cacheDir: String = context.getExternalFilesDir("media_cache")?.absolutePath
        ?: context.cacheDir.absolutePath + "/media_cache"

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    override val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    override suspend fun getCachedAssets(albumId: String): Result<List<CachedAsset>> = withContext(Dispatchers.IO) {
        try {
            val assets = db.cachedAssetDao().getByAlbumId(albumId)
            Result.success(assets.map { CachedAssetEntity.toDomain(it.asset, it.albumId) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAllCachedAssets(): Result<List<CachedAsset>> = withContext(Dispatchers.IO) {
        try {
            val assets = db.cachedAssetDao().getAllWithMemberships()
            Result.success(assets.map { CachedAssetEntity.toDomain(it.asset, it.albumId) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upsertAssets(assets: List<CachedAsset>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.cachedAssetDao().insertAll(assets.map { CachedAssetEntity.fromDomain(it) })
            db.cachedAssetDao().insertMemberships(
                assets.map { AlbumAssetCrossRef(albumId = it.albumId, assetId = it.id) },
            )
        }
    }

    override suspend fun removeAssets(assetIds: List<String>) = withContext(Dispatchers.IO) {
        val assets = db.withTransaction {
            val rows = db.cachedAssetDao().getByIds(assetIds)
            db.cachedAssetDao().deleteByIds(assetIds)
            rows
        }
        assets.forEach { entity ->
            deleteFile(entity.filePath)
            deleteFile(entity.thumbnailPath)
        }
    }

    override suspend fun getCachedAsset(assetId: String): CachedAsset? = withContext(Dispatchers.IO) {
        db.cachedAssetDao().getById(assetId)?.let { entity ->
            CachedAssetEntity.toDomain(entity, albumId = "")
        }
    }

    override suspend fun removeAlbumAssets(
        albumId: String,
        assetIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext
        val orphans = db.withTransaction {
            db.cachedAssetDao().deleteMemberships(albumId, assetIds)
            detachOrphanedAssets()
        }
        deleteOrphanedFiles(orphans)
    }

    override suspend fun clearAlbum(albumId: String) = withContext(Dispatchers.IO) {
        val orphans = db.withTransaction {
            db.cachedAssetDao().deleteMembershipsByAlbum(albumId)
            detachOrphanedAssets()
        }
        deleteOrphanedFiles(orphans)
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        val assets = db.withTransaction {
            val rows = db.cachedAssetDao().getAllAssets()
            db.cachedAssetDao().deleteAll()
            rows
        }
        assets.forEach { entity ->
            deleteFile(entity.filePath)
            deleteFile(entity.thumbnailPath)
        }
    }

    override suspend fun getAlbumSyncState(albumId: String): Result<AlbumSyncState> = withContext(Dispatchers.IO) {
        try {
            val state = db.albumSyncStateDao().getByAlbumId(albumId)
            Result.success(
                state?.let { AlbumSyncStateEntity.toDomain(it) } ?: AlbumSyncState(albumId = albumId),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAllAlbumSyncStates(): Result<List<AlbumSyncState>> = withContext(Dispatchers.IO) {
        try {
            val states = db.albumSyncStateDao().getAll()
            Result.success(states.map { AlbumSyncStateEntity.toDomain(it) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateAlbumSyncState(state: AlbumSyncState) = withContext(Dispatchers.IO) {
        db.albumSyncStateDao().insert(AlbumSyncStateEntity.fromDomain(state))
    }

    override suspend fun getAssetFilePath(assetId: String): String? = withContext(Dispatchers.IO) {
        db.cachedAssetDao().getById(assetId)?.filePath
    }

    override suspend fun getAssetThumbnailPath(assetId: String): String? = withContext(Dispatchers.IO) {
        db.cachedAssetDao().getById(assetId)?.thumbnailPath
    }

    override suspend fun getAssetFilePaths(assetIds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext emptyMap()
        db.cachedAssetDao().getByIds(assetIds)
            .filter { entity ->
                val file = File(entity.filePath)
                // File must exist, be non-empty, and match the stored file size
                // (a mismatch indicates a partial/corrupt download).
                file.exists() && file.length() > 0 && file.length() == entity.fileSize
            }
            .associate { it.id to it.filePath }
    }

    override suspend fun getAssetThumbnailPaths(assetIds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (assetIds.isEmpty()) return@withContext emptyMap()
        db.cachedAssetDao().getByIds(assetIds)
            .mapNotNull { entity ->
                val thumb = entity.thumbnailPath ?: return@mapNotNull null
                val file = File(thumb)
                if (file.exists() && file.length() > 0) entity.id to thumb else null
            }
            .toMap()
    }

    override suspend fun deleteAssetFiles(assetId: String) {
        withContext(Dispatchers.IO) {
            val entity = db.withTransaction {
                val row = db.cachedAssetDao().getById(assetId)
                db.cachedAssetDao().deleteByIds(listOf(assetId))
                row
            }
            entity?.let {
                deleteFile(entity.filePath)
                deleteFile(entity.thumbnailPath)
            }
        }
    }

    // Called by MediaCacheWorker to surface progress in the UI
    internal fun updateSyncProgress(progress: SyncProgress) {
        _syncProgress.value = progress
    }

    internal fun clearSyncProgress() {
        _syncProgress.value = null
    }

    private fun deleteFile(path: String?) {
        path?.let { File(it).delete() }
    }

    private suspend fun detachOrphanedAssets(): List<CachedAssetEntity> {
        val orphans = db.cachedAssetDao().getOrphanedAssets()
        if (orphans.isNotEmpty()) {
            db.cachedAssetDao().deleteByIds(orphans.map(CachedAssetEntity::id))
        }
        return orphans
    }

    private fun deleteOrphanedFiles(orphans: List<CachedAssetEntity>) {
        orphans.forEach { entity ->
            deleteFile(entity.filePath)
            if (entity.thumbnailPath != entity.filePath) deleteFile(entity.thumbnailPath)
        }
    }
}
