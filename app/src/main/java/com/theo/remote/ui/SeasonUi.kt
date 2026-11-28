package com.theo.remote.ui

import com.theo.remote.playSequence

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Blocked
import com.theo.remote.Features
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.SkipTracker
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================================
// Thèmes de saison
// =====================================================================================

object Season {
    enum class Kind(val label: String, val emojis: List<String>, val glow: Color, val greet: String) {
        Halloween("Halloween", listOf("🎃", "🦇", "🍂", "👻", "🕸️"), Color(0xFFFF7A1A), "🎃"),
        Noel("Noël", listOf("❄️", "✨", "❄️", "🎄", "❄️"), Color(0xFF9FD8FF), "🎄"),
        Ete("Été", listOf("☀️", "✨", "🌴", "🌊", "✨"), Color(0xFFFFC46B), "☀️"),
        /** récompense niveau 15 : des notes qui flottent (hors saisons) */
        Notes("Notes", listOf("🎵", "🎶", "✨", "🎵", "🎧"), Color(0xFFB9A6FF), ""),
    }

    /** Le thème du moment (ou celui choisi dans les Réglages). */
    fun current(f: Features.Flags = Features.flags.value): Kind? {
        if (!f.seasonal) return null
        when (f.season) {
            "halloween" -> return Kind.Halloween
            "noel" -> return Kind.Noel
            "ete" -> return Kind.Ete
        }
        val c = Calendar.getInstance()
        val m = c.get(Calendar.MONTH)
        val d = c.get(Calendar.DAY_OF_MONTH)
        return when {
            (m == Calendar.OCTOBER && d >= 20) || (m == Calendar.NOVEMBER && d <= 2) -> Kind.Halloween
            m == Calendar.DECEMBER || (m == Calendar.JANUARY && d <= 2) -> Kind.Noel
            (m == Calendar.JUNE && d >= 21) || m == Calendar.JULY || m == Calendar.AUGUST -> Kind.Ete
            else -> null
        }
    }
}

/** Lueur + petits éléments qui flottent en haut de l'accueil. */
@Composable
fun SeasonOverlay() {
    val f by Features.flags.collectAsState()
    com.theo.remote.Rewards.equipped.collectAsState().value
    com.theo.remote.Rewards.xp.collectAsState().value
    val kind = Season.current(f) ?: (if (f.rewards && com.theo.remote.Rewards.isOn("home:notes")) Season.Kind.Notes else null) ?: return
    val anim = rememberInfiniteTransition(label = "season")
    val t by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "seasonT")
    val particles = remember(kind) {
        val r = Random(kind.ordinal + 7)
        List(14) { i -> floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 0.8f, i.toFloat(), r.nextFloat()) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(420.dp)) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }
        // lueur aux couleurs de la saison
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(kind.glow.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.05f), radius = size.width * 0.9f),
                radius = size.width * 0.9f, center = Offset(size.width * 0.85f, size.height * 0.05f),
            )
        }
        if (!f.eco) particles.forEach { p ->
            val emoji = kind.emojis[p[3].toInt() % kind.emojis.size]
            val rising = kind == Season.Kind.Ete
            Text(
                emoji,
                fontSize = (12 + p[2] * 10).sp,
                modifier = Modifier.graphicsLayer {
                    val prog = (t * p[2] + p[1]) % 1f
                    translationX = p[0] * wPx + sin((prog * 6.28f) + p[4] * 6f) * 18f
                    translationY = if (rising) hPx * (1f - prog) else hPx * prog - 40f
                    alpha = (0.55f * (1f - prog)).coerceIn(0f, 0.55f) + 0.1f
                    rotationZ = sin(prog * 6.28f + p[4]) * 20f
                },
            )
        }
    }
}

/** Petit emoji à côté du « Bonsoir, Theo ». */
fun seasonGreeting(): String = Season.current()?.let { " " + it.greet }.orEmpty()

// =====================================================================================
// Sons souvent passés
// =====================================================================================

@Composable
fun SkipSuggestionCard() {
    val skips by SkipTracker.skips.collectAsState()
    val ignored by SkipTracker.ignored.collectAsState()
    val blocked by Blocked.items.collectAsState()
    val s = remember(skips, ignored, blocked) { SkipTracker.suggestions() }.firstOrNull() ?: return
    val shape = Rounded(20)
    Column(Modifier.padding(horizontal = Space.gutter).fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tu le passes souvent", color = Txt3, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        Text("« ${s.title} » · ${s.artist}", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Passé ${s.count} fois au bout de quelques secondes. On arrête de te le proposer ?", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PillButton("Le masquer") { Blocked.block(s.title, s.artist); SkipTracker.forget(s.key); TidalRemote.showToast("On ne te proposera plus « ${s.title} »") }
            PillButton("Je le garde", filled = false) { SkipTracker.ignore(s.key) }
        }
    }
}

@Composable
fun SkipsPanel() {
    val skips by SkipTracker.skips.collectAsState()
    val list = remember(skips) { skips.values.sortedByDescending { it.count } }
    val state = rememberLazyListState()
    DismissWhenAtTop(state)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = state, contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            PanelTitle("Sons souvent passés")
            Text("Les sons que tu passes entre 1 et 30 secondes après le début.", color = Txt2, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Space.s))
            if (list.isEmpty()) Text("Rien pour l'instant.", color = Txt3, fontSize = 13.sp)
        }
        items(list, key = { it.key }) { s ->
            Row(Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.06f, edge = 0.10f).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.artist} · passé ${s.count}×", color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Masquer", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { Blocked.block(s.title, s.artist); SkipTracker.forget(s.key) }.padding(6.dp),
                )
                Text(
                    "Oublier", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) { SkipTracker.forget(s.key) }.padding(6.dp),
                )
            }
        }
    }
}

// =====================================================================================
// Setlists (setlist.fm)
// =====================================================================================

object Setlists {
    data class Show(val id: String, val artist: String, val date: String, val venue: String, val city: String, val songs: List<String>)

    val key = MutableStateFlow("")
    val open = MutableStateFlow<Show?>(null)
    private var prefs: android.content.SharedPreferences? = null
    private val cache = HashMap<String, List<Show>>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        key.value = p.getString("setlistKey", "").orEmpty()
    }

    fun setKey(k: String) { key.value = k.trim(); prefs?.edit()?.putString("setlistKey", key.value)?.apply() }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("x-api-key", key.value)
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Accept-Language", "fr")
        try { if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }.getOrNull()

    /** Derniers concerts d'un artiste (avec des sons), le plus récent d'abord. */
    suspend fun recent(artist: String, date: String? = null): List<Show> = withContext(Dispatchers.IO) {
        if (key.value.isBlank()) return@withContext emptyList()
        val ck = "$artist|$date"
        cache[ck]?.let { return@withContext it }
        val q = "artistName=" + URLEncoder.encode(artist, "UTF-8") + (date?.let { "&date=$it" } ?: "") + "&p=1"
        val body = get("https://api.setlist.fm/rest/1.0/search/setlists?$q") ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("setlist") }.getOrNull() ?: return@withContext emptyList()
        val out = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val songs = ArrayList<String>()
            val sets = o.optJSONObject("sets")?.optJSONArray("set")
            if (sets != null) for (s in 0 until sets.length()) {
                val ss = sets.getJSONObject(s).optJSONArray("song") ?: continue
                for (k in 0 until ss.length()) ss.getJSONObject(k).optString("name").takeIf { it.isNotBlank() }?.let { songs += it }
            }
            if (songs.isEmpty()) return@mapNotNull null
            val venue = o.optJSONObject("venue")
            Show(
                o.optString("id"), o.optJSONObject("artist")?.optString("name") ?: artist, o.optString("eventDate"),
                venue?.optString("name").orEmpty(), venue?.optJSONObject("city")?.optString("name").orEmpty(), songs,
            )
        }
        cache[ck] = out
        out
    }

    fun prettyDate(d: String): String = runCatching {
        SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(SimpleDateFormat("dd-MM-yyyy", Locale.US).parse(d)!!)
    }.getOrDefault(d)

    /** Après un concert de tes artistes (vu dans « Concerts ») : notif quand la setlist est dispo. */
    suspend fun checkAfterConcerts(context: Context) {
        val ctx = context.applicationContext
        init(ctx); Concerts.init(ctx)
        if (!Features.flags.value.setlists || key.value.isBlank()) return
        val p = prefs ?: return
        val notified = p.getStringSet("setlistNotified", emptySet()).orEmpty()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val week = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 7L * 24 * 3600_000))
        val past = Concerts.events.value.filter { it.nearby && it.date < today && it.date >= week && it.id !in notified }
        for (e in past.take(3)) {
            val d = runCatching { SimpleDateFormat("dd-MM-yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(e.date)!!) }.getOrNull() ?: continue
            val show = recent(e.artist, d).firstOrNull() ?: continue
            delay(700)
            p.edit().putStringSet("setlistNotified", notified + e.id).apply()
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel("hush_concerts") == null) {
                nm.createNotificationChannel(android.app.NotificationChannel("hush_concerts", "Concerts de tes artistes", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = android.app.PendingIntent.getActivity(ctx, 70, android.content.Intent(ctx, com.theo.remote.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(ctx, "hush_concerts")
                .setSmallIcon(com.theo.remote.R.drawable.ic_notif)
                .setContentTitle("🎤 La setlist de ${show.artist} est dispo")
                .setContentText("${show.songs.size} sons joués à ${show.city} · à réécouter dans Outils → Setlists")
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify(300, n) }
        }
    }
}

@Composable
fun SetlistsPanel() {
    val key by Setlists.key.collectAsState()
    val open by Setlists.open.collectAsState()
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    val shows by produceState<List<Setlists.Show>?>(null, key, entries.size, scrobbles.size) {
        if (key.isBlank()) { value = emptyList(); return@produceState }
        val since = System.currentTimeMillis() - 180L * 24 * 3600_000
        val artists = withContext(Dispatchers.Default) {
            buildEvents(entries, scrobbles).filter { it.ts >= since }.groupingBy { mainArtist(it.artist) }.eachCount()
                .entries.sortedByDescending { it.value }.take(10).map { it.key }
        }
        val out = ArrayList<Setlists.Show>()
        for (a in artists) {
            runCatching { Setlists.recent(a).firstOrNull() }.getOrNull()?.let { out += it; value = out.toList() }
            delay(600) // setlist.fm : 2 requêtes / seconde max
        }
        value = out.toList()
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        val o = open
        if (o != null) {
            item {
                PanelTitle(o.artist)
                Text("${Setlists.prettyDate(o.date)} · ${o.venue}, ${o.city}", color = Txt2, fontSize = 13.sp)
                Spacer(Modifier.height(Space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PillButton("Revivre le concert") { TidalRemote.playSequence(o.songs.map { it to o.artist }) }
                    PillButton("Retour", filled = false) { Setlists.open.value = null }
                }
                Spacer(Modifier.height(Space.m))
            }
            items(o.songs.withIndex().toList(), key = { "s" + it.index }) { (i, song) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) {
                        TidalRemote.playSequence(o.songs.drop(i).map { it to o.artist })
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                    Text(song, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            item {
                PanelTitle("Setlists")
                if (key.isBlank()) SetlistKeyCard()
                else Text("Le dernier concert de chacun de tes artistes, avec les sons joués ce soir-là. Toucher pour voir et réécouter.", color = Txt2, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(Space.s))
                if (key.isNotBlank() && shows == null) SkeletonList(4)
                if (key.isNotBlank() && shows?.isEmpty() == true) Text("Pas de setlist trouvée pour tes artistes.", color = Txt3, fontSize = 13.sp)
            }
            items(shows.orEmpty(), key = { it.id }) { sh ->
                Row(
                    Modifier.fillMaxWidth().glass(Rounded(16), fill = 0.07f, edge = 0.12f).bounceClick(pressedScale = 0.97f) { Setlists.open.value = sh }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎤", fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sh.artist, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Setlists.prettyDate(sh.date)} · ${sh.city}", color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${sh.songs.size} sons", color = Txt3, fontSize = 11.sp)
                    }
                    Text("›", color = Txt2, fontSize = 20.sp)
                }
            }
        }
    }
}

/** Clé setlist.fm (gratuite pour un usage perso). */
@Composable
fun SetlistKeyCard() {
    val key by Setlists.key.collectAsState()
    var text by remember { mutableStateOf(key) }
    Column(Modifier.fillMaxWidth().glass(Rounded(20), fill = 0.08f, edge = 0.14f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Clé setlist.fm", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Les setlists viennent de setlist.fm. Crée un compte gratuit sur setlist.fm, puis va sur setlist.fm/settings/api, demande une clé (usage perso) et colle-la ici.",
            color = Txt2, fontSize = 13.sp, lineHeight = 18.sp,
        )
        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp), fill = 0.10f, edge = 0.16f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (text.isEmpty()) Text("Colle ta clé ici", color = Txt3, fontSize = 13.sp)
            androidx.compose.foundation.text.BasicTextField(
                value = text, onValueChange = { text = it.trim().take(64) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 13.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PillButton("Enregistrer") { Setlists.setKey(text); TidalRemote.showToast("Clé enregistrée") }
    }
}
