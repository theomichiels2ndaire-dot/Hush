package com.theo.remote.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Barre de progression ondulée (comme le lecteur d'Android) : la partie déjà écoutée est une vague
 * qui avance pendant la lecture et s'aplatit à la pause ; le reste est un trait fin et droit.
 */
@Composable
fun WavyBar(
    value: Float,
    playing: Boolean,
    onChange: (Float) -> Unit,
    onFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val shown = (drag ?: value).coerceIn(0f, 1f)
    // la vague se lisse quand c'est en pause ou qu'on fait glisser
    val amp by animateFloatAsState(if (playing && drag == null) 1f else 0f, tween(450), label = "amp")
    val thumb by animateFloatAsState(if (drag != null) 1.35f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "thumb")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    Canvas(
        modifier.fillMaxWidth().height(28.dp).then(
            if (!enabled) Modifier else Modifier
                .pointerInput(Unit) {
                    detectTapGestures { o -> finished((o.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { finished(it) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { c, _ ->
                        val v = (c.position.x / size.width).coerceIn(0f, 1f)
                        drag = v
                        change(v)
                    }
                },
        ),
    ) {
        val y = size.height / 2
        val x = size.width * shown
        val stroke = 4.dp.toPx()
        val gap = 7.dp.toPx()
        val wave = 30.dp.toPx() // longueur d'une ondulation
        val a = 3.4.dp.toPx() * amp
        // reste du son : trait droit et discret
        if (x + gap < size.width - stroke / 2) {
            drawLine(Color.White.copy(alpha = 0.28f), Offset(x + gap, y), Offset(size.width - stroke / 2, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        // déjà écouté : la vague (elle grandit doucement au départ pour ne pas commencer en plein creux)
        val end = (x - gap).coerceAtLeast(0f)
        if (end > stroke / 2) {
            val p = Path()
            var px = stroke / 2
            p.moveTo(px, y)
            val ramp = 18.dp.toPx()
            while (px < end) {
                px = (px + 2f).coerceAtMost(end)
                val k = (px / ramp).coerceAtMost(1f) * ((end - px) / ramp).coerceIn(0f, 1f).let { 0.35f + 0.65f * it }
                p.lineTo(px, y + a * k * sin(px / wave * 2f * PI.toFloat() - phase))
            }
            drawPath(p, Color.White.copy(alpha = if (drag != null) 1f else 0.9f), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // curseur : petit trait vertical arrondi
        val tw = 5.dp.toPx() * thumb
        val th = 18.dp.toPx() * thumb
        drawRoundRect(Color.White, topLeft = Offset(x - tw / 2, y - th / 2), size = Size(tw, th), cornerRadius = CornerRadius(tw / 2))
    }
}
