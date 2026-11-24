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
        /** Comment on l'a trouvé (ex. « reconnu à l'écoute »). */
        val how: String? = null,
    )

    @Volatile private var how: String? = null

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
            why = null; how = null
            if ("tiktok" in t) _ui.value = _ui.value?.copy(how = "Écoute de la vidéo…")
            val found = withContext(Dispatchers.IO) { runCatching { resolve(t) }.getOrNull() }
            if (found == null) {
                _ui.value = _ui.value?.copy(loading = false, error = why ?: "Impossible de lire ce lien", how = null)
                return@launch
            }
            val (title, artist) = found
            _ui.value = _ui.value?.copy(how = how)
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
     * Où écouter ce son. Plusieurs sources, pour ne pas dépendre d'une seule :
     * song.link (toutes les plateformes d'un coup), puis Deezer et Apple Music en direct,
     * et des liens de recherche pour Spotify, YouTube Music et SoundCloud.
     */
    private suspend fun findPlatforms(best: TidalApi.Result?, title: String, artist: String) {
        val t = best?.title ?: title
        val a = best?.artists?.firstOrNull() ?: artist.split(",", "&", " feat").first().trim()
        val q = "$t $a".trim()
        val enc = URLEncoder.encode(q, "UTF-8")
        val found = linkedMapOf<String, String>()
        withContext(Dispatchers.IO) {
            if (best != null) found["Tidal"] = "https://tidal.com/browse/track/${best.id}"
            // Apple Music (recherche iTunes, publique)
            val apple = runCatching {
                val j = JSONObject(fetch("https://itunes.apple.com/search?term=$enc&entity=song&limit=5&country=FR") ?: return@runCatching null)
                val arr = j.optJSONArray("results") ?: return@runCatching null
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { n(it.optString("trackName")).startsWith(n(t)) || n(t).startsWith(n(it.optString("trackName"))) }
                    ?.optString("trackViewUrl")?.ifBlank { null }
            }.getOrNull()
            // song.link : toutes les plateformes à partir de Tidal ou d'Apple Music
            val from = found["Tidal"] ?: apple
            if (from != null) runCatching {
                val j = JSONObject(fetch("https://api.song.link/v1-alpha.1/links?userCountry=FR&url=" + URLEncoder.encode(from, "UTF-8")) ?: return@runCatching)
                val links = j.optJSONObject("linksByPlatform") ?: return@runCatching
                linkedMapOf(
                    "spotify" to "Spotify", "appleMusic" to "Apple Music", "deezer" to "Deezer", "youtubeMusic" to "YouTube Music",
                    "youtube" to "YouTube", "soundcloud" to "SoundCloud", "amazonMusic" to "Amazon Music", "audiomack" to "Audiomack", "tidal" to "Tidal",
                ).forEach { (k, name) -> links.optJSONObject(k)?.optString("url")?.ifBlank { null }?.let { if (name !in found) found[name] = it } }
            }
            if (apple != null && "Apple Music" !in found) found["Apple Music"] = apple
            // Deezer (API publique)
            if ("Deezer" !in found) runCatching {
                val j = JSONObject(fetch("https://api.deezer.com/search?q=$enc&limit=5") ?: return@runCatching)
                val arr = j.optJSONArray("data") ?: return@runCatching
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { n(it.optString("title_short").ifBlank { it.optString("title") }).let { x -> x.startsWith(n(t)) || n(t).startsWith(x) } }
                    ?.optString("link")?.ifBlank { null }?.let { found["Deezer"] = it }
            }
        }
        // pas de lien direct trouvé : on propose au moins une recherche sur les grandes plateformes
        val list = found.toList().toMutableList()
        if (list.none { it.first == "Spotify" }) list += "Spotify (chercher)" to "https://open.spotify.com/search/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        if (list.none { it.first.startsWith("YouTube") }) list += "YouTube Music (chercher)" to "https://music.youtube.com/search?q=$enc"
        if (list.none { it.first == "SoundCloud" }) list += "SoundCloud (chercher)" to "https://soundcloud.com/search?q=$enc"

        // pas trouvé par la recherche Tidal, mais song.link connaît le son sur Tidal : on le récupère
        if (best == null && found["Tidal"] != null) {
            val id = Regex("track/(\\d+)").find(found["Tidal"]!!)?.groupValues?.get(1)
            val res = runCatching { TidalApi.search(q.ifBlank { title }) }.getOrDefault(emptyList())
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
        // 1) on récupère la vidéo, on en tire des extraits audio de 12 s et on les « shazame »
        TidalRemote.appContext?.let { Recognizer.init(it) }
        var recErr: String? = null
        val ctx = TidalRemote.appContext
        val video = downloadTikTok(full)
        if (video != null && ctx != null) {
            var clips = 0
            for (start in listOf(3, 15, 0)) {
                val clip = Recognizer.clipFromVideo(ctx, video, start) ?: continue
                clips++
                runCatching { Recognizer.identify(clip) }.onFailure { recErr = it.message }.getOrNull()?.let {
                    how = "Reconnu à l'écoute"
                    return it.first to it.second
                }
                if (recErr != null && ("api_token" in recErr!! || "Limite" in recErr!!)) break
            }
            if (clips == 0 && recErr == null) recErr = "pas réussi à lire le son de la vidéo"
        } else recErr = "vidéo TikTok pas récupérée"
        // 2) sinon : AudD essaie d'aller chercher le lien lui-même
        if (Recognizer.token.isNotBlank()) runCatching { Recognizer.identify(null, url = full) }.getOrNull()?.let {
            how = "Reconnu à l'écoute"
            return it.first to it.second
        }
        // 3) pas reconnu : on le dit, sans proposer le titre affiché par TikTok (souvent faux)
        why = "Son pas reconnu à l'écoute" + (recErr?.let { " : ${friendly(it)}" } ?: "") +
            ". Lance la vidéo à côté et utilise « Identifier »."
        return null
    }

    private fun friendly(e: String) = when {
        "fingerprint" in e -> "le service n'a pas pu écouter ce fichier"
        "api_token" in e || "Clé AudD" in e -> "il faut ta clé AudD : Identifier › Ajouter une clé"
        "Limite" in e || "limit" in e -> "limite AudD du jour atteinte"
        else -> e
    }

    /** Télécharge la vidéo (au plus 10 Mo) le temps de la reconnaissance. Rien n'est gardé. */
    private fun downloadTikTok(full: String): ByteArray? = runCatching {
        val c = open(full)
        val html = c.inputStream.bufferedReader().use { it.readText() }
        val cookies = c.headerFields["Set-Cookie"].orEmpty().joinToString("; ") { it.substringBefore(';') }
        val vid = Regex("/video/(\\d+)").find(full)?.groupValues?.get(1) ?: return@runCatching null
        // on part de la fiche de CETTE vidéo (sinon la page peut contenir d'autres vidéos suggérées)
        val at = html.indexOf("\"id\":\"$vid\"").takeIf { it >= 0 } ?: return@runCatching null
        val raw = Regex("\"(?:playAddr|downloadAddr)\":\"(https:[^\"]+)\"").find(html, at)?.groupValues?.get(1) ?: return@runCatching null
        val video = unescape(raw).replace("\\u0026", "&")
        val v = (URL(video).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) Hush")
            setRequestProperty("Referer", "https://www.tiktok.com/")
            if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
        }
        if (v.responseCode !in 200..299) return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        v.inputStream.use { input ->
            val buf = ByteArray(64 * 1024)
            while (out.size() < 10 * 1024 * 1024) { val k = input.read(buf); if (k < 0) break; out.write(buf, 0, k) }
        }
        out.toByteArray().takeIf { it.size > 50_000 }
    }.getOrNull()

    /** Le nom du son affiché sur la vidéo TikTok (♬ …). */
    private fun shownTikTokSound(full: String): Pair<String, String>? {
        var music: String? = null
        fetch("https://www.tiktok.com/oembed?url=" + URLEncoder.encode(full, "UTF-8"))?.let { raw ->
            val html = runCatching { JSONObject(raw).optString("html") }.getOrDefault("")
            music = Regex("title=\"♬\\s*([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: Regex("♬\\s*([^<]+)<").find(html)?.groupValues?.get(1)
        }
        // sinon : la page de la vidéo, puis le lecteur intégré officiel (/embed/v2/…)
        val id = Regex("/video/(\\d+)").find(full)?.groupValues?.get(1)
        for (src in listOfNotNull(full, id?.let { "https://www.tiktok.com/embed/v2/$it" })) {
            if (music != null) break
            val page = fetch(src) ?: continue
            music = musicFromPage(page)
        }
        val m = music?.let { android.text.Html.fromHtml(it, 0).toString().trim() } ?: run {
            why = "Impossible de lire le son de cette vidéo TikTok"
            return null
        }
        if (Regex("(?i)^(original sound|son original|sonido original|suono originale|originalton|som original|оригинальный звук|orijinal ses|dźwięk oryginalny|origineel geluid|оригінальний звук|الصوت الأصلي|オリジナル楽曲|오리지널 사운드|suara asli|âm thanh gốc|เสียงต้นฉบับ)").containsMatchIn(m)) {
            why = "C'est un son original (${m.substringAfter(" - ", "").ifBlank { "pas une chanson" }}) : pas une chanson référencée. Lance la vidéo et utilise « Identifier »."
            return null
        }
        val cut = m.lastIndexOf(" - ")
        return if (cut > 0) m.substring(0, cut).trim() to m.substring(cut + 3).trim() else m to ""
    }

    /** Le son dans le code d'une page TikTok : bloc « music » (titre + auteur), sinon le texte « ♬ … ». */
    private fun musicFromPage(page: String): String? {
        for (marker in listOf("\"music\":{", "\"musicInfo\":{")) {
            val i = page.indexOf(marker)
            if (i < 0) continue
            val chunk = page.substring(i, minOf(page.length, i + 3000))
            val t = Regex("\"(?:title|musicName)\":\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            val a = Regex("\"(?:authorName|author|musicAuthor)\":\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            if (t != null) return unescape(if (a != null) "$t - $a" else t)
        }
        return Regex("♬\\s*([^<\"]{2,120})").find(page)?.groupValues?.get(1)?.let { unescape(it) }
    }

    /** « \\u00e9 » et compagnie → vrais caractères. */
    private fun unescape(x: String) = Regex("\\\\u([0-9a-fA-F]{4})").replace(x) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\/", "/")

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
