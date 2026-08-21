package com.dav3.immichframe

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.dav3.immichframe.data.remote.buildImmichMediaClient
import com.dav3.immichframe.domain.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class ImmichFrameApp :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: com.dav3.immichframe.data.sync.SyncScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Schedule periodic sync based on user settings (deferred to background)
        appScope.launch {
            syncScheduler.schedulePeriodicSync()
        }
    }

    /** Provides the global preview-only [ImageLoader] used by `AsyncImage`. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = {
                        buildImmichMediaClient(
                            serverUrl = { runBlocking { settingsRepository.serverUrl.first() } },
                            apiKey = { runBlocking { settingsRepository.apiKey.first() } },
                        )
                    },
                ),
            )
        }
        .crossfade(true)
        .build()
}
