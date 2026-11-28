package com.theo.remote.ui

import com.theo.remote.playPlaylist
import com.theo.remote.playSequence

import android.content.Context
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.Recognizer
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import com.theo.remote.Transitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

// =====================================================================================
// Humeur du moment
// =====================================================================================

/** Hush pioche dans TES sons ceux qui collent à l'humeur (grâce aux genres des artistes sur Last.fm). */
object MoodMix {

    enum class Mood(val label: String, val tags: List<String>, val fallback: String) {
        Chill("Chill", listOf("chill", "lo-fi", "lofi", "r&b", "rnb", "soul", "jazz", "acoustic", "downtempo", "neo-soul", "bossa", "chillhop"), "Chill"),
        Hype("Motivé", listOf("hip-hop", "hip hop", "rap", "trap", "drill", "rock", "metal", "edm", "grime", "phonk", "dubstep", "workout"), "Motivation"),
        Sad("Triste", listOf("sad", "melanchol", "emo", "ballad", "piano", "singer-songwriter", "indie folk", "slowcore", "heartbreak"), "Sad songs"),
        Party("Soirée", listOf("dance", "party", "club", "house", "afrobeat", "reggaeton", "dancehall", "disco", "amapiano", "funk", "pop"), "Party"),
        Focus("Focus", listOf("instrumental", "ambient", "lo-fi", "classical", "piano", "post-rock", "study", "minimal", "soundtrack"), "Focus"),
        Love("Love", listOf("love", "romantic", "r&b", "rnb", "soul", "ballad", "slow jam"), "Love songs"),
    }

    private val mutex = Mutex()
    private val tagCache = HashMap<String, List<String>>()
    private var loaded = false

    private val _busy = MutableStateFlow<Mood?>(null)
    val busy: StateFlow<Mood?> = _busy

    /** Lance un mix de tes sons pour cette humeur (ou une playlist Tidal si on n'a pas assez d'infos). */
    suspend fun play(context: Context, mood: Mood) {
        _busy.value = mood
        try {
            val picks = runCatching { pick(context, mood) }.getOrDefault(emptyList())
            if (picks.size >= 6) {
                TidalRemote.playSequence(picks)
                TidalRemote.showToast("Humeur ${mood.label.lowercase()} : ${picks.size} de tes sons")
            } else {
                TidalRemote.playPlaylist(mood.fallback)
                TidalRemote.showToast(if (LastFm.apiKey.isBlank()) "Ajoute ta clé Last.fm (Stats) pour un mix de tes sons" else "Pas assez de tes sons pour cette humeur : playlist Tidal")
            }
        } finally {
            _busy.value = null
        }
    }

    private suspend fun pick(context: Context, mood: Mood): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val key = LastFm.apiKey
        if (key.isBlank()) return@withContext emptyList()
        val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
        if (ev.isEmpty()) return@withContext emptyList()
        val since = System.currentTimeMillis() - 180L * 24 * 3600_000
        val recent = ev.filter { it.ts >= since }.ifEmpty { ev }
        val artists = recent.groupingBy { mainArtist(it.artist) }.eachCount().entries.sortedByDescending { it.value }.take(40).map { it.key }
        val tags = tagsFor(context, artists, key)
        val good = artists.filter { a -> tags[a.lowercase()].orEmpty().any { t -> mood.tags.any { t.contains(it) } } }.toSet()
        val counts = recent.groupingBy { it.key }.eachCount()
        val byKey = HashMap<String, Ev>()
        recent.forEach { byKey[it.key] = it }
        counts.entries.sortedByDescending { it.value }
            .mapNotNull { byKey[it.key] }
            .filter { mainArtist(it.artist) in good && !com.theo.remote.Blocked.isBlocked(it.title, it.artist) }
            .take(80).shuffled().take(30)
            .map { it.title to it.artist }
    }

    /** Genres des artistes (Last.fm), gardés en mémoire pour ne pas tout redemander. */
    internal suspend fun tagsFor(context: Context, artists: List<String>, key: String): Map<String, List<String>> = mutex.withLock {
        val prefs = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
        if (!loaded) {
            loaded = true
            runCatching {
                val o = JSONObject(prefs.getString("artistTags", "{}") ?: "{}")
                o.keys().forEach { k -> val a = o.getJSONArray(k); tagCache[k] = (0 until a.length()).map { a.getString(it) } }
            }
        }
        var changed = false
        for (a in artists) {
            val k = a.lowercase()
            if (tagCache.containsKey(k)) continue
            val url = "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags&format=json&autocorrect=1" +
                "&artist=${URLEncoder.encode(a, "UTF-8")}&api_key=${URLEncoder.encode(key, "UTF-8")}"
            val list = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                val body = c.inputStream.bufferedReader().readText()
                val arr = JSONObject(body).optJSONObject("toptags")?.optJSONArray("tag") ?: JSONArray()
                (0 until minOf(arr.length(), 8)).map { arr.getJSONObject(it).optString("name").lowercase() }
            }.getOrNull() ?: continue
            tagCache[k] = list
            changed = true
            delay(220)
        }
        if (changed) {
            val o = JSONObject()
            tagCache.forEach { (k, v) -> o.put(k, JSONArray(v)) }
            prefs.edit().putString("artistTags", o.toString()).apply()
        }
        tagCache
    }
}

// =====================================================================================
// Mix du jour
// =====================================================================================

/** Un mix différent chaque jour : tes favoris du moment, des sons oubliés et des nouveautés de tes artistes. */
object DailyMix {
    data class Item(val title: String, val artist: String, val cover: String?, val why: String)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    private val mutex = Mutex()
    private var loaded = false

    fun coverModel(c: String?): Any? = c?.let { if (it.startsWith("/")) File(it) else it }

    suspend fun ensure(context: Context) = mutex.withLock {
        val prefs = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
        val today = Calendar.getInstance().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }
        if (!loaded) {
            loaded = true
            _items.value = decode(prefs.getString("dailyMix", null))
        }
        if (prefs.getString("dailyMixDay", null) == today && _items.value.isNotEmpty()) return@withLock
        val fresh = runCatching { build() }.getOrDefault(emptyList())
        if (fresh.size >= 8) {
            _items.value = fresh
            prefs.edit().putString("dailyMix", encode(fresh)).putString("dailyMixDay", today).apply()
        }
    }

    private suspend fun build(): List<Item> {
        val ev = withContext(Dispatchers.Default) { buildEvents(History.entries.value, LastFm.scrobbles.value) }
        if (ev.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val day = 24L * 3600_000
        val recent = ev.filter { it.ts >= now - 30 * day }
        val old = ev.filter { it.ts < now - 60 * day }
        fun cover(e: Ev): String? = when (val c = e.cover) { is File -> c.absolutePath; is String -> c; else -> null }
        val latest = HashMap<String, Ev>()
        ev.forEach { e -> val o = latest[e.key]; if (o == null || o.cover == null) latest[e.key] = e }

        // tes favoris du moment
        val recentCounts = recent.groupingBy { it.key }.eachCount()
        val favs = recentCounts.entries.sortedByDescending { it.value }.take(40).shuffled().take(12)
            .mapNotNull { latest[it.key] }.map { Item(it.title, it.artist, cover(it), "Tu l'écoutes en ce moment") }

        // ressortis du placard : beaucoup écoutés avant, plus du tout ce mois-ci
        val recentKeys = recentCounts.keys
        val forgotten = old.groupingBy { it.key }.eachCount().entries.filter { it.value >= 4 && it.key !in recentKeys }
            .sortedByDescending { it.value }.take(40).shuffled().take(8)
            .mapNotNull { latest[it.key] }.map { Item(it.title, it.artist, cover(it), "Ressorti du placard") }

        // nouveautés : les sons populaires de tes artistes que tu n'as jamais écoutés
        val known = ev.map { it.key }.toHashSet()
        val topArtists = recent.ifEmpty { ev }.groupingBy { mainArtist(it.artist) }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }
        val discover = ArrayList<Item>()
        for (a in topArtists) {
            val artist = runCatching { TidalApi.findArtist(a) }.getOrNull() ?: continue
            val page = runCatching { TidalApi.artistPage(artist) }.getOrNull() ?: continue
            page.topTracks.filter { History.keyOf(it.title, it.artistLine) !in known && History.keyOf(it.fullTitle, it.artistLine) !in known }
                .shuffled().take(2)
                .forEach { discover += Item(it.title, it.artistLine, it.coverUrl, "À découvrir chez ${artist.name}") }
            delay(250)
        }

        // on mélange en gardant de la variété (pas deux fois le même artiste d'affilée si possible)
        val all = (favs + forgotten + discover).filterNot { com.theo.remote.Blocked.isBlocked(it.title, it.artist) }.distinctBy { it.title.lowercase() + it.artist.lowercase() }.shuffled().toMutableList()
        val out = ArrayList<Item>()
        while (all.isNotEmpty()) {
            val i = all.indexOfFirst { out.isEmpty() || mainArtist(it.artist) != mainArtist(out.last().artist) }.takeIf { it >= 0 } ?: 0
            out += all.removeAt(i)
        }
        return out
    }

    private fun encode(l: List<Item>) = JSONArray().apply {
        l.forEach { put(JSONObject().put("t", it.title).put("a", it.artist).put("c", it.cover ?: "").put("w", it.why)) }
    }.toString()

    private fun decode(s: String?): List<Item> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.optString("t"), o.optString("a"), o.optString("c").ifBlank { null }, o.optString("w"))
        }
    }.getOrDefault(emptyList())
}

// =====================================================================================
// Badges
// =====================================================================================

object Badges {
    data class Badge(val id: String, val title: String, val desc: String, val icon: String, val progress: Float) {
        val unlocked get() = progress >= 1f
    }

    /** Calcule tous les badges (à faire hors du fil principal). */
    fun compute(context: Context, ev: List<Ev>): List<Badge> {
        val prefs = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        fun dayOf(t: Long): Long = cal.apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun hourOf(t: Long) = cal.apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

        // plus longue série de jours d'affilée
        val days = ev.map { dayOf(it.ts) }.toSortedSet().toList()
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (d in days) {
            run = if (prev != Long.MIN_VALUE && d - prev in 82_800_000L..90_000_000L) run + 1 else 1
            best = maxOf(best, run); prev = d
        }
        val plays = ev.size
        val night = ev.count { hourOf(it.ts) in 0..4 }
        val early = ev.count { hourOf(it.ts) in 5..7 }
        val artists = ev.map { mainArtist(it.artist).lowercase() }.toHashSet().size
        val topTrack = ev.groupingBy { it.key }.eachCount().values.maxOrNull() ?: 0
        val topArtist = ev.groupingBy { mainArtist(it.artist).lowercase() }.eachCount().values.maxOrNull() ?: 0
        val bestDayMin = ev.groupBy { dayOf(it.ts) }.values.maxOfOrNull { l -> l.sumOf { it.durMs } / 60_000 } ?: 0
        val blind = prefs.getInt("blindBest", 0)
        val party = prefs.getBoolean("usedParty", false)
        val dj = Transitions.pairs.value.isNotEmpty() || Transitions.settings.value.fadeAll || Transitions.cues.value.isNotEmpty()
        val found = Recognizer.history.value.size

        fun p(v: Number, goal: Number) = (v.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
        return listOf(
            Badge("streak3", "Régulier", "3 jours d'affilée avec de la musique", "🔥", p(best, 3)),
            Badge("streak7", "Une semaine", "7 jours d'affilée", "📅", p(best, 7)),
            Badge("streak30", "Inarrêtable", "30 jours d'affilée", "🏆", p(best, 30)),
            Badge("plays100", "Ça commence", "100 écoutes", "🎧", p(plays, 100)),
            Badge("plays1000", "Mélomane", "1 000 écoutes", "💿", p(plays, 1000)),
            Badge("plays5000", "Légende", "5 000 écoutes", "👑", p(plays, 5000)),
            Badge("night", "Oiseau de nuit", "50 écoutes entre minuit et 5 h", "🌙", p(night, 50)),
            Badge("early", "Lève-tôt", "50 écoutes entre 5 h et 8 h", "🌅", p(early, 50)),
            Badge("explorer", "Explorateur", "100 artistes différents", "🧭", p(artists, 100)),
            Badge("loyal", "Fidèle", "Un même son écouté 50 fois", "🔁", p(topTrack, 50)),
            Badge("fan", "Fan absolu", "300 écoutes d'un même artiste", "⭐", p(topArtist, 300)),
            Badge("marathon", "Marathon", "5 h de musique en une journée", "⏱", p(bestDayMin, 300)),
            Badge("party", "Ambianceur", "Lancer une soirée Hush", "🎉", if (party) 1f else 0f),
            Badge("blind", "Oreille d'or", "5 000 points au blind test", "🎯", p(blind, 5000)),
            Badge("dj", "DJ", "Régler tes transitions", "🎚", if (dj) 1f else 0f),
            Badge("shazam", "Détective", "Reconnaître 10 sons", "🔎", p(found, 10)),
        )
    }

    /** Badges débloqués depuis la dernière fois (et on les retient). */
    fun newlyUnlocked(context: Context, list: List<Badge>): List<Badge> {
        val prefs = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("badgesSeen", emptySet()).orEmpty()
        val now = list.filter { it.unlocked }.map { it.id }.toSet()
        val fresh = list.filter { it.unlocked && it.id !in seen }
        if (now != seen) prefs.edit().putStringSet("badgesSeen", now + seen).apply()
        // au tout premier calcul, on ne fête pas tout d'un coup
        return if (seen.isEmpty() && !prefs.getBoolean("badgesInit", false)) {
            prefs.edit().putBoolean("badgesInit", true).apply(); emptyList()
        } else fresh
    }
}
