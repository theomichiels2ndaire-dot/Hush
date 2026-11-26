package com.theo.remote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.theo.remote.TidalApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------- égaliseur animé

/** Trois petites barres qui dansent : « ce son joue ». [playing] = false les fige. */
@Composable
fun EqBars(playing: Boolean = true, color: Color = Txt, barWidth: Dp = 3.dp, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 280, 560)
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(barWidth * 0.7f), verticalAlignment = Alignment.Bottom) {
        phases.forEach { delayMs ->
            val v by t.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420, delayMillis = delayMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar",
            )
            Box(
                Modifier.width(barWidth).fillMaxHeight(if (playing) v else 0.35f)
                    .clip(PillShape).background(color),
            )
        }
    }
}

/** Pochette assombrie avec l'égaliseur par-dessus (son en cours dans une liste). */
@Composable
fun PlayingOverlay(playing: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
        EqBars(playing = playing, barWidth = 4.dp, height = 18.dp)
    }
}

// ---------------------------------------------------------------- reflet de verre

/** Léger reflet en haut à gauche d'une pochette (dessiné par-dessus). */
fun Modifier.glare(): Modifier = if (com.theo.remote.Features.flags.value.flatStyle) this else this.drawWithContent {
    drawContent()
    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent), start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width * 0.55f, size.height * 0.55f)))
}

// ---------------------------------------------------------------- mosaïque 2×2

/** Pochette en mosaïque des 4 premiers sons, pour une playlist sans image. */
@Composable
fun Mosaic(urls: List<String>, radius: Dp, modifier: Modifier = Modifier) {
    val four = (urls.distinct() + urls).take(4)
    Box(modifier.clip(RoundedCornerShape(radius)).background(Color.White.copy(alpha = 0.07f))) {
        if (four.size >= 4) {
            Column(Modifier.fillMaxSize()) {
                for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (c in 0..1) Cover(model = four[r * 2 + c], radius = 0.dp, modifier = Modifier.weight(1f).fillMaxSize())
                }
            }
        } else if (four.isNotEmpty()) {
            Cover(model = four.first(), radius = radius, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), end = androidx.compose.ui.geometry.Offset(260f, 260f))))
    }
}

/** Pochettes des premiers sons d'une playlist (mémoire, disque, sinon une page Tidal). */
object MosaicCache {
    private val mem = HashMap<String, List<String>>()
    suspend fun get(ctx: android.content.Context, id: String): List<String> {
        mem[id]?.let { return it }
        val fromMem = cachedTracks[id]?.mapNotNull { it.coverUrl }
        val list = fromMem?.takeIf { it.size >= 4 }
            ?: PlaylistDisk.get(ctx, id).mapNotNull { it.coverUrl }.takeIf { it.isNotEmpty() }
            ?: runCatching { TidalApi.playlistPage(id).tracks.mapNotNull { it.coverUrl } }.getOrDefault(emptyList())
        val out = list.distinct().take(4)
        mem[id] = out
        return out
    }
}

// ---------------------------------------------------------------- apparition en cascade

private val cascadeSeen = HashSet<Any>()

/** Fondu + petite montée à la première apparition (une seule fois par élément), décalé selon la position. */
// « Affichage instantané » : pas d'apparition en cascade, tout est là d'un coup
fun Modifier.cascadeIn(index: Int, key: Any): Modifier = if (com.theo.remote.Features.flags.value.flatStyle || com.theo.remote.Features.flags.value.homeCache) this else composed {
    val first = remember(key) { key !in cascadeSeen && index < 14 }
    val a = remember(key) { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(key) {
        cascadeSeen += key
        if (a.value < 1f) {
            delay(index * 12L)
            a.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
    }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 28f }
}

// ---------------------------------------------------------------- cadre en pointillés

fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color = color, cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )
}

// ---------------------------------------------------------------- playlist en cours

/** La dernière playlist lancée depuis Hush (pour l'égaliseur sur sa pochette dans la Biblio). */
object NowPlaylist {
    private val _id = MutableStateFlow<String?>(null)
    val id: StateFlow<String?> = _id
    fun set(id: String?) {
        _id.value = id
        // compte les lancements (panneau latéral : tes playlists les plus jouées)
        if (id != null) {
            val p = PlaylistStore.list.value?.firstOrNull { it.id == id }
            if (p != null) com.theo.remote.PlaylistPlays.bump(p.id, p.name, p.coverUrl)
            else com.theo.remote.PlaylistPlays.known.value.firstOrNull { it.id == id }?.let { com.theo.remote.PlaylistPlays.bump(it.id, it.name, it.cover) }
        }
    }
}

// ---------------------------------------------------------------- écrans vides illustrés

/** Un vinyle qui flotte, un titre, une phrase et un bouton optionnel, à la place d'une ligne de texte. */
@Composable
fun EmptyState(title: String, sub: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val t = rememberInfiniteTransition(label = "float")
    val y by t.animateFloat(0f, -8f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(120.dp).graphicsLayer { translationY = y.dp.toPx() }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { rotationZ = spin }.clip(PillShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF1C1E2A), Color(0xFF2A2D3E), Color(0xFF1C1E2A), Color(0xFF2A2D3E), Color(0xFF15161F))))
                    .drawBehind {
                        for (k in 1..3) drawCircle(Color.White.copy(alpha = 0.06f), radius = size.minDimension * (0.22f + k * 0.08f), style = Stroke(1.dp.toPx()))
                    },
            )
            Box(Modifier.size(34.dp).clip(PillShape).background(Brush.linearGradient(listOf(Ok, lerpColor(Ok, Color.Black, 0.4f)))))
            Box(Modifier.fillMaxSize().clip(PillShape).glare())
        }
        androidx.compose.material3.Text(title, color = Txt, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
        androidx.compose.material3.Text(
            sub, color = Txt2, fontSize = 15.sp, lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (action != null && onAction != null) PillButton(action) { onAction() }
    }
}

private fun lerpColor(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

// ---------------------------------------------------------------- dégradés → aplats

/** Style Pochette : un dégradé devient un aplat ([flat], sinon sa première couleur). */
fun flatOr(
    colors: List<Color>,
    start: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
    end: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Infinite,
    flat: Color? = null,
): Brush = if (com.theo.remote.Features.flags.value.flatStyle) androidx.compose.ui.graphics.SolidColor(flat ?: colors.first())
else Brush.linearGradient(colors, start, end)

/** Surface grise unie du style Pochette. */
val Surface2 = Color(0xFF1C1C1C)

/** Boutons : 10 dp d'arrondi en style Pochette (au lieu de pilules partout). */
val BtnShape: androidx.compose.ui.graphics.Shape
    get() = if (com.theo.remote.Features.flags.value.flatStyle) RoundedCornerShape(12.dp) else PillShape

// ---------------------------------------------------------------- navigation

/** Re-toucher l'onglet actif (remonter en haut) et raccourcis par appui long. */
object NavEvents {
    private val _reselect = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val reselect: kotlinx.coroutines.flow.SharedFlow<String> = _reselect
    fun reselect(tab: String) { _reselect.tryEmit(tab) }

    private val _voice = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val voice: kotlinx.coroutines.flow.SharedFlow<Unit> = _voice
    /** Appui long sur Recherche : on lance la recherche vocale. */
    fun voice() { _voice.tryEmit(Unit) }

    private val _openPlaylist = kotlinx.coroutines.flow.MutableSharedFlow<TidalApi.Playlist>(extraBufferCapacity = 2)
    val openPlaylistEvents: kotlinx.coroutines.flow.SharedFlow<TidalApi.Playlist> = _openPlaylist
    /** Ouvre une playlist depuis n'importe où (« Où est ce son ? »). */
    fun openPlaylist(p: TidalApi.Playlist) { _openPlaylist.tryEmit(p) }
}

// ---------------------------------------------------------------- couleur adaptative

/** Couleur claire tirée de la pochette en cours, pour les onglets et les boutons. */
object CoverTint {
    val color = MutableStateFlow(Color.Unspecified)

    /** La teinte à utiliser maintenant (null = couleur adaptative coupée ou pas encore de pochette). */
    @Composable
    fun current(): Color? {
        val c by color.collectAsState()
        val f by com.theo.remote.Features.flags.collectAsState()
        return if (f.adaptiveColor && c != Color.Unspecified) c else null
    }
}


/**
 * Barre de titre compacte en verre : apparaît en haut quand le gros titre de la page est sorti de l'écran
 * en scrollant (comme dans les applis d'Apple).
 */
@androidx.compose.runtime.Composable
fun CompactTopBar(visible: Boolean, title: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, backdrop: Backdrop? = null) {
    val liquid = backdrop != null && liquidOn()
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { -it / 3 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
        modifier = modifier,
    ) {
        androidx.compose.foundation.layout.Box(
            if (liquid) androidx.compose.ui.Modifier.fillMaxWidth()
            else androidx.compose.ui.Modifier.fillMaxWidth()
                .background(Ink.copy(alpha = 0.72f))
                .glass(androidx.compose.ui.graphics.RectangleShape, fill = 0.06f, edge = 0.10f),
        ) {
        // verre liquide : la barre est une plaque de verre, son bord du bas tord ce qui passe dessous
        if (liquid) {
            LiquidGlass(
                backdrop!!, androidx.compose.ui.graphics.RectangleShape, cornerRadius = 0.dp,
                modifier = androidx.compose.ui.Modifier.matchParentSize(),
                blur = 4.dp, depth = 16.dp, refraction = 12.dp, chroma = 0.25f, spec = 0.9f,
            )
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.matchParentSize().background(Ink.copy(alpha = 0.28f)))
        }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxWidth()
                .statusBarsPadding()
                .height(50.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                title, color = Txt, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 70.dp),
            )
        }
        }
    }
}
