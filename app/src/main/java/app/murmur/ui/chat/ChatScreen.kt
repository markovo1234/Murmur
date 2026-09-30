package app.murmur.ui.chat

import android.Manifest
import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.core.mesh.DeliveryStatus
import app.murmur.core.protocol.PeerId
import app.murmur.core.text.Disappearing
import app.murmur.core.text.Replies
import app.murmur.data.ChatRepository
import app.murmur.data.PeerRepository
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.HoldToConfirmButton
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.components.sharedAvatar
import app.murmur.ui.container
import app.murmur.ui.containerViewModel
import app.murmur.ui.peer.PeerSheet
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything the chat screen can do, bundled so the screen stays a stateless function. */
class ChatActions(
    val onDraftChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenPeer: (String) -> Unit = {},
    val onToggleBlock: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onBlockSender: (String) -> Unit = {},
    val onReply: (MessageUi?) -> Unit = {},
    val onReact: (String, String) -> Unit = { _, _ -> },
    val onRetract: (String) -> Unit = {},
    val onWave: () -> Unit = {},
    val onSetTimer: (Long) -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onLeaveChannel: () -> Unit = {},
    val onSearch: (String?) -> Unit = {},
    val onSos: (String) -> Unit = {},
    val onCall: () -> Unit = {},
)

@Composable
fun ChatRoute(conversationId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val vm = containerViewModel(key = "chat-$conversationId") { ChatViewModel(it, conversationId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var replyingToId by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var search by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var sheetPeer by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Behind the lock screen the chat isn't really visible: keep notifying and don't mark anything read.
    val lockShowing by LocalContext.current.container.appLock.showing.collectAsStateWithLifecycle(initialValue = false)
    LifecycleResumeEffect(conversationId, lockShowing) {
        vm.setVisible(!lockShowing)
        onPauseOrDispose { vm.setVisible(false) }
    }
    // Restore the saved draft once.
    LaunchedEffect(state.loaded) { if (state.loaded && draft == null) draft = state.savedDraft }
    val newestKey = state.items.firstOrNull()?.key
    LaunchedEffect(newestKey, lockShowing) { if (newestKey != null && !lockShowing) vm.markRead() }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.waves.collect { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } }
    LaunchedEffect(search) { vm.setSearch(search) }
    val calls = LocalContext.current.container.calls
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.call() else scope.launch { snackbar.showSnackbar("Murmur needs the microphone for calls.") }
    }

    val replyingTo = replyingToId?.let { id -> state.items.firstNotNullOfOrNull { (it as? ChatItem.Bubble)?.message?.takeIf { m -> m.id == id } } }

    ChatScreen(
        state = state,
        draft = draft.orEmpty(),
        replyingTo = replyingTo,
        search = search,
        snackbar = snackbar,
        actions = ChatActions(
            onDraftChange = {
                draft = it
                vm.onDraftChanged(it)
            },
            onSend = {
                vm.send(draft.orEmpty(), replyingTo)
                draft = ""
                replyingToId = null
                vm.onDraftChanged("")
            },
            onBack = onBack,
            onOpenPeer = { id -> sheetPeer = id },
            onToggleBlock = vm::toggleBlock,
            onClear = vm::clear,
            onRetry = vm::retry,
            onDelete = vm::delete,
            onBlockSender = vm::blockSender,
            onReply = { replyingToId = it?.id },
            onReact = vm::react,
            onRetract = vm::retract,
            onWave = vm::wave,
            onSetTimer = vm::setTimer,
            onToggleMute = vm::toggleMute,
            onLeaveChannel = { vm.leaveChannel(onBack) },
            onSearch = { search = it },
            onSos = vm::sendSos,
            onCall = { if (calls.hasMicPermission()) vm.call() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        ),
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
    actions: ChatActions,
    replyingTo: MessageUi? = null,
    search: String? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var timerDialog by remember { mutableStateOf(false) }
    var sosDialog by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<MessageUi?>(null) }
    var infoFor by remember { mutableStateOf<MessageUi?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (search != null) {
                SearchBar(search, onChange = { actions.onSearch(it) }, onClose = { actions.onSearch(null) })
            } else {
                ChatTopBar(
                    state = state,
                    menuOpen = menuOpen,
                    onMenu = { menuOpen = it },
                    actions = actions,
                    onClearRequest = { confirmClear = true },
                    onLeaveRequest = { confirmLeave = true },
                    onTimerRequest = { timerDialog = true },
                    onSosRequest = { sosDialog = true },
                )
            }
        },
        bottomBar = {
            if (search == null) {
                Column {
                    AnimatedVisibility(state.blocked) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("You blocked ${state.title}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = actions.onToggleBlock) { Text("Unblock") }
                            }
                        }
                    }
                    Composer(
                        text = draft,
                        onTextChange = actions.onDraftChange,
                        onSend = {
                            actions.onSend()
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        enabled = !state.blocked,
                        placeholder = when (state.kind) {
                            ChatKind.NEARBY -> "Message everyone nearby"
                            ChatKind.CHANNEL -> "Message ${state.title}"
                            ChatKind.DIRECT -> "Message"
                        },
                        replyingTo = replyingTo,
                        onCancelReply = { actions.onReply(null) },
                        mentionNames = state.mentionNames,
                    )
                }
            }
        },
    ) { padding ->
        val results = state.searchResults
        if (search != null) {
            SearchResults(results, search, padding) { id ->
                actions.onSearch(null)
                jumpTo = id
            }
        } else {
            MessageList(
                state = state,
                listState = listState,
                padding = padding,
                jumpTo = jumpTo,
                onJumped = { jumpTo = null },
                onLongPress = { actionsFor = it },
                actions = actions,
                onQuoteClick = { msg ->
                    val key = Replies.snippetKey(msg.quote ?: return@MessageList).take(60)
                    val original = if (key.isEmpty()) {
                        null
                    } else {
                        state.items.asSequence()
                            .mapNotNull { (it as? ChatItem.Bubble)?.message }
                            .firstOrNull { it.id != msg.id && it.isText && !it.retracted && it.body.replace('\n', ' ').trim().startsWith(key) }
                    }
                    if (original != null) jumpTo = original.id else scope.launch { snackbar.showSnackbar("The original message isn't here anymore") }
                },
            )
        }
    }

    actionsFor?.let { msg ->
        MessageActionsSheet(
            msg = msg,
            isRoom = state.isRoom,
            onDismiss = { actionsFor = null },
            onReact = { emoji ->
                actions.onReact(msg.id, emoji)
                actionsFor = null
            },
            onReply = {
                actions.onReply(msg)
                actionsFor = null
            },
            onInfo = {
                infoFor = msg
                actionsFor = null
            },
            onRetract = {
                actions.onRetract(msg.id)
                actionsFor = null
            },
            onDelete = {
                actions.onDelete(msg.id)
                actionsFor = null
            },
            onBlockSender = {
                actions.onBlockSender(msg.senderId)
                actionsFor = null
            },
        )
    }
    infoFor?.let { msg -> MessageInfoDialog(msg, isRoom = state.isRoom, onDismiss = { infoFor = null }) }
    if (timerDialog) {
        TimerDialog(current = state.disappearSeconds, onPick = {
            actions.onSetTimer(it)
            timerDialog = false
        }, onDismiss = { timerDialog = false })
    }
    if (sosDialog) {
        SosDialog(onSend = {
            actions.onSos(it)
            sosDialog = false
        }, onDismiss = { sosDialog = false })
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear chat?",
            text = "All messages in ${state.title} will be deleted from this phone.",
            confirm = "Clear",
            onConfirm = {
                actions.onClear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave ${state.title}?",
            text = "You'll stop receiving its messages and its history is deleted from this phone.",
            confirm = "Leave",
            onConfirm = {
                confirmLeave = false
                actions.onLeaveChannel()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    state: ChatUiState,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    actions: ChatActions,
    onClearRequest: () -> Unit,
    onLeaveRequest: () -> Unit,
    onTimerRequest: () -> Unit,
    onSosRequest: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        title = {
            Row(
                Modifier
                    .clip(CircleShape)
                    .clickable(enabled = state.kind == ChatKind.DIRECT, onClickLabel = "Open profile") { actions.onOpenPeer(state.conversationId) }
                    .heightIn(min = Dimens.MinTouch)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state.kind) {
                    ChatKind.DIRECT -> EmojiAvatar(
                        emoji = state.peer?.emoji ?: PeerRepository.DEFAULT_EMOJI,
                        colorIndex = state.peer?.colorIndex ?: 0,
                        size = 40.dp,
                        modifier = Modifier.sharedAvatar(state.conversationId),
                        online = state.peer?.isOnline == true,
                        verified = state.peer?.verified == true,
                    )
                    else -> RoomIcon(state.kind, state.channelLocked, 40.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.muted) {
                            Spacer(Modifier.width(4.dp))
                            Icon(MurmurIcons.Muted, contentDescription = "Muted", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    AnimatedContent(state.subtitle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        },
        actions = {
            if (state.kind == ChatKind.DIRECT) {
                IconButton(onClick = actions.onCall, enabled = !state.blocked) { Icon(Icons.Filled.Call, contentDescription = "Voice call") }
            }
            IconButton(onClick = { actions.onSearch("") }) { Icon(Icons.Filled.Search, contentDescription = "Search in chat") }
            Box {
                IconButton(onClick = { onMenu(true) }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                    val entries = buildList<Pair<String, () -> Unit>> {
                        when (state.kind) {
                            ChatKind.DIRECT -> {
                                add("View profile" to { actions.onOpenPeer(state.conversationId) })
                                add("Wave 👋" to actions.onWave)
                                val timer = if (state.disappearSeconds > 0) "Disappearing: ${Disappearing.label(state.disappearSeconds)}" else "Disappearing messages"
                                add(timer to onTimerRequest)
                                add((if (state.muted) "Unmute" else "Mute") to actions.onToggleMute)
                                add((if (state.blocked) "Unblock" else "Block") to actions.onToggleBlock)
                            }
                            ChatKind.NEARBY -> {
                                add("🆘 Send SOS alert" to onSosRequest)
                                add((if (state.muted) "Unmute" else "Mute") to actions.onToggleMute)
                            }
                            ChatKind.CHANNEL -> {
                                add((if (state.muted) "Unmute" else "Mute") to actions.onToggleMute)
                                add("Leave channel" to onLeaveRequest)
                            }
                        }
                        add("Clear chat" to onClearRequest)
                    }
                    for ((label, action) in entries) {
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            onMenu(false)
                            action()
                        })
                    }
                }
            }
        },
    )
}

/** Round icon for #nearby and channels. */
@Composable
fun RoomIcon(kind: ChatKind, locked: Boolean, size: androidx.compose.ui.unit.Dp) {
    Surface(
        Modifier.size(size),
        shape = CircleShape,
        color = if (kind == ChatKind.NEARBY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        contentColor = if (kind == ChatKind.NEARBY) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                kind == ChatKind.NEARBY -> Icon(MurmurIcons.Hub, contentDescription = null)
                locked -> Icon(Icons.Filled.Lock, contentDescription = null)
                else -> Text("#", fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onChange,
                placeholder = { Text("Search messages") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
    )
}

@Composable
private fun SearchResults(results: List<SearchHit>?, query: String, padding: PaddingValues, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
        when {
            query.isBlank() -> item { Hint("Type to search this chat") }
            results == null -> Unit
            results.isEmpty() -> item { Hint("No messages match “$query”") }
            else -> items(results, key = { it.id }) { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Show in chat") { onOpen(hit.id) }
                        .padding(horizontal = Dimens.ScreenPadding, vertical = 10.dp),
                ) {
                    Row {
                        Text(hit.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(Format.clock(hit.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    Text(hit.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(Modifier.padding(horizontal = Dimens.ScreenPadding))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
private fun MessageList(
    state: ChatUiState,
    listState: LazyListState,
    padding: PaddingValues,
    jumpTo: String?,
    onJumped: () -> Unit,
    onLongPress: (MessageUi) -> Unit,
    actions: ChatActions,
    onQuoteClick: (MessageUi) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // History present at first load appears instantly; only later messages animate in.
    var initialKeys by remember { mutableStateOf<Set<String>?>(null) }
    val animated = remember { HashSet<String>() }
    LaunchedEffect(state.loaded) {
        if (state.loaded && initialKeys == null) initialKeys = state.items.map { it.key }.toSet()
    }
    var highlighted by remember { mutableStateOf<String?>(null) }

    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    var unseen by remember { mutableIntStateOf(0) }
    val newest = state.items.firstOrNull() as? ChatItem.Bubble
    LaunchedEffect(newest?.key) {
        if (newest == null || initialKeys == null) return@LaunchedEffect
        if (atBottom || newest.message.outgoing) listState.animateScrollToItem(0) else unseen++
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }
    LaunchedEffect(state.typing) { if (state.typing && atBottom) listState.animateScrollToItem(0) }
    LaunchedEffect(jumpTo, state.items) {
        val id = jumpTo ?: return@LaunchedEffect
        val index = state.items.indexOfFirst { it.key == id }
        if (index < 0) return@LaunchedEffect
        val offset = if (state.typing) 1 else 0
        listState.animateScrollToItem(index + offset)
        onJumped()
        highlighted = id
        delay(1_400)
        highlighted = null
    }

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
            items(state.items, key = { it.key }, contentType = {
                when (it) {
                    is ChatItem.Day -> 1
                    is ChatItem.Bubble -> if (it.message.isText) 0 else 2
                }
            }) { item ->
                when (item) {
                    is ChatItem.Day -> DayChip(item.label)
                    is ChatItem.Bubble -> {
                        val msg = item.message
                        val seen = initialKeys
                        val animateIn = remember(item.key) { seen != null && item.key !in seen && animated.add(item.key) }
                        when {
                            msg.isSystem -> SystemLine(msg, animateIn, onLongPress = { onLongPress(msg) })
                            msg.isSos -> SosCard(msg, animateIn, onLongPress = { onLongPress(msg) })
                            else -> MessageBubble(
                                msg = msg,
                                isRoom = state.isRoom,
                                maxBubbleWidth = maxBubble,
                                animateIn = animateIn,
                                highlighted = highlighted == msg.id,
                                onLongPress = { onLongPress(msg) },
                                onRetry = { actions.onRetry(msg.id) },
                                onSenderClick = { actions.onOpenPeer(msg.senderId) },
                                onReply = { actions.onReply(msg) },
                                onReact = { actions.onReact(msg.id, it) },
                                onQuoteClick = { onQuoteClick(msg) },
                            )
                        }
                    }
                }
            }
            if (state.loaded && state.items.isEmpty()) {
                item(key = "empty") {
                    Hint(
                        when (state.kind) {
                            ChatKind.NEARBY -> "Say hi to everyone in range 👋"
                            ChatKind.CHANNEL -> "Nothing here yet. Share the channel name with friends nearby."
                            ChatKind.DIRECT -> "No messages yet. Say hi 👋"
                        },
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
    isRoom: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
    onRetract: () -> Unit,
    onDelete: () -> Unit,
    onBlockSender: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val canInteract = msg.isText && !msg.retracted
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            if (canInteract) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (emoji in ChatRepository.REACTIONS) {
                        val mine = msg.reactions.any { it.emoji == emoji && it.mine }
                        Surface(
                            shape = CircleShape,
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier
                                .size(Dimens.MinTouch)
                                .clip(CircleShape)
                                .clickable(onClickLabel = if (mine) "Remove $emoji" else "React $emoji") { onReact(emoji) },
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SheetItem("Reply", MurmurIcons.Reply, onClick = onReply)
                SheetItem("Copy", MurmurIcons.Copy) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", msg.body))) }
                    onDismiss()
                }
                SheetItem("Info", Icons.Filled.Info, onClick = onInfo)
                if (msg.outgoing) SheetItem("Delete for everyone", Icons.Filled.Delete, danger = true, onClick = onRetract)
            }
            SheetItem("Delete for me", Icons.Filled.Delete, onClick = onDelete)
            if (isRoom && !msg.outgoing && !msg.isSystem) {
                SheetItem("Block ${msg.senderName}", MurmurIcons.Block, danger = true, onClick = onBlockSender)
            }
        }
    }
}

@Composable
private fun SheetItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun MessageInfoDialog(msg: MessageUi, isRoom: Boolean, onDismiss: () -> Unit) {
    fun full(t: Long) = if (t <= 0) "–" else java.text.SimpleDateFormat("EEE d MMM, HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Message info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("From", msg.senderName)
                InfoRow(if (msg.outgoing) "Sent" else "Written", full(msg.sentAt))
                if (!msg.outgoing) InfoRow("Received", full(msg.time))
                if (!msg.outgoing) InfoRow("Route", if (msg.hops <= 1) "direct" else "relayed, ${msg.hops} hops")
                if (msg.outgoing && !isRoom) {
                    InfoRow("Status", msg.status?.let(::statusLabel) ?: "–")
                    InfoRow("Delivered", full(msg.deliveredAt))
                    InfoRow("Read", full(msg.readAt))
                }
                if (msg.expiresAt > 0) InfoRow("Disappears", full(msg.expiresAt))
                if (msg.reactions.isNotEmpty()) InfoRow("Reactions", msg.reactions.joinToString("  ") { "${it.emoji} ${it.count}" })
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TimerDialog(current: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Disappearing messages") },
        text = {
            Column {
                Text(
                    "New messages in this chat are deleted from both phones after:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (option in Disappearing.OPTIONS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.MinTouch)
                            .selectable(selected = option == current, role = Role.RadioButton) { onPick(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(Disappearing.label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}

@Composable
private fun SosDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var confirmA11y by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("🆘 Send an SOS alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Everyone in range (and further through the mesh) gets a loud alert. Only use it in a real emergency.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ChatRepository.SOS_MAX_CHARS) },
                    label = { Text("What's happening and where? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                HoldToConfirmButton(
                    label = "Hold to send SOS",
                    onConfirmed = { onSend(text) },
                    onAccessibilityClick = { confirmA11y = true },
                )
            }
        },
    )
    if (confirmA11y) {
        ConfirmDialog("Send SOS?", "Everyone nearby will get an emergency alert.", "Send", onConfirm = {
            confirmA11y = false
            onSend(text)
        }, onDismiss = { confirmA11y = false })
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    quote: String? = null,
    reactions: List<ReactionUi> = emptyList(),
    kind: Int = 0,
    retracted: Boolean = false,
    mention: Boolean = false,
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
        quoteAuthor = quote?.let { "Luna" },
        quote = quote,
        reactions = reactions,
        kind = kind,
        retracted = retracted,
        mentionsMe = mention,
    ),
)

private val previewState = ChatUiState(
    conversationId = PreviewData.luna.id.toHex(),
    peer = PreviewData.luna,
    title = "Luna",
    subtitle = "nearby · strong signal · ⏱ 1 hour",
    items = listOf(
        previewMessage("6", "Luna waved at you 👋", false, 0, kind = 1),
        previewMessage("5", "On my way! *5 min*", true, 1, DeliveryStatus.SENT, quote = "Want to meet by the fountain?"),
        previewMessage("4", "Great, see you there 🙌", true, 2, DeliveryStatus.READ, reactions = listOf(ReactionUi("❤️", 1, false))),
        previewMessage("3", "Want to meet by the fountain?", false, 4, older = true, reactions = listOf(ReactionUi("👍", 2, true))),
        previewMessage("2", "", false, 5, newer = true, retracted = true),
        previewMessage("1", "Hi Luna, are you here too?", true, 6, DeliveryStatus.FAILED),
        ChatItem.Day("Today", "day-1"),
    ),
    typing = true,
    loaded = true,
    disappearSeconds = 3600,
)

@Preview(name = "Chat · light", showBackground = true)
@Composable
private fun ChatLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    ChatScreen(previewState, "Hello", ChatActions(), replyingTo = (previewState.items[3] as ChatItem.Bubble).message)
}

@Preview(name = "Chat · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    ChatScreen(previewState, "", ChatActions())
}

@Preview(name = "#nearby with SOS · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NearbyDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    ChatScreen(
        ChatUiState(
            conversationId = "nearby",
            kind = ChatKind.NEARBY,
            title = "#nearby",
            subtitle = "4 people in range",
            items = listOf(
                previewMessage("c", "Need a medic at stage 2", false, 0, kind = 2, hops = 3),
                previewMessage("b", "Anyone else at the station? @Sam", false, 1, header = true, hops = 3, mention = true),
                previewMessage("a", "Testing, testing…", true, 3),
                ChatItem.Day("Today", "day-1"),
            ),
            loaded = true,
        ),
        "@Lu",
        ChatActions(),
    )
}

@Preview(name = "Channel · light", showBackground = true)
@Composable
private fun ChannelLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    ChatScreen(
        ChatUiState(
            conversationId = "ch:hiking",
            kind = ChatKind.CHANNEL,
            title = "#hiking",
            subtitle = "🔒 password channel · 3 active",
            channelLocked = true,
            items = listOf(previewMessage("x", "Trail at 9? `north gate`", false, 2, header = true)),
            loaded = true,
            mentionNames = listOf("Luna", "Kai"),
        ),
        "",
        ChatActions(),
        search = null,
    )
}
