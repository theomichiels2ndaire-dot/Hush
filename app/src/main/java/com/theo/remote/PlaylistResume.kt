package com.theo.remote

import android.content.Context
import android.os.SystemClock
import com.theo.remote.ui.NowPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Reprise exacte : pour chaque playlist, le son et la seconde où tu l'as laissée.
 * Dans la playlist, un bouton « Reprendre » relance pile à cet endroit.
 */
object PlaylistResume {
    data class Spot(val title: String, val artist: String, val posMs: Long, val at: Long)

    private val _spots = MutableStateFlow<Map<String, Spot>>(emptyMap())
    val spots: StateFlow<Map<String, Spot>> = _spots
    private var prefs: android.content.SharedPreferences? = null
    private var lastSave = 0L

    /** Son à recaler à la bonne seconde dès qu'il démarre. */
    private var pending: Spot? = null
    private var pendingSince = 0L

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _spots.value = runCatching {
            val o = JSONObject(p.getString("plResume", "{}") ?: "{}")
            o.keys().asSequence().associateWith { k ->
                val x = o.getJSONObject(k)
                Spot(x.optString("t"), x.optString("a"), x.optLong("p"), x.optLong("at"))
            }
        }.getOrDefault(emptyMap())
    }

    /** Appelé plusieurs fois par seconde par le lecteur. */
    fun track(s: TidalRemote.State) {
        val t = s.track ?: return
        val now = SystemClock.elapsedRealtime()

        // reprise demandée : on saute à la bonne seconde quand le bon son a démarré
        pending?.let { sp ->
            if (now - pendingSince > 15_000) { pending = null; return@let }
            if (s.isPlaying && t.title.equals(sp.title, true)) {
                val pos = s.livePosition()
                if (pos < sp.posMs - 3000) TidalRemote.seekTo(sp.posMs)
                pending = null
            }
        }

        if (!Features.flags.value.resumePlaylist) return
        val id = NowPlaylist.id.value ?: return
        if (!s.isPlaying || now - lastSave < 3000) return
        val pos = s.livePosition()
        if (pos < 10_000) return
        lastSave = now
        val spot = Spot(t.title, t.artist, pos, System.currentTimeMillis())
        _spots.value = _spots.value + (id to spot)
        save()
    }

    /** Relance la playlist au son et à la seconde gardés. */
    fun resume(context: Context, id: String, list: List<TidalApi.Result>) {
        val sp = _spots.value[id] ?: return
        val i = indexIn(sp, list)
        if (i < 0) return
        NowPlaylist.set(id)
        TidalRemote.setShuffle(false)
        pending = sp
        pendingSince = SystemClock.elapsedRealtime()
        // Hush enchaîne la suite de la playlist (pas de file d'attente)
        MixQueue.play(context, list.map { LocalMusic.Entry.T(it) }, i)
    }

    fun indexIn(sp: Spot, list: List<TidalApi.Result>): Int =
        list.indexOfFirst { it.title.equals(sp.title, true) || it.fullTitle.equals(sp.title, true) }

    fun forget(id: String) { _spots.value = _spots.value - id; save() }

    private fun save() {
        val o = JSONObject()
        // on garde les 40 playlists les plus récentes
        _spots.value.entries.sortedByDescending { it.value.at }.take(40).forEach { (k, v) ->
            o.put(k, JSONObject().put("t", v.title).put("a", v.artist).put("p", v.posMs).put("at", v.at))
        }
        prefs?.edit()?.putString("plResume", o.toString())?.apply()
    }
}
