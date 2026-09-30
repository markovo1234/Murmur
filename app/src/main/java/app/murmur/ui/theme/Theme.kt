package app.murmur.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.murmur.data.ThemeMode

/** Night-sky palette (dark) and its daytime counterpart. */
object Palette {
    // Dark
    val Night = Color(0xFF0A0F1E)
    val NightSurface = Color(0xFF111831)
    val NightContainer = Color(0xFF172042)
    val NightOutline = Color(0xFF2A3560)
    val Mint = Color(0xFF5EEAD4)
    val Lavender = Color(0xFFA78BFA)
    val Amber = Color(0xFFFBBF24)
    val Coral = Color(0xFFFF6B6B)
    val Starlight = Color(0xFFE6EAF6)
    val Haze = Color(0xFF9AA4C7)

    // Light
    val Day = Color(0xFFF6F7FB)
    val DaySurface = Color(0xFFFFFFFF)
    val DayContainer = Color(0xFFEEF1F8)
    val DayOutline = Color(0xFFCBD2E6)
    val Teal = Color(0xFF0D9488)
    val Violet = Color(0xFF7C3AED)
    val Rust = Color(0xFFB45309)
    val Red = Color(0xFFDC2626)
    val Ink = Color(0xFF0B1226)
    val InkMuted = Color(0xFF4A5578)

    /** The 10 avatar colors (emoji sits on top; a 2 dp ring surrounds it). */
    val Avatars = listOf(
        Color(0xFF5EEAD4), Color(0xFFA78BFA), Color(0xFFFBBF24), Color(0xFFFB7185), Color(0xFF60A5FA),
        Color(0xFF34D399), Color(0xFFF472B6), Color(0xFFF97316), Color(0xFF22D3EE), Color(0xFFC084FC),
    )

    fun avatar(index: Int): Color = Avatars[index.mod(Avatars.size)]
}

private val DarkColors = darkColorScheme(
    primary = Palette.Mint,
    onPrimary = Color(0xFF03201C),
    primaryContainer = Color(0xFF0F3B37),
    onPrimaryContainer = Color(0xFFA7F3E8),
    inversePrimary = Palette.Teal,
    secondary = Palette.Lavender,
    onSecondary = Color(0xFF1B0F40),
    secondaryContainer = Color(0xFF2E2458),
    onSecondaryContainer = Color(0xFFE2D9FF),
    tertiary = Palette.Amber,
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF3F2E06),
    onTertiaryContainer = Color(0xFFFDE68A),
    error = Palette.Coral,
    onError = Color(0xFF2D0000),
    errorContainer = Color(0xFF4A1518),
    onErrorContainer = Color(0xFFFFD6D6),
    background = Palette.Night,
    onBackground = Palette.Starlight,
    surface = Palette.NightSurface,
    onSurface = Palette.Starlight,
    surfaceVariant = Palette.NightContainer,
    onSurfaceVariant = Palette.Haze,
    surfaceTint = Palette.Mint,
    inverseSurface = Palette.Starlight,
    inverseOnSurface = Palette.NightSurface,
    outline = Palette.NightOutline,
    outlineVariant = Color(0xFF1F2A50),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF26315A),
    surfaceDim = Palette.Night,
    surfaceContainerLowest = Color(0xFF070B17),
    surfaceContainerLow = Color(0xFF0E1428),
    surfaceContainer = Palette.NightContainer,
    surfaceContainerHigh = Color(0xFF1D2850),
    surfaceContainerHighest = Color(0xFF243060),
)

private val LightColors = lightColorScheme(
    primary = Palette.Teal,
    // White on #0D9488 is only 3.7:1, so on-primary text is near-black (4.8:1).
    onPrimary = Color(0xFF021A17),
    primaryContainer = Color(0xFFCCF5EE),
    onPrimaryContainer = Color(0xFF053B35),
    inversePrimary = Palette.Mint,
    secondary = Palette.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE4FF),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Palette.Rust,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDD5),
    onTertiaryContainer = Color(0xFF451A03),
    error = Palette.Red,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A),
    background = Palette.Day,
    onBackground = Palette.Ink,
    surface = Palette.DaySurface,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.DayContainer,
    onSurfaceVariant = Palette.InkMuted,
    surfaceTint = Palette.Teal,
    inverseSurface = Color(0xFF1B2340),
    inverseOnSurface = Palette.Day,
    outline = Palette.DayOutline,
    outlineVariant = Color(0xFFDDE2F0),
    scrim = Color(0xFF000000),
    surfaceBright = Palette.DaySurface,
    surfaceDim = Color(0xFFDCE0EC),
    surfaceContainerLowest = Palette.DaySurface,
    surfaceContainerLow = Color(0xFFF3F5FA),
    surfaceContainer = Palette.DayContainer,
    surfaceContainerHigh = Color(0xFFE7EBF4),
    surfaceContainerHighest = Color(0xFFE0E5F0),
)

/** Colors Material 3 has no role for. */
@Immutable
data class MurmurColors(
    val isDark: Boolean,
    val ownBubbleStart: Color,
    val ownBubbleEnd: Color,
    val onOwnBubble: Color,
    /** Alpha for timestamps/ticks on own bubbles: 0.7 unless contrast needs more. */
    val ownBubbleMetaAlpha: Float,
    val otherBubble: Color,
    val onOtherBubble: Color,
    val hop: Color,
    val onHop: Color,
    val online: Color,
    val radarRing: Color,
    val radarSweep: Color,
    val radarPulse: Color,
)

val LocalMurmurColors = staticCompositionLocalOf { murmurColors(DarkColors, true) }

/** True when the system "Remove animations" setting (animator scale 0) is on. */
val LocalReduceMotion = staticCompositionLocalOf { false }

val MurmurShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object Dimens {
    val ScreenPadding = 16.dp
    val Grid = 4.dp
    val MinTouch = 48.dp
    val BubbleRadius = 20.dp
    val BubbleGroupedRadius = 6.dp
    val SheetRadius = 28.dp
    const val BUBBLE_MAX_WIDTH_FRACTION = 0.78f
}

@Composable
fun MurmurTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val extra = remember(scheme, dark) { murmurColors(scheme, dark) }
    CompositionLocalProvider(LocalMurmurColors provides extra, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), shapes = MurmurShapes, content = content)
    }
}

object MurmurTheme {
    val colors: MurmurColors @Composable get() = LocalMurmurColors.current
    val reduceMotion: Boolean @Composable get() = LocalReduceMotion.current
}

// ---------------------------------------------------------------------------- contrast helpers

fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/**
 * Own bubbles are a primary → secondary gradient. Pick the text color (near-black or white) that reads
 * best on both ends; if either end is below 4.5:1, nudge that end toward black/white until it passes.
 */
internal fun murmurColors(scheme: ColorScheme, dark: Boolean): MurmurColors {
    val darkText = Color(0xFF0A0F1E)
    val lightText = Color.White
    val start = scheme.primary
    val end = scheme.secondary
    fun worst(text: Color) = minOf(contrast(text, start), contrast(text, end))
    val text = if (worst(darkText) >= worst(lightText)) darkText else lightText
    val towards = if (text == lightText) Color.Black else Color.White
    fun fix(c: Color): Color {
        var out = c
        var t = 0f
        while (contrast(text, out) < 4.6f && t < 1f) {
            t += 0.04f
            out = lerp(c, towards, t)
        }
        return out
    }
    val s = fix(start)
    val e = fix(end)
    var alpha = 0.7f
    while (alpha < 1f && minOf(contrast(text.copy(alpha = alpha).compositeOver(s), s), contrast(text.copy(alpha = alpha).compositeOver(e), e)) < 4.5f) {
        alpha += 0.05f
    }
    return MurmurColors(
        isDark = dark,
        ownBubbleStart = s,
        ownBubbleEnd = e,
        onOwnBubble = text,
        ownBubbleMetaAlpha = alpha.coerceAtMost(1f),
        otherBubble = scheme.surfaceContainer,
        onOtherBubble = scheme.onSurface,
        hop = scheme.tertiary,
        onHop = scheme.onTertiary,
        online = if (dark) Color(0xFF34D399) else Color(0xFF047857),
        radarRing = scheme.primary.copy(alpha = if (dark) 0.22f else 0.30f),
        radarSweep = scheme.primary,
        radarPulse = scheme.primary,
    )
}
