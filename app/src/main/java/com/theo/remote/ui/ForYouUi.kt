package com.theo.remote.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.TidalRemote

/** Une grande carte de « Pour toi ce soir ». */
private data class ForYouCard(
    val key: String,
    val label: String,
    val title: String,
    val cover: Any?,
    val tint: Color,
    val icon: ImageVector? = null,
    val onPlay: (() -> Unit)?,
    val onOpen: () -> Unit,
)

/** Accueil : « Pour toi ce soir », de grandes cartes avec pochette, ombre et bouton lecture. */
@Composable
fun ForYouRow() {
    val ctx = LocalContext.current
    val flags by com.theo.remote.Features.flags.collectAsState()
    val mixAll by DailyMix.items.collectAsState()
    val blocked by com.theo.remote.Blocked.items.collectAsState()
    val mix = remember(mixAll, blocked) { mixAll.filterNot { com.theo.remote.Blocked.isBlocked(it.title, it.artist) } }
    val entries by History.entries.collectAsState()
    LaunchedEffect(entries.isNotEmpty()) { if (entries.isNotEmpty()) runCatching { DailyMix.ensure(ctx) } }
    val plays by com.theo.remote.PlaylistPlays.list.collectAsState()
    val store by PlaylistStore.list.collectAsState()
    val open = { p: com.theo.remote.TidalApi.Playlist -> NavEvents.openPlaylist(p) }

    val cards = remember(mix, plays, store, flags.discover) {
        buildList {
            if (mix.isNotEmpty()) add(
                ForYouCard(
                    "mix", "Mix du jour", "${mix.size} sons pour toi", DailyMix.coverModel(mix.first().cover), Color(0xFF3B2A6B),
                    onPlay = { TidalRemote.playSequence(mix.map { it.title to it.artist }) },
                    onOpen = { Panels.show(Panels.Kind.Mix) },
                ),
            )
            // ta playlist du moment : la plus lancée
            com.theo.remote.PlaylistPlays.top(2).forEach { e ->
                val p = store?.firstOrNull { it.id == e.id }
                add(
                    ForYouCard(
                        "pl${e.id}", "Playlist du moment", e.name, com.theo.remote.PlaylistCovers.art(e.id, e.cover), Color(0xFF243B63),
                        onPlay = { TidalRemote.setShuffle(false); NowPlaylist.set(e.id); TidalRemote.playPlaylist(e.name) },
                        onOpen = { if (p != null) open(p) else { TidalRemote.setShuffle(false); NowPlaylist.set(e.id); TidalRemote.playPlaylist(e.name) } },
                    ),
                )
            }
            if (flags.discover) add(
                ForYouCard(
                    "discover", "Découverte", "Jamais écoutés", null, Color(0xFF0F4C5C), icon = Icons.Rounded.Explore,
                    onPlay = null, onOpen = { Panels.show(Panels.Kind.Discover) },
                ),
            )
        }
    }
    if (cards.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ce soir".let { if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 17) "Aujourd'hui" else it },
            color = Txt, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = Space.gutter))
        LazyRow(contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cards, key = { it.key }) { c -> BigCard(c) }
        }
    }
}

@Composable
private fun BigCard(c: ForYouCard) {
    val shape = Rounded(22)
    Box(
        Modifier.size(width = 230.dp, height = 290.dp).shadow(22.dp, shape).clip(shape).background(c.tint)
            .bounceClick(pressedScale = 0.97f, onClick = c.onOpen),
    ) {
        if (c.cover != null) Cover(model = c.cover, radius = 0.dp, modifier = Modifier.fillMaxSize())
        else if (c.icon != null) Icon(c.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.align(Alignment.Center).padding(bottom = 60.dp).size(96.dp))
        // le bas de la carte s'assombrit pour que le texte se lise sur n'importe quelle pochette
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(130.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.label, color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(c.title, color = Color.White, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            c.onPlay?.let { play ->
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(46.dp).shadow(10.dp, PillShape).clip(PillShape).background(Color.White).bounceClick(onClick = play),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire ${c.title}", tint = Color.Black, modifier = Modifier.size(26.dp)) }
            }
        }
    }
}
