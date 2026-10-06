package app.murmur.ui.peer

import android.Manifest
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import app.murmur.call.StartCallResult
import app.murmur.ui.theme.MurmurType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.core.crypto.SafetyNumber
import app.murmur.core.mesh.PeerStatus
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.Format
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.containerViewModel
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PeerSheetUi(
    val peer: Peer? = null,
    val displayName: String = "",
    /** 24 digits as "1234 5678 …", null until both keys are known. */
    val safetyNumber: String? = null,
    val now: Long = 0L,
)

class PeerSheetViewModel(private val c: AppContainer, private val peerId: PeerId) : ViewModel() {
    val state: StateFlow<PeerSheetUi> = combine(c.peers.peers, c.identity) { peers, identity ->
        val peer = peers.firstOrNull { it.id == peerId }
        val key = peer?.signingKey
        PeerSheetUi(
            peer = peer,
            displayName = peer?.let { Format.displayName(it, peers) } ?: "",
            safetyNumber = if (key != null && identity != null) SafetyNumber.formatted(identity.signing.publicKey, key) else null,
            now = c.clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeerSheetUi())

    fun toggleVerified() {
        val peer = state.value.peer ?: return
        viewModelScope.launch { c.peers.setVerified(peer.id, !peer.verified) }
    }

    fun toggleBlocked() {
        val peer = state.value.peer ?: return
        viewModelScope.launch { c.peers.setBlocked(peer.id, !peer.blocked) }
    }

    fun toggleFavorite() {
        val peer = state.value.peer ?: return
        viewModelScope.launch { c.peers.setFavorite(peer.id, !peer.favorite) }
    }

    fun setAlias(alias: String?) {
        viewModelScope.launch { c.peers.setAlias(peerId, alias) }
    }

    /** Returns false when throttled. */
    suspend fun wave(): Boolean = c.chats.wave(peerId)

    fun hasMicPermission(): Boolean = c.calls.hasMicPermission()

    /** Starts a call; returns why it couldn't, or null when it's ringing. */
    suspend fun call(): String? = when (c.calls.startCall(peerId)) {
        StartCallResult.STARTED -> null
        StartCallResult.ALREADY_IN_CALL -> "You're already in a call."
        StartCallResult.MESH_OFF -> "The mesh is off, so calls can't go out."
        StartCallResult.OFFLINE -> "${state.value.displayName} is offline. Calls need them in range or through the mesh."
        StartCallResult.BLOCKED -> "Unblock them to call."
        StartCallResult.DEMO -> "Demo people can't take calls. Try it with a friend's phone."
    }
}

/** Everything the peer sheet can do. */
class PeerSheetActions(
    val onMessage: () -> Unit = {},
    val onToggleVerified: () -> Unit = {},
    val onToggleBlocked: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onSetAlias: (String?) -> Unit = {},
    val onWave: suspend () -> Boolean = { true },
    /** Returns why the call couldn't start, or null. */
    val onCall: suspend () -> String? = { null },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSheet(peerId: PeerId, onDismiss: () -> Unit, onMessage: () -> Unit) {
    val vm = containerViewModel(key = "peer-${peerId.toHex()}") { PeerSheetViewModel(it, peerId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var callProblem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { callProblem = vm.call() } else callProblem = "Murmur needs the microphone for calls."
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
    ) {
        PeerSheetContent(
            state,
            PeerSheetActions(
                onMessage = onMessage,
                onToggleVerified = vm::toggleVerified,
                onToggleBlocked = vm::toggleBlocked,
                onToggleFavorite = vm::toggleFavorite,
                onSetAlias = vm::setAlias,
                onWave = vm::wave,
                onCall = {
                    if (vm.hasMicPermission()) {
                        vm.call()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        null
                    }
                },
            ),
            notice = callProblem,
        )
    }
}

@Composable
fun PeerSheetContent(state: PeerSheetUi, actions: PeerSheetActions, notice: String? = null) {
    val peer = state.peer
    var renaming by remember { mutableStateOf(false) }
    var waved by remember { mutableStateOf<Boolean?>(null) }
    var callNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(waved) {
        if (waved != null) {
            delay(2_500)
            waved = null
        }
    }
    val shownNotice = callNotice ?: notice
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (peer == null) {
            Text("This peer is no longer known.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        // Who, and how a message gets to them.
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiAvatar(peer.emoji, peer.colorIndex, 64.dp, online = peer.isOnline, verified = peer.verified)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Format.route(peer, state.now),
                    style = MurmurType.Mono,
                    color = if (peer.status == PeerStatus.VIA_MESH) MurmurTheme.colors.hop else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val extra = listOfNotNull(
                    peer.alias?.let { "calls themselves “${peer.nickname}”" },
                    peer.firstSeen.takeIf { it > 0 }?.let {
                        "first met " + Format.day(state.now, it).lowercase().let { d -> if (d == "today" || d == "yesterday") d else "on $d" }
                    },
                )
                if (extra.isNotEmpty()) {
                    Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CircleAction(
                onClick = { renaming = true },
                label = "Set a nickname for ${peer.name}",
            ) { Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            CircleAction(
                onClick = actions.onToggleFavorite,
                label = if (peer.favorite) "Remove from favorites" else "Add to favorites",
            ) {
                Icon(
                    if (peer.favorite) Icons.Filled.Star else MurmurIcons.StarOutline,
                    contentDescription = null,
                    tint = if (peer.favorite) MurmurTheme.colors.hop else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Message (primary) · Wave · Call
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tall = Modifier.height(52.dp)
            Button(
                onClick = actions.onMessage,
                enabled = !peer.blocked,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = tall.weight(1.4f),
            ) {
                Icon(MurmurIcons.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Message", maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
            FilledTonalButton(
                onClick = { scope.launch { waved = actions.onWave() } },
                enabled = !peer.blocked && peer.isOnline && waved == null,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = tall.weight(1f),
            ) {
                Text(
                    when (waved) {
                        true -> "Waved!"
                        false -> "Wait a bit"
                        null -> "👋 Wave"
                    },
                    maxLines = 1,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FilledTonalButton(
                onClick = { scope.launch { callNotice = actions.onCall() } },
                enabled = !peer.blocked && peer.isOnline,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = tall.weight(1f),
            ) {
                Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Call", maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
        }
        if (shownNotice != null) {
            Text(shownNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        SafetyCard(state, peer, actions.onToggleVerified)

        TextButton(
            onClick = actions.onToggleBlocked,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = Dimens.MinTouch),
        ) {
            Icon(MurmurIcons.Block, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (peer.blocked) "Unblock ${peer.name}" else "Block ${peer.name}")
        }
    }

    if (renaming && peer != null) {
        AliasDialog(
            current = peer.alias ?: "",
            original = peer.nickname,
            onSave = {
                actions.onSetAlias(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

/** Safety number in a grid, with a verified state and one button to change it. */
@Composable
private fun SafetyCard(state: PeerSheetUi, peer: Peer, onToggleVerified: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Safety number", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (peer.verified) "VERIFIED" else "NOT VERIFIED",
                    style = MurmurType.Mono,
                    fontSize = 10.5.sp,
                    color = if (peer.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val number = state.safetyNumber
            if (number == null) {
                Text("Available once their key has been received.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val groups = number.split(" ")
            Column(
                Modifier.fillMaxWidth().semantics { contentDescription = "Safety number ${number.replace(" ", ", ")}" },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (row in groups.chunked(SAFETY_COLUMNS)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (g in row) {
                            Text(g, style = MurmurType.Mono, fontSize = 16.sp, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        }
                        repeat(SAFETY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Text(
                if (peer.verified) {
                    "You checked this with ${peer.name} in person."
                } else {
                    "Compare with ${peer.name}'s screen. Matching numbers mean nobody is impersonating them."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onToggleVerified,
                shape = RoundedCornerShape(14.dp),
                colors = if (peer.verified) {
                    ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouch)
                    .semantics { stateDescription = if (peer.verified) "Verified" else "Not verified" },
            ) {
                Text(if (peer.verified) "Verified ✓ · tap to undo" else "Numbers match: mark verified", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 44 dp round button in a 48 dp touch target. */
@Composable
private fun CircleAction(onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(Dimens.MinTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) { content() }
    }
}

private const val SAFETY_COLUMNS = 3

@Composable
private fun AliasDialog(current: String, original: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nickname for $original") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Only you see this name. It replaces “$original” everywhere in your app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "").take(ALIAS_MAX) },
                    singleLine = true,
                    placeholder = { Text(original) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { null }) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(onClick = { onSave(null) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private const val ALIAS_MAX = 40

private val previewState = PeerSheetUi(
    peer = PreviewData.luna,
    displayName = "Luna",
    safetyNumber = "0412 9981 3307 5520 6143 8876",
    now = PreviewData.NOW,
)

@Preview(name = "Peer sheet · light", showBackground = true)
@Composable
private fun PeerSheetLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { PeerSheetContent(previewState.copy(peer = PreviewData.luna.copy(favorite = true, alias = "Luna ☀️")), PeerSheetActions()) }
}

@Preview(name = "Peer sheet · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PeerSheetDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { PeerSheetContent(previewState.copy(peer = PreviewData.theo, displayName = "Theo"), PeerSheetActions()) }
}
