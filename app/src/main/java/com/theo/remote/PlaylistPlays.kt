package com.theo.remote

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Tes lancements de playlists : combien de fois et quand.
 * Sert au panneau latéral (les plus jouées), au widget playlists et aux raccourcis de l'icône (les dernières).
 */
object PlaylistPlays {
    data class Entry(val id: String, val name: String, val cover: String?, val count: Int, val lastAt: Long = 0)

    private val _list = MutableStateFlow<List<Entry>>(emptyList())
    val list: StateFlow<List<Entry>> = _list
    /** toutes tes playlists Tidal vues la dernière fois (pour le widget quand l'appli est fermée) */
    private val _known = MutableStateFlow<List<Entry>>(emptyList())
    val known: StateFlow<List<Entry>> = _known
    private var prefs: android.content.SharedPreferences? = null
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shortcutJob: Job? = null

    const val ACTION_PLAY = "com.theo.remote.PLAY_PLAYLIST"
    const val EXTRA_ID = "hush.plId"
    const val EXTRA_NAME = "hush.plName"

    fun init(context: Context) {
        if (prefs != null) return
        app = context.applicationContext
        val p = context.applicationContext.getSharedPreferences("hush_pl_plays", Context.MODE_PRIVATE)
        prefs = p
        _list.value = decode(p.getString("list", "[]"))
        _known.value = decode(p.getString("known", "[]"))
    }

    private fun decode(s: String?): List<Entry> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Entry(it.getString("i"), it.getString("n"), it.optString("c").ifBlank { null }, it.optInt("k"), it.optLong("t")) } }
    }.getOrDefault(emptyList())

    private fun encode(l: List<Entry>): String {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("c", it.cover ?: "").put("k", it.count).put("t", it.lastAt)) }
        return a.toString()
    }

    fun bump(id: String, name: String, cover: String?) {
        if (id.startsWith("__")) return
        val old = _list.value.firstOrNull { it.id == id }
        _list.value = _list.value.filter { it.id != id } + Entry(id, name, cover ?: old?.cover, (old?.count ?: 0) + 1, System.currentTimeMillis())
        prefs?.edit()?.putString("list", encode(_list.value))?.apply()
        refreshShortcuts()
    }

    /** Appelé quand la liste de tes playlists Tidal est chargée. */
    fun remember(all: List<Triple<String, String, String?>>) {
        val l = all.filter { !it.first.startsWith("__") }.map { Entry(it.first, it.second, it.third, 0) }
        if (l.map { it.id to it.cover } == _known.value.map { it.id to it.cover }) return
        _known.value = l
        prefs?.edit()?.putString("known", encode(l))?.apply()
        refreshShortcuts()
    }

    fun top(n: Int) = _list.value.sortedByDescending { it.count }.take(n)

    /** Les dernières lancées, complétées par tes playlists si tu en as lancé moins de [n]. */
    fun recent(n: Int): List<Entry> {
        val r = _list.value.sortedByDescending { it.lastAt }.take(n)
        return (r + _known.value.filter { k -> r.none { it.id == k.id } }).take(n)
    }

    /** Pour le widget : les plus jouées, puis le reste de tes playlists. */
    fun forWidget(n: Int): List<Entry> {
        val t = top(n)
        return (t + _known.value.filter { k -> t.none { it.id == k.id } }).take(n)
    }

    // ---------------------------------------------------------------- raccourcis de l'icône

    fun refreshShortcuts() {
        val ctx = app ?: return
        shortcutJob?.cancel()
        shortcutJob = scope.launch {
            delay(800)
            if (!Features.flags.value.iconShortcuts) {
                runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(ctx) }
                return@launch
            }
            val max = runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx) }.getOrDefault(4).coerceAtMost(4)
            val list = recent(max).mapIndexed { i, e ->
                val intent = Intent(ctx, MainActivity::class.java).setAction(ACTION_PLAY)
                    .putExtra(EXTRA_ID, e.id).putExtra(EXTRA_NAME, e.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val bmp = coverBitmap(e.id, e.cover, 192)
                ShortcutInfoCompat.Builder(ctx, "pl_${e.id}")
                    .setShortLabel(e.name.take(24))
                    .setLongLabel(e.name)
                    .setIcon(bmp?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .setRank(i)
                    .build()
            }
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(ctx, list) }
        }
    }

    /** Pochette en image : ta photo perso, sinon celle de Tidal (téléchargée), en carré. */
    fun coverBitmap(id: String, cover: String?, size: Int): Bitmap? = runCatching {
        val art = PlaylistCovers.art(id, cover)
        val raw: Bitmap? = when (art) {
            is File -> BitmapFactory.decodeFile(art.absolutePath)
            is String -> URL(art).openStream().use { BitmapFactory.decodeStream(it) }
            else -> cover?.let { u -> URL(u).openStream().use { BitmapFactory.decodeStream(it) } }
        }
        raw?.let { b ->
            val side = minOf(b.width, b.height)
            val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(sq, size, size, true)
        }
    }.getOrNull()
}
