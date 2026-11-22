package com.theo.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Carnet façon Letterboxd : une note sur 5 (demi-étoiles) et une phrase d'avis,
 * pour un album ou pour un son.
 */
object Ratings {
    enum class Kind { Album, Track }

    data class Entry(
        val kind: Kind, val title: String, val artist: String, val cover: String?,
        val stars: Float, val review: String, val at: Long,
    ) {
        val key get() = keyOf(kind, title, artist)
    }

    /** ce qu'on est en train de noter */
    data class Target(val kind: Kind, val title: String, val artist: String, val cover: String?)

    private val _items = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val items: StateFlow<Map<String, Entry>> = _items
    val editing = MutableStateFlow<Target?>(null)
    private var prefs: android.content.SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun keyOf(kind: Kind, title: String, artist: String) = (if (kind == Kind.Album) "a:" else "t:") + History.keyOf(title, artist)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush_ratings", Context.MODE_PRIVATE)
        prefs = p
        _items.value = runCatching {
            val a = JSONArray(p.getString("list", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(
                    if (o.optString("k") == "a") Kind.Album else Kind.Track,
                    o.getString("t"), o.getString("a"), o.optString("c").ifBlank { null },
                    o.getDouble("s").toFloat(), o.optString("r"), o.getLong("at"),
                )
            }.associateBy { it.key }
        }.getOrDefault(emptyMap())
    }

    fun get(kind: Kind, title: String, artist: String) = _items.value[keyOf(kind, title, artist)]

    // même son écrit un peu différemment (« A, B » / « A & B », « (feat. …) ») : on retrouve quand même la note
    private fun loose(t: String) = t.lowercase().substringBefore(" (").substringBefore(" [").substringBefore(" - ").replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun looseKey(title: String, artist: String) = loose(title) + "|" + loose(artist.split(",", "&", " feat", " ft.").first())
    private var looseSrc: Map<String, Entry>? = null
    private var looseMap: Map<String, Entry> = emptyMap()

    /** Note d'un son (tolérante sur l'écriture du titre et des artistes). */
    fun track(title: String, artist: String): Entry? {
        val all = _items.value
        all[keyOf(Kind.Track, title, artist)]?.let { return it }
        if (looseSrc !== all) {
            looseMap = all.values.filter { it.kind == Kind.Track }.associateBy { looseKey(it.title, it.artist) }
            looseSrc = all
        }
        return looseMap[looseKey(title, artist)]
    }

    fun edit(kind: Kind, title: String, artist: String, cover: String? = null) {
        if (title.isBlank()) return
        editing.value = Target(kind, title, artist, cover ?: get(kind, title, artist)?.cover)
    }

    fun set(t: Target, stars: Float, review: String) {
        val old = get(t.kind, t.title, t.artist)
        val e = Entry(t.kind, t.title, t.artist, t.cover ?: old?.cover, stars.coerceIn(0.5f, 5f), review.trim(), old?.at ?: System.currentTimeMillis())
        _items.value = _items.value + (e.key to e)
        save()
        // pas de pochette (son lancé depuis Tidal) : on la retrouve sur Tidal
        if (e.cover == null) scope.launch {
            val url = runCatching {
                if (t.kind == Kind.Album) TidalApi.findAlbum(t.title, t.artist)?.coverUrl
                else TidalApi.search("${t.title} ${t.artist}").firstOrNull()?.coverUrl
            }.getOrNull() ?: return@launch
            _items.value[e.key]?.let { cur -> _items.value = _items.value + (e.key to cur.copy(cover = url)); save() }
        }
    }

    fun remove(t: Target) { _items.value = _items.value - keyOf(t.kind, t.title, t.artist); save() }

    private fun save() {
        val a = JSONArray()
        _items.value.values.forEach {
            a.put(
                JSONObject().put("k", if (it.kind == Kind.Album) "a" else "t").put("t", it.title).put("a", it.artist)
                    .put("c", it.cover ?: "").put("s", it.stars.toDouble()).put("r", it.review).put("at", it.at)
            )
        }
        prefs?.edit()?.putString("list", a.toString())?.apply()
    }
}
