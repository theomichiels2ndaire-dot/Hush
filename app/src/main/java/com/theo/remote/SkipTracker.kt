package com.theo.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Repère les sons que tu passes au bout de quelques secondes. */
object SkipTracker {
    data class Skip(val key: String, val title: String, val artist: String, val count: Int, val at: Long)

    private val _skips = MutableStateFlow<Map<String, Skip>>(emptyMap())
    val skips: StateFlow<Map<String, Skip>> = _skips
    private val _ignored = MutableStateFlow<Set<String>>(emptySet())
    val ignored: StateFlow<Set<String>> = _ignored
    private var prefs: android.content.SharedPreferences? = null

    private var lastKey: String? = null
    private var lastTitle = ""
    private var lastArtist = ""
    private var lastPos = 0L
    private var lastDur = 0L

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _skips.value = runCatching {
            val o = JSONObject(p.getString("skips", "{}"))
            o.keys().asSequence().associateWith { k ->
                val x = o.getJSONObject(k)
                Skip(k, x.optString("t"), x.optString("a"), x.optInt("n"), x.optLong("at"))
            }
        }.getOrDefault(emptyMap())
        _ignored.value = p.getStringSet("skipsIgnored", emptySet()).orEmpty().toSet()
    }

    /** Appelé plusieurs fois par seconde par le lecteur. */
    fun track(s: TidalRemote.State) {
        val t = s.track
        val key = t?.let { History.keyOf(it.title, it.artist) }
        if (key != lastKey) {
            val prev = lastKey
            // le son d'avant a été coupé entre 1 et 30 s (et ce n'était pas un son très court)
            if (prev != null && key != null && lastPos in 1_000..30_000 && lastDur > 60_000 && Features.flags.value.skipDetect) {
                record(prev, lastTitle, lastArtist)
            }
            lastKey = key
            lastPos = 0
        }
        if (t != null) {
            lastTitle = t.title; lastArtist = t.artist; lastDur = t.durationMs
            if (s.isPlaying) lastPos = s.livePosition()
        }
    }

    private fun record(key: String, title: String, artist: String) {
        val old = _skips.value[key]
        _skips.value = _skips.value + (key to Skip(key, title, artist, (old?.count ?: 0) + 1, System.currentTimeMillis()))
        save()
    }

    /** Sons à proposer de masquer : passés souvent, et plus souvent passés qu'écoutés. */
    fun suggestions(): List<Skip> {
        val plays = History.entries.value.associate { it.key to it.plays.size }
        return _skips.value.values
            .filter { it.key !in _ignored.value && it.count >= 3 && it.count * 2 >= (plays[it.key] ?: 0) && !Blocked.isBlocked(it.title, it.artist) }
            .sortedByDescending { it.count }
    }

    fun ignore(key: String) {
        _ignored.value = _ignored.value + key
        prefs?.edit()?.putStringSet("skipsIgnored", _ignored.value)?.apply()
    }

    fun forget(key: String) {
        _skips.value = _skips.value - key
        save()
    }

    private fun save() {
        val o = JSONObject()
        _skips.value.values.sortedByDescending { it.at }.take(300).forEach { o.put(it.key, JSONObject().put("t", it.title).put("a", it.artist).put("n", it.count).put("at", it.at)) }
        prefs?.edit()?.putString("skips", o.toString())?.apply()
    }
}
