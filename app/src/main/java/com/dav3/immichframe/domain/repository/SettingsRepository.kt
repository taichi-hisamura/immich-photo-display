package com.dav3.immichframe.domain.repository

import com.dav3.immichframe.domain.model.PermissionCheckResult
import com.dav3.immichframe.domain.model.SlideshowSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val serverUrl: Flow<String>
    val apiKey: Flow<String>
    val selectedAlbumIds: Flow<List<String>>
    val slideshowSettings: Flow<SlideshowSettings>
    val onboardingCompletedSteps: Flow<Set<String>>

    /**
     * The final locally retained image when every selected album is confirmed
     * empty. It prevents the frame from becoming blank while awaiting photos.
     */
    val fallbackAssetId: Flow<String?>

    /** Whether a six-digit in-app PIN protects the administration screens. */
    val adminPinConfigured: Flow<Boolean>

    /** Asset IDs the user has manually toggled in the media-selection grid. */
    val mediaSelectionToggledIds: Flow<Set<String>>

    /**
     * Whether new media (added to albums after the user's last visit) should be
     * shown by default in the slideshow. Default true.
     */
    val mediaSelectionNewItemsShown: Flow<Boolean>

    /** Immich server version string (e.g. "1.135.0"), persisted at connect time. */
    val serverVersion: Flow<String>

    /** Whether the stored API key was created with scoped permissions. */
    val apiKeyScoped: Flow<Boolean>

    /**
     * Last permission check result (serialized as JSON). Null if never checked
     * or if the key was changed since the last check.
     */
    val permissionStatus: Flow<PermissionCheckResult?>

    suspend fun setServerUrl(url: String)

    suspend fun setApiKey(key: String)

    suspend fun setServerVersion(version: String)

    suspend fun setApiKeyScoped(scoped: Boolean)

    suspend fun setPermissionStatus(status: PermissionCheckResult?)

    suspend fun setSelectedAlbumIds(ids: List<String>)

    suspend fun setSlideshowSettings(settings: SlideshowSettings)

    suspend fun setFallbackAssetId(assetId: String?)

    /** Update only the transient state controlled by the display schedule receiver. */
    suspend fun setScreenScheduleSleeping(sleeping: Boolean)

    suspend fun setMediaSelectionToggledIds(ids: Set<String>)

    suspend fun setMediaSelectionNewItemsShown(shown: Boolean)

    suspend fun markOnboardingStepCompleted(stepId: String)

    suspend fun resetOnboarding()

    suspend fun resetOnboardingForScreen(stepIds: Collection<String>)

    /** Stores only a salted verifier; the PIN itself is never persisted. */
    suspend fun setAdminPin(pin: String)

    suspend fun verifyAdminPin(pin: String): Boolean

    suspend fun clearAdminPin()

    suspend fun clearAll()
}
