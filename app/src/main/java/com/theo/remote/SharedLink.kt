package com.theo.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * « Partager → Hush » : un lien Spotify, YouTube, Deezer ou Apple Music (ou juste un texte « artiste - titre »)
 * est retrouvé sur Tidal, prêt à lire, mettre en file ou ranger dans une playlist.
 */
object SharedLink {
    data class Ui(
        val source: String,
        val asked: String,
        val loading: Boolean = true,
        val best: TidalApi.Result? = null,
        val others: List<TidalApi.Result> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun close() { _ui.value = null }
    fun pick(r: TidalApi.Result) { _ui.value = _ui.value?.let { it.copy(best = r, others = (listOfNotNull(it.best) + it.others).filter { o -> o.id != r.id }) } }

    /** Texte reçu par « Partager ». */
    fun handle(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _ui.value = Ui(source = sourceOf(t), asked = t.take(80))
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { resolve(t) }.getOrNull() }
            if (found == null) {
                _ui.value = _ui.value?.copy(loading = false, error = "Impossible de lire ce lien")
                return@launch
            }
            val (title, artist) = found
            val res = runCatching { TidalApi.search("$title $artist".trim()) }.getOrDefault(emptyList())
            if (res.isEmpty()) {
                _ui.value = _ui.value?.copy(loading = false, asked = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - "), error = "Pas trouvé sur Tidal")
                return@launch
            }
            val nt = n(title)
            val na = n(artist)
            // le bon titre du bon artiste d'abord, sinon le premier résultat
            val best = res.firstOrNull { n(it.title) == nt && (na.isEmpty() || it.artists.any { a -> n(a) == na || na.contains(n(a)) }) }
                ?: res.firstOrNull { n(it.title).startsWith(nt) || nt.startsWith(n(it.title)) }
                ?: res.first()
            _ui.value = _ui.value?.copy(
                loading = false,
                asked = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - "),
                best = best,
                others = res.filter { it.id != best.id }.take(4),
            )
        }
    }

    private fun n(x: String) = x.lowercase().substringBefore(" (").substringBefore(" [").replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun sourceOf(t: String) = when {
        "spotify" in t -> "Spotify"
        "youtu" in t -> "YouTube"
        "deezer" in t -> "Deezer"
        "apple.com" in t -> "Apple Music"
        "soundcloud" in t -> "SoundCloud"
        else -> "Texte partagé"
    }

    /** → (titre, artiste) */
    private fun resolve(text: String): Pair<String, String>? {
        val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')')
        if (url == null) {
            // juste du texte : « artiste - titre » ou « titre de artiste »
            val parts = text.split(" - ", " – ", " — ")
            return if (parts.size >= 2) parts.drop(1).joinToString(" ") to parts[0] else text to ""
        }
        return when {
            "spotify" in url -> spotify(url)
            "youtu" in url -> youtube(url)
            "deezer" in url -> deezer(url)
            "apple.com" in url -> apple(url)
            else -> pageTitle(url)?.let { cleanTitle(it) }
        }
    }

    private fun spotify(url: String): Pair<String, String>? {
        // le titre de la page : « Titre - song and lyrics by Artiste | Spotify »
        val title = pageTitle(url) ?: return null
        Regex("^(.*?) - (?:song|single|album)(?: and lyrics)? by (.*?) \\| Spotify").find(title)?.let { return it.groupValues[1] to it.groupValues[2] }
        Regex("^(.*?) \\| Spotify").find(title)?.let { return cleanTitle(it.groupValues[1]) }
        return null
    }

    private fun youtube(url: String): Pair<String, String>? {
        val j = JSONObject(fetch("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")) ?: return null)
        val title = j.optString("title")
        val channel = j.optString("author_name").removeSuffix(" - Topic").replace(Regex("(?i)vevo$"), "").trim()
        val (t, a) = cleanTitle(title)
        return if (a.isNotBlank()) t to a else t to channel
    }

    private fun deezer(url: String): Pair<String, String>? {
        // liens courts : on suit la redirection jusqu'à l'adresse du titre
        val final = finalUrl(url) ?: url
        val id = Regex("track/(\\d+)").find(final)?.groupValues?.get(1) ?: return pageTitle(final)?.let { cleanTitle(it) }
        val j = JSONObject(fetch("https://api.deezer.com/track/$id") ?: return null)
        return j.optString("title_short").ifBlank { j.optString("title") } to (j.optJSONObject("artist")?.optString("name") ?: "")
    }

    private fun apple(url: String): Pair<String, String>? {
        val id = Regex("[?&]i=(\\d+)").find(url)?.groupValues?.get(1) ?: Regex("/song/[^/]+/(\\d+)").find(url)?.groupValues?.get(1)
            ?: return pageTitle(url)?.let { cleanTitle(it) }
        val j = JSONObject(fetch("https://itunes.apple.com/lookup?id=$id") ?: return null)
        val r = j.optJSONArray("results")?.optJSONObject(0) ?: return null
        return r.optString("trackName") to r.optString("artistName")
    }

    /** « Artiste - Titre (Official Video) » → (Titre, Artiste) */
    private fun cleanTitle(raw: String): Pair<String, String> {
        val t = raw.replace(Regex("(?i)[(\\[](official|clip|lyrics?|audio|visuali[sz]er|video|vid[ée]o|music video|paroles)[^)\\]]*[)\\]]"), "")
            .replace(Regex("\\s+"), " ").trim()
        val parts = t.split(" - ", " – ", " — ", " | ")
        return if (parts.size >= 2) parts[1].trim() to parts[0].trim() else t to ""
    }

    private fun pageTitle(url: String): String? {
        val html = fetch(url) ?: return null
        val og = Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val tt = Regex("<title[^>]*>([^<]+)</title>").find(html)?.groupValues?.get(1)
        return (tt ?: og)?.let { android.text.Html.fromHtml(it, 0).toString() }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 8000
        readTimeout = 10000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) Hush")
        setRequestProperty("Accept-Language", "fr,en;q=0.8")
    }

    private fun fetch(url: String): String? = runCatching {
        val c = open(url)
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun finalUrl(url: String): String? = runCatching {
        var u = url
        repeat(5) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 8000 }
            val loc = c.getHeaderField("Location") ?: return@runCatching u
            u = URL(URL(u), loc).toString()
        }
        u
    }.getOrNull()
}
