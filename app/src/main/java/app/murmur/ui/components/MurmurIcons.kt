package app.murmur.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons that material-icons-core lacks, drawn by hand (material-icons-extended is not allowed).
 * Filled paths follow the Material Symbols geometry (Apache 2.0).
 */
object MurmurIcons {
    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Bluetooth: ImageVector by lazy {
        filled(
            "Bluetooth",
            "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29z" +
                "M13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z",
        )
    }

    val DoubleTick: ImageVector by lazy {
        filled(
            "DoubleTick",
            "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7z" +
                "M22.24,5.59L11.66,16.17 7.48,12l-1.41,1.41L11.66,19l12,-12 -1.42,-1.41z" +
                "M0.41,13.41L6,19l1.41,-1.41L1.83,12 0.41,13.41z",
        )
    }

    val Tick: ImageVector by lazy {
        filled("Tick", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    }

    val Clock: ImageVector by lazy {
        filled(
            "Clock",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z" +
                "M12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
        )
    }

    val Chat: ImageVector by lazy {
        filled(
            "Chat",
            "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z" +
                "M6,9h12v2H6zM14,14H6v-2h8zM18,8H6V6h12z",
        )
    }

    val Copy: ImageVector by lazy {
        filled(
            "Copy",
            "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1z" +
                "M19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2z" +
                "M19,21L8,21L8,7h11v14z",
        )
    }

    val Verified: ImageVector by lazy {
        filled(
            "Verified",
            "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12L21,5l-9,-4z" +
                "M10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z",
        )
    }

    val Block: ImageVector by lazy {
        filled(
            "Block",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12z" +
                "M12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z",
        )
    }

    val Hub: ImageVector by lazy {
        filled(
            "Hub",
            "M8.4,18.2C8.78,18.7 9,19.32 9,20c0,1.66 -1.34,3 -3,3s-3,-1.34 -3,-3 1.34,-3 3,-3c0.44,0 0.85,0.09 1.23,0.26l1.41,-1.77c-0.92,-1.03 -1.29,-2.39 -1.09,-3.69l-2.03,-0.68C4.98,11.95 4.06,12.5 3,12.5c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3c0,0.07 0,0.14 -0.01,0.21l2.03,0.68c0.64,-1.21 1.82,-2.09 3.22,-2.32V5.91C9.96,5.57 9,4.4 9,3c0,-1.66 1.34,-3 3,-3s3,1.34 3,3c0,1.4 -0.96,2.57 -2.25,2.91v2.16c1.4,0.23 2.58,1.11 3.22,2.32L18.01,9.71C18,9.64 18,9.57 18,9.5c0,-1.66 1.34,-3 3,-3s3,1.34 3,3 -1.34,3 -3,3c-1.06,0 -1.98,-0.55 -2.52,-1.37l-2.03,0.68c0.2,1.29 -0.16,2.65 -1.09,3.69l1.41,1.77C17.15,17.09 17.56,17 18,17c1.66,0 3,1.34 3,3s-1.34,3 -3,3 -3,-1.34 -3,-3c0,-0.68 0.22,-1.3 0.6,-1.8l-1.41,-1.77c-1.35,0.75 -3.01,0.76 -4.37,0L8.4,18.2z",
        )
    }

    val Reply: ImageVector by lazy {
        filled("Reply", "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z")
    }

    val Timer: ImageVector by lazy {
        filled(
            "Timer",
            "M15,1H9v2h6V1zM11,14h2V8h-2v6z" +
                "M19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61z" +
                "M12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        )
    }

    val Muted: ImageVector by lazy {
        filled(
            "Muted",
            "M20,18.69L7.84,6.14 5.27,3.49 4,4.76l2.8,2.8v0.01c-0.52,0.99 -0.8,2.16 -0.8,3.42v5l-2,2v1h13.73l2,2L21,19.72l-1,-1.03z" +
                "M12,22c1.11,0 2,-0.89 2,-2h-4c0,1.11 0.89,2 2,2z" +
                "M18,14.68L18,11c0,-3.08 -1.64,-5.64 -4.5,-6.32L13.5,4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68c-0.63,0.15 -1.2,0.41 -1.72,0.73L18,14.68z",
        )
    }

    val Pin: ImageVector by lazy {
        filled(
            "Pin",
            "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z",
        )
    }

    val People: ImageVector by lazy {
        filled(
            "People",
            "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3z" +
                "M8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3z" +
                "M8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z" +
                "M16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
        )
    }

    val StarOutline: ImageVector by lazy {
        filled(
            "StarOutline",
            "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24z" +
                "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
        )
    }

    /** Concentric range rings with a sweep line: the Radar tab. */
    val Radar: ImageVector by lazy {
        ImageVector.Builder("Radar", 24.dp, 24.dp, 24f, 24f).apply {
            val ink = SolidColor(Color.Black)
            addPath(addPathNodes("M12,2.5a9.5,9.5 0 1,1 0,19a9.5,9.5 0 1,1 0,-19"), stroke = ink, strokeLineWidth = 1.8f)
            addPath(addPathNodes("M12,7a5,5 0 1,1 0,10a5,5 0 1,1 0,-10"), stroke = ink, strokeLineWidth = 1.8f)
            addPath(addPathNodes("M12,12L18.7,5.3"), stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
            addPath(addPathNodes("M12,10a2,2 0 1,1 0,4a2,2 0 1,1 0,-4z"), fill = ink)
        }.build()
    }
}
