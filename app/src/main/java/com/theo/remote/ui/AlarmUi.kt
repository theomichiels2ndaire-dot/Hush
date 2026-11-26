package com.theo.remote.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Alarm
import com.theo.remote.AlarmActivity
import com.theo.remote.TidalRemote
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- écran qui sonne

@Composable
fun AlarmRingScreen(onWake: () -> Unit, onStop: () -> Unit, onSnooze: () -> Unit) {
    val s by TidalRemote.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val colors = rememberCoverColors(s.track?.art)
    val pulse = rememberInfiniteTransition(label = "alarm")
    val glow by pulse.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    Box(Modifier.fillMaxSize().background(Ink)) {
        Ambient(colors, strength = 1f, animated = true)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Text(if (hour < 12) "Bonjour Theo" else "Debout Theo", color = Txt2, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(now)),
                color = Txt, fontSize = 96.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.graphicsLayer { scaleX = glow; scaleY = glow },
            )
            Text(SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(now)).replaceFirstChar { it.uppercase() }, color = Txt2, fontSize = 17.sp)

            Spacer(Modifier.weight(1f))
            s.track?.let { t ->
                Row(
                    Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.08f, edge = 0.14f).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(bitmap = t.art, radius = 12.dp, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.size(14.dp))
                    AnimatedContent(t.title to t.artist, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "alarmTrack") { (title, artist) ->
                        Column {
                            Text(title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(artist, color = Txt2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))

            Box(
                Modifier.fillMaxWidth().height(64.dp).clip(PillShape).background(Txt).bounceClick(pressedScale = 0.95f, onClick = onWake),
                contentAlignment = Alignment.Center,
            ) { Text("Je me lève, garde la musique", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.weight(1f).height(58.dp).glass(PillShape, fill = 0.12f, edge = 0.16f).bounceClick(pressedScale = 0.95f, onClick = onSnooze),
                    contentAlignment = Alignment.Center,
                ) { Text("Encore 9 min", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.weight(1f).height(58.dp).glass(PillShape, fill = 0.12f, edge = 0.16f).bounceClick(pressedScale = 0.95f, onClick = onStop),
                    contentAlignment = Alignment.Center,
                ) { Text("Arrêter", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------- réglage du réveil

private val DAYS = listOf(
    Calendar.MONDAY to "L", Calendar.TUESDAY to "M", Calendar.WEDNESDAY to "M", Calendar.THURSDAY to "J",
    Calendar.FRIDAY to "V", Calendar.SATURDAY to "S", Calendar.SUNDAY to "D",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmPanel() {
    val ctx = LocalContext.current
    val saved by Alarm.config.collectAsState()
    val playlists by PlaylistStore.list.collectAsState()
    val picker = rememberTimePickerState(initialHour = saved.hour, initialMinute = saved.minute, is24Hour = true)
    var days by remember { mutableStateOf(saved.days) }
    var source by remember { mutableStateOf(saved.source) }
    var playlist by remember { mutableStateOf(saved.playlist) }
    var volume by remember { mutableStateOf(saved.volume.toFloat()) }
    var ramp by remember { mutableStateOf(saved.rampSec) }
    var canExact by remember { mutableStateOf(Alarm.canSchedule(ctx)) }
    LaunchedEffect(Unit) { Alarm.init(ctx); runCatching { PlaylistStore.load() } }

    fun current(enabled: Boolean) = Alarm.Config(enabled, picker.hour, picker.minute, days, source, playlist, volume.toInt(), ramp)

    val scroll = rememberScrollState()
    DismissWhenAtTop(scroll)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PanelTitle("Réveil musical")
        val nextAt = Alarm.next(saved)
        Text(
            if (nextAt != null) "Prochain réveil ${untilText(nextAt)}" else "Réveil coupé",
            color = if (nextAt != null) Ok else Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )

        Box(Modifier.fillMaxWidth().blockDismiss(), contentAlignment = Alignment.Center) {
            TimePicker(
                state = picker,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Color.White.copy(alpha = 0.07f),
                    clockDialSelectedContentColor = Ink,
                    selectorColor = Txt,
                    containerColor = Color.Transparent,
                    timeSelectorSelectedContainerColor = Color.White.copy(alpha = 0.18f),
                    timeSelectorSelectedContentColor = Txt,
                ),
            )
        }

        Label("Jours")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEach { (d, l) ->
                val on = d in days
                Box(
                    Modifier.weight(1f).height(42.dp).clip(PillShape)
                        .then(if (on) Modifier.background(Txt) else Modifier.glass(PillShape, fill = 0.08f, edge = 0.14f))
                        .bounceClick { days = if (on) days - d else days + d },
                    contentAlignment = Alignment.Center,
                ) { Text(l, color = if (on) Ink else Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
        Text(if (days.isEmpty()) "Aucun jour choisi : le réveil sonnera une seule fois." else "", color = Txt3, fontSize = 12.sp)

        Label("Me réveiller avec")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Choice("Mon dernier son", source == "last") { source = "last" } }
            item { Choice("Ma Musique (aléatoire)", source == "favorites") { source = "favorites" } }
            items(playlists.orEmpty(), key = { it.id }) { p ->
                Choice(p.name, source == "playlist" && playlist == p.name) { source = "playlist"; playlist = p.name }
            }
        }

        Label("Volume final : ${volume.toInt()} %")
        Slider(
            value = volume, onValueChange = { volume = it }, valueRange = 10f..100f,
            colors = SliderDefaults.colors(thumbColor = Txt, activeTrackColor = Txt, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
        )

        Label("Le son monte en")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "30 s", 60 to "1 min", 180 to "3 min", 300 to "5 min").forEach { (sec, l) -> Choice(l, ramp == sec) { ramp = sec } }
        }

        if (!canExact && Build.VERSION.SDK_INT >= 31) {
            Text(
                "Android doit autoriser Hush à sonner à l'heure pile. Active « Alarmes et rappels » pour Hush.",
                color = Warn, fontSize = 13.sp, lineHeight = 18.sp,
            )
            PillButton("Autoriser", filled = false) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                canExact = Alarm.canSchedule(ctx)
            }
        }

        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PillButton(if (saved.enabled) "Enregistrer" else "Activer le réveil") {
                val c = current(true)
                Alarm.set(ctx, c)
                Alarm.next(c)?.let { TidalRemote.showToast("Réveil ${untilText(it)}") }
            }
            if (saved.enabled) PillButton("Couper", filled = false) { Alarm.set(ctx, current(false)); TidalRemote.showToast("Réveil coupé") }
        }
        Text(
            "Tester maintenant",
            color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.96f) {
                Alarm.set(ctx, current(saved.enabled))
                ctx.startActivity(Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(vertical = 6.dp),
        )
        Text(
            "Si Tidal ne lance rien (pas de réseau…), une sonnerie classique prend le relais au bout de 25 s.",
            color = Txt3, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Start,
        )
    }
}

/** « dans 7 h 12 » / « demain à 7:30 » */
fun untilText(at: Long): String {
    val diff = at - System.currentTimeMillis()
    val h = diff / 3_600_000
    val m = (diff / 60_000) % 60
    return if (h < 24) "dans ${if (h > 0) "$h h " else ""}${m.toString().padStart(if (h > 0) 2 else 1, '0')} min"
    else "le " + SimpleDateFormat("EEEE à HH:mm", Locale.FRANCE).format(Date(at))
}

@Composable
private fun Label(t: String) {
    Text(t, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Box(
        Modifier.clip(shape)
            .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (on) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
