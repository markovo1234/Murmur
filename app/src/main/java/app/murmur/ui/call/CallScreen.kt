package app.murmur.ui.call

import android.Manifest
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.call.CallManager
import app.murmur.call.CallPhase
import app.murmur.call.CallUi
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.container
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.delay

/** Everything the call screen can do. */
class CallActions(
    val onAccept: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onHangUp: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onToggleSpeaker: () -> Unit = {},
    val onMinimize: () -> Unit = {},
    val onExpand: () -> Unit = {},
)

/**
 * Covers the whole app during a call (drawn above the lock screen, like a phone call). It can shrink to
 * a pill at the top so you can keep using Murmur while talking.
 */
@Composable
fun CallOverlay(onCoveringChange: (Boolean) -> Unit = {}) {
    val c = LocalContext.current.container
    val call by c.calls.state.collectAsStateWithLifecycle()
    val current = call
    if (current == null) {
        LaunchedEffect(Unit) { onCoveringChange(false) }
        return
    }
    var minimized by rememberSaveable(current.callId) { mutableStateOf(false) }
    LaunchedEffect(minimized) { onCoveringChange(!minimized) }
    var micDenied by rememberSaveable(current.callId) { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) c.calls.accept() else micDenied = true
    }
    val accept: () -> Unit = {
        if (c.calls.hasMicPermission()) c.calls.accept() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // "Answer" in the notification.
    LaunchedEffect(current.callId) {
        c.calls.answerRequests.collect { id ->
            if (id == current.callId) {
                minimized = false
                accept()
            }
        }
    }
    BackHandler(enabled = !minimized && current.phase != CallPhase.ENDED) { minimized = true }

    val actions = CallActions(
        onAccept = accept,
        onDecline = { c.calls.decline() },
        onHangUp = { c.calls.hangup() },
        onToggleMute = { c.calls.toggleMute() },
        onToggleSpeaker = { c.calls.toggleSpeaker() },
        onMinimize = { minimized = true },
        onExpand = { minimized = false },
    )
    AnimatedContent(
        targetState = minimized,
        transitionSpec = {
            (slideInVertically { if (targetState) -it else it } + fadeIn()) togetherWith (slideOutVertically { if (targetState) it else -it } + fadeOut())
        },
        label = "call",
    ) { mini ->
        if (mini) {
            CallPill(current, actions)
        } else {
            CallScreen(current, actions, micDenied = micDenied)
        }
    }
}

@Composable
fun CallScreen(call: CallUi, actions: CallActions, micDenied: Boolean = false) {
    val dark = MurmurTheme.colors.isDark
    val top = if (dark) Color(0xFF0B1026) else Color(0xFF1E2A5A)
    val bottom = if (dark) Color(0xFF05070F) else Color(0xFF0E1330)
    // A Surface swallows touches, so nothing underneath reacts.
    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom))),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (call.phase != CallPhase.ENDED) {
                    IconButton(onClick = actions.onMinimize) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize call", tint = Color.White)
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Encrypted voice call · Bluetooth", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.size(48.dp))
            }

            Spacer(Modifier.weight(0.6f))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
                val ringing = call.phase == CallPhase.DIALING || call.phase == CallPhase.RINGING || call.phase == CallPhase.INCOMING
                if (ringing && !MurmurTheme.reduceMotion) Ripples(Color.White.copy(alpha = 0.35f))
                EmojiAvatar(call.emoji, call.colorIndex, 128.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                call.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                statusText(call),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (call.phase == CallPhase.ACTIVE) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CallSignal(call.signal)
                    Spacer(Modifier.width(8.dp))
                    Text(routeText(call), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
            if (micDenied && call.phase == CallPhase.INCOMING) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Murmur needs the microphone to answer. Allow it in the app's settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFB4AB),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallPhase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(MurmurIcons.CallEnd, "Decline", DECLINE_RED, onClick = actions.onDecline)
                    RoundAction(Icons.Filled.Call, "Answer", ACCEPT_GREEN, onClick = actions.onAccept)
                }
                CallPhase.ENDED -> Spacer(Modifier.height(ACTION_SIZE + 28.dp))
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                    ToggleAction(
                        icon = if (call.muted) MurmurIcons.MicOff else MurmurIcons.Mic,
                        label = if (call.muted) "Unmute" else "Mute",
                        checked = call.muted,
                        enabled = call.phase == CallPhase.ACTIVE,
                        onClick = actions.onToggleMute,
                    )
                    RoundAction(MurmurIcons.CallEnd, if (call.phase == CallPhase.ACTIVE) "End" else "Cancel", DECLINE_RED, onClick = actions.onHangUp)
                    ToggleAction(
                        icon = MurmurIcons.Speaker,
                        label = "Speaker",
                        checked = call.speaker,
                        enabled = call.phase == CallPhase.ACTIVE,
                        onClick = actions.onToggleSpeaker,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

/** Minimized call: tap to go back to the full screen. */
@Composable
private fun CallPill(call: CallUi, actions: CallActions) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = CircleShape,
            color = if (call.phase == CallPhase.INCOMING) ACCEPT_GREEN else Color(0xFF1E2A5A),
            contentColor = Color.White,
            shadowElevation = 6.dp,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = "Return to call", onClick = actions.onExpand),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("${call.name} · ${statusText(call)}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (call.muted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(MurmurIcons.MicOff, contentDescription = "muted", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun statusText(call: CallUi): String = when (call.phase) {
    CallPhase.DIALING -> "Calling…"
    CallPhase.RINGING -> "Ringing…"
    CallPhase.INCOMING -> "Incoming call"
    CallPhase.ACTIVE -> {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(call.connectedAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
        CallManager.formatDuration(((now - call.connectedAt) / 1000).coerceAtLeast(0))
    }
    CallPhase.ENDED -> call.endReason ?: "Call ended"
}

private fun routeText(call: CallUi): String = when (val h = call.hops) {
    null -> "Connecting…"
    1 -> "Direct Bluetooth link"
    else -> "Through the mesh · $h hops"
}

@Composable
private fun CallSignal(level: Int) {
    val word = when (level) {
        3 -> "good"
        2 -> "fair"
        1 -> "poor"
        else -> "no audio arriving"
    }
    Row(
        Modifier.semantics { contentDescription = "Call quality $word" },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 1..3) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 4).dp)
                    .background(if (i <= level) Color.White else Color.White.copy(alpha = 0.25f), CircleShape),
            )
        }
    }
}

@Composable
private fun Ripples(color: Color) {
    val t = rememberInfiniteTransition(label = "ripples")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Restart), label = "phase")
    Canvas(Modifier.fillMaxSize()) {
        val maxR = size.minDimension / 2
        for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            val r = maxR * (0.55f + 0.45f * p)
            drawCircle(color.copy(alpha = color.alpha * (1f - p)), radius = r, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = color,
            contentColor = Color.White,
            modifier = Modifier
                .size(ACTION_SIZE)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (checked) Color.White else Color.White.copy(alpha = 0.16f),
            contentColor = if (checked) Color(0xFF0B1026) else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier
                .size(ACTION_SIZE_SMALL)
                .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
                .semantics {
                    contentDescription = label
                    stateDescription = if (checked) "On" else "Off"
                },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

private val ACTION_SIZE: Dp = 72.dp
private val ACTION_SIZE_SMALL: Dp = 64.dp
private val ACCEPT_GREEN = Color(0xFF16A34A)
private val DECLINE_RED = Color(0xFFDC2626)

private fun previewCall(phase: CallPhase) = CallUi(
    callId = "c",
    peerId = PreviewData.luna.id,
    name = "Luna",
    emoji = "🌙",
    colorIndex = 0,
    outgoing = phase != CallPhase.INCOMING,
    phase = phase,
    connectedAt = System.currentTimeMillis() - 83_000,
    muted = phase == CallPhase.ACTIVE,
    signal = 2,
    hops = 2,
)

@Preview(name = "Call · incoming", showBackground = true)
@Composable
private fun IncomingPreview() = MurmurTheme(ThemeMode.LIGHT) { CallScreen(previewCall(CallPhase.INCOMING), CallActions()) }

@Preview(name = "Call · active · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ActivePreview() = MurmurTheme(ThemeMode.DARK) { CallScreen(previewCall(CallPhase.ACTIVE), CallActions()) }

@Preview(name = "Call · ringing", showBackground = true)
@Composable
private fun RingingPreview() = MurmurTheme(ThemeMode.LIGHT) { CallScreen(previewCall(CallPhase.RINGING), CallActions()) }

@Preview(name = "Call · pill", showBackground = true)
@Composable
private fun PillPreview() = MurmurTheme(ThemeMode.LIGHT) { CallPill(previewCall(CallPhase.ACTIVE), CallActions()) }
