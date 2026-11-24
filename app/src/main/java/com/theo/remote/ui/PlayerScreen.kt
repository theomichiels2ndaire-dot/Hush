package com.theo.remote.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(s: TidalRemote.State, onClose: () -> Unit, onLyrics: () -> Unit) {
    // option : le lecteur en verre (mix des maquettes B et C)
    if (com.theo.remote.Features.flags.collectAsState().value.glassPlayer) { GlassMixPlayer(s, onClose, onLyrics); return }
    val openDetail = LocalOpenDetail.current
    val t = s.track
    val pos = rememberLivePosition(s, intervalMs = 500)
    val duration = t?.durationMs ?: 0L
    var dragPos by remember { mutableStateOf<Float?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var bigCover by remember { mutableStateOf(false) }
    val likes by History.likes.collectAsState()
    val liked = t != null && likes.contains(History.keyOf(t.title, t.artist))
    val ctx = LocalContext.current

    // gestes du lecteur (option) : deux doigts qui glissent vers le haut / le bas = volume
    val gesturesOn = androidx.compose.runtime.rememberUpdatedState(com.theo.remote.Features.flags.collectAsState().value.playerGestures)
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volHud by remember { mutableStateOf<Float?>(null) }
    val volHaptic = rememberHaptic()

    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    var startVol = -1f
                    var startY = 0f
                    var lastStep = -1
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val down = e.changes.filter { it.pressed }
                        if (gesturesOn.value && down.size >= 2) {
                            val y = down.map { it.position.y }.average().toFloat()
                            if (startVol < 0f) { startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max; startY = y }
                            // la moitié de l'écran = tout le volume
                            val v = (startVol + (startY - y) / (size.height * 0.5f)).coerceIn(0f, 1f)
                            val step = kotlin.math.round(v * max).toInt()
                            if (step != lastStep) {
                                if (lastStep >= 0) volHaptic()
                                lastStep = step
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
                            }
                            volHud = v
                            e.changes.forEach { it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                    volHud = null
                }
            }
            .systemBarsPadding().padding(horizontal = 24.dp),
    ) {
        // poignée
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 72.dp, height = 44.dp)
                .clip(PillShape)
                .bounceClick(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Réduire le lecteur", tint = Txt.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
        }

        Spacer(Modifier.height(14.dp))
        // à l'ouverture, la pochette part de la place du mini-lecteur (en bas à gauche, en petit) et vient se poser
        val fly = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { fly.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 230f)) }
        // place finale de la pochette (mesurée avant l'animation) : elle grandit en continu depuis le mini-lecteur
        var coverRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val mini = CoverAnchor.mini
        Box(
            Modifier
                .onGloballyPositioned { if (coverRect == null) coverRect = it.boundsInWindow() }
                .graphicsLayer {
                    val f = fly.value
                    val k = 1f - f
                    val r = coverRect
                    if (mini != null && r != null && r.width > 0f) {
                        val sc = (mini.width / r.width) + (1f - mini.width / r.width) * f
                        scaleX = sc; scaleY = sc
                        translationX = (mini.center.x - r.center.x) * k
                        translationY = (mini.center.y - r.center.y) * k
                        alpha = 1f
                    } else if (mini != null) {
                        alpha = 0f // pas encore mesurée : on attend une image pour ne pas sauter
                    } else {
                        val sc = 0.16f + 0.84f * f
                        scaleX = sc; scaleY = sc
                        translationX = -size.width * 0.40f * k
                        translationY = size.height * 1.55f * k
                        alpha = 0.3f + 0.7f * f
                    }
                },
        ) {
            SwipeableCover(s) { if (com.theo.remote.Features.flags.value.fullCover) bigCover = true }
        }

        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val lines = when {
                maxHeight >= 64.dp -> 2
                maxHeight >= 36.dp -> 1
                else -> 0
            }
            if (lines > 0) LyricsPreview(s, onLyrics, lines)
        }

        // titre + actions (mode gaucher : cœur et menu passent à gauche)
        val leftHanded = com.theo.remote.Features.flags.collectAsState().value.leftHanded
        val handDir = if (leftHanded) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides handDir) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = t?.title to listOfNotNull(t?.artist?.ifBlank { null }, t?.album?.ifBlank { null }).joinToString(" — "),
                transitionSpec = {
                    (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 340f)) { it / 6 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 6 } + fadeOut(tween(160)))
                },
                modifier = Modifier.weight(1f),
                label = "title",
            ) { (title, sub) ->
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Column {
                    // toucher le titre : la page de l'album (ou de l'artiste si l'album est inconnu)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title ?: "—", color = Txt, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).bounceClick(pressedScale = 0.97f, onLongClick = { t?.let { TrackMenu.open(TrackMenu.Target(it.title, it.artist, it.art, album = it.album)) } }) {
                            val ar = t?.artist.orEmpty()
                            val al = t?.album.orEmpty()
                            when {
                                al.isNotBlank() -> openDetail(Detail.AlbumByName(al, ar))
                                ar.isNotBlank() -> openDetail(Detail.ArtistByName(ar.split(",", "&").first().trim()))
                            }
                        },
                    )
                    // le rang de la carte de ce son : toucher pour la voir
                    PlayerCardGem(title.orEmpty(), t?.artist.orEmpty())
                    }
                    Spacer(Modifier.height(3.dp))
                    // artiste → sa page ; album → sa page
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val artist = t?.artist.orEmpty()
                        val album = t?.album.orEmpty()
                        if (artist.isNotBlank()) {
                            Text(
                                artist,
                                color = Txt.copy(alpha = 0.62f), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp))
                                    .bounceClick(pressedScale = 0.96f) { openDetail(Detail.ArtistByName(artist.split(",", "&").first().trim())) },
                            )
                        }
                        if (album.isNotBlank()) {
                            Text(" — ", color = Txt.copy(alpha = 0.62f), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                album,
                                color = Txt.copy(alpha = 0.62f), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp))
                                    .bounceClick(pressedScale = 0.96f) { openDetail(Detail.AlbumByName(album, artist)) },
                            )
                        }
                    }
                }
                }
            }
            Spacer(Modifier.width(12.dp))
            HeartButton(s.tidalFavorite ?: liked) { t?.let { toggleHeart(s, it) } }
            Spacer(Modifier.width(10.dp))
            Box {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                var menu by remember { mutableStateOf(false) }
                val sheetGlass = LocalSheetGlass.current
                var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
                // contenu du menu ⋯ (le même en verre liquide ou en menu classique)
                val menuContent: @Composable (close: () -> Unit) -> Unit = { closeMenu -> PlayerMoreMenu(t, ctx, closeMenu) }
                Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
                    GlassCircle(Icons.Rounded.MoreHoriz, "Plus d'options", backdrop = LocalPlayerGlass.current) {
                        if (sheetGlass != null) { PlayerOverlay.menuAnchor = anchor; PlayerOverlay.menu = menuContent } else menu = true
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menu && sheetGlass == null,
                    onDismissRequest = { menu = false },
                    shape = Rounded(18),
                    containerColor = Color(0xFF1C1C24),
                ) {
                    menuContent { menu = false }
                }
                }
            }
        }
        }

        t?.let { NoteBadge(it.title, it.artist, Modifier.padding(top = 12.dp)) }

        // progression
        Spacer(Modifier.height(20.dp))
        val progress = dragPos ?: if (duration > 0) pos.toFloat() / duration else 0f
        com.theo.remote.Rewards.equipped.collectAsState().value
        val groove = com.theo.remote.Features.flags.value.rewards && com.theo.remote.Rewards.isOn("bar:groove")
        val retro = com.theo.remote.Features.flags.value.rewards && com.theo.remote.Rewards.isOn("time:retro")
        if (groove) GrooveBar(
            value = progress,
            playing = s.isPlaying,
            onChange = { dragPos = it },
            onFinished = { v -> TidalRemote.seekTo((v * duration).toLong()); dragPos = null },
            enabled = duration > 0,
        ) else if (com.theo.remote.Features.flags.collectAsState().value.wavyBar) WavyBar(
            value = progress,
            playing = s.isPlaying,
            onChange = { dragPos = it },
            onFinished = { v -> TidalRemote.seekTo((v * duration).toLong()); dragPos = null },
            enabled = duration > 0,
        ) else ThickBar(
            value = progress,
            onChange = { dragPos = it },
            onFinished = { v -> TidalRemote.seekTo((v * duration).toLong()); dragPos = null },
            enabled = duration > 0,
        )
        val shownPos = dragPos?.let { (it * duration).toLong() } ?: pos
        val timeFont = if (retro) androidx.compose.ui.text.font.FontFamily.Monospace else null
        // le temps grossit et s'éclaire pendant qu'on fait glisser la barre
        val seeking = dragPos != null
        val timeSize = if (retro) 14.sp else if (seeking) 15.sp else 12.sp
        val timeColor = if (retro) Ok else if (seeking) Txt else Txt.copy(alpha = 0.6f)
        Box(Modifier.fillMaxWidth()) {
            Text(fmt(shownPos), color = timeColor, fontSize = timeSize, fontFamily = timeFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart))
            // qualité + petit bouton des appareils (option) juste à côté
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                QualityBadge(t?.title, t?.artist)
                if (com.theo.remote.Features.flags.collectAsState().value.deviceChip) {
                    Spacer(Modifier.width(6.dp))
                    DeviceButton()
                }
            }
            Text("-" + fmt(duration - shownPos), color = timeColor, fontSize = timeSize, fontFamily = timeFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterEnd))
        }

        // commandes
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigButton(Icons.Rounded.FastRewind, "Précédent", 52.dp) { TidalRemote.previous() }
            PlayPauseButton(s.isPlaying, 76.dp)
            BigButton(Icons.Rounded.FastForward, "Suivant", 52.dp) { TidalRemote.next() }
        }

        // volume
        Spacer(Modifier.height(14.dp))
        VolumeBar()

        // paroles + file
        Spacer(Modifier.height(18.dp))
        val rowBd = LocalPlayerGlass.current
        val capsule = PillShape
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides handDir) {
        Box(
            Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .then(if (rowBd != null) Modifier.shadow(14.dp, capsule).clip(capsule) else Modifier),
        ) {
        // dans le lecteur : les boutons du bas sont posés dans une capsule de verre liquide
        if (rowBd != null) {
            LiquidGlass(rowBd, capsule, cornerRadius = 30.dp, modifier = Modifier.matchParentSize(), blur = 3.dp, depth = 20.dp, refraction = 12.dp, chroma = 0.25f)
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.12f)))
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = if (rowBd != null) 6.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShuffleButton(s.shuffle)
            if (com.theo.remote.Features.flags.collectAsState().value.bass) BassButton()
            // paroles : une simple icône, comme les autres boutons du bas
            Box(
                Modifier.size(48.dp).clip(PillShape).bounceClick(pressedScale = 0.96f, onClick = onLyrics),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "Paroles", tint = Txt.copy(alpha = 0.75f), modifier = Modifier.size(25.dp)) }
            if (com.theo.remote.Features.flags.collectAsState().value.ratings) QuickRateStar(s.track?.title, s.track?.artist)
            Box(
                Modifier.size(48.dp).clip(PillShape).bounceClick(pressedScale = 0.96f) { showQueue = !showQueue },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "File d'attente", tint = if (showQueue) Txt else Txt.copy(alpha = 0.75f), modifier = Modifier.size(26.dp))
            }
        }
        }
        }
    }

    // jauge de volume pendant le geste à deux doigts
    volHud?.let { v ->
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.width(220.dp).clip(PillShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = Txt, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(6.dp).clip(PillShape).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(v).background(Color.White))
                }
                Spacer(Modifier.width(10.dp))
                Text("${(v * 100).toInt()} %", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (showQueue) QueueOverlay(s) { showQueue = false }
    androidx.compose.animation.AnimatedVisibility(
        visible = bigCover,
        enter = androidx.compose.animation.fadeIn(tween(250)) + androidx.compose.animation.scaleIn(tween(320), initialScale = 0.9f),
        exit = androidx.compose.animation.fadeOut(tween(200)),
    ) {
        BigCover(s) { bigCover = false }
    }
}

/** Contenu du menu ⋯ du lecteur. */
@Composable
internal fun PlayerMoreMenu(t: TidalRemote.Track?, ctx: android.content.Context, close: () -> Unit) {
    val pf = com.theo.remote.Features.flags.value
    MenuHeader("Ce son")
    MenuLine(Icons.AutoMirrored.Rounded.PlaylistAdd, "Ajouter à une playlist") {
        close(); t?.let { PlaylistEdit.pickCurrent(it.title, it.artist) }
    }
    if (pf.whereIsIt) MenuLine(Icons.Rounded.TravelExplore, "Où est ce son ?") { close(); t?.let { WhereIsIt.open(it.title, it.artist) } }
    if (pf.credits) MenuLine(Icons.Rounded.Info, "Crédits du son") { close(); t?.let { Credits.open(it.title, it.artist) } }
    if (pf.clip) MenuLine(Icons.Rounded.OndemandVideo, "Voir le clip") { close(); t?.let { Credits.openClip(ctx, it.title, it.artist) } }
    if (pf.bass || pf.spatial) {
        MenuHeader("Son")
        if (pf.spatial) MenuLine(Icons.Rounded.Headphones, if (com.theo.remote.AudioFx.spatial.value) "Couper le son 8D" else "Son 8D") {
            close(); com.theo.remote.AudioFx.setSpatial(!com.theo.remote.AudioFx.spatial.value); Panels.show(Panels.Kind.Sound)
        }
        if (pf.bass) MenuLine(Icons.Rounded.GraphicEq, "Basses") { close(); Panels.show(Panels.Kind.Sound) }
    }
    MenuHeader("Outils")
    MenuLine(Icons.Rounded.DirectionsCar, "Mode voiture") { close(); CarMode.open() }
    MenuLine(Icons.AutoMirrored.Rounded.OpenInNew, "Ouvrir dans Tidal") { close(); TidalRemote.openTidal(ctx) }
}

@Composable
private fun MenuHeader(label: String) {
    Text(
        label, color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuLine(icon: ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Txt, modifier = Modifier.size(22.dp)) },
        onClick = onClick,
        modifier = Modifier.height(44.dp),
    )
}

@Composable
fun GlassCircle(icon: ImageVector, label: String, backdrop: Backdrop? = null, onClick: () -> Unit) {
    // posé sur quelque chose qui défile : vrai verre liquide
    if (backdrop != null && liquidOn()) { LiquidCircleButton(backdrop, icon, label, onClick = onClick); return }
    val shape = PillShape
    Box(
        Modifier.size(44.dp).glass(shape, fill = 0.16f, edge = 0.14f).bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Txt, modifier = Modifier.size(21.dp))
    }
}

@Composable
fun BigButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Box(
        Modifier.size(size + 20.dp).clip(PillShape).bounceClick { haptic(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Txt, modifier = Modifier.size(size))
    }
}

@Composable
fun LyricsPill(active: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Row(
        Modifier
            .height(44.dp)
            .then(if (active) Modifier.clip(shape).background(Txt) else Modifier.glass(shape, fill = 0.12f, edge = 0.14f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = if (active) Ink else Txt, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Paroles", color = if (active) Ink else Txt, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Volume média du téléphone (celui que Tidal utilise). */
@Composable
private fun VolumeBar() {
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    var dragging by remember { mutableStateOf(false) }

    // suit les boutons physiques de volume
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            if (!dragging) vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }
    }
    DisposableEffect(Unit) { onDispose { } }

    fun apply(v: Float) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt().coerceIn(0, max), 0)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = null, tint = Txt.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            ThickBar(
                value = vol,
                onChange = { dragging = true; vol = it; apply(it) },
                onFinished = { vol = it; apply(it); dragging = false },
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = Txt.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun HeartButton(liked: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptic(strong = true)
    var pops by remember { mutableIntStateOf(0) }
    var likedNow by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    LaunchedEffect(pops) {
        if (pops > 0) {
            if (likedNow) {
                // on aime : le cœur gonfle fort et une onde part autour
                ring.snapTo(0f)
                kotlinx.coroutines.coroutineScope {
                    launch { ring.animateTo(1f, tween(520)) }
                    pop.snapTo(0.6f)
                    pop.animateTo(1.35f, tween(140))
                    pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 420f))
                }
            } else {
                pop.snapTo(0.75f)
                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
            }
        }
    }
    val pink = Color(0xFFFF4D6D)
    val shape = PillShape
    val liquidBd = LocalPlayerGlass.current
    Box(
        Modifier.size(44.dp)
            .drawBehind {
                val r = ring.value
                if (r < 1f) drawCircle(pink.copy(alpha = 0.7f * (1f - r)), radius = size.minDimension / 2f * (0.8f + 0.9f * r), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() * (1f - r) + 0.5f))
            }
            .then(if (liquidBd != null) Modifier.clip(shape) else Modifier.glass(shape, fill = 0.16f, edge = 0.14f))
            .bounceClick { haptic(); likedNow = !liked; pops++; onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // dans le lecteur : une goutte de verre liquide
        if (liquidBd != null) {
            LiquidGlass(liquidBd, shape, cornerRadius = 22.dp, modifier = Modifier.matchParentSize(), blur = 2.dp, depth = 16.dp, refraction = 9.dp, chroma = 0.28f, zoom = 1.08f, spec = 1.1f)
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.12f)))
        }
        Crossfade(liked, label = "heart") { on ->
            Icon(
                if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (on) "Retirer des favoris" else "Ajouter aux favoris",
                tint = if (on) pink else Txt,
                modifier = Modifier.size(21.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            )
        }
    }
}

@Composable
fun PlayPauseButton(playing: Boolean, size: Dp) {
    val haptic = rememberHaptic()
    val liquidBd = LocalPlayerGlass.current
    // dans le lecteur : le bouton lecture devient une grosse goutte de verre, l'icône un peu plus petite dedans
    val iconSize = if (liquidBd != null) size * 0.62f else size
    Box(
        Modifier.size(size + 20.dp).then(if (liquidBd != null) Modifier.shadow(16.dp, PillShape) else Modifier)
            .clip(PillShape).bounceClick(pressedScale = 0.82f) { haptic(); TidalRemote.playPause() },
        contentAlignment = Alignment.Center,
    ) {
        if (liquidBd != null) {
            LiquidGlass(liquidBd, PillShape, cornerRadius = (size + 20.dp) / 2, modifier = Modifier.matchParentSize(), blur = 2.dp, depth = size * 0.4f, refraction = size * 0.22f, chroma = 0.3f, zoom = 1.1f, spec = 1.2f)
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.10f)))
        }
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                    (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(120)))
            },
            label = "playPause",
        ) { p ->
            Icon(
                if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (p) "Pause" else "Lecture",
                tint = Txt,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun ShuffleButton(on: Boolean) {
    val haptic = rememberHaptic()
    val bg by animateColorAsState(if (on) Txt else Color.Transparent, tween(220), label = "shuffleBg")
    val fg by animateColorAsState(if (on) Ink else Txt.copy(alpha = 0.75f), tween(220), label = "shuffleFg")
    Box(
        Modifier.size(44.dp).clip(PillShape).background(bg).bounceClick(pressedScale = 0.85f) { haptic(); TidalRemote.toggleShuffle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Shuffle, contentDescription = if (on) "Désactiver l'aléatoire" else "Lecture aléatoire", tint = fg, modifier = Modifier.size(22.dp))
    }
}


/**
 * Pochette qu'on fait glisser : à gauche = son suivant, à droite = son précédent.
 * Le geste est lu AVANT la couche qui bouge (ordre des modificateurs), donc il ne tremble pas.
 */
@Composable
private fun SwipeableCover(s: TidalRemote.State, onTap: () -> Unit = {}) {
    val haptic = rememberHaptic(strong = true)
    val t = s.track
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val steady = rememberSteadyPlaying(s.isPlaying)
    val coverScale by animateFloatAsState(if (steady) 1f else 0.84f, spring(dampingRatio = 0.62f, stiffness = 260f), label = "cover")

    // pochette animée : elle « respire » au rythme du son (option, coupée en mode économie)
    val flags by com.theo.remote.Features.flags.collectAsState()
    val pulseOn = flags.animatedCover && !flags.eco && steady
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(pulseOn) {
        if (pulseOn) com.theo.remote.CoverPulse.start(ctx)
        onDispose { if (pulseOn) com.theo.remote.CoverPulse.stop() }
    }
    val level by com.theo.remote.CoverPulse.level.collectAsState()
    val live by com.theo.remote.CoverPulse.live.collectAsState()
    val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "breath").animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(2600, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breathT",
    )
    val beat by animateFloatAsState(if (pulseOn && live) level else 0f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "beat")

    // double-tap à gauche / à droite : -10 s / +10 s, avec une bulle qui le montre
    val posNow = androidx.compose.runtime.rememberUpdatedState(rememberLivePosition(s, 500))
    var seekFlash by remember { mutableIntStateOf(0) }
    var seekKey by remember { mutableIntStateOf(0) }
    val seekAlpha = remember { Animatable(0f) }
    LaunchedEffect(seekKey) {
        if (seekKey == 0) return@LaunchedEffect
        seekAlpha.snapTo(1f)
        kotlinx.coroutines.delay(450)
        seekAlpha.animateTo(0f, tween(300))
    }
    // double-tap pour liker : le gros cœur qui apparaît sur la pochette
    val sNow = androidx.compose.runtime.rememberUpdatedState(s)
    val flagsNow = androidx.compose.runtime.rememberUpdatedState(flags)
    var heartKey by remember { mutableIntStateOf(0) }
    val bigHeart = remember { Animatable(0f) }
    val bigHeartAlpha = remember { Animatable(0f) }
    LaunchedEffect(heartKey) {
        if (heartKey == 0) return@LaunchedEffect
        bigHeartAlpha.snapTo(1f)
        bigHeart.snapTo(0.2f)
        bigHeart.animateTo(1.15f, tween(180))
        bigHeart.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        kotlinx.coroutines.delay(380)
        launch { bigHeart.animateTo(1.3f, tween(260)) }
        bigHeartAlpha.animateTo(0f, tween(260))
    }

    // changement de son en douceur (option) : quand le son change tout seul ou par un bouton,
    // la nouvelle pochette arrive en glissant depuis la droite (comme quand on la balaie soi-même)
    var selfSwipe by remember { mutableStateOf(false) }
    val trackKey = t?.let { it.title + "|" + it.artist }
    var lastTrackKey by remember { mutableStateOf(trackKey) }
    LaunchedEffect(trackKey) {
        if (trackKey == null || trackKey == lastTrackKey) return@LaunchedEffect
        val first = lastTrackKey == null
        lastTrackKey = trackKey
        if (first || selfSwipe || !flags.smoothChange || flags.eco) { selfSwipe = false; return@LaunchedEffect }
        offset.snapTo(width * 0.35f)
        offset.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 220f))
    }
    fun pulse(): Float = when {
        !pulseOn -> 1f
        live -> 1f + beat * 0.045f
        else -> 1f + breath.value * 0.022f
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    // double-tap façon Insta : on like (jamais de « je n'aime plus » par accident)
                    onDoubleTap = { at ->
                        val st = sNow.value
                        val tr = st.track
                        val side = at.x / size.width
                        // gestes du lecteur (option) : deux touches à gauche = -10 s, à droite = +10 s
                        if (flagsNow.value.playerGestures && tr != null && (side < 0.3f || side > 0.7f)) {
                            haptic()
                            val fwd = side > 0.7f
                            val d = tr.durationMs.coerceAtLeast(1)
                            TidalRemote.seekTo((posNow.value + if (fwd) 10_000 else -10_000).coerceIn(0, d - 1500))
                            seekFlash = if (fwd) 1 else -1
                            seekKey++
                        } else if (flagsNow.value.doubleTapLike && tr != null) {
                            haptic()
                            heartKey++
                            val liked = st.tidalFavorite ?: com.theo.remote.History.isLiked(tr.title, tr.artist)
                            if (!liked) toggleHeart(st, tr)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragCancel = { scope.launch { offset.animateTo(0f, SoftSpring) } },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val w = width.toFloat()
                        scope.launch {
                            when {
                                offset.value < -w * 0.28f || (v < -1100f && offset.value < -24f) -> {
                                    haptic()
                                    offset.animateTo(-w * 1.15f, tween(170))
                                    selfSwipe = true
                                    TidalRemote.next()
                                    offset.snapTo(w * 0.6f)
                                    offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f))
                                }
                                offset.value > w * 0.28f || (v > 1100f && offset.value > 24f) -> {
                                    haptic()
                                    offset.animateTo(w * 1.15f, tween(170))
                                    selfSwipe = true
                                    TidalRemote.previous()
                                    offset.snapTo(-w * 0.6f)
                                    offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f))
                                }
                                else -> offset.animateTo(0f, SoftSpring)
                            }
                        }
                    },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { offset.snapTo(offset.value + dx) }
                }
            },
    ) {
        // effets de pochette débloqués avec les récompenses
        val eq by com.theo.remote.Rewards.equipped.collectAsState()
        com.theo.remote.Rewards.xp.collectAsState().value // se met à jour quand le niveau change
        val vinyl = flags.rewards && com.theo.remote.Rewards.isOn("cover:vinyl")
        val halo = flags.rewards && com.theo.remote.Rewards.isOn("cover:halo")
        val shine = flags.rewards && com.theo.remote.Rewards.isOn("cover:glass")
        val framed = flags.rewards && com.theo.remote.Rewards.isOn("cover:frame")
        val disc = flags.rewards && com.theo.remote.Rewards.isOn("cover:disc")
        val spin = remember { Animatable(0f) }
        LaunchedEffect(vinyl, disc, steady, eq) {
            if ((vinyl || disc) && steady) while (true) spin.animateTo(spin.value + 360f, tween(if (disc) 7_000 else 16_000, easing = androidx.compose.animation.core.LinearEasing))
        }
        val radius = if (vinyl) 400.dp else 14.dp
        val accent = Ok
        if (disc) {
            // disque plat : un vinyle noir qui tourne, la pochette en étiquette au centre
            Crossfade(t?.art, animationSpec = tween(350), label = "disc") { art ->
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val p = (offset.value / width).coerceIn(-1.5f, 1.5f)
                        translationX = offset.value
                        rotationZ = spin.value
                        alpha = 1f - (abs(p) * 0.55f).coerceAtMost(0.7f)
                        val pk = pulse()
                        scaleX = coverScale * pk
                        scaleY = coverScale * pk
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        drawCircle(Color(0xFF111111), r)
                        for (k in 0..6) drawCircle(Color(0xFF262626), r * (0.5f + k * 0.07f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    }
                    Cover(bitmap = art, radius = 400.dp, modifier = Modifier.fillMaxSize(0.42f))
                    Box(Modifier.size(10.dp).clip(PillShape).background(Color(0xFF111111)))
                }
            }
        } else {
        // pochette vidéo de l'album, si Tidal en a une
        val videoUrl by androidx.compose.runtime.produceState<String?>(null, t?.title, t?.album, flags.videoCovers, flags.eco) {
            value = null
            val tr = t
            if (flags.videoCovers && !flags.eco && tr != null) value = com.theo.remote.VideoCovers.get(tr.title, tr.artist, tr.album)
        }
        Crossfade(t?.art, animationSpec = tween(350), label = "art") { art ->
            Box(Modifier.fillMaxSize()) {
            Cover(
                bitmap = art,
                radius = radius,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = (offset.value / width).coerceIn(-1.5f, 1.5f)
                        translationX = offset.value
                        rotationZ = p * 5f + if (vinyl) spin.value else 0f
                        alpha = 1f - (abs(p) * 0.55f).coerceAtMost(0.7f)
                        val pk = pulse()
                        scaleX = coverScale * pk
                        scaleY = coverScale * pk
                    }
                    .then(
                        // pochette encadrée : un filet fin autour, comme un cadre de galerie
                        if (framed) Modifier.drawBehind {
                            val g = 10.dp.toPx()
                            drawRoundRect(
                                Color.White.copy(alpha = 0.55f),
                                topLeft = androidx.compose.ui.geometry.Offset(-g, -g),
                                size = androidx.compose.ui.geometry.Size(size.width + 2 * g, size.height + 2 * g),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()),
                            )
                        } else Modifier,
                    )
                    .then(
                        if (halo) Modifier.shadow((40f * coverScale).dp, RoundedCornerShape(radius), ambientColor = accent, spotColor = accent)
                        // ombre teintée de la couleur de la pochette (au lieu d'une ombre noire)
                        else Modifier.shadow((32f * coverScale).dp, RoundedCornerShape(radius), ambientColor = accent, spotColor = accent),
                    )
                    .then(
                        if (shine) Modifier.clip(RoundedCornerShape(radius)).drawWithContent {
                            drawContent()
                            drawRect(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    0f to Color.White.copy(alpha = 0.28f), 0.35f to Color.White.copy(alpha = 0.06f), 0.5f to Color.Transparent, 1f to Color.Transparent,
                                    start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                                ),
                            )
                        } else Modifier,
                    ),
            )
            videoUrl?.let { url ->
                VideoCover(
                    url, playing = s.isPlaying,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val p = (offset.value / width).coerceIn(-1.5f, 1.5f)
                        translationX = offset.value
                        rotationZ = p * 5f
                        val pk = pulse()
                        scaleX = coverScale * pk
                        scaleY = coverScale * pk
                        shape = RoundedCornerShape(radius)
                        clip = true
                    },
                )
            }
            }
        }
        }
        // bulle « -10 s / +10 s » du double-tap sur un côté
        if (seekAlpha.value > 0f && seekFlash != 0) {
            Box(
                Modifier.align(if (seekFlash > 0) Alignment.CenterEnd else Alignment.CenterStart).padding(18.dp)
                    .graphicsLayer { alpha = seekAlpha.value; val k = 0.85f + 0.15f * seekAlpha.value; scaleX = k; scaleY = k }
                    .clip(PillShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(if (seekFlash > 0) "+10 s" else "−10 s", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
        // gros cœur du double-tap
        if (bigHeartAlpha.value > 0f) {
            Icon(
                Icons.Rounded.Favorite, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(120.dp).graphicsLayer {
                    scaleX = bigHeart.value; scaleY = bigHeart.value
                    alpha = bigHeartAlpha.value
                },
            )
        }
    }
}


@Composable
fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    val bg by animateColorAsState(if (on) Ok.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f), tween(220), label = "chipBg")
    val dot by animateColorAsState(if (on) Ok else Txt3, tween(220), label = "chipDot")
    Row(
        Modifier.clip(shape).background(bg).bounceClick(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(PillShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}


/** 2 lignes de paroles sous la pochette ; on touche pour ouvrir les paroles en grand. */
@Composable
private fun LyricsPreview(s: TidalRemote.State, onOpen: () -> Unit, maxLines: Int = 2) {
    val t = s.track ?: return
    var lyrics by remember(t.title, t.artist) { mutableStateOf<com.theo.remote.Lyrics.Result?>(null) }
    LaunchedEffect(t.title, t.artist) {
        lyrics = com.theo.remote.Lyrics.fetch(t.title, t.artist, t.album, t.durationMs)
    }
    val lines = lyrics?.synced?.filter { it.text.isNotBlank() } ?: return
    if (lines.isEmpty()) return
    val pos = rememberLivePosition(s, intervalMs = 400)
    val i = lines.indexOfLast { it.timeMs <= pos + 250 }.coerceAtLeast(0)
    val current = lines[i].text
    val next = lines.getOrNull(i + 1)?.text.orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(Rounded(14))
            .bounceClick(pressedScale = 0.98f, onClick = onOpen)
            .padding(vertical = 4.dp),
    ) {
        AnimatedContent(
            targetState = current to next,
            transitionSpec = {
                (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                    (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
            },
            label = "preview",
        ) { (a, b) ->
            Column {
                Text(a, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (maxLines > 1 && b.isNotBlank()) {
                    Text(b, color = Txt.copy(alpha = 0.45f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Badge de qualité (Hi-Res, Lossless, Dolby Atmos) d'après le catalogue Tidal. */
@Composable
internal fun QualityBadge(title: String?, artist: String?) {
    if (title == null) return
    var tags by remember(title, artist) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(title, artist) { tags = com.theo.remote.TidalApi.qualityOf(title, artist.orEmpty()) }
    val labels = buildList {
        when {
            "HIRES_LOSSLESS" in tags -> add("MAX · Hi-Res")
            "LOSSLESS" in tags -> add("Lossless")
        }
        if ("DOLBY_ATMOS" in tags) add("Dolby Atmos")
    }
    AnimatedVisibility(visible = labels.isNotEmpty(), enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { l ->
                Text(
                    l,
                    color = Txt.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}


/**
 * Cœur : ajoute/retire le son de « Ma Musique » sur Tidal quand c'est possible,
 * et garde la liste « Titres likés » de Hush synchronisée.
 */
fun toggleHeart(s: TidalRemote.State, t: TidalRemote.Track) {
    val before = s.tidalFavorite ?: History.isLiked(t.title, t.artist)
    if (TidalRemote.toggleTidalFavorite()) {
        History.setLike(t.title, t.artist, !before)
        TidalRemote.showToast(if (before) "Retiré de Ma Musique" else "Ajouté à Ma Musique")
    } else {
        History.toggleLike(t.title, t.artist)
    }
}


/** Pochette en plein écran : rien que la pochette, géante. Toucher pour revenir, glisser pour changer de son. */
@Composable
private fun BigCover(s: TidalRemote.State, onClose: () -> Unit) {
    val t = s.track
    val colors = rememberCoverColors(t?.art)
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Ink)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -120f) TidalRemote.next() else if (total > 120f) TidalRemote.previous() },
                ) { _, d -> total += d }
            },
        contentAlignment = Alignment.Center,
    ) {
        Ambient(colors, strength = 1f, animated = true)
        androidx.compose.animation.Crossfade(t?.art, animationSpec = tween(450), label = "bigArt") { art ->
            Cover(bitmap = art, radius = 22.dp, modifier = Modifier.fillMaxWidth().padding(18.dp).aspectRatio(1f).shadow(40.dp, Rounded(22)))
        }
        Column(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t?.title.orEmpty(), color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t?.artist.orEmpty(), color = Txt2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Barre de lecture « sillon » (récompense) : la partie jouée ondule pendant la lecture. */
@Composable
private fun GrooveBar(value: Float, playing: Boolean, onChange: (Float) -> Unit, onFinished: (Float) -> Unit, enabled: Boolean) {
    val phase by androidx.compose.animation.core.rememberInfiniteTransition(label = "groove").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "grooveT",
    )
    val amp by androidx.compose.animation.core.animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "grooveAmp")
    var w by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var v by remember { androidx.compose.runtime.mutableFloatStateOf(value) }
    val accent = Ok
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(28.dp)
            .onSizeChanged { w = it.width }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> val x = (o.x / w).coerceIn(0f, 1f); onChange(x); onFinished(x) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> v = (o.x / w).coerceIn(0f, 1f); onChange(v) },
                    onDragEnd = { onFinished(v) },
                    onDragCancel = { onFinished(v) },
                ) { ch, _ -> v = (ch.position.x / w).coerceIn(0f, 1f); onChange(v) }
            },
    ) {
        val cy = size.height / 2f
        val end = size.width * value.coerceIn(0f, 1f)
        val stroke = 4.dp.toPx()
        // reste du son : trait plat
        drawLine(Color.White.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(end, cy), androidx.compose.ui.geometry.Offset(size.width, cy), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        // partie jouée : une onde
        val path = androidx.compose.ui.graphics.Path()
        val step = 3f
        var x = 0f
        val a = 5.dp.toPx() * amp
        path.moveTo(0f, cy)
        while (x <= end) {
            path.lineTo(x, cy + kotlin.math.sin(x / 9.dp.toPx() - phase) * a)
            x += step
        }
        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawCircle(accent, 6.dp.toPx(), androidx.compose.ui.geometry.Offset(end, cy + kotlin.math.sin(end / 9.dp.toPx() - phase) * a))
    }
}

/**
 * Menus du lecteur en verre liquide (⋯ et notation par étoiles), dessinés par-dessus tout le lecteur :
 * on voit le lecteur à travers, tordu sur les bords du verre. Ils s'ouvrent en gonflant depuis leur bouton.
 */
@Composable
fun PlayerGlassOverlays(glass: Backdrop) {
    val menu = PlayerOverlay.menu
    val stars = PlayerOverlay.stars
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // lecteur fermé : on referme aussi ses menus
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { PlayerOverlay.menu = null; PlayerOverlay.stars = null } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        // ---- menu ⋯
        if (menu != null) {
            androidx.activity.compose.BackHandler { PlayerOverlay.menu = null }
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { PlayerOverlay.menu = null } })
            val a = PlayerOverlay.menuAnchor
            val shape = Rounded(24)
            var h by remember { mutableIntStateOf(0) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenH = with(density) { maxHeight.toPx() }
                val w = with(density) { 270.dp.toPx() }
                val gap = with(density) { 8.dp.toPx() }
                val x = (a.right - origin.x - w).coerceAtLeast(gap)
                val below = a.bottom - origin.y + gap
                // sous le bouton si ça tient, sinon on le remonte juste ce qu'il faut : jamais coupé par l'écran
                val top = with(density) { 44.dp.toPx() }
                val bottom = screenH - with(density) { 24.dp.toPx() }
                val y = (if (below + h <= bottom) below else bottom - h).coerceAtLeast(top)
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
                        .width(270.dp)
                        .onSizeChanged { h = it.height }
                        .graphicsLayer {
                            val v = appear.value
                            scaleX = 0.6f + 0.4f * v; scaleY = 0.6f + 0.4f * v
                            alpha = v.coerceIn(0f, 1f)
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, if (y >= below - 1f) 0f else ((a.bottom - origin.y - y) / h.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                        .shadow(26.dp, shape)
                        .clip(shape),
                ) {
                    LiquidGlass(glass, shape, cornerRadius = 24.dp, modifier = Modifier.matchParentSize(), blur = 18.dp, depth = 20.dp, refraction = 14.dp, chroma = 0.25f, spec = 1f)
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
                    Column(Modifier.padding(vertical = 6.dp)) { menu { PlayerOverlay.menu = null } }
                }
            }
        }
        // ---- étoiles (appui long sur l'étoile du lecteur, on glisse vers le haut)
        if (stars != null) {
            val a = PlayerOverlay.starsAnchor
            val shape = Rounded(30)
            var h by remember { mutableIntStateOf(0) }
            var w by remember { mutableIntStateOf(0) }
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 450f)) }
            val gap = with(density) { 12.dp.toPx() }
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((a.center.x - origin.x - w / 2f).toInt(), (a.top - origin.y - gap - h).toInt()) }
                    .onSizeChanged { h = it.height; w = it.width }
                    .graphicsLayer {
                        val v = appear.value
                        scaleX = 0.5f + 0.5f * v; scaleY = 0.5f + 0.5f * v
                        alpha = v.coerceIn(0f, 1f)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .shadow(22.dp, shape)
                    .clip(shape),
            ) {
                LiquidGlass(glass, shape, cornerRadius = 30.dp, modifier = Modifier.matchParentSize(), blur = 6.dp, depth = 18.dp, refraction = 12.dp, chroma = 0.28f, spec = 1.1f)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.22f)))
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(if (stars == 0) "Glisse" else "$stars/5", color = if (stars == 0) Txt3 else Txt, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    for (i in 5 downTo 1) {
                        val on = i <= stars
                        val sc by animateFloatAsState(if (i == stars) 1.25f else 1f, label = "gs$i")
                        Icon(
                            Icons.Rounded.Star, contentDescription = null,
                            tint = if (on) Txt else Color.White.copy(alpha = 0.22f),
                            modifier = Modifier.size(34.dp).graphicsLayer { scaleX = sc; scaleY = sc },
                        )
                    }
                }
            }
        }
    }
}
