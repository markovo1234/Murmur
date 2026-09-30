package app.murmur.ui.peer

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import app.murmur.ui.components.HopBadge
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.components.PreviewData
import app.murmur.ui.components.SignalBars
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
}

/** Everything the peer sheet can do. */
class PeerSheetActions(
    val onMessage: () -> Unit = {},
    val onToggleVerified: () -> Unit = {},
    val onToggleBlocked: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onSetAlias: (String?) -> Unit = {},
    val onWave: suspend () -> Boolean = { true },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSheet(peerId: PeerId, onDismiss: () -> Unit, onMessage: () -> Unit) {
    val vm = containerViewModel(key = "peer-${peerId.toHex()}") { PeerSheetViewModel(it, peerId) }
    val state by vm.state.collectAsStateWithLifecycle()
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
            ),
        )
    }
}

@Composable
fun PeerSheetContent(state: PeerSheetUi, actions: PeerSheetActions) {
    val peer = state.peer
    var renaming by remember { mutableStateOf(false) }
    var waved by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(waved) {
        if (waved != null) {
            delay(2_500)
            waved = null
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (peer == null) {
            Text("This peer is no longer known.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        EmojiAvatar(peer.emoji, peer.colorIndex, 96.dp, online = peer.isOnline, verified = peer.verified)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onToggleFavorite) {
                Icon(
                    if (peer.favorite) Icons.Filled.Star else MurmurIcons.StarOutline,
                    contentDescription = if (peer.favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (peer.favorite) MurmurTheme.colors.hop else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                state.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Set a nickname for ${peer.name}")
            }
        }
        if (peer.alias != null) {
            Text(
                "Calls themselves “${peer.nickname}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (peer.status) {
                PeerStatus.NEARBY -> SignalBars(peer.rssi)
                PeerStatus.VIA_MESH -> HopBadge(peer.hops)
                PeerStatus.OFFLINE -> Unit
            }
            val line = if (peer.status == PeerStatus.OFFLINE) "offline · last seen ${Format.lastSeen(state.now, peer.lastSeen)}" else Format.peerStatus(peer)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (peer.firstSeen > 0) {
            Text(
                "First met ${Format.day(state.now, peer.firstSeen).lowercase().let { if (it == "today" || it == "yesterday") it else "on $it" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Safety number", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val number = state.safetyNumber
                if (number != null) {
                    val groups = number.split(" ")
                    Column(
                        Modifier.fillMaxWidth().semantics { contentDescription = "Safety number ${number.replace(" ", ", ")}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        for (row in groups.chunked(3)) {
                            Text(
                                row.joinToString("   "),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 20.sp,
                                letterSpacing = 1.sp,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                    Text(
                        "Compare this with the number on ${peer.name}'s phone. If they match, nobody is impersonating them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouch),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MurmurIcons.Verified, contentDescription = null, tint = if (peer.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text("Mark as verified", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = peer.verified,
                            onCheckedChange = { actions.onToggleVerified() },
                            modifier = Modifier.semantics { contentDescription = "Mark as verified" },
                        )
                    }
                } else {
                    Text("Available once their key has been received.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.onToggleBlocked,
                modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouch),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(MurmurIcons.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (peer.blocked) "Unblock" else "Block", maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    scope.launch { waved = actions.onWave() }
                },
                enabled = !peer.blocked && peer.isOnline && waved == null,
                modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouch),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    when (waved) {
                        true -> "Waved!"
                        false -> "Wait a bit"
                        null -> "👋 Wave"
                    },
                    maxLines = 1,
                )
            }
            Button(
                onClick = actions.onMessage,
                modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouch),
                enabled = !peer.blocked,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(MurmurIcons.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Message", maxLines = 1)
            }
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
