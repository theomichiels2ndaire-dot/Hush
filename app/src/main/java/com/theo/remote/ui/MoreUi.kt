package com.theo.remote.ui

import com.theo.remote.playAlbum
import com.theo.remote.playTitle

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.AmbientSound
import com.theo.remote.Features
import com.theo.remote.Follow
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.StatsExclude
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val moreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** GET simple (MusicBrainz, Wikipédia, Ticketmaster). */
private fun httpGet(url: String): String? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 15_000
    c.setRequestProperty("User-Agent", "HushAndroid/1.0 (appli perso)")
    c.setRequestProperty("Accept", "application/json")
    try {
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().readText()
    } finally {
        c.disconnect()
    }
}.getOrNull()

private fun panelList(): PaddingValues = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp)

// =====================================================================================
// Quoi de neuf
// =====================================================================================

object WhatsNew {
    const val ID = "2026-10-31b"
    val items = listOf(
        "Nouvelle identité : icône, écran de démarrage et accent lilas",
        "Recherche dans les Réglages",
        "Écran « Tous les outils » et accueil réorganisable",
        "Diagnostic et journal des plantages",
        "Wrapped de l'année et récap du dimanche",
        "Classement de tes albums",
        "Concerts près de chez toi",
        "Sons d'ambiance pour dormir",
        "Crédits du son et clip YouTube",
        "Sons exclus des stats",
        "Le n°1 en France il y a 5 ans",
        "Noir pur, taille du texte, mode gaucher, widget stats",
        "« Un son par artiste »",
        "Thèmes de saison, setlists des concerts, sons souvent passés",
        "Quiz paroles, aléatoire malin, niveaux du blind test",
        "Récompenses : XP, niveaux, et des icônes, couleurs et effets à débloquer",
    )
}

@Composable
fun WhatsNewDialog() {
    val ctx = LocalContext.current
    val f by Features.flags.collectAsState()
    val prefs = remember { ctx.getSharedPreferences("hush", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(prefs.getString("whatsNewSeen", null) != WhatsNew.ID) }
    if (!f.whatsNew || !show) return
    fun close() { prefs.edit().putString("whatsNewSeen", WhatsNew.ID).apply(); show = false }
    androidx.compose.ui.window.Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier.fillMaxWidth().clip(Rounded(28)).background(Color(0xFF14151F)).glass(Rounded(28), fill = 0.04f, edge = 0.14f).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Quoi de neuf", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Dans cette version de Hush :", color = Txt2, fontSize = 15.sp)
            WhatsNew.items.forEach { Text(it, color = Txt, fontSize = 15.sp, lineHeight = 19.sp) }
            Spacer(Modifier.height(4.dp))
            PillButton("C'est parti") { close() }
        }
    }
}

// =====================================================================================
// Mode invité et sons exclus des stats
// =====================================================================================

/** Réglages → Tes goûts : sons exclus des stats. */
@Composable
fun StatsExcludeCard() {
    val f by Features.flags.collectAsState()
    val items by StatsExclude.items.collectAsState()
    Column(Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.07f, edge = 0.12f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Ce qui ne compte pas dans tes stats", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        if (f.statsExclude) {
            if (items.isEmpty()) Text("Appui long sur un son → « Ne pas compter dans mes stats ».", color = Txt3, fontSize = 13.sp, lineHeight = 17.sp)
            items.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(i.artist, color = Txt2, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(
                        "Recompter", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { StatsExclude.include(i.key) }.padding(6.dp),
                    )
                }
            }
        }
    }
}

// =====================================================================================
// Classement des albums
// =====================================================================================

private data class AlbumRank(val album: String, val artist: String, val cover: Any?, val plays: Int, val heard: Set<String>)

private fun computeAlbums(): List<AlbumRank> {
    data class Acc(val album: String, val artist: String, var cover: Any?, var plays: Int = 0, val heard: MutableSet<String> = HashSet())
    val map = HashMap<String, Acc>()
    fun add(album: String, artist: String, title: String, cover: Any?, n: Int) {
        if (album.isBlank() || n <= 0) return
        val a = mainArtist(artist)
        val k = album.trim().lowercase() + "|" + a.lowercase()
        val acc = map.getOrPut(k) { Acc(album.trim(), a, cover) }
        acc.plays += n
        acc.heard += normTitle(title)
        if (acc.cover == null) acc.cover = cover
    }
    for (e in History.entries.value) {
        val n = e.plays.count { !StatsExclude.skip(e.key, it) }
        add(e.album, e.artist, e.title, e.coverUrl ?: e.artPath?.let { File(it) }, n)
    }
    // les scrobbles Last.fm d'avant Hush (on évite de compter deux fois les sons déjà dans l'historique)
    val hushStart = History.entries.value.flatMap { it.plays }.minOrNull() ?: Long.MAX_VALUE
    for (s in LastFm.scrobbles.value) {
        if (s.ts >= hushStart) continue
        if (StatsExclude.skip(History.keyOf(s.title, s.artist), s.ts)) continue
        add(s.album, s.artist, s.title, s.image, 1)
    }
    return map.values.sortedByDescending { it.plays }.take(30).map { AlbumRank(it.album, it.artist, it.cover, it.plays, it.heard) }
}

@Composable
fun AlbumsPanel() {
    val entries by History.entries.collectAsState()
    val v by StatsExclude.version.collectAsState()
    val albums by produceState<List<AlbumRank>?>(null, entries, v) { value = withContext(Dispatchers.Default) { computeAlbums() } }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = panelList(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            PanelTitle("Tes albums")
            Text("Tes albums les plus écoutés, et combien de leurs sons tu connais. Toucher pour lancer l'album.", color = Txt2, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Space.s))
            if (albums == null) SkeletonList(6)
            else if (albums.orEmpty().isEmpty()) Text("Pas encore assez d'écoutes.", color = Txt3, fontSize = 13.sp)
        }
        itemsIndexed(albums.orEmpty(), key = { _, a -> a.album + a.artist }) { i, a -> AlbumRow(i + 1, a, completion = i < 12) }
    }
}

@Composable
private fun AlbumRow(rank: Int, a: AlbumRank, completion: Boolean) {
    // nombre de sons de l'album (pour les premiers seulement : ça demande Tidal)
    val total by produceState<Int?>(null, a.album, a.artist, completion) {
        if (!completion) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { TidalApi.findAlbum(a.album, a.artist)?.let { TidalApi.albumPage(it).tracks.map { t -> normTitle(t.title) }.toSet() } }.getOrNull()
                ?.let { all -> if (all.isEmpty()) null else all.size * 1000 + all.count { it in a.heard } }
        }
    }
    val t = total
    Row(
        Modifier.fillMaxWidth().clip(Rounded(14)).bounceClick(pressedScale = 0.97f) { TidalRemote.playAlbum(a.album, a.artist) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Cover(model = a.cover, radius = 8.dp, modifier = Modifier.size(54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.album, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${a.artist} · ${a.plays} écoutes", color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t != null) {
                val size = t / 1000
                val heard = (t % 1000).coerceAtMost(size)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(4.dp).clip(PillShape).background(Color.White.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxWidth(heard / size.coerceAtLeast(1).toFloat()).height(4.dp).clip(PillShape).background(if (heard >= size) Ok else Txt))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (heard >= size) "En entier" else "$heard/$size", color = if (heard >= size) Ok else Txt3, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =====================================================================================
// Crédits du son (MusicBrainz) et clip
// =====================================================================================

object Credits {
    data class Info(val year: String?, val album: String?, val roles: List<Pair<String, List<String>>>)

    val target = MutableStateFlow<Pair<String, String>?>(null)
    private val cache = HashMap<String, Info?>()

    fun open(title: String, artist: String) { target.value = title to artist; Panels.show(Panels.Kind.Credits) }

    private val ROLE = linkedMapOf(
        "composer" to "Composition", "lyricist" to "Paroles", "writer" to "Écriture", "producer" to "Production",
        "arranger" to "Arrangement", "mix" to "Mixage", "mastering" to "Mastering", "recording" to "Enregistrement",
        "engineer" to "Ingé son", "programming" to "Programmation", "vocal" to "Voix", "instrument" to "Musiciens",
    )

    suspend fun fetch(title: String, artist: String): Info? = withContext(Dispatchers.IO) {
        val key = "$title|$artist"
        if (cache.containsKey(key)) return@withContext cache[key]
        val clean = title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim().ifBlank { title }
        val t1 = clean.replace("\"", "")
        val a1 = mainArtist(artist).replace("\"", "")
        val q = "recording:\"$t1\" AND artist:\"$a1\""
        val search = httpGet("https://musicbrainz.org/ws/2/recording/?query=${enc(q)}&fmt=json&limit=5") ?: return@withContext null
        val recs = JSONObject(search).optJSONArray("recordings") ?: JSONArray()
        val best = (0 until recs.length()).map { recs.getJSONObject(it) }.filter { it.optInt("score") >= 70 }
            .maxByOrNull { it.optJSONArray("relations")?.length() ?: 0 } ?: run { cache[key] = null; return@withContext null }
        delay(1100) // MusicBrainz : une requête par seconde
        val id = best.getString("id")
        val body = httpGet("https://musicbrainz.org/ws/2/recording/$id?inc=releases+artist-rels+work-rels+work-level-rels&fmt=json")
        val o = body?.let { JSONObject(it) } ?: best
        val roles = LinkedHashMap<String, MutableSet<String>>()
        fun addRels(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val name = r.optJSONObject("artist")?.optString("name") ?: continue
                val label = ROLE[r.optString("type")] ?: continue
                roles.getOrPut(label) { LinkedHashSet() } += name
            }
        }
        val rels = o.optJSONArray("relations")
        addRels(rels)
        // auteurs et compositeurs : sur l'« œuvre » liée à l'enregistrement
        if (rels != null) for (i in 0 until rels.length()) rels.getJSONObject(i).optJSONObject("work")?.optJSONArray("relations")?.let { addRels(it) }
        val releases = o.optJSONArray("releases")
        val album = releases?.optJSONObject(0)?.optString("title")?.ifBlank { null }
        val date = o.optString("first-release-date").ifBlank { releases?.optJSONObject(0)?.optString("date").orEmpty() }
        val sorted = ROLE.values.mapNotNull { l -> roles[l]?.let { l to it.toList() } }
        Info(date.take(4).ifBlank { null }, album, sorted).also { cache[key] = it }
    }

    fun openClip(context: Context, title: String, artist: String) {
        val q = enc("$title ${mainArtist(artist)} clip officiel")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
fun CreditsPanel() {
    val t by Credits.target.collectAsState()
    val (title, artist) = t ?: return
    val ctx = LocalContext.current
    val f by Features.flags.collectAsState()
    var loaded by remember(t) { mutableStateOf(false) }
    val info by produceState<Credits.Info?>(null, t) { value = runCatching { Credits.fetch(title, artist) }.getOrNull(); loaded = true }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = panelList(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            PanelTitle("Crédits")
            Text(title, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Txt2, fontSize = 15.sp)
            Spacer(Modifier.height(Space.m))
            val i = info
            when {
                !loaded -> SkeletonLines(4)
                i == null || (i.roles.isEmpty() && i.year == null) -> Text("Pas de crédits trouvés pour ce son (source : MusicBrainz, base libre).", color = Txt3, fontSize = 13.sp, lineHeight = 20.sp)
                else -> Column(Modifier.fillMaxWidth().glass(Rounded(20), fill = 0.07f, edge = 0.12f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    i.year?.let { CreditLine("Sortie", it) }
                    i.album?.let { CreditLine("Album", it) }
                    i.roles.forEach { (role, names) -> CreditLine(role, names.joinToString(", ")) }
                }
            }
            Spacer(Modifier.height(Space.m))
            if (f.clip) PillButton("Voir le clip sur YouTube", filled = false) { Credits.openClip(ctx, title, artist) }
            Spacer(Modifier.height(Space.s))
            Text("Source : MusicBrainz", color = Txt3, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CreditLine(label: String, value: String) {
    Column {
        Text(label, color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    }
}

// =====================================================================================
// Concerts près de chez toi (Ticketmaster)
// =====================================================================================

object Concerts {
    data class Event(val id: String, val artist: String, val name: String, val date: String, val time: String?, val venue: String, val city: String, val postal: String, val url: String) {
        /** Île-de-France : codes postaux 75, 77, 78, 91, 92, 93, 94, 95 */
        val nearby get() = postal.take(2) in setOf("75", "77", "78", "91", "92", "93", "94", "95")
    }

    val events = MutableStateFlow<List<Event>>(emptyList())
    val loading = MutableStateFlow(false)
    val key = MutableStateFlow("")
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        key.value = p.getString("tmKey", "").orEmpty()
        events.value = runCatching { decode(JSONArray(p.getString("concerts", "[]"))) }.getOrDefault(emptyList())
    }

    fun setKey(k: String) { key.value = k.trim(); prefs?.edit()?.putString("tmKey", key.value)?.apply() }

    private fun decode(a: JSONArray) = (0 until a.length()).map { val o = a.getJSONObject(it)
        Event(o.getString("id"), o.optString("ar"), o.optString("n"), o.optString("d"), o.optString("t").ifBlank { null }, o.optString("v"), o.optString("c"), o.optString("p"), o.optString("u")) }

    private fun encode(l: List<Event>) = JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("ar", it.artist).put("n", it.name).put("d", it.date).put("t", it.time ?: "").put("v", it.venue).put("c", it.city).put("p", it.postal).put("u", it.url)) } }

    /** Tes artistes : les plus écoutés ces 6 derniers mois + ceux que tu suis. */
    private fun artists(): List<String> {
        val since = System.currentTimeMillis() - 180L * 24 * 3600_000
        val top = buildEvents(History.entries.value, LastFm.scrobbles.value).filter { it.ts >= since }
            .groupingBy { mainArtist(it.artist) }.eachCount().entries.sortedByDescending { it.value }.take(20).map { it.key }
        return (Follow.artists.value + top).distinctBy { it.lowercase() }.take(30)
    }

    /** Cherche les concerts ; renvoie les nouveaux (pas encore vus). */
    suspend fun refresh(): List<Event> = withContext(Dispatchers.IO) {
        val k = key.value
        if (k.isBlank() || loading.value) return@withContext emptyList()
        loading.value = true
        try {
            val out = ArrayList<Event>()
            for (a in artists()) {
                val body = httpGet("https://app.ticketmaster.com/discovery/v2/events.json?apikey=${enc(k)}&keyword=${enc(a)}&countryCode=FR&classificationName=music&size=20&sort=date,asc")
                delay(260) // 5 requêtes / seconde max
                val arr = body?.let { JSONObject(it).optJSONObject("_embedded")?.optJSONArray("events") } ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val emb = e.optJSONObject("_embedded")
                    val atts = emb?.optJSONArray("attractions")
                    val match = atts != null && (0 until atts.length()).any { atts.getJSONObject(it).optString("name").equals(a, ignoreCase = true) }
                    if (!match) continue
                    val venue = emb?.optJSONArray("venues")?.optJSONObject(0)
                    val start = e.optJSONObject("dates")?.optJSONObject("start")
                    out += Event(
                        e.optString("id"), a, e.optString("name"), start?.optString("localDate").orEmpty(), start?.optString("localTime")?.take(5)?.ifBlank { null },
                        venue?.optString("name").orEmpty(), venue?.optJSONObject("city")?.optString("name").orEmpty(), venue?.optString("postalCode").orEmpty(), e.optString("url"),
                    )
                }
            }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val list = out.filter { it.date >= today }.distinctBy { it.id }.sortedBy { it.date }
            val p = prefs
            val seen = p?.getStringSet("concertsSeen", emptySet()).orEmpty()
            val fresh = list.filter { it.id !in seen }
            events.value = list
            p?.edit()?.putString("concerts", encode(list).toString())?.putStringSet("concertsSeen", seen + list.map { it.id })?.putLong("concertsAt", System.currentTimeMillis())?.apply()
            // la toute première recherche ne compte pas comme « nouveau »
            if (seen.isEmpty()) emptyList() else fresh
        } finally {
            loading.value = false
        }
    }

    /** Vérification quotidienne (avec le contrôle des sorties) : notif pour les nouvelles dates près de chez toi. */
    suspend fun dailyCheck(context: Context) {
        val ctx = context.applicationContext
        init(ctx); Features.init(ctx); History.init(ctx); LastFm.init(ctx); Follow.init(ctx)
        if (!Features.flags.value.concerts || key.value.isBlank()) return
        val last = prefs?.getLong("concertsAt", 0) ?: 0
        if (System.currentTimeMillis() - last < 20 * 3600_000L) return
        val fresh = refresh().filter { it.nearby }
        if (fresh.isEmpty()) return
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("hush_concerts") == null) {
            nm.createNotificationChannel(android.app.NotificationChannel("hush_concerts", "Concerts de tes artistes", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        fresh.take(3).forEachIndexed { i, e ->
            val open = android.app.PendingIntent.getActivity(ctx, 60 + i, Intent(Intent.ACTION_VIEW, Uri.parse(e.url)), android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(ctx, "hush_concerts")
                .setSmallIcon(com.theo.remote.R.drawable.ic_notif)
                .setContentTitle("${e.artist} en concert")
                .setContentText("${prettyDate(e.date)} · ${e.venue}, ${e.city}")
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify(200 + i, n) }
        }
    }

    fun prettyDate(d: String): String = runCatching {
        SimpleDateFormat("EEE d MMM yyyy", Locale.FRANCE).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!)
    }.getOrDefault(d)
}

@Composable
fun ConcertsPanel() {
    val ctx = LocalContext.current
    val events by Concerts.events.collectAsState()
    val loading by Concerts.loading.collectAsState()
    val key by Concerts.key.collectAsState()
    LaunchedEffect(key) { if (key.isNotBlank() && Concerts.events.value.isEmpty()) runCatching { Concerts.refresh() } }
    val near = events.filter { it.nearby }
    val far = events.filterNot { it.nearby }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = panelList(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            PanelTitle("Concerts")
            if (key.isBlank()) {
                ConcertsKeyCard()
            } else {
                Text(
                    if (loading) "Je cherche les dates de tes artistes…" else "Les dates de tes artistes les plus écoutés et de ceux que tu suis. Toucher pour les billets.",
                    color = Txt2, fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(Space.s))
                Text(
                    if (loading) "…" else "Actualiser", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { moreScope.launch { Concerts.refresh() } }.padding(vertical = 4.dp),
                )
                if (!loading && events.isEmpty()) Text("Aucune date trouvée en France pour l'instant.", color = Txt3, fontSize = 15.sp)
                if (Features.flags.value.setlists) Text(
                    "Voir les setlists des derniers concerts ›", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { Panels.show(Panels.Kind.Setlists) }.padding(vertical = 4.dp),
                )
            }
        }
        if (near.isNotEmpty()) item { Text("Près de chez toi · Île-de-France", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = Space.m)) }
        items(near, key = { "n" + it.id }) { e -> ConcertRow(e) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
        if (far.isNotEmpty()) item { Text("Ailleurs en France", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = Space.m)) }
        items(far, key = { "f" + it.id }) { e -> ConcertRow(e) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    }
}

@Composable
private fun ConcertRow(e: Concerts.Event, onClick: () -> Unit) {
    val shape = Rounded(16)
    Row(
        Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).bounceClick(pressedScale = 0.97f, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val parts = Concerts.prettyDate(e.date).split(" ")
            Text(parts.getOrNull(1).orEmpty(), color = Ok, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(parts.getOrNull(2).orEmpty(), color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.artist, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.venue} · ${e.city}", color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Concerts.prettyDate(e.date) + (e.time?.let { " · $it" } ?: ""), color = Txt3, fontSize = 11.sp)
        }
    }
}

/** Clé Ticketmaster (gratuite) : à coller une fois. */
@Composable
fun ConcertsKeyCard() {
    val ctx = LocalContext.current
    val key by Concerts.key.collectAsState()
    var text by remember { mutableStateOf(key) }
    Column(Modifier.fillMaxWidth().glass(Rounded(20), fill = 0.08f, edge = 0.14f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Clé Ticketmaster", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Les dates viennent de Ticketmaster. Crée un compte gratuit sur developer.ticketmaster.com, puis copie ta « Consumer Key » ici.",
            color = Txt2, fontSize = 13.sp, lineHeight = 18.sp,
        )
        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp), fill = 0.10f, edge = 0.16f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (text.isEmpty()) Text("Colle ta clé ici", color = Txt3, fontSize = 13.sp)
            BasicTextField(
                value = text, onValueChange = { text = it.trim().take(64) }, singleLine = true,
                textStyle = TextStyle(color = Txt, fontSize = 13.sp), cursorBrush = SolidColor(Txt), modifier = Modifier.fillMaxWidth(),
            )
        }
        PillButton("Enregistrer") { Concerts.setKey(text); Follow.schedule(ctx); moreScope.launch { Concerts.refresh() }; TidalRemote.showToast("Clé enregistrée") }
    }
}

// =====================================================================================
// Le n°1 en France il y a 5 ans (Wikipédia)
// =====================================================================================

object ChartsAgo {
    data class Hit(val title: String, val artist: String, val date: String, val year: Int)

    val hit = MutableStateFlow<Hit?>(null)
    private var loadedFor = ""

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private fun clean(x: String): String {
        var s = x
        s = s.replace(Regex("<ref[^>]*/>"), "").replace(Regex("<ref[\\s\\S]*?</ref>"), "")
        repeat(3) { s = s.replace(Regex("\\{\\{[^{}]*\\}\\}"), "") }
        s = s.replace(Regex("\\[\\[[^\\]|]*\\|([^\\]]*)\\]\\]"), "$1").replace(Regex("\\[\\[([^\\]]*)\\]\\]"), "$1")
        s = s.replace("'''", "").replace("''", "").replace("&nbsp;", " ").replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), "")
        // attributs de cellule : rowspan="3" | texte
        s = s.replace(Regex("^\\s*(?:[a-z-]+=\"?[^\"|]*\"?\\s*)+\\|(?!\\|)"), "")
        return s.trim()
    }

    fun load(context: Context) {
        val cal = Calendar.getInstance().apply { add(Calendar.YEAR, -5) }
        val year = cal.get(Calendar.YEAR)
        val tag = "$year-${cal.get(Calendar.WEEK_OF_YEAR)}"
        if (loadedFor == tag && hit.value != null) return
        val p = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
        // cache de la semaine
        p.getString("chartsAgo", null)?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optString("tag") == tag }?.let {
            hit.value = Hit(it.getString("t"), it.getString("a"), it.getString("d"), year); loadedFor = tag; return
        }
        loadedFor = tag
        moreScope.launch {
            val h = runCatching { find(year, cal.timeInMillis) }.getOrNull() ?: return@launch
            hit.value = h
            p.edit().putString("chartsAgo", JSONObject().put("tag", tag).put("t", h.title).put("a", h.artist).put("d", h.date).toString()).apply()
        }
    }

    private fun find(year: Int, target: Long): Hit? {
        val pages = listOf("List of number-one hits of $year (France)", "List of number-one singles of $year (France)")
        val wiki = pages.firstNotNullOfOrNull { page ->
            httpGet("https://en.wikipedia.org/w/api.php?action=parse&page=${enc(page)}&prop=wikitext&format=json&formatversion=2&redirects=1")
                ?.let { runCatching { JSONObject(it).getJSONObject("parse").getString("wikitext") }.getOrNull() }
        } ?: return null
        val start = wiki.indexOf("{|")
        if (start < 0) return null
        val end = wiki.indexOf("\n|}", start).let { if (it < 0) wiki.length else it }
        val table = wiki.substring(start, end)
        val dateRx = Regex("^(\\d{1,2}) (${MONTHS.joinToString("|")})$")
        var artist = ""; var title = ""
        var best: Hit? = null
        for (row in table.split(Regex("\\n\\|-[^\\n]*"))) {
            val cells = row.lines().filter { it.startsWith("|") || it.startsWith("!") }
                .filterNot { it.startsWith("{|") || it.startsWith("|+") || it.startsWith("|}") }
                .flatMap { it.substring(1).split("||", "!!") }
                .map { clean(it) }
            val di = cells.indexOfFirst { dateRx.matches(it) }
            if (di < 0) continue
            val m = dateRx.find(cells[di])!!
            val c = Calendar.getInstance().apply { clear(); set(year, MONTHS.indexOf(m.groupValues[2]), m.groupValues[1].toInt()) }
            val after = cells.drop(di + 1).filter { it.isNotBlank() }
            val quoted = after.firstOrNull { it.startsWith("\"") || it.startsWith("“") }
            if (quoted != null) {
                title = quoted.trim('"', '“', '”', ' ')
                artist = after.firstOrNull { it != quoted }?.let { it.replace(" and ", " & ").replace(" featuring ", " feat. ") } ?: artist
            }
            if (c.timeInMillis <= target && title.isNotBlank()) best = Hit(title, artist, "${m.groupValues[1]} ${MONTHS.indexOf(m.groupValues[2])}", year)
        }
        return best
    }
}

@Composable
fun ChartsAgoCard() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChartsAgo.load(ctx) }
    val h by ChartsAgo.hit.collectAsState()
    val x = h ?: return
    val shape = Rounded(20)
    Row(
        Modifier.padding(horizontal = Space.gutter).fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f)
            .bounceClick(pressedScale = 0.97f) { TidalRemote.playTitle(x.title, x.artist) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("", fontSize = 30.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Il y a 5 ans · n°1 en France", color = Txt3, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            Text(x.title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${x.artist} · cette semaine-là en ${x.year}", color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).clip(PillShape).background(Txt), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire", tint = Ink, modifier = Modifier.size(24.dp))
        }
    }
}

// =====================================================================================
// Sons d'ambiance
// =====================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmbientPanel() {
    val playing by AmbientSound.playing.collectAsState()
    val volume by AmbientSound.volume.collectAsState()
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = panelList(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item {
            PanelTitle("Sons d'ambiance")
            Text("Un fond sonore doux, joué par-dessus ta musique (ou tout seul). Il s'arrête avec le minuteur de sommeil.", color = Txt2, fontSize = 13.sp, lineHeight = 20.sp)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                AmbientSound.Kind.values().forEach { k ->
                    val on = playing == k
                    val emoji = when (k) { AmbientSound.Kind.Rain -> ""; AmbientSound.Kind.Waves -> ""; AmbientSound.Kind.Pink -> ""; AmbientSound.Kind.Brown -> ""; AmbientSound.Kind.White -> "" }
                    val shape = Rounded(20)
                    Column(
                        Modifier.width(150.dp).heightIn(min = 92.dp).clip(shape)
                            .then(if (on) Modifier.background(Ok.copy(alpha = 0.25f)) else Modifier)
                            .glass(shape, fill = 0.07f, edge = if (on) 0.4f else 0.12f)
                            .bounceClick(pressedScale = 0.95f) { if (on) AmbientSound.stop() else AmbientSound.play(k) }
                            .padding(14.dp),
                    ) {
                        Text(emoji, fontSize = 26.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(k.label, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        Text(if (on) "En cours · toucher pour couper" else "Toucher pour lancer", color = if (on) Ok else Txt3, fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            Text("Volume", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = volume, onValueChange = { AmbientSound.setVolume(it) },
                colors = SliderDefaults.colors(thumbColor = Txt, activeTrackColor = Txt, inactiveTrackColor = Color.White.copy(alpha = 0.14f)),
                modifier = Modifier.blockDismiss(),
            )
        }
    }
}
