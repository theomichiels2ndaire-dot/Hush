package com.theo.remote.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote

/** Une page de détail (artiste ou album), connue directement ou à retrouver par son nom. */
sealed class Detail {
    data class ArtistRef(val artist: TidalApi.Artist) : Detail()
    data class ArtistByName(val name: String) : Detail()
    data class AlbumRef(val album: TidalApi.Album) : Detail()
    data class AlbumByName(val title: String, val artist: String) : Detail()

    val isArtist get() = this is ArtistRef || this is ArtistByName

    companion object
}

/** N'importe quel écran peut ouvrir une page artiste/album. */
val LocalOpenDetail = staticCompositionLocalOf<(Detail) -> Unit> { {} }

/** Ouvre la page d'un artiste à partir d'un son (par son id si connu, sinon par son nom). */
fun Detail.Companion.artistOf(r: TidalApi.Result): Detail? {
    val name = r.artists.firstOrNull() ?: return null
    val id = r.artistIds.firstOrNull()
    return if (id != null) Detail.ArtistRef(TidalApi.Artist(id, name, null)) else Detail.ArtistByName(name)
}

// ---------------------------------------------------------------- page artiste

@Composable
fun ArtistScreen(d: Detail, s: TidalRemote.State, onBack: () -> Unit) {
    val open = LocalOpenDetail.current
    var page by remember(d) { mutableStateOf<TidalApi.ArtistPage?>(null) }
    var error by remember(d) { mutableStateOf<String?>(null) }
    LaunchedEffect(d) {
        try {
            val artist = when (d) {
                is Detail.ArtistRef -> d.artist
                is Detail.ArtistByName -> TidalApi.findArtist(d.name) ?: throw TidalApi.ApiException("Artiste introuvable sur Tidal")
                else -> return@LaunchedEffect
            }
            page = TidalApi.artistPage(artist)
        } catch (e: Exception) {
            error = e.message ?: "Erreur réseau"
        }
    }
    val name = page?.artist?.name ?: (d as? Detail.ArtistByName)?.name ?: (d as? Detail.ArtistRef)?.artist?.name.orEmpty()
    val picture = page?.artist?.pictureUrl
    // couleur tirée de la photo de l'artiste (style Pochette : la seule couleur de la page)
    val colors = rememberUrlCoverColors(picture)
    val accent = androidx.compose.ui.graphics.lerp(colors.b, Color.White, 0.35f)
    val band = androidx.compose.ui.graphics.lerp(colors.b, Color.Black, 0.62f)
    val similar by produceState(emptyList<TidalApi.Artist>(), page?.artist?.id) {
        value = page?.artist?.id?.let { runCatching { TidalApi.similarArtistsFull(it) }.getOrNull() } ?: emptyList()
    }
    val me = rememberArtistMe(name)
    val flags by com.theo.remote.Features.flags.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    val g = Modifier.padding(horizontal = Space.gutter)
    var allTop by remember(d) { mutableStateOf(false) }
    var discKind by remember(d) { mutableStateOf<String?>(null) }

    val artGlass = rememberBackdrop()
    Box(Modifier.fillMaxSize().background(Ink)) {
        Box(Modifier.fillMaxSize().backdropSource(artGlass)) {
        Ambient(colors, strength = 0.95f, header = true)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(bottom = 190.dp),
        ) {
            // ---- grande photo plein écran + nom en très gros (façon Apple Music)
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(480.dp).clipToBounds().background(band)) {
                    // la photo défile moins vite que la page (effet de profondeur)
                    Cover(
                        model = TidalApi.bigPicture(picture), radius = 0.dp,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val off = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
                            translationY = off * 0.45f
                        },
                    )
                    // fondu vers le fond de la page, pour que la photo s'y fonde
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(260.dp).background(
                            Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.55f), Ink.copy(alpha = 0.92f))),
                        ),
                    )
                    Row(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            me?.rank?.let { r ->
                                Text(
                                    if (r == 1) "Ton artiste n°1 ce mois-ci" else "Dans ton top $r ce mois-ci",
                                    color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(PillShape).background(accent).padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                name, color = Txt, fontSize = 52.sp, lineHeight = 54.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.2).sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 20f)),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        // gros bouton lecture rond, aux couleurs de la photo
                        Box(
                            Modifier.size(58.dp).shadow(16.dp, PillShape, ambientColor = colors.a, spotColor = colors.a)
                                .clip(PillShape).background(accent)
                                .bounceClick(pressedScale = 0.88f) { TidalRemote.setShuffle(false); TidalRemote.playArtist(name) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire $name", tint = Ink, modifier = Modifier.size(32.dp)) }
                    }
                }
            }
            // ---- Lire, Aléatoire, Suivre
            item(key = "actions") {
                Column(g.padding(top = 18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtistButton(Icons.Rounded.PlayArrow, "Lire", accent, Ink, Modifier.weight(1f)) { TidalRemote.setShuffle(false); TidalRemote.playArtist(name) }
                        ArtistButton(Icons.Rounded.Shuffle, "Aléatoire", Surface2, Txt, Modifier.weight(1f)) { TidalRemote.setShuffle(true); TidalRemote.playArtist(name) }
                        if (flags.follow) {
                            val followed by com.theo.remote.Follow.artists.collectAsState()
                            val isF = followed.any { it.equals(name, ignoreCase = true) }
                            Box(
                                Modifier.size(52.dp).glass(PillShape, fill = if (isF) 0.24f else 0.12f)
                                    .bounceClick { com.theo.remote.Follow.toggle(ctx, name) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(if (isF) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = if (isF) "Suivi" else "Suivre", tint = Txt, modifier = Modifier.size(22.dp)) }
                        }
                    }
                    if (flags.discography) page?.let { Spacer(Modifier.height(12.dp)); DiscographyBar(name, it) }
                }
            }

            val p = page
            when {
                error != null -> item { Text(error!!, color = Ko, fontSize = 13.sp, modifier = g.padding(top = 20.dp)) }
                p == null -> item { Box(g.padding(top = 20.dp)) { SkeletonList(6) } }
                else -> {
                    // ---- dernière sortie
                    val latest = p.albums.maxByOrNull { it.releaseDate ?: it.year ?: "" }
                    if (latest != null) item(key = "latest") {
                        val since = latest.releaseDate?.let { daysSince(it) }
                        Row(
                            g.padding(top = 22.dp).fillMaxWidth().shadow(14.dp, Rounded(20), ambientColor = colors.a, spotColor = colors.a).clip(Rounded(20)).background(band)
                                .bounceClick(pressedScale = 0.98f) { open(Detail.AlbumRef(latest)) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(model = latest.coverUrl, radius = 12.dp, modifier = Modifier.size(88.dp).shadow(12.dp, RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "Dernière sortie" + when { since == null -> ""; since <= 0 -> " · aujourd'hui"; since < 60 -> " · il y a $since jour${if (since > 1) "s" else ""}"; else -> " · ${latest.year ?: ""}" },
                                    color = Txt.copy(alpha = 0.75f), fontSize = 12.sp,
                                )
                                Text(latest.title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(typeLabel(latest.type), latest.count.takeIf { it > 0 }?.let { "$it titre${if (it > 1) "s" else ""}" }).joinToString(" · "),
                                    color = Txt.copy(alpha = 0.75f), fontSize = 12.sp,
                                )
                            }
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Txt)
                                    .bounceClick { TidalRemote.playAlbum(latest.title, latest.artist ?: name) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire ${latest.title}", tint = Ink, modifier = Modifier.size(24.dp)) }
                        }
                    }
                    // ---- toi et l'artiste
                    if (me != null && me.plays > 0) item(key = "me") {
                        Column(
                            g.padding(top = 14.dp).fillMaxWidth().clip(Rounded(14)).background(Surface2).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Toi et $name", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row {
                                MeStat(fmtHours(me.ms), "écoutées", Modifier.weight(1f))
                                MeStat("${me.distinct}", if (me.distinct > 1) "sons différents" else "son", Modifier.weight(1f))
                                MeStat(me.first?.let { java.text.SimpleDateFormat("MMM yy", java.util.Locale.FRANCE).format(java.util.Date(it)) } ?: "—", "première écoute", Modifier.weight(1f))
                            }
                        }
                    }
                    // ---- populaires
                    if (p.topTracks.isNotEmpty()) {
                        item(key = "topTitle") { ArtistSection("Populaires", g.padding(top = 26.dp)) }
                        val shown = if (allTop) p.topTracks else p.topTracks.take(5)
                        itemsIndexed(shown, key = { i, r -> "t$i-${r.id}" }) { i, r ->
                            val n = me?.perTitle?.get(artNorm(r.title)) ?: 0
                            val fav = me?.favorite != null && me.favorite == artNorm(r.title)
                            val playing = s.launch?.trackId == r.id
                            Row(
                                g.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(8.dp))
                                    .bounceClick(pressedScale = 0.98f, onLongClick = { TrackMenu.open(r) }) { TidalRemote.play(r) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${i + 1}", color = if (playing || fav) accent else Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                                Box(Modifier.size(46.dp).clip(RoundedCornerShape(6.dp))) {
                                    Cover(model = r.coverUrl, radius = 6.dp, modifier = Modifier.fillMaxSize())
                                    if (playing) PlayingOverlay(s.isPlaying, Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.fullTitle, color = if (playing || fav) accent else Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        when {
                                            fav -> "Ton préféré · $n écoute${if (n > 1) "s" else ""}"
                                            me != null && me.plays > 0 && n == 0 -> "Jamais écouté"
                                            n > 0 -> "$n écoute${if (n > 1) "s" else ""}"
                                            else -> r.artistLine
                                        },
                                        color = if (fav) accent else Txt2, fontSize = 13.sp, fontWeight = if (fav) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                RatingBadge(r.title, r.artistLine)
                                if (r.durationMs > 0) Text(fmtDur(r.durationMs), color = Txt2, fontSize = 13.sp)
                            }
                        }
                        if (p.topTracks.size > 5) item(key = "more") {
                            Text(
                                if (allTop) "Moins" else "Voir les ${p.topTracks.size}", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = g.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).bounceClick { allTop = !allTop }.padding(vertical = 10.dp),
                            )
                        }
                    }
                    // ---- discographie
                    if (p.albums.isNotEmpty()) item(key = "disc") {
                        val albums = p.albums.distinctBy { it.id }
                        val kinds = listOf("ALBUM" to "Albums", "EP" to "EP", "SINGLE" to "Singles").filter { (k, _) -> albums.any { it.type.equals(k, true) } }
                        val kind = discKind ?: kinds.firstOrNull()?.first
                        val shownAlbums = if (kinds.size > 1 && kind != null) albums.filter { it.type.equals(kind, true) } else albums
                        Column(Modifier.padding(top = 26.dp)) {
                            Row(g.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Discographie", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                                if (kinds.size > 1) kinds.forEach { (k, label) ->
                                    val on = k == kind
                                    Text(
                                        label, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Txt else Surface2)
                                            .bounceClick { discKind = k }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(shownAlbums.distinctBy { it.id }, key = { it.id }) { a -> AlbumTile(a) { open(Detail.AlbumRef(a)) } }
                            }
                        }
                    }
                    // ---- les fans écoutent aussi
                    if (similar.isNotEmpty()) item(key = "similar") {
                        Column(Modifier.padding(top = 26.dp)) {
                            ArtistSection("Les fans écoutent aussi", g)
                            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(similar.distinctBy { it.id }, key = { it.id }) { a ->
                                    Column(
                                        Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.96f) { open(Detail.ArtistRef(a)) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Cover(model = a.pictureUrl, radius = 48.dp, modifier = Modifier.size(96.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(a.name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        // bouton retour : il reste en place quand on fait défiler, en verre liquide par-dessus la page
        Box(Modifier.statusBarsPadding().padding(12.dp)) {
            GlassCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Retour", backdrop = artGlass) { onBack() }
        }
    }
}

@Composable
private fun ArtistButton(icon: ImageVector, label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Row(
        // boutons en pilule ; le gris uni devient du verre
        modifier.height(52.dp).then(
            if (bg == Surface2) Modifier.glass(Rounded(16), fill = 0.14f)
            else Modifier.clip(Rounded(16)).background(bg),
        ).bounceClick(pressedScale = 0.95f) { haptic(); onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun ArtistSection(text: String, modifier: Modifier) {
    Text(text, color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = modifier.padding(bottom = 10.dp))
}

@Composable
private fun MeStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = Txt2, fontSize = 12.sp, maxLines = 1)
    }
}

/** Toi et cet artiste : écoutes, sons, première écoute, rang du mois. */
private data class ArtistMe(
    val plays: Int, val ms: Long, val distinct: Int, val first: Long?,
    val perTitle: Map<String, Int>, val favorite: String?, val rank: Int?,
)

private fun artNorm(t: String) = t.lowercase().substringBefore(" (").replace(Regex("[^\\p{L}\\p{N}]"), "")

@Composable
private fun rememberArtistMe(name: String): ArtistMe? {
    val entries by com.theo.remote.History.entries.collectAsState()
    val scrobbles by com.theo.remote.LastFm.scrobbles.collectAsState()
    val me by produceState<ArtistMe?>(null, name, entries, scrobbles) {
        if (name.isBlank()) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val evs = buildEvents(entries, scrobbles)
                val mine = evs.filter { e -> e.artist.split(",", "&").any { it.trim().equals(name, true) } }
                val per = mine.groupingBy { artNorm(it.title) }.eachCount()
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                }
                val month = evs.filter { it.ts >= cal.timeInMillis }.groupingBy { mainArtist(it.artist).lowercase() }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key }
                val r = month.indexOf(name.lowercase()).takeIf { it in 0..9 }?.plus(1)
                ArtistMe(
                    plays = mine.size, ms = mine.sumOf { it.durMs }, distinct = per.size,
                    first = mine.minOfOrNull { it.ts }, perTitle = per,
                    favorite = per.maxByOrNull { it.value }?.key, rank = r,
                )
            }.getOrNull()
        }
    }
    return me
}

private fun fmtHours(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m min" else "${m / 60} h"
}

private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

private fun typeLabel(t: String?) = when (t?.uppercase()) { "ALBUM" -> "Album"; "EP" -> "EP"; "SINGLE" -> "Single"; else -> null }

private fun daysSince(date: String): Int? = runCatching {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(date.take(10)) ?: return null
    ((System.currentTimeMillis() - d.time) / 86_400_000L).toInt()
}.getOrNull()

// ---------------------------------------------------------------- page album

@Composable
fun AlbumScreen(d: Detail, s: TidalRemote.State, onBack: () -> Unit) {
    val open = LocalOpenDetail.current
    var page by remember(d) { mutableStateOf<TidalApi.AlbumPage?>(null) }
    var error by remember(d) { mutableStateOf<String?>(null) }
    LaunchedEffect(d) {
        try {
            val album = when (d) {
                is Detail.AlbumRef -> d.album
                is Detail.AlbumByName -> TidalApi.findAlbum(d.title, d.artist) ?: throw TidalApi.ApiException("Album introuvable sur Tidal")
                else -> return@LaunchedEffect
            }
            page = TidalApi.albumPage(album)
        } catch (e: Exception) {
            error = e.message ?: "Erreur réseau"
        }
    }
    val a = page?.album ?: (d as? Detail.AlbumRef)?.album
    val title = a?.title ?: (d as? Detail.AlbumByName)?.title.orEmpty()
    val artist = a?.artist ?: (d as? Detail.AlbumByName)?.artist
    val main = artist?.let { mainArtist(it) }.orEmpty()
    // couleur tirée de la pochette : la seule couleur de la page
    val colors = rememberUrlCoverColors(a?.coverUrl)
    val accent = androidx.compose.ui.graphics.lerp(colors.b, Color.White, 0.35f)
    val band = androidx.compose.ui.graphics.lerp(colors.b, Color.Black, 0.62f)
    val me = rememberArtistMe(main)
    // les autres sorties de l'artiste
    val more by produceState(emptyList<TidalApi.Album>(), main, a?.id) {
        if (main.isBlank()) return@produceState
        value = runCatching {
            val ar = TidalApi.findArtist(main) ?: return@runCatching emptyList()
            TidalApi.artistPage(ar).albums.distinctBy { it.id }.filter { it.id != a?.id && !it.title.equals(title, true) }.take(12)
        }.getOrDefault(emptyList())
    }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    val g = Modifier.padding(horizontal = Space.gutter)

    val albGlass = rememberBackdrop()
    Box(Modifier.fillMaxSize().background(Ink)) {
        Box(Modifier.fillMaxSize().backdropSource(albGlass)) {
        Ambient(colors, strength = 0.95f, header = true)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 190.dp)) {
            // ---- pochette sur un aplat de sa couleur
            item(key = "head") {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 22.dp)) {
                    Spacer(Modifier.height(68.dp))
                    // façon Apple Music : grande pochette en relief, tout centré dessous
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Cover(model = a?.coverUrl?.let { TidalApi.bigPicture(it) }, radius = 18.dp, modifier = Modifier.size(290.dp).shadow(40.dp, Rounded(18), ambientColor = colors.a, spotColor = colors.a))
                    }
                    Spacer(Modifier.height(24.dp))
                    Column(g.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, color = Txt, fontSize = 30.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (!artist.isNullOrBlank()) {
                            Text(
                                artist, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
                                    .bounceClick(pressedScale = 0.96f) { open(Detail.ArtistByName(main)) }.padding(vertical = 4.dp),
                            )
                        }
                        val total = page?.tracks?.sumOf { it.durationMs } ?: 0L
                        val meta = listOfNotNull(
                            typeLabel(a?.type),
                            a?.year,
                            (page?.tracks?.size ?: a?.count)?.takeIf { it > 0 }?.let { "$it titre${if (it > 1) "s" else ""}" },
                            total.takeIf { it > 60_000 }?.let { fmtLength(it) },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, color = Txt.copy(alpha = 0.66f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // ---- Lire, Aléatoire, ajouter à une playlist
            item(key = "actions") {
                Row(g.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ArtistButton(Icons.Rounded.PlayArrow, "Lire", accent, Ink, Modifier.weight(1f)) { TidalRemote.setShuffle(false); TidalRemote.playAlbum(title, artist.orEmpty()) }
                    ArtistButton(Icons.Rounded.Shuffle, "Aléatoire", Surface2, Txt, Modifier.weight(1f)) { TidalRemote.setShuffle(true); TidalRemote.playAlbum(title, artist.orEmpty()) }
                    Box(
                        Modifier.size(52.dp).glass(PillShape, fill = 0.14f)
                            .bounceClick { page?.tracks?.takeIf { it.isNotEmpty() }?.let { PlaylistEdit.pick(it) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, contentDescription = "Ajouter l'album à une playlist", tint = Txt, modifier = Modifier.size(22.dp)) }
                }
            }
            if (com.theo.remote.Features.flags.value.focusAlbum) item(key = "focus") {
                Row(
                    g.padding(top = 10.dp).fillMaxWidth().height(48.dp).glass(Rounded(16), fill = 0.10f)
                        .bounceClick(pressedScale = 0.98f) { page?.tracks?.let { FocusAlbum.start(title, artist.orEmpty(), a?.coverUrl, it) } },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.Headphones, contentDescription = null, tint = Txt, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Écouter sans distraction", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            item(key = "rating") {
                AlbumRatingCard(title, main, a?.coverUrl, accent, g.padding(top = 14.dp))
            }
            val p = page
            when {
                error != null -> item { Text(error!!, color = Ko, fontSize = 13.sp, modifier = g.padding(top = 20.dp)) }
                p == null -> item { Box(g.padding(top = 20.dp)) { SkeletonList(8) } }
                else -> {
                    // ---- toi et cet album
                    val counts = p.tracks.map { me?.perTitle?.get(artNorm(it.title)) ?: 0 }
                    val heard = counts.count { it > 0 }
                    val favIdx = counts.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
                    if (heard > 0) item(key = "me") {
                        Column(
                            g.padding(top = 18.dp).fillMaxWidth().glass(Rounded(20), fill = 0.10f).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Toi et cet album", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                            Row {
                                MeStat("$heard/${p.tracks.size}", "sons écoutés", Modifier.weight(1f))
                                MeStat("${counts.sum()}", "écoutes", Modifier.weight(1f))
                                MeStat(if (heard == p.tracks.size) "Fini" else "${p.tracks.size - heard}", if (heard == p.tracks.size) "en entier" else "à découvrir", Modifier.weight(1f))
                            }
                            // avancement : une barre unie
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.10f))) {
                                Box(Modifier.fillMaxWidth(heard.toFloat() / p.tracks.size).height(6.dp).clip(RoundedCornerShape(4.dp)).background(accent))
                            }
                        }
                    }
                    item(key = "tracksGap") { Spacer(Modifier.height(16.dp)) }
                    itemsIndexed(p.tracks, key = { i, r -> "a$i-${r.id}" }) { i, r ->
                        val n = counts.getOrElse(i) { 0 }
                        val fav = i == favIdx
                        val playing = s.launch?.trackId == r.id
                        val hi = playing || fav
                        Row(
                            g.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp))
                                .bounceClick(pressedScale = 0.98f, onLongClick = { TrackMenu.open(r) }) { TidalRemote.play(r) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(30.dp)) {
                                if (playing) EqBars(playing = s.isPlaying, color = accent, height = 14.dp)
                                else Text("${i + 1}", color = if (fav) accent else Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(r.fullTitle, color = if (hi) accent else Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val feat = r.artistLine.takeIf { !it.equals(artist, true) }
                                val tag = when {
                                    fav -> "Ton préféré · $n écoute${if (n > 1) "s" else ""}"
                                    heard > 0 && n == 0 -> "Jamais écouté"
                                    n > 0 -> "$n écoute${if (n > 1) "s" else ""}"
                                    else -> null
                                }
                                val sub = listOfNotNull(tag, feat).joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, color = if (fav) accent else Txt2, fontSize = 13.sp, fontWeight = if (fav) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            RatingBadge(r.title, r.artistLine)
                            if (r.durationMs > 0) Text(fmtDur(r.durationMs), color = Txt2, fontSize = 13.sp)
                        }
                    }
                    a?.releaseDate?.let { rd ->
                        item(key = "date") {
                            val txt = runCatching {
                                val dd = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(rd.take(10))!!
                                "Sorti le " + java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.FRANCE).format(dd)
                            }.getOrNull()
                            if (txt != null) Text(txt, color = Txt2, fontSize = 13.sp, modifier = g.padding(top = 16.dp))
                        }
                    }
                    // ---- plus de l'artiste
                    if (more.isNotEmpty()) item(key = "more") {
                        Column(Modifier.padding(top = 28.dp)) {
                            ArtistSection("Plus de $main", g)
                            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(more.distinctBy { it.id }, key = { it.id }) { x -> AlbumTile(x) { open(Detail.AlbumRef(x)) } }
                            }
                        }
                    }
                }
            }
        }
        }
        // bouton retour : il reste en place quand on fait défiler, en verre liquide par-dessus la page
        Box(Modifier.statusBarsPadding().padding(12.dp)) {
            GlassCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Retour", backdrop = albGlass) { onBack() }
        }
    }
}

private fun fmtLength(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m min" else "${m / 60} h ${"%02d".format(m % 60)}"
}

// ---------------------------------------------------------------- briques communes

@Composable
private fun CloseHandle(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 72.dp, height = 44.dp).clip(PillShape).bounceClick(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Fermer", tint = Txt.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = Space.s))
}

@Composable
private fun AlbumTile(a: TidalApi.Album, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.96f, onClick = onClick)) {
        Cover(model = a.coverUrl, radius = 8.dp, modifier = Modifier.size(140.dp))
        Spacer(Modifier.height(Space.s))
        Text(a.title, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(a.year, typeLabel(a.type)).joinToString(" · "), color = Txt2, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DetailPill(icon: ImageVector, label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = PillShape
    val haptic = rememberHaptic()
    Row(
        modifier
            .height(48.dp)
            .then(if (filled) Modifier.clip(shape).background(Txt) else Modifier.glass(shape, fill = 0.12f, edge = 0.16f))
            .bounceClick { haptic(); onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Ink else Txt, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (filled) Ink else Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}
