package app.murmur.call

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.murmur.MainActivity
import app.murmur.MurmurApp
import app.murmur.ui.call.LockScreenCall
import app.murmur.ui.rememberReduceMotion
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The call screen in its own window, so a call wakes the phone and rings over the lock screen like
 * WhatsApp (the incoming-call notification's full-screen intent opens it). It shows only the call: the
 * rest of Murmur stays behind the lock screen and app lock. It closes itself once the call is over.
 */
class CallActivity : ComponentActivity() {
    /** "Answer" was tapped in the notification. */
    private val answerTapped = MutableStateFlow(false)

    private val container get() = (application as MurmurApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        // The call screen is always dark.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                // Ringing outlasts most screen timeouts, so keep the screen on until it's answered or over.
                // During the call it sleeps as usual (the proximity sensor handles holding it to your ear).
                container.calls.state.map { it?.phase == CallPhase.INCOMING }.distinctUntilChanged().collect { ringing ->
                    if (ringing) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
        // Same as the app: with app lock on, no screenshots or recent-apps preview.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settingsState.map { it?.appLock?.enabled == true }.distinctUntilChanged().collect { secure ->
                    if (secure) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
        setContent {
            val settingsOrNull by container.settingsState.collectAsStateWithLifecycle()
            val settings = settingsOrNull ?: return@setContent
            MurmurTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, reduceMotion = rememberReduceMotion()) {
                LockScreenCall(
                    answerTapped = answerTapped,
                    hideCaller = container.calls.hideContent(),
                    onOpenApp = ::openApp,
                    onDone = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) {
            intent.removeExtra(EXTRA_ANSWER)
            answerTapped.value = true
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    /** The minimize arrow: unlock if needed, then carry on in the app with the call shrunk to the pill. */
    private fun openApp() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            goToApp()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = goToApp()
            },
        )
    }

    private fun goToApp() {
        container.calls.setMinimized(true)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_ANSWER = "app.murmur.extra.ANSWER_CALL"
    }
}
