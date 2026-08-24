package com.dav3.immichframe.domain.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.dav3.immichframe.ScreenScheduleReceiver
import com.dav3.immichframe.domain.model.SlideshowSettings
import com.dav3.immichframe.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules the daily transition between normal display and display sleep. */
@Singleton
class DisplayScheduleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun updateSchedule(settings: SlideshowSettings) {
        cancelAlarms()
        if (!settings.screenScheduleEnabled) {
            settingsRepository.setScreenScheduleSleeping(false)
            return
        }

        settingsRepository.setScreenScheduleSleeping(settings.isScreenScheduleActive(currentMinuteOfDay()))
        scheduleNextTransitions(settings)
    }

    suspend fun rescheduleAfterBoot() {
        updateSchedule(settingsRepository.slideshowSettings.first())
    }

    suspend fun handleAlarm(action: String?) {
        val settings = settingsRepository.slideshowSettings.first()
        if (!settings.screenScheduleEnabled) return

        when (action) {
            ACTION_SLEEP -> settingsRepository.setScreenScheduleSleeping(true)
            ACTION_WAKE -> {
                wakeScreen()
                settingsRepository.setScreenScheduleSleeping(false)
            }
            else -> return
        }
        scheduleNextTransitions(settings)
    }

    private fun scheduleNextTransitions(settings: SlideshowSettings) {
        schedule(
            minutes = settings.screenScheduleOffTime,
            action = ACTION_SLEEP,
            requestCode = REQUEST_SLEEP,
        )
        schedule(
            minutes = settings.screenScheduleOnTime,
            action = ACTION_WAKE,
            requestCode = REQUEST_WAKE,
        )
    }

    private fun schedule(minutes: Int, action: String, requestCode: Int) {
        val triggerAtMillis = nextOccurrenceMillis(minutes, ZonedDateTime.now())
        val pendingIntent = pendingIntent(action, requestCode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarms() {
        alarmManager.cancel(pendingIntent(ACTION_SLEEP, REQUEST_SLEEP))
        alarmManager.cancel(pendingIntent(ACTION_WAKE, REQUEST_WAKE))
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, ScreenScheduleReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$WAKE_LOCK_TAG:wake",
        ).acquire(WAKE_LOCK_TIMEOUT_MILLIS)
    }

    companion object {
        const val ACTION_SLEEP = "com.familyphotoframe.immichframe.lowbandwidth.action.SLEEP_DISPLAY"
        const val ACTION_WAKE = "com.familyphotoframe.immichframe.lowbandwidth.action.WAKE_DISPLAY"

        private const val REQUEST_SLEEP = 7101
        private const val REQUEST_WAKE = 7102
        private const val WAKE_LOCK_TAG = "ImmichFrameDisplaySchedule"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10_000L
    }
}

internal fun nextOccurrenceMillis(minutesSinceMidnight: Int, now: ZonedDateTime): Long {
    val normalizedMinutes = minutesSinceMidnight.coerceIn(0, 1439)
    var occurrence = now
        .toLocalDate()
        .atStartOfDay(now.zone)
        .plusMinutes(normalizedMinutes.toLong())
    if (!occurrence.isAfter(now)) occurrence = occurrence.plusDays(1)
    return occurrence.toInstant().toEpochMilli()
}

/** True on pre-Android 12 devices, or after the user grants the special access. */
fun canScheduleExactDisplayAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/** Opens Android's own per-app "Alarms & reminders" access screen. */
fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }
}

private fun currentMinuteOfDay(): Int {
    val now = ZonedDateTime.now()
    return now.hour * 60 + now.minute
}
