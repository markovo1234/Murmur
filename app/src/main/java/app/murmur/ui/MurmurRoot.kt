package app.murmur.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.murmur.AppContainer
import app.murmur.ui.chat.ChatRoute
import app.murmur.ui.components.LocalNavAnimatedScope
import app.murmur.ui.components.LocalSharedTransitionScope
import app.murmur.ui.components.Motion
import app.murmur.ui.diagnostics.DiagnosticsRoute
import app.murmur.ui.main.MainRoute
import app.murmur.ui.lock.LockScreen
import app.murmur.ui.onboarding.OnboardingRoute
import app.murmur.ui.people.PeopleRoute
import app.murmur.ui.settings.BlockedRoute
import app.murmur.ui.settings.ProfileEditRoute
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

// Type-safe routes.
@Serializable data object OnboardingDest
@Serializable data object MainDest
@Serializable data class ChatDest(val conversationId: String)
@Serializable data object ProfileDest
@Serializable data object BlockedDest
@Serializable data object DiagnosticsDest
@Serializable data object PeopleDest

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MurmurRoot(container: AppContainer, pendingConversation: MutableStateFlow<String?>) {
    val settingsOrNull by container.settingsState.collectAsStateWithLifecycle()
    val settings = settingsOrNull ?: return // the splash screen stays up until settings load
    val reduce = rememberReduceMotion()

    MurmurTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, reduceMotion = reduce) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val nav = rememberNavController()
            val start = remember { if (settings.onboardingDone) MainDest else OnboardingDest }
            val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

            // Shared-axis: slide 30 dp + fade, 300 ms, emphasized. Instant when animations are off.
            fun spec() = Motion.tweenOrSnap<androidx.compose.ui.unit.IntOffset>(reduce)
            fun fadeSpec() = Motion.tweenOrSnap<Float>(reduce)
            val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
                slideInHorizontally(spec()) { shift } + fadeIn(fadeSpec())
            }
            val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
                slideOutHorizontally(spec()) { -shift } + fadeOut(fadeSpec())
            }
            val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
                slideInHorizontally(spec()) { -shift } + fadeIn(fadeSpec())
            }
            val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
                slideOutHorizontally(spec()) { shift } + fadeOut(fadeSpec())
            }

            // App lock covers everything (the screens underneath stay put, so unlocking returns to them).
            val lockShowing by container.appLock.showing.collectAsStateWithLifecycle(initialValue = settings.appLock.enabled)
            SharedTransitionLayout(if (lockShowing) Modifier.clearAndSetSemantics {} else Modifier) {
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    NavHost(
                        navController = nav,
                        startDestination = start,
                        enterTransition = enter,
                        exitTransition = exit,
                        popEnterTransition = popEnter,
                        popExitTransition = popExit,
                    ) {
                        composable<OnboardingDest> { OnboardingRoute() }
                        composable<MainDest> {
                            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                MainRoute(
                                    onOpenChat = { nav.navigate(ChatDest(it)) { launchSingleTop = true } },
                                    onEditProfile = { nav.navigate(ProfileDest) },
                                    onBlocked = { nav.navigate(BlockedDest) },
                                    onDiagnostics = { nav.navigate(DiagnosticsDest) },
                                    onPeople = { nav.navigate(PeopleDest) { launchSingleTop = true } },
                                )
                            }
                        }
                        composable<ChatDest> { entry ->
                            val route = entry.toRoute<ChatDest>()
                            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                ChatRoute(
                                    conversationId = route.conversationId,
                                    onBack = { nav.popBackStack() },
                                    onOpenChat = { nav.navigate(ChatDest(it)) },
                                )
                            }
                        }
                        composable<ProfileDest> { ProfileEditRoute(onBack = { nav.popBackStack() }) }
                        composable<BlockedDest> { BlockedRoute(onBack = { nav.popBackStack() }) }
                        composable<DiagnosticsDest> { DiagnosticsRoute(onBack = { nav.popBackStack() }) }
                        composable<PeopleDest> {
                            PeopleRoute(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(ChatDest(it)) { launchSingleTop = true } })
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = lockShowing,
                enter = EnterTransition.None,
                exit = fadeOut(Motion.tweenOrSnap(reduce, 220)),
            ) {
                LockScreen()
            }

            // Onboarding finished → Radar. Panic wipe → back to onboarding.
            LaunchedEffect(settings.onboardingDone) {
                val onOnboarding = nav.currentDestination?.hasRoute<OnboardingDest>() == true
                if (settings.onboardingDone && onOnboarding) nav.resetTo(MainDest)
                if (!settings.onboardingDone && !onOnboarding && nav.currentDestination != null) nav.resetTo(OnboardingDest)
            }

            // Notification tap → open that chat.
            val pending by pendingConversation.collectAsStateWithLifecycle()
            LaunchedEffect(pending, settings.onboardingDone) {
                val id = pending ?: return@LaunchedEffect
                if (!settings.onboardingDone) return@LaunchedEffect
                pendingConversation.value = null
                nav.navigate(ChatDest(id)) { launchSingleTop = true }
            }
        }
    }
}

private fun NavHostController.resetTo(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
