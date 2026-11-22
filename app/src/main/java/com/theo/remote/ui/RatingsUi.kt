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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.theo.remote.Ratings
import com.theo.remote.TidalRemote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- étoiles

/** Une étoile remplie à [fill] (0, 0.5 ou 1). */
@Composable
private fun StarIcon(fill: Float, size: Dp, color: Color) {
    Box(Modifier.size(size)) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = Color.White.copy(alpha = 0.16f), modifier = Modifier.size(size))
        if (fill > 0f) Icon(
            Icons.Rounded.Star, contentDescription = null, tint = color,
            modifier = Modifier.size(size).drawWithContent { clipRect(right = this.size.width * fill) { this@drawWithContent.drawContent() } },
        )
    }
}

/** Note en lecture seule. */
@Composable
fun Stars(stars: Float, size: Dp = 14.dp, color: Color = Txt) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 1..5) StarIcon((stars - (i - 1)).coerceIn(0f, 1f).let { if (it in 0.01f..0.99f) 0.5f else it }, size, color)
    }
}

/** Étoiles à toucher : moitié gauche = demi-étoile, moitié droite = étoile pleine. */
@Composable
private fun StarPicker(stars: Float, color: Color, onChange: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..5) {
            val fill = (stars - (i - 1)).coerceIn(0f, 1f)
            Box(
                Modifier.size(44.dp).pointerInput(i) {
                    detectTapGestures { o -> onChange(if (o.x < size.width / 2f) i - 0.5f else i.toFloat()) }
                },
                contentAlignment = Alignment.Center,
            ) { StarIcon(fill, 38.dp, color) }
        }
    }
}

fun starsLabel(s: Float): String = (if (s % 1f == 0f) s.toInt().toString() else s.toString().replace('.', ',')) + "/5"

/** Petite note à côté d'un son dans les listes (rien si pas noté ou option coupée). */
@Composable
fun RatingBadge(title: String, artist: String, color: Color = Txt2) {
    // le rang de la carte du son (losange), puis la note
    CardRankBadge(title, artist)
    RatingStars(title, artist, color)
}

@Composable
private fun RatingStars(title: String, artist: String, color: Color) {
    val flags by com.theo.remote.Features.flags.collectAsState()
    if (!flags.ratings || !flags.ratingsEverywhere) return
    val items by Ratings.items.collectAsState()
    val e = remember(items, title, artist) { Ratings.track(title, artist) } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Icon(Icons.Rounded.Star, contentDescription = "Ta note", tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(2.dp))
        Text(starsLabel(e.stars).removeSuffix("/5"), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- noter

@Composable
fun RateDialog() {
    val t by Ratings.editing.collectAsState()
    val target = t ?: return
    val old = remember(target) { Ratings.get(target.kind, target.title, target.artist) }
    var stars by remember(target) { mutableFloatStateOf(old?.stars ?: 0f) }
    var text by remember(target) { mutableStateOf(old?.review.orEmpty()) }
    fun close() { Ratings.editing.value = null }
    androidx.activity.compose.BackHandler { close() }
    // fenêtre dans l'appli (plus une fenêtre Android à part) : une plaque de verre qui flotte en bas
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).bounceClick(pressedScale = 1f) { close() })
        GlassPlate(
            Rounded(30), 30.dp,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(10.dp).fillMaxWidth(),
        ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(model = target.cover, radius = 8.dp, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (target.kind == Ratings.Kind.Album) "Noter l'album" else "Noter le son", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(target.title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(target.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                StarPicker(stars, Txt) { stars = it }
                Text(if (stars == 0f) "Touche une étoile" else starsLabel(stars), color = if (stars == 0f) Txt3 else Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)).padding(12.dp),
            ) {
                if (text.isEmpty()) Text("Ton avis en une phrase (facultatif)", color = Txt3, fontSize = 15.sp)
                BasicTextField(
                    value = text, onValueChange = { text = it.replace("\n", " ").take(280) },
                    textStyle = TextStyle(color = Txt, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Txt),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Garder") {
                    if (stars > 0f) {
                        Ratings.set(target, stars, text); close()
                        TidalRemote.showToast("Noté ${starsLabel(stars)}")
                    } else TidalRemote.showToast("Choisis d'abord une note")
                }
                if (old != null) PillButton("Effacer", filled = false) { Ratings.remove(target); close() }
                else PillButton("Annuler", filled = false) { close() }
            }
        }
        }
    }
}

// ---------------------------------------------------------------- sur la page album

@Composable
fun AlbumRatingCard(title: String, artist: String, cover: String?, accent: Color, modifier: Modifier = Modifier) {
    if (!com.theo.remote.Features.flags.collectAsState().value.ratings || title.isBlank()) return
    val items by Ratings.items.collectAsState()
    val e = items[Ratings.keyOf(Ratings.Kind.Album, title, artist)]
    Column(
        modifier.fillMaxWidth().clip(Rounded(14)).background(Surface2)
            .bounceClick(pressedScale = 0.98f) { Ratings.edit(Ratings.Kind.Album, title, artist, cover) }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (e == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Noter l'album", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Stars(0f, 18.dp, accent)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ta note", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Stars(e.stars, 18.dp, accent)
            }
            if (e.review.isNotBlank()) Text(e.review, color = Txt2, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

// ---------------------------------------------------------------- dans la biblio

@Composable
fun RatingsLibRow() {
    if (!com.theo.remote.Features.flags.collectAsState().value.ratings) return
    val items by Ratings.items.collectAsState()
    val albums = items.values.count { it.kind == Ratings.Kind.Album }
    val tracks = items.size - albums
    Row(
        Modifier.fillMaxWidth().clip(Rounded(14)).background(Surface2)
            .bounceClick(pressedScale = 0.97f) { Panels.show(Panels.Kind.Ratings) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = Txt, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Mes notes", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (items.isEmpty()) "Note tes albums et tes sons sur 5"
                else "$albums album${if (albums > 1) "s" else ""} · $tracks son${if (tracks > 1) "s" else ""}",
                color = Txt2, fontSize = 13.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- page « Mes notes »

@Composable
fun RatingsPanel() {
    val items by Ratings.items.collectAsState()
    val open = LocalOpenDetail.current
    var tab by remember { mutableIntStateOf(0) }      // 0 albums, 1 sons
    var sort by remember { mutableIntStateOf(0) }     // 0 note, 1 date
    val kind = if (tab == 0) Ratings.Kind.Album else Ratings.Kind.Track
    val list = remember(items, tab, sort) {
        val l = items.values.filter { it.kind == kind }
        if (sort == 0) l.sortedWith(compareByDescending<Ratings.Entry> { it.stars }.thenByDescending { it.at })
        else l.sortedByDescending { it.at }
    }
    val state = rememberLazyListState()
    DismissWhenAtTop(state)
    val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.FRANCE) }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = state,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            PanelTitle("Mes notes")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Albums", tab == 0) { tab = 0 }
                Chip("Sons", tab == 1) { tab = 1 }
                Spacer(Modifier.weight(1f))
                Chip("Note", sort == 0) { sort = 0 }
                Chip("Date", sort == 1) { sort = 1 }
            }
            Spacer(Modifier.height(Space.m))
        }
        if (list.isEmpty()) item {
            EmptyState(
                if (tab == 0) "Aucun album noté" else "Aucun son noté",
                if (tab == 0) "Ouvre un album et touche « Noter l'album »." else "Appui long sur un son, ou menu ⋯ du lecteur, puis « Noter ce son ».",
            )
        }
        items(list, key = { it.key }) { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .bounceClick(pressedScale = 0.98f, onLongClick = { Ratings.edit(e.kind, e.title, e.artist, e.cover) }) {
                        if (e.kind == Ratings.Kind.Album) { Panels.closeAll(); open(Detail.AlbumByName(e.title, e.artist)) }
                        else TidalRemote.playTitle(e.title, e.artist)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Cover(model = e.cover, radius = 6.dp, modifier = Modifier.size(if (e.kind == Ratings.Kind.Album) 64.dp else 52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(e.title, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stars(e.stars, 14.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(fmt.format(Date(e.at)), color = Txt3, fontSize = 12.sp)
                    }
                    if (e.review.isNotBlank()) Text(e.review, color = Txt2, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (list.isNotEmpty()) item {
            Text("Appui long pour modifier une note", color = Txt3, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Txt else Color.White.copy(alpha = 0.08f))
            .bounceClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ---------------------------------------------------------------- étoile rapide du lecteur

/**
 * Étoile à côté de « Paroles » : une touche ouvre la fenêtre de note ;
 * on reste appuyé → 5 étoiles verticales, on glisse vers le haut pour noter, on lâche pour garder.
 */
@Composable
fun QuickRateStar(title: String?, artist: String?) {
    if (title.isNullOrBlank()) return
    val a = artist.orEmpty()
    val items by Ratings.items.collectAsState()
    val current = items[Ratings.keyOf(Ratings.Kind.Track, title, a)]
    var holding by remember { mutableStateOf(false) }
    var value by remember { mutableIntStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val step = with(density) { 40.dp.toPx() }
    val dead = with(density) { 14.dp.toPx() }
    // dans le lecteur avec le verre liquide : les étoiles s'affichent en verre par-dessus le lecteur
    val sheetGlass = LocalSheetGlass.current
    val glassStars = sheetGlass != null
    if (glassStars) androidx.compose.runtime.DisposableEffect(Unit) { onDispose { PlayerOverlay.stars = null } }

    Box(
        Modifier.size(48.dp)
            .onGloballyPositioned { PlayerOverlay.starsAnchor = it.boundsInRoot() }
            .clip(PillShape)
            .pointerInput(title, a, glassStars) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var cancelled = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        val u = waitForUpOrCancellation()
                        if (u == null) cancelled = true
                        u
                    }
                    if (cancelled) return@awaitEachGesture
                    if (up != null) {
                        // simple touche : la fenêtre complète (avec la phrase d'avis)
                        Ratings.edit(Ratings.Kind.Track, title, a)
                        return@awaitEachGesture
                    }
                    holding = true
                    value = 0
                    // la note est poussée directement au menu en verre (pas besoin d'attendre l'écran)
                    if (glassStars) PlayerOverlay.stars = 0
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    val startY = down.position.y
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        val upBy = startY - ch.position.y
                        val v = if (upBy < dead) 0 else (((upBy - dead) / step).toInt() + 1).coerceIn(1, 5)
                        if (v != value) {
                            value = v
                            if (glassStars) PlayerOverlay.stars = v
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    }
                    holding = false
                    if (glassStars) PlayerOverlay.stars = null
                    if (value > 0) {
                        Ratings.set(Ratings.Target(Ratings.Kind.Track, title, a, null), value.toFloat(), current?.review.orEmpty())
                        TidalRemote.showToast("Noté ${value}/5")
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (current != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (current != null) "Ta note : ${starsLabel(current.stars)}" else "Noter ce son",
            tint = if (current != null) Txt else Txt.copy(alpha = 0.75f), modifier = Modifier.size(26.dp),
        )
        if (holding && sheetGlass == null) {
            val lift = with(density) { 56.dp.roundToPx() }
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, -lift),
            ) {
                Column(
                    Modifier.clip(Rounded(24)).background(Surface2).padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(if (value == 0) "Glisse" else "$value/5", color = if (value == 0) Txt3 else Txt, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    for (i in 5 downTo 1) {
                        val on = i <= value
                        val sc by androidx.compose.animation.core.animateFloatAsState(if (i == value) 1.25f else 1f, label = "qs$i")
                        Icon(
                            Icons.Rounded.Star, contentDescription = null,
                            tint = if (on) Txt else Color.White.copy(alpha = 0.16f),
                            modifier = Modifier.size(34.dp).graphicsLayer { scaleX = sc; scaleY = sc },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- « sons à noter »

/** À la fin d'un son souvent écouté mais pas encore noté, une petite bulle propose de le noter. */
object RatePrompt {
    data class Ask(val title: String, val artist: String, val plays: Int)
    val ask = kotlinx.coroutines.flow.MutableStateFlow<Ask?>(null)
    private var prev: Triple<String, String, Long>? = null
    private var lastAsk = 0L
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("hush_rate_prompt", android.content.Context.MODE_PRIVATE)
    }

    /** Appelé à chaque changement de son. */
    fun onTrackChange(title: String, artist: String) {
        val now = System.currentTimeMillis()
        val p = prev
        prev = Triple(title, artist, now)
        if (p == null) return
        val f = com.theo.remote.Features.flags.value
        if (!f.ratings || !f.ratePrompt) return
        val (t, a, start) = p
        if (now - start < 60_000) return // passé trop vite : pas un son qu'on a écouté
        if (now - lastAsk < 20 * 60_000) return // pas plus d'une bulle toutes les 20 min
        if (Ratings.track(t, a) != null) return
        val key = com.theo.remote.History.keyOf(t, a)
        val pr = prefs
        if (pr?.getBoolean(key, false) == true) return // déjà proposé une fois
        val plays = com.theo.remote.History.entries.value.firstOrNull { it.key == key }?.plays?.size ?: 0
        if (plays < 3) return
        lastAsk = now
        pr?.edit()?.putBoolean(key, true)?.apply()
        ask.value = Ask(t, a, plays)
    }
}

@Composable
fun RatePromptBubble() {
    val a by RatePrompt.ask.collectAsState()
    var last by remember { mutableStateOf<RatePrompt.Ask?>(null) }
    a?.let { last = it }
    LaunchedEffect(a) { if (a != null) { kotlinx.coroutines.delay(12_000); if (RatePrompt.ask.value == a) RatePrompt.ask.value = null } }
    androidx.compose.animation.AnimatedVisibility(
        visible = a != null,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val x = last ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = Space.gutter).padding(bottom = 8.dp).fillMaxWidth().clip(Rounded(14)).background(Surface2).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tu l'as écouté ${x.plays} fois", color = Txt2, fontSize = 12.sp)
                Text(x.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp)) {
                    for (i in 1..5) Box(
                        Modifier.size(34.dp).clip(PillShape).bounceClick {
                            Ratings.set(Ratings.Target(Ratings.Kind.Track, x.title, x.artist, null), i.toFloat(), "")
                            TidalRemote.showToast("Noté $i/5")
                            RatePrompt.ask.value = null
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.StarBorder, contentDescription = "$i étoile${if (i > 1) "s" else ""}", tint = Txt, modifier = Modifier.size(26.dp)) }
                }
            }
            Box(
                Modifier.size(40.dp).clip(PillShape).bounceClick { RatePrompt.ask.value = null },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, contentDescription = "Plus tard", tint = Txt2, modifier = Modifier.size(20.dp)) }
        }
    }
}
