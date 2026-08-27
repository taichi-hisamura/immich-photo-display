package com.dav3.immichframe.domain.repository

import com.dav3.immichframe.domain.model.AlbumSyncState
import com.dav3.immichframe.domain.model.CachedAsset
import com.dav3.immichframe.domain.model.SyncProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MediaCacheRepository {
    // Cached assets
    suspend fun getCachedAssets(albumId: String): Result<List<CachedAsset>>

    /**
     * Emits the current combined cache contents for the supplied albums, then
     * emits again whenever the background synchronizer changes that cache.
     */
    fun observeCachedAssets(albumIds: List<String>): Flow<List<CachedAsset>>
    suspend fun getCachedAsset(assetId: String): CachedAsset?
    suspend fun getAllCachedAssets(): Result<List<CachedAsset>>
    suspend fun upsertAssets(assets: List<CachedAsset>)
    suspend fun removeAssets(assetIds: List<String>)
    suspend fun removeAlbumAssets(albumId: String, assetIds: List<String>)
    suspend fun clearAlbum(albumId: String)
    suspend fun clearAll()

    /**
     * Keeps an actively displayed asset on disk if a sync removes its final
     * album membership. The slideshow releases it after it transitions away.
     */
    fun retainAssetForDisplay(assetId: String)

    suspend fun releaseRetainedAsset(assetId: String)

    // Album sync state
    suspend fun getAlbumSyncState(albumId: String): Result<AlbumSyncState>
    suspend fun getAllAlbumSyncStates(): Result<List<AlbumSyncState>>
    suspend fun updateAlbumSyncState(state: AlbumSyncState)

    // File management
    suspend fun getAssetFilePath(assetId: String): String?
    suspend fun getAssetThumbnailPath(assetId: String): String?

    /**
     * Batch-resolve local file paths for a set of asset IDs. Returns only
     * entries whose file still exists on disk. Used by the slideshow to
     * display cached media offline instead of hitting the network.
     */
    suspend fun getAssetFilePaths(assetIds: List<String>): Map<String, String>

    /**
     * Batch-resolve local thumbnail paths for a set of asset IDs. Returns only
     * entries whose thumbnail file still exists on disk. Used by the media
     * selection grid where thumbnails (always JPEG) are needed regardless of
     * asset type — Coil can't decode video files into bitmaps.
     */
    suspend fun getAssetThumbnailPaths(assetIds: List<String>): Map<String, String>

    suspend fun deleteAssetFiles(assetId: String)

    // Cache directory
    val cacheDir: String

    // Progress tracking
    val syncProgress: StateFlow<SyncProgress?>
}
