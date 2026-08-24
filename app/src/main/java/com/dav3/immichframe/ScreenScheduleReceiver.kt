package com.dav3.immichframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dav3.immichframe.domain.system.DisplayScheduleManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ScreenScheduleReceiver : BroadcastReceiver() {
    @Inject lateinit var displayScheduleManager: DisplayScheduleManager

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                displayScheduleManager.handleAlarm(intent.action)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
