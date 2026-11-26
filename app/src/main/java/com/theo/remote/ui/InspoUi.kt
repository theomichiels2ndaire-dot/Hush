package com.theo.remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.StatsExclude
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================================
// Un son par artiste
// =====================================================================================

private fun oneSongPerArtist(limit: Int = 60): List<SmartLists.Item> {
    val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
    val byArtist = ev.groupBy { mainArtist(it.artist).lowercase() }
    return byArtist.values.sortedByDescending { it.size }.take(limit).mapNotNull { plays ->
        val top = plays.groupBy { it.key }.maxByOrNull { it.value.size } ?: return@mapNotNull null
        val e = top.value.firstOrNull { it.cover != null } ?: top.value.first()
        if (com.theo.remote.Blocked.isBlocked(e.title, e.artist)) null else SmartLists.Item(e.title, e.artist, e.cover, top.value.size)
    }
}

@Composable
fun OneSongPanel() {
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val v by StatsExclude.version.collectAsState()
    val items by produceState<List<SmartLists.Item>?>(null, entries, scrobbles, v) { value = withContext(Dispatchers.Default) { oneSongPerArtist() } }
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        item {
            PanelTitle("Un son par artiste")
            Text("Ton son préféré de chacun de tes artistes, du plus écouté au moins écouté. Ton best of perso.", color = Txt2, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Space.m))
            val l = items.orEmpty()
            if (items == null) SkeletonList(6)
            else if (l.isEmpty()) Text("Pas encore assez d'écoutes.", color = Txt3, fontSize = 13.sp)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PillButton("Lire") { SmartLists.play(l, shuffled = false) }
                    PillButton("Aléatoire", filled = false) { SmartLists.play(l, shuffled = true) }
                }
                Spacer(Modifier.height(Space.s))
                Text(
                    copying ?: "Copier dans Tidal", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) {
                        if (copying != null) return@bounceClick
                        copying = "Recherche dans Tidal…"
                        scope.launch {
                            val r = runCatching { SmartLists.copyToTidal("Un son par artiste", l) { i, total -> copying = "Recherche dans Tidal… $i / $total" } }
                            copying = null
                            TidalRemote.showToast(r.fold({ "Playlist « Un son par artiste » créée dans Tidal ($it sons)" }, { "Connecte ton compte Tidal (Biblio) pour copier" }))
                        }
                    }.padding(vertical = 6.dp),
                )
                Spacer(Modifier.height(Space.s))
            }
        }
        itemsIndexed(items.orEmpty(), key = { _, it -> it.artist + it.title }) { i, it ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .bounceClick(pressedScale = 0.97f, onLongClick = { TrackMenu.open(TrackMenu.Target(it.title, it.artist, it.cover)) }) {
                        SmartLists.play(items.orEmpty().drop(i), shuffled = false)
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Cover(model = it.cover, radius = 6.dp, modifier = Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(mainArtist(it.artist), color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.title, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${it.plays}×", color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
