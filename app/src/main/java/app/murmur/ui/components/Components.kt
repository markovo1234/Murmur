package app.murmur.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.sp
import app.murmur.ui.theme.MurmurTheme
import app.murmur.ui.theme.MurmurType
import app.murmur.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

// ------------------------------------------------------------------------ shared elements

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shares a peer's avatar between Radar/Chats and the chat top bar. No-op in previews. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedAvatar(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedAvatar.sharedElement(
            sharedContentState = rememberSharedContentState(key = "avatar-$key"),
            animatedVisibilityScope = animated,
        )
    }
}

// ------------------------------------------------------------------------ avatar

/** Circular emoji avatar on one of the 10 preset colors, with a 2 dp ring. */
@Composable
fun EmojiAvatar(
    emoji: String,
    colorIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    online: Boolean? = null,
    verified: Boolean = false,
) {
    val base = Palette.avatar(colorIndex)
    val fill = base.copy(alpha = if (MurmurTheme.colors.isDark) 0.28f else 0.24f).compositeOver(MaterialTheme.colorScheme.surface)
    val emojiSize = with(LocalDensity.current) { (size * 0.52f).toSp() }
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, base, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, style = TextStyle(fontSize = emojiSize), textAlign = TextAlign.Center)
        }
        if (online != null) {
            val scale by animateFloatAsState(
                targetValue = if (online) 1f else 0f,
                animationSpec = Motion.popOrSnap(MurmurTheme.reduceMotion),
                label = "onlineDot",
            )
            val dot = (size * 0.28f).coerceAtLeast(10.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot)
                    .semantics { if (online) contentDescription = "Online" }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(MurmurTheme.colors.online),
            )
        }
        if (verified) {
            val badge = (size * 0.34f).coerceAtLeast(14.dp)
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).size(badge),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(MurmurIcons.Verified, contentDescription = "Verified", modifier = Modifier.padding(2.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------ signal bars

/** 0–4 bars for a scan RSSI. */
fun signalLevel(rssi: Int?): Int = when {
    rssi == null -> 0
    rssi > -55 -> 4
    rssi > -67 -> 3
    rssi > -80 -> 2
    rssi > -92 -> 1
    else -> 0
}

fun signalWord(rssi: Int?): String = when {
    rssi == null -> "unknown signal"
    rssi > -60 -> "strong signal"
    rssi > -80 -> "good signal"
    else -> "weak signal"
}

@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier) {
    val level = signalLevel(rssi)
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outline
    Row(
        modifier.semantics { contentDescription = "Signal ${signalWord(rssi)}" },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 1..4) {
            val color by animateColorAsState(if (i <= level) on else off, label = "bar$i")
            val height by androidx.compose.animation.core.animateDpAsState(
                targetValue = (4 + i * 3).dp * (if (i <= level) 1f else 0.85f),
                animationSpec = Motion.moveOrSnap(MurmurTheme.reduceMotion),
                label = "barHeight$i",
            )
            Box(Modifier.width(4.dp).height(height).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

// ------------------------------------------------------------------------ typing dots

/** Three dots: 900 ms cycle, 150 ms stagger, 4 dp rise. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val reduce = MurmurTheme.reduceMotion
    val t = if (reduce) {
        null
    } else {
        rememberInfiniteTransition(label = "typing").animateFloat(
            initialValue = 0f,
            targetValue = 900f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "typingClock",
        )
    }
    val rise = with(LocalDensity.current) { 4.dp.toPx() }
    Row(modifier.semantics { contentDescription = "Typing" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 3) {
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        val clock = t?.value ?: 0f
                        val phase = (clock - i * 150f) / 300f
                        translationY = if (phase in 0f..1f) -rise * sin(PI.toFloat() * phase) else 0f
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

// ------------------------------------------------------------------------ status dot + pill

/** A colored dot with a soft pulse ring. The color animates between states. */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val animated by animateColorAsState(color, label = "dotColor")
    val reduce = MurmurTheme.reduceMotion
    val pulse = if (reduce) {
        null
    } else {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
            label = "pulseProgress",
        )
    }
    Canvas(modifier.size(size * 2.2f)) {
        val r = size.toPx() / 2
        val p = pulse?.value
        if (p != null) drawCircle(animated, radius = r * (1f + 1.2f * p), alpha = 0.45f * (1f - p))
        drawCircle(animated, radius = r)
    }
}

// ------------------------------------------------------------------------ badges

@Composable
fun HopBadge(hops: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = "Relayed, $hops hops" },
        shape = CircleShape,
        color = MurmurTheme.colors.hop,
        contentColor = MurmurTheme.colors.onHop,
    ) {
        Text(
            "$hops hop${if (hops == 1) "" else "s"}",
            style = MurmurType.Mono,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Mono, letter-spaced, upper-case section label ("CHANNELS"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MurmurType.Section,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * A pill-shaped "hold for 2 s" bar whose fill sweeps left to right while pressed, then springs back if
 * released early. Accessibility services get [onAccessibilityClick] instead.
 */
@Composable
fun HoldToConfirmBar(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 2_000,
    color: Color = MaterialTheme.colorScheme.error,
    onAccessibilityClick: () -> Unit = onConfirmed,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .drawBehind { drawRect(color.copy(alpha = 0.35f), size = size.copy(width = size.width * progress.value)) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val fill = scope.launch {
                        progress.animateTo(1f, tween(((1f - progress.value) * holdMillis).toInt(), easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onConfirmed()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (fill.isActive) {
                        fill.cancel()
                        scope.launch { progress.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
                    }
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = "$label. Hold for 2 seconds"
                onClick(label) {
                    onAccessibilityClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Unread badge that pops when the count grows. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    val reduce = MurmurTheme.reduceMotion
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count > last && !reduce) {
            scale.snapTo(1.35f)
            scale.animateTo(1f, Motion.pop())
        }
        last = count
    }
    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .semantics { contentDescription = "$count unread" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

// ------------------------------------------------------------------------ shimmer

/** Sweeps a highlight across its content. The brush is built once; only a translation animates. */
@Composable
fun Modifier.shimmer(): Modifier {
    if (MurmurTheme.reduceMotion) return this
    val highlight = MaterialTheme.colorScheme.onSurface
    val progress by rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f,
        2f,
        infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "shimmerX",
    )
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val band = size.width * 0.5f
            val brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to highlight.copy(alpha = 0.55f),
                1f to Color.Transparent,
                startX = 0f,
                endX = band,
            )
            onDrawWithContent {
                drawContent()
                translate(left = progress * size.width - band / 2) {
                    drawRect(brush, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(band, size.height), blendMode = BlendMode.SrcAtop)
                }
            }
        }
}

// ------------------------------------------------------------------------ hold to confirm

/**
 * Hold for [holdMillis] to confirm: a circular fill grows while pressed and springs back if released
 * early. Accessibility services get [onAccessibilityClick] instead.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 2_000,
    color: Color = MaterialTheme.colorScheme.error,
    /** Accessibility services can't "hold"; this runs on their click (e.g. show a confirm dialog). */
    onAccessibilityClick: () -> Unit = onConfirmed,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val track = color.copy(alpha = 0.18f)
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.10f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val fill = scope.launch {
                        progress.animateTo(1f, tween(((1f - progress.value) * holdMillis).toInt(), easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onConfirmed()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (fill.isActive) {
                        // Released early: spring back.
                        fill.cancel()
                        scope.launch { progress.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
                    }
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = "$label. Hold for 2 seconds"
                onClick(label) {
                    onAccessibilityClick()
                    true
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val stroke = 3.dp.toPx()
            drawCircle(track, style = Stroke(stroke))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawCircle(color, radius = size.minDimension / 2 * 0.42f * progress.value)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Uses [LocalContentColor] at 70% for secondary text like timestamps. */
@Composable
fun metaColor(): Color = LocalContentColor.current.copy(alpha = 0.7f)

// ------------------------------------------------------------------------ press bounce

/**
 * Tactile feedback: the content dips to [pressedScale] while held and springs back on release, like a
 * physical key. Honours "Remove animations". Pair it with the caller's own [androidx.compose.foundation.clickable];
 * this only animates.
 */
@Composable
fun Modifier.pressBounce(pressedScale: Float = 0.96f): Modifier {
    if (MurmurTheme.reduceMotion) return this
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.pop(),
        label = "pressBounce",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        // An indication-less hoverable/pressable source so `pressed` tracks touches even when the
        // caller's own clickable draws the ripple.
        .hoverable(interaction)
        .indication(interaction, null)
        .then(
            Modifier.pointerInput(interaction) {
                detectTapGestures(
                    onPress = {
                        val press = androidx.compose.foundation.interaction.PressInteraction.Press(it)
                        interaction.tryEmit(press)
                        val released = tryAwaitRelease()
                        interaction.tryEmit(
                            if (released) {
                                androidx.compose.foundation.interaction.PressInteraction.Release(press)
                            } else {
                                androidx.compose.foundation.interaction.PressInteraction.Cancel(press)
                            },
                        )
                    },
                )
            },
        )
}

// ------------------------------------------------------------------------ animated count

/**
 * A number that rolls when it changes: the old digits slide out and the new ones slide in (up when the
 * value grows, down when it shrinks). Snaps when animations are off.
 */
@Composable
fun AnimatedCount(
    count: Int,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
) {
    val reduce = MurmurTheme.reduceMotion
    androidx.compose.animation.AnimatedContent(
        targetState = count,
        transitionSpec = {
            if (reduce) {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.snap()) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap())
            } else {
                val up = targetState > initialState
                val enter = androidx.compose.animation.slideInVertically(Motion.pop()) { if (up) it else -it } +
                    androidx.compose.animation.fadeIn()
                val exit = androidx.compose.animation.slideOutVertically(Motion.move()) { if (up) -it else it } +
                    androidx.compose.animation.fadeOut()
                (enter togetherWith exit).using(androidx.compose.animation.SizeTransform(clip = false))
            }
        },
        modifier = modifier,
        label = "count",
    ) { value ->
        Text(value.toString(), style = style, color = color, fontWeight = fontWeight)
    }
}
