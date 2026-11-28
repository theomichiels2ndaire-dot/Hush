package com.theo.remote.ui

import com.theo.remote.playSequence

import android.content.Context
import com.theo.remote.Blocked
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Playlists intelligentes : elles se remplissent toutes seules selon tes règles. */
object SmartLists {

    enum class Sort(val label: String) { Plays("Plus écoutés"), Recent("Récents"), Random("Aléatoire"), Title("Titre"), Stars("Ta note") }

    data class Rules(
        /** période prise en compte, en jours (0 = depuis toujours) */
        val periodDays: Int = 30,
        /** artiste (vide = tous) */
        val artist: String = "",
        val likedOnly: Boolean = false,
        val noFeats: Boolean = false,
        /** pas écouté depuis N jours (0 = peu importe) */
        val notSinceDays: Int = 0,
        val minPlays: Int = 1,
        /** seulement des sons découverts pendant la période */
        val newOnly: Boolean = false,
        val sort: Sort = Sort.Plays,
        val limit: Int = 50,
        /** seulement les sons notés au moins N étoiles (0 = peu importe) */
        val minStars: Int = 0,
    )

    data class Smart(val id: String, val name: String, val rules: Rules, val color: Int)

    data class Item(val title: String, val artist: String, val cover: Any?, val plays: Int)

    private val _lists = MutableStateFlow<List<Smart>>(emptyList())
    val lists: StateFlow<List<Smart>> = _lists
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString("smartLists", null)
        _lists.value = if (saved == null) defaults() else decode(saved)
        // playlists de tes notes, ajoutées une fois (tu peux les modifier ou les supprimer ensuite)
        if (!p.getBoolean("smartRatingsAdded", false)) {
            _lists.value = _lists.value + ratingDefaults()
            p.edit().putBoolean("smartRatingsAdded", true).apply()
            save()
        } else if (saved == null) save()
    }

    private fun ratingDefaults() = listOf(
        Smart(id(), "Mes 5 étoiles", Rules(periodDays = 0, minStars = 5, sort = Sort.Plays, limit = 200), 4),
        Smart(id(), "4 étoiles et plus", Rules(periodDays = 0, minStars = 4, sort = Sort.Stars, limit = 200), 5),
    )

    private fun defaults() = listOf(
        Smart(id(), "Top du mois", Rules(periodDays = 30, sort = Sort.Plays, limit = 50), 0),
        Smart(id(), "Tes classiques", Rules(periodDays = 0, minPlays = 5, sort = Sort.Plays, limit = 100), 1),
        Smart(id(), "Likés oubliés", Rules(periodDays = 0, likedOnly = true, notSinceDays = 60, sort = Sort.Random, limit = 50), 2),
        Smart(id(), "Découvertes récentes", Rules(periodDays = 30, newOnly = true, sort = Sort.Recent, limit = 50), 3),
    )

    private fun id() = UUID.randomUUID().toString().take(8)

    fun upsert(s: Smart) {
        _lists.value = if (_lists.value.any { it.id == s.id }) _lists.value.map { if (it.id == s.id) s else it } else _lists.value + s
        save()
    }

    fun delete(id: String) {
        _lists.value = _lists.value.filterNot { it.id == id }
        save()
    }

    fun newDraft(): Smart = Smart(id(), "Nouvelle playlist", Rules(), _lists.value.size % 6)

    /** Calcule le contenu d'une playlist (hors du fil principal). */
    suspend fun evaluate(r: Rules): List<Item> = withContext(Dispatchers.Default) {
        val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
        val now = System.currentTimeMillis()
        val day = 24L * 3600_000
        val since = if (r.periodDays > 0) now - r.periodDays * day else 0L
        val likes = History.likes.value

        data class Agg(var plays: Int = 0, var first: Long = Long.MAX_VALUE, var last: Long = 0, var e: Ev? = null)
        val all = HashMap<String, Agg>()
        for (e in ev) {
            val a = all.getOrPut(e.key) { Agg() }
            if (e.ts >= since) a.plays++
            if (e.ts < a.first) a.first = e.ts
            if (e.ts > a.last) a.last = e.ts
            if (a.e == null || a.e?.cover == null) a.e = e
        }
        val artistQ = r.artist.trim().lowercase()
        if (r.minStars > 0) {
            // playlist de tes notes : les sons notés, écoutés ou pas encore
            val rated = com.theo.remote.Ratings.items.value.values.filter { it.kind == com.theo.remote.Ratings.Kind.Track && it.stars >= r.minStars }
            val byKey = all.values.mapNotNull { a -> a.e?.let { it.key to a } }.toMap()
            data class RItem(val it: Item, val stars: Float, val at: Long, val last: Long)
            var rl = rated.filter { e ->
                (artistQ.isEmpty() || e.artist.lowercase().contains(artistQ)) &&
                    (!r.noFeats || !isFeat(e.title, e.artist)) && !Blocked.isBlocked(e.title, e.artist)
            }.map { e ->
                val a = byKey[History.keyOf(e.title, e.artist)]
                RItem(Item(e.title, e.artist, e.cover ?: a?.e?.cover, a?.plays ?: 0), e.stars, e.at, a?.last ?: 0L)
            }
            rl = when (r.sort) {
                Sort.Plays -> rl.sortedByDescending { it.it.plays }
                Sort.Recent -> rl.sortedByDescending { maxOf(it.last, it.at) }
                Sort.Random -> rl.shuffled()
                Sort.Title -> rl.sortedBy { it.it.title.lowercase() }
                Sort.Stars -> rl.sortedWith(compareByDescending<RItem> { it.stars }.thenByDescending { it.at })
            }
            return@withContext rl.take(r.limit).map { it.it }
        }
        var out = all.values.filter { a ->
            val e = a.e ?: return@filter false
            a.plays >= r.minPlays &&
                (artistQ.isEmpty() || e.artist.lowercase().contains(artistQ)) &&
                (!r.likedOnly || likes.contains(History.keyOf(e.title, e.artist))) &&
                (!r.noFeats || !isFeat(e.title, e.artist)) &&
                (r.notSinceDays == 0 || a.last < now - r.notSinceDays * day) &&
                (!r.newOnly || a.first >= since) &&
                !Blocked.isBlocked(e.title, e.artist)
        }
        // « pas écouté depuis » : la période ne compte pas les écoutes, on prend l'historique entier
        if (r.notSinceDays > 0 && r.minPlays <= 1) out = out.filter { it.last > 0 }
        out = when (r.sort) {
            Sort.Plays -> out.sortedByDescending { it.plays }
            Sort.Recent -> out.sortedByDescending { it.last }
            Sort.Random -> out.shuffled()
            Sort.Title -> out.sortedBy { it.e?.title?.lowercase() }
            Sort.Stars -> out.sortedByDescending { a -> a.e?.let { com.theo.remote.Ratings.track(it.title, it.artist)?.stars } ?: 0f }
        }
        out.take(r.limit).mapNotNull { a -> a.e?.let { Item(it.title, it.artist, it.cover, a.plays) } }
    }

    private fun isFeat(title: String, artist: String): Boolean {
        val t = title.lowercase()
        return t.contains("feat") || t.contains("ft.") || t.contains(" with ") || artist.contains(",") || artist.contains("&")
    }

    /** Copie la playlist dans Tidal (on retrouve chaque son dans le catalogue). */
    suspend fun copyToTidal(name: String, items: List<Item>, progress: (Int, Int) -> Unit): Int {
        val ids = ArrayList<String>()
        items.take(100).forEachIndexed { i, it ->
            progress(i + 1, items.size.coerceAtMost(100))
            val found = runCatching { TidalApi.search("${it.title} ${mainArtist(it.artist)}") }.getOrNull()
            fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            val r = found?.firstOrNull { f -> n(f.title) == n(it.title) || n(f.fullTitle) == n(it.title) } ?: found?.firstOrNull()
            r?.let { ids += it.id }
            delay(220)
        }
        val p = TidalApi.createPlaylist(name, "Playlist intelligente Hush")
        TidalApi.addToPlaylist(p.id, ids.distinct())
        PlaylistEdit.refreshLater(p.id)
        return ids.size
    }

    fun play(items: List<Item>, shuffled: Boolean) {
        val l = if (shuffled) com.theo.remote.SmartShuffle.order(items, { it.title }, { it.artist }) else items
        TidalRemote.playSequence(l.map { it.title to it.artist })
    }

    private fun save() {
        val a = JSONArray()
        _lists.value.forEach { s ->
            val r = s.rules
            a.put(
                JSONObject().put("id", s.id).put("n", s.name).put("c", s.color)
                    .put("p", r.periodDays).put("ar", r.artist).put("l", r.likedOnly).put("nf", r.noFeats)
                    .put("ns", r.notSinceDays).put("mp", r.minPlays).put("no", r.newOnly).put("s", r.sort.name).put("lim", r.limit).put("ms", r.minStars),
            )
        }
        prefs?.edit()?.putString("smartLists", a.toString())?.apply()
    }

    private fun decode(s: String): List<Smart> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Smart(
                o.getString("id"), o.optString("n", "Playlist"),
                Rules(
                    periodDays = o.optInt("p", 30), artist = o.optString("ar"), likedOnly = o.optBoolean("l"),
                    noFeats = o.optBoolean("nf"), notSinceDays = o.optInt("ns"), minPlays = o.optInt("mp", 1),
                    newOnly = o.optBoolean("no"), sort = runCatching { Sort.valueOf(o.optString("s")) }.getOrDefault(Sort.Plays),
                    limit = o.optInt("lim", 50), minStars = o.optInt("ms", 0),
                ),
                o.optInt("c"),
            )
        }
    }.getOrDefault(defaults())
}
