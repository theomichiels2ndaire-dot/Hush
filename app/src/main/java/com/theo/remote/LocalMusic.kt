package com.theo.remote

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tes fichiers audio du téléphone, et tes playlists « locales » (rangées dans Hush,
 * puisque Tidal ne peut pas stocker de fichiers locaux dans ses playlists).
 */
object LocalMusic {
    data class Track(
        val id: String,
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        /** Pochette extraite du fichier (chemin d'une image), si le fichier en a une. */
        val art: String?,
    )

    data class Playlist(val id: String, val name: String, val ids: List<String>)

    /** Une ligne de playlist Hush : un fichier local ou un son Tidal (playlists mixtes). */
    sealed class Entry {
        abstract val key: String
        data class L(val t: Track) : Entry() { override val key get() = t.id }
        data class T(val r: TidalApi.Result) : Entry() { override val key get() = TIDAL + r.id }
    }
    const val TIDAL = "t:"

    /** Tes fichiers locaux ajoutés à des playlists Tidal (id de playlist Tidal → sons locaux). Visibles dans Hush. */
    private val _attached = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val attached: StateFlow<Map<String, List<String>>> = _attached

    fun attachedTo(tidalPlaylistId: String): List<Track> = _attached.value[tidalPlaylistId].orEmpty().mapNotNull { byId(it) }

    fun attach(tidalPlaylistIds: Collection<String>, ids: List<String>) {
        var m = _attached.value
        tidalPlaylistIds.forEach { pid -> m = m + (pid to (m[pid].orEmpty() + ids).distinct()) }
        _attached.value = m
        save()
    }

    fun detach(tidalPlaylistId: String, id: String) {
        _attached.value = _attached.value + (tidalPlaylistId to _attached.value[tidalPlaylistId].orEmpty().minus(id))
        save()
    }

    /** Sons Tidal rangés dans des playlists Hush (id → infos). */
    private val tidalItems = HashMap<String, TidalApi.Result>()

    /** Écoutes locales, comptées à part des stats Tidal. */
    data class Stat(val plays: Int, val ms: Long)
    private val _stats = MutableStateFlow<Map<String, Stat>>(emptyMap())
    val stats: StateFlow<Map<String, Stat>> = _stats

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists
    private var file: File? = null
    private var artDir: File? = null

    fun init(context: Context) {
        if (file != null) return
        val ctx = context.applicationContext
        file = File(ctx.filesDir, "local_music.json")
        artDir = File(ctx.filesDir, "local_art").apply { mkdirs() }
        runCatching {
            val o = JSONObject(file!!.readText())
            val t = o.optJSONArray("tracks") ?: JSONArray()
            _tracks.value = (0 until t.length()).map { i ->
                val j = t.getJSONObject(i)
                Track(j.getString("id"), j.getString("uri"), j.optString("title"), j.optString("artist"), j.optString("album"),
                    j.optLong("dur"), j.optString("art").ifBlank { null })
            }
            val p = o.optJSONArray("playlists") ?: JSONArray()
            _playlists.value = (0 until p.length()).map { i ->
                val j = p.getJSONObject(i)
                val ids = j.optJSONArray("ids") ?: JSONArray()
                Playlist(j.getString("id"), j.optString("name"), (0 until ids.length()).map { ids.getString(it) })
            }
            History.decodeResults(o.optString("tidal").ifBlank { null }).forEach { tidalItems[it.id] = it }
            val at = o.optJSONObject("attached")
            if (at != null) _attached.value = at.keys().asSequence().associateWith { k ->
                val a = at.getJSONArray(k); (0 until a.length()).map { a.getString(it) }
            }
            val st = o.optJSONObject("stats")
            if (st != null) _stats.value = st.keys().asSequence().associateWith { k ->
                val a = st.getJSONArray(k); Stat(a.getInt(0), a.getLong(1))
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        o.put("tracks", JSONArray().apply {
            _tracks.value.forEach { t ->
                put(JSONObject().put("id", t.id).put("uri", t.uri).put("title", t.title).put("artist", t.artist)
                    .put("album", t.album).put("dur", t.durationMs).put("art", t.art ?: ""))
            }
        })
        o.put("playlists", JSONArray().apply {
            _playlists.value.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("ids", JSONArray(p.ids))) }
        })
        // on ne garde que les sons Tidal encore utilisés
        val used = _playlists.value.flatMap { it.ids }.filter { it.startsWith(TIDAL) }.map { it.removePrefix(TIDAL) }.toSet()
        o.put("tidal", History.encodeResults(used.mapNotNull { tidalItems[it] }))
        o.put("attached", JSONObject().apply { _attached.value.forEach { (k, v) -> if (v.isNotEmpty()) put(k, JSONArray(v)) } })
        o.put("stats", JSONObject().apply { _stats.value.forEach { (k, v) -> put(k, JSONArray().put(v.plays).put(v.ms)) } })
        runCatching { f.writeText(o.toString()) }
    }

    fun entriesOf(p: Playlist): List<Entry> = p.ids.mapNotNull { id ->
        if (id.startsWith(TIDAL)) tidalItems[id.removePrefix(TIDAL)]?.let { Entry.T(it) } else byId(id)?.let { Entry.L(it) }
    }

    /** Ajoute des sons Tidal dans des playlists Hush. */
    fun addTidal(playlistIds: Collection<String>, results: List<TidalApi.Result>) {
        results.forEach { tidalItems[it.id] = it }
        addTo(playlistIds, results.map { TIDAL + it.id })
    }

    /** Corrige le titre / l'artiste d'un son local. */
    fun edit(id: String, title: String, artist: String) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(title = title.ifBlank { it.title }, artist = artist.ifBlank { it.artist }) else it }
        save()
    }

    /** Une écoute locale (comptée quand tu as écouté au moins 30 s). */
    fun countPlay(id: String, ms: Long) {
        val cur = _stats.value[id] ?: Stat(0, 0)
        _stats.value = _stats.value + (id to Stat(cur.plays + 1, cur.ms + ms))
        save()
    }

    private var artTried = false

    /** Les sons sans pochette : on la cherche sur Tidal (une fois par lancement). */
    suspend fun fetchMissingArt() = withContext(Dispatchers.IO) {
        if (artTried || !Features.flags.value.autoLocalArt) return@withContext
        artTried = true
        fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val missing = _tracks.value.filter { it.art == null }.take(60)
        for (t in missing) {
            val res = runCatching { TidalApi.search("${t.title} ${t.artist}".take(80)) }.getOrNull().orEmpty().filter { it.coverUrl != null }
            // on prend le bon titre en priorité (sinon le premier résultat)
            val found = res.firstOrNull { n(it.title) == n(t.title) } ?: res.firstOrNull { n(t.title).contains(n(it.title)) && n(it.title).length > 3 } ?: res.firstOrNull()
            val url = found?.coverUrl ?: continue
            val path = runCatching {
                val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                saveArt(t.id, bytes)
            }.getOrNull() ?: continue
            _tracks.value = _tracks.value.map { if (it.id == t.id) it.copy(art = path) else it }
        }
        save()
    }

    fun byId(id: String) = _tracks.value.firstOrNull { it.id == id }
    fun tracksOf(p: Playlist) = p.ids.mapNotNull { byId(it) }

    // ---------------------------------------------------------------- import

    /** Sons choisis un par un. Renvoie le nombre de nouveaux sons. */
    suspend fun importFiles(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        uris.forEach { u -> runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        add(ctx, uris)
    }

    /** Un dossier entier (et ses sous-dossiers). */
    suspend fun importFolder(context: Context, tree: Uri): Int = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val found = ArrayList<Uri>()
        fun walk(docId: String, depth: Int) {
            if (depth > 5 || found.size > 3000) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val mime = c.getString(1) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(id, depth + 1)
                        mime.startsWith("audio/") || mime == "application/ogg" -> found += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        runCatching { walk(DocumentsContract.getTreeDocumentId(tree), 0) }
        add(ctx, found)
    }

    private fun add(ctx: Context, uris: List<Uri>): Int {
        val known = _tracks.value.map { it.uri }.toHashSet()
        val fresh = uris.distinct().filter { it.toString() !in known }.mapNotNull { read(ctx, it) }
        if (fresh.isEmpty()) return 0
        _tracks.value = _tracks.value + fresh
        save()
        return fresh.size
    }

    private fun read(ctx: Context, uri: Uri): Track? = runCatching {
        val id = Integer.toHexString(uri.toString().hashCode()) + uri.toString().length
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "Son local"
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null }
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()?.ifBlank { null }
            val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim() ?: ""
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val art = r.embeddedPicture?.let { saveArt(id, it) }
            // pas de tags ? on devine « Artiste - Titre » depuis le nom du fichier
            val (t, a) = if (title != null) title to (artist ?: "Artiste inconnu")
            else if (name.contains(" - ")) name.substringAfter(" - ").trim() to (artist ?: name.substringBefore(" - ").trim())
            else name to (artist ?: "Artiste inconnu")
            Track(id, uri.toString(), t, a, album, dur, art)
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    private fun saveArt(id: String, bytes: ByteArray): String? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 500) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val f = File(artDir, "$id.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        f.absolutePath
    }.getOrNull()

    fun remove(ids: Collection<String>) {
        val set = ids.toSet()
        _tracks.value.filter { it.id in set }.forEach { t -> t.art?.let { runCatching { File(it).delete() } } }
        _tracks.value = _tracks.value.filterNot { it.id in set }
        _playlists.value = _playlists.value.map { p -> p.copy(ids = p.ids.filterNot { it in set }) }
        _attached.value = _attached.value.mapValues { (_, v) -> v.filterNot { it in set } }
        save()
    }

    // ---------------------------------------------------------------- playlists locales

    fun createPlaylist(name: String, ids: List<String> = emptyList()): Playlist {
        val p = Playlist("lp" + System.currentTimeMillis(), name, ids.distinct())
        _playlists.value = _playlists.value + p
        save()
        return p
    }

    /** Ajoute des sons dans une ou plusieurs playlists d'un coup (sans doublon). */
    fun addTo(playlistIds: Collection<String>, ids: List<String>) {
        val set = playlistIds.toSet()
        _playlists.value = _playlists.value.map { p -> if (p.id in set) p.copy(ids = (p.ids + ids).distinct()) else p }
        save()
    }

    fun removeFrom(playlistId: String, id: String) {
        _playlists.value = _playlists.value.map { p -> if (p.id == playlistId) p.copy(ids = p.ids - id) else p }
        save()
    }

    fun rename(playlistId: String, name: String) {
        _playlists.value = _playlists.value.map { p -> if (p.id == playlistId) p.copy(name = name) else p }
        save()
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        save()
    }
}
