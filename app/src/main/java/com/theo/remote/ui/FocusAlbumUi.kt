package com.theo.remote.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.theo.remote.Ratings
import com.theo.remote.TidalApi
import com.theo.remote.TidalRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Écoute d'un album sans distraction : plein écran, pas de saut, Ne pas déranger, la note à la fin. */
object FocusAlbum {
    data class Session(val title: String, val artist: String, val cover: String?, val tracks: List<TidalApi.Result>)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    fun start(title: String, artist: String, cover: String?, tracks: List<TidalApi.Result>) {
        if (tracks.isEmpty()) return
        _session.value = Session(title, artist, cover, tracks)
        TidalRemote.setShuffle(false)
        TidalRemote.playAlbum(title, artist)
    }

    fun stop() { _session.value = null }
}

private fun focusNorm(t: String) = t.lowercase().substringBefore(" (").substringBefore(" [").replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun FocusAlbumHost(s: TidalRemote.State) {
    val sess by FocusAlbum.session.collectAsState()
    val x = sess ?: return
    FocusAlbumScreen(x, s)
}

@Composable
private fun FocusAlbumScreen(x: FocusAlbum.Session, s: TidalRemote.State) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val colors = rememberUrlCoverColors(x.cover)
    val accent = lerp(colors.b, Color.White, 0.35f)
    val band = lerp(colors.b, Color.Black, 0.70f)
    val nm = remember { ctx.getSystemService(NotificationManager::class.java) }
    var dndOn by remember { mutableStateOf(false) }

    // plein écran, écran allumé, Ne pas déranger (si Hush a le droit) — tout revient comme avant à la sortie
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val win = ctx.findActivity()?.window
        val ctl = win?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        val before = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        if (nm?.isNotificationPolicyAccessGranted == true && before == NotificationManager.INTERRUPTION_FILTER_ALL) {
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); dndOn = true }
        }
        onDispose {
            view.keepScreenOn = false
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            if (dndOn) runCatching { nm?.setInterruptionFilter(before) }
        }
    }
    BackHandler { FocusAlbum.stop() }

    // où on en est dans l'album
    val keys = remember(x) { x.tracks.map { focusNorm(it.title) } }
    val cur = s.track?.let { t -> keys.indexOf(focusNorm(t.title)) } ?: -1
    var idx by remember(x) { mutableIntStateOf(-1) }
    var finished by remember(x) { mutableStateOf(false) }
    var started by remember(x) { mutableStateOf(false) }
    val pos = rememberLivePosition(s, intervalMs = 500)
    LaunchedEffect(cur, s.track?.title) {
        if (finished) return@LaunchedEffect
        if (cur >= 0) { idx = cur; started = true }
        // le dernier son est passé et Tidal enchaîne sur autre chose : l'album est fini
        else if (started && idx == x.tracks.lastIndex && s.track != null) {
            finished = true
            if (s.isPlaying) TidalRemote.playPause()
        }
    }
    LaunchedEffect(pos / 1000, s.isPlaying) {
        val d = s.track?.durationMs ?: 0L
        if (!finished && started && idx == x.tracks.lastIndex && cur == idx && !s.isPlaying && d > 0 && pos >= d - 3000) finished = true
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (finished) {
            FocusEnd(x, accent)
            return@Box
        }
        val list = rememberLazyListState()
        LaunchedEffect(idx) { if (idx > 1) list.animateScrollToItem(idx + 1 - 2) }
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(bottom = 140.dp),
        ) {
            item(key = "head") {
                Column(
                    Modifier.fillMaxWidth().background(band).statusBarsPadding().padding(start = Space.gutter, end = Space.gutter, top = 12.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)).bounceClick { FocusAlbum.stop() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, contentDescription = "Quitter l'écoute", tint = Txt, modifier = Modifier.size(22.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Cover(model = x.cover?.let { TidalApi.bigPicture(it) }, radius = 12.dp, modifier = Modifier.size(280.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(x.title, color = Txt, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(x.artist, color = Txt.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.height(16.dp))
                    // un segment par son : ceux passés pleins, celui en cours qui se remplit
                    val d = s.track?.durationMs ?: 0L
                    val frac = if (cur >= 0 && d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f
                    val f by animateFloatAsState(frac, tween(500), label = "focusProg")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        x.tracks.forEachIndexed { i, _ ->
                            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.14f))) {
                                val fill = when { i < idx -> 1f; i == idx -> f; else -> 0f }
                                if (fill > 0f) Box(Modifier.fillMaxWidth(fill).height(4.dp).background(accent))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (idx >= 0) "${idx + 1} sur ${x.tracks.size}" else "Lancement de l'album…",
                        color = Txt.copy(alpha = 0.7f), fontSize = 13.sp,
                    )
                    if (!dndOn && nm?.isNotificationPolicyAccessGranted == false) {
                        Text(
                            "Autoriser Hush à couper les notifs pendant l'écoute",
                            color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                                .bounceClick(pressedScale = 0.97f) {
                                    runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }.padding(6.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            itemsIndexed(x.tracks, key = { i, r -> "f$i-${r.id}" }) { i, r ->
                val now = i == idx
                val past = i < idx
                Row(
                    Modifier.fillMaxWidth().height(54.dp).padding(horizontal = Space.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(30.dp)) {
                        if (now) EqBars(playing = s.isPlaying, color = accent, height = 14.dp)
                        else Text("${i + 1}", color = if (past) Txt3 else Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        r.fullTitle, color = when { now -> accent; past -> Txt3; else -> Txt }, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // un seul bouton : lecture / pause (pas de saut, c'est le principe)
        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
                .size(72.dp).clip(RoundedCornerShape(10.dp)).background(accent).bounceClick { TidalRemote.playPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (s.isPlaying) "Pause" else "Lecture", tint = Ink, modifier = Modifier.size(38.dp))
        }
    }
}

@Composable
private fun FocusEnd(x: FocusAlbum.Session, accent: Color) {
    val rated = Ratings.get(Ratings.Kind.Album, x.title, mainArtist(x.artist))
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Space.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(model = x.cover, radius = 12.dp, modifier = Modifier.size(200.dp))
        Spacer(Modifier.height(22.dp))
        Text("Album écouté en entier", color = Txt, fontSize = 24.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text("${x.title} · ${x.artist}", color = Txt2, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(24.dp))
        if (com.theo.remote.Features.flags.value.ratings) {
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(accent)
                    .bounceClick { FocusAlbum.stop(); Ratings.edit(Ratings.Kind.Album, x.title, mainArtist(x.artist), x.cover) },
                contentAlignment = Alignment.Center,
            ) { Text(if (rated != null) "Revoir ta note" else "Noter l'album", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
            Spacer(Modifier.height(10.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(Surface2).bounceClick { FocusAlbum.stop() },
            contentAlignment = Alignment.Center,
        ) { Text("Fermer", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
    }
}
