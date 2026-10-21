package io.github.originosfcmguardian.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext

/** A single finite entry, cancelled by replacement/disposal. No outgoing screen is retained. */
@Composable
internal fun Modifier.guardianDestinationEnter(destination: String): Modifier {
    val progress = remember(destination) { Animatable(0f) }
    LaunchedEffect(destination) { guardianDestinationEnter(progress) }
    return graphicsLayer {
        alpha = 0.7f + 0.3f * progress.value
        translationY = 6.dp.toPx() * (1f - progress.value)
    }
}

/** Uses the window recomposer's system motion scale; disabled motion needs no animation frame. */
internal suspend fun guardianDestinationEnter(progress: Animatable<Float, AnimationVector1D>) {
    if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) progress.snapTo(1f)
    else progress.animateTo(1f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
}
