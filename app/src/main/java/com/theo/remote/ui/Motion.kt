package com.theo.remote.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.combinedClickable

/** Ressorts utilisés partout, pour que tout bouge de la même façon. */
val SoftSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 380f)
val SnappySpring = spring<Float>(dampingRatio = 0.55f, stiffness = 700f)

/** Clic avec un petit enfoncement élastique au lieu de l'onde Material. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.92f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, SnappySpring, label = "press")
    // petite vibration sur chaque vrai bouton (pas sur les fonds qui ferment une fenêtre)
    val view = androidx.compose.ui.platform.LocalView.current
    val tick = pressedScale < 1f
    val click: () -> Unit = {
        if (tick) Haptics.tap(view)
        onClick()
    }
    val long: (() -> Unit)? = onLongClick?.let { l -> {
        Haptics.long(view)
        l()
    } }
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (long == null) Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = click)
            else Modifier.combinedClickable(interactionSource = source, indication = null, enabled = enabled, onLongClick = long, onClick = click),
        )
}

// ---------------------------------------------------------------- glisser vers le bas pour fermer

/** Lien entre une fenêtre glissable et ce qu'elle contient. */
class DismissGate {
    /** La liste principale est-elle tout en haut ? (null = pas de liste → toujours oui) */
    var atTop: (() -> Boolean)? = null

    /** > 0 quand une fenêtre glissable est ouverte par-dessus (elle a la priorité). */
    var blocked = 0
}

val LocalDismissGate = staticCompositionLocalOf<DismissGate?> { null }

/** À appeler dans une fenêtre glissable qui contient une liste : on ne ferme que si elle est en haut. */
@Composable
fun DismissWhenAtTop(state: LazyListState) {
    val gate = LocalDismissGate.current
    DisposableEffect(gate, state) {
        gate?.atTop = { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }
        onDispose { gate?.atTop = null }
    }
}

@Composable
fun DismissWhenAtTop(state: androidx.compose.foundation.ScrollState) {
    val gate = LocalDismissGate.current
    DisposableEffect(gate, state) {
        gate?.atTop = { state.value == 0 }
        onDispose { gate?.atTop = null }
    }
}

/**
 * Fenêtre qu'on ferme en la glissant vers le bas.
 *
 * Le geste est lu sur un cadre FIXE (passe « Initial », avant les enfants) et seul le contenu
 * à l'intérieur se déplace : rien ne mesure le doigt dans un repère qui bouge, donc pas de tremblement.
 * Un geste plutôt horizontal est laissé aux enfants (pochette, barres de progression…).
 */
@Composable
fun SwipeDismissible(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onProgress: (Float) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(1) }
    val gate = remember { DismissGate() }
    val parent = LocalDismissGate.current
    val scope = rememberCoroutineScope()
    val job = remember { arrayOfNulls<Job>(1) }
    val dismiss by rememberUpdatedState(onDismiss)
    val progress by rememberUpdatedState(onProgress)

    // une fenêtre ouverte par-dessus une autre bloque celle du dessous
    DisposableEffect(parent) {
        parent?.let { it.blocked++ }
        onDispose { parent?.let { it.blocked-- } }
    }

    fun set(v: Float) {
        drag = v.coerceAtLeast(0f)
        progress((drag / height).coerceIn(0f, 1f))
    }

    suspend fun settle(velocity: Float) {
        if (drag > height * 0.22f || (velocity > 1400f && drag > 24f)) {
            animate(drag, height.toFloat(), animationSpec = tween(200)) { v, _ -> set(v) }
            dismiss()
        } else {
            animate(drag, 0f, animationSpec = SoftSpring) { v, _ -> set(v) }
        }
    }

    Box(
        modifier
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    var horizontal = false
                    var totalX = 0f
                    var totalY = 0f
                    if (drag > 0f) {
                        // on rattrape la fenêtre pendant son animation
                        job[0]?.cancel()
                        dragging = true
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        val dx = ch.position.x - ch.previousPosition.x
                        val dy = ch.position.y - ch.previousPosition.y
                        if (!dragging && !horizontal) {
                            totalX += dx
                            totalY += dy
                            if (abs(totalX) > slop || abs(totalY) > slop) {
                                if (abs(totalX) > abs(totalY)) {
                                    horizontal = true // geste horizontal : on laisse faire les enfants
                                } else if (totalY > 0 && dy >= 0 && gate.blocked == 0 && (gate.atTop?.invoke() ?: true)) {
                                    dragging = true
                                }
                                // sinon : la liste défile ; on reprend la main dès qu'elle touche le haut
                            }
                        }
                        if (dragging) {
                            set(drag + dy)
                            ch.consume()
                        }
                    }
                    if (dragging) {
                        val v = tracker.calculateVelocity().y
                        job[0] = scope.launch { settle(v) }
                    }
                }
            },
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalDismissGate provides gate) {
            Box(
                Modifier.graphicsLayer {
                    translationY = drag
                    val p = (drag / height).coerceIn(0f, 1f)
                    shape = RoundedCornerShape((p * 10f).coerceAtMost(1f) * 32.dp.toPx())
                    clip = drag > 0f
                },
                propagateMinConstraints = true,
                content = content,
            )
        }
    }
}

// ---------------------------------------------------------------- espacements

/** Grille d'espacement unique (multiples de 4/8) pour tout l'appli. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    /** Marge gauche/droite des écrans. */
    val gutter = 20.dp
}

// ---------------------------------------------------------------- vibrations

/**
 * Petite vibration « premium ». [strong] = confirmation (ajout, changement de son),
 * sinon un simple tic (boutons).
 */
@Composable
fun rememberHaptic(strong: Boolean = false): () -> Unit {
    val view = androidx.compose.ui.platform.LocalView.current
    return remember(view, strong) { { if (strong) Haptics.confirm(view) else Haptics.tap(view) } }
}

/**
 * Les vibrations de Hush, les mêmes partout : une petite tape légère pour les boutons et les gestes,
 * une plus franche pour confirmer (ajouté, aimé…), et l'appui long. Jamais deux d'affilée pour un seul geste.
 */
object Haptics {
    private var lastAt = 0L
    private var lastLevel = 0

    private fun play(view: android.view.View, type: Int, level: Int) {
        if (!com.theo.remote.Features.flags.value.haptics) return
        val now = android.os.SystemClock.uptimeMillis()
        // le bouton a déjà vibré pour ce même toucher (sauf si c'est une confirmation, plus forte)
        if (now - lastAt < 80 && level <= lastLevel) return
        lastAt = now; lastLevel = level
        view.performHapticFeedback(type)
    }

    fun tap(view: android.view.View) = play(view, android.view.HapticFeedbackConstants.KEYBOARD_TAP, 1)
    fun confirm(view: android.view.View) = play(view,
        if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.KEYBOARD_TAP, 2)
    fun long(view: android.view.View) = play(view, android.view.HapticFeedbackConstants.LONG_PRESS, 2)
}

// ---------------------------------------------------------------- squelettes de chargement

/** Reflet qui balaie les formes grises pendant un chargement. */
@Composable
fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -400f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerX",
    )
    val base = Color.White.copy(alpha = 0.06f)
    val light = Color.White.copy(alpha = 0.14f)
    return Brush.linearGradient(
        colors = listOf(base, light, base),
        start = Offset(x, 0f),
        end = Offset(x + 400f, 200f),
    )
}

/** Une ligne de liste en squelette (pochette + 2 lignes de texte). */
@Composable
fun SkeletonRow(brush: Brush) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(brush))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.35f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(brush))
        }
    }
}

/** Plusieurs lignes en squelette. */
@Composable
fun SkeletonList(count: Int = 6) {
    val brush = shimmerBrush()
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        repeat(count) { SkeletonRow(brush) }
    }
}

/** Grandes lignes floues, pour les paroles qui chargent. */
@Composable
fun SkeletonLines(count: Int = 5) {
    val brush = shimmerBrush()
    Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        repeat(count) { i ->
            Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.85f else 0.6f).height(26.dp).clip(RoundedCornerShape(8.dp)).background(brush))
        }
    }
}

// ---------------------------------------------------------------- verre flouté (« liquid glass »)

/**
 * Copie de ce qui est affiché derrière les barres flottantes.
 * La source enregistre son dessin dans [layer] ; les barres le redessinent floutées.
 */
class Backdrop(val layer: androidx.compose.ui.graphics.layer.GraphicsLayer) {
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** À poser sur le contenu qui passe SOUS les barres (jamais sur les barres elles-mêmes). */
fun Modifier.backdropSource(b: Backdrop): Modifier = this
    .onGloballyPositioned { b.coords = it }
    .drawWithContent {
        // mode économie : pas de copie de l'écran pour le flou
        if (com.theo.remote.Features.flags.value.eco) { drawContent(); return@drawWithContent }
        b.layer.record { this@drawWithContent.drawContent() }
        drawLayer(b.layer)
    }

private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float strength;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 q = abs(p - c) - (c - float2(radius));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    // 1 au bord, 0 à l'intérieur : la déformation ne touche que le pourtour
    float edge = 1.0 - smoothstep(0.0, radius * 1.1, -d);
    float2 dir = normalize(p - c + float2(0.0001));
    float2 offs = -dir * edge * edge * strength;
    return content.eval(p + offs);
}
"""

/** Crée (une fois par taille) l'effet flou + lentille sur les bords. */
private class GlassEffect {
    private var w = -1f
    private var h = -1f
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(width: Float, height: Float, cornerPx: Float, blurPx: Float, lensPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        if (width == w && height == h && cached != null) return cached
        w = width
        h = height
        val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
        val effect = if (android.os.Build.VERSION.SDK_INT >= 33) {
            val shader = android.graphics.RuntimeShader(LENS_SHADER)
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("radius", cornerPx.coerceAtMost(minOf(width, height) / 2f))
            shader.setFloatUniform("strength", lensPx)
            val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            android.graphics.RenderEffect.createChainEffect(lens, blur) // flou d'abord, puis lentille
        } else blur
        cached = effect.asComposeRenderEffect()
        return cached
    }
}

/**
 * Vrai verre liquide (Android 13+) : le verre est « bombé » sur ses bords. Ce qui passe dessous est
 * réfracté (tordu vers l'intérieur), les couleurs se séparent légèrement sur la courbe comme dans un prisme,
 * et un reflet de lumière suit la courbure (fort en haut à gauche, léger en bas à droite).
 * Pas de liseré blanc dessiné : tout vient de la lumière qui traverse le verre.
 */
private const val LIQUID_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;
uniform float refraction;
uniform float chroma;
uniform float zoom;
uniform float spec;

float sdRound(float2 p, float2 c, float r) {
    float2 q = abs(p - c) - (c - float2(r));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 p) {
    float2 c = size * 0.5;
    float r = min(radius, min(c.x, c.y));
    float d = sdRound(p, c, r);
    float e = 0.75;
    float2 g = float2(
        sdRound(p + float2(e, 0.0), c, r) - sdRound(p - float2(e, 0.0), c, r),
        sdRound(p + float2(0.0, e), c, r) - sdRound(p - float2(0.0, e), c, r)
    );
    float2 n = g / max(length(g), 0.0001);
    // 0 au bord, 1 dans la partie plate du verre
    float t = clamp(-d / depth, 0.0, 1.0);
    float bend = pow(1.0 - t, 2.2);
    // grossissement (loupe) puis réfraction vers l'intérieur sur la partie bombée
    float2 base = c + (p - c) / zoom;
    float2 off = -n * bend * refraction;
    half4 mid = content.eval(base + off);
    half4 col = mid;
    col.r = content.eval(base + off * (1.0 + chroma)).r;
    col.b = content.eval(base + off * (1.0 - chroma)).b;
    // un peu plus de couleur et de lumière, comme à travers une vraie vitre
    half lum = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(lum), col.rgb, half(1.22));
    col.rgb = col.rgb * half(1.03) + half(0.015);
    // reflets qui suivent la courbe
    float2 L = normalize(float2(-0.55, -0.85));
    float rim = 1.0 - smoothstep(0.0, 2.4, -d);
    float k1 = max(dot(n, L), 0.0);
    float k2 = max(dot(n, -L), 0.0);
    float band = (1.0 - t) * (1.0 - t);
    float s = rim * (0.6 * pow(k1, 1.4) + 0.25 * pow(k2, 1.6)) + band * 0.12 * pow(k1, 2.0);
    col.rgb = col.rgb + half3(half(s * spec));
    return col;
}
"""

/** Réglages d'un morceau de verre liquide. */
private class LiquidEffect {
    private var shader: Any? = null
    private val last = FloatArray(9) { Float.NaN }
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(
        width: Float, height: Float, cornerPx: Float, blurPx: Float,
        depthPx: Float, refractionPx: Float, chroma: Float, zoom: Float, spec: Float,
    ): androidx.compose.ui.graphics.RenderEffect? {
        if (android.os.Build.VERSION.SDK_INT < 33 || width <= 0f || height <= 0f) return null
        // rien n'a changé depuis l'image d'avant : on garde l'effet (aucune allocation à chaque image)
        val now = floatArrayOf(width, height, cornerPx, blurPx, depthPx, refractionPx, chroma, zoom, spec)
        if (cached != null && now.contentEquals(last)) return cached
        now.copyInto(last)
        val sh = (shader as? android.graphics.RuntimeShader) ?: android.graphics.RuntimeShader(LIQUID_SHADER).also { shader = it }
        sh.setFloatUniform("size", width, height)
        sh.setFloatUniform("radius", cornerPx)
        sh.setFloatUniform("depth", depthPx.coerceAtLeast(1f))
        sh.setFloatUniform("refraction", refractionPx)
        sh.setFloatUniform("chroma", chroma)
        sh.setFloatUniform("zoom", zoom)
        sh.setFloatUniform("spec", spec)
        val liquid = android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content")
        val effect = if (blurPx > 0.5f) {
            val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
            android.graphics.RenderEffect.createChainEffect(liquid, blur) // flou léger d'abord, puis le verre
        } else liquid
        cached = effect.asComposeRenderEffect()
        return cached
    }
}

/**
 * Morceau de verre liquide qui montre (réfracté) ce qui passe dessous.
 * [zoom] > 1 en fait une loupe (la pastille de l'onglet choisi).
 */
@Composable
fun LiquidGlass(
    b: Backdrop,
    shape: androidx.compose.ui.graphics.Shape,
    cornerRadius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    blur: androidx.compose.ui.unit.Dp = 3.dp,
    depth: androidx.compose.ui.unit.Dp = 20.dp,
    refraction: androidx.compose.ui.unit.Dp = 14.dp,
    chroma: Float = 0.22f,
    zoom: Float = 1f,
    spec: Float = 1f,
) {
    var offset by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val effect = remember { LiquidEffect() }
    Box(
        modifier
            .onGloballyPositioned { c ->
                b.coords?.let { src -> if (src.isAttached && c.isAttached) offset = src.localPositionOf(c, Offset.Zero) }
            }
            .graphicsLayer {
                // le rayon suit la vraie forme (utile avec « Formes nettes »), sinon celui donné
                val shapeR = (shape.createOutline(size, androidx.compose.ui.unit.LayoutDirection.Ltr, this) as? androidx.compose.ui.graphics.Outline.Rounded)
                    ?.roundRect?.topLeftCornerRadius?.x
                renderEffect = effect.get(
                    size.width, size.height,
                    (shapeR ?: cornerRadius.toPx()).coerceAtMost(minOf(size.width, size.height) / 2f),
                    blur.toPx(), depth.toPx(), refraction.toPx(), chroma, zoom, spec,
                )
                this.shape = shape
                clip = true
            }
            .drawBehind {
                translate(-offset.x, -offset.y) { drawLayer(b.layer) }
            },
    )
}

/** Fond du lecteur (lumières de la pochette) que les boutons du lecteur tordent ; null hors du lecteur. */
val LocalPlayerGlass = androidx.compose.runtime.staticCompositionLocalOf<Backdrop?> { null }

/** Tout le lecteur (fond + contenu) : ce que tordent ses menus en verre liquide ; null si le verre est coupé. */
val LocalSheetGlass = androidx.compose.runtime.staticCompositionLocalOf<Backdrop?> { null }

/** Menus du lecteur dessinés en verre liquide par-dessus tout le lecteur (⋯ et étoiles). */
object PlayerOverlay {
    /** Contenu du menu ⋯ ouvert (null = fermé) ; reçoit de quoi le fermer. */
    var menu by androidx.compose.runtime.mutableStateOf<(@Composable (close: () -> Unit) -> Unit)?>(null)
    var menuAnchor by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    /** Note en cours pendant l'appui long sur l'étoile (null = fermé, 0 = pas encore glissé). */
    var stars by androidx.compose.runtime.mutableStateOf<Int?>(null)
    var starsAnchor by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
}

/** Ce qui est affiché sous les fenêtres et menus de l'appli (les onglets et pages). */
val LocalMainGlass = androidx.compose.runtime.staticCompositionLocalOf<Backdrop?> { null }

/** Le verre liquide est-il actif (option, pas en mode économie de Hush, Android 13+) ? */
@Composable
fun liquidOn(): Boolean {
    val f = com.theo.remote.Features.flags.collectAsState().value
    return f.liquidGlass && !f.eco && android.os.Build.VERSION.SDK_INT >= 33
}

/**
 * Plaque de verre flottante pour les fenêtres et menus (verre liquide dépoli si actif, sinon fond sombre).
 * Le contenu se pose par-dessus.
 */
@Composable
fun GlassPlate(
    shape: androidx.compose.ui.graphics.Shape,
    corner: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalMainGlass.current,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val liquid = backdrop != null && liquidOn()
    Box(modifier.then(if (liquid) Modifier.shadow(26.dp, shape) else Modifier).clip(shape)) {
        if (liquid) {
            LiquidGlass(backdrop!!, shape, cornerRadius = corner, modifier = Modifier.matchParentSize(), blur = 18.dp, depth = 22.dp, refraction = 16.dp, chroma = 0.25f)
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)))
        } else {
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color(0xFF15151C)).glass(shape, fill = 0.06f, edge = 0.12f))
        }
        content()
    }
}

/** Bouton rond en verre liquide posé sur [b] (ce qui défile dessous). */
@Composable
fun LiquidCircleButton(
    b: Backdrop,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    iconSize: androidx.compose.ui.unit.Dp = 21.dp,
    tint: androidx.compose.ui.graphics.Color = Txt,
    onClick: () -> Unit,
) {
    val shape = PillShape
    Box(
        Modifier.size(size).shadow(10.dp, shape).clip(shape).bounceClick(pressedScale = 0.86f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlass(
            b, shape, cornerRadius = size / 2, modifier = Modifier.matchParentSize(),
            blur = 2.dp, depth = size * 0.36f, refraction = size * 0.2f, chroma = 0.28f, zoom = 1.08f, spec = 1.1f,
        )
        Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.14f)))
        androidx.compose.material3.Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * Fond en verre à mettre en PREMIER enfant d'une barre flottante (avec Modifier.matchParentSize()).
 * Option « Vrai verre liquide » (Android 13+) : verre bombé qui réfracte ; sinon flou classique.
 */
@Composable
fun BackdropBlur(
    b: Backdrop,
    shape: androidx.compose.ui.graphics.Shape,
    cornerRadius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    blur: androidx.compose.ui.unit.Dp = 26.dp,
    lens: androidx.compose.ui.unit.Dp = 9.dp,
) {
    // mode économie : un simple verre teinté, sans flou
    val flags = com.theo.remote.Features.flags.collectAsState().value
    if (flags.eco) {
        Box(modifier.graphicsLayer { this.shape = shape; clip = true }.background(androidx.compose.ui.graphics.Color(0xF0121218)))
        return
    }
    if (flags.liquidGlass && android.os.Build.VERSION.SDK_INT >= 33) {
        LiquidGlass(b, shape, cornerRadius, modifier, blur = 3.dp, depth = (cornerRadius * 0.75f).coerceIn(12.dp, 26.dp), refraction = (cornerRadius * 0.5f).coerceIn(8.dp, 16.dp))
        return
    }
    var offset by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val effect = remember { GlassEffect() }
    Box(
        modifier
            .onGloballyPositioned { c ->
                b.coords?.let { src -> if (src.isAttached && c.isAttached) offset = src.localPositionOf(c, Offset.Zero) }
            }
            .graphicsLayer {
                renderEffect = effect.get(size.width, size.height, cornerRadius.toPx(), blur.toPx(), lens.toPx())
                this.shape = shape
                clip = true
            }
            .drawBehind {
                translate(-offset.x, -offset.y) { drawLayer(b.layer) }
            },
    )
}


/** Zone où glisser ne doit pas fermer la fenêtre (cadran, poignée…). */
fun Modifier.blockDismiss(): Modifier = composed {
    val gate = LocalDismissGate.current
    this.pointerInput(gate) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate?.let { it.blocked++ }
            try {
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                } while (e.changes.any { it.pressed })
            } finally {
                gate?.let { it.blocked-- }
            }
        }
    }
}


/** Où était la pochette de la playlist qu'on vient de toucher (pour la faire « s'envoler » en l'ouvrant). */
object PlaylistAnchor {
    private var id: String? = null
    private var rect: androidx.compose.ui.geometry.Rect? = null
    fun set(playlistId: String, r: androidx.compose.ui.geometry.Rect) { id = playlistId; rect = r }
    fun has(playlistId: String?) = playlistId != null && playlistId == id && rect != null
    /** Récupère la position une seule fois (la réouverture suivante d'ailleurs glisse normalement). */
    fun take(playlistId: String): androidx.compose.ui.geometry.Rect? =
        if (playlistId == id) rect.also { id = null; rect = null } else null
}

/** L'élément part de [from] (taille et place) et grandit en continu jusqu'à sa vraie place. */
fun Modifier.flyFrom(from: androidx.compose.ui.geometry.Rect?): Modifier = composed {
    if (from == null) return@composed this
    val fly = remember { androidx.compose.animation.core.Animatable(0f) }
    var me by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    androidx.compose.runtime.LaunchedEffect(me != null) {
        if (me != null) fly.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 230f))
    }
    this
        .onGloballyPositioned { if (me == null) me = it.boundsInWindow() }
        .graphicsLayer {
            val r = me
            if (r == null || r.width <= 0f) { alpha = 0f; return@graphicsLayer }
            val f = fly.value
            val k = 1f - f
            val sc = from.width / r.width + (1f - from.width / r.width) * f
            scaleX = sc; scaleY = sc
            translationX = (from.center.x - r.center.x) * k
            translationY = (from.center.y - r.center.y) * k
        }
}

/**
 * Comme rememberScrollState, mais la position est vraiment retrouvée en revenant sur l'onglet :
 * même si le contenu se charge un peu après (sinon on se retrouvait remonté en haut).
 */
@Composable
fun rememberSteadyScrollState(): androidx.compose.foundation.ScrollState {
    val st = androidx.compose.foundation.rememberScrollState()
    var saved by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(st) {
        val target = saved
        if (target > 0 && st.value < target) {
            kotlinx.coroutines.withTimeoutOrNull(2500) {
                androidx.compose.runtime.snapshotFlow { st.maxValue }.filter { it >= target }.firstOrNull()
            }
            st.scrollTo(target.coerceAtMost(st.maxValue))
        }
        androidx.compose.runtime.snapshotFlow { st.value }.collect { saved = it }
    }
    return st
}

/**
 * Changement de son : pochette, fond, couleurs et titre passent au son suivant ensemble,
 * en un seul fondu de même durée (au lieu de bouger chacun à son rythme).
 */
object SongChange {
    fun ms(): Int = if (com.theo.remote.Features.flags.value.smoothChange) 1000 else 600
    fun <T> spec(delay: Int = 0): androidx.compose.animation.core.TweenSpec<T> =
        androidx.compose.animation.core.tween(ms() - delay, delayMillis = delay, easing = androidx.compose.animation.core.FastOutSlowInEasing)
}
