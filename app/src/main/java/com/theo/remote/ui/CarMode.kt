package com.theo.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mode voiture : grosse pochette, gros boutons, lisible d'un coup d'œil. */
object CarMode {
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown
    fun open() { _shown.value = true }
    fun close() { _shown.value = false }
}

@Composable
fun CarModeScreen(s: TidalRemote.State) {
    val t = s.track
    val colors = rememberCoverColors(t?.art)
    val view = LocalView.current
    val haptic = rememberHaptic(strong = true)
    val carGlass = rememberBackdrop()

    // l'écran reste allumé tant que le mode voiture est ouvert
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { CarMode.close() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // glisser à gauche / à droite n'importe où : son suivant / précédent
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -140f) { haptic(); TidalRemote.next() }
                        else if (total > 140f) { haptic(); TidalRemote.previous() }
                    },
                ) { _, d -> total += d }
            },
    ) {
        val landscape = maxWidth > maxHeight
        // le fond (lumières de la pochette) est ce que les boutons en verre liquide tordent
        Box(Modifier.fillMaxSize().backdropSource(carGlass)) {
            Ambient(colors, strength = 0.95f, modifier = Modifier.fillMaxSize(), animated = true)
            // voile sombre pour que le texte reste lisible en plein soleil comme de nuit
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalCarGlass provides (if (liquidOn()) carGlass else null)) {

        if (landscape) {
            Row(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CarCover(s, colors, Modifier.fillMaxHeight())
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CarTopBar()
                    Spacer(Modifier.weight(1f))
                    CarInfo(s, haptic)
                    Spacer(Modifier.height(18.dp))
                    CarProgress(s)
                    Spacer(Modifier.weight(1f))
                    CarControls(s, haptic, compact = true)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 26.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CarTopBar()
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                    CarCover(s, colors, Modifier.fillMaxHeight())
                }
                CarInfo(s, haptic)
                Spacer(Modifier.height(18.dp))
                CarProgress(s)
                Spacer(Modifier.height(20.dp))
                CarControls(s, haptic, compact = false)
                Spacer(Modifier.height(22.dp))
                CarPill(Icons.Rounded.Radio, "Sons similaires", Modifier.fillMaxWidth()) { haptic(); TidalRemote.playSimilar() }
                Spacer(Modifier.height(6.dp))
            }
        }
        }
    }
}

/** Le fond du mode voiture, pour le verre liquide des boutons (null = verre classique). */
private val LocalCarGlass = androidx.compose.runtime.staticCompositionLocalOf<Backdrop?> { null }

/** Fond d'un bouton du mode voiture : verre liquide si actif, sinon [fallback]. */
@Composable
private fun Modifier.carGlass(shape: androidx.compose.ui.graphics.Shape, fallback: Modifier.() -> Modifier): Modifier =
    if (LocalCarGlass.current != null) this.clip(shape) else this.fallback()

@Composable
private fun CarGlassLayer(shape: androidx.compose.ui.graphics.Shape, corner: androidx.compose.ui.unit.Dp, depth: androidx.compose.ui.unit.Dp, refraction: androidx.compose.ui.unit.Dp) {
    val b = LocalCarGlass.current ?: return
    LiquidGlass(b, shape, cornerRadius = corner, modifier = Modifier.fillMaxSize(), blur = 3.dp, depth = depth, refraction = refraction, chroma = 0.26f, spec = 1.1f)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
}

/** Heure en grand (utile en conduisant) + bouton pour quitter. */
@Composable
private fun CarTopBar() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            SimpleDateFormat("HH:mm", Locale.FRANCE).format(now),
            color = Txt, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(54.dp).carGlass(PillShape) { glass(PillShape, fill = 0.16f) }.bounceClick { CarMode.close() },
            contentAlignment = Alignment.Center,
        ) {
            CarGlassLayer(PillShape, 27.dp, 20.dp, 11.dp)
            Icon(Icons.Rounded.Close, contentDescription = "Quitter le mode voiture", tint = Txt, modifier = Modifier.size(28.dp))
        }
    }
}

/** Pochette en relief, ombre aux couleurs du son ; elle rétrécit un peu quand c'est en pause. */
@Composable
private fun CarCover(s: TidalRemote.State, colors: CoverColors, modifier: Modifier) {
    val playing = rememberSteadyPlaying(s.isPlaying)
    val scale by animateFloatAsState(if (playing) 1f else 0.86f, tween(450), label = "carCoverScale")
    val shape = Rounded(24)
    Box(modifier.widthIn(max = 420.dp).aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
        Crossfade(targetState = s.track?.art, animationSpec = tween(400), label = "carCover") { art ->
            Cover(
                bitmap = art,
                radius = 24.dp,
                modifier = Modifier
                    .fillMaxSize(scale)
                    .shadow(36.dp, shape, ambientColor = colors.a, spotColor = colors.a),
            )
        }
    }
}

/** Titre + artiste à gauche, cœur à droite (comme Apple Music). */
@Composable
private fun CarInfo(s: TidalRemote.State, haptic: () -> Unit) {
    val t = s.track
    val likes by History.likes.collectAsState()
    val liked = s.tidalFavorite ?: (t != null && likes.contains(History.keyOf(t.title, t.artist)))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = (t?.title ?: "Rien en lecture") to (t?.artist ?: "Lance un son dans Tidal"),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "carTitle",
            modifier = Modifier.weight(1f),
        ) { (title, artist) ->
            Column {
                Text(title, color = Txt, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(artist, color = Color.White.copy(alpha = 0.66f), fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.size(62.dp).carGlass(PillShape) { glass(PillShape, fill = 0.16f) }.bounceClick(pressedScale = 0.88f) { haptic(); t?.let { toggleHeart(s, it) } },
            contentAlignment = Alignment.Center,
        ) {
            CarGlassLayer(PillShape, 31.dp, 22.dp, 12.dp)
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (liked) "Retirer des favoris" else "J'aime",
                tint = if (liked) Color(0xFFFF4D6D) else Txt,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun CarProgress(s: TidalRemote.State) {
    val t = s.track
    val pos = rememberLivePosition(s, intervalMs = 1000)
    val dur = t?.durationMs ?: 0L
    val progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val p by animateFloatAsState(progress, tween(900), label = "carProgress")
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(PillShape).background(Color.White.copy(alpha = 0.92f)))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("-" + fmt((dur - pos).coerceAtLeast(0)), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Boutons nus façon Apple Music, mais avec de très grandes zones de toucher. */
@Composable
private fun CarControls(s: TidalRemote.State, haptic: () -> Unit, compact: Boolean) {
    val side = if (compact) 92.dp else 108.dp
    val main = if (compact) 120.dp else 140.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        CarIcon(Icons.Rounded.SkipPrevious, "Précédent", side, 0.66f) { haptic(); TidalRemote.previous() }
        CarIcon(
            if (rememberSteadyPlaying(s.isPlaying)) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            "Lecture / pause", main, 0.78f,
        ) { haptic(); TidalRemote.playPause() }
        CarIcon(Icons.Rounded.SkipNext, "Suivant", side, 0.66f) { haptic(); TidalRemote.next() }
    }
}

@Composable
private fun CarIcon(icon: ImageVector, label: String, size: Dp, ratio: Float, onClick: () -> Unit) {
    val liquid = LocalCarGlass.current != null
    Box(
        Modifier.size(size).clip(PillShape).bounceClick(pressedScale = 0.86f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // verre liquide : de grosses gouttes de verre ; l'icône un peu plus petite à l'intérieur
        CarGlassLayer(PillShape, size / 2, size * 0.34f, size * 0.16f)
        Icon(icon, contentDescription = label, tint = Txt, modifier = Modifier.size(size * (if (liquid) ratio * 0.72f else ratio)))
    }
}

@Composable
private fun CarPill(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = PillShape
    Row(
        modifier.height(68.dp).carGlass(shape) { glass(shape, fill = 0.14f) }.bounceClick(pressedScale = 0.96f, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CarGlassLayer(shape, 34.dp, 22.dp, 13.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Txt, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
