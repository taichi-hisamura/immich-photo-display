package com.dav3.immichframe.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dav3.immichframe.domain.model.ClockFormat
import com.dav3.immichframe.domain.model.ClockPosition
import com.dav3.immichframe.domain.model.FillMode
import com.dav3.immichframe.domain.model.PermissionCheckResult
import com.dav3.immichframe.domain.model.PermissionStatus
import com.dav3.immichframe.domain.model.RequiredPermission
import com.dav3.immichframe.domain.model.SlideshowSettings
import com.dav3.immichframe.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SELECTED_ALBUMS = stringSetPreferencesKey("selected_album_ids")
        val INTERVAL = intPreferencesKey("interval_sec")
        val TRANSITION = floatPreferencesKey("transition_sec")
        val FILL_MODE = stringPreferencesKey("fill_mode")
        val SHOW_CLOCK = stringPreferencesKey("show_clock")
        val CLOCK_SECONDS = stringPreferencesKey("clock_seconds")
        val CLOCK_FORMAT = stringPreferencesKey("clock_format")
        val CLOCK_SIZE = floatPreferencesKey("clock_size")
        val CLOCK_X = floatPreferencesKey("clock_x")
        val CLOCK_Y = floatPreferencesKey("clock_y")
        val KEEP_SCREEN_ON = stringPreferencesKey("keep_screen_on")
        val FULLSCREEN = stringPreferencesKey("fullscreen")
        val SHUFFLE = stringPreferencesKey("shuffle")
        val SKIP_VIDEOS = stringPreferencesKey("skip_videos")
        val MUTED = stringPreferencesKey("muted")
        val START_ON_BOOT = stringPreferencesKey("start_on_boot")
        val LAUNCHER_MODE = stringPreferencesKey("launcher_mode")
        val BOOT_VERIFIED = stringPreferencesKey("boot_verified")
        val AUTO_UPDATE = stringPreferencesKey("auto_update")
        val CLOCK_SNAP_TO_GRID = stringPreferencesKey("clock_snap_to_grid")
        val ADAPTIVE_BACKGROUND = stringPreferencesKey("adaptive_background")
        val PHOTO_ANIMATIONS = stringPreferencesKey("photo_animations")
        val ANIM_ZOOM_IN = stringPreferencesKey("anim_zoom_in")
        val ANIM_ZOOM_OUT = stringPreferencesKey("anim_zoom_out")
        val ANIM_PAN_LEFT = stringPreferencesKey("anim_pan_left")
        val ANIM_PAN_RIGHT = stringPreferencesKey("anim_pan_right")
        val ANIM_PAN_UP = stringPreferencesKey("anim_pan_up")
        val ANIM_PAN_DOWN = stringPreferencesKey("anim_pan_down")
        val AUTO_SYNC = stringPreferencesKey("auto_sync")
        val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        val NIGHT_MODE = stringPreferencesKey("night_mode")
        val NIGHT_MODE_START = intPreferencesKey("night_mode_start")
        val NIGHT_MODE_END = intPreferencesKey("night_mode_end")
        val NIGHT_MODE_BRIGHTNESS = intPreferencesKey("night_mode_brightness")
        val SCREEN_SCHEDULE_ENABLED = stringPreferencesKey("screen_schedule_enabled")
        val SCREEN_SCHEDULE_OFF_TIME = intPreferencesKey("screen_schedule_off_time")
        val SCREEN_SCHEDULE_ON_TIME = intPreferencesKey("screen_schedule_on_time")
        val SCREEN_SCHEDULE_SLEEPING = stringPreferencesKey("screen_schedule_sleeping")
        val MEDIA_SELECTION_TOGGLED = stringSetPreferencesKey("media_selection_toggled_ids")
        val MEDIA_SELECTION_NEW_SHOWN = stringPreferencesKey("media_selection_new_shown")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val API_KEY_SCOPED = stringPreferencesKey("api_key_scoped")
        val PERMISSION_STATUS = stringPreferencesKey("permission_status")
        val ONBOARDING_COMPLETED_STEPS = stringSetPreferencesKey("onboarding_completed_steps")
    }

    private val masterKey by lazy {
        MasterKey
            .Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override val serverUrl: Flow<String> =
        context.appDataStore.data.map { it[Keys.SERVER_URL] ?: "" }

    private val _apiKey = MutableStateFlow(encPrefs.getString("api_key", "") ?: "")

    private val _adminPinConfigured = MutableStateFlow(encPrefs.contains("admin_pin_hash"))

    override val apiKey: Flow<String> = _apiKey.asStateFlow()

    override val adminPinConfigured: Flow<Boolean> = _adminPinConfigured.asStateFlow()

    override val selectedAlbumIds: Flow<List<String>> =
        context.appDataStore.data.map {
            (it[Keys.SELECTED_ALBUMS] ?: emptySet()).toList()
        }

    override val onboardingCompletedSteps: Flow<Set<String>> =
        context.appDataStore.data.map {
            it[Keys.ONBOARDING_COMPLETED_STEPS] ?: emptySet()
        }

    override val slideshowSettings: Flow<SlideshowSettings> =
        context.appDataStore.data.map { prefs ->
            SlideshowSettings(
                intervalSeconds = prefs[Keys.INTERVAL] ?: 30,
                transitionSeconds = prefs[Keys.TRANSITION] ?: 1f,
                fillMode = FillMode.valueOf(prefs[Keys.FILL_MODE] ?: FillMode.CONTAIN.name),
                showClock = prefs[Keys.SHOW_CLOCK]?.toBoolean() ?: false,
                clockSeconds = prefs[Keys.CLOCK_SECONDS]?.toBoolean() ?: false,
                clockFormat = ClockFormat.valueOf(prefs[Keys.CLOCK_FORMAT] ?: ClockFormat.H24.name),
                clockSize = prefs[Keys.CLOCK_SIZE] ?: 48f,
                clockPosition = ClockPosition(
                    x = prefs[Keys.CLOCK_X] ?: -1f,
                    y = prefs[Keys.CLOCK_Y] ?: -1f,
                ),
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON]?.toBoolean() ?: true,
                fullscreen = prefs[Keys.FULLSCREEN]?.toBoolean() ?: true,
                shuffle = prefs[Keys.SHUFFLE]?.toBoolean() ?: true,
                skipVideos = true,
                muted = prefs[Keys.MUTED]?.toBoolean() ?: true,
                startOnBoot = prefs[Keys.START_ON_BOOT]?.toBoolean() ?: false,
                launcherMode = prefs[Keys.LAUNCHER_MODE]?.toBoolean() ?: false,
                bootVerified = prefs[Keys.BOOT_VERIFIED]?.toBoolean() ?: false,
                autoUpdate = false,
                clockSnapToGrid = prefs[Keys.CLOCK_SNAP_TO_GRID]?.toBoolean() ?: true,
                adaptiveBackground = prefs[Keys.ADAPTIVE_BACKGROUND]?.toBoolean() ?: false,
                photoAnimations = prefs[Keys.PHOTO_ANIMATIONS]?.toBoolean() ?: false,
                animZoomIn = prefs[Keys.ANIM_ZOOM_IN]?.toBoolean() ?: true,
                animZoomOut = prefs[Keys.ANIM_ZOOM_OUT]?.toBoolean() ?: true,
                animPanLeft = prefs[Keys.ANIM_PAN_LEFT]?.toBoolean() ?: true,
                animPanRight = prefs[Keys.ANIM_PAN_RIGHT]?.toBoolean() ?: true,
                animPanUp = prefs[Keys.ANIM_PAN_UP]?.toBoolean() ?: true,
                animPanDown = prefs[Keys.ANIM_PAN_DOWN]?.toBoolean() ?: true,
                autoSync = prefs[Keys.AUTO_SYNC]?.toBoolean() ?: true,
                syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL_MINUTES] ?: 360,
                nightMode = prefs[Keys.NIGHT_MODE]?.toBoolean() ?: false,
                nightModeStart = prefs[Keys.NIGHT_MODE_START] ?: 1320,
                nightModeEnd = prefs[Keys.NIGHT_MODE_END] ?: 420,
                nightModeBrightness = prefs[Keys.NIGHT_MODE_BRIGHTNESS] ?: 0,
                screenScheduleEnabled = prefs[Keys.SCREEN_SCHEDULE_ENABLED]?.toBoolean() ?: false,
                screenScheduleOffTime = prefs[Keys.SCREEN_SCHEDULE_OFF_TIME] ?: 1320,
                screenScheduleOnTime = prefs[Keys.SCREEN_SCHEDULE_ON_TIME] ?: 420,
                screenScheduleSleeping = prefs[Keys.SCREEN_SCHEDULE_SLEEPING]?.toBoolean() ?: false,
            )
        }

    override val mediaSelectionToggledIds: Flow<Set<String>> =
        context.appDataStore.data.map {
            it[Keys.MEDIA_SELECTION_TOGGLED] ?: emptySet()
        }

    override val mediaSelectionNewItemsShown: Flow<Boolean> =
        context.appDataStore.data.map {
            it[Keys.MEDIA_SELECTION_NEW_SHOWN]?.toBoolean() ?: true
        }

    override val serverVersion: Flow<String> =
        context.appDataStore.data.map { it[Keys.SERVER_VERSION] ?: "" }

    override val apiKeyScoped: Flow<Boolean> =
        context.appDataStore.data.map {
            it[Keys.API_KEY_SCOPED]?.toBoolean() ?: false
        }

    override val permissionStatus: Flow<PermissionCheckResult?> =
        context.appDataStore.data.map { prefs ->
            prefs[Keys.PERMISSION_STATUS]?.let { json ->
                runCatching { deserializePermissionStatus(json) }.getOrNull()
            }
        }

    override suspend fun setServerUrl(url: String) {
        context.appDataStore.edit { it[Keys.SERVER_URL] = url }
    }

    override suspend fun setApiKey(key: String) {
        encPrefs.edit().putString("api_key", key).apply()
        _apiKey.value = key
    }

    override suspend fun setServerVersion(version: String) {
        context.appDataStore.edit { it[Keys.SERVER_VERSION] = version }
    }

    override suspend fun setApiKeyScoped(scoped: Boolean) {
        context.appDataStore.edit { it[Keys.API_KEY_SCOPED] = scoped.toString() }
    }

    override suspend fun setPermissionStatus(status: PermissionCheckResult?) {
        context.appDataStore.edit { prefs ->
            if (status == null) {
                prefs.remove(Keys.PERMISSION_STATUS)
            } else {
                prefs[Keys.PERMISSION_STATUS] = serializePermissionStatus(status)
            }
        }
    }

    override suspend fun setSelectedAlbumIds(ids: List<String>) {
        context.appDataStore.edit { it[Keys.SELECTED_ALBUMS] = ids.toSet() }
    }

    override suspend fun setSlideshowSettings(settings: SlideshowSettings) {
        context.appDataStore.edit {
            it[Keys.INTERVAL] = settings.intervalSeconds
            it[Keys.TRANSITION] = settings.transitionSeconds
            it[Keys.FILL_MODE] = settings.fillMode.name
            it[Keys.SHOW_CLOCK] = settings.showClock.toString()
            it[Keys.CLOCK_SECONDS] = settings.clockSeconds.toString()
            it[Keys.CLOCK_FORMAT] = settings.clockFormat.name
            it[Keys.CLOCK_SIZE] = settings.clockSize
            it[Keys.CLOCK_X] = settings.clockPosition.x
            it[Keys.CLOCK_Y] = settings.clockPosition.y
            it[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn.toString()
            it[Keys.FULLSCREEN] = settings.fullscreen.toString()
            it[Keys.SHUFFLE] = settings.shuffle.toString()
            it[Keys.SKIP_VIDEOS] = true.toString()
            it[Keys.MUTED] = settings.muted.toString()
            it[Keys.START_ON_BOOT] = settings.startOnBoot.toString()
            it[Keys.LAUNCHER_MODE] = settings.launcherMode.toString()
            it[Keys.BOOT_VERIFIED] = settings.bootVerified.toString()
            it[Keys.AUTO_UPDATE] = false.toString()
            it[Keys.CLOCK_SNAP_TO_GRID] = settings.clockSnapToGrid.toString()
            it[Keys.ADAPTIVE_BACKGROUND] = settings.adaptiveBackground.toString()
            it[Keys.PHOTO_ANIMATIONS] = settings.photoAnimations.toString()
            it[Keys.ANIM_ZOOM_IN] = settings.animZoomIn.toString()
            it[Keys.ANIM_ZOOM_OUT] = settings.animZoomOut.toString()
            it[Keys.ANIM_PAN_LEFT] = settings.animPanLeft.toString()
            it[Keys.ANIM_PAN_RIGHT] = settings.animPanRight.toString()
            it[Keys.ANIM_PAN_UP] = settings.animPanUp.toString()
            it[Keys.ANIM_PAN_DOWN] = settings.animPanDown.toString()
            it[Keys.AUTO_SYNC] = settings.autoSync.toString()
            it[Keys.SYNC_INTERVAL_MINUTES] = settings.syncIntervalMinutes
            it[Keys.NIGHT_MODE] = settings.nightMode.toString()
            it[Keys.NIGHT_MODE_START] = settings.nightModeStart
            it[Keys.NIGHT_MODE_END] = settings.nightModeEnd
            it[Keys.NIGHT_MODE_BRIGHTNESS] = settings.nightModeBrightness
            it[Keys.SCREEN_SCHEDULE_ENABLED] = settings.screenScheduleEnabled.toString()
            it[Keys.SCREEN_SCHEDULE_OFF_TIME] = settings.screenScheduleOffTime
            it[Keys.SCREEN_SCHEDULE_ON_TIME] = settings.screenScheduleOnTime
            it[Keys.SCREEN_SCHEDULE_SLEEPING] = settings.screenScheduleSleeping.toString()
        }
    }

    override suspend fun setScreenScheduleSleeping(sleeping: Boolean) {
        context.appDataStore.edit { it[Keys.SCREEN_SCHEDULE_SLEEPING] = sleeping.toString() }
    }

    override suspend fun setMediaSelectionToggledIds(ids: Set<String>) {
        context.appDataStore.edit {
            it[Keys.MEDIA_SELECTION_TOGGLED] = ids
        }
    }

    override suspend fun setMediaSelectionNewItemsShown(shown: Boolean) {
        context.appDataStore.edit {
            it[Keys.MEDIA_SELECTION_NEW_SHOWN] = shown.toString()
        }
    }

    override suspend fun markOnboardingStepCompleted(stepId: String) {
        context.appDataStore.edit { prefs ->
            val current = prefs[Keys.ONBOARDING_COMPLETED_STEPS] ?: emptySet()
            prefs[Keys.ONBOARDING_COMPLETED_STEPS] = current + stepId
        }
    }

    override suspend fun resetOnboarding() {
        context.appDataStore.edit { prefs ->
            prefs.remove(Keys.ONBOARDING_COMPLETED_STEPS)
        }
    }

    override suspend fun resetOnboardingForScreen(stepIds: Collection<String>) {
        context.appDataStore.edit { prefs ->
            val current = prefs[Keys.ONBOARDING_COMPLETED_STEPS] ?: emptySet()
            prefs[Keys.ONBOARDING_COMPLETED_STEPS] = current - stepIds
        }
    }

    override suspend fun setAdminPin(pin: String) {
        require(pin.matches(Regex("\\d{6}"))) { "Admin PIN must contain exactly six digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val verifier = derivePinVerifier(pin, salt)
        encPrefs.edit()
            .putString("admin_pin_salt", Base64.getEncoder().encodeToString(salt))
            .putString("admin_pin_hash", Base64.getEncoder().encodeToString(verifier))
            .apply()
        _adminPinConfigured.value = true
    }

    override suspend fun verifyAdminPin(pin: String): Boolean {
        val encodedSalt = encPrefs.getString("admin_pin_salt", null) ?: return false
        val encodedVerifier = encPrefs.getString("admin_pin_hash", null) ?: return false
        return runCatching {
            val salt = Base64.getDecoder().decode(encodedSalt)
            val expected = Base64.getDecoder().decode(encodedVerifier)
            MessageDigest.isEqual(derivePinVerifier(pin, salt), expected)
        }.getOrDefault(false)
    }

    override suspend fun clearAdminPin() {
        encPrefs.edit()
            .remove("admin_pin_salt")
            .remove("admin_pin_hash")
            .apply()
        _adminPinConfigured.value = false
    }

    override suspend fun clearAll() {
        context.appDataStore.edit { prefs ->
            // Preserve tour completion — there's a separate "Reset All Tours"
            // button for that. Clearing settings should not un-take tours.
            val tourSteps = prefs[Keys.ONBOARDING_COMPLETED_STEPS]
            prefs.clear()
            if (tourSteps != null) {
                prefs[Keys.ONBOARDING_COMPLETED_STEPS] = tourSteps
            }
        }
        encPrefs.edit().clear().apply()
        _apiKey.value = ""
        _adminPinConfigured.value = false
    }

    private fun derivePinVerifier(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private val statusJson = Json { ignoreUnknownKeys = true }

    private fun serializePermissionStatus(status: PermissionCheckResult): String {
        val arr = buildJsonArray {
            status.statuses.forEach { (perm, st) ->
                add(
                    buildJsonObject {
                        put("scope", JsonPrimitive(perm.scope))
                        put(
                            "status",
                            JsonPrimitive(
                                when (st) {
                                    PermissionStatus.Granted -> "granted"
                                    PermissionStatus.Denied -> "denied"
                                    PermissionStatus.Unknown -> "unknown"
                                },
                            ),
                        )
                    },
                )
            }
        }
        return statusJson.encodeToString(JsonArray.serializer(), arr)
    }

    private fun deserializePermissionStatus(json: String): PermissionCheckResult {
        val arr = statusJson.decodeFromString(JsonArray.serializer(), json)
        val statuses = mutableMapOf<RequiredPermission, PermissionStatus>()
        for (element in arr) {
            val obj = element.jsonObject
            val scope = obj["scope"]!!.jsonPrimitive.content
            val statusStr = obj["status"]!!.jsonPrimitive.content
            val perm = RequiredPermission.entries.find { it.scope == scope } ?: continue
            val st = when (statusStr) {
                "granted" -> PermissionStatus.Granted
                "denied" -> PermissionStatus.Denied
                else -> PermissionStatus.Unknown
            }
            statuses[perm] = st
        }
        return PermissionCheckResult(statuses.toMap())
    }
}
