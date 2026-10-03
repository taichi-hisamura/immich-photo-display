package com.dav3.immichframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.dav3.immichframe.domain.repository.SettingsRepository
import com.dav3.immichframe.domain.system.DisplayScheduleManager
import com.dav3.immichframe.domain.system.isDefaultLauncher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restores unattended photo-frame operation after an in-place APK update. */
@AndroidEntryPoint
class PackageReplacedReceiver : BroadcastReceiver() {
    @Inject lateinit var displayScheduleManager: DisplayScheduleManager

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.slideshowSettings.first()
                displayScheduleManager.updateSchedule(settings)

                val configured =
                    settingsRepository.serverUrl.first().isNotBlank() &&
                        settingsRepository.apiKey.first().isNotBlank() &&
                        settingsRepository.selectedAlbumIds.first().isNotEmpty()
                val sleeping = settingsRepository.slideshowSettings.first().screenScheduleSleeping
                if (!shouldResumeAfterPackageReplace(configured, sleeping)) return@launch

                val hasOverlayPermission = Settings.canDrawOverlays(context)
                val defaultLauncher = isDefaultLauncher(context)
                if (!canLaunchFrameFromBackground(hasOverlayPermission, defaultLauncher)) {
                    Log.w(
                        TAG,
                        "Package updated, but background launch is unavailable; " +
                            "grant display-over-other-apps or use the app as the default launcher",
                    )
                    return@launch
                }

                displayScheduleManager.wakeScreenNow()
                val launchIntent =
                    if (defaultLauncher) {
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    } else {
                        context.packageManager.getLaunchIntentForPackage(context.packageName)
                    }
                launchIntent?.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                launchIntent?.let(context::startActivity)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to restore photo display after package update", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PackageReplacedReceiver"
    }
}

internal fun shouldResumeAfterPackageReplace(configured: Boolean, scheduledSleeping: Boolean): Boolean = configured && !scheduledSleeping

internal fun canLaunchFrameFromBackground(hasOverlayPermission: Boolean, isDefaultLauncher: Boolean): Boolean = hasOverlayPermission || isDefaultLauncher
