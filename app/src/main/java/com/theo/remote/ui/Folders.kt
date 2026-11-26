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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Dossiers de playlists (rangés dans Hush : Tidal ne connaît pas les dossiers de ses playlists). */
object PlaylistFolders {
    /** id de playlist → nom du dossier */
    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map
    /** dossier affiché dans la Biblio (null = tout) */
    val selected = MutableStateFlow<String?>(null)
    /** playlist dont on choisit le dossier (appui long) */
    val editing = MutableStateFlow<TidalApi.Playlist?>(null)
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("hush", Context.MODE_PRIVATE)
        prefs = p
        _map.value = runCatching {
            val o = JSONObject(p.getString("plFolders", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun folders(): List<String> = _map.value.values.distinct().sortedBy { it.lowercase() }

    fun put(id: String, folder: String?) {
        val f = folder?.trim()?.takeIf { it.isNotEmpty() }
        _map.value = if (f == null) _map.value - id else _map.value + (id to f)
        if (selected.value != null && selected.value !in folders()) selected.value = null
        prefs?.edit()?.putString("plFolders", JSONObject(_map.value as Map<*, *>).toString())?.apply()
    }
}

/** Les dossiers en pastilles au-dessus des playlists. */
@Composable
fun FolderChips() {
    val map by PlaylistFolders.map.collectAsState()
    val sel by PlaylistFolders.selected.collectAsState()
    val folders = remember(map) { PlaylistFolders.folders() }
    if (folders.isEmpty()) {
        Text("Maintiens une playlist pour la ranger dans un dossier", color = Txt3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        val options = listOf<String?>(null) + folders
        items(options.size) { i ->
            val o = options[i]
            val on = sel == o
            Box(
                Modifier.clip(BtnShape)
                    .then(if (on) Modifier.background(Txt) else Modifier.glass(BtnShape, fill = 0.08f, edge = 0.14f))
                    .bounceClick { PlaylistFolders.selected.value = o }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val n = if (o == null) map.size else map.values.count { it == o }
                Text(if (o == null) "Tout" else "$o · $n", color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Fenêtre « Ranger dans un dossier ». */
@Composable
fun FolderDialog() {
    val p by PlaylistFolders.editing.collectAsState()
    val pl = p ?: return
    val map by PlaylistFolders.map.collectAsState()
    val current = map[pl.id]
    var name by remember(pl.id) { mutableStateOf("") }
    fun done() { PlaylistFolders.editing.value = null }
    androidx.compose.ui.window.Dialog(onDismissRequest = { done() }) {
        val shape = Rounded(18)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Surface2).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Ranger « ${pl.name} »", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PlaylistFolders.folders().forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (f == current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .bounceClick(pressedScale = 0.98f) { PlaylistFolders.put(pl.id, f); done() }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(f, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (f == current) Text("Ici", color = Txt2, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("Nouveau dossier", color = Txt3, fontSize = 15.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PillButton("OK") { if (name.isNotBlank()) { PlaylistFolders.put(pl.id, name); done() } }
            }
            if (current != null) {
                Text(
                    "Sortir du dossier", color = Ko, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick { PlaylistFolders.put(pl.id, null); done() }.padding(vertical = 8.dp),
                )
            }
        }
    }
}
