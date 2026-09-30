package app.murmur.ui.chat

import android.content.ClipData
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.PeerRepository
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.components.sharedAvatar
import app.murmur.ui.containerViewModel
import app.murmur.ui.peer.PeerSheet
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.launch

@Composable
fun ChatRoute(conversationId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val vm = containerViewModel(key = "chat-$conversationId") { ChatViewModel(it, conversationId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(conversationId) { mutableStateOf("") }
    var sheetPeer by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(conversationId) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    val newestKey = state.items.firstOrNull()?.key
    LaunchedEffect(newestKey) { if (newestKey != null) vm.markRead() }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }

    ChatScreen(
        state = state,
        draft = draft,
        onDraftChange = {
            draft = it
            if (it.isNotBlank()) vm.onTyping()
        },
        onSend = {
            vm.send(draft)
            draft = ""
        },
        onBack = onBack,
        onOpenPeer = { id -> sheetPeer = id },
        onToggleBlock = vm::toggleBlock,
        onClear = vm::clear,
        onRetry = vm::retry,
        onDelete = vm::delete,
        onBlockSender = vm::blockSender,
        snackbar = snackbar,
    )

    sheetPeer?.let { hex ->
        PeerId.fromHex(hex)?.let { id ->
            PeerSheet(
                peerId = id,
                onDismiss = { sheetPeer = null },
                onMessage = {
                    sheetPeer = null
                    if (hex != conversationId) onOpenChat(hex)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenPeer: (String) -> Unit,
    onToggleBlock: () -> Unit,
    onClear: () -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBlockSender: (String) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<MessageUi?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .clickable(enabled = !state.isNearby, onClickLabel = "Open profile") { onOpenPeer(state.conversationId) }
                            .heightIn(min = Dimens.MinTouch)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.isNearby) {
                            Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                Box(contentAlignment = Alignment.Center) { Icon(MurmurIcons.Hub, contentDescription = null) }
                            }
                        } else {
                            EmojiAvatar(
                                emoji = state.peer?.emoji ?: PeerRepository.DEFAULT_EMOJI,
                                colorIndex = state.peer?.colorIndex ?: 0,
                                size = 40.dp,
                                modifier = Modifier.sharedAvatar(state.conversationId),
                                online = state.peer?.isOnline == true,
                                verified = state.peer?.verified == true,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AnimatedContent(state.subtitle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!state.isNearby) {
                                DropdownMenuItem(text = { Text("View profile") }, onClick = {
                                    menuOpen = false
                                    onOpenPeer(state.conversationId)
                                })
                                DropdownMenuItem(text = { Text(if (state.blocked) "Unblock" else "Block") }, onClick = {
                                    menuOpen = false
                                    onToggleBlock()
                                })
                            }
                            DropdownMenuItem(text = { Text("Clear chat") }, onClick = {
                                menuOpen = false
                                confirmClear = true
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(state.blocked) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("You blocked ${state.title}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = onToggleBlock) { Text("Unblock") }
                        }
                    }
                }
                Composer(
                    text = draft,
                    onTextChange = onDraftChange,
                    onSend = {
                        onSend()
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    enabled = !state.blocked,
                    placeholder = if (state.isNearby) "Message everyone nearby" else "Message",
                )
            }
        },
    ) { padding ->
        MessageList(
            state = state,
            listState = listState,
            padding = padding,
            onLongPress = { actionsFor = it },
            onRetry = onRetry,
            onSenderClick = onOpenPeer,
        )
    }

    actionsFor?.let { msg ->
        MessageActionsSheet(
            msg = msg,
            canBlockSender = state.isNearby && !msg.outgoing,
            onDismiss = { actionsFor = null },
            onDelete = {
                onDelete(msg.id)
                actionsFor = null
            },
            onBlockSender = {
                onBlockSender(msg.senderId)
                actionsFor = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear chat?") },
            text = { Text("All messages in ${state.title} will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    padding: PaddingValues,
    onLongPress: (MessageUi) -> Unit,
    onRetry: (String) -> Unit,
    onSenderClick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // History present at first load appears instantly; only later messages animate in.
    var initialKeys by remember { mutableStateOf<Set<String>?>(null) }
    val animated = remember { HashSet<String>() }
    LaunchedEffect(state.loaded) {
        if (state.loaded && initialKeys == null) initialKeys = state.items.map { it.key }.toSet()
    }

    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    var unseen by remember { mutableIntStateOf(0) }
    val newest = state.items.firstOrNull() as? ChatItem.Bubble
    LaunchedEffect(newest?.key) {
        if (newest == null || initialKeys == null) return@LaunchedEffect
        if (atBottom || newest.message.outgoing) listState.animateScrollToItem(0) else unseen++
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }
    LaunchedEffect(state.typing) { if (state.typing && atBottom) listState.animateScrollToItem(0) }

    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val maxBubble = maxWidth * Dimens.BUBBLE_MAX_WIDTH_FRACTION
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (state.typing) {
                item(key = "typing") { TypingBubble(Modifier.animateItem()) }
            }
            items(state.items, key = { it.key }, contentType = { if (it is ChatItem.Bubble) 0 else 1 }) { item ->
                when (item) {
                    is ChatItem.Day -> DayChip(item.label)
                    is ChatItem.Bubble -> {
                        val seen = initialKeys
                        val animateIn = remember(item.key) { seen != null && item.key !in seen && animated.add(item.key) }
                        MessageBubble(
                            msg = item.message,
                            isNearby = state.isNearby,
                            maxBubbleWidth = maxBubble,
                            animateIn = animateIn,
                            onLongPress = { onLongPress(item.message) },
                            onRetry = { onRetry(item.message.id) },
                            onSenderClick = { onSenderClick(item.message.senderId) },
                        )
                    }
                }
            }
            if (state.loaded && state.items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (state.isNearby) "Say hi to everyone in range 👋" else "No messages yet. Say hi 👋",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !atBottom,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            BadgedBox(badge = { if (unseen > 0) Badge { Text("$unseen") } }) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.semantics {
                        contentDescription = if (unseen > 0) "Scroll to newest, $unseen new" else "Scroll to newest"
                    },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    msg: MessageUi,
    canBlockSender: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onBlockSender: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            ListItem(
                headlineContent = { Text("Copy") },
                leadingContent = { Icon(MurmurIcons.Copy, contentDescription = null) },
                modifier = Modifier.clickable {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", msg.body))) }
                    onDismiss()
                },
            )
            ListItem(
                headlineContent = { Text("Delete for me") },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onDelete),
            )
            if (canBlockSender) {
                ListItem(
                    headlineContent = { Text("Block ${msg.senderName}", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(MurmurIcons.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable(onClick = onBlockSender),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

private fun previewMessage(
    id: String,
    body: String,
    outgoing: Boolean,
    minutesAgo: Long,
    status: DeliveryStatus? = null,
    older: Boolean = false,
    newer: Boolean = false,
    header: Boolean = false,
    hops: Int = 1,
) = ChatItem.Bubble(
    MessageUi(
        id = id,
        body = body,
        outgoing = outgoing,
        senderId = if (outgoing) "me" else PreviewData.luna.id.toHex(),
        senderName = if (outgoing) "You" else "Luna",
        senderEmoji = if (outgoing) "🐧" else "🌙",
        senderColor = if (outgoing) 1 else 0,
        time = PreviewData.NOW - minutesAgo * 60_000,
        status = status,
        hops = hops,
        groupedWithOlder = older,
        groupedWithNewer = newer,
        showHeader = header,
    ),
)

private val previewState = ChatUiState(
    conversationId = PreviewData.luna.id.toHex(),
    peer = PreviewData.luna,
    title = "Luna",
    subtitle = "nearby · strong signal",
    items = listOf(
        previewMessage("5", "On my way!", true, 1, DeliveryStatus.SENT),
        previewMessage("4", "Great, see you there 🙌", true, 2, DeliveryStatus.READ, newer = false),
        previewMessage("3", "Want to meet by the fountain?", false, 4, older = true),
        previewMessage("2", "Hey! Got your message 👋", false, 5, newer = true),
        previewMessage("1", "Hi Luna, are you here too?", true, 6, DeliveryStatus.FAILED),
        ChatItem.Day("Today", "day-1"),
    ),
    typing = true,
    loaded = true,
)

@Preview(name = "Chat · light", showBackground = true)
@Composable
private fun ChatLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    ChatScreen(previewState, "Hello", {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Chat · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    ChatScreen(previewState, "", {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "#nearby · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NearbyDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    ChatScreen(
        ChatUiState(
            conversationId = "nearby",
            isNearby = true,
            title = "#nearby",
            subtitle = "4 people in range",
            items = listOf(
                previewMessage("b", "Anyone else at the station?", false, 1, header = true, hops = 3),
                previewMessage("a", "Testing, testing…", true, 3),
                ChatItem.Day("Today", "day-1"),
            ),
            loaded = true,
        ),
        "",
        {}, {}, {}, {}, {}, {}, {}, {}, {},
    )
}
