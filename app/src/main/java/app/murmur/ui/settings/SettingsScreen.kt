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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Lock
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.murmur.data.AppLock
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
import androidx.compose.runtime.mutableIntStateOf
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
import app.murmur.ui.components.HoldToConfirmBar
import app.murmur.ui.components.PulsingDot
import app.murmur.ui.components.SectionLabel
import app.murmur.ui.theme.MurmurType
import app.murmur.core.mesh.PeerStatus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.container
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
    /** People heard directly right now. */
    val directLinks: Int = 0,
    /** People reachable only through other phones. */
    val viaMesh: Int = 0,
    val lockEnabled: Boolean = false,
    val lockTimeoutMillis: Long = 0,
    val hideNotificationContent: Boolean = false,
    val batterySaver: Boolean = false,
    val publicReach: Int = Settings.REACH_NORMAL,
    val favoriteAlerts: Boolean = true,
    val relayedTotal: Long = 0,
    /** Calls may wake the phone and ring over the lock screen (Android 14+ can switch that off). */
    val callsOnLockScreen: Boolean = true,
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
            directLinks = peers.count { it.status == PeerStatus.NEARBY && !it.blocked },
            viaMesh = peers.count { it.status == PeerStatus.VIA_MESH && !it.blocked },
            lockEnabled = settings.appLock.enabled,
            lockTimeoutMillis = settings.appLock.timeoutMillis,
            hideNotificationContent = settings.hideNotificationContent,
            batterySaver = settings.batterySaver,
            publicReach = settings.publicReach,
            favoriteAlerts = settings.favoriteAlerts,
            relayedTotal = settings.relayedTotal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = launch { c.settings.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = launch { c.settings.setDynamicColor(on) }
    fun setRelay(on: Boolean) = launch { c.settings.setRelay(on) }
    fun setKeepRunning(on: Boolean) = launch { c.settings.setKeepRunning(on) }
    fun setReadReceipts(on: Boolean) = launch { c.settings.setReadReceipts(on) }
    fun setNearbyNotifications(on: Boolean) = launch { c.settings.setNearbyNotifications(on) }
    fun setHideContent(on: Boolean) = launch { c.settings.setHideNotificationContent(on) }
    fun setBatterySaver(on: Boolean) = launch { c.settings.setBatterySaver(on) }
    fun setReach(ttl: Int) = launch { c.settings.setPublicReach(ttl) }
    fun setFavoriteAlerts(on: Boolean) = launch { c.settings.setFavoriteAlerts(on) }
    fun setLockTimeout(millis: Long) = launch { c.settings.setLockTimeout(millis) }
    fun setPin(pin: String) = launch { c.appLock.setPin(pin) }
    fun disableLock() = launch { c.appLock.disable() }
    suspend fun verifyPin(pin: String): Boolean = c.appLock.verify(pin)

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

/** Everything the settings screen can change. */
class SettingsActions(
    val onEditProfile: () -> Unit = {},
    val onThemeChange: (ThemeMode) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    val onRelayChange: (Boolean) -> Unit = {},
    val onKeepRunningChange: (Boolean) -> Unit = {},
    val onReadReceiptsChange: (Boolean) -> Unit = {},
    val onNearbyNotificationsChange: (Boolean) -> Unit = {},
    val onHideContentChange: (Boolean) -> Unit = {},
    val onAllowLockScreenCalls: () -> Unit = {},
    val onBatterySaverChange: (Boolean) -> Unit = {},
    val onReachChange: (Int) -> Unit = {},
    val onFavoriteAlertsChange: (Boolean) -> Unit = {},
    val onLockTimeoutChange: (Long) -> Unit = {},
    val onSetPin: (String) -> Unit = {},
    val onDisableLock: () -> Unit = {},
    val onVerifyPin: suspend (String) -> Boolean = { true },
    val onBlocked: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onPanicWipe: () -> Unit = {},
)

@Composable
fun SettingsRoute(contentPadding: PaddingValues, onEditProfile: () -> Unit, onBlocked: () -> Unit, onDiagnostics: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifier = context.container.notifier
    // A system setting: check again whenever you come back from it.
    var callsOnLockScreen by remember { mutableStateOf(notifier.canRingOverLockScreen()) }
    LifecycleResumeEffect(notifier) {
        callsOnLockScreen = notifier.canRingOverLockScreen()
        onPauseOrDispose {}
    }
    SettingsScreen(
        state = state.copy(callsOnLockScreen = callsOnLockScreen),
        contentPadding = contentPadding,
        actions = SettingsActions(
            onEditProfile = onEditProfile,
            onThemeChange = vm::setTheme,
            onDynamicColorChange = vm::setDynamicColor,
            onRelayChange = vm::setRelay,
            onKeepRunningChange = vm::setKeepRunning,
            onReadReceiptsChange = vm::setReadReceipts,
            onNearbyNotificationsChange = vm::setNearbyNotifications,
            onHideContentChange = vm::setHideContent,
            onAllowLockScreenCalls = {
                runCatching { context.startActivity(notifier.lockScreenCallSettings()) }
            },
            onBatterySaverChange = vm::setBatterySaver,
            onReachChange = vm::setReach,
            onFavoriteAlertsChange = vm::setFavoriteAlerts,
            onLockTimeoutChange = vm::setLockTimeout,
            onSetPin = vm::setPin,
            onDisableLock = vm::disableLock,
            onVerifyPin = vm::verifyPin,
            onBlocked = onBlocked,
            onDiagnostics = onDiagnostics,
            onPanicWipe = vm::panicWipe,
        ),
    )
}

@Composable
fun SettingsScreen(state: SettingsUiState, contentPadding: PaddingValues, actions: SettingsActions) {
    var confirmWipe by remember { mutableStateOf(false) }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(
            start = Dimens.ScreenPadding,
            end = Dimens.ScreenPadding,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text("You", style = MurmurType.ScreenTitle, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        }
        item(key = "profile") { ProfileCard(state, actions.onEditProfile) }
        item(key = "mesh-card") { MeshCard(state) }
        item(key = "appearance") {
            Group("Appearance") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                        options.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = state.themeMode == mode,
                                onClick = { actions.onThemeChange(mode) },
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
                    onChange = actions.onDynamicColorChange,
                )
            }
        }
        item(key = "mesh") {
            Group("Mesh") {
                SwitchRow("Relay for others", "Pass on messages so the mesh reaches further", state.relay, onChange = actions.onRelayChange)
                SwitchRow("Keep running in background", "Stay reachable when Murmur isn't open", state.keepRunning, onChange = actions.onKeepRunningChange)
                SwitchRow(
                    "Battery saver",
                    "Scan and advertise less often while Murmur is in the background. People take longer to appear.",
                    state.batterySaver,
                    onChange = actions.onBatterySaverChange,
                )
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Message reach", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "How many hops your #nearby and channel messages travel. Short keeps them closer to you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val options = listOf(Settings.REACH_NORMAL to "Normal · 7 hops", Settings.REACH_SHORT to "Short · 3 hops")
                        options.forEachIndexed { i, (ttl, label) ->
                            SegmentedButton(
                                selected = state.publicReach == ttl,
                                onClick = { actions.onReachChange(ttl) },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
        }
        item(key = "notifications") {
            Group("Notifications") {
                SwitchRow("#nearby messages", "DMs, joined channels and @mentions always notify unless you mute them", state.nearbyNotifications, onChange = actions.onNearbyNotificationsChange)
                SwitchRow("Favorites nearby", "Tell me when a favorite comes into range", state.favoriteAlerts, onChange = actions.onFavoriteAlertsChange)
                SwitchRow(
                    "Hide message text",
                    if (state.lockEnabled) "Always hidden while app lock is on" else "Notifications show only who wrote, not what",
                    checked = state.hideNotificationContent || state.lockEnabled,
                    enabled = !state.lockEnabled,
                    onChange = actions.onHideContentChange,
                )
                if (!state.callsOnLockScreen) {
                    NavRow(Icons.Filled.Call, "Calls on the lock screen", "Off: calls won't wake the screen. Tap to allow.", actions.onAllowLockScreenCalls)
                }
            }
        }
        item(key = "security") {
            Group("Security") {
                SwitchRow(
                    "App lock",
                    "Ask for a PIN to open Murmur. Also hides it from the recent-apps preview and screenshots.",
                    state.lockEnabled,
                    onChange = { on -> pinFlow = if (on) PinFlow.Create else PinFlow.Disable },
                )
                if (state.lockEnabled) {
                    NavRow(Icons.Filled.Lock, "Change PIN", "Enter your current PIN first") { pinFlow = PinFlow.Change }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Lock after leaving the app", style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val options = LOCK_TIMEOUTS
                            options.forEachIndexed { i, (millis, label) ->
                                SegmentedButton(
                                    selected = state.lockTimeoutMillis == millis,
                                    onClick = { actions.onLockTimeoutChange(millis) },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                    }
                }
                SwitchRow("Read receipts", "Let people see when you've read their messages", state.readReceipts, onChange = actions.onReadReceiptsChange)
            }
        }
        item(key = "privacy") {
            Group("Privacy") {
                NavRow(MurmurIcons.Block, "Blocked people", if (state.blockedCount == 0) "Nobody" else "${state.blockedCount}", actions.onBlocked)
                Text(
                    "#nearby messages are deleted automatically after 24 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        item(key = "wipe") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldToConfirmBar(
                    label = "Hold to wipe everything",
                    onConfirmed = actions.onPanicWipe,
                    onAccessibilityClick = { confirmWipe = true },
                )
                Text(
                    "Erases chats, people, settings and your identity keys, then starts over. Hold for 2 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        item(key = "diagnostics") {
            Group("Advanced") {
                NavRow(Icons.Filled.Build, "Diagnostics", "Radio state, links, counters, log, Demo mode", actions.onDiagnostics)
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

    pinFlow?.let { flow ->
        PinDialog(
            flow = flow,
            verify = actions.onVerifyPin,
            onDone = { pin ->
                when (flow) {
                    PinFlow.Create, PinFlow.Change -> actions.onSetPin(pin)
                    PinFlow.Disable -> actions.onDisableLock()
                }
                pinFlow = null
            },
            onDismiss = { pinFlow = null },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe everything?") },
            text = { Text("All messages, settings and your identity keys will be erased. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    actions.onPanicWipe()
                }) { Text("Wipe", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, Modifier.padding(start = 8.dp, top = 8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = CardShape,
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

private val CardShape = RoundedCornerShape(24.dp)

/** Avatar, nickname and your id, with an Edit pill. */
@Composable
private fun ProfileCard(state: SettingsUiState, onEdit: () -> Unit) {
    val p = state.profile
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = CardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClickLabel = "Edit profile", onClick = onEdit)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmojiAvatar(p?.emoji ?: "🙂", p?.colorIndex ?: 0, 64.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p?.nickname ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (state.peerId.isNotEmpty()) {
                    Text(
                        "id " + state.peerId.chunked(4).joinToString(" · "),
                        style = MurmurType.Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Text("Edit", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/** Mesh health at a glance: whether it keeps running, links, and how much this phone has relayed. */
@Composable
private fun MeshCard(state: SettingsUiState) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val relayed = when (state.relayedTotal) {
        0L -> "nothing relayed yet"
        1L -> "1 message relayed"
        else -> "${java.text.NumberFormat.getIntegerInstance().format(state.relayedTotal)} messages relayed"
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = ink,
        shape = CardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(if (state.keepRunning) MurmurTheme.colors.online else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.keepRunning) "Mesh running" else "Mesh runs only while Murmur is open",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                buildString {
                    append("${state.directLinks} direct link${if (state.directLinks == 1) "" else "s"}")
                    if (state.viaMesh > 0) append(" · ${state.viaMesh} via mesh")
                    append("\n")
                    append(if (state.relay) "relaying · $relayed" else "relaying off · $relayed")
                    append("\nno internet permission · Bluetooth LE only")
                },
                style = MurmurType.Mono,
                lineHeight = 18.sp,
            )
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

private val LOCK_TIMEOUTS = listOf(0L to "Now", 60_000L to "1 min", 300_000L to "5 min", 1_800_000L to "30 min")

/** What the PIN dialog is for. */
enum class PinFlow { Create, Change, Disable }

@Composable
private fun PinDialog(flow: PinFlow, verify: suspend (String) -> Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    // Steps: optional "current PIN", then "new PIN" + "confirm".
    var step by remember { mutableIntStateOf(if (flow == PinFlow.Create) 1 else 0) }
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = when (step) {
        0 -> "Enter your current PIN"
        1 -> "Choose a PIN"
        else -> "Enter it again"
    }

    fun submit() {
        when (step) {
            0 -> {
                busy = true
                scope.launch {
                    val ok = verify(pin)
                    busy = false
                    when {
                        !ok -> {
                            error = "Wrong PIN"
                            pin = ""
                        }
                        flow == PinFlow.Disable -> onDone(pin)
                        else -> {
                            step = 1
                            pin = ""
                            error = null
                        }
                    }
                }
            }
            1 -> {
                first = pin
                pin = ""
                step = 2
                error = null
            }
            else -> if (pin == first) {
                onDone(pin)
            } else {
                error = "PINs didn't match. Try again."
                pin = ""
                first = ""
                step = 1
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 1) {
                    Text(
                        "4 to 8 digits. If you forget it, the only way back in is to reinstall Murmur, which erases everything.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> if (v.length <= 8 && v.all { it in '0'..'9' }) pin = v },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (AppLock.isValidPin(pin) && !busy) submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = AppLock.isValidPin(pin) && !busy, onClick = ::submit) {
                Text(if (step == 2 || flow == PinFlow.Disable) (if (flow == PinFlow.Disable) "Turn off" else "Save") else "Next")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val previewState = SettingsUiState(
    profile = Profile("Sam", "🐧", 1),
    peerId = "3f9a0c12e4b7d655",
    blockedCount = 1,
    lockEnabled = true,
    lockTimeoutMillis = 60_000,
    relayedTotal = 1_284,
)

@Preview(name = "Settings · light", showBackground = true, heightDp = 2000)
@Composable
private fun SettingsLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    Surface { SettingsScreen(previewState, PaddingValues(0.dp), SettingsActions()) }
}

@Preview(name = "Settings · dark", showBackground = true, heightDp = 2000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    Surface { SettingsScreen(previewState.copy(themeMode = ThemeMode.DARK, lockEnabled = false, relayedTotal = 0), PaddingValues(0.dp), SettingsActions()) }
}
