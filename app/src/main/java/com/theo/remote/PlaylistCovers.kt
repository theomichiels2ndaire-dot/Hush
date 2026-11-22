package com.theo.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pochettes de playlists choisies dans Hush (visibles seulement dans Hush) :
 * une photo de la galerie, ou la mosaïque des sons à la place de la pochette Tidal.
 */
object PlaylistCovers {
    const val MOSAIC = "mosaic"
    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    /** id de playlist → chemin du fichier, ou [MOSAIC] */
    val map: StateFlow<Map<String, String>> = _map
    /** playlist dont on choisit la pochette (id, nom) */
    val editing = MutableStateFlow<Pair<String, String>?>(null)
    private var prefs: android.content.SharedPreferences? = null
    private var dir: File? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush_pl_covers", Context.MODE_PRIVATE)
        prefs = p
        dir = File(context.applicationContext.filesDir, "covers").apply { mkdirs() }
        _map.value = p.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .filter { (_, v) -> v == MOSAIC || File(v).exists() }.toMap()
    }

    /** Ce qu'il faut afficher : un fichier, null (= mosaïque), ou la pochette Tidal. */
    fun art(id: String, tidalCover: String?): Any? {
        if (!Features.flags.value.playlistCovers) return tidalCover
        val v = _map.value[id] ?: return tidalCover
        return if (v == MOSAIC) null else File(v)
    }

    fun has(id: String) = _map.value.containsKey(id)

    fun setMosaic(id: String) { clearFile(id); put(id, MOSAIC) }

    fun reset(id: String) {
        clearFile(id)
        _map.value = _map.value - id
        prefs?.edit()?.remove(id)?.apply()
    }

    /** Copie la photo choisie (réduite à 1200 px) dans les fichiers de Hush. */
    suspend fun setPhoto(context: Context, id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@runCatching false
            // recadrage carré au centre
            val side = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = if (side > 1200) Bitmap.createScaledBitmap(sq, 1200, 1200, true) else sq
            val d = dir ?: File(context.filesDir, "covers").apply { mkdirs() }
            // nom unique : l'image se rafraîchit tout de suite (pas de vieux cache)
            val f = File(d, "${id.hashCode()}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            withContext(Dispatchers.Main) { clearFile(id); put(id, f.absolutePath) }
            true
        }.getOrDefault(false)
    }

    private fun put(id: String, v: String) {
        _map.value = _map.value + (id to v)
        prefs?.edit()?.putString(id, v)?.apply()
    }

    private fun clearFile(id: String) {
        _map.value[id]?.takeIf { it != MOSAIC }?.let { runCatching { File(it).delete() } }
    }
}
