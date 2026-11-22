package com.theo.remote.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.theo.remote.TidalApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Où est ce son ? » : dans quelles de tes playlists un son est déjà rangé.
 * Le contenu de chaque playlist est gardé sur le téléphone et relu seulement si son nombre de titres a changé.
 */
object WhereIsIt {
    data class Target(val title: String, val artist: String, val id: String? = null)
    val target = MutableStateFlow<Target?>(null)

    fun open(title: String, artist: String, id: String? = null) { target.value = Target(title, artist, id) }

    private class Index(val count: Int, val keys: Set<String>, val ids: Set<String>)
    private val mem = HashMap<String, Index>()
    private var loadedDisk = false

    private fun norm(t: String) = t.lowercase().substringBefore(" (").substringBefore(" [").substringBefore(" - ").replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun key(title: String, artist: String) = norm(title) + "|" + norm(mainArtist(artist))

    private fun file(ctx: Context) = File(ctx.filesDir, "playlist_index.json")

    private fun loadDisk(ctx: Context) {
        if (loadedDisk) return
        loadedDisk = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            o.keys().forEach { id ->
                val e = o.getJSONObject(id)
                val k = e.getJSONArray("k"); val i = e.getJSONArray("i")
                mem[id] = Index(e.getInt("n"), (0 until k.length()).map { k.getString(it) }.toSet(), (0 until i.length()).map { i.getString(it) }.toSet())
            }
        }
    }

    private fun saveDisk(ctx: Context) = runCatching {
        val o = JSONObject()
        mem.forEach { (id, x) -> o.put(id, JSONObject().put("n", x.count).put("k", JSONArray(x.keys.toList())).put("i", JSONArray(x.ids.toList()))) }
        file(ctx).writeText(o.toString())
    }

    private suspend fun allTracks(id: String): List<TidalApi.Result> {
        val out = ArrayList<TidalApi.Result>()
        var next: String? = null
        var first = true
        while (first || next != null) {
            val page = TidalApi.playlistPage(id, next)
            out += page.tracks
            next = page.next
            first = false
            if (out.size > 3000) break
        }
        return out
    }

    /** Cherche le son dans toutes tes playlists. [progress] (faites, total). */
    suspend fun find(ctx: Context, t: Target, progress: (Int, Int) -> Unit): List<TidalApi.Playlist> = withContext(Dispatchers.IO) {
        loadDisk(ctx)
        PlaylistStore.load()
        val pls = PlaylistStore.list.value.orEmpty()
        val k = key(t.title, t.artist)
        var done = 0
        val sem = Semaphore(4)
        val found = coroutineScope {
            pls.map { p ->
                async {
                    sem.withPermit {
                        val idx = mem[p.id]?.takeIf { it.count == p.count } ?: runCatching {
                            val tr = allTracks(p.id)
                            Index(p.count, tr.map { key(it.title, it.artistLine) }.toSet(), tr.map { it.id }.toSet()).also { synchronized(mem) { mem[p.id] = it } }
                        }.getOrNull()
                        synchronized(this@WhereIsIt) { done++; progress(done, pls.size) }
                        if (idx != null && (k in idx.keys || (t.id != null && t.id in idx.ids))) p else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // les playlists supprimées sortent de l'index
        synchronized(mem) { mem.keys.retainAll(pls.map { it.id }.toSet()); saveDisk(ctx) }
        found
    }
}

@Composable
fun WhereIsItDialog() {
    val t by WhereIsIt.target.collectAsState()
    val target = t ?: return
    val ctx = LocalContext.current
    var done by remember(target) { mutableIntStateOf(0) }
    var total by remember(target) { mutableIntStateOf(0) }
    var result by remember(target) { mutableStateOf<List<TidalApi.Playlist>?>(null) }
    var error by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) {
        runCatching { WhereIsIt.find(ctx, target) { d, n -> done = d; total = n } }
            .onSuccess { result = it }.onFailure { error = true }
    }
    fun close() { WhereIsIt.target.value = null }
    Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier.fillMaxWidth().clip(Rounded(18)).background(Surface2).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Où est ce son ?", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(target.title, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(target.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val r = result
            when {
                error -> Text("Impossible de lire tes playlists (connexion à Tidal ?)", color = Ko, fontSize = 14.sp)
                r == null -> {
                    Text(if (total > 0) "Je regarde dans tes playlists… $done/$total" else "Je regarde dans tes playlists…", color = Txt, fontSize = 15.sp)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.10f))) {
                        if (total > 0) Box(Modifier.fillMaxWidth(done.toFloat() / total).height(4.dp).background(Txt))
                    }
                    Text("La première fois peut prendre un peu de temps, ensuite c'est instantané.", color = Txt3, fontSize = 12.sp)
                }
                r.isEmpty() -> {
                    Text("Dans aucune de tes playlists", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    PillButton("Ajouter à une playlist") { close(); PlaylistEdit.pickCurrent(target.title, target.artist) }
                }
                else -> {
                    Text("Dans ${r.size} playlist${if (r.size > 1) "s" else ""}", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) { r.forEach { p ->
                        val art = rememberPlaylistArt(p)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).bounceClick(pressedScale = 0.97f) { close(); NavEvents.openPlaylist(p) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (art != null) Cover(model = art, radius = 6.dp, modifier = Modifier.size(44.dp))
                            else Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.count} titre${if (p.count > 1) "s" else ""}", color = Txt2, fontSize = 12.sp)
                            }
                        }
                    } }
                }
            }
            PillButton("Fermer", filled = false) { close() }
        }
    }
}
