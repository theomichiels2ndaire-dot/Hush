package com.theo.remote.ui

import com.theo.remote.toggleShuffle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.theo.remote.History
import com.theo.remote.TidalRemote

/**
 * Lecteur « verre » (option) : la pochette bord à bord en haut, un grand panneau de verre posé dessus,
 * les commandes en gouttes et la progression dans un tube de verre rempli de liquide.
 */
@Composable
internal fun GlassMixPlayer(s: TidalRemote.State, onClose: () -> Unit, onLyrics: () -> Unit) {
    val openDetail = LocalOpenDetail.current
    val ctx = LocalContext.current
    val t = s.track
    val flags = com.theo.remote.Features.flags.collectAsState().value
    val colors = rememberCoverColors(t?.art)
    val tint = lerp(Color.White, colors.a, 0.35f)
    val likes by History.likes.collectAsState()
    val liked = t != null && likes.contains(History.keyOf(t.title, t.artist))
    val pos = rememberLivePosition(s, intervalMs = 250)
    val duration = t?.durationMs ?: 0L
    var dragPos by remember { mutableStateOf<Float?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    val haptic = rememberHaptic(strong = true)
    val liquid = liquidOn()
    // ce que le panneau de verre floute : le fond et la pochette
    val panelGlass = rememberBackdrop()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(panelGlass)) {
            if (liquid) Ambient(colors, strength = 1f, animated = true)
            // pochette bord à bord, fondue vers le bas ; glisser = son suivant / précédent
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.66f)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -120f) { haptic(); TidalRemote.next() } else if (total > 120f) { haptic(); TidalRemote.previous() }
                            },
                        ) { _, d -> total += d }
                    },
            ) {
                Crossfade(t?.art, animationSpec = SongChange.spec(), label = "mixArt") { art ->
                    if (art != null) Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }

        // le geste caché du lecteur, expliqué une fois
        HintBubble("swipeCover", "Glisse la pochette vers la gauche ou la droite pour changer de son. Appui long sur le titre pour plus d'options.", Modifier.align(Alignment.Center).padding(horizontal = 24.dp))

        // barre du haut
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Drop(44.dp, "Réduire le lecteur", onClick = onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Txt, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("EN LECTURE", color = Txt.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                val album = t?.album.orEmpty()
                if (album.isNotBlank()) Text(album, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                var menu by remember { mutableStateOf(false) }
                val sheetGlass = LocalSheetGlass.current
                var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
                val menuContent: @Composable (close: () -> Unit) -> Unit = { closeMenu -> PlayerMoreMenu(t, ctx, closeMenu) }
                Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
                    Drop(44.dp, "Plus d'options", onClick = {
                        if (sheetGlass != null) { PlayerOverlay.menuAnchor = anchor; PlayerOverlay.menu = menuContent } else menu = true
                    }) {
                        Icon(Icons.Rounded.MoreHoriz, contentDescription = null, tint = Txt, modifier = Modifier.size(22.dp))
                    }
                }
                DropdownMenu(
                    expanded = menu && sheetGlass == null,
                    onDismissRequest = { menu = false },
                    shape = Rounded(18),
                    containerColor = Color(0xFF1C1C24),
                ) { menuContent { menu = false } }
            }
        }

        // le panneau de verre
        val panel = Rounded(40)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
                .shadow(30.dp, panel, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(panel),
        ) {
            if (liquid) {
                LiquidGlass(panelGlass, panel, cornerRadius = 40.dp, modifier = Modifier.matchParentSize(), blur = 22.dp, depth = 22.dp, refraction = 14.dp, chroma = 0.2f, spec = 1f)
                Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.04f)))
            } else {
                Box(Modifier.matchParentSize().background(Color(0xFF14080F).copy(alpha = 0.55f)))
            }
            Box(
                Modifier.matchParentSize()
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.4f to Color.White.copy(alpha = 0.03f), 1f to tint.copy(alpha = 0.10f)))
                    .border(1.dp, rimBrush(0.85f, 0.35f), panel),
            )
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)) {
                // titre + j'aime
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t?.title ?: "—", color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).bounceClick(pressedScale = 0.97f, onLongClick = { t?.let { TrackMenu.open(TrackMenu.Target(it.title, it.artist, it.art, album = it.album)) } }) {
                                    val ar = t?.artist.orEmpty(); val al = t?.album.orEmpty()
                                    when {
                                        al.isNotBlank() -> openDetail(Detail.AlbumByName(al, ar))
                                        ar.isNotBlank() -> openDetail(Detail.ArtistByName(ar.split(",", "&").first().trim()))
                                    }
                                },
                            )
                            PlayerCardGem(t?.title.orEmpty(), t?.artist.orEmpty())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val artist = t?.artist.orEmpty()
                            Text(
                                artist, color = Txt.copy(alpha = 0.72f), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).bounceClick(pressedScale = 0.96f) {
                                    if (artist.isNotBlank()) openDetail(Detail.ArtistByName(artist.split(",", "&").first().trim()))
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            QualityBadge(t?.title, t?.artist)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    HeartButton(s.tidalFavorite ?: liked) { t?.let { toggleHeart(s, it) } }
                }

                MixLyrics(s, onLyrics)

                // progression : tube de verre (ou la barre ondulée si le tube est coupé)
                Spacer(Modifier.height(16.dp))
                val progress = dragPos ?: if (duration > 0) pos.toFloat() / duration else 0f
                val onChange: (Float) -> Unit = { dragPos = it }
                val onFinished: (Float) -> Unit = { v -> TidalRemote.seekTo((v * duration).toLong()); dragPos = null }
                if (flags.glassTube) GlassTube(progress, tint, onChange, onFinished, enabled = duration > 0)
                else WavyBar(value = progress, playing = s.isPlaying, onChange = onChange, onFinished = onFinished, enabled = duration > 0)
                val shownPos = dragPos?.let { (it * duration).toLong() } ?: pos
                Row(Modifier.fillMaxWidth()) {
                    Text(fmt(shownPos), color = Txt.copy(alpha = 0.65f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("-" + fmt(duration - shownPos), color = Txt.copy(alpha = 0.65f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                // commandes en gouttes
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Drop(62.dp, "Précédent", onClick = { TidalRemote.previous() }) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = Txt, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(24.dp))
                    MilkPlay(s.isPlaying, colors)
                    Spacer(Modifier.width(24.dp))
                    Drop(62.dp, "Suivant", onClick = { TidalRemote.next() }) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Txt, modifier = Modifier.size(32.dp))
                    }
                }

                // outils : une rainure, l'outil actif devient une goutte
                Spacer(Modifier.height(16.dp))
                val groove = PillShape
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clip(groove)
                        .background(Color.Black.copy(alpha = 0.22f))
                        .border(1.dp, Color.White.copy(alpha = 0.07f), groove)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tool(Icons.Rounded.ChatBubbleOutline, "Paroles", active = false, onClick = onLyrics)
                    Tool(Icons.Rounded.Shuffle, if (s.shuffle) "Désactiver l'aléatoire" else "Lecture aléatoire", active = s.shuffle) { TidalRemote.toggleShuffle() }
                    if (flags.ratings) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QuickRateStar(t?.title, t?.artist) }
                    if (flags.bass) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { BassButton() }
                    Tool(Icons.AutoMirrored.Rounded.QueueMusic, "File d'attente", active = showQueue) { showQueue = !showQueue }
                    if (flags.deviceChip) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { DeviceButton() }
                }
            }
        }
    }
    if (showQueue) QueueOverlay(s, onLyrics = { showQueue = false; onLyrics() }) { showQueue = false }
}

/** Liseré de verre : clair en haut à gauche, plus discret en bas à droite. */
private fun rimBrush(top: Float, bottom: Float) = Brush.linearGradient(
    0f to Color.White.copy(alpha = top),
    0.3f to Color.White.copy(alpha = 0.10f),
    0.6f to Color.White.copy(alpha = 0.02f),
    1f to Color.White.copy(alpha = bottom),
)

/** Une goutte de verre bombée (reflet en haut à gauche, liseré lumineux). */
@Composable
private fun Drop(size: Dp, label: String, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val haptic = rememberHaptic()
    val shape = PillShape
    val bd = LocalPlayerGlass.current
    Box(
        Modifier.size(size).shadow(size * 0.22f, shape).clip(shape)
            .bounceClick(pressedScale = 0.88f) { haptic(); onClick() }
            .androidSemantics(label),
        contentAlignment = Alignment.Center,
    ) {
        if (bd != null) LiquidGlass(bd, shape, cornerRadius = size / 2, modifier = Modifier.matchParentSize(), blur = 2.dp, depth = size * 0.3f, refraction = size * 0.18f, chroma = 0.28f, zoom = 1.08f, spec = 1.15f)
        Box(
            Modifier.matchParentSize()
                .drawBehind {
                    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.12f)), center = Offset(this.size.width * 0.3f, this.size.height * 0.2f), radius = this.size.width * 1.1f))
                    // petit reflet en croissant
                    drawOval(Color.White.copy(alpha = 0.22f), topLeft = Offset(this.size.width * 0.22f, this.size.height * 0.08f), size = Size(this.size.width * 0.5f, this.size.height * 0.22f))
                }
                .border(1.dp, rimBrush(0.9f, 0.5f), shape),
        )
        content()
    }
}

/** Le gros bouton lecture : une goutte de « lait » teintée par la pochette. */
@Composable
private fun MilkPlay(playing: Boolean, colors: CoverColors) {
    val haptic = rememberHaptic()
    val shape = PillShape
    val milk = lerp(Color.White, colors.a, 0.22f)
    val ink = lerp(colors.b, Color.Black, 0.55f)
    Box(
        Modifier.size(88.dp)
            .shadow(24.dp, shape, ambientColor = colors.a, spotColor = colors.a)
            .clip(shape)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Color.White, lerp(Color.White, milk, 0.5f), milk), center = Offset(size.width * 0.32f, size.height * 0.22f), radius = size.width * 0.95f))
                // ombre interne en bas, teintée
                drawRect(Brush.verticalGradient(0.6f to Color.Transparent, 1f to colors.a.copy(alpha = 0.28f)))
            }
            .border(1.dp, Color.White.copy(alpha = 0.9f), shape)
            .bounceClick(pressedScale = 0.84f) { haptic(); TidalRemote.playPause() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                    (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(120)))
            },
            label = "milkPlay",
        ) { p ->
            Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (p) "Pause" else "Lecture", tint = ink, modifier = Modifier.size(42.dp))
        }
    }
}

/** Un outil de la rainure ; actif = petite goutte. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Tool(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val shape = PillShape
    val on by animateFloatAsState(if (active) 1f else 0f, tween(220), label = "tool")
    Box(
        Modifier.weight(1f).height(40.dp).clip(shape)
            .drawBehind {
                if (on > 0.01f) drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.36f * on), Color.White.copy(alpha = 0.08f * on), Color.White.copy(alpha = 0.14f * on)), center = Offset(size.width * 0.3f, size.height * 0.15f), radius = size.width))
            }
            .border(1.dp, if (active) rimBrush(0.9f, 0.45f) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)), shape)
            .bounceClick(pressedScale = 0.9f) { haptic(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Txt.copy(alpha = 0.72f + 0.28f * on), modifier = Modifier.size(22.dp))
    }
}

/** Barre de progression en tube de verre rempli de liquide. */
@Composable
private fun GlassTube(value: Float, tint: Color, onChange: (Float) -> Unit, onFinished: (Float) -> Unit, enabled: Boolean) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val shown = (drag ?: value).coerceIn(0f, 1f)
    val grow by animateFloatAsState(if (drag != null) 1.35f else 1f, label = "tubeGrow")
    Box(
        Modifier.fillMaxWidth().height(30.dp).then(
            if (!enabled) Modifier else Modifier
                .pointerInput(Unit) { detectTapGestures { o -> finished((o.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { finished(it) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { c, _ -> val v = (c.position.x / size.width).coerceIn(0f, 1f); drag = v; change(v) }
                },
        ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = PillShape
        Box(
            Modifier.fillMaxWidth().height(15.dp * grow).clip(shape)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.24f), Color.White.copy(alpha = 0.05f))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.32f))), shape)
                .padding(2.dp),
        ) {
            if (shown > 0.005f) Box(
                Modifier.fillMaxHeight().fillMaxWidth(shown).clip(shape)
                    .drawBehind {
                        drawRect(Brush.verticalGradient(listOf(Color.White, lerp(Color.White, tint, 0.5f), tint)))
                        // reflet en haut du liquide
                        val r = size.height * 0.15f
                        drawRoundRect(Color.White.copy(alpha = 0.95f), topLeft = Offset(size.height * 0.4f, size.height * 0.1f), size = Size((size.width - size.height).coerceAtLeast(0f), size.height * 0.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
                        // ménisque : le bout du liquide un peu plus foncé
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, tint.copy(alpha = 0.5f)), startX = size.width - size.height * 1.4f, endX = size.width))
                    },
            )
        }
    }
}

/** Les paroles en direct dans le panneau ; toucher pour les ouvrir en grand. */
@Composable
private fun MixLyrics(s: TidalRemote.State, onOpen: () -> Unit) {
    val t = s.track ?: return
    var lyrics by remember(t.title, t.artist) { mutableStateOf<com.theo.remote.Lyrics.Result?>(null) }
    LaunchedEffect(t.title, t.artist) { lyrics = com.theo.remote.Lyrics.fetch(t.title, t.artist, t.album, t.durationMs) }
    val lines = lyrics?.synced?.filter { it.text.isNotBlank() } ?: return
    if (lines.isEmpty()) return
    val pos = rememberLivePosition(s, intervalMs = 400)
    val i = lines.indexOfLast { it.timeMs <= pos + 250 }.coerceAtLeast(0)
    val current = lines[i].text
    val next = lines.getOrNull(i + 1)?.text.orEmpty()
    Spacer(Modifier.height(16.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.98f, onClick = onOpen)) {
        AnimatedContent(
            targetState = current to next,
            transitionSpec = {
                (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                    (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
            },
            label = "mixLyrics",
        ) { (a, b) ->
            Column {
                Text(a, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (b.isNotBlank()) Text(b, color = Txt.copy(alpha = 0.42f), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun Modifier.androidSemantics(label: String): Modifier =
    this.semantics { contentDescription = label }
