package com.theo.remote.ui

import androidx.compose.runtime.collectAsState
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage

// ---------------------------------------------------------------- couleurs

/** Fond de l'appli (noir pur en mode AMOLED). */
val Ink: Color get() {
    val f = com.theo.remote.Features.flags.value
    // fonds débloqués avec les récompenses : en style Pochette, ce sont des aplats sombres
    if (f.rewards) when (com.theo.remote.Rewards.equippedIn("bg")) {
        "bg:ink" -> return Color(0xFF0F1626)
        "bg:season" -> return SeasonInk.current()
        "bg:aurora" -> if (f.flatStyle) return Color(0xFF0B1F1C)
        "bg:stars" -> if (f.flatStyle) return Color(0xFF0E0F24)
        "bg:sunset" -> if (f.flatStyle) return Color(0xFF231218)
    }
    return if (f.amoled) Color.Black else Color(0xFF07080C)
}

/** Teinte du fond selon la saison (récompense « Saisons »). */
object SeasonInk {
    fun current(): Color = when (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)) {
        8, 9, 10 -> Color(0xFF1E130C)   // automne : roux très sombre
        11, 0, 1 -> Color(0xFF10151C)   // hiver : gris bleuté
        2, 3, 4 -> Color(0xFF0F1A12)    // printemps : vert sombre
        else -> Color(0xFF1C170C)       // été : sable sombre
    }
}
val Txt = Color(0xFFF4F6FA)
val Txt2 = Color(0xFFB0B6C3) // un peu plus clair : textes secondaires plus lisibles
val Txt3 = Color(0xFF7D8494)
/** Couleur d'accent (réglable : vert, violet, bleu, rose, orange ou celle de la pochette). */
val Ok: Color get() = Color(com.theo.remote.Accent.current())
val Warn = Color(0xFFFFC46B)
val Ko = Color(0xFFFF6B7A)

/** Verre dépoli : voile clair translucide + liseré, posé sur le fond flou. */
/**
 * « Formes nettes » (option) : les grands arrondis sont réduits, et les pilules larges
 * deviennent des rectangles aux coins doux. Les boutons ronds restent ronds.
 */
fun Rounded(r: Int): RoundedCornerShape =
    RoundedCornerShape(if (com.theo.remote.Features.flags.value.crispShapes && r >= 14) (r * 0.6f).dp else r.dp)

object PillShape : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val w = size.width; val h = size.height
        val round = minOf(w, h) / 2f
        val square = kotlin.math.abs(w - h) <= 0.2f * maxOf(w, h)
        val r = if (!com.theo.remote.Features.flags.value.crispShapes || square) round else minOf(round, with(density) { 12.dp.toPx() })
        return androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, androidx.compose.ui.geometry.CornerRadius(r, r)))
    }
}

fun Modifier.glass(shape: Shape, fill: Float = 0.08f, edge: Float = 0.12f): Modifier =
    if (com.theo.remote.Features.flags.value.flatStyle)
        // style Pochette : surfaces grises unies, sans liseré de verre
        this.clip(shape).background(Color.White.copy(alpha = if (fill <= 0.08f) 0.055f else 0.09f))
    else this
        .clip(shape)
        .background(Color.White.copy(alpha = fill))
        // plus de liseré blanc autour des surfaces en verre (demandé par Theo)

// ---------------------------------------------------------------- couleurs de la pochette

data class CoverColors(val a: Color, val b: Color, val c: Color)

val DefaultCover = CoverColors(Color(0xFF243B63), Color(0xFF3B2A6B), Color(0xFF0F4C5C))

/** Couleurs des pochettes déjà analysées (par image), pour ne jamais refaire le calcul. */
private val bmpColorCache = android.util.LruCache<Int, CoverColors>(60)

/**
 * Couleurs d'une pochette. L'analyse se fait en arrière-plan : pendant ce temps on garde les couleurs
 * d'avant (le fond fait ensuite un fondu), au lieu de bloquer l'écran au moment où le son change.
 */
@Composable
fun rememberCoverColors(art: Bitmap?): CoverColors {
    val key = art?.let { System.identityHashCode(it) }
    val cached = key?.let { bmpColorCache.get(it) }
    var last by remember { mutableStateOf(cached ?: DefaultCover) }
    androidx.compose.runtime.LaunchedEffect(key) {
        if (art == null || key == null) { last = DefaultCover; return@LaunchedEffect }
        val c = bmpColorCache.get(key) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { paletteOf(art) }
        bmpColorCache.put(key, c)
        last = c
    }
    return cached ?: last
}

/** Couleurs déjà calculées, par lien : en revenant sur une page, rien n'est recalculé. */
private val urlColorCache = android.util.LruCache<String, CoverColors>(300)

private fun paletteOf(art: Bitmap): CoverColors = try {
    val p = Palette.from(art).maximumColorCount(16).generate()
    fun sw(vararg list: Palette.Swatch?) = list.firstOrNull { it != null }?.let { Color(it.rgb) }
    CoverColors(
        a = sw(p.vibrantSwatch, p.dominantSwatch, p.mutedSwatch) ?: DefaultCover.a,
        b = sw(p.darkVibrantSwatch, p.darkMutedSwatch, p.dominantSwatch) ?: DefaultCover.b,
        c = sw(p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch) ?: DefaultCover.c,
    )
} catch (e: Exception) { DefaultCover }

/**
 * Couleurs d'une pochette distante (playlist…), téléchargée en petit juste pour l'analyse.
 * Le calcul se fait hors de l'écran (fil d'arrière-plan) et reste en mémoire : plus de saccade en défilant.
 */
@Composable
fun rememberUrlCoverColors(url: String?): CoverColors {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var colors by remember(url) { mutableStateOf(url?.let { urlColorCache.get(it) } ?: DefaultCover) }
    androidx.compose.runtime.LaunchedEffect(url) {
        if (url == null || urlColorCache.get(url) != null) return@LaunchedEffect
        val req = coil.request.ImageRequest.Builder(ctx).data(url).size(160).allowHardware(false).build()
        val result = coil.Coil.imageLoader(ctx).execute(req)
        val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { paletteOf(bmp) }
        urlColorCache.put(url, c)
        colors = c
    }
    return colors
}

/**
 * Fond « dégradé maillé » : grosses taches de lumière aux couleurs de la pochette.
 * Dessiné avec des dégradés radiaux, donc pas besoin du flou d'Android 12.
 */
@Composable
fun Ambient(colors: CoverColors, strength: Float, modifier: Modifier = Modifier, animated: Boolean = false, themeable: Boolean = false, header: Boolean = false) {
    // mode économie : fond figé
    val flagsNow = com.theo.remote.Features.flags.collectAsState().value
    // style Pochette : fond noir uni ; en tête de page, la couleur de la pochette qui se fond dans le noir
    if (flagsNow.flatStyle && (themeable || header)) {
        val top by animateColorAsState(androidx.compose.ui.graphics.lerp(colors.a, Color.Black, 0.38f), tween(700), label = "top")
        Box(modifier.fillMaxSize().background(Ink)) {
            if (header) Box(
                Modifier.fillMaxWidth().height(460.dp)
                    .background(Brush.verticalGradient(listOf(top, top.copy(alpha = 0.55f), Ink))),
            )
        }
        return
    }
    val eco = flagsNow.eco
    // noir pur : les lueurs restent, mais plus discrètes
    val amoledK = if (flagsNow.amoled) 0.45f else 1f
    // les taches de lumière dérivent lentement (comme le fond animé d'Apple Music)
    val drift = if (animated && !eco) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "drift")
        t.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                tween(26_000, easing = androidx.compose.animation.core.LinearEasing),
            ),
            label = "driftT",
        )
    } else null
    // fond débloqué avec les récompenses (écran principal et panneaux)
    val eqBg = com.theo.remote.Rewards.equipped.collectAsState().value["bg"]
    com.theo.remote.Rewards.xp.collectAsState().value
    val bgTheme = if (themeable && flagsNow.rewards && eqBg != null && com.theo.remote.Rewards.unlocked(eqBg)) eqBg else null
    val themed = when (bgTheme) {
        "bg:aurora" -> CoverColors(Color(0xFF1FD1A0), Color(0xFF7B4DFF), Color(0xFF0F6E8C))
        "bg:stars" -> CoverColors(Color(0xFF1B2450), Color(0xFF2C1F5E), Color(0xFF0B1630))
        "bg:sunset" -> CoverColors(Color(0xFFFF7A45), Color(0xFFE2457A), Color(0xFF5B2A8C))
        else -> colors
    }
    // changement de son en douceur (option) : les couleurs du fond glissent lentement de l'une à l'autre
    val colorMs = if (flagsNow.smoothChange) 1600 else 900
    val a by animateColorAsState(themed.a, tween(colorMs, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "a")
    val b by animateColorAsState(themed.b, tween(colorMs + 200, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "b")
    val c by animateColorAsState(themed.c, tween(colorMs + 400, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "c")
    val stars = remember(bgTheme) {
        if (bgTheme != "bg:stars") emptyList() else {
            val r = kotlin.random.Random(42)
            List(70) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.6f, 0.25f + r.nextFloat() * 0.6f) }
        }
    }
    val s by animateFloatAsState(strength * amoledK, tween(500), label = "s")
    // les dégradés ne sont recréés que quand les couleurs changent ; à chaque image on ne fait que
    // déplacer et agrandir des formes déjà prêtes (bien moins de travail pour le téléphone)
    Box(
        modifier.fillMaxSize().background(Ink).drawWithCache {
            val w = size.width
            val h = size.height
            val k = s
            fun mk(color: Color, r: Float, alpha: Float) = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha * k), color.copy(alpha = alpha * k * 0.4f), Color.Transparent),
                center = Offset.Zero,
                radius = r,
            )
            val rA = w * 1.0f; val rB1 = w * 0.95f; val rB2 = w * 1.05f; val rC = w * 0.85f
            val bA = mk(a, rA, 0.95f)
            val bB1 = mk(b, rB1, 0.9f)
            val bB2 = mk(b, rB2, 0.7f)
            val bC = mk(c, rC, 0.85f)
            val fade = if (themeable || header) Brush.verticalGradient(
                0f to Color.Transparent, 0.35f to Ink.copy(alpha = 0.35f), 0.7f to Ink.copy(alpha = 0.82f), 1f to Ink,
            ) else null
            onDrawBehind {
                val p = drift?.value ?: 0f
                val m = if (drift != null) 1f else 0f
                fun dx(q: Float, ph: Float) = m * w * 0.16f * kotlin.math.sin(p * q + ph)
                fun dy(q: Float, ph: Float) = m * h * 0.10f * kotlin.math.cos(p * q + ph)
                fun pulse(q: Float, ph: Float) = 1f + m * 0.12f * kotlin.math.sin(p * q + ph)
                fun blob(brush: Brush, r: Float, cx: Float, cy: Float, sc: Float) {
                    translate(cx, cy) {
                        scale(sc, sc, pivot = Offset.Zero) { drawCircle(brush, radius = r, center = Offset.Zero) }
                    }
                }
                blob(bA, rA, w * 0.05f + dx(1f, 0f), h * 0.08f + dy(1f, 0f), pulse(2f, 0.5f))
                blob(bB1, rB1, w * 1.05f + dx(1f, 2f), h * 0.38f + dy(2f, 1f), pulse(1f, 2f))
                blob(bB2, rB2, w * 0.15f + dx(2f, 4f), h * 1.0f + dy(1f, 3f), pulse(1f, 4f))
                blob(bC, rC, w * 0.95f + dx(1f, 5f), h * 0.95f + dy(2f, 5f), pulse(2f, 1f))
                drawRect(Color.Black.copy(alpha = 0.22f))
                // écrans principaux : la lumière de la pochette vient d'en haut et se fond dans le noir plus bas
                if (fade != null) drawRect(fade)
                stars.forEach { st -> drawCircle(Color.White.copy(alpha = st[3]), radius = st[2], center = Offset(st[0] * w, st[1] * h)) }
            }
        },
    )
}

// ---------------------------------------------------------------- images

@Composable
fun Cover(
    bitmap: Bitmap? = null,
    model: Any? = null,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(Color.White.copy(alpha = 0.07f)),
    ) {
        when {
            bitmap != null -> Image(
                // conversion faite une seule fois par image (pas à chaque image de l'animation ou du défilement)
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            model != null -> AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------- barre épaisse (progression / volume)

@Composable
fun ThickBar(
    value: Float,
    onChange: (Float) -> Unit,
    onFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 7.dp,
    enabled: Boolean = true,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val shown = (drag ?: value).coerceIn(0f, 1f)
    val grow by animateFloatAsState(if (drag != null) 1.6f else 1f, label = "grow")

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            val v = (o.x / size.width).coerceIn(0f, 1f)
                            finished(v)
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { drag?.let { finished(it) }; drag = null },
                            onDragCancel = { drag = null },
                        ) { change2, _ ->
                            val v = (change2.position.x / size.width).coerceIn(0f, 1f)
                            drag = v
                            change(v)
                        }
                    }
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = PillShape
        val local = rememberBackdrop()
        Box(
            Modifier
                .fillMaxWidth()
                .height(height * grow)
                .backdropSource(local)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .background(Color.White.copy(alpha = if (drag != null) 0.95f else 0.75f)),
            )
        }
        // verre liquide : pendant qu'on fait glisser, une goutte de verre suit le doigt, grossit la barre
        // dessous et s'étire quand on va vite
        if (liquidOn()) {
            val show by animateFloatAsState(if (drag != null) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "drop")
            if (show > 0.01f) {
                val follow by animateFloatAsState(shown, spring(dampingRatio = 0.7f, stiffness = 900f), label = "dropX")
                val stretch = (kotlin.math.abs(shown - follow) * 6f).coerceIn(0f, 0.8f)
                val w = 34.dp * (1f + stretch)
                val h = 22.dp * (1f - 0.25f * stretch)
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = maxWidth * follow - w / 2)
                        .size(w, h)
                        .graphicsLayer { scaleX = 0.4f + 0.6f * show; scaleY = 0.4f + 0.6f * show; alpha = show.coerceIn(0f, 1f) }
                        .shadow(8.dp, shape)
                        .clip(shape),
                ) {
                    LiquidGlass(local, shape, cornerRadius = 11.dp, modifier = Modifier.matchParentSize(), blur = 0.dp, depth = 8.dp, refraction = 5.dp, chroma = 0.3f, zoom = 1.35f, spec = 1.3f)
                    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.06f)))
                }
            }
        }
    }
}

fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
