package app.murmur.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.murmur.AppContainer
import app.murmur.MurmurApp

/** The process-wide [AppContainer] (manual DI). */
val Context.container: AppContainer get() = (applicationContext as MurmurApp).container

/** A ViewModel built from the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

private fun animatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Tracks the system "Remove animations" setting (animator duration scale 0). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember { mutableStateOf(animatorScale(context) == 0f) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = animatorScale(context) == 0f
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduce
}
