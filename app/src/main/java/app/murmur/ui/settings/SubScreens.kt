package app.murmur.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.core.protocol.PeerId
import app.murmur.data.Peer
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.PreviewData
import app.murmur.ui.containerViewModel
import app.murmur.ui.onboarding.ProfileDraft
import app.murmur.ui.onboarding.ProfileEditor
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------ profile edit

class ProfileEditViewModel(private val c: AppContainer) : ViewModel() {
    private val _draft = MutableStateFlow(ProfileDraft())
    val draft: StateFlow<ProfileDraft> = _draft.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = c.settingsState.first { it != null }?.profile
            _draft.value = ProfileDraft.from(profile)
        }
    }

    fun setNickname(v: String) = _draft.update { it.copy(nickname = v) }
    fun setEmoji(v: String) = _draft.update { it.copy(emoji = v) }
    fun setColor(v: Int) = _draft.update { it.copy(colorIndex = v) }

    /** Saving floods a fresh ANNOUNCE (the running mesh watches the profile). */
    fun save(onDone: () -> Unit) {
        val profile = _draft.value.toProfile()
        if (!profile.isValid) return
        viewModelScope.launch {
            c.settings.setProfile(profile)
            onDone()
        }
    }
}

@Composable
fun ProfileEditRoute(onBack: () -> Unit) {
    val vm = containerViewModel { ProfileEditViewModel(it) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    ProfileEditScreen(draft, vm::setNickname, vm::setEmoji, vm::setColor, onSave = { vm.save(onBack) }, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    draft: ProfileDraft,
    onNickname: (String) -> Unit,
    onEmoji: (String) -> Unit,
    onColor: (Int) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            Button(
                onClick = onSave,
                enabled = draft.isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(Dimens.ScreenPadding)
                    .heightIn(min = 52.dp),
            ) { Text("Save") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding)) {
            ProfileEditor(draft, onNickname, onEmoji, onColor)
        }
    }
}

// ------------------------------------------------------------------------ blocked list

class BlockedViewModel(private val c: AppContainer) : ViewModel() {
    val blocked: StateFlow<List<Peer>> = c.peers.peers.map { list -> list.filter { it.blocked }.sortedBy { it.nickname.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unblock(id: PeerId) {
        viewModelScope.launch { c.peers.setBlocked(id, false) }
    }
}

@Composable
fun BlockedRoute(onBack: () -> Unit) {
    val vm = containerViewModel { BlockedViewModel(it) }
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    BlockedScreen(blocked, vm::unblock, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(blocked: List<Peer>, onUnblock: (PeerId) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked people") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            if (blocked.isEmpty()) {
                item {
                    Text(
                        "Nobody is blocked. Blocked people's messages are still relayed for others, but you never see them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            items(blocked, key = { it.id.raw }) { peer ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp).animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmojiAvatar(peer.emoji, peer.colorIndex, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(peer.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("#${peer.id.shortTag}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { onUnblock(peer.id) }) { Text("Unblock") }
                }
            }
        }
    }
}

@Preview(name = "Profile edit · light", showBackground = true)
@Composable
private fun ProfileEditLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    ProfileEditScreen(ProfileDraft("Sam", "🐧", 1), {}, {}, {}, {}, {})
}

@Preview(name = "Profile edit · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProfileEditDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    ProfileEditScreen(ProfileDraft("Sam", "🐧", 1), {}, {}, {}, {}, {})
}

@Preview(name = "Blocked · light", showBackground = true)
@Composable
private fun BlockedLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    BlockedScreen(listOf(PreviewData.kai.copy(blocked = true)), {}, {})
}

@Preview(name = "Blocked · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BlockedDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    BlockedScreen(emptyList(), {}, {})
}
