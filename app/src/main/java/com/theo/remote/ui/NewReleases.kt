package com.theo.remote.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.TidalApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Sorties récentes des artistes que tu écoutes le plus (mises en cache 12 h). */
object NewReleases {
    private val _list = MutableStateFlow<List<TidalApi.Album>>(emptyList())
    val list: StateFlow<List<TidalApi.Album>> = _list
    private val mutex = Mutex()
    private var loadedFromDisk = false

    suspend fun refresh(context: Context) = mutex.withLock {
        val prefs = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        if (!loadedFromDisk) {
            loadedFromDisk = true
            _list.value = decode(prefs.getString("releases", null))
        }
        val age = System.currentTimeMillis() - prefs.getLong("releasesAt", 0)
        if (age < 12 * 3600_000L && _list.value.isNotEmpty()) return@withLock

        val artists = withContext(Dispatchers.Default) { topArtists() }
        if (artists.isEmpty()) return@withLock
        val found = ArrayList<TidalApi.Album>()
        for (a in artists) {
            runCatching { TidalApi.recentReleases(a) }.getOrNull()?.let { found += it }
            delay(250)
        }
        val sorted = found.distinctBy { it.id }.sortedByDescending { it.releaseDate ?: "" }.take(20)
        _list.value = sorted
        prefs.edit().putString("releases", encode(sorted)).putLong("releasesAt", System.currentTimeMillis()).apply()
    }

    internal fun topArtists(): List<String> {
        val since = System.currentTimeMillis() - 60L * 24 * 3600_000
        val counts = HashMap<String, Int>()
        History.entries.value.forEach { e ->
            val n = e.plays.count { it >= since }
            if (n > 0) counts.merge(mainArtist(e.artist), n, Int::plus)
        }
        LastFm.scrobbles.value.asReversed().takeWhile { it.ts >= since }.forEach { counts.merge(mainArtist(it.artist), 1, Int::plus) }
        return counts.filterKeys { it.isNotBlank() }.entries.sortedByDescending { it.value }.take(8).map { it.key }
    }

    internal fun encode(list: List<TidalApi.Album>) = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("id", it.id).put("t", it.title).put("a", it.artist).put("c", it.coverUrl)
                    .put("y", it.year).put("n", it.count).put("k", it.type).put("d", it.releaseDate),
            )
        }
    }.toString()

    internal fun decode(s: String?): List<TidalApi.Album> = runCatching {
        val arr = JSONArray(s ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
            TidalApi.Album(o.getString("id"), o.optString("t"), str("a"), str("c"), str("y"), o.optInt("n"), str("k"), str("d"))
        }
    }.getOrDefault(emptyList())
}

/** « il y a 3 jours », « aujourd'hui »… */
fun releasedAgo(date: String?): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return ""
    val days = ChronoUnit.DAYS.between(d, LocalDate.now())
    return when {
        days <= 0 -> "Aujourd'hui"
        days == 1L -> "Hier"
        days < 7 -> "Il y a $days jours"
        days < 30 -> "Il y a ${days / 7} sem."
        else -> "Il y a ${days / 30} mois"
    }
}

@Composable
fun ReleaseTile(a: TidalApi.Album, onClick: () -> Unit) {
    val fresh = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(a.releaseDate), LocalDate.now()) <= 14 }.getOrDefault(false)
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.96f, onClick = onClick)) {
        Box {
            Cover(model = a.coverUrl, radius = 12.dp, modifier = Modifier.size(150.dp).shadow(14.dp, RoundedCornerShape(12.dp)))
            if (fresh) {
                Text(
                    "Nouveau",
                    color = Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(PillShape).background(Txt).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        Text(a.title, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(a.artist, releasedAgo(a.releaseDate).ifBlank { null }).joinToString(" · "),
            color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Sorties à venir : les albums déjà annoncés sur Tidal par tes artistes les plus écoutés et ceux que tu suis
 * (mis à jour toutes les 12 h). Le jour de la sortie, une notification prévient.
 */
object Upcoming {
    private val _list = MutableStateFlow<List<TidalApi.Album>>(emptyList())
    val list: StateFlow<List<TidalApi.Album>> = _list
    private val mutex = Mutex()
    private var loadedFromDisk = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        _list.value = NewReleases.decode(prefs(context).getString("upcoming", null)).filter { !isOut(it) }
    }

    private fun isOut(a: TidalApi.Album) = (a.releaseDate ?: "") < LocalDate.now().toString()

    suspend fun refresh(context: Context, force: Boolean = false) = mutex.withLock {
        load(context)
        val prefs = prefs(context)
        val age = System.currentTimeMillis() - prefs.getLong("upcomingAt", 0)
        if (!force && age < 12 * 3600_000L) return@withLock
        com.theo.remote.Follow.init(context)
        val artists = withContext(Dispatchers.Default) {
            (NewReleases.topArtists() + com.theo.remote.Follow.artists.value).distinctBy { it.lowercase() }.take(16)
        }
        if (artists.isEmpty()) return@withLock
        val found = ArrayList<TidalApi.Album>()
        for (a in artists) {
            runCatching { TidalApi.upcomingReleases(a) }.getOrNull()?.let { found += it }
            delay(250)
        }
        val sorted = found.distinctBy { it.id }.sortedBy { it.releaseDate ?: "" }
        _list.value = sorted
        prefs.edit().putString("upcoming", NewReleases.encode(sorted)).putLong("upcomingAt", System.currentTimeMillis()).apply()
    }

    /** Les sorties d'aujourd'hui qu'on n'a pas encore annoncées (pour la notification du jour J). */
    fun dueToday(context: Context): List<TidalApi.Album> {
        load(context)
        val today = LocalDate.now().toString()
        val p = prefs(context)
        val done = p.getStringSet("upcomingNotified", emptySet()).orEmpty()
        val due = _list.value.filter { it.releaseDate == today && it.id !in done }
        if (due.isNotEmpty()) p.edit().putStringSet("upcomingNotified", done + due.map { it.id }).apply()
        return due
    }
}

/** « Demain », « Dans 5 jours », « Dans 3 sem. »… */
fun releaseCountdown(date: String?): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return ""
    val days = ChronoUnit.DAYS.between(LocalDate.now(), d)
    return when {
        days <= 0 -> "Aujourd'hui"
        days == 1L -> "Demain"
        days < 14 -> "Dans $days jours"
        days < 60 -> "Dans ${days / 7} sem."
        else -> "Dans ${days / 30} mois"
    }
}

/** Une sortie à venir : pochette avec le compte à rebours dessus. */
@Composable
fun UpcomingTile(a: TidalApi.Album, onClick: () -> Unit) {
    val soon = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(a.releaseDate)) <= 7 }.getOrDefault(false)
    val glow = rememberUrlCoverColors(a.coverUrl).a
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.96f, onClick = onClick)) {
        Box {
            Cover(model = a.coverUrl, radius = 14.dp, modifier = Modifier.size(150.dp).shadow(16.dp, Rounded(14), ambientColor = glow, spotColor = glow))
            Text(
                releaseCountdown(a.releaseDate),
                color = if (soon) Ink else Txt, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(PillShape)
                    .background(if (soon) Txt else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(Space.s))
        Text(a.title, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(a.artist, runCatching { LocalDate.parse(a.releaseDate).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.FRANCE)) }.getOrNull()).joinToString(" · "),
            color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
