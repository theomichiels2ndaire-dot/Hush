package com.theo.remote

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Historique local des titres joués dans Tidal + coups de cœur.
 * Tout reste sur le téléphone (pas de compte Tidal nécessaire).
 */
object History {

    data class Entry(
        val title: String,
        val artist: String,
        val album: String,
        val artPath: String?,
        /** Pochette officielle trouvée dans le catalogue Tidal (fiable, prioritaire). */
        val coverUrl: String? = null,
        /** Moments où ce son a été lancé (les 60 derniers). */
        val plays: List<Long> = emptyList(),
        val durationMs: Long = 0,
    ) {
        val lastPlayed get() = plays.maxOrNull() ?: 0L
        val key get() = keyOf(title, artist)
    }

    private const val MAX = 150

    /** Image enregistrée par son (pour remplacer une mauvaise pochette par la bonne). */
    private val savedArt = HashMap<String, Int>()

    /** Change à chaque nouvelle image enregistrée, pour forcer le rechargement à l'écran. */
    private val _coverVersion = MutableStateFlow(0)
    val coverVersion: StateFlow<Int> = _coverVersion.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var coverDir: File? = null

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _likes = MutableStateFlow<Set<String>>(emptySet())
    val likes: StateFlow<Set<String>> = _likes.asStateFlow()

    fun keyOf(title: String, artist: String) =
        "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}"

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        coverDir = File(context.applicationContext.filesDir, "covers").apply { mkdirs() }
        _entries.value = load(p.getString("history", null))
        _likes.value = p.getStringSet("likes", emptySet())?.toSet() ?: emptySet()
        loadSearchHistory(p)
    }

    /** Pendant un blind test, les extraits ne comptent pas. */
    @Volatile var paused = false

    /** Appelé à chaque mise à jour de Tidal ; ne réécrit que si quelque chose a changé. */
    fun record(track: TidalRemote.Track) {
        if (paused) return
        val p = prefs ?: return
        if (track.title.isBlank() || track.title == "—") return
        val key = keyOf(track.title, track.artist)
        val current = _entries.value
        val top = current.firstOrNull()
        val gen = track.art?.let { System.identityHashCode(it) }
        // même son, même image déjà enregistrée : rien à faire
        if (top != null && top.key == key && (gen == null || savedArt[key] == gen)) return

        val artPath = track.art?.let { bmp -> saveCover(key, bmp).also { if (it != null) savedArt[key] = gen!! } }
            ?: current.firstOrNull { it.key == key }?.artPath
        val old = current.firstOrNull { it.key == key }
        val known = old?.coverUrl
        // nouvelle écoute si ce son n'était pas déjà le dernier joué
        val now = System.currentTimeMillis()
        val plays = if (top?.key != key) ((old?.plays ?: emptyList()) + now).takeLast(60) else (old?.plays ?: listOf(now))
        val entry = Entry(track.title, track.artist, track.album, artPath, known, plays, track.durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0)
        val next = (listOf(entry) + current.filter { it.key != key }).take(MAX)
        _entries.value = next
        p.edit().putString("history", save(next)).apply()
    }

    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    private val misses = HashSet<String>()

    /**
     * Va chercher dans le catalogue Tidal la vraie pochette des sons de l'historique qui n'en ont pas.
     * Un par un, avec une pause, pour ne pas se faire bloquer par Tidal.
     */
    suspend fun resolveCovers() {
        if (!TidalApi.isConfigured || !resolving.compareAndSet(false, true)) return
        try {
            while (true) {
                val e = _entries.value.firstOrNull { it.coverUrl == null && it.key !in misses } ?: break
                val url = try {
                    val results = TidalApi.search("${e.title} ${e.artist}")
                    fun n(x: String) = x.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
                    val artistN = n(e.artist.split(",", "&").first())
                    (results.firstOrNull { n(it.fullTitle) == n(e.title) && it.artists.any { a -> n(a).contains(artistN) || artistN.contains(n(a)) } }
                        ?: results.firstOrNull { n(it.title) == n(e.title) || n(it.fullTitle) == n(e.title) }
                        ?: results.firstOrNull { it.artists.any { a -> n(a) == artistN } })?.coverUrl
                } catch (ex: Exception) {
                    null
                }
                if (url == null) {
                    misses += e.key
                } else {
                    val next = _entries.value.map { if (it.key == e.key) it.copy(coverUrl = url) else it }
                    _entries.value = next
                    prefs?.edit()?.putString("history", save(next))?.apply()
                }
                kotlinx.coroutines.delay(400)
            }
        } finally {
            resolving.set(false)
        }
    }

    // ---------------------------------------------------------------- sections automatiques

    private const val DAY = 24L * 60 * 60 * 1000

    /** Les sons les plus écoutés ces 7 derniers jours. */
    fun weekly(list: List<Entry>, now: Long = System.currentTimeMillis()): List<Entry> =
        list.map { it to it.plays.count { t -> t > now - 7 * DAY } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.lastPlayed })
            .map { it.first }
            .take(12)

    /** Des sons que tu aimais (plusieurs écoutes) mais pas écoutés depuis au moins 2 semaines. */
    fun forgotten(list: List<Entry>, now: Long = System.currentTimeMillis()): List<Entry> =
        list.filter { it.lastPlayed in 1 until now - 14 * DAY }
            .sortedWith(compareByDescending<Entry> { it.plays.size }.thenBy { it.lastPlayed })
            .take(12)

    /** Ce que tu écoutes d'habitude à cette heure-ci (± 2 h). */
    fun thisTimeOfDay(list: List<Entry>, now: Long = System.currentTimeMillis()): List<Entry> {
        val cal = java.util.Calendar.getInstance()
        val hourNow = cal.apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        fun near(t: Long): Boolean {
            val h = cal.apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY)
            val d = kotlin.math.abs(h - hourNow)
            return minOf(d, 24 - d) <= 2
        }
        return list.map { it to it.plays.count { t -> t < now - 2 * 60 * 60 * 1000 && near(t) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(12)
    }

    // ---------------------------------------------------------------- historique de recherche

    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val searches: StateFlow<List<String>> = _searches.asStateFlow()

    private val _searchTracks = MutableStateFlow<List<TidalApi.Result>>(emptyList())
    val searchTracks: StateFlow<List<TidalApi.Result>> = _searchTracks.asStateFlow()

    fun addSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val next = (listOf(q) + _searches.value.filter { !it.equals(q, ignoreCase = true) }).take(12)
        _searches.value = next
        prefs?.edit()?.putString("searches", JSONArray(next).toString())?.apply()
    }

    fun removeSearch(query: String) {
        val next = _searches.value - query
        _searches.value = next
        prefs?.edit()?.putString("searches", JSONArray(next).toString())?.apply()
    }

    fun addSearchTrack(r: TidalApi.Result) {
        val next = (listOf(r) + _searchTracks.value.filter { it.id != r.id }).take(20)
        _searchTracks.value = next
        saveSearchTracks(next)
    }

    fun removeSearchTrack(id: String) {
        val next = _searchTracks.value.filter { it.id != id }
        _searchTracks.value = next
        saveSearchTracks(next)
    }

    fun clearSearchHistory() {
        _searches.value = emptyList()
        _searchTracks.value = emptyList()
        prefs?.edit()?.remove("searches")?.remove("searchTracks")?.apply()
    }

    private fun saveSearchTracks(list: List<TidalApi.Result>) {
        prefs?.edit()?.putString("searchTracks", encodeResults(list))?.apply()
    }

    /** Sérialise des sons (pour l'historique de recherche et la file « À suivre »). */
    fun encodeResults(list: List<TidalApi.Result>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().put("id", r.id).put("t", r.title).put("v", r.version ?: "")
                    .put("a", JSONArray(r.artists)).put("al", r.album ?: "").put("c", r.coverUrl ?: "")
                    .put("d", r.durationMs).put("e", r.explicit)
                    .apply { r.addedAt?.let { put("ad", it) }; r.itemId?.let { put("it", it) } }
            )
        }
        return arr.toString()
    }

    fun decodeResults(json: String?): List<TidalApi.Result> = try {
        val t = JSONArray(json ?: "[]")
        (0 until t.length()).map {
            val o = t.getJSONObject(it)
            val a = o.optJSONArray("a") ?: JSONArray()
            TidalApi.Result(
                id = o.optString("id"),
                title = o.optString("t"),
                version = o.optString("v").ifBlank { null },
                artists = (0 until a.length()).map { i -> a.getString(i) },
                album = o.optString("al").ifBlank { null },
                coverUrl = o.optString("c").ifBlank { null },
                durationMs = o.optLong("d"),
                explicit = o.optBoolean("e"),
                itemId = o.optString("it").ifBlank { null },
                addedAt = if (o.has("ad")) o.optLong("ad") else null,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadSearchHistory(p: SharedPreferences) {
        try {
            val q = JSONArray(p.getString("searches", "[]"))
            _searches.value = (0 until q.length()).map { q.getString(it) }
            val t = JSONArray(p.getString("searchTracks", "[]"))
            _searchTracks.value = (0 until t.length()).map {
                val o = t.getJSONObject(it)
                val a = o.optJSONArray("a") ?: JSONArray()
                TidalApi.Result(
                    id = o.optString("id"),
                    title = o.optString("t"),
                    version = o.optString("v").ifBlank { null },
                    artists = (0 until a.length()).map { i -> a.getString(i) },
                    album = o.optString("al").ifBlank { null },
                    coverUrl = o.optString("c").ifBlank { null },
                    durationMs = o.optLong("d"),
                    explicit = o.optBoolean("e"),
                )
            }
        } catch (e: Exception) {
            // historique illisible : on repart de zéro
        }
    }

    fun isLiked(title: String, artist: String) = _likes.value.contains(keyOf(title, artist))

    fun setLike(title: String, artist: String, liked: Boolean) {
        if (isLiked(title, artist) != liked) toggleLike(title, artist)
    }

    fun toggleLike(title: String, artist: String) {
        val p = prefs ?: return
        val key = keyOf(title, artist)
        val next = if (_likes.value.contains(key)) _likes.value - key else _likes.value + key
        _likes.value = next
        p.edit().putStringSet("likes", next).apply()
    }

    private fun saveCover(key: String, bmp: Bitmap): String? = try {
        val dir = coverDir ?: return null
        val file = File(dir, "${key.hashCode().toUInt()}.jpg")
        val size = 300
        val scaled = if (bmp.width > size) Bitmap.createScaledBitmap(bmp, size, size * bmp.height / bmp.width, true) else bmp
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        _coverVersion.value++
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    private fun save(list: List<Entry>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("t", it.title).put("a", it.artist).put("al", it.album)
                    .put("p", it.artPath ?: "").put("u", it.coverUrl ?: "")
                    .put("pl", JSONArray(it.plays)).put("d", it.durationMs)
            )
        }
    }.toString()

    private fun load(json: String?): List<Entry> = try {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(
                o.optString("t"), o.optString("a"), o.optString("al"),
                o.optString("p").ifBlank { null }, o.optString("u").ifBlank { null },
                o.optJSONArray("pl")?.let { a -> (0 until a.length()).map { i -> a.getLong(i) } } ?: emptyList(),
                o.optLong("d"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
