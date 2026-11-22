package com.theo.remote

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Pochettes vidéo des albums (si Tidal en a), gardées en mémoire pour ne pas redemander. */
object VideoCovers {
    private val cache = ConcurrentHashMap<String, String>() // clé album → url ("" = pas de vidéo)
    /** dernier résultat, affiché dans le Diagnostic */
    val lastProbe = MutableStateFlow<String?>(null)

    suspend fun get(title: String, artist: String, album: String): String? {
        if (album.isBlank()) return null
        val main = artist.split(",", "&", " feat").first().trim()
        val key = album.lowercase() + "|" + main.lowercase()
        cache[key]?.let { return it.ifEmpty { null } }
        val al = runCatching { TidalApi.findAlbum(album, main) }.getOrNull()
        if (al == null) { cache[key] = ""; lastProbe.value = "« $album » : album introuvable sur Tidal"; return null }
        val (url, info) = runCatching { TidalApi.videoCover(al.id) }.getOrElse { null to "erreur : ${it.message}" }
        cache[key] = url.orEmpty()
        lastProbe.value = "« ${al.title} » : $info"
        TidalRemote.note("Pochette vidéo · « ${al.title} » : $info")
        return url
    }
}
