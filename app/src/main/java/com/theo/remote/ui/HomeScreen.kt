package com.theo.remote.ui

import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.theo.remote.TidalApi
import com.theo.remote.TidalAuth
import com.theo.remote.TidalRemote
import java.io.File
import java.util.Calendar

@Composable
fun HomeScreen(
    s: TidalRemote.State,
    onOpenPlayer: () -> Unit,
    onOpenPlaylist: (TidalApi.Playlist) -> Unit,
) {
    val entries by History.entries.collectAsState()
    val likes by History.likes.collectAsState()
    val loggedIn by TidalAuth.loggedIn.collectAsState()
    val playlists by PlaylistStore.list.collectAsState()
    var likedOnly by rememberSaveable { mutableStateOf(false) }

    // complète les pochettes manquantes avec celles du catalogue Tidal
    val missing = entries.count { it.coverUrl == null }
    LaunchedEffect(missing) { if (missing > 0) History.resolveCovers() }
    LaunchedEffect(loggedIn) { if (loggedIn) runCatching { PlaylistStore.load() } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val releases by NewReleases.list.collectAsState()
    val openDetail = LocalOpenDetail.current
    LaunchedEffect(entries.isNotEmpty()) { if (entries.isNotEmpty()) runCatching { NewReleases.refresh(ctx) } }
    val upcoming by Upcoming.list.collectAsState()
    val upcomingOn = com.theo.remote.Features.flags.collectAsState().value.upcoming
    // la playlist qui grandit : la fournée de la semaine se prépare dès le lundi
    val growingOn = com.theo.remote.Features.flags.collectAsState().value.growing
    LaunchedEffect(growingOn) {
        Growing.init(ctx)
        if (growingOn) runCatching { Growing.ensureWeek(ctx) }
    }
    LaunchedEffect(entries.isNotEmpty(), upcomingOn) {
        Upcoming.load(ctx)
        if (entries.isNotEmpty() && upcomingOn) runCatching { Upcoming.refresh(ctx) }
    }

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val hello = when (hour) {
        in 5..11 -> "Bonjour"
        in 12..17 -> "Bon après-midi"
        else -> "Bonsoir"
    }
    val momentLabel = when (hour) {
        in 5..11 -> "Pour ce matin"
        in 12..17 -> "Pour cet après-midi"
        in 18..22 -> "Pour ce soir"
        else -> "Pour cette nuit"
    }

    val blocked by com.theo.remote.Blocked.items.collectAsState()
    val features by com.theo.remote.Features.flags.collectAsState()
    fun shown(id: String) = id !in features.hidden
    val visible = remember(entries, blocked) { entries.filterNot { com.theo.remote.Blocked.isBlocked(it.title, it.artist) } }
    val liked = remember(visible, likes) { visible.filter { likes.contains(it.key) } }
    val nShort = if (features.compactHome) 8 else 6
    val shortcuts = if (likedOnly) liked.take(nShort) else visible.take(nShort)
    val weekly = remember(visible) { History.weekly(visible) }
    val moment = remember(visible, hour) { History.thisTimeOfDay(visible) }
    val forgotten = remember(visible) { History.forgotten(visible) }
    val recents = remember(visible) { visible.drop(6).take(20) }

    val homeScroll = rememberScrollState()
    LaunchedEffect(Unit) { NavEvents.reselect.collect { if (it == "Home") homeScroll.animateScrollTo(0) } }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(homeScroll)
            .padding(top = Space.l, bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(if (features.compactHome) Space.m else Space.l),
    ) {
        Column(Modifier.padding(horizontal = Space.gutter).cascadeIn(0, "homeHello")) {
            Text(
                java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.FRANCE).format(java.util.Date()).replaceFirstChar { it.uppercase() },
                color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "$hello Theo${seasonGreeting()}",
                color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
            )
        }

        // les outils de Hush : les plus utiles en pastilles, le reste dans « Tous les outils »
        val alarm by com.theo.remote.Alarm.config.collectAsState()
        val alarmAt = remember(alarm) { com.theo.remote.Alarm.next(alarm) }

        if (!s.hasPermission || !s.connected) {
            Box(Modifier.padding(horizontal = Space.gutter)) { SetupCard(s) }
            // nettoyage du mois (une fois par mois, s'il y a des sons que tu passes toujours)
            val cleanPl by PlaylistStore.list.collectAsState()
            LaunchedEffect(cleanPl) { PlaylistCleanup.refreshDue() }
            CleanupCard(Modifier.padding(horizontal = Space.gutter))
        }

        // sections dans l'ordre choisi dans les Réglages
        val afterShortcuts = setOf("mood", "weekly", "moment", "releases", "playlists", "forgotten", "recents")
        smartOrder(features.homeOrder, features.smartHome).forEach { id ->
            if (!shown(id) || (likedOnly && id in afterShortcuts)) return@forEach
            androidx.compose.runtime.key(id) {
                when (id) {
                    "tools" -> LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        item { ToolChip(androidx.compose.material.icons.Icons.Rounded.GraphicEq, "Identifier") { Panels.show(Panels.Kind.Shazam) } }
                        if (features.trip) item { ToolChip(androidx.compose.material.icons.Icons.Rounded.Timer, "Trajet") { Panels.show(Panels.Kind.Trip) } }
                        item {
                            ToolChip(
                                androidx.compose.material.icons.Icons.Rounded.Alarm,
                                alarmAt?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(it)) } ?: "Réveil",
                            ) { Panels.show(Panels.Kind.Alarm) }
                        }
                        item { ToolChip(androidx.compose.material.icons.Icons.Rounded.Apps, "Outils") { Panels.show(Panels.Kind.Tools) } }
                    }
                    "resume" -> if (features.resumeLast) ResumeCard(s)
                    "now" -> AnimatedVisibility(
                        visible = s.connected && s.track != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Box(Modifier.padding(horizontal = Space.gutter)) { NowPlayingCard(s, onOpenPlayer) }
                    }
                    "foryou" -> ForYouRow()
                    "mix" -> if (!shown("foryou")) DailyMixCard()
                    "gem" -> if (features.hiddenGem) HiddenGemCard()
                    "yearAgo" -> if (features.yearAgo) YearAgoSection()
                    "chartsAgo" -> if (features.chartsAgo) ChartsAgoCard()
                    "skips" -> if (features.skipDetect) SkipSuggestionCard()
                    "shortcuts" -> Column(verticalArrangement = Arrangement.spacedBy(if (features.compactHome) Space.m else Space.l)) {
                        Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Chip("Tout", !likedOnly) { likedOnly = false }
                            Chip("Titres likés", likedOnly) { likedOnly = true }
                        }
                        if (shortcuts.isEmpty()) {
                            Text(
                                if (likedOnly) "Touche le cœur dans le lecteur pour ajouter un son ici."
                                else "Les sons que tu écoutes dans Tidal apparaîtront ici.",
                                color = Txt2, fontSize = 13.sp, lineHeight = 20.sp,
                                modifier = Modifier.padding(horizontal = Space.gutter),
                            )
                        } else {
                            // grille 2 colonnes façon Spotify
                            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                shortcuts.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                        pair.forEach { e -> Shortcut(e, Modifier.weight(1f)) }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    "mood" -> MoodRow()
                    "weekly" -> if (weekly.isNotEmpty()) Section("Cette semaine") { items(weekly, key = { it.key }) { RecentCard(it) } }
                    "moment" -> if (moment.isNotEmpty()) Section(momentLabel) { items(moment, key = { it.key }) { RecentCard(it) } }
                    "releases" -> if (releases.isNotEmpty()) Section("Nouveautés") {
                        items(releases, key = { "rel-" + it.id }) { a -> ReleaseTile(a) { openDetail(Detail.AlbumRef(a)) } }
                    }
                    "upcoming" -> if (features.upcoming && upcoming.isNotEmpty()) Section("À venir") {
                        items(upcoming, key = { "up-" + it.id }) { a -> UpcomingTile(a) { openDetail(Detail.AlbumRef(a)) } }
                    }
                    "growing" -> if (features.growing) GrowingCard()
                    "playlists" -> {
                        val pl = playlists.orEmpty()
                        if (loggedIn && pl.isNotEmpty()) Section("Playlists") { items(pl, key = { it.id }) { p -> PlaylistTile(p) { onOpenPlaylist(p) } } }
                    }
                    "forgotten" -> if (forgotten.isNotEmpty()) Section("Pas écoutés depuis longtemps") { items(forgotten, key = { it.key }) { RecentCard(it) } }
                    "recents" -> if (recents.isNotEmpty()) Section("Récents") { items(recents, key = { it.key }) { RecentCard(it) } }
                    else -> {}
                }
                Unit
            }
        }
    }
    // en scrollant, « Accueil » reste lisible dans une barre en verre en haut
    }
}

@Composable
private fun Section(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(
            title,
            color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            content = content,
        )
    }
}

/** Grande carte du son en cours, sur un dégradé aux couleurs de sa pochette. */
@Composable
private fun NowPlayingCard(s: TidalRemote.State, onOpen: () -> Unit) {
    val t = s.track ?: return
    val colors = rememberCoverColors(t.art)
    val shape = Rounded(24)
    val pos = rememberLivePosition(s, intervalMs = 1000)
    val progress = if (t.durationMs > 0) (pos.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (com.theo.remote.Features.flags.value.flatStyle)
                    // style Pochette : la couleur du son, en aplat
                    Modifier.background(androidx.compose.ui.graphics.lerp(colors.a, Color.Black, 0.3f))
                else Modifier
                    .background(Brush.linearGradient(listOf(colors.a.copy(alpha = 0.85f), colors.b.copy(alpha = 0.9f))))
                    .background(Color.Black.copy(alpha = 0.18f))
                    .glass(shape, fill = 0.06f, edge = 0.18f),
            )
            .bounceClick(pressedScale = 0.98f, onClick = onOpen),
    ) {
        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Cover(bitmap = t.art, radius = 16.dp, modifier = Modifier.size(104.dp).shadow(14.dp, Rounded(16)).clip(Rounded(16)).glare())
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text("En ce moment", color = Txt.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Space.xs))
                AnimatedContent(
                    targetState = t.title to t.artist,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                    label = "nowTitle",
                ) { (title, artist) ->
                    Column {
                        Text(title, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                        Text(artist, color = Txt.copy(alpha = 0.75f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(Space.s))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(PillShape).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(PillShape).background(Txt))
                }
            }
            Spacer(Modifier.width(Space.s))
            PlayPauseButton(s.isPlaying, 34.dp)
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Box(
        Modifier
            .clip(shape)
            .then(if (active) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = Space.m, vertical = 9.dp),
    ) {
        Text(label, color = if (active) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Shortcut(e: History.Entry, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val haptic = rememberHaptic()
    Row(
        modifier
            .height(56.dp)
            .glass(shape, fill = 0.07f, edge = 0.10f)
            .bounceClick(pressedScale = 0.96f, onLongClick = { haptic(); TrackMenu.open(TrackMenu.Target(e.title, e.artist, e.coverUrl ?: e.artPath?.let { java.io.File(it) }, album = e.album)) }) { haptic(); TidalRemote.playTitle(e.title, e.artist) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = e.coverUrl ?: e.artPath?.let { File(it) }, radius = 0.dp, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            e.title,
            color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = Space.s),
        )
    }
}

@Composable
private fun RecentCard(e: History.Entry) {
    val haptic = rememberHaptic()
    val compact = com.theo.remote.Features.flags.collectAsState().value.compactHome
    val side = if (compact) 108.dp else 140.dp
    Column(
        Modifier
            .width(side)
            .clip(RoundedCornerShape(12.dp))
            .bounceClick(pressedScale = 0.96f, onLongClick = { haptic(); TrackMenu.open(TrackMenu.Target(e.title, e.artist, e.coverUrl ?: e.artPath?.let { java.io.File(it) }, album = e.album)) }) { haptic(); TidalRemote.playTitle(e.title, e.artist) },
    ) {
        Cover(
            model = e.coverUrl ?: e.artPath?.let { File(it) },
            radius = 16.dp,
            modifier = Modifier.size(side).shadow(16.dp, Rounded(16), ambientColor = Color.Black, spotColor = Color.Black).clip(Rounded(16)).glare(),
        )
        Spacer(Modifier.height(Space.s))
        Text(e.title, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(e.artist, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistTile(p: TidalApi.Playlist, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var mosaic by remember(p.id) { mutableStateOf<List<String>>(emptyList()) }
    val art = rememberPlaylistArt(p)
    if (art == null) LaunchedEffect(p.id) { mosaic = MosaicCache.get(ctx, p.id) }
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .bounceClick(pressedScale = 0.96f, onClick = onClick),
    ) {
        if (art != null) Cover(
            model = art,
            radius = 16.dp,
            modifier = Modifier.size(140.dp).shadow(16.dp, Rounded(16), ambientColor = Color.Black, spotColor = Color.Black).clip(Rounded(16)).glare(),
        ) else Mosaic(mosaic, 16.dp, Modifier.size(140.dp).shadow(16.dp, Rounded(16)))
        Spacer(Modifier.height(Space.s))
        Text(p.name, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${p.count} titre${if (p.count > 1) "s" else ""}", color = Txt2, fontSize = 12.sp)
    }
}


/** Pastille d'outil sur l'accueil (soirée, voiture, réveil…). */
@Composable
private fun ToolChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val shape = BtnShape
    Row(
        Modifier.glass(shape, fill = 0.10f, edge = 0.16f).bounceClick(pressedScale = 0.94f, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Txt, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}


/** « Reprendre où j'en étais » : le dernier son, à l'endroit où tu t'es arrêté. */
@Composable
private fun ResumeCard(s: TidalRemote.State) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var spot by remember { mutableStateOf(com.theo.remote.LastPlay.get()) }
    val sp = spot ?: return
    if (s.isPlaying) return
    val shape = Rounded(20)
    Row(
        Modifier.padding(horizontal = Space.gutter).fillMaxWidth().glass(shape, fill = 0.09f, edge = 0.14f)
            .bounceClick(pressedScale = 0.98f) { scope.launch { com.theo.remote.LastPlay.resume(ctx, sp); spot = null } }
            .padding(horizontal = Space.m, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(PillShape).background(Txt), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Rounded.PlayArrow, contentDescription = "Reprendre", tint = Ink, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Reprendre à ${fmt(sp.posMs)}", color = Txt2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(sp.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sp.artist, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(44.dp).clip(PillShape).bounceClick(pressedScale = 0.85f) { com.theo.remote.LastPlay.dismiss(); spot = null },
            contentAlignment = Alignment.Center,
        ) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Ignorer", tint = Txt2, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Accueil selon le moment (option) : certaines sections remontent selon l'heure.
 * « Outils », « Reprendre » et « En ce moment » restent toujours en haut ; le reste garde ton ordre.
 */
private fun smartOrder(order: List<String>, on: Boolean): List<String> {
    if (!on) return order
    val boost = when (java.time.LocalTime.now().hour) {
        in 5..10 -> listOf("releases", "upcoming", "mood", "foryou", "growing")   // matin : nouveautés et sons calmes
        in 11..17 -> listOf("foryou", "mix", "shortcuts", "weekly")               // journée
        in 18..23 -> listOf("mix", "playlists", "foryou", "gem", "moment")        // soir : mix et tes playlists (5 étoiles…)
        else -> listOf("mood", "playlists", "forgotten", "yearAgo")               // nuit
    }
    val pinned = setOf("tools", "resume", "now")
    val top = order.filter { it in pinned }
    val lifted = boost.filter { it in order && it !in pinned }
    return top + lifted + order.filter { it !in pinned && it !in lifted }
}
