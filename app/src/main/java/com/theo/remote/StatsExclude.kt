package com.theo.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ce qui ne compte pas dans tes stats :
 * - des sons précis (« Ne pas compter dans mes stats »),
 * - les moments en mode invité (quand quelqu'un d'autre met la musique).
 */
object StatsExclude {
    data class Item(val key: String, val title: String, val artist: String)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    /** périodes invité [début, fin] ; fin = 0 tant que le mode invité est en cours */
    private val _guest = MutableStateFlow<List<LongArray>>(emptyList())
    /** début du mode invité en cours, sinon null */
    private val _guestSince = MutableStateFlow<Long?>(null)
    val guestSince: StateFlow<Long?> = _guestSince
    /** change à chaque modif : sert à refaire le calcul des stats */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _items.value = runCatching {
            val a = JSONArray(p.getString("statsExcluded", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("k"), o.optString("t"), o.optString("a")) }
        }.getOrDefault(emptyList())
        _guest.value = runCatching {
            val a = JSONArray(p.getString("guestPeriods", "[]"))
            (0 until a.length()).map { val o = a.getJSONArray(it); longArrayOf(o.getLong(0), o.getLong(1)) }
        }.getOrDefault(emptyList())
        _guestSince.value = _guest.value.lastOrNull()?.takeIf { it[1] == 0L }?.get(0)
        // le mode invité a été retiré : une session restée ouverte est fermée (tes stats reprennent)
        if (_guestSince.value != null) setGuest(false)
    }

    fun isExcluded(title: String, artist: String) = _items.value.any { it.key == History.keyOf(title, artist) }

    fun exclude(title: String, artist: String) {
        val k = History.keyOf(title, artist)
        if (_items.value.any { it.key == k }) return
        _items.value = _items.value + Item(k, title, artist)
        save()
    }

    fun include(key: String) {
        _items.value = _items.value.filterNot { it.key == key }
        save()
    }

    fun setGuest(on: Boolean) {
        val now = System.currentTimeMillis()
        if (on && _guestSince.value == null) {
            _guest.value = (_guest.value + longArrayOf(now, 0L)).takeLast(200)
            _guestSince.value = now
        } else if (!on && _guestSince.value != null) {
            _guest.value = _guest.value.map { if (it[1] == 0L) longArrayOf(it[0], now) else it }
            _guestSince.value = null
        }
        save()
    }

    /** true si cette écoute ne doit pas compter */
    fun skip(key: String, ts: Long): Boolean {
        if (_items.value.isNotEmpty() && _items.value.any { it.key == key }) return true
        for (g in _guest.value) if (ts >= g[0] && (g[1] == 0L || ts <= g[1])) return true
        return false
    }

    val isEmpty get() = _items.value.isEmpty() && _guest.value.isEmpty()

    private fun save() {
        _version.value++
        val a = JSONArray()
        _items.value.forEach { a.put(JSONObject().put("k", it.key).put("t", it.title).put("a", it.artist)) }
        val g = JSONArray()
        _guest.value.forEach { g.put(JSONArray().put(it[0]).put(it[1])) }
        prefs?.edit()?.putString("statsExcluded", a.toString())?.putString("guestPeriods", g.toString())?.apply()
    }
}
