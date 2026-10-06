package app.murmur.ui.onboarding

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.murmur.AppContainer
import app.murmur.data.ThemeMode
import app.murmur.ui.components.Motion
import app.murmur.ui.components.MurmurIcons
import app.murmur.ui.containerViewModel
import app.murmur.ui.radar.RadarField
import app.murmur.ui.theme.Dimens
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val _draft = MutableStateFlow(ProfileDraft(emoji = AVATAR_EMOJIS.random(), colorIndex = (0 until 10).random()))
    val draft: StateFlow<ProfileDraft> = _draft.asStateFlow()

    fun setNickname(v: String) = _draft.update { it.copy(nickname = v) }
    fun setEmoji(v: String) = _draft.update { it.copy(emoji = v) }
    fun setColor(v: Int) = _draft.update { it.copy(colorIndex = v) }

    /** Saves the profile; the root navigates to Radar and the mesh starts once settings reflect it. */
    fun finish() {
        val profile = _draft.value.toProfile()
        if (!profile.isValid) return
        viewModelScope.launch {
            c.settings.setProfile(profile)
            c.settings.setOnboardingDone(true)
        }
    }
}

@Composable
fun OnboardingRoute() {
    val vm = containerViewModel { OnboardingViewModel(it) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val permissions = rememberPermissionActions()
    OnboardingScreen(
        draft = draft,
        permissions = permissions.snapshot,
        onNickname = vm::setNickname,
        onEmoji = vm::setEmoji,
        onColor = vm::setColor,
        onRequestBluetooth = permissions.requestBluetooth,
        onRequestNotifications = permissions.requestNotifications,
        onEnableBluetooth = permissions.enableBluetooth,
        onOpenLocationSettings = permissions.openLocationSettings,
        onOpenAppSettings = permissions.openAppSettings,
        onFinish = vm::finish,
    )
}

@Composable
fun OnboardingScreen(
    draft: ProfileDraft,
    permissions: PermissionSnapshot,
    onNickname: (String) -> Unit,
    onEmoji: (String) -> Unit,
    onColor: (Int) -> Unit,
    onRequestBluetooth: () -> Unit,
    onRequestNotifications: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onFinish: () -> Unit,
    initialPage: Int = 0,
) {
    val pager = rememberPagerState(initialPage = initialPage) { 3 }
    val scope = rememberCoroutineScope()
    val reduce = MurmurTheme.reduceMotion
    fun go(page: Int) {
        scope.launch { if (reduce) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }
    BackHandler(enabled = pager.currentPage > 0) { go(pager.currentPage - 1) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            HorizontalPager(state = pager, userScrollEnabled = false, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> WelcomePage(draft, onNext = { go(1) })
                    1 -> ProfilePage(draft, onNickname, onEmoji, onColor, onNext = { go(2) })
                    else -> PermissionsPage(
                        permissions,
                        onRequestBluetooth,
                        onRequestNotifications,
                        onEnableBluetooth,
                        onOpenLocationSettings,
                        onOpenAppSettings,
                        onFinish,
                    )
                }
            }
            PageIndicator(count = 3, current = pager.currentPage, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = "Step ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in 0 until count) {
            val width by animateDpAsState(if (i == current) 24.dp else 8.dp, Motion.popOrSnap(MurmurTheme.reduceMotion), label = "dotWidth")
            val color by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun WelcomePage(draft: ProfileDraft, onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RadarField(
            myEmoji = draft.emoji,
            myColor = draft.colorIndex,
            peers = emptyList(),
            onPeerClick = {},
            showPeers = false,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(0.8f),
        )
        Spacer(Modifier.height(24.dp))
        Text("Murmur", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Chat with people nearby. No internet. No accounts.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Get started") }
    }
}

@Composable
private fun ProfilePage(
    draft: ProfileDraft,
    onNickname: (String) -> Unit,
    onEmoji: (String) -> Unit,
    onColor: (Int) -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Who are you?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "People nearby see this. Change it any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ProfileEditor(draft, onNickname, onEmoji, onColor)
        }
        Button(
            onClick = onNext,
            enabled = draft.isValid,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding).heightIn(min = 52.dp),
        ) { Text("Continue") }
    }
}

@Composable
private fun PermissionsPage(
    p: PermissionSnapshot,
    onRequestBluetooth: () -> Unit,
    onRequestNotifications: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("A few permissions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Murmur only talks to phones around you over Bluetooth.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!p.bleSupported) {
                PermissionCard(
                    icon = Icons.Filled.Warning,
                    title = "Bluetooth LE not available",
                    reason = "This phone can't run the mesh. You can still explore Murmur with Demo mode (You → Diagnostics).",
                    state = PermState.PERMANENTLY_DENIED,
                    onRequest = {},
                    onOpenSettings = {},
                    showActions = false,
                )
            } else {
                PermissionCard(
                    icon = if (p.bluetoothIsLocation) Icons.Filled.LocationOn else MurmurIcons.Bluetooth,
                    title = if (p.bluetoothIsLocation) "Location (for Bluetooth)" else "Nearby devices",
                    reason = if (p.bluetoothIsLocation) {
                        "This Android version needs it to scan for Bluetooth. Murmur never uses your location."
                    } else {
                        "Find, connect to and be found by phones around you."
                    },
                    state = p.bluetooth,
                    onRequest = onRequestBluetooth,
                    onOpenSettings = onOpenAppSettings,
                )
                if (p.notifications != null) {
                    PermissionCard(
                        icon = Icons.Filled.Notifications,
                        title = "Notifications",
                        reason = "Know when someone messages you, and see that the mesh is running.",
                        state = p.notifications,
                        onRequest = onRequestNotifications,
                        onOpenSettings = onOpenAppSettings,
                    )
                }
                ToggleCard(
                    icon = MurmurIcons.Bluetooth,
                    title = "Bluetooth",
                    reason = if (p.bluetoothOn) "Bluetooth is on." else "Turn on Bluetooth to reach people nearby.",
                    on = p.bluetoothOn,
                    actionLabel = "Turn on",
                    enabled = p.bluetooth == PermState.GRANTED || p.bluetoothIsLocation,
                    onAction = onEnableBluetooth,
                )
                if (p.locationNeeded) {
                    ToggleCard(
                        icon = Icons.Filled.LocationOn,
                        title = "Location services",
                        reason = if (p.locationOn) "Location is on." else "Android needs Location on to scan for Bluetooth on this version.",
                        on = p.locationOn,
                        actionLabel = "Open settings",
                        enabled = true,
                        onAction = onOpenLocationSettings,
                    )
                }
            }
        }
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding).heightIn(min = 52.dp)) {
            Text("Finish")
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    reason: String,
    state: PermState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    showActions: Boolean = true,
) {
    CardShell(icon, title, reason) {
        if (!showActions) return@CardShell
        AnimatedContent(
            targetState = state,
            transitionSpec = { (fadeIn() + scaleIn(Motion.pop(), initialScale = 0.3f)) togetherWith fadeOut() },
            label = "permState",
        ) { s ->
            when (s) {
                PermState.GRANTED -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "$title granted",
                    tint = MurmurTheme.colors.online,
                    modifier = Modifier.size(32.dp),
                )
                PermState.NOT_REQUESTED -> FilledTonalButton(onClick = onRequest) { Text("Allow") }
                PermState.DENIED -> FilledTonalButton(onClick = onRequest) { Text("Retry") }
                PermState.PERMANENTLY_DENIED -> TextButton(onClick = onOpenSettings) { Text("Open settings") }
            }
        }
    }
}

@Composable
private fun ToggleCard(icon: ImageVector, title: String, reason: String, on: Boolean, actionLabel: String, enabled: Boolean, onAction: () -> Unit) {
    CardShell(icon, title, reason) {
        AnimatedContent(targetState = on, transitionSpec = { (fadeIn() + scaleIn(Motion.pop(), initialScale = 0.3f)) togetherWith fadeOut() }, label = "toggle") { isOn ->
            if (isOn) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "$title on", tint = MurmurTheme.colors.online, modifier = Modifier.size(32.dp))
            } else {
                FilledTonalButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun CardShell(icon: ImageVector, title: String, reason: String, trailing: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Preview(name = "Onboarding welcome · light", showBackground = true)
@Composable
private fun WelcomeLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    OnboardingScreen(ProfileDraft("", "🐧", 1), PermissionSnapshot(), {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Onboarding profile · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProfileDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    OnboardingScreen(ProfileDraft("Sam", "🐧", 1), PermissionSnapshot(), {}, {}, {}, {}, {}, {}, {}, {}, {}, initialPage = 1)
}

@Preview(name = "Onboarding permissions · light", showBackground = true)
@Composable
private fun PermissionsLightPreview() = MurmurTheme(ThemeMode.LIGHT) {
    OnboardingScreen(
        ProfileDraft("Sam", "🐧", 1),
        PermissionSnapshot(bluetooth = PermState.GRANTED, notifications = PermState.PERMANENTLY_DENIED, bluetoothOn = false),
        {}, {}, {}, {}, {}, {}, {}, {}, {},
        initialPage = 2,
    )
}

@Preview(name = "Onboarding permissions · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PermissionsDarkPreview() = MurmurTheme(ThemeMode.DARK) {
    OnboardingScreen(
        ProfileDraft("Sam", "🐧", 1),
        PermissionSnapshot(bluetooth = PermState.DENIED, notifications = PermState.NOT_REQUESTED, locationNeeded = true, locationOn = false, bluetoothIsLocation = true),
        {}, {}, {}, {}, {}, {}, {}, {}, {},
        initialPage = 2,
    )
}
