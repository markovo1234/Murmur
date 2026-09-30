package app.murmur.ui.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.HopBadge
import app.murmur.ui.components.Motion
import app.murmur.ui.components.sharedAvatar
import app.murmur.ui.theme.MurmurTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ring radii as a fraction of the radar radius: three range rings + the dashed via-mesh ring. */
private val RING_FRACTIONS = floatArrayOf(0.30f, 0.56f, 0.80f)
private const val MESH_RING_FRACTION = 1.0f
private const val MAX_PEERS = 12

/** Where a peer sits: direct peers on a ring by RSSI band (gliding within it), mesh-only peers outside. */
internal fun radarRadiusFraction(peer: Peer): Float {
    if (peer.status != PeerStatus.NEARBY) return MESH_RING_FRACTION
    val rssi = (peer.rssi ?: -85).toFloat()
    fun within(ring: Float, t: Float) = ring + (t.coerceIn(0f, 1f) - 0.5f) * 0.10f
    return when {
        rssi > -60f -> within(RING_FRACTIONS[0], (-40f - rssi) / 20f)
        rssi > -80f -> within(RING_FRACTIONS[1], (-60f - rssi) / 20f)
        else -> within(RING_FRACTIONS[2], (-80f - rssi) / 20f)
    }
}

/** A stable angle (radians) derived from the peerId. */
internal fun radarAngle(id: PeerId): Float {
    val mixed = (id.raw xor (id.raw ushr 29)) * -0x61c8864680b583ebL
    val degrees = ((mixed ushr 40) % 360L).toFloat()
    return (degrees / 180f * PI).toFloat()
}

/** Bob phase in [0, 1). */
internal fun bobPhase(id: PeerId): Float = ((id.raw ushr 11) and 1023L).toFloat() / 1024f

@Stable
private class Slot(peer: Peer, val isNew: Boolean) {
    var peer by mutableStateOf(peer)
    val visible = MutableTransitionState(false).apply { targetState = true }
    val radius = Animatable(radarRadiusFraction(peer))
}

/**
 * The radar: one Canvas for rings, the rotating sweep and pulse rings (cached brushes, no per-frame
 * allocations, animated values read only in the draw phase); peer avatars are composables layered on top.
 */
@Composable
fun RadarField(
    myEmoji: String,
    myColor: Int,
    peers: List<Peer>,
    onPeerClick: (PeerId) -> Unit,
    modifier: Modifier = Modifier,
    showPeers: Boolean = true,
) {
    val reduce = MurmurTheme.reduceMotion
    val colors = MurmurTheme.colors
    val scheme = MaterialTheme.colorScheme
    val transition = if (reduce) null else rememberInfiniteTransition(label = "radar")
    val sweep: State<Float>? = transition?.animateFloat(0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "sweep")
    val pulse: State<Float>? = transition?.animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "pulse")
    val bob: State<Float>? = transition?.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "bob")

    val visiblePeers = remember(peers) { peers.filter { it.isOnline && !it.blocked }.take(MAX_PEERS) }

    BoxWithConstraints(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = radarDescription(visiblePeers) },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val avatarSize = 44.dp
        val sizePx = with(density) { maxWidth.toPx() }
        val radiusPx = sizePx / 2f - with(density) { (avatarSize / 2 + 6.dp).toPx() }

        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val ringStroke = Stroke(width = 1.dp.toPx())
                    val meshStroke = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                    )
                    val pulseStroke = Stroke(width = 2.dp.toPx())
                    val edgeStroke = 2.dp.toPx()
                    val sweepBrush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.70f to Color.Transparent,
                        1f to colors.radarSweep.copy(alpha = if (colors.isDark) 0.38f else 0.28f),
                        center = center,
                    )
                    val glow = Brush.radialGradient(
                        0f to scheme.primary.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radiusPx,
                    )
                    val ringColor = colors.radarRing
                    val meshColor = colors.hop.copy(alpha = 0.45f)
                    onDrawBehind {
                        drawCircle(glow, radius = radiusPx, center = center)
                        for (f in RING_FRACTIONS) drawCircle(ringColor, radius = radiusPx * f, center = center, style = ringStroke)
                        drawCircle(meshColor, radius = radiusPx * MESH_RING_FRACTION, center = center, style = meshStroke)
                        val p = pulse?.value
                        if (p != null) {
                            // Two rings half a cycle apart: a new one every 1.6 s, each expanding over 3.2 s.
                            val p2 = (p + 0.5f) % 1f
                            drawCircle(colors.radarPulse, radius = radiusPx * p, center = center, alpha = 0.35f * (1f - p), style = pulseStroke)
                            drawCircle(colors.radarPulse, radius = radiusPx * p2, center = center, alpha = 0.35f * (1f - p2), style = pulseStroke)
                        }
                        val angle = sweep?.value ?: 300f
                        rotate(angle, pivot = center) {
                            drawCircle(sweepBrush, radius = radiusPx, center = center)
                            drawLine(
                                colors.radarSweep.copy(alpha = 0.55f),
                                start = center,
                                end = Offset(center.x + radiusPx, center.y),
                                strokeWidth = edgeStroke,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                },
        )

        EmojiAvatar(myEmoji, myColor, 56.dp, modifier = Modifier.semantics { contentDescription = "You" })

        if (showPeers) {
            PeerLayer(visiblePeers, radiusPx, bob, onPeerClick)
        }
    }
}

@Composable
private fun PeerLayer(peers: List<Peer>, radiusPx: Float, bob: State<Float>?, onPeerClick: (PeerId) -> Unit) {
    val slots = remember { mutableStateMapOf<PeerId, Slot>() }
    var initialized by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val reduce = MurmurTheme.reduceMotion

    LaunchedEffect(peers) {
        val ids = peers.map { it.id }.toSet()
        var arrived = false
        for (p in peers) {
            val slot = slots[p.id]
            if (slot == null) {
                slots[p.id] = Slot(p, isNew = initialized)
                arrived = arrived || initialized
            } else {
                slot.peer = p
                slot.visible.targetState = true
            }
        }
        for ((id, slot) in slots) if (id !in ids) slot.visible.targetState = false
        if (arrived) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        initialized = true
    }

    val bobPx = with(LocalDensity.current) { 3.dp.toPx() }
    for ((id, slot) in slots) {
        androidx.compose.runtime.key(id.raw) {
            val peer = slot.peer
            LaunchedEffect(radarRadiusFraction(peer)) {
                val target = radarRadiusFraction(peer)
                if (reduce) slot.radius.snapTo(target) else slot.radius.animateTo(target, Motion.move())
            }
            if (!slot.visible.targetState && slot.visible.isIdle && !slot.visible.currentState) {
                LaunchedEffect(Unit) { slots.remove(id) }
            }
            val angle = remember(id) { radarAngle(id) }
            val phase = remember(id) { bobPhase(id) }
            AnimatedVisibility(
                visibleState = slot.visible,
                enter = if (reduce) fadeIn(tween(0)) else scaleIn(Motion.pop(), initialScale = 0.2f) + fadeIn(),
                exit = if (reduce) fadeOut(tween(0)) else fadeOut(tween(260)) + scaleOut(tween(260), targetScale = 0.4f),
                modifier = Modifier.offset {
                    val r = radiusPx * slot.radius.value
                    IntOffset((cos(angle) * r).roundToInt(), (sin(angle) * r).roundToInt())
                },
            ) {
                RadarAvatar(peer, slot.isNew && !reduce, bob, phase, bobPx, onClick = { onPeerClick(id) })
            }
        }
    }
}

@Composable
private fun RadarAvatar(peer: Peer, ripple: Boolean, bob: State<Float>?, phase: Float, bobPx: Float, onClick: () -> Unit) {
    val rippleProgress = remember { Animatable(if (ripple) 0f else 1f) }
    LaunchedEffect(Unit) { if (ripple) rippleProgress.animateTo(1f, tween(900)) }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(48.dp)
            .graphicsLayer {
                val t = bob?.value ?: 0f
                translationY = bobPx * sin(2f * PI.toFloat() * (t + phase))
            }
            .drawBehind {
                val p = rippleProgress.value
                if (p < 1f) drawCircle(primary, radius = size.minDimension / 2f * (1f + 1.4f * p), alpha = 0.5f * (1f - p), style = Stroke(2.dp.toPx()))
            }
            .clip(CircleShape)
            .clickable(onClickLabel = "Open profile", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = Format.peerA11y(peer) },
        contentAlignment = Alignment.Center,
    ) {
        EmojiAvatar(
            peer.emoji,
            peer.colorIndex,
            44.dp,
            modifier = Modifier.sharedAvatar(peer.id.toHex()),
            verified = peer.verified,
        )
        if (peer.status == PeerStatus.VIA_MESH) {
            HopBadge(peer.hops, Modifier.align(Alignment.BottomCenter).offset(y = 6.dp))
        }
    }
}

private fun radarDescription(peers: List<Peer>): String {
    val nearby = peers.count { it.status == PeerStatus.NEARBY }
    val mesh = peers.size - nearby
    return "Radar: $nearby nearby, $mesh via mesh"
}
