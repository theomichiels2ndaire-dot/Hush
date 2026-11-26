package com.theo.remote.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Tout ce qu'il faut pour raconter un mois. */
private data class Recap(
    val month: String,
    val stats: Stats,
    val prevMinutes: Long,
    val artistShare: Int,
    /** récap d'une année entière (sinon d'un mois) */
    val year: Boolean = false,
) {
    val unit get() = if (year) "année" else "mois"
    val of get() = if (year) month else ofMonth(month)
}

private val Palettes = listOf(
    listOf(Color(0xFF3B1FBF), Color(0xFFE0458B)),
    listOf(Color(0xFF0B6E66), Color(0xFF2F5FE0)),
    listOf(Color(0xFFB2401F), Color(0xFF5B1A8C)),
    listOf(Color(0xFF14213D), Color(0xFF0E8C7F)),
    listOf(Color(0xFF8C1A4A), Color(0xFFF08A24)),
    listOf(Color(0xFF1B2B8C), Color(0xFF7A2FE0)),
    listOf(Color(0xFF111118), Color(0xFF3B1FBF)),
)

private const val SLIDES = 7
private const val SLIDE_MS = 6500

@Composable
fun WrappedScreen(events: List<Ev>, offset: Int, onClose: () -> Unit, year: Int? = null) {
    val recap by produceState<Recap?>(null, events, offset, year) {
        value = withContext(Dispatchers.Default) {
            if (year != null) {
                fun start(y: Int) = java.util.Calendar.getInstance().apply { clear(); set(y, java.util.Calendar.JANUARY, 1) }.timeInMillis
                val st = computeStats(events, start(year), start(year + 1))
                val prev = events.filter { it.ts in start(year - 1) until start(year) }.sumOf { it.durMs } / 60_000
                val share = st.topArtists.firstOrNull()?.let { (it.second * 100f / st.plays.coerceAtLeast(1)).toInt() } ?: 0
                return@withContext Recap(year.toString(), st, prev, share, year = true)
            }
            val (a, b) = monthRange(offset)
            val (pa, pb) = monthRange(offset + 1)
            val st = computeStats(events, a, b)
            val prev = events.filter { it.ts in pa until pb }.sumOf { it.durMs } / 60_000
            val share = st.topArtists.firstOrNull()?.let { (it.second * 100f / st.plays.coerceAtLeast(1)).toInt() } ?: 0
            Recap(monthName(offset), st, prev, share)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val r = recap
        if (r == null) {
            Box(Modifier.fillMaxSize().background(Ink))
            return@Dialog
        }
        Stories(r, onClose)
    }
}

@Composable
private fun Stories(r: Recap, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val haptic = rememberHaptic()

    // avance tout seul, comme une story
    val lastIndex = remember { intArrayOf(-1) }
    LaunchedEffect(index, paused) {
        if (lastIndex[0] != index) { progress.snapTo(0f); lastIndex[0] = index }
        if (paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * SLIDE_MS).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (index < SLIDES - 1) index++
    }
    fun go(to: Int) {
        if (to < 0) { scope.launch { progress.snapTo(0f) }; return }
        if (to >= SLIDES) { onClose(); return }
        haptic()
        index = to
    }

    val pal = Palettes[index % Palettes.size]
    val c1 by animateColorAsState(pal[0], tween(700), label = "w1")
    val c2 by animateColorAsState(pal[1], tween(700), label = "w2")

    Box(Modifier.fillMaxSize().background(Ink)) {
        // la partie capturée pour le partage
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .background(Brush.linearGradient(listOf(c1, c2), start = Offset.Zero, end = Offset(900f, 2200f)))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            paused = true
                            tryAwaitRelease()
                            paused = false
                        },
                        onTap = { o -> if (o.x < size.width * 0.3f) go(index - 1) else go(index + 1) },
                    )
                },
        ) {
            BlobBackdrop()
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    (fadeIn(tween(380)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 260f)) { it / 8 } + scaleIn(tween(420), initialScale = 0.96f)) togetherWith
                        fadeOut(tween(200))
                },
                label = "slide",
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(top = 56.dp, bottom = 96.dp, start = 28.dp, end = 28.dp),
            ) { i ->
                Slide(i, r)
            }
            Text(
                "Hush",
                color = Txt.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.BottomStart).systemBarsPadding().padding(start = 28.dp, bottom = 36.dp),
            )
        }

        // barres de progression + fermer
        Column(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(SLIDES) { i ->
                    val p = when {
                        i < index -> 1f
                        i == index -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(PillShape).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(p).height(3.dp).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Récap ${r.of}", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(36.dp).clip(PillShape).bounceClick(pressedScale = 0.85f, onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = Txt, modifier = Modifier.size(24.dp))
                }
            }
        }

        // partager en story
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .systemBarsPadding()
                .padding(end = 20.dp, bottom = 24.dp)
                .clip(PillShape)
                .background(Color.White)
                .bounceClick(pressedScale = 0.92f) {
                    paused = true
                    scope.launch {
                        runCatching { share(ctx, layer.toImageBitmap().asAndroidBitmap(), r.month) }
                        paused = false
                    }
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.IosShare, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Partager", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** Taches de couleur qui bougent doucement derrière le texte. */
@Composable
private fun BoxScope.BlobBackdrop() {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            t.animateTo(1f, tween(9000, easing = LinearEasing))
            t.snapTo(0f)
        }
    }
    Canvas(Modifier.matchParentSize()) {
        val a = t.value * 2 * Math.PI
        val r = size.minDimension
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * (0.3f + 0.2f * kotlin.math.cos(a).toFloat()), size.height * (0.25f + 0.08f * kotlin.math.sin(a).toFloat())), radius = r * 0.7f),
            radius = r * 0.7f,
            center = Offset(size.width * (0.3f + 0.2f * kotlin.math.cos(a).toFloat()), size.height * (0.25f + 0.08f * kotlin.math.sin(a).toFloat())),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent), center = Offset(size.width * (0.8f - 0.15f * kotlin.math.sin(a).toFloat()), size.height * 0.8f), radius = r * 0.8f),
            radius = r * 0.8f,
            center = Offset(size.width * (0.8f - 0.15f * kotlin.math.sin(a).toFloat()), size.height * 0.8f),
        )
    }
}

@Composable
private fun Slide(i: Int, r: Recap) {
    val st = r.stats
    when (i) {
        0 -> Center {
            Big("Ton ${r.unit} ${r.of}", 44)
            Spacer(Modifier.height(20.dp))
            Small("${st.plays} écoutes, ${st.distinctTracks} sons différents, ${st.distinctArtists} artistes.")
            Spacer(Modifier.height(6.dp))
            Small("On regarde ça ensemble ?")
        }
        1 -> Center {
            Small("Tu as écouté")
            CountUp(st.minutes) { "$it" }
            Big("minutes de musique", 30)
            Spacer(Modifier.height(20.dp))
            val diff = if (r.prevMinutes > 0) ((st.minutes - r.prevMinutes) * 100 / r.prevMinutes).toInt() else null
            Small(
                when {
                    diff == null -> "Soit ${fmtMinutes(st.minutes)} au total."
                    diff >= 0 -> "C'est ${diff} % de plus que ${if (r.year) "l'année" else "le mois"} d'avant."
                    else -> "C'est ${-diff} % de moins que ${if (r.year) "l'année" else "le mois"} d'avant."
                },
            )
        }
        2 -> {
            val top = st.topTracks.firstOrNull()
            Center {
                Small("Ton son n°1")
                Spacer(Modifier.height(20.dp))
                Cover(model = top?.first?.cover, radius = 18.dp, modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(1f).shadow(30.dp, Rounded(18)))
                Spacer(Modifier.height(24.dp))
                Big(top?.first?.title ?: "—", 32, maxLines = 2)
                Small(top?.first?.artist.orEmpty())
                Spacer(Modifier.height(8.dp))
                top?.let { Small("${it.second} écoute${if (it.second > 1) "s" else ""}", bold = true) }
            }
        }
        3 -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Big("Ton top 5", 38)
            Spacer(Modifier.height(24.dp))
            st.topTracks.take(5).forEachIndexed { n, (e, c) -> Stagger(n) { TopLine(n + 1, e.title, e.artist, c, e.cover) } }
        }
        4 -> {
            val top = st.topArtists.firstOrNull()
            Center {
                Small("L'artiste de ton ${r.unit}")
                Spacer(Modifier.height(12.dp))
                Big(top?.first ?: "—", 50, maxLines = 2)
                Spacer(Modifier.height(16.dp))
                top?.let { Small("${it.second} écoutes · ${r.artistShare} % de ton ${r.unit}", bold = true) }
                Spacer(Modifier.height(28.dp))
                st.topArtists.drop(1).take(4).forEachIndexed { n, (a, c) ->
                    Stagger(n) { Small("${n + 2}. $a  ·  $c") }
                }
            }
        }
        5 -> {
            val h = st.perHour.indices.maxByOrNull { st.perHour[it] } ?: 0
            Center {
                Small("Ton heure préférée")
                Big("${h}h", 88)
                Small(
                    when (h) {
                        in 5..11 -> "Musique dès le matin, tu démarres fort."
                        in 12..17 -> "L'après-midi, c'est ton moment."
                        in 18..22 -> "Le soir, tu montes le son."
                        else -> "Oiseau de nuit confirmé."
                    },
                )
                Spacer(Modifier.height(28.dp))
                HourBars(st.perHour)
            }
        }
        else -> Summary(r)
    }
}

/** Dernière carte : le résumé à partager. */
@Composable
private fun Summary(r: Recap) {
    val st = r.stats
    val shape = Rounded(26)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.28f)).glass(shape, fill = 0.06f, edge = 0.2f).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Mon ${r.month} en musique", color = Txt.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Minutes", color = Txt2, fontSize = 12.sp)
                    Text("${st.minutes}", color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Écoutes", color = Txt2, fontSize = 12.sp)
                    Text("${st.plays}", color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Top sons", color = Txt2, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    st.topTracks.take(5).forEachIndexed { n, (e, _) ->
                        Text("${n + 1}. ${e.title}", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Top artistes", color = Txt2, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    st.topArtists.take(5).forEachIndexed { n, (a, _) ->
                        Text("${n + 1}. $a", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            st.topTracks.firstOrNull()?.let { (e, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(model = e.cover, radius = 10.dp, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (r.year) "Son de l'année" else "Son du mois", color = Txt2, fontSize = 12.sp)
                        Text(e.title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- petits morceaux

@Composable
private fun Center(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun Big(text: String, size: Int, maxLines: Int = 3) {
    Text(
        text, color = Txt, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, lineHeight = (size * 1.08f).sp,
        textAlign = TextAlign.Center, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Small(text: String, bold: Boolean = false) {
    Text(text, color = Txt.copy(alpha = 0.85f), fontSize = 17.sp, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 23.sp)
}

/** Le chiffre qui défile jusqu'à sa valeur. */
@Composable
private fun CountUp(target: Long, label: (Long) -> String) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target.toFloat(), tween(1600)) }
    Text(label(a.value.toLong()), color = Txt, fontSize = 92.sp, fontWeight = FontWeight.ExtraBold)
}

/** Fait apparaître les lignes l'une après l'autre. */
@Composable
private fun Stagger(n: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250L + n * 160L); shown = true }
    val p by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 260f), label = "stagger")
    Box(Modifier.graphicsLayer { alpha = p; translationY = (1f - p) * 60f }) { content() }
}

@Composable
private fun TopLine(rank: Int, title: String, artist: String, count: Int, cover: Any?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(34.dp))
        Cover(model = cover, radius = 8.dp, modifier = Modifier.size(56.dp).shadow(12.dp, RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Txt.copy(alpha = 0.75f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count", color = Txt.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HourBars(perHour: IntArray) {
    val max = (perHour.maxOrNull() ?: 0).coerceAtLeast(1)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 120f)) }
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width / 24f
        perHour.forEachIndexed { i, v ->
            val h = (size.height * v / max * grow.value).coerceAtLeast(4f)
            drawRoundRect(
                Color.White.copy(alpha = if (v == max) 1f else 0.45f),
                topLeft = Offset(i * w + w * 0.15f, size.height - h),
                size = Size(w * 0.7f, h),
                cornerRadius = CornerRadius(4f),
            )
        }
    }
}

/** Enregistre l'image et ouvre le partage (Instagram propose « Story »). */
private suspend fun share(ctx: Context, bmp: Bitmap, month: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "hush-recap-$month.png")
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        f.outputStream().use { soft.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".share", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Partager ton récap").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
