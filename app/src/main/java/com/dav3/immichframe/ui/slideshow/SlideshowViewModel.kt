package com.dav3.immichframe.ui.slideshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dav3.immichframe.data.sync.SyncScheduler
import com.dav3.immichframe.domain.model.Asset
import com.dav3.immichframe.domain.model.AssetType
import com.dav3.immichframe.domain.model.ClockPosition
import com.dav3.immichframe.domain.repository.ImmichRepository
import com.dav3.immichframe.domain.repository.MediaCacheRepository
import com.dav3.immichframe.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SlideshowUiState(
    val assets: List<Asset> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** True when albums are confirmed empty and one final photo is retained. */
    val isShowingFallback: Boolean = false,
    /**
     * Set when the selected album(s) no longer exist on the server. The UI
     * should navigate back to album selection so the user can pick again.
     */
    val albumGone: Boolean = false,
)

@HiltViewModel
class SlideshowViewModel
@Inject
constructor(
    private val immichRepo: ImmichRepository,
    private val cacheRepo: MediaCacheRepository,
    private val settingsRepo: SettingsRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SlideshowUiState())
    val uiState: StateFlow<SlideshowUiState> = _uiState

    /**
     * Asset ID → local cached file path. Populated in [load] so that
     * [imageUrl] / [videoUrl] can return a `file://` URI for offline display
     * instead of a network URL. Falls back to network for cache misses.
     */
    private val localFilePaths = mutableMapOf<String, String>()

    /**
     * Asset ID → local cached thumbnail path. Populated in [load] so that
     * [thumbnailUrl] can return a `file://` URI for the small JPEG thumbnail
     * (used by adaptive background color extraction) without a DB lookup.
     */
    private val localThumbnailPaths = mutableMapOf<String, String>()

    private var cacheObservationJob: Job? = null

    /** Latest cache snapshot that excludes the currently painted photo. */
    private var pendingCachedAssets: List<Asset>? = null
    private var retainedDisplayAssetId: String? = null

    val settings =
        settingsRepo.slideshowSettings
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.dav3.immichframe.domain.model
                    .SlideshowSettings(),
            )

    val onboardingSteps: StateFlow<Set<String>> =
        settingsRepo.onboardingCompletedSteps
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun markStepCompleted(stepId: String) {
        viewModelScope.launch { settingsRepo.markOnboardingStepCompleted(stepId) }
    }

    fun skipOnboarding(stepIds: List<String>) {
        viewModelScope.launch {
            stepIds.forEach { settingsRepo.markOnboardingStepCompleted(it) }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, albumGone = false)
            val s = settingsRepo.slideshowSettings.first()
            val albumIds = settingsRepo.selectedAlbumIds.first()
            if (albumIds.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "No albums selected")
                return@launch
            }

            val toggledIds = settingsRepo.mediaSelectionToggledIds.first()
            val newItemsShown = settingsRepo.mediaSelectionNewItemsShown.first()

            // First, try to load from cache (offline-capable fast path)
            val cachedAssets = mutableListOf<Asset>()
            for (id in albumIds) {
                cacheRepo.getCachedAssets(id).fold(
                    onSuccess = { assets -> cachedAssets.addAll(assets.map { it.toAsset() }) },
                    onFailure = { /* cache miss is non-fatal */ },
                )
            }
            val uniqueCachedAssets = cachedAssets.distinctBy(Asset::id)

            val fallbackAsset = settingsRepo.fallbackAssetId.first()
                ?.let { assetId -> cacheRepo.getCachedAsset(assetId)?.toAsset() }
            if (fallbackAsset != null) {
                cacheRepo.retainAssetForDisplay(fallbackAsset.id)
                retainedDisplayAssetId = fallbackAsset.id
                resolveLocalPaths(uniqueCachedAssets + fallbackAsset)
                _uiState.value = SlideshowUiState(
                    assets = listOf(fallbackAsset),
                    isLoading = false,
                    isShowingFallback = true,
                )
                observeCachedAssets(albumIds, toggledIds, newItemsShown)
                if (s.autoSync) syncScheduler.syncIfStale(albumIds)
                return@launch
            }

            if (uniqueCachedAssets.isNotEmpty()) {
                // Resolve local file paths up front so imageUrl/videoUrl can
                // serve offline `file://` URIs without per-frame DB lookups.
                resolveLocalPaths(uniqueCachedAssets)

                // Show cached assets immediately
                val videoCount = uniqueCachedAssets.count { it.type == AssetType.VIDEO }
                val imageCount = uniqueCachedAssets.count { it.type == AssetType.IMAGE }
                android.util.Log.d("SlideshowLoad", "Cache: $imageCount images, $videoCount videos, skipVideos=${s.skipVideos}")
                val filteredAssets = applyMediaSelection(uniqueCachedAssets, toggledIds, newItemsShown)
                    .let { if (s.skipVideos) it.filter { it.type == AssetType.IMAGE } else it }
                val ordered = if (s.shuffle) filteredAssets.shuffled() else filteredAssets
                _uiState.value = if (ordered.isNotEmpty()) {
                    SlideshowUiState(assets = ordered, currentIndex = 0, isLoading = false)
                } else {
                    SlideshowUiState(isLoading = false, error = "No images found in cache")
                }
                updateDisplayLease(ordered.firstOrNull()?.id)

                // Kick off background sync via WorkManager (worker handles download + reconcile)
                if (s.autoSync) {
                    syncScheduler.syncIfStale(albumIds)
                }
                observeCachedAssets(albumIds, toggledIds, newItemsShown)
            } else {
                // Cold start: no cache yet — fetch metadata from network for immediate display
                val allAssets = mutableListOf<Asset>()
                val errors = mutableListOf<String>()
                var albumGone = false
                for (id in albumIds) {
                    immichRepo.getAlbumAssets(id).fold(
                        onSuccess = { allAssets.addAll(it) },
                        onFailure = {
                            // Immich returns 404 for a deleted album. Treat that
                            // as permanent — we'll bounce back to album selection.
                            if (isAlbumGone(it)) albumGone = true
                            errors.add("${id.take(8)}: ${it.message ?: "unknown"}")
                        },
                    )
                }
                val uniqueAssets = allAssets.distinctBy(Asset::id)

                if (albumGone) {
                    // Album deleted on server — clear selection and signal UI
                    settingsRepo.setSelectedAlbumIds(emptyList())
                    _uiState.value = SlideshowUiState(isLoading = false, albumGone = true)
                    return@launch
                }

                val filteredAssets = applyMediaSelection(uniqueAssets, toggledIds, newItemsShown)
                    .let { if (s.skipVideos) it.filter { it.type == AssetType.IMAGE } else it }
                android.util.Log.d("SlideshowLoad", "Network: ${uniqueAssets.count { it.type == AssetType.IMAGE }} images, ${uniqueAssets.count { it.type == AssetType.VIDEO }} videos, skipVideos=${s.skipVideos}")
                val ordered = if (s.shuffle) filteredAssets.shuffled() else filteredAssets

                _uiState.value = when {
                    ordered.isNotEmpty() -> {
                        SlideshowUiState(assets = ordered, currentIndex = 0, isLoading = false)
                    }
                    errors.isNotEmpty() -> {
                        SlideshowUiState(isLoading = false, error = "Asset load failed:\n${errors.joinToString("\n")}")
                    }
                    else -> {
                        SlideshowUiState(isLoading = false, error = "No images found")
                    }
                }
                updateDisplayLease(ordered.firstOrNull()?.id)

                // Populate cache in background (worker downloads files + writes DB)
                if (ordered.isNotEmpty()) {
                    observeCachedAssets(albumIds, toggledIds, newItemsShown)
                    syncScheduler.syncNow(albumIds)
                }
            }
        }
    }

    /**
     * Keeps the running slideshow aligned with Room while a background cache
     * sync adds, updates, or removes media. Previously the cache was read only
     * at screen entry, so the count stayed stale until the user navigated away
     * and back even after a successful sync.
     */
    private fun observeCachedAssets(
        albumIds: List<String>,
        toggledIds: Set<String>,
        newItemsShown: Boolean,
    ) {
        cacheObservationJob?.cancel()
        cacheObservationJob = viewModelScope.launch {
            var receivedInitialSnapshot = false
            cacheRepo.observeCachedAssets(albumIds).collect { cachedRows ->
                val cachedAssets = cachedRows.map { it.toAsset() }
                // Room can emit an initial empty snapshot while its per-album
                // queries are starting. The slideshow already loaded its
                // cache synchronously above, so that placeholder must not be
                // mistaken for a server-confirmed removal.
                if (!receivedInitialSnapshot && cachedAssets.isEmpty()) {
                    receivedInitialSnapshot = true
                    return@collect
                }
                receivedInitialSnapshot = true
                val filteredAssets = applyMediaSelection(cachedAssets, toggledIds, newItemsShown)
                    .let { assets ->
                        if (settings.value.skipVideos) assets.filter { it.type == AssetType.IMAGE } else assets
                    }
                val oldState = _uiState.value
                val currentAsset = oldState.assets.getOrNull(oldState.currentIndex)
                val currentWasRemoved = currentAsset != null && filteredAssets.none { it.id == currentAsset.id }

                if (oldState.isShowingFallback || currentWasRemoved) {
                    pendingCachedAssets = filteredAssets
                    if (filteredAssets.isEmpty() && currentAsset != null) {
                        settingsRepo.setFallbackAssetId(currentAsset.id)
                        _uiState.value = oldState.copy(isLoading = false, error = null, isShowingFallback = true)
                    }
                    return@collect
                }

                resolveLocalPaths(filteredAssets)
                val byId = filteredAssets.associateBy(Asset::id)
                val existingIds = oldState.assets.map(Asset::id).toSet()
                val preserved = oldState.assets.mapNotNull { byId[it.id] }
                val additions = filteredAssets.filter { it.id !in existingIds }
                    .let { assets -> if (settings.value.shuffle) assets.shuffled() else assets }
                val updatedAssets = preserved + additions
                val currentId = oldState.assets.getOrNull(oldState.currentIndex)?.id
                val updatedIndex = updatedAssets.indexOfFirst { it.id == currentId }
                    .takeIf { it >= 0 }
                    ?: oldState.currentIndex.coerceIn(0, (updatedAssets.size - 1).coerceAtLeast(0))

                _uiState.value = oldState.copy(
                    assets = updatedAssets,
                    currentIndex = updatedIndex,
                    isLoading = false,
                    error = if (updatedAssets.isEmpty()) "No images found in cache" else null,
                )
            }
        }
    }

    fun next() {
        val s = _uiState.value
        pendingCachedAssets?.let { pending ->
            if (pending.isNotEmpty()) {
                pendingCachedAssets = null
                settingsRepoClearFallback()
                showNextCacheSnapshot(pending)
            }
            return
        }
        if (s.isShowingFallback) return
        if (s.assets.isNotEmpty()) {
            val nextIndex = (s.currentIndex + 1) % s.assets.size
            _uiState.value = s.copy(currentIndex = nextIndex)
            updateDisplayLease(s.assets[nextIndex].id)
        }
    }

    /**
     * Advances only when [assetId] is still visible. Image-loading callbacks
     * can arrive after a crossfade, and must not skip a newer photo.
     */
    fun nextIfCurrent(assetId: String) {
        if (_uiState.value.assets.getOrNull(_uiState.value.currentIndex)?.id == assetId) {
            next()
        }
    }

    fun previous() {
        val s = _uiState.value
        if (!s.isShowingFallback && s.assets.isNotEmpty()) {
            val previousIndex = (s.currentIndex - 1 + s.assets.size) % s.assets.size
            _uiState.value = s.copy(currentIndex = previousIndex)
            updateDisplayLease(s.assets[previousIndex].id)
        }
    }

    private suspend fun resolveLocalPaths(assets: List<Asset>) {
        localFilePaths.clear()
        localFilePaths.putAll(cacheRepo.getAssetFilePaths(assets.map(Asset::id)))
        localThumbnailPaths.clear()
        localThumbnailPaths.putAll(cacheRepo.getAssetThumbnailPaths(assets.map(Asset::id)))
    }

    private fun showNextCacheSnapshot(cachedAssets: List<Asset>) {
        val ordered = if (settings.value.shuffle) cachedAssets.shuffled() else cachedAssets
        val nextAsset = ordered.firstOrNull() ?: return
        viewModelScope.launch {
            resolveLocalPaths(ordered)
            _uiState.value = SlideshowUiState(assets = ordered, currentIndex = 0, isLoading = false)
            updateDisplayLease(nextAsset.id)
        }
    }

    private fun settingsRepoClearFallback() {
        viewModelScope.launch { settingsRepo.setFallbackAssetId(null) }
    }

    private fun updateDisplayLease(nextAssetId: String?) {
        val previousAssetId = retainedDisplayAssetId
        if (previousAssetId == nextAssetId) return
        nextAssetId?.let(cacheRepo::retainAssetForDisplay)
        retainedDisplayAssetId = nextAssetId
        previousAssetId?.let { assetId ->
            viewModelScope.launch { cacheRepo.releaseRetainedAsset(assetId) }
        }
    }

    fun setClockPosition(pos: ClockPosition) {
        viewModelScope.launch {
            settingsRepo.setSlideshowSettings(settings.value.copy(clockPosition = pos))
        }
    }

    fun setMuted(value: Boolean) {
        viewModelScope.launch {
            settingsRepo.setSlideshowSettings(settings.value.copy(muted = value))
        }
    }

    /**
     * Returns a display URL for an image asset. If the asset is cached
     * locally on disk, returns a `file://` URI (works offline). Otherwise
     * returns the network URL (requires server connectivity).
     *
     * This low-bandwidth build always uses the transcoded preview endpoint;
     * animated GIFs therefore display as a static preview frame.
     */
    fun imageUrl(asset: Asset): String {
        localFilePaths[asset.id]?.let { path ->
            if (File(path).exists()) return "file://$path"
        }
        return immichRepo.imageUrl(asset.id, asset.originalMimeType)
    }

    fun videoUrl(assetId: String): String {
        localFilePaths[assetId]?.let { path ->
            if (File(path).exists()) return "file://$path"
        }
        return immichRepo.videoUrl(assetId)
    }

    /**
     * Small JPEG thumbnail URL — used for adaptive background color
     * extraction. Always prefers the locally cached thumbnail (works for
     * GIFs, videos, and regular images alike — Coil only needs a few pixels
     * to extract a dominant color, so the tiny transcode is perfect).
     */
    fun thumbnailUrl(assetId: String): String {
        localThumbnailPaths[assetId]?.let { path ->
            if (File(path).exists()) return "file://$path"
        }
        return immichRepo.thumbnailUrl(assetId)
    }
}

/**
 * Returns true if the exception indicates the album no longer exists on the
 * server (HTTP 404), as opposed to a transient network/server error.
 */
private fun isAlbumGone(throwable: Throwable): Boolean {
    val msg = throwable.message.orEmpty()
    // Retrofit/OkHttp surfaces HTTP status in the message for HttpException;
    // for an IOException (server unreachable) the message won't contain a code.
    return msg.contains("404") || msg.contains("Not Found", ignoreCase = true)
}

fun com.dav3.immichframe.domain.model.CachedAsset.toAsset(): Asset = Asset(
    id = id,
    type = type,
    lastModified = lastModified,
    originalMimeType = originalMimeType,
)

/**
 * Computes which assets are visible based on the media-selection state.
 *
 * - [newItemsShown] = true (default): all assets start **shown**. IDs in
 *   [toggledIds] are the ones the user tapped to **hide**.
 * - [newItemsShown] = false: all assets start **hidden**. IDs in
 *   [toggledIds] are the ones the user tapped to **show**.
 *
 * This is used by both the slideshow playback and the media-selection grid
 * so the two views stay consistent.
 */
fun applyMediaSelection(
    assets: List<Asset>,
    toggledIds: Set<String>,
    newItemsShown: Boolean,
): List<Asset> = if (newItemsShown) {
    assets.filter { it.id !in toggledIds }
} else {
    assets.filter { it.id in toggledIds }
}
