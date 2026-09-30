package app.murmur.ui.peer

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSheet(peerId: PeerId, onDismiss: () -> Unit, onMessage: () -> Unit) {
    val vm = containerViewModel(key = "peer-${peerId.toHex()}") { PeerSheetViewModel(it, peerId) }
    val state by vm.state.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = Dimens.SheetRadius, topEnd = Dimens.SheetRadius),
    ) {
        PeerSheetContent(state, onMessage, vm::toggleVerified, vm::toggleBlocked)
    }
}

@Composable
fun PeerSheetContent(state: PeerSheetUi, onMessage: () -> Unit, onToggleVerified: () -> Unit, onToggleBlocked: () -> Unit) {
    val peer = state.peer
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
        Text(state.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (peer.status) {
                PeerStatus.NEARBY -> SignalBars(peer.rssi)
                PeerStatus.VIA_MESH -> HopBadge(peer.hops)
                PeerStatus.OFFLINE -> Unit
            }
            val line = if (peer.status == PeerStatus.OFFLINE) "offline · last seen ${Format.lastSeen(state.now, peer.lastSeen)}" else Format.peerStatus(peer)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "Compare this with the number on ${peer.nickname}'s phone. If they match, nobody is impersonating them.",
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
                            onCheckedChange = { onToggleVerified() },
                            modifier = Modifier.semantics { contentDescription = "Mark as verified" },
                        )
                    }
                } else {
                    Text("Available once their key has been received.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onToggleBlocked,
                modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouch),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(MurmurIcons.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (peer.blocked) "Unblock" else "Block")
            }
            Button(onClick = onMessage, modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouch), enabled = !peer.blocked) {
                Icon(MurmurIcons.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Message")
            }
        }
    }
}

private val previewState = PeerSheetUi(
    peer = PreviewData.luna,
    displayName = "Luna",
    safetyNumber = "0412 9981 3307 5520 6143 8876",
    now = PreviewData.NOW,
)

@Preview(name = "Peer sheet · light", showBackground = true)
@Composable
private fun PeerSheetLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { PeerSheetContent(previewState, {}, {}, {}) }
}

@Preview(name = "Peer sheet · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PeerSheetDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { PeerSheetContent(previewState.copy(peer = PreviewData.theo, displayName = "Theo"), {}, {}, {}) }
}
