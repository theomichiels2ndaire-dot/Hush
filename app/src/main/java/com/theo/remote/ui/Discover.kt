package com.theo.remote.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Blocked
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.Lyrics
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val DAY_MS = 24L * 3600_000

/** Titre « nettoyé » pour comparer un son de Tidal avec ton historique. */
fun normTitle(t: String): String = t.lowercase()
    .replace(Regex("""\s*[(\[].*?[)\]]"""), "")
    .replace(Regex("""\s+-\s+.*$"""), "")
    .replace(Regex("[^\\p{L}\\p{N}]"), "")

private val discoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun allEvents() = buildEvents(History.entries.value, LastFm.scrobbles.value)

// =====================================================================================
// Son caché du jour
// =====================================================================================

object HiddenGem {
    data class Gem(
        val id: String, val title: String, val version: String?, val artists: List<String>,
        val album: String?, val cover: String?, val day: Int, val because: String,
    ) {
        val artistLine get() = artists.joinToString(", ")
        val fullTitle get() = if (version.isNullOrBlank()) title else "$title ($version)"
        fun toResult() = TidalApi.Result(id, title, version, artists, album, cover, 0L, false)
    }

    val gem = MutableStateFlow<Gem?>(null)
    val loading = MutableStateFlow(false)
    private var prefs: android.content.SharedPreferences? = null

    fun today(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        gem.value = runCatching {
            val o = JSONObject(p.getString("gem", null) ?: return@runCatching null)
            val a = o.getJSONArray("ar")
            Gem(
                o.getString("id"), o.getString("t"), o.optString("v").ifBlank { null }, (0 until a.length()).map { a.getString(it) },
                o.optString("al").ifBlank { null }, o.optString("c").ifBlank { null }, o.getInt("d"), o.optString("b"),
            )
        }.getOrNull()
    }

    private fun save(g: Gem) {
        prefs?.edit()?.putString(
            "gem",
            JSONObject().put("id", g.id).put("t", g.title).put("v", g.version ?: "").put("ar", JSONArray(g.artists))
                .put("al", g.album ?: "").put("c", g.cover ?: "").put("d", g.day).put("b", g.because).toString(),
        )?.apply()
    }

    /** Trouve le son du jour (une fois par jour, ou à la demande avec « Un autre »). */
    fun refresh(force: Boolean = false) {
        if (loading.value) return
        if (!force && gem.value?.day == today()) return
        if (!TidalApi.isConfigured) return
        loading.value = true
        discoverScope.launch {
            try {
                val ev = allEvents()
                if (ev.isEmpty()) return@launch
                val heard = ev.map { normTitle(it.title) }.toHashSet()
                val recent = ev.filter { it.ts >= System.currentTimeMillis() - 120 * DAY_MS }.ifEmpty { ev }
                val artists = recent.groupingBy { mainArtist(it.artist) }.eachCount().entries
                    .sortedByDescending { it.value }.take(15).map { it.key }
                val rnd = Random(today() * 31 + if (force) System.currentTimeMillis().toInt() else 0)
                for (a in artists.shuffled(rnd).take(4)) {
                    val art = runCatching { TidalApi.findArtist(a) }.getOrNull() ?: continue
                    val page = runCatching { TidalApi.artistPage(art) }.getOrNull() ?: continue
                    val cands = ArrayList(page.topTracks)
                    for (al in page.albums.shuffled(rnd).take(3)) {
                        runCatching { TidalApi.albumPage(al).tracks }.getOrNull()?.let { cands += it }
                        delay(150)
                    }
                    val fresh = cands.filter { normTitle(it.title) !in heard && !Blocked.isBlocked(it.fullTitle, it.artistLine) }
                        .distinctBy { normTitle(it.title) }
                    if (fresh.isNotEmpty()) {
                        val r = fresh.random(rnd)
                        val g = Gem(r.id, r.title, r.version, r.artists, r.album, r.coverUrl, today(), art.name)
                        gem.value = g
                        save(g)
                        return@launch
                    }
                }
            } catch (_: Exception) {
            } finally {
                loading.value = false
            }
        }
    }
}

@Composable
fun HiddenGemCard() {
    val g by HiddenGem.gem.collectAsState()
    val loading by HiddenGem.loading.collectAsState()
    LaunchedEffect(Unit) { HiddenGem.refresh() }
    val x = g ?: return
    val shape = Rounded(22)
    Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Jamais écouté", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(flatOr(listOf(lerp(Ok, Color.Black, 0.55f).copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f))))
                .glass(shape, fill = 0.04f, edge = 0.14f)
                .bounceClick(pressedScale = 0.97f, onLongClick = { TrackMenu.open(TrackMenu.Target(x.fullTitle, x.artistLine, x.cover, x.toResult())) }) {
                    TidalRemote.play(x.toResult())
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Cover(model = x.cover, radius = 14.dp, modifier = Modifier.size(78.dp).clip(Rounded(14)).glare())
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(24.dp).clip(PillShape).background(Ok),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Star, contentDescription = null, tint = Ink, modifier = Modifier.size(14.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(x.fullTitle, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(x.artistLine, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("Proche de ${x.because}", color = Txt3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (loading) "Recherche…" else "Un autre ↻",
                    color = Txt, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).bounceClick(pressedScale = 0.92f) { HiddenGem.refresh(force = true) },
                )
            }
            Box(Modifier.size(44.dp).clip(PillShape).background(Txt), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire", tint = Ink, modifier = Modifier.size(26.dp))
            }
        }
    }
}

// =====================================================================================
// Discographie complète
// =====================================================================================

private data class Disco(val heard: Int, val total: Int, val unheard: List<TidalApi.Result>)

@Composable
fun DiscographyBar(name: String, page: TidalApi.ArtistPage) {
    val d by produceState<Disco?>(null, page) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                val all = LinkedHashMap<String, TidalApi.Result>()
                page.topTracks.forEach { all.putIfAbsent(normTitle(it.title), it) }
                for (al in page.albums.take(30)) {
                    runCatching { TidalApi.albumPage(al).tracks }.getOrNull()?.forEach { all.putIfAbsent(normTitle(it.title), it) }
                    delay(120)
                }
                val low = name.lowercase()
                val heard = allEvents().filter { it.artist.lowercase().contains(low) }.map { normTitle(it.title) }.toHashSet()
                val unheard = all.filterKeys { it !in heard }.values.toList()
                Disco(all.size - unheard.size, all.size, unheard)
            }
        }.getOrNull()
    }
    val x = d
    val shape = Rounded(18)
    Column(Modifier.fillMaxWidth().padding(top = Space.m).glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m)) {
        if (x == null || x.total == 0) {
            Text("Discographie", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (x == null) "Calcul de ta progression…" else "Pas de discographie trouvée", color = Txt3, fontSize = 13.sp)
            return@Column
        }
        val pct = (x.heard * 100 / x.total).coerceIn(0, 100)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Discographie", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("$pct %", color = Ok, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(Color.White.copy(alpha = 0.10f))) {
            Box(Modifier.fillMaxWidth(pct / 100f).height(8.dp).clip(PillShape).background(Ok))
        }
        Spacer(Modifier.height(6.dp))
        Text("Tu as écouté ${x.heard} de ses ${x.total} sons", color = Txt2, fontSize = 13.sp)
        if (x.unheard.isNotEmpty()) {
            Spacer(Modifier.height(Space.s))
            PillButton("Écouter ceux que tu ne connais pas (${x.unheard.size})", filled = false) {
                TidalRemote.playSequence(x.unheard.take(100).map { it.fullTitle to it.artistLine })
            }
        }
    }
}

// =====================================================================================
// Featurings
// =====================================================================================

private data class Feat(val a: String, val b: String, val tracks: List<Ev>, val plays: Int)

private val SPLIT = Regex("""\s*(?:,|&|\s[xX×]\s|\s(?:feat|ft)\.?\s|\swith\s)\s*""", RegexOption.IGNORE_CASE)
private val TITLE_FEAT = Regex("""[(\[]\s*(?:feat\.?|ft\.?|with)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)

private fun artistsOf(title: String, artist: String): List<String> {
    val l = ArrayList<String>()
    l += artist.split(SPLIT)
    TITLE_FEAT.findAll(title).forEach { m -> l += m.groupValues[1].split(SPLIT) }
    return l.map { it.trim() }.filter { it.length > 1 }.distinctBy { it.lowercase() }
}

private fun computeFeats(ev: List<Ev>): Pair<List<Feat>, Map<String, Int>> {
    val byTrack = ev.groupBy { it.key }
    val artistPlays = HashMap<String, Int>()
    val display = HashMap<String, String>()
    val pairs = HashMap<Pair<String, String>, MutableList<List<Ev>>>()
    for (list in byTrack.values) {
        val e = list.first()
        val names = artistsOf(e.title, e.artist)
        names.forEach { n -> val k = n.lowercase(); display.putIfAbsent(k, n); artistPlays[k] = (artistPlays[k] ?: 0) + list.size }
        if (names.size < 2) continue
        val keys = names.map { it.lowercase() }.sorted()
        for (i in keys.indices) for (j in i + 1 until keys.size) pairs.getOrPut(keys[i] to keys[j]) { ArrayList() } += list
    }
    val feats = pairs.map { (k, lists) ->
        Feat(display[k.first] ?: k.first, display[k.second] ?: k.second, lists.map { l -> l.firstOrNull { it.cover != null } ?: l.first() }, lists.sumOf { it.size })
    }.sortedByDescending { it.plays }
    return feats to artistPlays.mapKeys { display[it.key] ?: it.key }
}

@Composable
fun FeatsPanel() {
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val data by produceState<Pair<List<Feat>, Map<String, Int>>?>(null, entries, scrobbles) {
        value = withContext(Dispatchers.Default) { computeFeats(buildEvents(entries, scrobbles)) }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    val feats: List<Feat>? = data?.first
    val plays: Map<String, Int> = data?.second ?: emptyMap()
    // les artistes sur la carte : ceux des plus gros feats
    val nodes = remember(feats) {
        val f = feats.orEmpty().take(30)
        f.flatMap { listOf(it.a, it.b) }.distinct().sortedByDescending { plays[it] ?: 0 }.take(12)
    }
    val edges = remember(feats, nodes) { feats.orEmpty().filter { it.a in nodes && it.b in nodes } }
    val shown = remember(feats, selected) { feats.orEmpty().filter { selected == null || it.a == selected || it.b == selected }.take(40) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item {
            PanelTitle("Featurings")
            Text("Qui a collaboré avec qui parmi les artistes que tu écoutes. Touche un artiste pour voir ses feats.", color = Txt2, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Space.m))
            if (feats == null) SkeletonList(4)
            else if (nodes.size < 2) Text("Pas encore assez de feats dans ton historique.", color = Txt3, fontSize = 14.sp)
            else FeatMap(nodes, edges, selected) { selected = if (selected == it) null else it }
            Spacer(Modifier.height(Space.m))
            if (selected != null) Text("Les feats de $selected", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        items(shown, key = { it.a + "×" + it.b }) { f ->
            val shape = Rounded(16)
            Row(
                Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f)
                    .bounceClick(pressedScale = 0.97f) { TidalRemote.playSequence(f.tracks.map { it.title to it.artist }) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(model = f.tracks.firstOrNull()?.cover, radius = 8.dp, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${f.a} × ${f.b}", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${f.tracks.size} son${if (f.tracks.size > 1) "s" else ""} · ${f.plays} écoutes · ${f.tracks.joinToString(", ") { it.title }}",
                        color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire leurs feats", tint = Txt, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun FeatMap(nodes: List<String>, edges: List<Feat>, selected: String?, onTap: (String) -> Unit) {
    val n = nodes.size
    val pos = remember(nodes) {
        nodes.indices.map { i ->
            val a = -Math.PI / 2 + 2 * Math.PI * i / n
            Offset((0.5 + 0.36 * cos(a)).toFloat(), (0.5 + 0.40 * sin(a)).toFloat())
        }
    }
    val idx = remember(nodes) { nodes.withIndex().associate { it.value to it.index } }
    val maxPlays = (edges.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)
    val accent = Ok
    Box(Modifier.fillMaxWidth().aspectRatio(1f).glass(Rounded(24), fill = 0.05f, edge = 0.10f)) {
        Canvas(Modifier.matchParentSize()) {
            edges.forEach { e ->
                val i = idx[e.a] ?: return@forEach
                val j = idx[e.b] ?: return@forEach
                val on = selected == null || selected == e.a || selected == e.b
                drawLine(
                    if (on && selected != null) accent else Color.White.copy(alpha = if (on) 0.35f else 0.06f),
                    Offset(pos[i].x * size.width, pos[i].y * size.height),
                    Offset(pos[j].x * size.width, pos[j].y * size.height),
                    strokeWidth = (2f + 8f * e.plays / maxPlays),
                    cap = StrokeCap.Round,
                )
            }
        }
        Layout(
            modifier = Modifier.matchParentSize(),
            content = {
                nodes.forEach { name ->
                    val on = selected == name
                    val shape = PillShape
                    Text(
                        name, color = if (on) Ink else Txt, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(shape)
                            .then(if (on) Modifier.background(accent) else Modifier.background(Color(0xFF1C1C26)).glass(shape, fill = 0.06f, edge = 0.18f))
                            .bounceClick(pressedScale = 0.9f) { onTap(name) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            },
        ) { measurables, c ->
            val maxW = 120.dp.roundToPx()
            val ps = measurables.map { it.measure(Constraints(maxWidth = maxW)) }
            layout(c.maxWidth, c.maxHeight) {
                ps.forEachIndexed { i, p ->
                    val x = (pos[i].x * c.maxWidth - p.width / 2f).toInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0))
                    val y = (pos[i].y * c.maxHeight - p.height / 2f).toInt().coerceIn(0, (c.maxHeight - p.height).coerceAtLeast(0))
                    p.place(x, y)
                }
            }
        }
    }
}

// =====================================================================================
// Paroles les plus écoutées
// =====================================================================================

object LyricWords {
    data class Word(val w: String, val score: Int, val songs: List<String>)

    val words = MutableStateFlow<List<Word>>(emptyList())
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val updatedAt = MutableStateFlow(0L)
    private var prefs: android.content.SharedPreferences? = null

    private val STOP = (
        "les des une que qui pas est suis sont mais pour dans sur avec tout tous toute toutes comme elle ils elles nous vous " +
            "leur leurs mon ton son mes tes ses donc car plus moins bien fait faire dit être etre avoir ont été ete était etait " +
            "sans sous chez entre cette ces cet quoi quand alors encore même meme aussi trop très tres peu rien jamais toujours " +
            "ouais yeah ohh ooh hey yah nan non oui ça cela celui celle ceux lui moi toi eux veux vais vas fais avais aux par " +
            "parce ici quelque comment pourquoi où dont avant après apres depuis vers notre votre nos vos sais faut peut " +
            "c'est j'ai the and you that this with for are was but not your all can get got like just don what when they them " +
            "have know she her his him its it's i'm im out now how who will would could from there their then than been were " +
            "too into ain gon gonna wanna yeh woah whoa uh huh mmh yea yeahh hmm lala nah aah ahh oooh eh oh ah"
        ).split(" ").toHashSet()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val o = JSONObject(p.getString("lyricWords", null) ?: return)
            updatedAt.value = o.optLong("at")
            val a = o.getJSONArray("w")
            words.value = (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                val s = x.optJSONArray("s") ?: JSONArray()
                Word(x.getString("w"), x.getInt("n"), (0 until s.length()).map { s.getString(it) })
            }
        }
    }

    private fun save() {
        val a = JSONArray()
        words.value.forEach { a.put(JSONObject().put("w", it.w).put("n", it.score).put("s", JSONArray(it.songs))) }
        prefs?.edit()?.putString("lyricWords", JSONObject().put("at", updatedAt.value).put("w", a).toString())?.apply()
    }

    fun isStale() = words.value.isEmpty() || System.currentTimeMillis() - updatedAt.value > 7 * DAY_MS

    /** Récupère les paroles de tes sons les plus écoutés et compte les mots. */
    fun analyze() {
        if (progress.value != null) return
        progress.value = 0 to 1
        discoverScope.launch {
            try {
                val ev = allEvents()
                val recent = ev.filter { it.ts >= System.currentTimeMillis() - 90 * DAY_MS }.ifEmpty { ev }
                val top = recent.groupBy { it.key }.values.sortedByDescending { it.size }.take(40)
                val score = HashMap<String, Int>()
                val songs = HashMap<String, MutableList<Pair<String, Int>>>()
                top.forEachIndexed { i, plays ->
                    progress.value = (i + 1) to top.size
                    val e = plays.first()
                    val r = runCatching { Lyrics.fetch(e.title, e.artist, "", e.durMs) }.getOrNull() ?: return@forEachIndexed
                    val text = r.plain ?: r.synced?.joinToString("\n") { it.text } ?: return@forEachIndexed
                    val counts = HashMap<String, Int>()
                    text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}]+"))
                        .filter { it.length >= 3 && it !in STOP }
                        .forEach { counts[it] = (counts[it] ?: 0) + 1 }
                    counts.forEach { (w, c) ->
                        score[w] = (score[w] ?: 0) + c.coerceAtMost(8) * plays.size
                        songs.getOrPut(w) { ArrayList() } += "${e.title} — ${mainArtist(e.artist)}" to c
                    }
                }
                words.value = score.entries.sortedByDescending { it.value }.take(80).map { (w, s) ->
                    Word(w, s, songs[w].orEmpty().sortedByDescending { it.second }.take(4).map { it.first })
                }
                updatedAt.value = System.currentTimeMillis()
                save()
            } catch (_: Exception) {
            } finally {
                progress.value = null
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordsPanel() {
    val words by LyricWords.words.collectAsState()
    val progress by LyricWords.progress.collectAsState()
    val at by LyricWords.updatedAt.collectAsState()
    var selected by remember { mutableStateOf<LyricWords.Word?>(null) }
    LaunchedEffect(Unit) { if (LyricWords.isStale()) LyricWords.analyze() }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item {
            PanelTitle("Tes mots")
            Text("Les mots qui reviennent le plus dans les paroles de tes 40 sons les plus écoutés (3 derniers mois).", color = Txt2, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Space.s))
            val p = progress
            if (p != null) {
                Text("Lecture des paroles… ${p.first} / ${p.second}", color = Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(Color.White.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(p.first.toFloat() / p.second.coerceAtLeast(1)).height(6.dp).clip(PillShape).background(Txt))
                }
            } else if (at > 0) {
                Text(
                    "Mis à jour le ${java.text.SimpleDateFormat("d MMMM", java.util.Locale.FRANCE).format(java.util.Date(at))} · Actualiser",
                    color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).bounceClick(pressedScale = 0.95f) { LyricWords.analyze() },
                )
            }
            Spacer(Modifier.height(Space.m))
        }
        if (words.isNotEmpty()) item {
            val max = words.first().score.toFloat()
            FlowRow(
                Modifier.fillMaxWidth().glass(Rounded(24), fill = 0.06f, edge = 0.10f).padding(Space.m),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                words.take(50).shuffled(Random(words.size)).forEach { w ->
                    val k = w.score / max
                    Text(
                        w.w,
                        color = if (selected == w) Ok else Txt.copy(alpha = 0.45f + 0.55f * k),
                        fontSize = (13 + 23 * k).sp,
                        fontWeight = if (k > 0.4f) FontWeight.ExtraBold else FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).bounceClick(pressedScale = 0.9f) { selected = if (selected == w) null else w },
                    )
                }
            }
            selected?.let { w ->
                Spacer(Modifier.height(Space.s))
                Column(Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.08f, edge = 0.14f).padding(Space.m)) {
                    Text("« ${w.w} »", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Surtout dans :", color = Txt2, fontSize = 12.sp)
                    w.songs.forEach { Text(it, color = Txt, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Spacer(Modifier.height(Space.m))
            Text("Top 15", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        items(words.take(15), key = { "w-" + it.w }) { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${words.indexOf(w) + 1}", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.w, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text(w.songs.firstOrNull().orEmpty(), color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Petite carte d'entrée dans les Stats. */
@Composable
fun StatsEntry(emoji: String, title: String, sub: String, kind: Panels.Kind) {
    val shape = Rounded(22)
    Row(
        Modifier.fillMaxWidth().glass(shape, fill = 0.08f, edge = 0.12f)
            .bounceClick(pressedScale = 0.97f) { Panels.show(kind) }
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // vraies icônes à la place des emojis
        val ic = when (emoji) {
            "🤝" -> Icons.Rounded.Groups
            "💬" -> Icons.Rounded.ChatBubbleOutline
            "⭐" -> Icons.Rounded.StarOutline
            "💿" -> Icons.Rounded.Album
            "🎤" -> Icons.Rounded.Mic
            else -> Icons.Rounded.MusicNote
        }
        Icon(ic, contentDescription = null, tint = Ok, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(sub, color = Txt2, fontSize = 13.sp)
        }
    }
}
