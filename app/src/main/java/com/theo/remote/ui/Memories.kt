package com.theo.remote.ui

import com.theo.remote.playTitle

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DAY = 24L * 3600_000
private fun fmtDate(ts: Long, pattern: String = "d MMMM yyyy") = SimpleDateFormat(pattern, Locale.FRANCE).format(Date(ts))

// =====================================================================================
// Ta première écoute
// =====================================================================================

object FirstListen {
    private var srcE: Any? = null
    private var srcS: Any? = null
    private var tracks: Map<String, Long> = emptyMap()
    private var artists: Map<String, Long> = emptyMap()

    @Synchronized
    private fun ensure() {
        val e = History.entries.value
        val s = LastFm.scrobbles.value
        if (e === srcE && s === srcS) return
        val t = HashMap<String, Long>()
        val a = HashMap<String, Long>()
        for (x in buildEvents(e, s)) { // déjà trié du plus ancien au plus récent
            t.putIfAbsent(x.key, x.ts)
            a.putIfAbsent(mainArtist(x.artist).lowercase(), x.ts)
        }
        tracks = t; artists = a; srcE = e; srcS = s
    }

    suspend fun track(title: String, artist: String): Long? = withContext(Dispatchers.Default) { ensure(); tracks[History.keyOf(title, artist)] }
    suspend fun artist(name: String): Long? = withContext(Dispatchers.Default) { ensure(); artists[mainArtist(name).lowercase()] }
}

/** « Première écoute : 12 mars 2025 » sous un son. */
@Composable
fun FirstListenLine(title: String, artist: String, modifier: Modifier = Modifier) {
    val f by com.theo.remote.Features.flags.collectAsState()
    if (!f.firstListen) return
    val ts by produceState<Long?>(null, title, artist) { value = FirstListen.track(title, artist) }
    ts?.let { Text("Première écoute : ${fmtDate(it)}", color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier) }
}

/** « Tu l'as découvert le … » sur la page d'un artiste. */
@Composable
fun ArtistDiscoveredLine(name: String) {
    val f by com.theo.remote.Features.flags.collectAsState()
    if (!f.firstListen) return
    val ts by produceState<Long?>(null, name) { value = FirstListen.artist(name) }
    ts?.let {
        val days = ((System.currentTimeMillis() - it) / DAY).toInt()
        val ago = when {
            days < 1 -> "aujourd'hui"
            days < 31 -> "il y a $days jour${if (days > 1) "s" else ""}"
            days < 365 -> "il y a ${days / 30} mois"
            else -> "il y a ${days / 365} an${if (days >= 730) "s" else ""}"
        }
        Text("Découvert le ${fmtDate(it)} · $ago", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// =====================================================================================
// Notes sur un son
// =====================================================================================

object TrackNotes {
    data class Note(val key: String, val title: String, val artist: String, val text: String, val at: Long)

    private val _notes = MutableStateFlow<Map<String, Note>>(emptyMap())
    val notes: StateFlow<Map<String, Note>> = _notes
    /** le son dont on écrit la note (titre, artiste) */
    val editing = MutableStateFlow<Pair<String, String>?>(null)
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _notes.value = runCatching {
            val a = JSONArray(p.getString("trackNotes", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Note(o.getString("k"), o.optString("t"), o.optString("a"), o.optString("x"), o.optLong("at")) }
                .associateBy { it.key }
        }.getOrDefault(emptyMap())
    }

    fun get(title: String, artist: String): Note? = _notes.value[History.keyOf(title, artist)]

    fun set(title: String, artist: String, text: String) {
        val k = History.keyOf(title, artist)
        _notes.value = if (text.isBlank()) _notes.value - k else _notes.value + (k to Note(k, title, artist, text.trim(), System.currentTimeMillis()))
        val a = JSONArray()
        _notes.value.values.forEach { a.put(JSONObject().put("k", it.key).put("t", it.title).put("a", it.artist).put("x", it.text).put("at", it.at)) }
        prefs?.edit()?.putString("trackNotes", a.toString())?.apply()
    }

    fun edit(title: String, artist: String) {
        editing.value = title to artist
        Panels.show(Panels.Kind.Notes)
    }
}

/** Petit encart de la note, sous le titre dans le lecteur. */
@Composable
fun NoteBadge(title: String, artist: String, modifier: Modifier = Modifier) {
    val f by com.theo.remote.Features.flags.collectAsState()
    val notes by TrackNotes.notes.collectAsState()
    if (!f.notes) return
    val n = notes[History.keyOf(title, artist)] ?: return
    val shape = Rounded(14)
    Row(
        modifier.fillMaxWidth().clip(shape).glass(shape, fill = 0.08f, edge = 0.12f)
            .bounceClick(pressedScale = 0.97f) { TrackNotes.edit(title, artist) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.EditNote, contentDescription = null, tint = Txt2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(n.text, color = Txt2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NotesPanel() {
    val editing by TrackNotes.editing.collectAsState()
    val notes by TrackNotes.notes.collectAsState()
    val all = remember(notes) { notes.values.sortedByDescending { it.at } }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item {
            PanelTitle("Tes notes")
            Text("Un petit mémo perso par son : une idée d'edit, un souvenir, un moment précis…", color = Txt2, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Space.m))
        }
        editing?.let { (title, artist) ->
            item(key = "editor-$title|$artist") {
                var text by remember(title, artist) { mutableStateOf(TrackNotes.get(title, artist)?.text.orEmpty()) }
                val shape = Rounded(20)
                Column(Modifier.fillMaxWidth().glass(shape, fill = 0.10f, edge = 0.18f).padding(Space.m)) {
                    Text(title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(Space.s))
                    Box(Modifier.fillMaxWidth().heightIn(min = 90.dp)) {
                        if (text.isEmpty()) Text("Écris ta note…", color = Txt3, fontSize = 15.sp)
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it.take(500) },
                            textStyle = TextStyle(color = Txt, fontSize = 15.sp, lineHeight = 21.sp),
                            cursorBrush = SolidColor(Txt),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                        )
                    }
                    Spacer(Modifier.height(Space.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        PillButton("Enregistrer") {
                            TrackNotes.set(title, artist, text)
                            TrackNotes.editing.value = null
                            TidalRemote.showToast(if (text.isBlank()) "Note effacée" else "Note enregistrée")
                        }
                        if (TrackNotes.get(title, artist) != null) PillButton("Effacer", filled = false) {
                            TrackNotes.set(title, artist, "")
                            TrackNotes.editing.value = null
                        }
                    }
                }
                Spacer(Modifier.height(Space.m))
            }
        }
        if (all.isEmpty() && editing == null) item {
            Text("Pas encore de note. Dans le lecteur : ⋯ → « Note sur ce son ».", color = Txt3, fontSize = 13.sp)
        }
        items(all, key = { it.key }) { n ->
            val shape = Rounded(18)
            Column(
                Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f)
                    .bounceClick(pressedScale = 0.97f, onLongClick = { TrackNotes.edit(n.title, n.artist) }) { TidalRemote.playTitle(n.title, n.artist) }
                    .padding(Space.m),
            ) {
                Text(n.text, color = Txt, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(6.dp))
                Text("${n.title} · ${n.artist}", color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Toucher pour écouter · appui long pour modifier", color = Txt3, fontSize = 11.sp)
            }
        }
    }
}

// =====================================================================================
// « Il y a un an »
// =====================================================================================

@Composable
fun YearAgoSection() {
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val data by produceState<Pair<String, List<Ev>>?>(null, entries, scrobbles) {
        value = withContext(Dispatchers.Default) {
            val ev = buildEvents(entries, scrobbles)
            val now = Calendar.getInstance()
            val cal = Calendar.getInstance()
            val m = now.get(Calendar.MONTH)
            val d = now.get(Calendar.DAY_OF_MONTH)
            val y = now.get(Calendar.YEAR)
            fun top(l: List<Ev>) = l.groupBy { it.key }.entries.sortedByDescending { it.value.size }
                .map { g -> g.value.firstOrNull { it.cover != null } ?: g.value.first() }
                .filterNot { com.theo.remote.Blocked.isBlocked(it.title, it.artist) }.take(15)
            // même jour, l'année la plus récente où tu as écouté quelque chose
            val sameDay = ev.filter { cal.timeInMillis = it.ts; cal.get(Calendar.MONTH) == m && cal.get(Calendar.DAY_OF_MONTH) == d && cal.get(Calendar.YEAR) < y }
            if (sameDay.isNotEmpty()) {
                val year = sameDay.maxOf { cal.timeInMillis = it.ts; cal.get(Calendar.YEAR) }
                val l = sameDay.filter { cal.timeInMillis = it.ts; cal.get(Calendar.YEAR) == year }
                val title = if (year == y - 1) "Il y a un an, jour pour jour" else "Le ${fmtDate(l.first().ts, "d MMMM yyyy")}"
                return@withContext title to top(l)
            }
            // sinon : la même semaine, l'an dernier
            val center = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis
            val week = ev.filter { kotlin.math.abs(it.ts - center) <= 3 * DAY }
            if (week.isNotEmpty()) "Il y a un an, cette semaine-là" to top(week) else null
        }
    }
    val (title, list) = data ?: return
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Text(title, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            items(list, key = { "ya-" + it.key }) { e ->
                Column(
                    Modifier.width(140.dp).clip(RoundedCornerShape(12.dp))
                        .bounceClick(pressedScale = 0.96f, onLongClick = { TrackMenu.open(TrackMenu.Target(e.title, e.artist, e.cover)) }) { TidalRemote.playTitle(e.title, e.artist) },
                ) {
                    Cover(model = e.cover, radius = 10.dp, modifier = Modifier.size(140.dp))
                    Spacer(Modifier.height(Space.s))
                    Text(e.title, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.artist, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// =====================================================================================
// Records
// =====================================================================================

private data class Record(val label: String, val value: String, val sub: String)

private fun computeRecords(ev: List<Ev>): List<Record> {
    if (ev.isEmpty()) return emptyList()
    val out = ArrayList<Record>()
    val cal = Calendar.getInstance()
    fun dayId(t: Long): Int { cal.timeInMillis = t; return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) }

    // plus longue session (moins de 20 min de pause entre deux sons)
    var bestLen = 0L; var bestStart = 0L
    var start = ev[0].ts; var end = ev[0].ts + ev[0].durMs
    for (i in 1 until ev.size) {
        val e = ev[i]
        if (e.ts - end > 20 * 60_000L) {
            if (end - start > bestLen) { bestLen = end - start; bestStart = start }
            start = e.ts
        }
        end = maxOf(end, e.ts + e.durMs)
    }
    if (end - start > bestLen) { bestLen = end - start; bestStart = start }
    out += Record("Plus longue session", fmtMinutes(bestLen / 60_000), "le ${fmtDate(bestStart)}")

    // plus grosse journée
    val perDay = HashMap<Int, Pair<Long, Long>>() // jour -> (ms, un timestamp)
    ev.forEach { e -> val k = dayId(e.ts); val p = perDay[k]; perDay[k] = ((p?.first ?: 0L) + e.durMs) to e.ts }
    perDay.values.maxByOrNull { it.first }?.let { (ms, ts) -> out += Record("Plus grosse journée", fmtMinutes(ms / 60_000), "le ${fmtDate(ts)}") }

    // le son le plus repassé en une journée
    val loops = HashMap<Pair<String, Int>, Int>()
    ev.forEach { e -> val k = e.key to dayId(e.ts); loops[k] = (loops[k] ?: 0) + 1 }
    loops.maxByOrNull { it.value }?.let { (k, n) ->
        val e = ev.last { it.key == k.first && dayId(it.ts) == k.second }
        if (n >= 2) out += Record("Son en boucle", "$n fois", "« ${e.title} » le ${fmtDate(e.ts)}")
    }

    // plus longue série de jours d'affilée
    val days = ev.map { cal.timeInMillis = it.ts; cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0); cal.timeInMillis / DAY }.distinct().sorted()
    var best = 1; var cur = 1; var bestEnd = days.firstOrNull() ?: 0L
    for (i in 1 until days.size) {
        cur = if (days[i] == days[i - 1] + 1) cur + 1 else 1
        if (cur > best) { best = cur; bestEnd = days[i] }
    }
    out += Record("Plus longue série", "$best jour${if (best > 1) "s" else ""}", "jusqu'au ${fmtDate(bestEnd * DAY)}")

    // plus gros mois
    val perMonth = HashMap<Int, Pair<Long, Long>>()
    ev.forEach { e -> cal.timeInMillis = e.ts; val k = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH); val p = perMonth[k]; perMonth[k] = ((p?.first ?: 0L) + e.durMs) to e.ts }
    perMonth.values.maxByOrNull { it.first }?.let { (ms, ts) -> out += Record("Plus gros mois", fmtMinutes(ms / 60_000), fmtDate(ts, "MMMM yyyy")) }

    // l'artiste le plus écouté en une journée
    val artistDay = HashMap<Pair<String, Int>, Int>()
    ev.forEach { e -> val k = mainArtist(e.artist) to dayId(e.ts); artistDay[k] = (artistDay[k] ?: 0) + 1 }
    artistDay.maxByOrNull { it.value }?.let { (k, n) ->
        val ts = ev.last { mainArtist(it.artist) == k.first && dayId(it.ts) == k.second }.ts
        out += Record("Journée fan", "$n sons", "de ${k.first} le ${fmtDate(ts)}")
    }
    return out
}

@Composable
fun RecordsCard(events: List<Ev>) {
    val records by produceState<List<Record>>(emptyList(), events) { value = withContext(Dispatchers.Default) { computeRecords(events) } }
    if (records.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Tes records", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        records.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                pair.forEach { r ->
                    Column(Modifier.weight(1f).heightIn(min = 104.dp).glass(Rounded(18), fill = 0.08f, edge = 0.12f).padding(Space.m)) {
                        Text(r.label, color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(r.value, color = Txt, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text(r.sub, color = Txt3, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// =====================================================================================
// Carte d'identité musicale
// =====================================================================================

private data class Identity(
    val profile: String,
    val profileSub: String,
    val since: Long,
    val hours: Long,
    val artists: Int,
    val tracks: Int,
    val top: List<Pair<String, Int>>,
    val topTrack: Ev?,
    val hour: Int,
    val weekday: String,
    val cover: Any?,
)

private fun computeIdentity(ev: List<Ev>): Identity? {
    if (ev.size < 10) return null
    val cal = Calendar.getInstance()
    val hours = IntArray(24); val wd = IntArray(8)
    ev.forEach { cal.timeInMillis = it.ts; hours[cal.get(Calendar.HOUR_OF_DAY)]++; wd[cal.get(Calendar.DAY_OF_WEEK)]++ }
    val hour = hours.indices.maxBy { hours[it] }
    val wdNames = listOf("", "dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi")
    val weekday = wdNames[(1..7).maxBy { wd[it] }]
    val artists = ev.groupingBy { mainArtist(it.artist) }.eachCount()
    val top = artists.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
    val tracks = ev.groupBy { it.key }
    val topTrackList = tracks.values.maxByOrNull { it.size }
    val topTrack = topTrackList?.firstOrNull { it.cover != null } ?: topTrackList?.first()
    val share = (top.firstOrNull()?.second ?: 0).toFloat() / ev.size
    val variety = artists.size.toFloat() / ev.size
    val night = (hours.slice(22..23).sum() + hours.slice(0..3).sum()).toFloat() / ev.size
    val morning = hours.slice(5..9).sum().toFloat() / ev.size
    val loops = ev.size.toFloat() / tracks.size
    val (p, s) = when {
        night > 0.3f -> "Oiseau de nuit" to "Surtout après 22 h"
        share > 0.25f -> "Fan absolu" to "Un artiste domine tes écoutes"
        morning > 0.3f -> "Lève-tôt" to "Surtout le matin"
        loops > 4f -> "Boucleur" to "Beaucoup de sons en boucle"
        variety > 0.25f -> "Explorateur" to "Beaucoup d'artistes nouveaux"
        else -> "Mélomane" to "Écoutes variées"
    }
    return Identity(
        p, s, ev.first().ts, ev.sumOf { it.durMs } / 3_600_000, artists.size, tracks.size,
        top, topTrack, hour, weekday, topTrack?.cover,
    )
}

/** Carte dans les Stats qui ouvre la carte d'identité. */
@Composable
fun IdCardEntry() {
    val shape = Rounded(22)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(flatOr(listOf(lerp(Ok, Color.Black, 0.35f), Color(0xFF15151E))))
            .bounceClick(pressedScale = 0.97f) { Panels.show(Panels.Kind.IdCard) }
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Badge, contentDescription = null, tint = Ok, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Profil d'écoute", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text("À partager en story", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@Composable
fun IdCardPanel() {
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val id by produceState<Identity?>(null, entries, scrobbles) {
        value = withContext(Dispatchers.Default) { computeIdentity(buildEvents(entries, scrobbles)) }
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val scroll = androidx.compose.foundation.rememberScrollState()
    DismissWhenAtTop(scroll)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 80.dp),
    ) {
        PanelTitle("Profil d'écoute")
        val i = id
        if (i == null) {
            Text("Pas encore assez d'écoutes.", color = Txt2, fontSize = 13.sp)
            return@Column
        }
        Spacer(Modifier.height(Space.s))
        // récompense niveau 10 : cadre doré + titre
        val rxp by com.theo.remote.Rewards.xp.collectAsState()
        com.theo.remote.Rewards.equipped.collectAsState().value
        val lvl = rxp?.let { com.theo.remote.Rewards.levelOf(it.total) } ?: 1
        val framed = com.theo.remote.Features.flags.value.rewards && com.theo.remote.Rewards.isOn("card:frame")
        // la carte (c'est elle qui est partagée)
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.66f).clip(Rounded(28))
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .background(flatOr(listOf(lerp(Ok, Color.Black, 0.25f), Color(0xFF14141C), Color(0xFF0B0B10))))
                .then(if (framed) Modifier.border(3.dp, flatOr(listOf(Color(0xFFF3DDB0), Color(0xFFD9A95F), Color(0xFFF3DDB0))), Rounded(28)) else Modifier)
                .padding(22.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Carte d'identité musicale", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Hush", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(model = i.cover, radius = 16.dp, modifier = Modifier.size(92.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Theo", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        if (com.theo.remote.Features.flags.value.rewards) Text(
                            "Niv. $lvl · ${com.theo.remote.Rewards.title(lvl)}",
                            color = if (framed) Color(0xFFF3DDB0) else Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(i.profile, color = Ok, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text(i.profileSub, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Top artistes", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                i.top.forEachIndexed { n, (a, _) ->
                    Text("${n + 1}. $a", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                i.topTrack?.let {
                    Text("Son fétiche", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("« ${it.title} »", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // récompense « Badges sur ta carte »
                if (com.theo.remote.Features.flags.value.rewards && com.theo.remote.Rewards.isOn("badges:card")) {
                    val bctx = androidx.compose.ui.platform.LocalContext.current
                    val bEntries by com.theo.remote.History.entries.collectAsState()
                    val bScrob by com.theo.remote.LastFm.scrobbles.collectAsState()
                    val won by androidx.compose.runtime.produceState(emptyList<Badges.Badge>(), bEntries, bScrob) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { Badges.compute(bctx, buildEvents(bEntries, bScrob)).filter { it.unlocked } }.getOrDefault(emptyList())
                        }
                    }
                    if (won.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            won.takeLast(3).forEach { b ->
                                Text(
                                    b.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            if (won.size > 3) Text("+${won.size - 3}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row {
                    IdStat("${i.hours} h", "écoutées", Modifier.weight(1f))
                    IdStat("${i.artists}", "artistes", Modifier.weight(1f))
                    IdStat("${i.tracks}", "sons", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    IdStat("${i.hour} h", "heure préférée", Modifier.weight(1f))
                    IdStat(i.weekday, "jour préféré", Modifier.weight(1f))
                    IdStat(fmtDate(i.since, "MMM yyyy"), "depuis", Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        PillButton("Partager en story") {
            scope.launch { runCatching { shareCard(ctx, layer.toImageBitmap().asAndroidBitmap()) } }
        }
    }
}

@Composable
private fun IdStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
    }
}

private suspend fun shareCard(ctx: Context, bmp: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "hush-carte.png")
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        f.outputStream().use { soft.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".share", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Partager ta carte").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
