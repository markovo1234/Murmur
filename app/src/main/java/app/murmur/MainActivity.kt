package app.murmur

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.murmur.ui.MurmurRoot
import kotlinx.coroutines.flow.MutableStateFlow

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
    }

    companion object {
        const val EXTRA_CONVERSATION = "app.murmur.extra.CONVERSATION"
    }
}
