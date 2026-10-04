package com.dav3.immichframe

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dav3.immichframe.ui.nav.ImmichNavHost
import com.dav3.immichframe.ui.settings.SettingsViewModel
import com.dav3.immichframe.ui.theme.ImmichFrameTheme
import com.dav3.immichframe.ui.update.UpdateViewModel
import dagger.hilt.android.AndroidEntryPoint

private const val FULLSCREEN_REHIDE_DELAY_MILLIS = 1_500L
private const val RESUME_REHIDE_DELAY_MILLIS = 300L

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    /**
     * A launcher or kiosk controller can deliver a new launch intent to the
     * existing activity while its Compose destination remains unchanged.
     * Propagate that event so the slideshow restarts its playback effects even
     * when no lifecycle state transition is observed by the destination.
     */
    private var launchGeneration by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchGeneration++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImmichFrameTheme {
                val updateVm: UpdateViewModel = hiltViewModel()

                val settingsVm: SettingsViewModel = hiltViewModel()
                val settingsState by settingsVm.uiState.collectAsState()
                val s = settingsState.settings
                // Keep every in-app screen (including Settings) in the same
                // immersive presentation as the slideshow. System bars remain
                // reachable with a swipe, but do not occupy screen space while
                // the frame is being administered.
                val rootView = LocalView.current
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(s.fullscreen, lifecycleOwner, rootView) {
                    val controller = WindowCompat.getInsetsController(window, rootView)
                    if (!s.fullscreen) {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                        onDispose { }
                    } else {
                        val hideBars = Runnable {
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                        }
                        fun scheduleHide(delayMillis: Long = FULLSCREEN_REHIDE_DELAY_MILLIS) {
                            rootView.removeCallbacks(hideBars)
                            rootView.postDelayed(hideBars, delayMillis)
                        }

                        controller.systemBarsBehavior =
                            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        scheduleHide(0)

                        val visibilityListener = View.OnSystemUiVisibilityChangeListener {
                            scheduleHide()
                        }
                        val touchListener = View.OnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) scheduleHide()
                            false
                        }
                        val lifecycleObserver = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                scheduleHide(RESUME_REHIDE_DELAY_MILLIS)
                            }
                        }
                        rootView.setOnSystemUiVisibilityChangeListener(visibilityListener)
                        rootView.setOnTouchListener(touchListener)
                        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

                        onDispose {
                            rootView.removeCallbacks(hideBars)
                            rootView.setOnSystemUiVisibilityChangeListener(null)
                            rootView.setOnTouchListener(null)
                            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }

                ImmichNavHost(
                    launchGeneration = launchGeneration,
                )

                // Update check on startup (non-blocking, background download)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    updateVm.checkForUpdate()
                }
            }
        }
    }
}
