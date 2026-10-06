package app.murmur.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.murmur.core.Murmur
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.text.Mentions
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.HopBadge
import app.murmur.ui.components.Motion
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.RichText
import app.murmur.ui.components.TypingDots
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import app.murmur.ui.theme.MurmurType
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

/** Entrance for new messages: fade + 16 dp slide-up + scale 0.96 → 1 (history appears instantly). */
@Composable
private fun rememberAppear(animateIn: Boolean): Animatable<Float, *> {
    val reduce = MurmurTheme.reduceMotion
    val appear = remember { Animatable(if (animateIn && !reduce) 0f else 1f) }
    LaunchedEffect(Unit) { if (appear.value < 1f) appear.animateTo(1f, tween(280, easing = Motion.Emphasized)) }
    return appear
}

private fun Modifier.appear(appear: Animatable<Float, *>, rise: Float): Modifier = graphicsLayer {
    val p = appear.value
    alpha = p
    translationY = (1f - p) * rise
    val s = 0.96f + 0.04f * p
    scaleX = s
    scaleY = s
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: MessageUi,
    isRoom: Boolean,
    maxBubbleWidth: Dp,
    animateIn: Boolean,
    highlighted: Boolean,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onSenderClick: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onQuoteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appear = rememberAppear(animateIn)
    val density = LocalDensity.current
    val rise = with(density) { 16.dp.toPx() }
    val colors = MurmurTheme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Swipe right to reply.
    val swipe = remember { Animatable(0f) }
    val maxSwipe = with(density) { 88.dp.toPx() }
    val trigger = with(density) { 60.dp.toPx() }
    val flash by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f) else Color.Transparent,
        tween(400),
        label = "flash",
    )

    Box(
        modifier
            .fillMaxWidth()
            .appear(appear, rise)
            .background(flash)
            .then(
                if (msg.retracted) {
                    Modifier
                } else {
                    Modifier.pointerInput(msg.id) {
                        var fired = false
                        var offset = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                fired = false
                                offset = 0f
                            },
                            onDragEnd = { scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) } },
                            onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                        ) { change, dx ->
                            offset = (offset + dx).coerceIn(0f, maxSwipe)
                            val next = offset
                            scope.launch { swipe.snapTo(next) }
                            if (!fired && next >= trigger) {
                                fired = true
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onReply()
                            }
                            change.consume()
                        }
                    }
                },
            ),
    ) {
        // The reply arrow that appears while swiping.
        Icon(
            MurmurIcons.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .size(22.dp)
                .graphicsLayer {
                    val p = (swipe.value / trigger).coerceIn(0f, 1f)
                    alpha = p
                    scaleX = 0.6f + 0.4f * p
                    scaleY = 0.6f + 0.4f * p
                },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipe.value.roundToInt(), 0) }
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
            val gradient = remember(colors) { Brush.linearGradient(listOf(colors.ownBubbleStart, colors.ownBubbleEnd), start = Offset.Zero, end = Offset.Infinite) }
            val textColor = when {
                msg.retracted -> MaterialTheme.colorScheme.onSurfaceVariant
                msg.outgoing -> colors.onOwnBubble
                else -> colors.onOtherBubble
            }
            val a11y = buildString {
                append(if (msg.outgoing) "You" else msg.senderName)
                append(": ")
                if (msg.retracted) append("message deleted") else append(msg.body)
                if (msg.quote != null) append(", replying to ${msg.quoteAuthor ?: "a message"}: ${msg.quote}")
                append(", ").append(Format.clock(msg.time))
                msg.status?.let { append(", ").append(statusLabel(it)) }
                if (!msg.outgoing && msg.hops > 1) append(", relayed, ${msg.hops} hops")
                if (msg.mentionsMe) append(", mentions you")
                if (msg.reactions.isNotEmpty()) append(", reactions ").append(msg.reactions.joinToString { "${it.emoji} ${it.count}" })
            }
            Box(
                Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(shape)
                    .then(
                        when {
                            msg.retracted -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                            msg.outgoing -> Modifier.background(gradient)
                            else -> Modifier.background(colors.otherBubble)
                        },
                    )
                    .then(if (msg.mentionsMe && !msg.retracted) Modifier.border(2.dp, colors.hop, shape) else Modifier)
                    .combinedClickable(
                        onLongClickLabel = "Message actions",
                        onClick = {},
                        onDoubleClick = if (msg.retracted) null else ({
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReact("❤️")
                        }),
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    )
                    .semantics {
                        contentDescription = a11y
                        if (!msg.retracted) {
                            customActions = listOf(
                                CustomAccessibilityAction("Reply") { onReply(); true },
                                CustomAccessibilityAction("React with a heart") { onReact("❤️"); true },
                            )
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Column {
                    if (msg.quote != null && !msg.retracted) {
                        QuoteBlock(msg.quoteAuthor, msg.quote, onOwn = msg.outgoing, onClick = onQuoteClick)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (msg.retracted) {
                        Text("Message deleted", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = textColor)
                    } else {
                        val mentionColor = if (msg.outgoing) colors.onOwnBubble else MaterialTheme.colorScheme.primary
                        val codeBg = textColor.copy(alpha = 0.12f)
                        val formatted = remember(msg.body, mentionColor, codeBg) {
                            if (RichText.hasMarkup(msg.body)) RichText.format(msg.body, mentionColor, codeBg) else null
                        }
                        if (formatted != null) {
                            Text(formatted, style = MaterialTheme.typography.bodyLarge, color = textColor)
                        } else {
                            Text(msg.body, style = MaterialTheme.typography.bodyLarge, color = textColor)
                        }
                    }
                }
            }
            if (msg.reactions.isNotEmpty() && !msg.retracted) {
                ReactionRow(msg.reactions, onReact, Modifier.padding(top = 2.dp))
            }
            val showMeta = !msg.groupedWithNewer || (msg.outgoing && !isRoom && msg.status != DeliveryStatus.READ)
            if (showMeta || msg.status == DeliveryStatus.FAILED) {
                MetaRow(msg, isRoom, onRetry)
            }
        }
    }
}

@Composable
private fun QuoteBlock(author: String?, quote: String, onOwn: Boolean, onClick: () -> Unit) {
    val colors = MurmurTheme.colors
    val ink = if (onOwn) colors.onOwnBubble else colors.onOtherBubble
    val bar = if (onOwn) colors.onOwnBubble.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(ink.copy(alpha = 0.08f))
            .clickable(onClickLabel = "Show the original message", onClick = onClick)
            .padding(end = 8.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar))
        Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            if (author != null) {
                Text(author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ink)
            }
            Text(quote, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Reaction chips under a bubble: tap to add/remove yours. */
@Composable
fun ReactionRow(reactions: List<ReactionUi>, onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in reactions) {
            val scale = remember { Animatable(0.4f) }
            LaunchedEffect(r.count) { scale.animateTo(1f, Motion.pop()) }
            Surface(
                shape = CircleShape,
                color = if (r.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (r.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                border = if (r.mine) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .clip(CircleShape)
                    .clickable(onClickLabel = if (r.mine) "Remove your ${r.emoji}" else "React ${r.emoji}") { onReact(r.emoji) }
                    .semantics { contentDescription = "${r.emoji} ${r.count}${if (r.mine) ", including you" else ""}" },
            ) {
                Text(
                    if (r.count > 1) "${r.emoji} ${r.count}" else r.emoji,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MetaRow(msg: MessageUi, isRoom: Boolean, onRetry: () -> Unit) {
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
    val note = msg.statusNote
    val noteColor = when {
        note == null -> meta
        note.waiting -> MurmurTheme.colors.hop
        msg.status == DeliveryStatus.READ -> MaterialTheme.colorScheme.primary
        else -> meta
    }
    Row(
        Modifier.padding(top = 3.dp, start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!msg.outgoing && msg.hops > 1 && !msg.showHeader) HopBadge(msg.hops)
        if (msg.expiresAt > 0) Icon(MurmurIcons.Timer, contentDescription = "Disappears", tint = meta, modifier = Modifier.size(12.dp))
        if (msg.outgoing && !isRoom && msg.status != null && !msg.retracted) DeliveryTicks(msg.status, tint = if (note != null) noteColor else null)
        AnimatedContent(
            targetState = if (note == null || note.waiting) note?.text ?: Format.clock(msg.time) else "${Format.clock(msg.time)} · ${note.text}",
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "meta",
        ) { text ->
            Text(text, style = MurmurType.Mono, fontSize = 10.5.sp, color = noteColor)
        }
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
fun DeliveryTicks(status: DeliveryStatus, tint: Color? = null) {
    val meta = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    val reduce = MurmurTheme.reduceMotion
    val tintOverride = tint
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
        val (icon, color) = when (s) {
            DeliveryStatus.PENDING, DeliveryStatus.SENDING -> MurmurIcons.Clock to meta
            DeliveryStatus.SENT -> MurmurIcons.Tick to meta
            DeliveryStatus.DELIVERED -> MurmurIcons.DoubleTick to meta
            DeliveryStatus.READ -> MurmurIcons.DoubleTick to MaterialTheme.colorScheme.primary
            DeliveryStatus.FAILED -> MurmurIcons.Clock to MaterialTheme.colorScheme.error
        }
        Icon(icon, contentDescription = statusLabel(s).replaceFirstChar { it.uppercase() }, tint = tintOverride ?: color, modifier = Modifier.size(16.dp))
    }
}

/** "Luna waved at you 👋" and similar events. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SystemLine(msg: MessageUi, animateIn: Boolean, onLongPress: () -> Unit) {
    val appear = rememberAppear(animateIn)
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    Box(Modifier.fillMaxWidth().appear(appear, rise).padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.clip(CircleShape).combinedClickable(onLongClickLabel = "Delete", onClick = {}, onLongClick = onLongPress),
        ) {
            Text(
                "${msg.body} · ${Format.clock(msg.time)}",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** An emergency alert inside #nearby. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SosCard(msg: MessageUi, animateIn: Boolean, onLongPress: () -> Unit) {
    val appear = rememberAppear(animateIn)
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .appear(appear, rise)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onLongClickLabel = "Message actions", onClick = {}, onLongClick = onLongPress)
            .semantics { contentDescription = "Emergency alert from ${msg.senderName}: ${msg.body.ifEmpty { "needs help" }}" },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🆘  SOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(if (msg.outgoing) "You sent an alert" else msg.senderName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!msg.outgoing && msg.hops > 1) HopBadge(msg.hops)
            }
            Text(msg.body.ifEmpty { "Needs help nearby" }, style = MaterialTheme.typography.bodyLarge)
            Text(Format.clock(msg.time), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A channel invitation in a DM: Join (or Open, once you're in with the same password). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InviteCard(msg: MessageUi, maxWidth: androidx.compose.ui.unit.Dp, animateIn: Boolean, onJoin: () -> Unit, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val invite = msg.invite ?: return
    val appear = rememberAppear(animateIn)
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    val who = if (msg.outgoing) "You sent an invitation" else "${msg.senderName} invited you"
    Box(
        Modifier.fillMaxWidth().appear(appear, rise).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = if (msg.outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(onLongClickLabel = "Message actions", onClick = {}, onLongClick = onLongPress)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$who to #${invite.channel}${if (invite.locked) ", password protected" else ""}"
                },
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoomIcon(ChatKind.CHANNEL, invite.locked, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("#${invite.channel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (invite.locked) "$who · password included" else who,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.clock(msg.time), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    if (invite.joined) {
                        FilledTonalButton(onClick = onOpen) { Text("Open") }
                    } else {
                        Button(onClick = onJoin) { Text("Join #${invite.channel}", maxLines = 1) }
                    }
                }
            }
        }
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
 * when there's something to send. Above it: the message being replied to, and @-suggestions in rooms.
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    replyingTo: MessageUi? = null,
    onCancelReply: () -> Unit = {},
    mentionNames: List<String> = emptyList(),
    /** DMs: a 👋 button left of the field. */
    onWave: (() -> Unit)? = null,
) {
    val bytes = remember(text) { Murmur.utf8Size(text) }
    val tooLong = bytes > Murmur.MAX_TEXT_BYTES
    val canSend = enabled && text.isNotBlank() && !tooLong
    val shown by animateFloatAsState(if (canSend) 1f else 0f, Motion.popOrSnap(MurmurTheme.reduceMotion), label = "send")
    val haptics = LocalHapticFeedback.current
    val partial = remember(text) { Mentions.partial(text) }
    val suggestions = remember(partial, mentionNames) {
        if (partial == null) emptyList() else mentionNames.filter { it.startsWith(partial, ignoreCase = true) && !it.equals(partial, ignoreCase = true) }.take(8)
    }
    val edge = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.drawBehind { drawLine(edge, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            AnimatedVisibility(replyingTo != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val r = replyingTo
                if (r != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MurmurIcons.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (r.outgoing) "Replying to yourself" else "Replying to ${r.senderName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(r.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onCancelReply) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply") }
                    }
                }
            }
            AnimatedVisibility(suggestions.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (name in suggestions) {
                        SuggestionChip(onClick = { onTextChange(Mentions.complete(text, name)) }, label = { Text("@$name") })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                if (onWave != null) {
                    Box(
                        Modifier
                            .size(Dimens.MinTouch)
                            .clip(CircleShape)
                            .clickable(enabled = enabled, role = Role.Button, onClick = onWave)
                            .semantics { contentDescription = "Wave" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) { Text("👋", fontSize = 20.sp) }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(23.dp),
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
}

private const val COUNTER_FROM = 800
