package com.theo.remote

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Recherche dans le catalogue via l'API officielle Tidal (openapi.tidal.com/v2).
 * Authentification "client credentials" : pas besoin de ton compte, juste l'ID/secret
 * de l'appli créée sur developer.tidal.com (rangés dans local.properties).
 */
object TidalApi {

    private const val COUNTRY = "FR"
    private const val AUTH_URL = "https://auth.tidal.com/v1/oauth2/token"
    private const val API = "https://openapi.tidal.com/v2"

    data class Result(
        val id: String,
        val title: String,
        val version: String?,
        val artists: List<String>,
        val album: String?,
        val coverUrl: String?,
        val durationMs: Long,
        val explicit: Boolean,
        /** Qualités dispo selon Tidal : LOSSLESS, HIRES_LOSSLESS, DOLBY_ATMOS… */
        val mediaTags: List<String> = emptyList(),
        val artistIds: List<String> = emptyList(),
        val albumId: String? = null,
        /** Identifiant de la ligne dans une playlist (pour la retirer). */
        val itemId: String? = null,
        /** Tempo donné par Tidal (battements par minute), quand il le connaît : sert au tri par énergie. */
        val bpm: Float? = null,
        /** MAJOR / MINOR… (Tidal) */
        val keyScale: String? = null,
        /** Date d'ajout dans la playlist (ms), quand Tidal la donne. */
        val addedAt: Long? = null,
    ) {
        val fullTitle get() = if (version.isNullOrBlank()) title else "$title ($version)"
        val artistLine get() = artists.joinToString(", ")
        /** Ce qu'on envoie à Tidal pour qu'il lance ce titre précis. */
        val playQuery get() = listOfNotNull(title, version?.ifBlank { null }, artists.firstOrNull()).joinToString(" ")
    }

    class ApiException(message: String) : Exception(message)

    private var token: String? = null
    private var tokenExpiresAt = 0L

    val isConfigured get() = BuildConfig.TIDAL_CLIENT_ID.isNotBlank() && BuildConfig.TIDAL_CLIENT_SECRET.isNotBlank()

    suspend fun search(query: String): List<Result> = withContext(Dispatchers.IO) {
        if (!isConfigured) throw ApiException("Identifiants Tidal manquants dans local.properties")
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val include = URLEncoder.encode("tracks,tracks.artists,tracks.albums,tracks.albums.coverArt", "UTF-8")
        val json = get("$API/searchResults?filter%5Bquery%5D=$q&countryCode=$COUNTRY&include=$include")
        parse(json)
    }

    data class SearchAll(val tracks: List<Result>, val artists: List<Artist>)

    /** Recherche complète : les titres + les artistes (avec leur photo), en un seul appel. */
    suspend fun searchAll(query: String): SearchAll = withContext(Dispatchers.IO) {
        if (!isConfigured) throw ApiException("Identifiants Tidal manquants dans local.properties")
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val include = URLEncoder.encode("tracks,tracks.artists,tracks.albums,tracks.albums.coverArt,artists,artists.profileArt", "UTF-8")
        val root = runCatching { get("$API/searchResults?filter%5Bquery%5D=$q&countryCode=$COUNTRY&include=$include") }.getOrNull()
            ?: return@withContext SearchAll(search(query), emptyList())
        val tracks = parse(root)
        val byKey = index(root)
        val data = root.opt("data")
        val first = when (data) { is JSONArray -> if (data.length() > 0) data.getJSONObject(0) else null; is JSONObject -> data; else -> null }
        val artists = relIds(first?.optJSONObject("relationships"), "artists")
            .mapNotNull { id -> byKey["artists:$id"]?.let { artistFrom(it, byKey) } }
            .filter { it.name.isNotBlank() }
        SearchAll(tracks, artists.take(10))
    }

    // ------------------------------------------------------------------ artistes et albums

    data class Artist(val id: String, val name: String, val pictureUrl: String?)

    data class Album(
        val id: String,
        val title: String,
        val artist: String?,
        val coverUrl: String?,
        val year: String?,
        val count: Int,
        val type: String?,
        val releaseDate: String? = null,
    )

    data class ArtistPage(val artist: Artist, val topTracks: List<Result>, val albums: List<Album>)
    data class AlbumPage(val album: Album, val tracks: List<Result>)

    private val artistCache = HashMap<String, ArtistPage>()
    private val albumCache = HashMap<String, AlbumPage>()

    // ---------- pages artiste / album gardées sur le téléphone (instantanées, et lisibles sans réseau)
    /** Dossier des pages gardées (donné au lancement de l'appli). */
    @Volatile var detailDir: java.io.File? = null
    private const val DETAIL_FRESH_MS = 12 * 3600_000L

    private fun detailFile(kind: String, id: String) =
        detailDir?.let { java.io.File(it.apply { mkdirs() }, kind + "_" + id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    private fun artistJson(a: Artist) = JSONObject().put("id", a.id).put("name", a.name).put("pic", a.pictureUrl ?: "")
    private fun artistOf(o: JSONObject) = Artist(o.optString("id"), o.optString("name"), o.optString("pic").ifBlank { null })
    private fun albumJson(a: Album) = JSONObject().put("id", a.id).put("title", a.title).put("artist", a.artist ?: "").put("cover", a.coverUrl ?: "")
        .put("year", a.year ?: "").put("count", a.count).put("type", a.type ?: "").put("date", a.releaseDate ?: "")
    private fun albumOf(o: JSONObject) = Album(o.optString("id"), o.optString("title"), o.optString("artist").ifBlank { null }, o.optString("cover").ifBlank { null },
        o.optString("year").ifBlank { null }, o.optInt("count"), o.optString("type").ifBlank { null }, o.optString("date").ifBlank { null })

    private fun saveDetail(kind: String, id: String, o: JSONObject) {
        val f = detailFile(kind, id) ?: return
        runCatching { SafeFile.write(f, o.put("at", System.currentTimeMillis()).toString()) }
    }

    /** (page, enregistrée il y a combien de ms) */
    private fun <T> loadDetail(kind: String, id: String, parse: (JSONObject) -> T): Pair<T, Long>? {
        val f = detailFile(kind, id) ?: return null
        val text = SafeFile.read(f) ?: return null
        return runCatching { val o = JSONObject(text); parse(o) to (System.currentTimeMillis() - o.optLong("at")) }.getOrNull()
    }

    private fun parseArtistPage(o: JSONObject) = ArtistPage(artistOf(o.getJSONObject("artist")),
        History.decodeResults(o.optString("top")),
        o.optJSONArray("albums")?.let { a -> (0 until a.length()).map { albumOf(a.getJSONObject(it)) } } ?: emptyList())

    private fun parseAlbumPage(o: JSONObject) = AlbumPage(albumOf(o.getJSONObject("album")), History.decodeResults(o.optString("tracks")))

    private fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun artistFrom(o: JSONObject, byKey: Map<String, JSONObject>): Artist? {
        val a = o.optJSONObject("attributes") ?: return null
        return Artist(o.optString("id"), a.optString("name"), coverFor(o, byKey, "profileArt"))
    }

    private fun albumFrom(o: JSONObject, byKey: Map<String, JSONObject>): Album? {
        val a = o.optJSONObject("attributes") ?: return null
        val artist = relIds(o.optJSONObject("relationships"), "artists")
            .mapNotNull { byKey["artists:$it"]?.optJSONObject("attributes")?.optString("name") }
            .joinToString(", ").ifBlank { null }
        return Album(
            id = o.optString("id"),
            title = a.optString("title"),
            artist = artist,
            coverUrl = coverFor(o, byKey),
            year = a.optString("releaseDate").take(4).ifBlank { null },
            count = a.optInt("numberOfItems", 0),
            type = a.optString("type").ifBlank { null },
            releaseDate = a.optString("releaseDate").ifBlank { null },
        )
    }

    /** Trouve un artiste par son nom (pour ouvrir sa page depuis le lecteur). */
    suspend fun findArtist(name: String): Artist? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val root = get("$API/searchResults?filter%5Bquery%5D=$q&countryCode=$COUNTRY&include=artists,artists.profileArt")
        val byKey = index(root)
        val list = byKey.values.filter { it.optString("type") == "artists" }.mapNotNull { artistFrom(it, byKey) }
        list.firstOrNull { n(it.name) == n(name) } ?: list.firstOrNull()
    }

    /** Trouve un album par son titre (+ artiste). */
    suspend fun findAlbum(title: String, artist: String): Album? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val root = get("$API/searchResults?filter%5Bquery%5D=$q&countryCode=$COUNTRY&include=albums,albums.coverArt,albums.artists")
        val byKey = index(root)
        val list = byKey.values.filter { it.optString("type") == "albums" }.mapNotNull { albumFrom(it, byKey) }
        list.firstOrNull { n(it.title) == n(title) && (it.artist == null || n(it.artist).contains(n(artist.split(",").first()))) }
            ?: list.firstOrNull { n(it.title) == n(title) }
            ?: list.firstOrNull()
    }

    /**
     * Pochette vidéo (animée) d'un album, si Tidal en fournit une : une « artwork » de type vidéo
     * dans coverArt (ou suggestedCoverArts). Renvoie (adresse du .mp4 ou null, ce qu'on a vu — pour le diagnostic).
     */
    suspend fun videoCover(albumId: String): Pair<String?, String> = withContext(Dispatchers.IO) {
        val seen = ArrayList<String>()
        fun scan(root: JSONObject): String? {
            val included = root.optJSONArray("included") ?: return null
            var best: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until included.length()) {
                val o = included.getJSONObject(i)
                if (o.optString("type") != "artworks") continue
                val at = o.optJSONObject("attributes") ?: continue
                val mt = at.optString("mediaType")
                seen += mt.ifBlank { "?" }
                val files = at.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val f = files.getJSONObject(j)
                    val href = f.optString("href")
                    val isVideo = mt.equals("VIDEO", true) || href.substringBefore('?').endsWith(".mp4", true)
                    if (!isVideo || href.isBlank()) continue
                    val w = f.optJSONObject("meta")?.optInt("width") ?: 0
                    val diff = kotlin.math.abs(w - 640)
                    if (diff < bestDiff) { bestDiff = diff; best = href }
                }
            }
            return best
        }
        val a = runCatching { scan(get("$API/albums/$albumId?countryCode=$COUNTRY&include=coverArt")) }.getOrNull()
        if (a != null) return@withContext a to "vidéo trouvée"
        val b = runCatching { scan(get("$API/albums/$albumId/relationships/suggestedCoverArts?countryCode=$COUNTRY&include=suggestedCoverArts", retry = false)) }.getOrNull()
        b to (if (b != null) "vidéo trouvée (suggérée)" else "pas de vidéo · artworks vus : " + seen.distinct().joinToString().ifBlank { "aucun" })
    }

    /** Page artiste : photo, sons populaires, albums. */
    suspend fun artistPage(artist: Artist): ArtistPage = withContext(Dispatchers.IO) {
        artistCache[artist.id]?.let { return@withContext it }
        val saved = loadDetail("artist", artist.id, ::parseArtistPage)
        if (saved != null && saved.second < DETAIL_FRESH_MS) return@withContext saved.first.also { artistCache[artist.id] = it }
        val fresh = fetchArtistPage(artist)
        // rien reçu (pas de réseau) : la version gardée, même ancienne
        if (fresh.topTracks.isEmpty() && fresh.albums.isEmpty()) return@withContext saved?.first ?: fresh
        artistCache[artist.id] = fresh
        saveDetail("artist", artist.id, JSONObject().put("artist", artistJson(fresh.artist)).put("top", History.encodeResults(fresh.topTracks))
            .put("albums", JSONArray().apply { fresh.albums.forEach { put(albumJson(it)) } }))
        fresh
    }

    private suspend fun fetchArtistPage(artist: Artist): ArtistPage {
        val base = "$API/artists/${artist.id}"

        // photo (si la recherche ne l'a pas donnée)
        val full = if (artist.pictureUrl == null) runCatching {
            val root = get("$base?countryCode=$COUNTRY&include=profileArt")
            val byKey = index(root)
            root.optJSONObject("data")?.let { artistFrom(it, byKey) }
        }.getOrNull() ?: artist else artist

        // sons populaires : relation « tracks » de l'artiste, sinon une recherche filtrée
        val top = runCatching {
            val root = get("$base/relationships/tracks?countryCode=$COUNTRY&include=tracks,tracks.artists,tracks.albums,tracks.albums.coverArt")
            tracksFrom(root.optJSONArray("data") ?: JSONArray(), index(root))
        }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { search(artist.name).filter { artist.id in it.artistIds } }.getOrNull()
            ?: emptyList()

        // albums
        val albums = runCatching {
            val root = get("$base/relationships/albums?countryCode=$COUNTRY&include=albums,albums.coverArt")
            val byKey = index(root)
            val refs = root.optJSONArray("data") ?: JSONArray()
            (0 until refs.length()).mapNotNull { i -> byKey["albums:${refs.getJSONObject(i).optString("id")}"]?.let { albumFrom(it, byKey) } }
        }.getOrNull() ?: emptyList()

        return ArtistPage(full, top.distinctBy { it.id }.take(10), albums.sortedByDescending { it.year ?: "" })
    }

    /** Page album : pochette, infos, liste des titres. */
    suspend fun albumPage(album: Album): AlbumPage = withContext(Dispatchers.IO) {
        albumCache[album.id]?.let { return@withContext it }
        val saved = loadDetail("album", album.id, ::parseAlbumPage)
        if (saved != null && saved.second < DETAIL_FRESH_MS) return@withContext saved.first.also { albumCache[album.id] = it }
        val fresh = try { fetchAlbumPage(album) } catch (e: Exception) { return@withContext saved?.first ?: throw e }
        if (fresh.tracks.isEmpty() && saved != null) return@withContext saved.first
        albumCache[album.id] = fresh
        saveDetail("album", album.id, JSONObject().put("album", albumJson(fresh.album)).put("tracks", History.encodeResults(fresh.tracks)))
        fresh
    }

    private suspend fun fetchAlbumPage(album: Album): AlbumPage {
        val base = "$API/albums/${album.id}"
        val full = if (album.coverUrl == null || album.artist == null) runCatching {
            val root = get("$base?countryCode=$COUNTRY&include=coverArt,artists")
            val byKey = index(root)
            root.optJSONObject("data")?.let { albumFrom(it, byKey) }
        }.getOrNull() ?: album else album

        val tracks = ArrayList<Result>()
        var url: String? = "$base/relationships/items?countryCode=$COUNTRY&include=items,items.artists"
        var pages = 0
        while (url != null && pages < 6) {
            val root = get(url)
            tracks += tracksFrom(root.optJSONArray("data") ?: JSONArray(), index(root))
            url = nextLink(root)
            pages++
        }
        // les titres d'un album n'ont pas leur propre pochette : on met celle de l'album
        val withCover = tracks.map { if (it.coverUrl == null) it.copy(coverUrl = full.coverUrl, album = it.album ?: full.title) else it }
        return AlbumPage(full, withCover)
    }

    // ------------------------------------------------------------------ qualité du son en cours

    private val qualityCache = HashMap<String, List<String>>()

    /** Qualités disponibles pour un son (cherché par titre + artiste dans le catalogue). */
    suspend fun qualityOf(title: String, artist: String): List<String> {
        val key = "$title|$artist"
        qualityCache[key]?.let { return it }
        if (!isConfigured) return emptyList()
        val tags = try {
            fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            val main = n(artist.split(",", "&").first())
            val results = search("$title $artist")
            (results.firstOrNull { n(it.fullTitle) == n(title) && it.artists.any { a -> n(a).contains(main) || main.contains(n(a)) } }
                ?: results.firstOrNull { n(it.title) == n(title) })?.mediaTags ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        qualityCache[key] = tags
        return tags
    }

    // ------------------------------------------------------------------ ton compte (connexion requise)

    data class Playlist(val id: String, val name: String, val count: Int, val coverUrl: String?)

    /** Tes playlists (créées et ajoutées) dans ta collection Tidal. */
    suspend fun myPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val pages = userPages(
            "$API/userCollectionPlaylists/me/relationships/items?countryCode=$COUNTRY&include=items,items.coverArt",
            fallback = "$API/userCollectionPlaylists/me/relationships/items?countryCode=$COUNTRY&include=items",
        )
        val byKey = HashMap<String, JSONObject>()
        pages.forEach { index(it, byKey) }
        pages.flatMap { page ->
            val refs = page.optJSONArray("data") ?: JSONArray()
            (0 until refs.length()).mapNotNull { i ->
                val id = refs.getJSONObject(i).optString("id")
                val pl = byKey["playlists:$id"] ?: return@mapNotNull null
                val a = pl.optJSONObject("attributes") ?: return@mapNotNull null
                Playlist(
                    id = id,
                    name = a.optString("name").ifBlank { "Sans titre" },
                    count = a.optInt("numberOfItems", 0),
                    coverUrl = coverFor(pl, byKey),
                )
            }
        }.distinctBy { it.id }
    }

    /** Une page de titres d'une playlist ; `next` = lien de la page suivante (null à la fin). */
    data class TrackPage(val tracks: List<Result>, val next: String?)

    suspend fun playlistPage(id: String, next: String? = null): TrackPage = withContext(Dispatchers.IO) {
        val base = "$API/playlists/$id/relationships/items?countryCode=$COUNTRY"
        val page = if (next != null) userGet(next) else try {
            userGet("$base&include=items,items.artists,items.albums,items.albums.coverArt")
        } catch (e: ApiException) {
            if (e.message?.contains("401") == true) throw e
            userGet("$base&include=items")
        }
        TrackPage(tracksFrom(page.optJSONArray("data") ?: JSONArray(), index(page)), nextLink(page))
    }

    /**
     * Les sons d'une playlist que Tidal ne peut plus lire (retirés du catalogue ou bloqués en France).
     * Ils n'apparaissent pas dans la liste normale : on les retrouve ici pour pouvoir les enlever.
     */
    suspend fun playlistUnavailable(id: String): List<Result> = withContext(Dispatchers.IO) {
        val out = ArrayList<Result>()
        var url: String? = "$API/playlists/$id/relationships/items?countryCode=$COUNTRY&include=items,items.artists"
        var pages = 0
        while (url != null && pages < 60) {
            val page = userGet(url)
            val byKey = index(page)
            val refs = page.optJSONArray("data") ?: JSONArray()
            for (i in 0 until refs.length()) {
                val ref = refs.getJSONObject(i)
                if (ref.optString("type") != "tracks") continue
                val tid = ref.optString("id")
                val itemId = ref.optJSONObject("meta")?.optString("itemId")?.ifBlank { null } ?: continue
                val track = byKey["tracks:$tid"]
                val a = track?.optJSONObject("attributes")
                val avail = a?.optJSONArray("availability")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                val dead = track == null || a == null || (avail != null && "STREAM" !in avail)
                if (!dead) continue
                val artists = relIds(track?.optJSONObject("relationships"), "artists").mapNotNull { byKey["artists:$it"]?.optJSONObject("attributes")?.optString("name") }
                out += Result(
                    id = tid,
                    title = a?.optString("title")?.takeIf { it.isNotBlank() } ?: "Son indisponible",
                    version = null, artists = artists, album = null, coverUrl = null,
                    durationMs = 0, explicit = false, itemId = itemId,
                )
            }
            url = nextLink(page)
            pages++
        }
        out
    }

    /** Suit la pagination (links.next) ; réessaie avec un include plus simple si Tidal refuse le premier. */
    private suspend fun userPages(url: String, fallback: String, maxPages: Int = 25): List<JSONObject> {
        val first = try {
            userGet(url)
        } catch (e: ApiException) {
            if (e.message?.contains("401") == true) throw e
            userGet(fallback)
        }
        val out = arrayListOf(first)
        var next = nextLink(first)
        while (next != null && out.size < maxPages) {
            kotlinx.coroutines.delay(350)
            val page = userGet(next)
            out += page
            next = nextLink(page)
        }
        return out
    }

    private fun nextLink(page: JSONObject): String? {
        val n = page.optJSONObject("links")?.optString("next")?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return when {
            n.startsWith("http") -> n
            n.startsWith("/v2/") -> "https://openapi.tidal.com$n"
            n.startsWith("/") -> "$API$n"
            else -> "$API/$n"
        }
    }

    private suspend fun userGet(url: String, retry: Boolean = true, attempt: Int = 0): JSONObject {
        val token = TidalAuth.accessToken()
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.api+json")
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        val code = c.responseCode
        if (code == 429 && attempt < 5) {
            // Tidal demande de ralentir : on attend (Retry-After si fourni) puis on réessaie
            val wait = c.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: (1000L shl attempt)
            kotlinx.coroutines.delay(wait.coerceIn(500, 15_000))
            return userGet(url, retry, attempt + 1)
        }
        if (code == 401 && retry) {
            TidalAuth.invalidate()
            return userGet(url, retry = false, attempt = attempt)
        }
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code == 429) throw ApiException("Tidal est saturé de demandes, réessaie dans une minute")
        if (code !in 200..299) throw ApiException("Tidal a répondu $code ${body.take(160)}")
        return JSONObject(body)
    }


    // ------------------------------------------------------------------ « Ma Musique » (favoris)

    /** Une page de tes titres favoris (les plus récents d'abord). */
    /** Sons similaires à un titre (relation « similarTracks » du catalogue). */
    suspend fun similarTracks(trackId: String): List<Result> = withContext(Dispatchers.IO) {
        val root = get("$API/tracks/$trackId/relationships/similarTracks?countryCode=$COUNTRY&include=similarTracks,similarTracks.artists,similarTracks.albums,similarTracks.albums.coverArt")
        tracksFrom(root.optJSONArray("data") ?: JSONArray(), index(root))
    }

    /** Sons populaires d'un artiste (sans la page complète). */
    suspend fun artistTopTracks(artistId: String): List<Result> = withContext(Dispatchers.IO) {
        val root = get("$API/artists/$artistId/relationships/tracks?countryCode=$COUNTRY&include=tracks,tracks.artists,tracks.albums,tracks.albums.coverArt")
        tracksFrom(root.optJSONArray("data") ?: JSONArray(), index(root))
    }

    /** Artistes proches (relation « similarArtists »). */
    /** Les artistes proches, avec nom et photo (pour « Les fans écoutent aussi »). */
    suspend fun similarArtistsFull(artistId: String, max: Int = 8): List<Artist> = withContext(Dispatchers.IO) {
        val ids = runCatching { similarArtists(artistId) }.getOrDefault(emptyList()).take(max)
        kotlinx.coroutines.coroutineScope {
            ids.map { id ->
                async {
                    runCatching {
                        val root = get("$API/artists/$id?countryCode=$COUNTRY&include=profileArt")
                        root.optJSONObject("data")?.let { artistFrom(it, index(root)) }
                    }.getOrNull()
                }
            }.mapNotNull { it.await() }.filter { it.name.isNotBlank() }
        }
    }

    /** Photo en grand : on demande une taille plus haute que la vignette. */
    fun bigPicture(url: String?): String? = url?.replace(Regex("/\\d+x\\d+\\.(jpg|png|webp)$"), "/750x750.$1")

    suspend fun similarArtists(artistId: String): List<String> = withContext(Dispatchers.IO) {
        val root = get("$API/artists/$artistId/relationships/similarArtists?countryCode=$COUNTRY")
        val d = root.optJSONArray("data") ?: JSONArray()
        (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("id")?.ifBlank { null } }
    }

    /**
     * Recommandations pour une playlist, variées à chaque fois :
     * sons proches de titres pris au hasard + tops d'artistes similaires, mélangés,
     * sans ce qui est déjà dans la playlist ni ce qu'on t'a déjà proposé ([exclude]).
     */
    suspend fun playlistRecommendations(tracks: List<Result>, count: Int = 10, exclude: Set<String> = emptySet()): List<Result> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()
        fun k(r: Result) = (r.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "") + "|" + (r.artists.firstOrNull() ?: "").lowercase())
        val have = tracks.map { it.id }.toHashSet()
        val haveK = tracks.map { k(it) }.toHashSet()
        val pool = java.util.Collections.synchronizedList(ArrayList<Result>())
        kotlinx.coroutines.coroutineScope {
            // 1) sons similaires à 8 titres pris au hasard
            tracks.shuffled().take(8).forEach { seed ->
                launch { runCatching { similarTracks(seed.id) }.getOrNull()?.let { pool += it } }
            }
            // 2) tops d'artistes proches de 3 artistes de la playlist
            val artistIds = tracks.flatMap { it.artistIds.take(1) }.distinct().shuffled().take(3)
            artistIds.forEach { aid ->
                launch {
                    val sim = runCatching { similarArtists(aid) }.getOrDefault(emptyList()).shuffled().take(3)
                    sim.forEach { s -> runCatching { artistTopTracks(s) }.getOrNull()?.let { pool += it.shuffled().take(4) } }
                }
            }
        }
        var cands = pool.toList().filter { it.id !in have && k(it) !in haveK }.distinctBy { k(it) }
        if (cands.size < count) {
            // repli : sons populaires des artistes de la playlist
            val ids = tracks.flatMap { it.artistIds.take(1) }.distinct().shuffled().take(4)
            ids.forEach { id -> runCatching { artistTopTracks(id) }.getOrNull()?.let { cands = cands + it.filter { r -> r.id !in have && k(r) !in haveK } } }
            cands = cands.distinctBy { k(it) }
        }
        val fresh = cands.filter { it.id !in exclude }
        val src = (if (fresh.size >= count / 2) fresh else cands).shuffled()
        // pas plus de 2 sons du même artiste
        val perArtist = HashMap<String, Int>()
        src.filter { r ->
            val a = (r.artists.firstOrNull() ?: "").lowercase()
            val n = perArtist.getOrDefault(a, 0)
            if (n >= 2) false else { perArtist[a] = n + 1; true }
        }.take(count)
    }

    suspend fun favoritesPage(next: String? = null): TrackPage = withContext(Dispatchers.IO) {
        val inc = "include=items,items.artists,items.albums,items.albums.coverArt"
        val page = if (next != null) userGet(next) else {
            val tries = listOf(
                "$API/userCollectionTracks/me/relationships/items?countryCode=$COUNTRY&$inc&sort=-addedAt",
                "$API/userCollectionTracks/me/relationships/items?countryCode=$COUNTRY&$inc",
                "$API/userCollections/me/relationships/tracks?countryCode=$COUNTRY&include=tracks,tracks.artists,tracks.albums,tracks.albums.coverArt",
            )
            var last: Exception? = null
            var ok: JSONObject? = null
            for (u in tries) {
                try { ok = userGet(u); break } catch (e: ApiException) {
                    if (e.message?.contains("401") == true) throw e
                    last = e
                }
            }
            ok ?: throw (last ?: ApiException("Favoris introuvables"))
        }
        TrackPage(tracksFrom(page.optJSONArray("data") ?: JSONArray(), index(page)), nextLink(page))
    }

    // ------------------------------------------------------------------ modifier tes playlists

    /** Crée une playlist (privée, accessible par lien) et renvoie-la. */
    suspend fun createPlaylist(name: String, description: String? = null): Playlist = withContext(Dispatchers.IO) {
        val attrs = JSONObject().put("name", name).put("accessType", "UNLISTED")
        if (!description.isNullOrBlank()) attrs.put("description", description)
        val body = JSONObject().put("data", JSONObject().put("type", "playlists").put("attributes", attrs))
        val res = userSend("POST", "$API/playlists?countryCode=$COUNTRY", body)
        val id = res?.optJSONObject("data")?.optString("id")?.ifBlank { null } ?: throw ApiException("Tidal n'a pas créé la playlist")
        Playlist(id, name, 0, null)
    }

    suspend fun renamePlaylist(id: String, name: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put(
            "data",
            JSONObject().put("type", "playlists").put("id", id).put("attributes", JSONObject().put("name", name)),
        )
        userSend("PATCH", "$API/playlists/$id", body)
        Unit
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        userSend("DELETE", "$API/playlists/$id", null)
        Unit
    }

    /** Les sons déjà dans chaque playlist (gardé 10 min, mis à jour à chaque ajout / retrait). */
    private val playlistIds = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, MutableSet<String>>>()

    private suspend fun playlistTrackIds(id: String): MutableSet<String> {
        playlistIds[id]?.let { (at, ids) -> if (System.currentTimeMillis() - at < 10 * 60_000) return ids }
        val ids = java.util.Collections.synchronizedSet(HashSet<String>())
        var url: String? = "$API/playlists/$id/relationships/items?countryCode=$COUNTRY"
        var pages = 0
        while (url != null && pages < 250) {
            val page = userGet(url)
            val refs = page.optJSONArray("data") ?: JSONArray()
            for (i in 0 until refs.length()) refs.optJSONObject(i)?.let { if (it.optString("type") == "tracks") ids.add(it.optString("id")) }
            url = nextLink(page); pages++
        }
        playlistIds[id] = System.currentTimeMillis() to ids
        return ids
    }

    /**
     * Ajoute des sons à une playlist, sans jamais mettre deux fois le même :
     * ceux qui y sont déjà sont ignorés (et on le dit).
     */
    suspend fun addToPlaylist(id: String, trackIds: List<String>) = withContext(Dispatchers.IO) {
        val wanted = trackIds.distinct()
        val existing = if (Features.flags.value.noDuplicates) runCatching { playlistTrackIds(id) }.getOrNull() else null
        val toAdd = if (existing == null) wanted else wanted.filter { it !in existing }
        val skipped = wanted.size - toAdd.size
        if (toAdd.isEmpty()) {
            TidalRemote.showToast(if (wanted.size == 1) "Déjà dans cette playlist" else "Ces sons sont déjà dans cette playlist")
            return@withContext
        }
        toAdd.chunked(20).forEach { chunk ->
            val data = JSONArray().apply { chunk.forEach { put(JSONObject().put("id", it).put("type", "tracks")) } }
            userSend("POST", "$API/playlists/$id/relationships/items?countryCode=$COUNTRY", JSONObject().put("data", data))
        }
        existing?.addAll(toAdd)
        if (skipped > 0) TidalRemote.showToast("$skipped déjà dans la playlist, ignoré${if (skipped > 1) "s" else ""}")
    }

    suspend fun removeFromPlaylist(id: String, tracks: List<Result>) = withContext(Dispatchers.IO) {
        playlistIds.remove(id)
        val data = JSONArray()
        tracks.forEach { r ->
            val item = r.itemId ?: return@forEach
            data.put(JSONObject().put("id", r.id).put("type", "tracks").put("meta", JSONObject().put("itemId", item)))
        }
        if (data.length() == 0) throw ApiException("Ce son ne peut pas être retiré (identifiant manquant)")
        userSend("DELETE", "$API/playlists/$id/relationships/items", JSONObject().put("data", data))
        Unit
    }

    /** Requête d'écriture sur ton compte (POST / PATCH / DELETE). */
    private suspend fun userSend(method: String, url: String, body: JSONObject?, attempt: Int = 0, retry: Boolean = true): JSONObject? {
        val token = TidalAuth.accessToken()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.api+json")
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/vnd.api+json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        if (code == 429 && attempt < 4) {
            val wait = c.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: (1000L shl attempt)
            kotlinx.coroutines.delay(wait.coerceIn(500, 10_000))
            return userSend(method, url, body, attempt + 1, retry)
        }
        if (code == 401 && retry) {
            TidalAuth.invalidate()
            return userSend(method, url, body, attempt, retry = false)
        }
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code == 403) throw ApiException("Tidal refuse la modification : reconnecte ton compte (nouveaux droits) ou ce n'est pas ta playlist")
        if (code !in 200..299) throw ApiException("Tidal a répondu $code ${text.take(160)}")
        return if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
    }

    // ------------------------------------------------------------------ nouveautés

    /** Albums récents (moins de `days` jours) d'un artiste. */
    suspend fun recentReleases(artistName: String, days: Int = 120): List<Album> = withContext(Dispatchers.IO) {
        val artist = findArtist(artistName) ?: return@withContext emptyList()
        if (n(artist.name) != n(artistName)) return@withContext emptyList()
        val base = "$API/artists/${artist.id}/relationships/albums?countryCode=$COUNTRY&include=albums,albums.coverArt,albums.artists"
        val all = ArrayList<Album>()
        var url: String? = base
        var pages = 0
        while (url != null && pages < 4) {
            val root = get(url)
            val byKey = index(root)
            val refs = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until refs.length()) {
                byKey["albums:${refs.getJSONObject(i).optString("id")}"]?.let { albumFrom(it, byKey) }?.let { all += it }
            }
            url = nextLink(root)
            pages++
        }
        val limit = java.time.LocalDate.now().minusDays(days.toLong()).toString()
        val today = java.time.LocalDate.now().plusDays(1).toString()
        all.filter { (it.releaseDate ?: "") in limit..today }
            .map { if (it.artist == null) it.copy(artist = artist.name) else it }
            .distinctBy { n(it.title) }
    }

    /** Albums annoncés d'un artiste (date de sortie dans le futur, jusqu'à un an), si Tidal les a déjà. */
    suspend fun upcomingReleases(artistName: String): List<Album> = withContext(Dispatchers.IO) {
        val artist = findArtist(artistName) ?: return@withContext emptyList()
        if (n(artist.name) != n(artistName)) return@withContext emptyList()
        val base = "$API/artists/${artist.id}/relationships/albums?countryCode=$COUNTRY&include=albums,albums.coverArt,albums.artists"
        val all = ArrayList<Album>()
        var url: String? = base
        var pages = 0
        while (url != null && pages < 4) {
            val root = get(url)
            val byKey = index(root)
            val refs = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until refs.length()) {
                byKey["albums:${refs.getJSONObject(i).optString("id")}"]?.let { albumFrom(it, byKey) }?.let { all += it }
            }
            url = nextLink(root)
            pages++
        }
        val today = java.time.LocalDate.now().toString()
        val max = java.time.LocalDate.now().plusDays(365).toString()
        all.filter { (it.releaseDate ?: "") > today && (it.releaseDate ?: "") <= max }
            .map { if (it.artist == null) it.copy(artist = artist.name) else it }
            .distinctBy { n(it.title) }
    }

    // ------------------------------------------------------------------ parsing JSON:API

    private fun index(root: JSONObject, into: HashMap<String, JSONObject> = HashMap()): HashMap<String, JSONObject> {
        val included = root.optJSONArray("included") ?: JSONArray()
        for (i in 0 until included.length()) {
            val o = included.getJSONObject(i)
            into["${o.optString("type")}:${o.optString("id")}"] = o
        }
        return into
    }

    private fun parse(root: JSONObject): List<Result> {
        val byKey = index(root)

        // data = tableau (nouveau format) ou objet (ancien) ; les titres sont dans relationships.tracks.data
        val data = root.opt("data")
        val first = when (data) {
            is JSONArray -> if (data.length() > 0) data.getJSONObject(0) else null
            is JSONObject -> data
            else -> null
        } ?: return emptyList()

        val refs = first.optJSONObject("relationships")?.optJSONObject("tracks")?.optJSONArray("data") ?: return emptyList()
        return tracksFrom(refs, byKey)
    }

    private fun tracksFrom(refs: JSONArray, byKey: Map<String, JSONObject>): List<Result> {
        val out = ArrayList<Result>()
        for (i in 0 until refs.length()) {
            val ref = refs.getJSONObject(i)
            val id = ref.optString("id")
            val track = byKey["tracks:$id"] ?: continue
            val a = track.optJSONObject("attributes") ?: continue
            val rel = track.optJSONObject("relationships")

            val artists = relIds(rel, "artists").mapNotNull { byKey["artists:$it"]?.optJSONObject("attributes")?.optString("name") }
            val albumObj = relIds(rel, "albums").firstNotNullOfOrNull { byKey["albums:$it"] }
            val albumAttrs = albumObj?.optJSONObject("attributes")

            out += Result(
                id = id,
                title = a.optString("title"),
                version = a.optString("version").takeIf { it.isNotBlank() && it != "null" },
                artists = artists,
                album = albumAttrs?.optString("title")?.takeIf { it.isNotBlank() },
                coverUrl = coverFor(albumObj, byKey),
                durationMs = isoDurationMs(a.optString("duration")),
                explicit = a.optBoolean("explicit"),
                mediaTags = a.optJSONArray("mediaTags")?.let { t -> (0 until t.length()).map { i -> t.optString(i) } } ?: emptyList(),
                artistIds = relIds(rel, "artists"),
                albumId = relIds(rel, "albums").firstOrNull(),
                itemId = ref.optJSONObject("meta")?.optString("itemId")?.ifBlank { null },
                bpm = a.optDouble("bpm", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                keyScale = a.optString("keyScale").takeIf { it.isNotBlank() && it != "null" && it != "UNKNOWN" },
                addedAt = ref.optJSONObject("meta")?.optString("addedAt")?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
        return out
    }

    private fun relIds(rel: JSONObject?, name: String): List<String> {
        val arr = rel?.optJSONObject(name)?.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
    }

    /** Pochette : via la relation coverArt → artworks.files, sinon l'ancien champ imageLinks. */
    private fun coverFor(album: JSONObject?, byKey: Map<String, JSONObject>, rel: String = "coverArt"): String? {
        if (album == null) return null
        val artId = relIds(album.optJSONObject("relationships"), rel).firstOrNull()
        val files = artId?.let { byKey["artworks:$it"]?.optJSONObject("attributes")?.optJSONArray("files") }
            ?: album.optJSONObject("attributes")?.optJSONArray("imageLinks")
            ?: return null
        var best: String? = null
        var bestDiff = Int.MAX_VALUE
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val w = f.optJSONObject("meta")?.optInt("width") ?: 0
            val diff = kotlin.math.abs(w - 320)
            if (diff < bestDiff) {
                bestDiff = diff
                best = f.optString("href")
            }
        }
        return best
    }

    private fun isoDurationMs(s: String): Long {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(s) ?: return 0
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLongOrNull() ?: 0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return (h * 3600 + min * 60) * 1000 + (sec * 1000).toLong()
    }

    // ------------------------------------------------------------------ HTTP

    private fun get(url: String, retry: Boolean = true, attempt: Int = 0): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer ${accessToken()}")
        c.setRequestProperty("Accept", "application/vnd.api+json")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        val code = c.responseCode
        if (code == 429 && attempt < 3) {
            // Tidal demande de ralentir : petite pause puis on réessaie
            val wait = c.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: (800L shl attempt)
            Thread.sleep(wait.coerceIn(400, 8000))
            return get(url, retry, attempt + 1)
        }
        if (code == 401 && retry) {
            token = null
            return get(url, retry = false, attempt = attempt)
        }
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw ApiException("Tidal a répondu $code ${body.take(160)}")
        return JSONObject(body)
    }

    @Synchronized
    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpiresAt) return it }

        val basic = Base64.encodeToString(
            "${BuildConfig.TIDAL_CLIENT_ID}:${BuildConfig.TIDAL_CLIENT_SECRET}".toByteArray(),
            Base64.NO_WRAP,
        )
        val c = URL(AUTH_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Basic $basic")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }

        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw ApiException("Connexion à l'API refusée ($code) — vérifie l'ID et le secret")

        val j = JSONObject(body)
        val t = j.getString("access_token")
        token = t
        tokenExpiresAt = now + (j.optLong("expires_in", 3600) - 60) * 1000
        return t
    }
}
