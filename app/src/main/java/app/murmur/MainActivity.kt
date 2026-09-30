package app.murmur

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.murmur.ui.MurmurRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Conversation to open (from a notification tap), consumed by the UI. */
    private val pendingConversation = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as MurmurApp).container
        splash.setKeepOnScreenCondition { container.settingsState.value == null }
        splash.setOnExitAnimationListener { provider ->
            // The icon scales up and fades out; then the splash is removed.
            val icon: View = try {
                provider.iconView
            } catch (_: RuntimeException) {
                provider.view
            }
            val set = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.35f),
                    ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.35f),
                    ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f),
                )
                duration = 380L
                interpolator = AnticipateInterpolator(0.6f)
            }
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = provider.remove()
            })
            set.start()
        }
        enableEdgeToEdge()
        handleIntent(intent)
        // With app lock on, hide the app from screenshots and the recent-apps preview.
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
            MurmurRoot(container = container, pendingConversation = pendingConversation)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_CONVERSATION)?.let { pendingConversation.value = it }
        if (intent?.getBooleanExtra(EXTRA_ANSWER_CALL, false) == true) {
            intent.removeExtra(EXTRA_ANSWER_CALL)
            (application as MurmurApp).container.calls.requestAnswer()
        }
    }

    companion object {
        const val EXTRA_CONVERSATION = "app.murmur.extra.CONVERSATION"
        const val EXTRA_ANSWER_CALL = "app.murmur.extra.ANSWER_CALL"
    }
}
