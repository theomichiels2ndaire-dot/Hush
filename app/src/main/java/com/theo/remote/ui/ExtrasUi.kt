package com.theo.remote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Gestures
import com.theo.remote.History
import com.theo.remote.Karaoke
import com.theo.remote.LastFm
import com.theo.remote.Lyrics
import com.theo.remote.PartyServer
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================================
// Karaoké
// =====================================================================================

@Composable
fun KaraokePanel() {
    val ctx = LocalContext.current
    val s by TidalRemote.state.collectAsState()
    val party by PartyServer.status.collectAsState()
    var withFriends by remember { mutableStateOf(Karaoke.party) }
    val t = s.track
    val lyrics by produceState<Lyrics.Result?>(null, t?.title, t?.artist) {
        value = null
        if (t != null) value = runCatching { Lyrics.fetch(t.title, t.artist, t.album, t.durationMs) }.getOrNull()
    }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = TidalRemote.state.value.livePosition(); delay(50) } }
    val colors = rememberCoverColors(t?.art)

    Box(Modifier.fillMaxSize()) {
        Ambient(colors, strength = 1f, animated = true)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Space.gutter).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PanelTitle("Karaoké")
            Text(listOfNotNull(t?.title, t?.artist).joinToString(" · "), color = Txt2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))

            val lines = lyrics?.synced.orEmpty()
            if (lines.isEmpty()) {
                Text(
                    when {
                        t == null -> "Lance un son dans Tidal"
                        lyrics == null -> "Je cherche les paroles…"
                        else -> "Pas de paroles synchronisées pour ce son"
                    },
                    color = Txt2, fontSize = 18.sp, textAlign = TextAlign.Center,
                )
            } else {
                val i = lines.indexOfLast { it.timeMs <= pos }
                val cur = lines.getOrNull(i)
                val next = lines.getOrNull(i + 1)
                val end = next?.timeMs ?: ((cur?.timeMs ?: 0L) + 4000)
                val p = if (cur == null) 0f else ((pos - cur.timeMs).toFloat() / (end - cur.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                Text(lines.getOrNull(i - 1)?.text.orEmpty(), color = Txt3, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(18.dp))
                AnimatedContent(
                    targetState = i,
                    transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 }) togetherWith (fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 3 }) },
                    label = "karaLine",
                ) { idx ->
                    val line = lines.getOrNull(idx)?.text?.ifBlank { "♪" } ?: "♪"
                    FillText(line, if (idx == i) p else 1f)
                }
                Spacer(Modifier.height(18.dp))
                Text(next?.text.orEmpty(), color = Txt2, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Text(lines.getOrNull(i + 2)?.text.orEmpty(), color = Txt3, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            }

            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.08f, edge = 0.12f).padding(Space.m)) {
                Column(Modifier.weight(1f)) {
                    Text("Sur le téléphone de tes potes", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (withFriends) "Ils voient les paroles sur la page du QR code (mode soirée)" else "Les paroles s'affichent aussi via le QR code",
                        color = Txt2, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
                GlassSwitch(withFriends) {
                    withFriends = it
                    Karaoke.party = it
                    if (it && !party.running) PartyServer.start(ctx)
                }
            }
        }
    }
}

/** Une ligne qui se remplit de blanc au fil du chant. */
@Composable
private fun FillText(text: String, progress: Float) {
    val p by animateFloatAsState(progress, tween(120), label = "fill")
    Box(contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.32f), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp, textAlign = TextAlign.Center)
        Text(
            text, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp, textAlign = TextAlign.Center,
            modifier = Modifier.drawWithContent {
                clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
            },
        )
    }
}

// =====================================================================================
// Humeur (accueil)
// =====================================================================================

@Composable
fun MoodRow() {
    val ctx = LocalContext.current
    val busy by MoodMix.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val palette = mapOf(
        MoodMix.Mood.Chill to listOf(Color(0xFF2F7FE0), Color(0xFF34C3B0)),
        MoodMix.Mood.Hype to listOf(Color(0xFFE0452F), Color(0xFFF0A024)),
        MoodMix.Mood.Sad to listOf(Color(0xFF3B4B8C), Color(0xFF7A6FB0)),
        MoodMix.Mood.Party to listOf(Color(0xFFE0458B), Color(0xFF7A2FE0)),
        MoodMix.Mood.Focus to listOf(Color(0xFF14524A), Color(0xFF2F5FE0)),
        MoodMix.Mood.Love to listOf(Color(0xFFE0457A), Color(0xFFF07A6A)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Humeur", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = Space.gutter))
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(MoodMix.Mood.entries.size) { i ->
                val m = MoodMix.Mood.entries[i]
                val shape = Rounded(18)
                Box(
                    Modifier.size(width = 112.dp, height = 70.dp).clip(shape)
                        .background(flatOr(palette[m] ?: listOf(Color.Gray, Color.DarkGray)))
                        .glass(shape, fill = 0.02f, edge = 0.16f)
                        .bounceClick(pressedScale = 0.94f) { if (busy == null) scope.launch { MoodMix.play(ctx, m) } }
                        .padding(12.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(if (busy == m) "…" else m.label, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

// =====================================================================================
// Mix du jour (accueil + liste)
// =====================================================================================

@Composable
fun DailyMixCard() {
    val ctx = LocalContext.current
    val allItems by DailyMix.items.collectAsState()
    val blocked by com.theo.remote.Blocked.items.collectAsState()
    val items = remember(allItems, blocked) { allItems.filterNot { com.theo.remote.Blocked.isBlocked(it.title, it.artist) } }
    val entries by History.entries.collectAsState()
    LaunchedEffect(entries.isNotEmpty()) { if (entries.isNotEmpty()) runCatching { DailyMix.ensure(ctx) } }
    if (items.isEmpty()) return
    val shape = Rounded(24)
    Row(
        Modifier.padding(horizontal = Space.gutter).fillMaxWidth().glass(shape, fill = 0.08f, edge = 0.14f)
            .bounceClick(pressedScale = 0.98f) { Panels.show(Panels.Kind.Mix) }.padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // mosaïque de 4 pochettes
        Column(Modifier.size(92.dp).clip(Rounded(14))) {
            for (r in 0..1) Row(Modifier.weight(1f)) {
                for (c in 0..1) Cover(model = DailyMix.coverModel(items.getOrNull(r * 2 + c)?.cover), radius = 0.dp, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text("Mix du jour", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${items.size} sons", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        Box(
            Modifier.size(48.dp).shadow(10.dp, PillShape).clip(PillShape).background(Txt)
                .bounceClick { TidalRemote.playSequence(items.map { it.title to it.artist }) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire le mix", tint = Ink, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
fun MixPanel() {
    val allItems by DailyMix.items.collectAsState()
    val blocked by com.theo.remote.Blocked.items.collectAsState()
    val items = remember(allItems, blocked) { allItems.filterNot { com.theo.remote.Blocked.isBlocked(it.title, it.artist) } }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp)) {
        item {
            PanelTitle("Mix du jour")
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PillButton("Lire") { TidalRemote.playSequence(items.map { it.title to it.artist }) }
                PillButton("Aléatoire", filled = false) { TidalRemote.playSequence(com.theo.remote.SmartShuffle.order(items, { it.title }, { it.artist }).map { it.title to it.artist }) }
            }
            Spacer(Modifier.height(Space.m))
        }
        itemsIndexed(items, key = { i, it -> "$i${it.title}" }) { i, it ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .bounceClick(pressedScale = 0.97f) { TidalRemote.playSequence(items.drop(i).map { x -> x.title to x.artist }) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(model = DailyMix.coverModel(it.cover), radius = 6.dp, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${it.artist} · ${it.why}", color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// =====================================================================================
// Badges (onglet Stats)
// =====================================================================================

@Composable
fun BadgesSection() {
    val ctx = LocalContext.current
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val badges by produceState(emptyList<Badges.Badge>(), entries, scrobbles) {
        value = withContext(Dispatchers.Default) { Badges.compute(ctx, buildEvents(entries, scrobbles)) }
        Badges.newlyUnlocked(ctx, value).firstOrNull()?.let { TidalRemote.showToast("Nouveau badge : ${it.icon} ${it.title}") }
    }
    if (badges.isEmpty()) return
    var open by remember { mutableStateOf<Badges.Badge?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Badges · ${badges.count { it.unlocked }} / ${badges.size}", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        badges.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { b -> BadgeTile(b, Modifier.weight(1f)) { open = if (open?.id == b.id) null else b } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        open?.let { b ->
            Column(Modifier.fillMaxWidth().glass(Rounded(18), fill = 0.08f, edge = 0.14f).padding(Space.m)) {
                Text("${b.icon}  ${b.title}", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(b.desc, color = Txt2, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(b.progress).height(6.dp).background(if (b.unlocked) Ok else Txt))
                }
                Text(if (b.unlocked) "Débloqué !" else "${(b.progress * 100).toInt()} %", color = if (b.unlocked) Ok else Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BadgeTile(b: Badges.Badge, modifier: Modifier, onClick: () -> Unit) {
    val shape = Rounded(18)
    Column(
        modifier.aspectRatio(0.85f).glass(shape, fill = if (b.unlocked) 0.12f else 0.04f, edge = if (b.unlocked) 0.2f else 0.08f)
            .bounceClick(pressedScale = 0.94f, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(b.icon, fontSize = 26.sp, modifier = Modifier.drawWithContent {
            drawContent()
            if (!b.unlocked) drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset.Zero, size = Size(size.width, size.height))
        })
        Spacer(Modifier.height(4.dp))
        Text(b.title, color = if (b.unlocked) Txt else Txt3, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
        if (!b.unlocked) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(3.dp).clip(PillShape).background(Color.White.copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxWidth(b.progress).height(3.dp).background(Txt2))
            }
        }
    }
}

// =====================================================================================
// Gestes (réglages)
// =====================================================================================

@Composable
fun GesturesCard() {
    val g by Gestures.settings.collectAsState()
    val shape = Rounded(22)
    Column(Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Gestes", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Secouer → son suivant", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Deux petites secousses franches", color = Txt2, fontSize = 13.sp)
            }
            GlassSwitch(g.shake) { Gestures.set(g.copy(shake = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Retourner → pause", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Écran contre la table ; retourne-le dans la minute pour reprendre", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
            }
            GlassSwitch(g.flip) { Gestures.set(g.copy(flip = it)) }
        }
    }
}
