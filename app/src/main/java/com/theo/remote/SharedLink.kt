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
        /** Les plateformes où le son est dispo (Spotify, Apple Music…) : nom → lien. */
        val platforms: List<Pair<String, String>> = emptyList(),
        val platformsLoading: Boolean = false,
    )

    /** Raison précise quand on ne trouve pas de chanson (ex. « son original » TikTok). */
    @Volatile private var why: String? = null

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
            why = null
            val found = withContext(Dispatchers.IO) { runCatching { resolve(t) }.getOrNull() }
            if (found == null) {
                _ui.value = _ui.value?.copy(loading = false, error = why ?: "Impossible de lire ce lien")
                return@launch
            }
            val (title, artist) = found
            val res = runCatching { TidalApi.search("$title $artist".trim()) }.getOrDefault(emptyList())
            if (res.isEmpty()) {
                _ui.value = _ui.value?.copy(loading = false, asked = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - "), error = "Pas sur Tidal", platformsLoading = true)
                findPlatforms(null, title, artist)
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
                platformsLoading = true,
            )
            findPlatforms(best, title, artist)
        }
    }

    /**
     * Où écouter ce son : on part du lien Tidal (ou d'Apple Music si pas sur Tidal)
     * et song.link donne les liens de toutes les plateformes.
     */
    private suspend fun findPlatforms(best: TidalApi.Result?, title: String, artist: String) {
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val from = if (best != null) "https://tidal.com/browse/track/${best.id}" else {
                    val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
                    val j = JSONObject(fetch("https://itunes.apple.com/search?term=$q&entity=song&limit=1&country=FR") ?: return@runCatching emptyList())
                    j.optJSONArray("results")?.optJSONObject(0)?.optString("trackViewUrl")?.ifBlank { null } ?: return@runCatching emptyList()
                }
                val j = JSONObject(fetch("https://api.song.link/v1-alpha.1/links?userCountry=FR&url=" + URLEncoder.encode(from, "UTF-8")) ?: return@runCatching emptyList())
                val links = j.optJSONObject("linksByPlatform") ?: return@runCatching emptyList()
                val names = linkedMapOf(
                    "tidal" to "Tidal", "spotify" to "Spotify", "appleMusic" to "Apple Music", "deezer" to "Deezer",
                    "youtubeMusic" to "YouTube Music", "youtube" to "YouTube", "soundcloud" to "SoundCloud",
                    "amazonMusic" to "Amazon Music", "audiomack" to "Audiomack",
                )
                names.mapNotNull { (k, name) -> links.optJSONObject(k)?.optString("url")?.ifBlank { null }?.let { name to it } }
            }.getOrDefault(emptyList())
        }
        // pas trouvé par la recherche Tidal, mais song.link connaît le son sur Tidal : on le récupère
        if (best == null && list.any { it.first == "Tidal" }) {
            val id = Regex("track/(\\d+)").find(list.first { it.first == "Tidal" }.second)?.groupValues?.get(1)
            val res = runCatching { TidalApi.search("$title $artist".trim().ifBlank { title }) }.getOrDefault(emptyList())
            res.firstOrNull { it.id == id }?.let { r ->
                _ui.value = _ui.value?.copy(best = r, others = res.filter { it.id != r.id }.take(4), error = null)
            }
        }
        _ui.value = _ui.value?.copy(platforms = list, platformsLoading = false)
    }

    private fun n(x: String) = x.lowercase().substringBefore(" (").substringBefore(" [").replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun sourceOf(t: String) = when {
        "spotify" in t -> "Spotify"
        "youtu" in t -> "YouTube"
        "deezer" in t -> "Deezer"
        "apple.com" in t -> "Apple Music"
        "soundcloud" in t -> "SoundCloud"
        "tiktok" in t -> "TikTok"
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
            "tiktok" in url -> tiktok(url)
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

    /**
     * Vidéo TikTok → le son utilisé. D'abord l'intégration officielle (oEmbed), qui affiche
     * « ♬ Titre - Artiste » ; sinon la page de la vidéo. Un « son original » n'est pas une chanson.
     */
    private fun tiktok(url: String): Pair<String, String>? {
        val full = finalUrl(url) ?: url
        var music: String? = null
        fetch("https://www.tiktok.com/oembed?url=" + URLEncoder.encode(full, "UTF-8"))?.let { raw ->
            val html = runCatching { JSONObject(raw).optString("html") }.getOrDefault("")
            music = Regex("title=\"♬\\s*([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: Regex("♬\\s*([^<]+)<").find(html)?.groupValues?.get(1)
        }
        if (music == null) {
            val page = fetch(full)
            val i = page?.indexOf("\"music\":{") ?: -1
            if (page != null && i >= 0) {
                val chunk = page.substring(i, minOf(page.length, i + 3000))
                val t = Regex("\"title\":\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
                val a = Regex("\"authorName\":\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
                if (t != null) music = if (a != null) "$t - $a" else t
            }
        }
        val m = music?.let { android.text.Html.fromHtml(it, 0).toString().trim() } ?: run {
            why = "Impossible de lire le son de cette vidéo TikTok"
            return null
        }
        if (Regex("(?i)^(original sound|son original|sonido original|suono originale|originalton|som original)").containsMatchIn(m)) {
            why = "C'est un son original (${m.substringAfter(" - ", "").ifBlank { "pas une chanson" }}) : pas une chanson référencée. Lance la vidéo et utilise « Identifier »."
            return null
        }
        val cut = m.lastIndexOf(" - ")
        return if (cut > 0) m.substring(0, cut).trim() to m.substring(cut + 3).trim() else m to ""
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
