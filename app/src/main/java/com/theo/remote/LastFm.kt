package com.theo.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Import de l'historique Last.fm (les « scrobbles » que Tidal y envoie),
 * pour que les stats ne partent pas de zéro.
 */
object LastFm {

    data class Scrobble(val ts: Long, val title: String, val artist: String, val album: String, val image: String?)

    sealed class Status {
        data object Idle : Status()
        data class Running(val page: Int, val pages: Int, val count: Int) : Status()
        data class Done(val count: Int) : Status()
        data class Failed(val message: String) : Status()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private var prefs: android.content.SharedPreferences? = null
    private var job: Job? = null

    private val _scrobbles = MutableStateFlow<List<Scrobble>>(emptyList())
    val scrobbles: StateFlow<List<Scrobble>> = _scrobbles.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    val user get() = prefs?.getString("lastfm_user", "").orEmpty()
    val apiKey get() = prefs?.getString("lastfm_key", "").orEmpty()

    fun init(context: Context) {
        if (file != null) return
        val ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("hush", Context.MODE_PRIVATE)
        file = File(ctx.filesDir, "lastfm.tsv")
        scope.launch { _scrobbles.value = load() }
    }

    /** Lance l'import (ou la mise à jour : seulement les nouveaux scrobbles). */
    fun import(user: String, key: String) {
        if (job?.isActive == true) return
        prefs?.edit()?.putString("lastfm_user", user.trim())?.putString("lastfm_key", key.trim())?.apply()
        job = scope.launch {
            try {
                val existing = _scrobbles.value.ifEmpty { load() }
                val from = existing.maxOfOrNull { it.ts }?.let { it / 1000 + 1 }
                val fresh = ArrayList<Scrobble>()
                var page = 1
                var pages = 1
                while (page <= pages) {
                    _status.value = Status.Running(page, pages, fresh.size)
                    val url = "https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&format=json&limit=200" +
                        "&user=${enc(user.trim())}&api_key=${enc(key.trim())}&page=$page" +
                        (from?.let { "&from=$it" } ?: "")
                    val root = getJson(url)
                    if (root.has("error")) throw IllegalStateException(errorText(root.optInt("error"), root.optString("message")))
                    val rt = root.optJSONObject("recenttracks") ?: break
                    pages = rt.optJSONObject("@attr")?.optString("totalPages")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val arr = rt.optJSONArray("track") ?: JSONArray()
                    for (i in 0 until arr.length()) parse(arr.getJSONObject(i))?.let { fresh += it }
                    page++
                    delay(220) // Last.fm demande de ne pas dépasser ~5 requêtes/s
                }
                val all = (existing + fresh).distinctBy { it.ts.toString() + it.title }.sortedBy { it.ts }
                save(all)
                _scrobbles.value = all
                _status.value = Status.Done(fresh.size)
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: "Erreur réseau")
            }
        }
    }

    fun forget() {
        job?.cancel()
        scope.launch {
            file?.delete()
            _scrobbles.value = emptyList()
            _status.value = Status.Idle
        }
    }

    private fun errorText(code: Int, msg: String) = when (code) {
        6 -> "Pseudo Last.fm introuvable"
        10, 26 -> "Clé API Last.fm invalide"
        17 -> "Ton profil Last.fm est privé : rends ton historique visible"
        29 -> "Last.fm limite les requêtes, réessaie dans quelques minutes"
        else -> "Last.fm : $msg"
    }

    private fun parse(o: JSONObject): Scrobble? {
        val uts = o.optJSONObject("date")?.optString("uts")?.toLongOrNull() ?: return null // « en cours » : pas encore daté
        val artist = o.optJSONObject("artist")?.optString("#text").orEmpty().ifBlank { o.optJSONObject("artist")?.optString("name").orEmpty() }
        val title = o.optString("name")
        if (title.isBlank() || artist.isBlank()) return null
        val imgs = o.optJSONArray("image")
        var img: String? = null
        if (imgs != null) for (i in 0 until imgs.length()) {
            val u = imgs.getJSONObject(i).optString("#text")
            if (u.isNotBlank()) img = u // la dernière = la plus grande
        }
        if (img?.contains("2a96cbd8b46e442fc41c2b86b821562f") == true) img = null // image vide de Last.fm
        return Scrobble(uts * 1000, title, artist, o.optJSONObject("album")?.optString("#text").orEmpty(), img)
    }

    private suspend fun load(): List<Scrobble> = withContext(Dispatchers.IO) {
        val f = file ?: return@withContext emptyList()
        if (!f.exists()) return@withContext emptyList()
        runCatching {
            f.bufferedReader().useLines { seq ->
                seq.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 5) null else Scrobble(p[0].toLong(), p[1], p[2], p[3], p[4].ifBlank { null })
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<Scrobble>) {
        val f = file ?: return
        fun c(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        f.bufferedWriter().use { w ->
            list.forEach { w.write("${it.ts}\t${c(it.title)}\t${c(it.artist)}\t${c(it.album)}\t${it.image.orEmpty()}\n") }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String, attempt: Int = 0): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Hush/1.0 (appli perso Android)")
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        val code = c.responseCode
        if ((code == 429 || code >= 500) && attempt < 4) {
            Thread.sleep(1500L shl attempt)
            return getJson(url, attempt + 1)
        }
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return runCatching { JSONObject(body) }.getOrElse { throw IllegalStateException("Last.fm a répondu $code") }
    }
}
