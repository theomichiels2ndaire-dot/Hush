package com.theo.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Karaoké : les paroles du son en cours, aussi sur le téléphone de tes potes (page du QR code). */
object Karaoke {
    /** Tes potes voient les paroles sur la page de la soirée. */
    @Volatile var party = false

    /** Ce que la page de la soirée reçoit : paroles synchronisées + position. */
    fun json(): JSONObject {
        val o = JSONObject().put("on", party)
        if (!party) return o
        val st = TidalRemote.state.value
        val t = st.track ?: return o
        val ly = runCatching { runBlocking { Lyrics.fetch(t.title, t.artist, t.album, t.durationMs) } }.getOrNull()
        val lines = JSONArray()
        ly?.synced?.forEach { lines.put(JSONArray().put(it.timeMs).put(it.text)) }
        return o.put("key", t.title + "|" + t.artist)
            .put("title", t.title).put("artist", t.artist)
            .put("lines", lines)
            .put("plain", if (ly?.synced.isNullOrEmpty()) ly?.plain.orEmpty() else "")
            .put("pos", st.livePosition())
            .put("playing", st.advancing)
    }
}
