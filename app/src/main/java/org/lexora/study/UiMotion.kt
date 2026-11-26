package org.lexora.study

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

internal const val MOTION_PRESS = 110
internal const val MOTION_FAST = 160
internal const val MOTION_STANDARD = 200
internal const val MOTION_PROGRESS = 220

val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun check(): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val enabled = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
        return scale == 0f || !enabled
    }
    var reduced by remember(context) { mutableStateOf(check()) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = check()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return reduced
}

fun peerScreenTransition(reducedMotion: Boolean): ContentTransform =
    if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
    else fadeIn(tween(MOTION_STANDARD, easing = FastOutSlowInEasing)) togetherWith
        fadeOut(tween(MOTION_FAST, easing = FastOutSlowInEasing))

fun drillTransition(forward: Boolean, reducedMotion: Boolean): ContentTransform {
    if (reducedMotion) return EnterTransition.None togetherWith ExitTransition.None
    val enterOffset: (Int) -> Int = { if (forward) it / 16 else -it / 16 }
    val exitOffset: (Int) -> Int = { if (forward) -it / 20 else it / 20 }
    return (fadeIn(tween(MOTION_STANDARD, easing = FastOutSlowInEasing)) +
        slideInHorizontally(tween(MOTION_STANDARD, easing = FastOutSlowInEasing), initialOffsetX = enterOffset)) togetherWith
        (fadeOut(tween(MOTION_FAST, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(tween(MOTION_FAST, easing = FastOutSlowInEasing), targetOffsetX = exitOffset))
}

fun stepTransition(reducedMotion: Boolean): ContentTransform =
    if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
    else (fadeIn(tween(MOTION_FAST, easing = FastOutSlowInEasing)) +
        slideInHorizontally(tween(MOTION_FAST, easing = FastOutSlowInEasing)) { it / 24 }) togetherWith
        fadeOut(tween(120, easing = FastOutSlowInEasing))

fun panelEnter(reducedMotion: Boolean): EnterTransition =
    if (reducedMotion) EnterTransition.None
    else fadeIn(tween(180, easing = FastOutSlowInEasing)) + expandVertically(tween(180, easing = FastOutSlowInEasing))

fun panelExit(reducedMotion: Boolean): ExitTransition =
    if (reducedMotion) ExitTransition.None
    else fadeOut(tween(150, easing = FastOutSlowInEasing)) + shrinkVertically(tween(150, easing = FastOutSlowInEasing))

@Composable
fun rememberDelayedBusy(busy: Boolean, delayMs: Long = 150L): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }
    return visible
}

@Composable
fun rememberPressScale(interactionSource: InteractionSource, enabled: Boolean): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val target = if (enabled && pressed && !reducedMotion) 0.985f else 1f
    return animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = MOTION_PRESS, easing = FastOutSlowInEasing),
        label = "pressScale"
    )
}

fun Modifier.applyScale(scale: State<Float>): Modifier = graphicsLayer {
    val s = scale.value
    scaleX = s
    scaleY = s
}
