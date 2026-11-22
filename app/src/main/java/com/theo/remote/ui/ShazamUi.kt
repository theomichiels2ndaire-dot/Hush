package com.theo.remote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Recognizer
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote

@Composable
fun ShazamPanel() {
    val ctx = LocalContext.current
    val status by Recognizer.status.collectAsState()
    val level by Recognizer.level.collectAsState()
    val history by Recognizer.history.collectAsState()
    var token by remember { mutableStateOf(Recognizer.token) }
    var showKey by remember { mutableStateOf(false) }
    val haptic = rememberHaptic(strong = true)
    DisposableEffect(Unit) {
        Recognizer.init(ctx)
        token = Recognizer.token
        onDispose { Recognizer.cancel() }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Recognizer.listen(ctx) else TidalRemote.showToast("Hush a besoin du micro pour reconnaître un son")
    }
    fun go() {
        haptic()
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Recognizer.listen(ctx)
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listening = status is Recognizer.Status.Listening || status is Recognizer.Status.Searching
    val scroll = rememberScrollState()
    DismissWhenAtTop(scroll)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PanelTitle("Quel est ce son ?")

        // le gros bouton qui écoute
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            ListenRings(active = listening, level = level)
            val sc by animateFloatAsState(if (listening) 1f + level * 0.12f else 1f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "micScale")
            Box(
                Modifier.size(150.dp)
                    .shadow(30.dp, PillShape)
                    .clip(PillShape)
                    .background(flatOr(listOf(Color(0xFF5B3CF0), Color(0xFFE0458B)), flat = androidx.compose.ui.graphics.lerp(Ok, Color.Black, 0.5f)))
                    .bounceClick(pressedScale = 0.9f) { if (listening) Recognizer.cancel() else go() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.GraphicEq, contentDescription = "Reconnaître le son", tint = Txt,
                    modifier = Modifier.size(70.dp).scale(sc),
                )
            }
        }

        AnimatedContent(
            targetState = status,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.96f)) togetherWith fadeOut(tween(150)) },
            contentKey = { it::class },
            label = "shazamStatus",
        ) { st ->
            when (st) {
                is Recognizer.Status.Idle -> Hint("Touche le cercle et approche ton téléphone de la musique.")
                is Recognizer.Status.Listening -> Hint("J'écoute… ${(st.progress * 8).toInt().coerceAtMost(8)} / 8 s")
                is Recognizer.Status.Searching -> Hint("Je cherche…")
                is Recognizer.Status.NoMatch -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Hint("Pas trouvé. Rapproche-toi du son et réessaie.")
                    Spacer(Modifier.height(Space.s))
                    PillButton("Réessayer") { go() }
                }
                is Recognizer.Status.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(st.message, color = Ko, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                    Spacer(Modifier.height(Space.s))
                    PillButton("Réessayer") { go() }
                }
                is Recognizer.Status.Match -> FoundCard(st.found)
            }
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(Space.s))
            Text("Déjà reconnus", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            history.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f) {
                        f.tidal?.let { TidalRemote.play(it) } ?: TidalRemote.playTitle(f.title, f.artist)
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(model = f.tidal?.coverUrl, radius = 6.dp, modifier = Modifier.size(46.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    f.tidal?.let { AddToQueueButton(it) }
                }
            }
        }

        Spacer(Modifier.height(Space.s))
        Text(
            if (showKey) "Clé AudD" else if (token.isBlank()) "Ajouter une clé AudD (gratuite, plus d'essais)" else "Clé AudD enregistrée · modifier",
            color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.97f) { showKey = !showKey }.padding(vertical = 6.dp),
        )
        if (showKey) {
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxWidth().height(46.dp).glass(shape, fill = 0.08f, edge = 0.14f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (token.isEmpty()) Text("Colle ta clé (dashboard.audd.io)", color = Txt3, fontSize = 15.sp)
                BasicTextField(
                    value = token,
                    onValueChange = { token = it; Recognizer.setToken(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Txt),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "La reconnaissance passe par AudD. Sans clé, quelques essais par jour ; avec ta clé gratuite (sur dashboard.audd.io), beaucoup plus.",
                color = Txt3, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun Hint(t: String) {
    Text(t, color = Txt2, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth())
}

/** Cercles qui s'éloignent du bouton pendant l'écoute. */
@Composable
private fun ListenRings(active: Boolean, level: Float) {
    val t = rememberInfiniteTransition(label = "rings")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ringP")
    val alpha by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "ringA")
    Canvas(Modifier.size(300.dp)) {
        val base = 75.dp.toPx()
        for (k in 0 until 3) {
            val f = (p + k / 3f) % 1f
            val r = base + f * (size.minDimension / 2 - base) * (0.7f + level * 0.5f)
            drawCircle(Color.White.copy(alpha = (1f - f) * 0.35f * alpha), radius = r, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun FoundCard(f: Recognizer.Found) {
    val shape = Rounded(24)
    Column(
        Modifier.fillMaxWidth().glass(shape, fill = 0.09f, edge = 0.16f).padding(Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(model = f.tidal?.coverUrl, radius = 16.dp, modifier = Modifier.size(170.dp).shadow(24.dp, Rounded(16)))
        Spacer(Modifier.height(Space.m))
        Text(f.title, color = Txt, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(f.artist, f.album).filter { it.isNotBlank() }.joinToString(" · "), color = Txt2, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(Space.m))
        val r: TidalApi.Result? = f.tidal
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FoundAction(Icons.Rounded.PlayArrow, "Lire", filled = true) { r?.let { TidalRemote.play(it) } ?: TidalRemote.playTitle(f.title, f.artist) }
            if (r != null) {
                FoundAction(Icons.AutoMirrored.Rounded.QueueMusic, "À suivre", filled = false) { TidalRemote.addToQueue(r) }
                FoundAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", filled = false) { PlaylistEdit.pick(listOf(r)) }
            }
        }
        if (r == null) {
            Spacer(Modifier.height(Space.s))
            Text("Pas trouvé tel quel dans Tidal : « Lire » lance une recherche.", color = Txt3, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FoundAction(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Row(
        Modifier.height(44.dp)
            .then(if (filled) Modifier.clip(shape).background(Txt) else Modifier.glass(shape, fill = 0.12f, edge = 0.16f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Ink else Txt, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (filled) Ink else Txt, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}
