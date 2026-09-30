package app.murmur.ui.settings

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.BuildConfig
import app.murmur.core.Murmur
import app.murmur.core.mesh.Profile
import app.murmur.data.Settings
import app.murmur.data.ThemeMode
import app.murmur.ui.components.EmojiAvatar
import app.murmur.ui.components.HoldToConfirmButton
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.containerViewModel
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val profile: Profile? = null,
    val peerId: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    val relay: Boolean = true,
    val keepRunning: Boolean = true,
    val readReceipts: Boolean = true,
    val nearbyNotifications: Boolean = false,
    val blockedCount: Int = 0,
    val version: String = BuildConfig.VERSION_NAME,
    val protocolVersion: Int = Murmur.PROTOCOL_VERSION,
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(c.settingsState, c.peers.peers, c.identity) { s, peers, identity ->
        val settings = s ?: Settings()
        SettingsUiState(
            profile = settings.profile,
            peerId = identity?.peerId?.toHex().orEmpty(),
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            relay = settings.relay,
            keepRunning = settings.keepRunningInBackground,
            readReceipts = settings.readReceipts,
            nearbyNotifications = settings.nearbyNotifications,
            blockedCount = peers.count { it.blocked },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = launch { c.settings.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = launch { c.settings.setDynamicColor(on) }
    fun setRelay(on: Boolean) = launch { c.settings.setRelay(on) }
    fun setKeepRunning(on: Boolean) = launch { c.settings.setKeepRunning(on) }
    fun setReadReceipts(on: Boolean) = launch { c.settings.setReadReceipts(on) }
    fun setNearbyNotifications(on: Boolean) = launch { c.settings.setNearbyNotifications(on) }

    /**
     * Erases messages, settings and identity keys; the root then shows onboarding. Runs in the app scope
     * because navigating away clears this ViewModel mid-wipe.
     */
    fun panicWipe() {
        c.appScope.launch { c.panicWipe() }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun SettingsRoute(contentPadding: PaddingValues, onEditProfile: () -> Unit, onBlocked: () -> Unit, onDiagnostics: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        contentPadding = contentPadding,
        onEditProfile = onEditProfile,
        onThemeChange = vm::setTheme,
        onDynamicColorChange = vm::setDynamicColor,
        onRelayChange = vm::setRelay,
        onKeepRunningChange = vm::setKeepRunning,
        onReadReceiptsChange = vm::setReadReceipts,
        onNearbyNotificationsChange = vm::setNearbyNotifications,
        onBlocked = onBlocked,
        onDiagnostics = onDiagnostics,
        onPanicWipe = vm::panicWipe,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onEditProfile: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onRelayChange: (Boolean) -> Unit,
    onKeepRunningChange: (Boolean) -> Unit,
    onReadReceiptsChange: (Boolean) -> Unit,
    onNearbyNotificationsChange: (Boolean) -> Unit,
    onBlocked: () -> Unit,
    onDiagnostics: () -> Unit,
    onPanicWipe: () -> Unit,
) {
    var confirmWipe by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(
            start = Dimens.ScreenPadding,
            end = Dimens.ScreenPadding,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "profile") {
            Group("Profile") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Edit profile", onClick = onEditProfile)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val p = state.profile
                    EmojiAvatar(p?.emoji ?: "🙂", p?.colorIndex ?: 0, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p?.nickname ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Edit nickname and avatar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
        item(key = "appearance") {
            Group("Appearance") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                        options.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = state.themeMode == mode,
                                onClick = { onThemeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            ) { Text(label) }
                        }
                    }
                }
                SwitchRow(
                    title = "Dynamic color",
                    subtitle = if (state.dynamicColorAvailable) "Use colors from your wallpaper" else "Needs Android 12 or newer",
                    checked = state.dynamicColor && state.dynamicColorAvailable,
                    enabled = state.dynamicColorAvailable,
                    onChange = onDynamicColorChange,
                )
            }
        }
        item(key = "mesh") {
            Group("Mesh") {
                SwitchRow("Relay for others", "Pass on messages so the mesh reaches further", state.relay, onChange = onRelayChange)
                SwitchRow("Keep running in background", "Stay reachable when Murmur isn't open", state.keepRunning, onChange = onKeepRunningChange)
                SwitchRow("Read receipts", "Let people see when you've read their messages", state.readReceipts, onChange = onReadReceiptsChange)
                SwitchRow("#nearby notifications", "Notify for public messages (DMs always notify)", state.nearbyNotifications, onChange = onNearbyNotificationsChange)
            }
        }
        item(key = "privacy") {
            Group("Privacy") {
                NavRow(MurmurIcons.Block, "Blocked people", if (state.blockedCount == 0) "Nobody" else "${state.blockedCount}", onBlocked)
                Text(
                    "#nearby messages are deleted automatically after 24 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Panic wipe", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Erases all messages, settings and your identity keys, then starts over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HoldToConfirmButton(
                        label = "Hold to wipe everything",
                        onConfirmed = onPanicWipe,
                        onAccessibilityClick = { confirmWipe = true },
                    )
                }
            }
        }
        item(key = "diagnostics") {
            Group("Advanced") {
                NavRow(Icons.Filled.Build, "Diagnostics", "Radio state, links, counters, log, Demo mode", onDiagnostics)
            }
        }
        item(key = "about") {
            Group("About") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Murmur ${state.version}", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Protocol version ${state.protocolVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.peerId.isNotEmpty()) {
                        Text("Your peer id: ${state.peerId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("This app has no internet permission.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe everything?") },
            text = { Text("All messages, settings and your identity keys will be erased. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    onPanicWipe()
                }) { Text("Wipe", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "On" else "Off" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

private val previewState = SettingsUiState(profile = Profile("Sam", "🐧", 1), peerId = "3f9a0c12e4b7d655", blockedCount = 1)

@Preview(name = "Settings · light", showBackground = true, heightDp = 1400)
@Composable
private fun SettingsLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { SettingsScreen(previewState, PaddingValues(0.dp), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(name = "Settings · dark", showBackground = true, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { SettingsScreen(previewState.copy(themeMode = ThemeMode.DARK), PaddingValues(0.dp), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
}
