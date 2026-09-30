package app.murmur.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** Motion tokens shared by every screen. */
object Motion {
    /** Material 3 emphasized easing. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val NAV_MILLIS = 300

    /** Pop-ins: bouncy. */
    fun <T> pop(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 400f)

    /** Position changes: calm. */
    fun <T> move(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    /** A tween that becomes a snap when the user turned animations off. */
    fun <T> tweenOrSnap(reduce: Boolean, millis: Int = NAV_MILLIS): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(millis, easing = Emphasized)

    fun <T> popOrSnap(reduce: Boolean): FiniteAnimationSpec<T> = if (reduce) snap() else pop()
    fun <T> moveOrSnap(reduce: Boolean): FiniteAnimationSpec<T> = if (reduce) snap() else move()
}
