package com.theo.remote.ui

import com.theo.remote.playTitle

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Features
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.SkipTracker
import com.theo.remote.SongCards
import com.theo.remote.SongCards.Tier
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- matières (cadre de la carte)

private class Mat(val frame: Brush, val ink: Color, val glow: Color, val bar: List<Color>)

private fun mat(t: Tier): Mat = when (t) {
    // holographique : rose, cyan, jaune pâle, lilas
    Tier.Diamant -> Mat(
        Brush.linearGradient(
            listOf(Color(0xFFF7B5E3), Color(0xFFE9E6FF), Color(0xFF9FE8F2), Color(0xFFFFF3C4), Color(0xFFF0B9F0), Color(0xFFB9D9FF)),
            start = Offset(0f, 0f), end = Offset(900f, 1200f),
        ),
        Color(0xFF1E1530), Color(0xFFE59BE0), listOf(Color(0xFFF7B5E3), Color(0xFF9FE8F2)),
    )
    Tier.Or -> Mat(
        Brush.linearGradient(
            listOf(Color(0xFFFFE9A6), Color(0xFFE3B347), Color(0xFFB8861F), Color(0xFFF6D57A), Color(0xFFC9962C)),
            start = Offset(0f, 0f), end = Offset(700f, 1000f),
        ),
        Color(0xFF2A1A02), Color(0xFFE6AA32), listOf(Color(0xFFC98D24), Color(0xFFFFE39A)),
    )
    Tier.Argent -> Mat(
        Brush.linearGradient(
            listOf(Color(0xFFF4F6FA), Color(0xFFB8BFCA), Color(0xFF8D96A4), Color(0xFFDCE1E8), Color(0xFFA9B1BD)),
            start = Offset(0f, 0f), end = Offset(700f, 1000f),
        ),
        Color(0xFF151A22), Color(0xFFC2CAD6), listOf(Color(0xFF8F98A5), Color(0xFFE6E9ED)),
    )
    Tier.Bronze -> Mat(
        Brush.linearGradient(
            listOf(Color(0xFFF0BE98), Color(0xFFC07646), Color(0xFF8A4B2A), Color(0xFFDDA077), Color(0xFFA9603A)),
            start = Offset(0f, 0f), end = Offset(700f, 1000f),
        ),
        Color(0xFF2A1205), Color(0xFFC27A47), listOf(Color(0xFFC27A47), Color(0xFFE2A57C)),
    )
}

/** Petit losange de la couleur d'un rang. */
@Composable
private fun TierDot(t: Tier, size: Dp = 16.dp) {
    Box(Modifier.size(size).rotate(45f).clip(RoundedCornerShape(size / 3)).background(mat(t).frame))
}

/** Halo doux (dégradé radial : pas de bord carré comme avec un flou). */
@Composable
private fun Halo(color: Color, modifier: Modifier, alpha: Float = 0.5f) {
    Box(modifier.background(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent))))
}

// ---------------------------------------------------------------- inclinaison du téléphone (reflet holo)

/** Inclinaison (-1..1 sur x et y) : le reflet des cartes brillantes la suit. */
val LocalTilt = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Offset>> { mutableStateOf(Offset.Zero) }

@Composable
private fun rememberTilt(enabled: Boolean): androidx.compose.runtime.State<Offset> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st = remember { mutableStateOf(Offset.Zero) }
    androidx.compose.runtime.DisposableEffect(enabled) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val x = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
                // téléphone tenu un peu penché (~35°) = position neutre
                val y = ((e.values[1] / 9.81f) - 0.6f).coerceIn(-1f, 1f)
                val o = st.value
                st.value = Offset(o.x + (x - o.x) * 0.18f, o.y + (y - o.y) * 0.18f)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (enabled && sensor != null) sm?.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(l) }
    }
    return st
}

/** Angle qui tourne en boucle (cadre animé des cartes rares) : un seul pour toute la page. */
val LocalSpin = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Float>> { mutableStateOf(0f) }

@Composable
private fun CardEffects(content: @Composable () -> Unit) {
    val f by Features.flags.collectAsState()
    val tilt = rememberTilt(f.cardHolo && !f.eco)
    val spin = rememberInfiniteTransition(label = "rare").animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    androidx.compose.runtime.CompositionLocalProvider(LocalTilt provides tilt, LocalSpin provides spin) { content() }
}

private val rainbow = listOf(
    Color(0xFFFF8AD8), Color(0xFFFFE27A), Color(0xFF8CFFC9), Color(0xFF7FD8FF), Color(0xFFB79CFF), Color(0xFFFF8AD8),
)

// ---------------------------------------------------------------- la carte (façon carte à collectionner)

/**
 * Une carte : cadre en métal (ou holographique), pochette, numéro, plaque avec le titre, et le rang en bas.
 * Tout est proportionnel à la largeur, pour la même carte en petit dans la grille et en grand.
 */
@Composable
private fun SongCard(c: SongCards.Card, modifier: Modifier = Modifier, back: Boolean = false) {
    val f by Features.flags.collectAsState()
    val tiltState = LocalTilt.current
    val spinState = LocalSpin.current
    val special = f.cardSpecial && c.special != null
    val shiny = f.cardHolo && (c.tier >= Tier.Or || special)
    BoxWithConstraints(modifier.aspectRatio(0.74f)) {
        val w = maxWidth
        val u = w.value / 100f // une « unité » = 1 % de la largeur
        fun d(x: Float) = (x * u).dp
        fun s(x: Float): TextUnit = (x * u).sp
        val m = mat(c.tier)
        val shape = RoundedCornerShape(d(6f))
        Box(
            Modifier.fillMaxSize()
                .shadow(d(4f), shape, ambientColor = if (special) Color(0xFFFF8AD8) else m.glow, spotColor = if (special) Color(0xFFFF8AD8) else m.glow)
                .clip(shape),
        ) {
            // cartes rares : un anneau arc-en-ciel qui tourne autour du cadre
            if (special) {
                Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = spinState.value; scaleX = 1.6f; scaleY = 1.6f }.background(Brush.sweepGradient(rainbow)))
            }
            Box(
                Modifier.fillMaxSize().padding(if (special) d(1.3f) else 0.dp).clip(shape)
                    .background(m.frame)
                    .border(d(0.6f), Color.White.copy(alpha = 0.75f), shape)
                    .drawWithContent {
                        drawContent()
                        // reflet : il glisse avec l'inclinaison du téléphone sur les cartes brillantes
                        val t = if (shiny) tiltState.value else Offset.Zero
                        val cx = size.width * (0.5f + t.x * 0.9f)
                        val cy = size.height * (0.5f + t.y * 0.9f)
                        val band = size.width * 0.55f
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = if (shiny) 0.42f else 0.22f), Color.Transparent),
                                start = Offset(cx - band, cy - band), end = Offset(cx + band, cy + band),
                            ),
                        )
                        // holo : un voile arc-en-ciel qui change avec l'inclinaison (Diamant et cartes rares)
                        if (shiny && (c.tier == Tier.Diamant || special)) {
                            drawRect(
                                Brush.linearGradient(rainbow, start = Offset(-size.width * (1f + t.x), -size.height * t.y), end = Offset(size.width * (2f - t.x), size.height * (1.5f - t.y))),
                                alpha = 0.22f, blendMode = androidx.compose.ui.graphics.BlendMode.Overlay,
                            )
                        }
                    },
            ) {
                Column(Modifier.fillMaxSize().padding(d(2.6f)), verticalArrangement = Arrangement.spacedBy(d(2f))) {
                    val inner = RoundedCornerShape(d(4f))
                    if (!back) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(inner).border(d(0.5f), Color.Black.copy(alpha = 0.25f), inner)) {
                            Cover(model = c.cover, radius = d(4f), modifier = Modifier.fillMaxSize())
                            Row(
                                Modifier.align(Alignment.TopEnd).padding(d(2.4f)).clip(RoundedCornerShape(d(1.8f))).background(Color(0xE61C1D22)).padding(horizontal = d(2f), vertical = d(0.8f)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("N°", color = Color.White.copy(alpha = 0.6f), fontSize = s(3.2f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(d(0.8f)))
                                Text("%03d".format(c.number), color = Color.White, fontSize = s(5f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                            }
                            // en danger : un sablier orange en haut à gauche
                            if (f.cardDanger && c.dangerDays != null) {
                                Box(
                                    Modifier.align(Alignment.TopStart).padding(d(2.4f)).size(d(9f)).clip(PillShape).background(Color(0xFFFF8A3D)),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.HourglassBottom, contentDescription = "Carte en danger", tint = Color.Black, modifier = Modifier.size(d(6f))) }
                            }
                            if (special) {
                                Text(
                                    c.special!!, color = Color.White, fontSize = s(3.6f), fontWeight = FontWeight.Black, letterSpacing = s(0.6f), maxLines = 1,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(d(2.4f)).clip(RoundedCornerShape(d(1.8f)))
                                        .background(Brush.linearGradient(listOf(Color(0xFFB04BD6), Color(0xFFFF6FA8)))).padding(horizontal = d(2f), vertical = d(0.8f)),
                                )
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().weight(1f).clip(inner).background(Color(0xEB24252B)).padding(horizontal = d(4f), vertical = d(2f)),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                c.title.uppercase(), color = Color.White, fontSize = s(7.4f), lineHeight = s(7.6f), fontWeight = FontWeight.Black,
                                letterSpacing = s(-0.25f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(c.artist, color = Color.White.copy(alpha = 0.7f), fontSize = s(4.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        CardBack(c, Modifier.fillMaxWidth().weight(1f).clip(inner).background(Color(0xF024252B)), ::d, ::s)
                    }
                    // le rang, lettres espacées, et les points (où en est la carte dans son rang)
                    Row(Modifier.fillMaxWidth().height(d(7f)), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(c.tier.label.uppercase(), color = m.ink, fontSize = s(4.6f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = s(1.6f))
                        Spacer(Modifier.width(d(2.5f)))
                        repeat(c.dots) {
                            Box(Modifier.padding(horizontal = d(0.5f)).size(d(2.4f)).clip(PillShape).background(m.ink))
                        }
                    }
                }
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("d MMM yy", Locale.FRENCH)

/** Le dos de la carte : ses stats. */
@Composable
private fun CardBack(c: SongCards.Card, modifier: Modifier, d: (Float) -> Dp, s: (Float) -> TextUnit) {
    Column(modifier.padding(d(5f)), verticalArrangement = Arrangement.spacedBy(d(3f))) {
        Text(c.title.uppercase(), color = Color.White, fontSize = s(6.6f), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${c.score}", color = Color.White, fontSize = s(16f), lineHeight = s(16f), fontWeight = FontWeight.Black)
            Spacer(Modifier.width(d(2f)))
            Text("points", color = Color.White.copy(alpha = 0.6f), fontSize = s(4.4f), modifier = Modifier.padding(bottom = d(2f)))
        }
        @Composable fun line(k: String, v: String) {
            Row(Modifier.fillMaxWidth()) {
                Text(k, color = Color.White.copy(alpha = 0.65f), fontSize = s(4.6f), modifier = Modifier.weight(1f))
                Text(v, color = Color.White, fontSize = s(4.6f), fontWeight = FontWeight.Bold)
            }
        }
        line("Écoutes (3 mois)", "${c.plays}")
        line("Fois passé", "${c.skips}")
        line("Temps d'écoute", fmtTotal(c.totalMs))
        Spacer(Modifier.height(d(1f)))
        Tier.entries.forEach { t ->
            val at = c.reached[t] ?: 0L
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(d(3f)).rotate(45f).clip(RoundedCornerShape(d(0.8f))).background(if (at > 0) mat(t).frame else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.15f)))))
                Spacer(Modifier.width(d(2.5f)))
                Text(t.label, color = if (at > 0) Color.White else Color.White.copy(alpha = 0.4f), fontSize = s(4.6f), modifier = Modifier.weight(1f))
                Text(if (at > 0) dateFmt.format(Date(at)) else "—", color = Color.White.copy(alpha = 0.65f), fontSize = s(4.2f))
            }
        }
        Spacer(Modifier.weight(1f))
        val next = c.next
        Text(
            when {
                next == null -> "Au sommet. Elle redescend si tu ne l'écoutes pas pendant 2 mois."
                next == Tier.Diamant && c.score >= next.min -> "Points atteints : Diamant si tu ne la passes pas pendant un mois."
                else -> "Encore ${next.min - c.score} point${if (next.min - c.score > 1) "s" else ""} pour passer en ${next.label}."
            },
            color = Color.White.copy(alpha = 0.75f), fontSize = s(4.2f), lineHeight = s(5.6f),
        )
    }
}

private fun fmtTotal(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
}

// ---------------------------------------------------------------- calcul en arrière-plan

/** Garde les cartes à jour (et prépare l'annonce quand une carte monte). */
@Composable
private fun CardsUpdater() {
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val skips by SkipTracker.skips.collectAsState()
    val f by Features.flags.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { SongCards.init(ctx); SkipTracker.init(ctx) }
    LaunchedEffect(entries, scrobbles, skips, f.cardPromo) {
        delay(1200)
        withContext(Dispatchers.Default) { SongCards.compute(buildEvents(entries, scrobbles), skips, announce = f.cardPromo) }
        // les pochettes qui manquent, cherchées sur Tidal
        SongCards.resolveCovers()
    }
}

object CardsState {
    var open by mutableStateOf<SongCards.Card?>(null)
}

// ---------------------------------------------------------------- onglet Cartes

private enum class CardSort(val label: String) { Rang("Rang"), Points("Points"), Artiste("Artiste"), Montee("Montée récente") }

private fun norm(x: String) = x.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

@Composable
fun CardsScreen() = CardEffects {
    val cards by SongCards.cards.collectAsState()
    val books by SongCards.artists.collectAsState()
    val f by Features.flags.collectAsState()
    var filter by remember { mutableStateOf<Tier?>(null) }
    var sort by remember { mutableStateOf(CardSort.Rang) }
    var query by remember { mutableStateOf("") }
    var artistsMode by remember { mutableStateOf(false) }
    var artist by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { NavEvents.reselect.collect { if (it == "Cards") list.animateScrollToItem(0) } }
    BackHandler(enabled = artist != null) { artist = null }
    val byTier = remember(cards) { cards.groupBy { it.tier } }
    val topTier = cards.firstOrNull()?.tier ?: Tier.Diamant
    // la liste affichée : recherche, artiste choisi, rang filtré, puis le tri
    val shown = remember(cards, filter, sort, query, artist) {
        val q = norm(query)
        cards.filter { c ->
            (filter == null || c.tier == filter) &&
                (artist == null || norm(c.mainArtist) == norm(artist!!)) &&
                (q.isEmpty() || norm(c.title).contains(q) || norm(c.artist).contains(q))
        }.let { l ->
            when (sort) {
                CardSort.Rang -> l
                CardSort.Points -> l.sortedByDescending { it.score }
                CardSort.Artiste -> l.sortedWith(compareBy<SongCards.Card> { it.mainArtist.lowercase() }.thenByDescending { it.score })
                CardSort.Montee -> l.sortedByDescending { c -> c.reached.values.maxOrNull() ?: 0L }
            }
        }
    }
    val sections = sort == CardSort.Rang && query.isBlank() && artist == null
    val danger = if (f.cardDanger) cards.filter { it.dangerDays != null }.sortedBy { it.dangerDays } else emptyList()
    Box(Modifier.fillMaxSize()) {
        Halo(mat(topTier).glow, Modifier.fillMaxWidth().height(520.dp).graphicsLayer { translationY = -120.dp.toPx() }, alpha = 0.35f)
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 190.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text("${cards.size} son${if (cards.size > 1) "s" else ""} classé${if (cards.size > 1) "s" else ""}", color = Txt2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Cartes", color = Txt, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                }
            }
            // recherche
            item {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(Rounded(24)).background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = Txt2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Chercher une carte ou un artiste", color = Txt3, fontSize = 15.sp)
                        androidx.compose.foundation.text.BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 15.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) Icon(Icons.Rounded.Close, contentDescription = "Effacer", tint = Txt2, modifier = Modifier.size(20.dp).clip(PillShape).clickable { query = "" })
                }
            }
            // cartes / classeur par artiste, puis le tri
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { SmallChip("Cartes", !artistsMode) { artistsMode = false } }
                    item { SmallChip("Artistes", artistsMode) { artistsMode = true; artist = null } }
                    if (!artistsMode) {
                        item { Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(34.dp).background(Color.White.copy(alpha = 0.15f))) }
                        CardSort.entries.forEach { o -> item { SmallChip(o.label, sort == o) { sort = o } } }
                    }
                }
            }
            if (artistsMode) {
                if (books.isEmpty()) item { Text("Pas encore d'artiste avec des cartes.", color = Txt2, fontSize = 14.sp) }
                items(books.size, key = { "b" + books[it].name }) { i ->
                    val b = books[i]
                    ArtistBookRow(b) { artist = b.name; artistsMode = false; sort = CardSort.Rang }
                }
                return@LazyColumn
            }
            artist?.let { a ->
                item {
                    val b = books.firstOrNull { norm(it.name) == norm(a) }
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassCircle(Icons.Rounded.ChevronLeft, "Retour") { artist = null }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a, color = Txt, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (b != null) Text("${b.cards.size} carte${if (b.cards.size > 1) "s" else ""} sur ${b.heard} sons écoutés", color = Txt2, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (sections) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tier.entries.reversed().forEach { t ->
                            val on = filter == t
                            Column(
                                Modifier.weight(1f).height(76.dp).clip(Rounded(18))
                                    .background(if (on) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.07f))
                                    .bounceClick { filter = if (on) null else t },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                TierDot(t)
                                Spacer(Modifier.height(6.dp))
                                Text("${byTier[t]?.size ?: 0}", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Black)
                                Text(t.label, color = Txt2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                // cartes en danger : à réécouter avant qu'elles redescendent
                if (danger.isNotEmpty() && filter == null) {
                    item(key = "danger") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                                Text("À sauver", color = Txt, fontSize = 21.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                                Text("redescendent bientôt", color = Color(0xFFFF8A3D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(danger.size, key = { "d" + danger[it].key }) { i ->
                                    SongCard(danger[i], Modifier.width(112.dp).bounceClick(pressedScale = 0.95f, onLongClick = { danger[i].let { c -> TrackMenu.open(TrackMenu.Target(c.title, c.artist, c.cover)) } }) { CardsState.open = danger[i] })
                                }
                            }
                        }
                    }
                }
            }
            if (cards.isEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Text("Pas encore de carte", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("Un son devient une carte Bronze après 3 écoutes. Plus tu l'écoutes, plus il monte : Argent à 8, Or à 15, Diamant à 30.", color = Txt2, fontSize = 14.sp, lineHeight = 20.sp)
                }
            } else if (shown.isEmpty()) {
                item { Text("Aucune carte ne correspond.", color = Txt2, fontSize = 14.sp, modifier = Modifier.padding(4.dp)) }
            }
            if (sections) {
                for (t in Tier.entries.reversed()) {
                    if (filter != null && filter != t) continue
                    val l = byTier[t].orEmpty()
                    if (l.isEmpty()) continue
                    item(key = "h$t") {
                        Row(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Text(t.label, color = Txt, fontSize = 21.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                            Text("${l.size} son${if (l.size > 1) "s" else ""}", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    cardRows(l, "$t")
                }
            } else {
                cardRows(shown, "flat")
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.cardRows(l: List<SongCards.Card>, tag: String) {
    val rows = l.chunked(3)
    items(rows.size, key = { "$tag-$it-" + rows[it].first().key }) { i ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rows[i].forEach { c -> SongCard(c, Modifier.weight(1f).bounceClick(pressedScale = 0.95f, onLongClick = { TrackMenu.open(TrackMenu.Target(c.title, c.artist, c.cover)) }) { CardsState.open = c }) }
            repeat(3 - rows[i].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SmallChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(Rounded(17)).background(if (on) Color.White else Color.White.copy(alpha = 0.1f)).bounceClick(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Color.Black else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/** Une ligne du classeur : l'artiste, ses meilleures pochettes en éventail, et sa progression. */
@Composable
private fun ArtistBookRow(b: SongCards.ArtistBook, onClick: () -> Unit) {
    val best = b.cards.maxByOrNull { it.tier.ordinal }?.tier ?: Tier.Bronze
    val frac = (b.cards.size.toFloat() / b.heard.coerceAtLeast(1)).coerceIn(0f, 1f)
    Row(
        Modifier.fillMaxWidth().clip(Rounded(20)).background(Color.White.copy(alpha = 0.07f)).bounceClick(pressedScale = 0.98f, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(86.dp).height(64.dp)) {
            b.cards.take(3).reversed().forEachIndexed { i, c ->
                val k = b.cards.take(3).size - 1 - i
                Cover(
                    model = c.cover, radius = 8.dp,
                    modifier = Modifier.size(52.dp).graphicsLayer { translationX = (k * 14).dp.toPx(); rotationZ = (k - 1) * 6f; translationY = 6.dp.toPx() }
                        .border(1.5.dp, Brush.linearGradient(mat(c.tier).bar), RoundedCornerShape(8.dp)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.name, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                TierDot(best, 11.dp)
            }
            Text("${b.cards.size} carte${if (b.cards.size > 1) "s" else ""} sur ${b.heard} son${if (b.heard > 1) "s" else ""} écouté${if (b.heard > 1) "s" else ""}", color = Txt2, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(mat(best).bar)))
            }
        }
    }
}

// ---------------------------------------------------------------- le rang d'un son ailleurs dans Hush

/** Losange du rang à côté d'un son dans les listes (rien s'il n'a pas de carte). */
@Composable
fun CardRankBadge(title: String, artist: String) {
    val f by Features.flags.collectAsState()
    if (!f.songCards || !f.cardBadges) return
    val cards by SongCards.cards.collectAsState()
    val c = remember(cards, title, artist) { SongCards.find(title, artist) } ?: return
    Box(Modifier.padding(horizontal = 4.dp)) { TierDot(c.tier, 11.dp) }
}

/** Dans le lecteur : le losange du rang à côté du titre ; le toucher ouvre la carte. */
@Composable
fun PlayerCardGem(title: String, artist: String) {
    val f by Features.flags.collectAsState()
    if (!f.songCards || !f.cardBadges) return
    val cards by SongCards.cards.collectAsState()
    val c = remember(cards, title, artist) { SongCards.find(title, artist) } ?: return
    Box(
        Modifier.padding(start = 8.dp).size(38.dp).clip(PillShape).bounceClick { CardsState.open = c },
        contentAlignment = Alignment.Center,
    ) { TierDot(c.tier, 15.dp) }
}

// ---------------------------------------------------------------- hôte (carte en grand + annonce), au-dessus de tout

@Composable
fun CardsHost() {
    val f by Features.flags.collectAsState()
    if (!f.songCards) return
    CardsUpdater()
    val c = CardsState.open
    AnimatedVisibility(
        visible = c != null,
        enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 300f), initialScale = 0.85f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.92f),
    ) {
        val last = remember { arrayOfNulls<SongCards.Card>(1) }
        if (c != null) last[0] = c
        last[0]?.let { CardEffects { CardViewer(it) } }
    }
    val promo by SongCards.promo.collectAsState()
    promo?.let { if (f.cardPromo) CardEffects { CardPromo(it) } else LaunchedEffect(it) { SongCards.dismissPromo() } }
}

/**
 * Une carte en grand, comme sortie du classeur : elle penche sous le doigt (3D), les flèches passent à la suivante,
 * « Retourner » montre ses stats.
 */
@Composable
private fun CardViewer(c: SongCards.Card) {
    val cards by SongCards.cards.collectAsState()
    val f by Features.flags.collectAsState()
    BackHandler { CardsState.open = null }
    var back by remember(c.key) { mutableStateOf(false) }
    val flip by animateFloatAsState(if (back) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = 260f), label = "flip")
    val i = cards.indexOfFirst { it.key == c.key }
    val m = mat(c.tier)
    val density = LocalDensity.current
    // inclinaison au doigt : la carte penche vers où tu glisses, puis revient
    val rx = remember(c.key) { Animatable(0f) }
    val ry = remember(c.key) { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val phone = LocalTilt.current
    // le reflet suit à la fois le téléphone et le doigt
    val mixed = remember(c.key, phone) { androidx.compose.runtime.derivedStateOf { Offset(phone.value.x + ry.value / 22f, phone.value.y - rx.value / 22f) } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
            .clickable(remember { MutableInteractionSource() }, null) { CardsState.open = null },
    ) {
        Halo(if (f.cardSpecial && c.special != null) Color(0xFFFF8AD8) else m.glow, Modifier.align(Alignment.Center).fillMaxWidth().height(620.dp), alpha = 0.55f)
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
            GlassCircle(Icons.Rounded.Close, "Fermer") { CardsState.open = null }
        }
        Box(
            Modifier.align(Alignment.Center).padding(bottom = 60.dp).fillMaxWidth(0.82f)
                .graphicsLayer { rotationY = flip + ry.value; rotationX = rx.value; cameraDistance = 14f * density.density }
                .pointerInput(c.key, f.cardHolo) {
                    if (!f.cardHolo) return@pointerInput
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch { rx.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 220f)) }
                            scope.launch { ry.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 220f)) }
                        },
                        onDragCancel = {
                            scope.launch { rx.animateTo(0f) }
                            scope.launch { ry.animateTo(0f) }
                        },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { ry.snapTo((ry.value + drag.x / 9f).coerceIn(-22f, 22f)) }
                        scope.launch { rx.snapTo((rx.value - drag.y / 9f).coerceIn(-22f, 22f)) }
                    }
                }
                .clickable(remember { MutableInteractionSource() }, null) { back = !back },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTilt provides mixed) {
                if (flip <= 90f) SongCard(c, Modifier.fillMaxWidth())
                else SongCard(c, Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f }, back = true)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // en danger : dans combien de jours, et de quoi la relancer
            if (f.cardDanger && c.dangerDays != null) {
                Row(
                    Modifier.fillMaxWidth().clip(Rounded(16)).background(Color(0x33FF8A3D)).border(1.dp, Color(0x88FF8A3D), Rounded(16)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.HourglassBottom, contentDescription = null, tint = Color(0xFFFF8A3D), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    val d = c.dangerDays
                    Text(
                        (if (c.dangerTo != null) "Redescend en ${c.dangerTo.label}" else "Perd sa carte") + if (d <= 0) " aujourd'hui" else " dans $d jour${if (d > 1) "s" else ""}",
                        color = Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF8A3D)).bounceClick { TidalRemote.playTitle(c.title, c.artist) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("RELANCER", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (i > 0) GlassCircle(Icons.Rounded.ChevronLeft, "Carte précédente") { CardsState.open = cards[i - 1] } else Spacer(Modifier.size(44.dp))
                if (i in 0 until cards.size - 1) GlassCircle(Icons.Rounded.ChevronRight, "Carte suivante") { CardsState.open = cards[i + 1] } else Spacer(Modifier.size(44.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(54.dp).clip(Rounded(16)).background(Color.White.copy(alpha = 0.12f)).border(1.dp, Color.White.copy(alpha = 0.18f), Rounded(16)).bounceClick { back = !back },
                    contentAlignment = Alignment.Center,
                ) { Text(if (back) "FACE" else "RETOURNER", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
                Row(
                    Modifier.weight(1f).height(54.dp).clip(Rounded(16)).background(Color.White).bounceClick { TidalRemote.playTitle(c.title, c.artist) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(4.dp))
                    Text("LIRE", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- annonce « Nouvelle carte »

@Composable
private fun CardPromo(p: SongCards.Promo) {
    val cards by SongCards.cards.collectAsState()
    val c = cards.firstOrNull { it.key == p.card.key } ?: p.card
    val m = mat(c.tier)
    BackHandler { SongCards.dismissPromo() }
    val haptic = rememberHaptic(strong = true)
    val pop = remember(c.key, c.tier) { Animatable(0f) }
    LaunchedEffect(c.key, c.tier) { haptic(); pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 180f)) }
    val spin by rememberInfiniteTransition(label = "rays").animateFloat(0f, 360f, infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    Box(Modifier.fillMaxSize().background(Color(0xFF040507)).clickable(remember { MutableInteractionSource() }, null) {}) {
        // rayons de lumière qui tournent doucement derrière la carte
        Canvas(Modifier.fillMaxSize()) {
            val ctr = Offset(size.width / 2, size.height * 0.42f)
            for (k in 0 until 20) {
                val a = Math.toRadians((spin + k * 18f).toDouble())
                val a2 = Math.toRadians((spin + k * 18f + 6f).toDouble())
                val r = size.height
                val path = Path().apply {
                    moveTo(ctr.x, ctr.y)
                    lineTo(ctr.x + (r * kotlin.math.cos(a)).toFloat(), ctr.y + (r * kotlin.math.sin(a)).toFloat())
                    lineTo(ctr.x + (r * kotlin.math.cos(a2)).toFloat(), ctr.y + (r * kotlin.math.sin(a2)).toFloat())
                    close()
                }
                drawPath(path, Brush.radialGradient(listOf(m.glow.copy(alpha = 0.25f), Color.Transparent), center = ctr, radius = size.height * 0.5f))
            }
        }
        Halo(m.glow, Modifier.align(Alignment.Center).fillMaxWidth().height(560.dp), alpha = 0.5f)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("NOUVELLE CARTE", color = Txt.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(0.78f).graphicsLayer { scaleX = 0.6f + 0.4f * pop.value; scaleY = scaleX; alpha = pop.value.coerceIn(0f, 1f); rotationZ = 6f * (1f - pop.value) }) {
                SongCard(c, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(20.dp))
            Text("${c.title} passe en ${c.tier.label}", color = Txt, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                if (c.tier == Tier.Diamant) "${c.plays} écoutes, et pas passé une seule fois ce mois-ci." else "${c.plays} écoutes ces 3 derniers mois.",
                color = Txt2, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(54.dp).clip(Rounded(16)).background(Color.White.copy(alpha = 0.12f)).bounceClick { SongCards.dismissPromo() }, contentAlignment = Alignment.Center) {
                    Text("CONTINUER", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
                Box(Modifier.weight(1f).height(54.dp).clip(Rounded(16)).background(Color.White).bounceClick { SongCards.dismissPromo(); CardsState.open = c }, contentAlignment = Alignment.Center) {
                    Text("VOIR LA CARTE", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
    }
}
