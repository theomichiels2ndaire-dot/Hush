package com.theo.remote.ui

import com.theo.remote.playExact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Blocked
import com.theo.remote.History
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun Pick(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Box(
        Modifier.clip(shape)
            .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
            .bounceClick(pressedScale = 0.93f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(label, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

// =====================================================================================
// Pour mon trajet
// =====================================================================================

private data class TripItem(val title: String, val artist: String, val cover: Any?, val durMs: Long)

private fun buildTrip(minutes: Int, source: Int): List<TripItem> {
    val now = System.currentTimeMillis()
    val likes = History.likes.value
    val all = History.entries.value.filter { it.durationMs in 45_000..900_000 && !Blocked.isBlocked(it.title, it.artist) }
    val ranked = when (source) {
        1 -> all.filter { likes.contains(it.key) }.sortedByDescending { it.lastPlayed }
        2 -> all.sortedByDescending { it.plays.size }
        else -> all.sortedByDescending { e -> e.plays.count { it > now - 60L * 24 * 3600_000 } * 10 + e.plays.size }
    }
    val pool = ranked.take(150).shuffled()
    val target = minutes * 60
    val maxS = target + 20
    // sac à dos : la combinaison qui tombe le plus près de la durée voulue
    val durs = pool.map { ((it.durationMs + 500) / 1000).toInt() }
    val reach = IntArray(maxS + 1) { -1 }
    reach[0] = -2
    for (i in pool.indices) {
        val d = durs[i]
        for (s in maxS downTo d) if (reach[s] == -1 && reach[s - d] != -1) reach[s] = i
    }
    val best = (0..maxS).filter { reach[it] != -1 && it > 0 }.minByOrNull { kotlin.math.abs(it - target) } ?: return emptyList()
    val picked = ArrayList<Int>()
    var s = best
    while (s > 0) { val i = reach[s]; picked += i; s -= durs[i] }
    return picked.shuffled().map { i -> val e = pool[i]; TripItem(e.title, e.artist, e.coverUrl ?: e.artPath?.let { File(it) }, e.durationMs) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripPanel() {
    var minutes by remember { mutableIntStateOf(25) }
    var custom by remember { mutableFloatStateOf(25f) }
    var source by remember { mutableIntStateOf(0) }
    var trip by remember { mutableStateOf<List<TripItem>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    DismissWhenAtTop(list)

    fun make() {
        busy = true
        scope.launch {
            trip = withContext(Dispatchers.Default) { buildTrip(minutes, source) }
            busy = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item {
            PanelTitle("Pour mon trajet")
            Text("Dis combien de temps dure ton trajet : Hush te fait une file qui dure pile ce temps.", color = Txt2, fontSize = 15.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Space.m))
            Text("Durée : $minutes min", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(Space.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 20, 25, 30, 45, 60, 90).forEach { m -> Pick("$m min", minutes == m) { minutes = m; custom = m.toFloat(); trip = null } }
            }
            Slider(
                value = custom,
                onValueChange = { custom = it; minutes = it.toInt(); trip = null },
                valueRange = 5f..120f,
                colors = SliderDefaults.colors(thumbColor = Txt, activeTrackColor = Txt, inactiveTrackColor = Color.White.copy(alpha = 0.14f)),
                modifier = Modifier.blockDismiss(),
            )
            Text("Avec quoi ?", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(Space.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tes sons du moment", "Titres likés", "Tes classiques").forEachIndexed { i, l -> Pick(l, source == i) { source = i; trip = null } }
            }
            Spacer(Modifier.height(Space.m))
            PillButton(if (busy) "Je prépare…" else if (trip == null) "Créer ma file" else "Une autre") { if (!busy) make() }
            Spacer(Modifier.height(Space.m))
            trip?.let { l ->
                if (l.isEmpty()) {
                    Text("Pas assez de sons avec une durée connue. Écoute encore un peu avec Hush ouvert.", color = Txt3, fontSize = 13.sp)
                } else {
                    val total = l.sumOf { it.durMs }
                    val arrive = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(System.currentTimeMillis() + total))
                    Column(Modifier.fillMaxWidth().glass(Rounded(20), fill = 0.08f, edge = 0.14f).padding(Space.m)) {
                        Text("${l.size} sons · ${total / 60_000} min ${"%02d".format((total / 1000) % 60)} s", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Si tu lances maintenant : fin de la musique vers $arrive", color = Txt2, fontSize = 13.sp)
                        Spacer(Modifier.height(Space.s))
                        PillButton("C'est parti") { TidalRemote.playExact(l.map { it.title to it.artist }); Panels.close() }
                    }
                    Spacer(Modifier.height(Space.s))
                }
            }
        }
        itemsIndexed(trip.orEmpty(), key = { i, it -> "$i${it.title}" }) { i, it ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                Cover(model = it.cover, radius = 6.dp, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(mmss(it.durMs), color = Txt3, fontSize = 12.sp)
            }
        }
    }
}

// =====================================================================================
// Basses et son 8D
// =====================================================================================

/** Bouton du lecteur : touche = niveau de basses suivant, appui long = réglages du son. */
@Composable
fun BassButton() {
    val bass by com.theo.remote.AudioFx.bass.collectAsState()
    val haptic = rememberHaptic()
    Box(
        Modifier.size(48.dp).clip(PillShape)
            .bounceClick(pressedScale = 0.92f, onLongClick = { Panels.show(Panels.Kind.Sound) }) {
                haptic()
                com.theo.remote.AudioFx.cycleBass()
                val n = com.theo.remote.AudioFx.bass.value
                TidalRemote.showToast(if (n == 0) "Basses : normal" else "Basses +$n")
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Rounded.GraphicEq, contentDescription = "Basses",
                tint = if (bass > 0) Ok else Txt.copy(alpha = 0.75f), modifier = Modifier.size(24.dp),
            )
            if (bass > 0) Text("+$bass", color = Ok, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 9.sp)
        }
    }
}

@Composable
fun SoundPanel() {
    val bass by com.theo.remote.AudioFx.bass.collectAsState()
    val spatial by com.theo.remote.AudioFx.spatial.collectAsState()
    val status by com.theo.remote.AudioFx.status.collectAsState()
    val f by com.theo.remote.Features.flags.collectAsState()
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item {
            PanelTitle("Son")
            Text(
                when (status) {
                    com.theo.remote.AudioFx.Status.Off -> "Aucun effet en cours."
                    com.theo.remote.AudioFx.Status.Tidal -> "✓ Branché sur le son de Tidal."
                    com.theo.remote.AudioFx.Status.Global -> "Branché sur la sortie du téléphone. Si tu n'entends aucune différence, relance un son dans Tidal pour qu'il donne accès à son audio."
                    com.theo.remote.AudioFx.Status.Blocked -> "Ton téléphone refuse les effets sur le son de Tidal pour l'instant. Relance un son dans Tidal puis réessaie."
                },
                color = if (status == com.theo.remote.AudioFx.Status.Blocked) Ko else Txt2, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }
        if (f.bass) item {
            val shape = Rounded(22)
            Column(Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text("Basses", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Normal", "+1", "+2", "+3").forEachIndexed { i, l -> Pick(l, bass == i) { com.theo.remote.AudioFx.setBass(i) } }
                }
                Text("Raccourci : le bouton ≋ dans le lecteur.", color = Txt3, fontSize = 12.sp)
            }
        }
        if (f.spatial) item {
            val shape = Rounded(22)
            Column(Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Son 8D", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Le son tourne doucement autour de ta tête. À écouter avec des écouteurs.", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.width(Space.s))
                    GlassSwitch(spatial) { com.theo.remote.AudioFx.setSpatial(it) }
                }
            }
        }
    }
}

// =====================================================================================
// Tous les outils
// =====================================================================================

private data class ToolItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val sub: String, val onClick: (android.content.Context) -> Unit)

@Composable
fun ToolsPanel() {
    val f by com.theo.remote.Features.flags.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val I = androidx.compose.material.icons.Icons.Rounded
    val tools = buildList {
        if (f.discover) add(ToolItem(I.Style, "Découverte", "Des sons jamais écoutés, à glisser") { Panels.show(Panels.Kind.Discover) })
        add(ToolItem(I.GraphicEq, "Quel est ce son ?", "Reconnaître un son autour de toi") { Panels.show(Panels.Kind.Shazam) })
        add(ToolItem(I.Quiz, "Blind test", "Devine tes sons") { Panels.show(Panels.Kind.BlindTest) })
        if (f.rewards) add(ToolItem(I.EmojiEvents, "Récompenses", "Ton niveau et ce que tu as débloqué") { Panels.show(Panels.Kind.Rewards) })
        if (f.lyricsQuiz) add(ToolItem(I.FormatQuote, "Quiz paroles", "Trouve la suite ou le titre de tes sons") { Panels.show(Panels.Kind.LyricsQuiz) })
        add(ToolItem(I.Celebration, "Soirée", "Tes potes ajoutent des sons par QR code") { Panels.show(Panels.Kind.Party) })
        add(ToolItem(I.Mic, "Karaoké", "Paroles en grand") { Panels.show(Panels.Kind.Karaoke) })
        add(ToolItem(I.DirectionsCar, "Voiture", "Gros boutons pour la route") { CarMode.open() })
        add(ToolItem(I.Alarm, "Réveil", "Te réveiller en musique") { Panels.show(Panels.Kind.Alarm) })
        if (f.trip) add(ToolItem(I.Timer, "Trajet", "Une file pile à la bonne durée") { Panels.show(Panels.Kind.Trip) })
        if (f.bass || f.spatial) add(ToolItem(I.Headphones, "Son", "Basses et son 8D") { Panels.show(Panels.Kind.Sound) })
        if (f.notes) add(ToolItem(I.EditNote, "Tes notes", "Tes mémos sur les sons") { TrackNotes.editing.value = null; Panels.show(Panels.Kind.Notes) })
        if (f.quotes) add(ToolItem(I.FormatQuote, "Tes paroles", "Les lignes que tu as gardées") { Panels.show(Panels.Kind.Quotes) })
        if (f.idCard) add(ToolItem(I.Badge, "Profil d'écoute", "À partager en story") { Panels.show(Panels.Kind.IdCard) })
        if (f.feats) add(ToolItem(I.Hub, "Featurings", "Les collabs de tes artistes") { Panels.show(Panels.Kind.Feats) })
        if (f.lyricWords) add(ToolItem(I.Forum, "Tes mots", "Les mots de tes sons") { Panels.show(Panels.Kind.Words) })
        if (f.oneSong) add(ToolItem(I.LibraryMusic, "Un son par artiste", "Ton best of perso") { Panels.show(Panels.Kind.OneSong) })
        if (f.setlists) add(ToolItem(I.QueueMusic, "Setlists", "Les sons joués aux derniers concerts de tes artistes") { Panels.show(Panels.Kind.Setlists) })
        if (f.skipDetect) add(ToolItem(I.SkipNext, "Sons souvent passés", "Ceux que tu zappes au bout de quelques secondes") { Panels.show(Panels.Kind.Skips) })
        if (f.albums) add(ToolItem(I.Album, "Tes albums", "Classement et albums écoutés en entier") { Panels.show(Panels.Kind.Albums) })
        if (f.concerts) add(ToolItem(I.ConfirmationNumber, "Concerts", "Les dates de tes artistes") { Panels.show(Panels.Kind.Concerts) })
        if (f.ambient) add(ToolItem(I.Water, "Sons d'ambiance", "Pluie, vagues, bruits doux") { Panels.show(Panels.Kind.Ambient) })
    }
    // outils triés : ceux que tu ouvres le plus d'abord, le reste replié
    val uses = remember { ToolUse.counts(ctx) }
    val sorted = tools.withIndex().sortedWith(compareByDescending<IndexedValue<ToolItem>> { uses[it.value.title] ?: 0 }.thenBy { it.index }).map { it.value }
    val top = sorted.take(6)
    val rest = sorted.drop(6)
    var more by remember { mutableStateOf(false) }
    fun open(t: ToolItem) { ToolUse.bump(ctx, t.title); t.onClick(ctx) }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item { PanelTitle("Outils") }
        items(top.chunked(2).size) { row ->
            val pair = top.chunked(2)[row]
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                pair.forEach { t ->
                    val shape = Rounded(20)
                    Column(
                        Modifier.weight(1f).height(118.dp).glass(shape, fill = 0.07f, edge = 0.12f)
                            .bounceClick(pressedScale = 0.95f) { open(t) }
                            .padding(14.dp),
                    ) {
                        androidx.compose.material3.Icon(t.icon, contentDescription = null, tint = Ok, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.weight(1f))
                        Text(t.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.sub, color = Txt2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (rest.isNotEmpty()) item {
            Text(
                if (more) "Moins d'outils" else "${rest.size} autres outils",
                color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = Space.s).clip(RoundedCornerShape(8.dp)).bounceClick { more = !more }.padding(vertical = 6.dp),
            )
        }
        if (more) items(rest.size) { i ->
            val t = rest[i]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.98f) { open(t) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(t.icon, contentDescription = null, tint = Txt2, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(t.sub, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


/** Combien de fois tu ouvres chaque outil (pour mettre devant ceux qui servent). */
private object ToolUse {
    private fun p(ctx: android.content.Context) = ctx.getSharedPreferences("hush_tools_use", android.content.Context.MODE_PRIVATE)
    fun counts(ctx: android.content.Context): Map<String, Int> = p(ctx).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    fun bump(ctx: android.content.Context, title: String) { val sp = p(ctx); sp.edit().putInt(title, sp.getInt(title, 0) + 1).apply() }
}
