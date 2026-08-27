package com.dav3.immichframe.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dav3.immichframe.data.sync.SyncScheduler
import com.dav3.immichframe.domain.model.ClockFormat
import com.dav3.immichframe.domain.model.ClockPosition
import com.dav3.immichframe.domain.model.FillMode
import com.dav3.immichframe.domain.model.PermissionCheckResult
import com.dav3.immichframe.domain.model.PhotoAnimation
import com.dav3.immichframe.domain.model.SlideshowSettings
import com.dav3.immichframe.domain.model.SyncProgress
import com.dav3.immichframe.domain.repository.ImmichRepository
import com.dav3.immichframe.domain.repository.MediaCacheRepository
import com.dav3.immichframe.domain.repository.SettingsRepository
import com.dav3.immichframe.domain.system.DisplayScheduleManager
import com.dav3.immichframe.domain.system.openLauncherSettings
import com.dav3.immichframe.domain.system.setLauncherModeEnabled
import com.dav3.immichframe.ui.onboarding.TourSteps
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class SettingsUiState(
    val settings: SlideshowSettings = SlideshowSettings(),
    val serverUrl: String = "",
    val apiKey: String = "",
    val permissionStatus: PermissionCheckResult? = null,
    val permissionCheckInProgress: Boolean = false,
    val syncProgress: SyncProgress? = null,
    val syncRequested: Boolean = false,
    val adminPinConfigured: Boolean = false,
    val fallbackAssetId: String? = null,
)

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val settingsRepo: SettingsRepository,
    private val immichRepo: ImmichRepository,
    private val mediaCacheRepo: MediaCacheRepository,
    private val syncScheduler: SyncScheduler,
    private val displayScheduleManager: DisplayScheduleManager,
) : ViewModel() {
    private val permissionCheckingFlow = MutableStateFlow(false)
    private val syncRequestedFlow = MutableStateFlow(false)

    private val baseUiState =
        combine(
            settingsRepo.slideshowSettings,
            settingsRepo.serverUrl,
            settingsRepo.apiKey,
            settingsRepo.permissionStatus,
            permissionCheckingFlow,
        ) { slideshow, url, key, perms, checking ->
            SettingsUiState(
                settings = slideshow,
                serverUrl = url,
                apiKey = key,
                permissionStatus = perms,
                permissionCheckInProgress = checking,
            )
        }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            baseUiState,
            settingsRepo.adminPinConfigured,
            syncScheduler.syncProgress,
            syncRequestedFlow,
            settingsRepo.fallbackAssetId,
        ) { base, pinConfigured, progress, requested, fallbackAssetId ->
            base.copy(
                syncProgress = progress,
                syncRequested = requested,
                adminPinConfigured = pinConfigured,
                fallbackAssetId = fallbackAssetId,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    // null distinguishes "not read from DataStore yet" from a genuinely empty
    // completed set on a fresh installation. Settings uses it to avoid a
    // transient tour overlay while the persisted state is loading.
    val onboardingSteps: StateFlow<Set<String>?> =
        settingsRepo.onboardingCompletedSteps
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markStepCompleted(stepId: String) {
        viewModelScope.launch { settingsRepo.markOnboardingStepCompleted(stepId) }
    }

    fun skipOnboarding(stepIds: List<String>) {
        viewModelScope.launch {
            stepIds.forEach { settingsRepo.markOnboardingStepCompleted(it) }
        }
    }

    fun resetOnboarding() {
        viewModelScope.launch { settingsRepo.resetOnboarding() }
    }

    /**
     * Re-probe all API endpoints and update the stored permission status.
     * Called when the Settings screen opens and via the "Re-check" button.
     */
    fun recheckPermissions() {
        viewModelScope.launch {
            permissionCheckingFlow.value = true
            immichRepo.checkPermissions().onSuccess { result ->
                settingsRepo.setPermissionStatus(result)
                enforceDegradedSettings(result)
            }.onFailure {
                // Network error — keep the previous status, don't wipe it
            }
            permissionCheckingFlow.value = false
        }
    }

    /**
     * Force-off any setting gated by a missing optional permission.
     * Mirrors the logic in SetupViewModel.
     */
    private suspend fun enforceDegradedSettings(result: PermissionCheckResult) {
        val currentSettings = settingsRepo.slideshowSettings.first()
        var newSettings = currentSettings
        for (perm in result.missingOptional) {
            when (perm.gatedSettingKey) {
                "skip_videos" -> newSettings = newSettings.copy(skipVideos = true)
            }
        }
        if (newSettings != currentSettings) {
            settingsRepo.setSlideshowSettings(newSettings)
        }
    }

    fun resetOnboardingForSettings() {
        viewModelScope.launch {
            settingsRepo.resetOnboardingForScreen(TourSteps.SETTINGS.map { it.id })
        }
    }

    private val updateMutex = Mutex()

    /** Reads the LATEST persisted state from DataStore (not stale StateFlow cache). */
    private fun update(block: (SlideshowSettings) -> SlideshowSettings) = viewModelScope.launch {
        updateMutex.withLock {
            val current = settingsRepo.slideshowSettings.first()
            val newSettings = block(current)
            settingsRepo.setSlideshowSettings(newSettings)

            // Update sync schedule when autoSync or interval changes
            if (newSettings.autoSync != current.autoSync || newSettings.syncIntervalMinutes != current.syncIntervalMinutes) {
                if (newSettings.autoSync) {
                    syncScheduler.schedulePeriodicSync()
                } else {
                    syncScheduler.cancelPeriodicSync()
                }
            }

            if (
                newSettings.screenScheduleEnabled != current.screenScheduleEnabled ||
                newSettings.screenScheduleOffTime != current.screenScheduleOffTime ||
                newSettings.screenScheduleOnTime != current.screenScheduleOnTime
            ) {
                displayScheduleManager.updateSchedule(newSettings)
            }
        }
    }

    fun updateInterval(seconds: Int) = update { it.copy(intervalSeconds = seconds) }

    fun updateFillMode(mode: FillMode) = update { it.copy(fillMode = mode) }

    fun toggleClock() = update {
        val newShowClock = !it.showClock
        it.copy(
            showClock = newShowClock,
            // Reset to center when enabling
            clockPosition = if (newShowClock) ClockPosition(0.5f, 0.5f) else it.clockPosition,
        )
    }

    fun updateClockSize(size: Float) = update { it.copy(clockSize = size) }

    fun toggleClockSeconds() = update { it.copy(clockSeconds = !it.clockSeconds) }

    fun updateClockFormat(format: ClockFormat) = update { it.copy(clockFormat = format) }

    fun toggleKeepScreenOn() = update { it.copy(keepScreenOn = !it.keepScreenOn) }

    fun toggleFullscreen() = update { it.copy(fullscreen = !it.fullscreen) }

    fun toggleShuffle() = update { it.copy(shuffle = !it.shuffle) }

    fun toggleSkipVideos() = update { it.copy(skipVideos = !it.skipVideos) }

    fun toggleMuted() = update { it.copy(muted = !it.muted) }

    fun toggleStartOnBoot() = update {
        it.copy(startOnBoot = !it.startOnBoot, bootVerified = false)
    }

    fun toggleLauncherMode(context: Context) {
        val newEnabled = !uiState.value.settings.launcherMode
        setLauncherModeEnabled(context, newEnabled)
        update { it.copy(launcherMode = newEnabled) }
        // When enabling, show the home-chooser so the user can pick this app
        // as the default launcher.
        if (newEnabled) {
            openLauncherSettings(context)
        }
    }

    fun toggleAutoUpdate() = update { it.copy(autoUpdate = !it.autoUpdate) }

    fun toggleAutoSync() = update { it.copy(autoSync = !it.autoSync) }

    fun updateSyncInterval(minutes: Int) = update { it.copy(syncIntervalMinutes = minutes) }

    fun toggleNightMode() = update { it.copy(nightMode = !it.nightMode) }

    /** The start and end times are intentionally independent. */
    fun updateNightModeStart(minutes: Int) = update {
        it.copy(nightModeStart = minutes.coerceIn(0, 1439))
    }

    /** The start and end times are intentionally independent. */
    fun updateNightModeEnd(minutes: Int) = update {
        it.copy(nightModeEnd = minutes.coerceIn(0, 1439))
    }

    fun updateNightModeBrightness(percent: Int) = update { it.copy(nightModeBrightness = percent.coerceIn(0, 100)) }

    fun toggleScreenSchedule() = update {
        val enabled = !it.screenScheduleEnabled
        it.copy(
            screenScheduleEnabled = enabled,
            // A schedule needs the base display policy to keep the screen awake after waking.
            keepScreenOn = if (enabled) true else it.keepScreenOn,
        )
    }

    fun updateScreenScheduleOffTime(minutes: Int) = update {
        it.copy(screenScheduleOffTime = minutes.coerceIn(0, 1439))
    }

    fun updateScreenScheduleOnTime(minutes: Int) = update {
        it.copy(screenScheduleOnTime = minutes.coerceIn(0, 1439))
    }

    /** Replaces any inexact fallback alarms after exact-alarm access is granted. */
    fun refreshDisplaySchedule() = viewModelScope.launch {
        displayScheduleManager.rescheduleAfterBoot()
    }

    fun toggleClockSnapToGrid() = update { it.copy(clockSnapToGrid = !it.clockSnapToGrid) }

    fun toggleAdaptiveBackground() = update { it.copy(adaptiveBackground = !it.adaptiveBackground) }

    fun togglePhotoAnimations() = update { it.copy(photoAnimations = !it.photoAnimations) }

    fun toggleAnimation(anim: PhotoAnimation) = update {
        when (anim) {
            PhotoAnimation.ZOOM_IN -> it.copy(animZoomIn = !it.animZoomIn)
            PhotoAnimation.ZOOM_OUT -> it.copy(animZoomOut = !it.animZoomOut)
            PhotoAnimation.PAN_LEFT -> it.copy(animPanLeft = !it.animPanLeft)
            PhotoAnimation.PAN_RIGHT -> it.copy(animPanRight = !it.animPanRight)
            PhotoAnimation.PAN_UP -> it.copy(animPanUp = !it.animPanUp)
            PhotoAnimation.PAN_DOWN -> it.copy(animPanDown = !it.animPanDown)
        }
    }

    fun updateServerUrl(url: String) = viewModelScope.launch {
        settingsRepo.setServerUrl(url.trim().trimEnd('/'))
        immichRepo.invalidateCache()
    }

    fun updateApiKey(key: String) = viewModelScope.launch {
        settingsRepo.setApiKey(key.trim())
        immichRepo.invalidateCache()
    }

    fun setAdminPin(pin: String) = viewModelScope.launch {
        settingsRepo.setAdminPin(pin)
    }

    fun clearAdminPin() = viewModelScope.launch {
        settingsRepo.clearAdminPin()
    }

    fun verifyAdminPin(pin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(settingsRepo.verifyAdminPin(pin))
    }

    fun resetAll() = viewModelScope.launch {
        // Wipe the media cache BEFORE settings so a rapid re-login can't
        // serve cached files from a previous account. Without this, the
        // cache-first slideshow loader shows the old account's photos even
        // after DataStore + EncryptedSharedPreferences are cleared.
        mediaCacheRepo.clearAll()
        settingsRepo.clearAll()
    }

    fun syncNow() = viewModelScope.launch {
        val albumIds = settingsRepo.selectedAlbumIds.first()
        syncRequestedFlow.value = syncScheduler.syncNow(albumIds)
    }
}
