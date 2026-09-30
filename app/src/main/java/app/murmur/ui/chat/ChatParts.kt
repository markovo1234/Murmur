package app.murmur.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.murmur.core.Murmur
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.HopBadge
import app.murmur.ui.components.Motion
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.TypingDots
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme

/** Bubble shape: 20 dp corners, 6 dp on the side where it groups with its neighbours. */
private fun bubbleShape(outgoing: Boolean, withOlder: Boolean, withNewer: Boolean): RoundedCornerShape {
    val big = Dimens.BubbleRadius
    val small = Dimens.BubbleGroupedRadius
    return if (outgoing) {
        RoundedCornerShape(topStart = big, topEnd = if (withOlder) small else big, bottomEnd = if (withNewer) small else big, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = if (withOlder) small else big, topEnd = big, bottomEnd = big, bottomStart = if (withNewer) small else big)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: MessageUi,
    isNearby: Boolean,
    maxBubbleWidth: Dp,
    animateIn: Boolean,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onSenderClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = MurmurTheme.reduceMotion
    val appear = remember { Animatable(if (animateIn && !reduce) 0f else 1f) }
    LaunchedEffect(Unit) { if (appear.value < 1f) appear.animateTo(1f, tween(280, easing = Motion.Emphasized)) }
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    val colors = MurmurTheme.colors
    val haptics = LocalHapticFeedback.current

    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                // New messages: fade + 16 dp slide-up + scale 0.96 → 1.
                val p = appear.value
                alpha = p
                translationY = (1f - p) * rise
                val s = 0.96f + 0.04f * p
                scaleX = s
                scaleY = s
            }
            .padding(horizontal = 12.dp, vertical = if (msg.groupedWithOlder) 1.dp else 5.dp),
        horizontalAlignment = if (msg.outgoing) Alignment.End else Alignment.Start,
    ) {
        if (msg.showHeader) {
            Row(
                Modifier
                    .padding(bottom = 2.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Open profile", onClick = onSenderClick)
                    .heightIn(min = Dimens.MinTouch)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmojiAvatar(msg.senderEmoji, msg.senderColor, 24.dp)
                Spacer(Modifier.width(6.dp))
                Text(msg.senderName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (msg.hops > 1) {
                    Spacer(Modifier.width(6.dp))
                    HopBadge(msg.hops)
                }
            }
        }
        val shape = bubbleShape(msg.outgoing, msg.groupedWithOlder, msg.groupedWithNewer)
        val background = if (msg.outgoing) {
            remember(colors) { Brush.linearGradient(listOf(colors.ownBubbleStart, colors.ownBubbleEnd), start = Offset.Zero, end = Offset.Infinite) }
        } else {
            null
        }
        val a11y = buildString {
            append(if (msg.outgoing) "You" else msg.senderName)
            append(": ")
            append(msg.body)
            append(", ")
            append(Format.clock(msg.time))
            msg.status?.let { append(", ").append(statusLabel(it)) }
            if (!msg.outgoing && msg.hops > 1) append(", relayed, ${msg.hops} hops")
        }
        Box(
            Modifier
                .widthIn(max = maxBubbleWidth)
                .clip(shape)
                .then(if (background != null) Modifier.background(background) else Modifier.background(colors.otherBubble))
                .combinedClickable(
                    onClickLabel = null,
                    onLongClickLabel = "Message actions",
                    onClick = {},
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
                .semantics { contentDescription = a11y }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(
                msg.body,
                style = MaterialTheme.typography.bodyLarge,
                color = if (msg.outgoing) colors.onOwnBubble else colors.onOtherBubble,
            )
        }
        val showMeta = !msg.groupedWithNewer || (msg.outgoing && !isNearby && msg.status != DeliveryStatus.READ)
        if (showMeta || msg.status == DeliveryStatus.FAILED) {
            MetaRow(msg, isNearby, onRetry)
        }
    }
}

@Composable
private fun MetaRow(msg: MessageUi, isNearby: Boolean, onRetry: () -> Unit) {
    val meta = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    if (msg.status == DeliveryStatus.FAILED) {
        Text(
            "Not delivered · tap to retry",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Retry sending", onClick = onRetry)
                .heightIn(min = Dimens.MinTouch)
                .wrapContentHeight()
                .padding(horizontal = 8.dp),
        )
        return
    }
    Row(
        Modifier.padding(top = 3.dp, start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!msg.outgoing && msg.hops > 1 && !msg.showHeader) HopBadge(msg.hops)
        Text(Format.clock(msg.time), style = MaterialTheme.typography.labelSmall, color = meta)
        if (msg.outgoing && !isNearby && msg.status != null) DeliveryTicks(msg.status)
    }
}

fun statusLabel(status: DeliveryStatus): String = when (status) {
    DeliveryStatus.PENDING -> "pending"
    DeliveryStatus.SENDING -> "sending"
    DeliveryStatus.SENT -> "sent"
    DeliveryStatus.DELIVERED -> "delivered"
    DeliveryStatus.READ -> "read"
    DeliveryStatus.FAILED -> "not delivered"
}

/** Clock → ✓ → ✓✓ → ✓✓ in primary, crossfading with a small pop. */
@Composable
fun DeliveryTicks(status: DeliveryStatus) {
    val meta = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    val reduce = MurmurTheme.reduceMotion
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (fadeIn(tween(160)) + scaleIn(Motion.pop(), initialScale = 0.4f)) togetherWith fadeOut(tween(120))
            }
        },
        contentKey = {
            when (it) {
                DeliveryStatus.PENDING, DeliveryStatus.SENDING -> 0
                DeliveryStatus.SENT -> 1
                DeliveryStatus.DELIVERED -> 2
                DeliveryStatus.READ -> 3
                DeliveryStatus.FAILED -> 4
            }
        },
        label = "ticks",
    ) { s ->
        val (icon, tint) = when (s) {
            DeliveryStatus.PENDING, DeliveryStatus.SENDING -> MurmurIcons.Clock to meta
            DeliveryStatus.SENT -> MurmurIcons.Tick to meta
            DeliveryStatus.DELIVERED -> MurmurIcons.DoubleTick to meta
            DeliveryStatus.READ -> MurmurIcons.DoubleTick to MaterialTheme.colorScheme.primary
            DeliveryStatus.FAILED -> MurmurIcons.Clock to MaterialTheme.colorScheme.error
        }
        Icon(icon, contentDescription = statusLabel(s).replaceFirstChar { it.uppercase() }, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(Dimens.BubbleRadius))
            .background(MurmurTheme.colors.otherBubble)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        TypingDots()
    }
}

@Composable
fun DayChip(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Grows to 5 lines, shows a byte counter near the 1,000-byte limit, and scales/rotates the send button in
 * when there's something to send.
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val bytes = remember(text) { Murmur.utf8Size(text) }
    val tooLong = bytes > Murmur.MAX_TEXT_BYTES
    val canSend = enabled && text.isNotBlank() && !tooLong
    val shown by animateFloatAsState(if (canSend) 1f else 0f, Motion.popOrSnap(MurmurTheme.reduceMotion), label = "send")
    val haptics = LocalHapticFeedback.current
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                minLines = 1,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bytes >= COUNTER_FROM) {
                    Text(
                        "$bytes/${Murmur.MAX_TEXT_BYTES}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp).semantics {
                            contentDescription = "$bytes of ${Murmur.MAX_TEXT_BYTES} bytes used"
                        },
                    )
                }
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSend()
                    },
                    enabled = canSend,
                    modifier = Modifier
                        .size(Dimens.MinTouch)
                        .graphicsLayer {
                            scaleX = 0.4f + 0.6f * shown
                            scaleY = 0.4f + 0.6f * shown
                            rotationZ = (1f - shown) * -90f
                            alpha = shown.coerceIn(0f, 1f)
                        },
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private const val COUNTER_FROM = 800
