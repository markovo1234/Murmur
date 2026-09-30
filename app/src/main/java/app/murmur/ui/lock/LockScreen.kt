package app.murmur.ui.lock

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.data.AppLock
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.container
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen PIN pad shown over the app while it's locked. */
@Composable
fun LockScreen() {
    val c = LocalContext.current.container
    val activity = LocalActivity.current
    val settings by c.settingsState.collectAsStateWithLifecycle()
    val lockedOutUntil by c.appLock.lockedOutUntil.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(c.clock.now()) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Back leaves the app instead of revealing what's underneath.
    BackHandler { activity?.moveTaskToBack(true) }
    LaunchedEffect(lockedOutUntil) {
        while (c.clock.now() < lockedOutUntil) {
            now = c.clock.now()
            delay(250)
        }
        now = c.clock.now()
    }
    val lockoutSeconds = ((lockedOutUntil - now + 999) / 1000).coerceAtLeast(0).toInt()

    fun submit() {
        if (busy || !AppLock.isValidPin(pin)) return
        busy = true
        scope.launch {
            when (c.appLock.unlock(pin)) {
                AppLock.Result.OK -> error = null
                AppLock.Result.WRONG -> {
                    error = "Wrong PIN"
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    shake.animateTo(0f, keyframes {
                        durationMillis = 360
                        -18f at 60
                        16f at 120
                        -10f at 200
                        6f at 280
                    })
                }
                AppLock.Result.LOCKED_OUT -> error = null
            }
            pin = ""
            busy = false
        }
    }

    LockContent(
        emoji = settings?.profile?.emoji ?: "🔒",
        colorIndex = settings?.profile?.colorIndex ?: 0,
        pinLength = pin.length,
        error = error,
        lockoutSeconds = lockoutSeconds,
        busy = busy,
        shakeOffset = shake.value,
        onDigit = { d ->
            if (pin.length < MAX_PIN && lockoutSeconds == 0 && !busy) {
                pin += d
                error = null
            }
        },
        onBackspace = { pin = pin.dropLast(1) },
        onSubmit = ::submit,
    )
}

@Composable
fun LockContent(
    emoji: String,
    colorIndex: Int,
    pinLength: Int,
    error: String?,
    lockoutSeconds: Int,
    busy: Boolean,
    shakeOffset: Float,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box {
                EmojiAvatar(emoji, colorIndex, 88.dp)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Murmur is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    lockoutSeconds > 0 -> "Too many tries. Wait $lockoutSeconds s."
                    error != null -> error
                    else -> "Enter your PIN"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null || lockoutSeconds > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(24.dp))
            PinDots(pinLength, Modifier.graphicsLayer { translationX = shakeOffset * density })
            Spacer(Modifier.height(32.dp))
            PinPad(
                enabled = lockoutSeconds == 0 && !busy,
                canSubmit = AppLock.isValidPin("0".repeat(pinLength)) && lockoutSeconds == 0 && !busy,
                onDigit = onDigit,
                onBackspace = onBackspace,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun PinDots(length: Int, modifier: Modifier = Modifier) {
    val shown = maxOf(4, length)
    Row(
        modifier
            .height(20.dp)
            .semantics { contentDescription = "$length digits entered" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(shown) { i ->
            val filled = i < length
            val scale by animateFloatAsState(if (filled) 1f else 0.7f, label = "dot")
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun PinPad(enabled: Boolean, canSubmit: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (d in row) Key(d.toString(), enabled) { onDigit(d) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyIcon(enabled, "Delete digit", onClick = onBackspace) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            Key("0", enabled) { onDigit('0') }
            KeyIcon(canSubmit, "Unlock", primary = true, onClick = onSubmit) { Icon(Icons.Filled.Check, contentDescription = null) }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun KeyIcon(enabled: Boolean, description: String, primary: Boolean = false, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (primary && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (primary && enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
        },
        modifier = Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

private val KEY_SIZE = 72.dp
private const val MAX_PIN = 8

@Preview(name = "Lock · light", showBackground = true)
@Composable
private fun LockLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    LockContent("🐧", 1, 3, null, 0, false, 0f, {}, {}, {})
}

@Preview(name = "Lock · dark · wrong", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LockDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    LockContent("🐧", 1, 0, "Wrong PIN", 0, false, 0f, {}, {}, {})
}
