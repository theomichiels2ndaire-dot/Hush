package com.theo.remote.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.GraphicEq
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
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

// File d'attente du lecteur : la liste « À suivre », réorganisable et glissable.

@Composable
internal fun QueueOverlay(s: TidalRemote.State, onClose: () -> Unit) {
    val upNext by TidalRemote.upNext.collectAsState()
    val ctxQueue by TidalRemote.context.collectAsState()
    val mixUp by com.theo.remote.MixQueue.upcoming.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scrim by animateFloatAsState(if (visible) 0.45f else 0f, tween(250), label = "scrim")
    fun close() { visible = false }
    LaunchedEffect(visible) { if (!visible) { delay(200); onClose() } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { close() })
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 340f)) { it },
            exit = slideOutVertically(tween(240)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            SwipeDismissible(onDismiss = { close() }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .clip(shape)
                        .background(Color(0xFF15161D).copy(alpha = 0.94f))
                        .glass(shape, fill = 0.04f, edge = 0.12f)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 5.dp).clip(PillShape).background(Color.White.copy(alpha = 0.25f)))
                    Spacer(Modifier.height(16.dp))
                    val qf by com.theo.remote.Features.flags.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("File d'attente", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                        // passer de la liste aux pochettes (et inversement)
                        Box(
                            Modifier.size(40.dp).clip(PillShape).background(Color.White.copy(alpha = 0.08f))
                                .bounceClick { com.theo.remote.Features.set(qf.copy(queueCovers = !qf.queueCovers)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (qf.queueCovers) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                                contentDescription = if (qf.queueCovers) "Afficher en liste" else "Afficher en pochettes",
                                tint = Txt, modifier = Modifier.size(20.dp),
                            )
                        }
                        if (upNext.isNotEmpty() || ctxQueue.isNotEmpty() || mixUp.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            var confirm by remember { mutableStateOf(false) }
                            LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
                            Box(
                                Modifier.height(40.dp).clip(PillShape).background(Color.White.copy(alpha = 0.08f))
                                    .bounceClick { if (confirm) { TidalRemote.clearQueue(); confirm = false } else confirm = true }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (confirm) "Sûr ?" else "Vider", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // pochettes des sons de la file Tidal : donnée par Tidal, sinon retrouvée dans la playlist en cours ou ton historique
                    val nowPl by NowPlaylist.id.collectAsState()
                    val heard by com.theo.remote.History.entries.collectAsState()
                    val coverOf = remember(nowPl, heard) {
                        val m = HashMap<String, String>()
                        heard.forEach { e -> e.coverUrl?.let { m[com.theo.remote.QueueLogic.norm(e.title)] = it } }
                        nowPl?.let { PlaylistFull.inMemory(it) }?.forEach { r -> r.coverUrl?.let { c -> m[com.theo.remote.QueueLogic.norm(r.title)] = c; m[com.theo.remote.QueueLogic.norm(r.fullTitle)] = c } }
                        m
                    }
                    fun tidalCover(q: com.theo.remote.TidalRemote.QueueEntry): String? = q.icon ?: coverOf[com.theo.remote.QueueLogic.norm(q.title)]
                    // la file Tidal à partir du son d'après celui en cours
                    val activeIdx = s.queue.indexOfFirst { it.id == s.activeQueueId }
                    val tidalAhead = s.queue.drop(activeIdx + 1)
                    val nextFromTidal = upNext.isEmpty() && mixUp.isEmpty() && ctxQueue.isEmpty()
                    // le prochain son, mis en avant en grand
                    val nextUp: Triple<String, String, String?>? = upNext.firstOrNull()?.let { Triple(it.fullTitle, it.artistLine, it.coverUrl) }
                        ?: when (val e = mixUp.firstOrNull()) {
                            is com.theo.remote.LocalMusic.Entry.T -> Triple(e.r.fullTitle, e.r.artistLine, e.r.coverUrl)
                            is com.theo.remote.LocalMusic.Entry.L -> Triple(e.t.title, e.t.artist, null)
                            null -> ctxQueue.firstOrNull()?.let { Triple(it.title, it.artist, null) }
                                ?: tidalAhead.firstOrNull()?.let { Triple(it.title, it.subtitle, tidalCover(it)) }
                        }
                    // le son en cours, en petite carte avec sa progression
                    s.track?.let { cur ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(bitmap = cur.art, radius = 10.dp, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("En cours", color = Ok, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(cur.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (cur.artist.isNotBlank()) Text(cur.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    // toute la file défile d'un bloc (À suivre + la suite)
                    val scroll = androidx.compose.foundation.rememberScrollState()
                    DismissWhenAtTop(scroll)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                    if (upNext.isNotEmpty()) {
                        QueueHeader("À suivre", upNext.size)
                        Spacer(Modifier.height(if (qf.queueCovers) 10.dp else 4.dp))
                        if (qf.queueCovers) CoverQueueGrid(upNext) else ReorderableUpNext(upNext)
                        val after = if (mixUp.isNotEmpty()) mixUp.size else if (ctxQueue.isNotEmpty()) ctxQueue.size else tidalAhead.size
                        if (after > 0) {
                            Spacer(Modifier.height(14.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                            Spacer(Modifier.height(14.dp))
                            QueueHeader("Ensuite", after)
                            Spacer(Modifier.height(if (qf.queueCovers) 10.dp else 4.dp))
                        }
                    }
                    if (mixUp.isNotEmpty()) {
                        // playlist lancée par Hush : on montre la vraie suite de la playlist (pas la file interne de Tidal)
                        if (upNext.isEmpty()) {
                            QueueHeader("Ensuite" + if (s.shuffle) " · aléatoire" else "", mixUp.size)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (qf.queueCovers) CoverStaticGrid(mixUp.map { e ->
                            when (e) {
                                is com.theo.remote.LocalMusic.Entry.L -> Triple(e.t.title, e.t.artist, null)
                                is com.theo.remote.LocalMusic.Entry.T -> Triple(e.r.fullTitle, e.r.artistLine, e.r.coverUrl)
                            }
                        }) { i -> com.theo.remote.MixQueue.jumpTo(i) } else {
                        // clé stable (pas la place) : une ligne déplacée garde son état, sans saut
                        val seen = HashMap<String, Int>()
                        ReorderableRows(
                            mixUp.map { e ->
                                val id = when (e) { is com.theo.remote.LocalMusic.Entry.T -> e.r.id; is com.theo.remote.LocalMusic.Entry.L -> "l" + e.t.id }
                                val n = seen.merge(id, 1, Int::plus) ?: 1
                                when (e) {
                                    is com.theo.remote.LocalMusic.Entry.T -> QRow("$id#$n", e.r.fullTitle, e.r.artistLine, e.r.coverUrl, e.r)
                                    is com.theo.remote.LocalMusic.Entry.L -> QRow("$id#$n", e.t.title, "${e.t.artist} · fichier local", e.t.art?.let { java.io.File(it) })
                                }
                            },
                            onMove = { f, t -> com.theo.remote.MixQueue.move(f, t) },
                            onRemove = { k ->
                                val snap = com.theo.remote.MixQueue.snapshot()
                                com.theo.remote.MixQueue.removeAt(k)
                                TidalRemote.offerUndo("Retiré de la suite", "mixRemove") { com.theo.remote.MixQueue.restore(snap) }
                            },
                            onTap = { k -> com.theo.remote.MixQueue.jumpTo(k) },
                        )
                        }
                    } else if (ctxQueue.isNotEmpty()) {
                        // ce que tu écoutais avant que la file passe devant : Hush le reprend ensuite
                        if (upNext.isEmpty()) {
                            QueueHeader("Ensuite", ctxQueue.size)
                            Spacer(Modifier.height(4.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ctxQueue.forEach { c ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Cover(model = coverOf[com.theo.remote.QueueLogic.norm(c.title)], radius = 8.dp, modifier = Modifier.size(44.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (c.artist.isNotBlank()) Text(c.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    } else if (s.queue.isEmpty()) {
                        Text(
                            "Plus rien de prévu après ce son : Hush enchaînera tout seul sur des sons similaires, la musique ne s'arrête jamais.",
                            color = Txt2, fontSize = 13.sp, lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(20.dp))
                    } else {
                        // le premier est déjà en grand au-dessus : la liste commence au suivant
                        val rest = tidalAhead
                        if (upNext.isEmpty() && rest.isNotEmpty()) {
                            QueueHeader("Ensuite", rest.size)
                            Spacer(Modifier.height(if (qf.queueCovers) 10.dp else 4.dp))
                        }
                        if (qf.queueCovers) CoverStaticGrid(rest.map { Triple(it.title, it.subtitle, tidalCover(it)) }) { i -> rest.getOrNull(i)?.let { TidalRemote.skipToQueueItem(it.id) } }
                        else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            rest.forEach { q ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .bounceClick(pressedScale = 0.97f, onLongClick = { TrackMenu.open(TrackMenu.Target(q.title, q.subtitle, tidalCover(q))) }) { TidalRemote.skipToQueueItem(q.id) }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Cover(model = tidalCover(q), radius = 8.dp, modifier = Modifier.size(44.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(q.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (q.subtitle.isNotBlank()) Text(q.subtitle, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    // en bas de la file : remplir avec des sons proches, ou passer aux sons similaires
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val qctx = LocalContext.current
                        val qscope = rememberCoroutineScope()
                        var filling by remember { mutableStateOf(false) }
                        PillButton(if (filling) "Je cherche…" else "+ 10 sons comme ça", filled = false) {
                            val cur = s.track ?: return@PillButton
                            if (filling) return@PillButton
                            filling = true
                            qscope.launch {
                                val got = runCatching {
                                    InstantPlaylist.similar(qctx, cur.title, cur.artist, upNext.map { it.id }.toSet())
                                }.getOrDefault(emptyList())
                                if (got.isNotEmpty()) TidalRemote.replaceQueue(TidalRemote.upNext.value + got)
                                TidalRemote.showToast(if (got.isEmpty()) "Rien de proche dans tes favoris" else "${got.size} sons ajoutés à la file")
                                filling = false
                            }
                        }
                        PillButton("Sons similaires", filled = false) { TidalRemote.playSimilar(); close() }
                    }
                    Spacer(Modifier.height(16.dp))
                    } // fin de la zone qui défile
                }
            }
        }
    }
}


// ---------------------------------------------------------------- file en grandes pochettes

/**
 * « À suivre » en grandes pochettes (3 par ligne) : on maintient une pochette puis on la fait glisser
 * à sa nouvelle place, les autres se poussent ; la petite croix la retire de la file.
 */
@Composable
private fun CoverQueueGrid(list: List<com.theo.remote.TidalApi.Result>) {
    val haptic = rememberHaptic()
    val gate = LocalDismissGate.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = 3
        val gap = 10.dp
        val cw = (maxWidth - gap * (cols - 1)) / cols
        val ch = cw + 42.dp
        val cwPx = with(density) { cw.toPx() }
        val chPx = with(density) { ch.toPx() }
        val gapPx = with(density) { gap.toPx() }
        var dragFrom by remember { mutableIntStateOf(-1) }
        var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        fun baseX(i: Int) = (i % cols) * (cwPx + gapPx)
        fun baseY(i: Int) = (i / cols) * (chPx + gapPx)
        fun targetOf(from: Int, d: androidx.compose.ui.geometry.Offset): Int {
            if (from < 0) return -1
            val x = baseX(from) + d.x + cwPx / 2
            val y = baseY(from) + d.y + chPx / 2
            val c = (x / (cwPx + gapPx)).toInt().coerceIn(0, cols - 1)
            val r = (y / (chPx + gapPx)).toInt().coerceAtLeast(0)
            return (r * cols + c).coerceIn(0, list.lastIndex)
        }
        val target = targetOf(dragFrom, drag)
        val rows = (list.size + cols - 1) / cols
        Box(Modifier.fillMaxWidth().height(ch * rows + gap * (rows - 1).coerceAtLeast(0))) {
            list.forEachIndexed { i, r ->
                androidx.compose.runtime.key(r.id, i) {
                    // place affichée : les autres pochettes se poussent pendant qu'on en déplace une
                    val vis = when {
                        dragFrom < 0 || i == dragFrom -> i
                        i in (dragFrom + 1)..target -> i - 1
                        i in target until dragFrom -> i + 1
                        else -> i
                    }
                    val tx by animateFloatAsState(baseX(vis), spring(dampingRatio = 0.8f, stiffness = 500f), label = "qx")
                    val ty by animateFloatAsState(baseY(vis), spring(dampingRatio = 0.8f, stiffness = 500f), label = "qy")
                    val dragging = i == dragFrom
                    Column(
                        Modifier.width(cw)
                            .zIndex(if (dragging) 2f else 0f)
                            .graphicsLayer {
                                translationX = if (dragging) baseX(i) + drag.x else tx
                                translationY = if (dragging) baseY(i) + drag.y else ty
                                val sc = if (dragging) 1.08f else 1f
                                scaleX = sc; scaleY = sc
                            }
                            .pointerInput(list.size, i) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragFrom = i; drag = androidx.compose.ui.geometry.Offset.Zero; haptic(); gate?.let { it.blocked++ } },
                                    onDragEnd = {
                                        val to = targetOf(dragFrom, drag)
                                        if (to >= 0 && to != dragFrom && dragFrom >= 0) { TidalRemote.moveInQueue(dragFrom, to); haptic() }
                                        dragFrom = -1; drag = androidx.compose.ui.geometry.Offset.Zero
                                        gate?.let { it.blocked-- }
                                    },
                                    onDragCancel = { dragFrom = -1; drag = androidx.compose.ui.geometry.Offset.Zero; gate?.let { it.blocked-- } },
                                ) { c, d -> c.consume(); drag += d }
                            },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).shadow(if (dragging) 18.dp else 6.dp, Rounded(14))) {
                            Cover(model = r.coverUrl, radius = 14.dp, modifier = Modifier.fillMaxSize())
                            // numéro dans la file
                            Box(
                                Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(PillShape).background(Color.Black.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center,
                            ) { Text("${vis + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black) }
                            // retirer de la file
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(30.dp).clip(PillShape)
                                    .bounceClick { TidalRemote.removeFromQueue(i); haptic() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(22.dp).clip(PillShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Retirer de la file", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        Text(r.fullTitle, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.artistLine, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** La suite d'une playlist en pochettes (on touche pour y aller). */
@Composable
private fun CoverStaticGrid(items: List<Triple<String, String, String?>>, onTap: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(3).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEachIndexed { k, (t, a, c) ->
                    val idx = row * 3 + k
                    Column(Modifier.weight(1f).bounceClick(pressedScale = 0.95f) { onTap(idx) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Cover(model = c, radius = 14.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(t, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (a.isNotBlank()) Text(a, color = Txt2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------- file « À suivre » réorganisable

private val UpNextRowH = 58.dp

/**
 * File « À suivre » : on déplace un son en tirant la poignée ≡,
 * et on le retire en le glissant vers la gauche ou la droite.
 */
@Composable
private fun ReorderableUpNext(list: List<com.theo.remote.TidalApi.Result>) {
    ReorderableRows(
        list.map { QRow(it.id, it.fullTitle, it.artistLine, it.coverUrl, it) },
        onMove = { f, t -> TidalRemote.moveInQueue(f, t) },
        onRemove = { i -> TidalRemote.removeFromQueue(i) },
        onTap = null,
    )
}

/** Une ligne de la file (son de « À suivre » ou de la suite). */
internal data class QRow(val key: Any, val title: String, val artist: String, val cover: Any?, val result: com.theo.remote.TidalApi.Result? = null)

/**
 * Liste qu'on réordonne au doigt : maintiens un son (n'importe où sur la ligne, ou la poignée ≡)
 * puis glisse-le à sa place ; glisse-le sur le côté pour le retirer.
 */
@Composable
private fun ReorderableRows(list: List<QRow>, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onTap: ((Int) -> Unit)?) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // hauteur réelle d'une ligne, marges comprises (sinon la place visée dérivait au fil du glisser)
    val rowPx = with(density) { (UpNextRowH + 4.dp).toPx() }
    val gate = LocalDismissGate.current
    val haptic = rememberHaptic()
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val target = if (dragFrom < 0) -1 else (dragFrom + kotlin.math.round(dragY / rowPx).toInt()).coerceIn(0, list.lastIndex)

    Column {
        list.forEachIndexed { i, r ->
            androidx.compose.runtime.key(r.key) {
                // décalage des autres lignes pendant qu'on en déplace une
                val shift = when {
                    dragFrom < 0 || i == dragFrom -> 0f
                    i in (dragFrom + 1)..target -> -rowPx
                    i in target until dragFrom -> rowPx
                    else -> 0f
                }
                val shiftAnim = remember { Animatable(0f) }
                LaunchedEffect(shift, dragFrom) {
                    if (dragFrom < 0) shiftAnim.snapTo(0f) else shiftAnim.animateTo(shift, spring(dampingRatio = 0.85f, stiffness = 700f))
                }
                val dragging = i == dragFrom
                SwipeAwayRow(
                    onRemove = { onRemove(i) },
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            // au lâcher, la liste est déjà dans le nouvel ordre : on ne rejoue pas le décalage
                            translationY = if (dragging) dragY else if (dragFrom < 0) 0f else shiftAnim.value
                            // le son qu'on déplace se soulève : plus grand, ombre portée
                            val sc = if (dragging) 1.06f else 1f
                            scaleX = sc; scaleY = sc
                            shadowElevation = if (dragging) 36f else 0f
                            shape = Rounded(14)
                            clip = false
                        },
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(UpNextRowH)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dragging) Color(0xFF2E2F3D) else Color(0xFF15161D))
                            // maintenir n'importe où sur la ligne puis glisser : on déplace le son
                            .pointerInput(list.size, i) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragFrom = i; dragY = 0f; haptic(); gate?.let { it.blocked++ } },
                                    onDragEnd = {
                                        val to = (dragFrom + kotlin.math.round(dragY / rowPx).toInt()).coerceIn(0, list.lastIndex)
                                        if (to != dragFrom && dragFrom >= 0) { onMove(dragFrom, to); haptic() }
                                        dragFrom = -1; dragY = 0f
                                        gate?.let { it.blocked-- }
                                    },
                                    onDragCancel = { dragFrom = -1; dragY = 0f; gate?.let { it.blocked-- } },
                                ) { c, d -> c.consume(); dragY += d.y }
                            }
                            .then(if (onTap != null) Modifier.bounceClick(pressedScale = 0.98f) { onTap(i) } else Modifier)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(model = r.cover, radius = 8.dp, modifier = Modifier.size(44.dp).shadow(if (dragging) 10.dp else 4.dp, RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.artist.isNotBlank()) Text(r.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        r.result?.let { RatingBadge(it.title, it.artistLine) }
                        // poignée : on la tient et on déplace
                        Box(
                            Modifier.size(44.dp).pointerInput(list.size, i) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    gate?.let { it.blocked++ } // la fenêtre ne se ferme pas pendant qu'on déplace
                                    dragFrom = i
                                    dragY = 0f
                                    haptic()
                                    try {
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!ch.pressed) break
                                            dragY += ch.position.y - ch.previousPosition.y
                                            ch.consume()
                                        }
                                    } finally {
                                        val to = (dragFrom + kotlin.math.round(dragY / rowPx).toInt()).coerceIn(0, list.lastIndex)
                                        if (to != dragFrom && dragFrom >= 0) { onMove(dragFrom, to); haptic() }
                                        dragFrom = -1
                                        dragY = 0f
                                        gate?.let { it.blocked-- }
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.DragHandle, contentDescription = "Déplacer", tint = Txt2, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Titre discret d'une partie de la file, avec le nombre de sons. */
@Composable
private fun QueueHeader(title: String, count: Int?) {
    Text(
        title + if (count != null && count > 0) " · $count" else "",
        color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** Ligne qu'on fait glisser sur le côté pour la retirer (fond rouge + corbeille). */
@Composable
private fun SwipeAwayRow(onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val currentRemove by androidx.compose.runtime.rememberUpdatedState(onRemove)
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = rememberHaptic(strong = true)
    val reveal = (kotlin.math.abs(offset.value) / (width * 0.35f)).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().padding(vertical = 2.dp).onSizeChanged { width = it.width.coerceAtLeast(1) },
    ) {
        Box(
            Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Ko.copy(alpha = 0.25f + 0.6f * reveal)),
            contentAlignment = if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Icon(
                Icons.Rounded.DeleteOutline, contentDescription = null, tint = Txt,
                modifier = Modifier.padding(horizontal = 20.dp).size(22.dp).graphicsLayer { scaleX = 0.7f + 0.3f * reveal; scaleY = 0.7f + 0.3f * reveal },
            )
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .pointerInput(Unit) {
                    // (onRemove relu à chaque fois : après un déplacement, la ligne n'a plus le même numéro)
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (kotlin.math.abs(offset.value) > width * 0.35f) {
                                    haptic()
                                    offset.animateTo(if (offset.value > 0) width.toFloat() else -width.toFloat(), tween(180))
                                    currentRemove()
                                    offset.snapTo(0f)
                                } else {
                                    offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { ch, d ->
                        ch.consume()
                        scope.launch { offset.snapTo(offset.value + d) }
                    }
                },
        ) { content() }
    }
}

/** Carte « À suivre » : le prochain son en grand, aux couleurs de sa pochette. Toucher = y passer. */
@Composable
private fun NextUpCard(title: String, artist: String, cover: String?, onClick: () -> Unit) {
    val cc = rememberUrlCoverColors(cover)
    val shape = Rounded(22)
    Row(
        Modifier.fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = cc.a, spotColor = cc.a)
            .clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(cc.a, Color.Black, 0.3f), androidx.compose.ui.graphics.lerp(cc.b, Color.Black, 0.6f))))
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = cover, radius = 12.dp, modifier = Modifier.size(76.dp).shadow(14.dp, RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("À suivre", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (artist.isNotBlank()) Text(artist, color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
