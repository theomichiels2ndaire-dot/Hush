package com.theo.remote.ui

import com.theo.remote.playTitle

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Période du mur de pochettes. */
private enum class WallPeriod(val label: String, val days: Int) { Today("Aujourd'hui", 0), Week("7 jours", 7), Month("30 jours", 30) }

/** Une case du mur : un son, combien de fois, et quand pour la dernière fois. */
private data class WallItem(val title: String, val artist: String, val cover: Any?, val plays: Int, val last: Long)

/**
 * Mur de pochettes : tout ce que tu as écouté sur la période, en grille de pochettes
 * (les plus récents d'abord). Toucher une pochette relance le son.
 */
@Composable
fun CoverWallPanel() {
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    var period by rememberSaveable { mutableStateOf(WallPeriod.Today) }
    val items by produceState<List<WallItem>?>(null, entries, scrobbles, period) {
        value = withContext(Dispatchers.Default) {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val from = cal.timeInMillis - period.days * 24L * 3600_000
            buildEvents(entries, scrobbles)
                .filter { it.ts >= from }
                .groupBy { it.key }
                .map { (_, evs) ->
                    val latest = evs.maxBy { it.ts }
                    WallItem(latest.title, latest.artist, evs.firstNotNullOfOrNull { it.cover }, evs.size, latest.ts)
                }
                .sortedByDescending { it.last }
        }
    }
    val grid = rememberLazyGridState()
    val list = items
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                PanelTitle("Ton mur")
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        list == null -> "…"
                        list.isEmpty() -> "Rien d'écouté sur cette période"
                        else -> "${list.size} son${if (list.size > 1) "s" else ""} · ${list.sumOf { it.plays }} écoute${if (list.sumOf { it.plays } > 1) "s" else ""}"
                    },
                    color = Txt2, fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                // sélecteur de période
                Row(Modifier.fillMaxWidth().glass(PillShape, fill = 0.08f).padding(4.dp)) {
                    WallPeriod.entries.forEach { p ->
                        val on = p == period
                        val bg by animateColorAsState(if (on) Txt else Color.Transparent, label = "wallSeg")
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(PillShape).background(bg).bounceClick { period = p },
                            contentAlignment = Alignment.Center,
                        ) { Text(p.label, color = if (on) Ink else Txt2, fontSize = 13.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (list == null) {
            item(span = { GridItemSpan(maxLineSpan) }) { SkeletonList(4) }
        } else {
            itemsIndexed(list, key = { _, w -> w.title + "|" + w.artist }) { i, w ->
                WallTile(w, Modifier.animateItem().cascadeIn(i.coerceAtMost(18), "wall" + w.title + w.artist))
            }
        }
    }
}

@Composable
private fun WallTile(w: WallItem, modifier: Modifier) {
    val shape = Rounded(14)
    val haptic = rememberHaptic()
    Box(
        modifier.fillMaxWidth().aspectRatio(1f).shadow(10.dp, shape).clip(shape)
            .bounceClick(pressedScale = 0.93f) { haptic(); TidalRemote.playTitle(w.title, w.artist) },
    ) {
        Cover(model = w.cover, radius = 14.dp, modifier = Modifier.fillMaxSize())
        // titre en bas, sur un léger dégradé pour rester lisible
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
        ) {
            Text(w.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (w.plays > 1) Text(
            "×${w.plays}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(PillShape)
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
