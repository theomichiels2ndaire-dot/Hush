package com.theo.remote.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.Lyrics
import com.theo.remote.TidalRemote

@Composable
fun LyricsScreen(s: TidalRemote.State, onQueue: (() -> Unit)? = null, onClose: () -> Unit) {
    val t = s.track
    val pos = rememberLivePosition(s)
    val likes by History.likes.collectAsState()
    val liked = t != null && likes.contains(History.keyOf(t.title, t.artist))

    var lyrics by remember(t?.title, t?.artist) { mutableStateOf<Lyrics.Result?>(null) }
    var loading by remember(t?.title, t?.artist) { mutableStateOf(true) }
    LaunchedEffect(t?.title, t?.artist) {
        loading = true
        lyrics = t?.let { Lyrics.fetch(it.title, it.artist, it.album, it.durationMs) }
        loading = false
    }

    // traduction (option activable dans les Réglages)
    val features by com.theo.remote.Features.flags.collectAsState()
    val showTr by com.theo.remote.LyricsTranslate.show.collectAsState()
    val wantTr = features.translate && showTr
    var tr by remember(t?.title, t?.artist) { mutableStateOf<com.theo.remote.LyricsTranslate.Result?>(null) }
    LaunchedEffect(wantTr, lyrics) {
        val l = lyrics
        if (!wantTr || l == null || l.isEmpty || t == null) return@LaunchedEffect
        tr = com.theo.remote.LyricsTranslate.Result.Loading
        val lines = l.synced?.map { it.text } ?: l.plain.orEmpty().lines()
        tr = com.theo.remote.LyricsTranslate.translate("${t.title}|${t.artist}", lines)
    }
    val translated = (tr as? com.theo.remote.LyricsTranslate.Result.Done)?.lines?.takeIf { wantTr }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // en-tête compact
        Column(Modifier.padding(horizontal = 24.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 72.dp, height = 44.dp)
                    .clip(PillShape)
                    .bounceClick(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Réduire les paroles", tint = Txt.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(bitmap = t?.art, radius = 8.dp, modifier = Modifier.size(56.dp).shadow(10.dp, RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t?.title ?: "—", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t?.artist ?: "", color = Txt.copy(alpha = 0.62f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (features.translate) {
                    Box(
                        Modifier.size(44.dp).clip(PillShape)
                            .background(if (showTr) Txt else Color.Transparent)
                            .bounceClick { com.theo.remote.LyricsTranslate.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Translate, contentDescription = "Traduire les paroles", tint = if (showTr) Ink else Txt, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // 2.0 : la file d'attente en un tap depuis les paroles
                if (onQueue != null) {
                    Box(
                        Modifier.size(44.dp).clip(PillShape).bounceClick { onQueue() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "File d'attente", tint = Txt, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                HeartButton(s.tidalFavorite ?: liked) { t?.let { toggleHeart(s, it) } }
            }
            if (wantTr) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when (val r = tr) {
                        is com.theo.remote.LyricsTranslate.Result.Loading -> "Traduction… (la 1re fois, un petit téléchargement)"
                        is com.theo.remote.LyricsTranslate.Result.AlreadyFrench -> "Déjà en français"
                        is com.theo.remote.LyricsTranslate.Result.Done -> if (r.from.first() in "aeiouy") "Traduit de l'${r.from}" else "Traduit du ${r.from}"
                        is com.theo.remote.LyricsTranslate.Result.Failed -> r.message
                        null -> ""
                    },
                    color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // paroles en plein écran ; les commandes flottent par-dessus dans une plaque de verre
        val lyrGlass = rememberBackdrop()
        val liquid = liquidOn()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // paroles, avec un fondu en haut et en bas
            Box(
                Modifier
                    .fillMaxSize()
                    .backdropSource(lyrGlass)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent, 0.08f to Color.Black, 1f to Color.Black,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                val l = lyrics
                when {
                    loading -> Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp)) { SkeletonLines(6) }
                    l == null || l.isEmpty -> Message("Pas de paroles trouvées pour ce son.")
                    l.synced != null -> SyncedLyrics(l.synced, pos, translated, onSave = if (features.quotes && t != null) { line: Lyrics.Line -> com.theo.remote.LyricQuotes.add(line.text, t.title, t.artist, line.timeMs) } else null)
                    else -> PlainLyrics(l.plain.orEmpty(), translated)
                }
            }
            val panel = Rounded(30)
            Box(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
                    .shadow(18.dp, panel).clip(panel),
            ) {
                if (liquid) {
                    LiquidGlass(lyrGlass, panel, cornerRadius = 30.dp, modifier = Modifier.matchParentSize(), blur = 3.dp, depth = 22.dp, refraction = 16.dp, chroma = 0.25f)
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.22f)))
                } else {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)).glass(panel, fill = 0.08f, edge = 0.12f))
                }
                // commandes dans une plaque de verre : les paroles défilent dessous et se tordent sur ses bords
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    val duration = t?.durationMs ?: 0L
                    var dragPos by remember { mutableStateOf<Float?>(null) }
                    ThickBar(
                        value = dragPos ?: if (duration > 0) pos.toFloat() / duration else 0f,
                        onChange = { dragPos = it },
                        onFinished = { v -> TidalRemote.seekTo((v * duration).toLong()); dragPos = null },
                        enabled = duration > 0,
                    )
                    val shown = dragPos?.let { (it * duration).toLong() } ?: pos
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(shown), color = Txt.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("-" + fmt(duration - shown), color = Txt.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BigButton(Icons.Rounded.FastRewind, "Précédent", 36.dp) { TidalRemote.previous() }
                        PlayPauseButton(s.isPlaying, 48.dp)
                        BigButton(Icons.Rounded.FastForward, "Suivant", 36.dp) { TidalRemote.next() }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShuffleButton(s.shuffle)
                        LyricsPill(active = true, onClick = onClose)
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Txt.copy(alpha = 0.4f), modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = Txt.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<Lyrics.Line>, pos: Long, translated: List<String>? = null, onSave: ((Lyrics.Line) -> Unit)? = null,
    fontSize: Int = 34, pad: PaddingValues = PaddingValues(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 320.dp), dismissOnTop: Boolean = true,
    centered: Boolean = false,
) {
    // petite avance pour que la ligne s'allume pile quand elle est chantée
    val raw = lines.indexOfLast { it.timeMs <= pos + 250 }
    // anti-yoyo : une petite correction de position vers l'arrière ne fait pas remonter la ligne
    var current by remember(lines) { mutableIntStateOf(-1) }
    if (raw > current || (raw < current && (current < 0 || pos < lines[current].timeMs - 1200))) current = raw
    val list = rememberLazyListState()
    if (dismissOnTop) DismissWhenAtTop(list)

    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        // centré : la ligne chantée se place au milieu de la zone, sinon juste sous la ligne d'avant
        if (centered) list.animateScrollToItem(current, -(list.layoutInfo.viewportSize.height / 3))
        else list.animateScrollToItem((current - 1).coerceAtLeast(0))
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = pad,
        verticalArrangement = Arrangement.spacedBy((fontSize * 0.6f).dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val d = i - current
            // façon Apple Music : la ligne chantée grossit, les autres s'estompent et se floutent avec la distance
            val dist = kotlin.math.abs(d)
            val target = when {
                d == 0 -> Color.White
                d < 0 -> Color.White.copy(alpha = 0.32f)
                else -> Color.White.copy(alpha = (0.52f - 0.07f * dist).coerceAtLeast(0.22f))
            }
            val color by animateColorAsState(target, tween(350), label = "line")
            val scale by animateFloatAsState(if (d == 0) 1.04f else 0.94f, spring(dampingRatio = 0.65f, stiffness = 260f), label = "lineScale")
            val blurPx by animateFloatAsState(if (d == 0 || current < 0) 0f else (dist.coerceAtMost(4) * 0.9f), tween(450), label = "lineBlur")
            val blur = blurPx.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (centered) 0.5f else 0f, 0.5f)
                    }
                    .blur(blur)
                    .clip(RoundedCornerShape(8.dp))
                    .bounceClick(pressedScale = 0.96f, onLongClick = onSave?.let { f -> { if (line.text.isNotBlank()) f(line) } }) { TidalRemote.seekTo(line.timeMs) },
            ) {
                Text(
                    line.text.ifBlank { "♪" }, color = color, fontSize = fontSize.sp, lineHeight = (fontSize * 1.15f).sp, fontWeight = FontWeight.Black,
                    textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null,
                    modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
                )
                translated?.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { tl ->
                    Spacer(Modifier.height(4.dp))
                    Text(tl, color = color.copy(alpha = color.alpha * 0.75f), fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PlainLyrics(text: String, translated: List<String>? = null) {
    val scroll = rememberScrollState()
    DismissWhenAtTop(scroll)
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 28.dp, vertical = 40.dp),
    ) {
        Text("Paroles non synchronisées", color = Txt.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (translated == null) {
            Text(text, color = Txt.copy(alpha = 0.85f), fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
        } else {
            text.lines().forEachIndexed { i, l ->
                Text(l, color = Txt.copy(alpha = 0.85f), fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
                translated.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { Text(it, color = Txt.copy(alpha = 0.55f), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(200.dp))
    }
}

/**
 * Les paroles directement dans le lecteur (2.0), à la place de la pochette :
 * la ligne chantée s'allume et défile, toucher une ligne y saute.
 */
@Composable
fun InlineLyrics(s: TidalRemote.State, modifier: Modifier = Modifier, fontSize: Int = 26, centered: Boolean = false) {
    val t = s.track
    val pos = rememberLivePosition(s)
    var lyrics by remember(t?.title, t?.artist) { mutableStateOf<Lyrics.Result?>(null) }
    var loading by remember(t?.title, t?.artist) { mutableStateOf(true) }
    LaunchedEffect(t?.title, t?.artist) {
        loading = true
        lyrics = t?.let { Lyrics.fetch(it.title, it.artist, it.album, it.durationMs) }
        loading = false
    }
    val features by com.theo.remote.Features.flags.collectAsState()
    Box(
        modifier.clipToBounds()
            // fondu en haut et en bas : les paroles glissent sous le titre et le haut de l'écran
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent, 0.08f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent,
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        val l = lyrics
        when {
            loading -> Box(Modifier.fillMaxSize().padding(vertical = 24.dp)) { SkeletonLines(5) }
            l == null || l.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Pas de paroles pour ce son.", color = Txt.copy(alpha = 0.6f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            l.synced != null -> SyncedLyrics(
                l.synced, pos,
                onSave = if (features.quotes && t != null) { line: Lyrics.Line -> com.theo.remote.LyricQuotes.add(line.text, t.title, t.artist, line.timeMs) } else null,
                fontSize = fontSize, pad = PaddingValues(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 160.dp), dismissOnTop = false, centered = centered,
            )
            else -> PlainLyrics(l.plain.orEmpty())
        }
    }
}
