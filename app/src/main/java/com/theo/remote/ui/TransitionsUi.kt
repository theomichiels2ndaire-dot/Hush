package com.theo.remote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.TidalApi
import com.theo.remote.Transitions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Ce que l'éditeur de transitions doit afficher. */
object TransitionEdit {
    /** Un son dans l'éditeur (le son en cours n'a pas toujours de résultat Tidal). */
    data class Item(val title: String, val artist: String, val cover: Any?, val durationMs: Long) {
        val key get() = Transitions.keyOf(title, artist)
    }

    data class Request(val title: String, val items: List<Item>, val withPairs: Boolean)

    private val _req = MutableStateFlow<Request?>(null)
    val req: StateFlow<Request?> = _req

    fun forPlaylist(name: String, tracks: List<TidalApi.Result>) {
        _req.value = Request(name, tracks.take(100).map { Item(it.title, it.artistLine, it.coverUrl, it.durationMs) }, withPairs = true)
        Panels.show(Panels.Kind.Transitions)
    }

    fun forTrack(title: String, artist: String, cover: Any?, durationMs: Long) {
        _req.value = Request("Ce son", listOf(Item(title, artist, cover, durationMs)), withPairs = false)
        Panels.show(Panels.Kind.Transitions)
    }
}

// ---------------------------------------------------------------- éditeur

@Composable
fun TransitionsPanel() {
    val req by TransitionEdit.req.collectAsState()
    val cues by Transitions.cues.collectAsState()
    val pairs by Transitions.pairs.collectAsState()
    val settings by Transitions.settings.collectAsState()
    val r = req ?: return
    val list = rememberLazyListState()
    DismissWhenAtTop(list)

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                PanelTitle(if (r.withPairs) "Transitions" else "Début et fin")
                Text(
                    if (r.withPairs) "${r.title} · choisis la transition entre deux sons et coupe le début ou la fin de chacun."
                    else "${r.items.firstOrNull()?.title.orEmpty()} · fais-le démarrer plus tard ou finir plus tôt, à chaque écoute.",
                    color = Txt2, fontSize = 14.sp, lineHeight = 20.sp,
                )
                if (r.withPairs && r.items.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Transitions.Kind.Fade, Transitions.Kind.Rise, Transitions.Kind.Cut, Transitions.Kind.Auto).forEach { k ->
                            SmallPill(if (k == Transitions.Kind.Auto) "Tout effacer" else "Tout en ${k.label.lowercase()}", false) {
                                r.items.zipWithNext().forEach { (a, b) -> Transitions.setPair(a.key, b.key, k) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Space.s))
            }
        }
        itemsIndexed(r.items, key = { i, it -> "$i-${it.key}" }) { i, item ->
            Column {
                CueRow(item, cues[item.key])
                val next = r.items.getOrNull(i + 1)
                if (r.withPairs && next != null) {
                    val explicit = pairs["${item.key}>${next.key}"]
                    PairChip(explicit, settings) { k -> Transitions.setPair(item.key, next.key, k) }
                }
            }
        }
    }
}

/** Un son avec sa barre début / fin. */
@Composable
private fun CueRow(item: TransitionEdit.Item, cue: Transitions.Cue?) {
    val dur = item.durationMs.coerceAtLeast(1000L).toFloat()
    var range by remember(item.key, cue) {
        mutableStateOf((cue?.startMs ?: 0L).toFloat()..(cue?.endMs ?: item.durationMs).toFloat().coerceAtMost(dur))
    }
    val shape = Rounded(18)
    Column(Modifier.fillMaxWidth().glass(shape, fill = 0.06f, edge = 0.10f).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(model = item.cover, radius = 6.dp, modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val edited = range.start > 500f || range.endInclusive < dur - 500f
            if (edited) {
                Text(
                    "Réinitialiser", color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) {
                        range = 0f..dur
                        Transitions.setCue(item.key, null)
                    }.padding(6.dp),
                )
            }
        }
        RangeSlider(
            value = range,
            onValueChange = { v ->
                // au moins 20 s de son entre le début et la fin
                if (v.endInclusive - v.start >= 20_000f) range = v
            },
            valueRange = 0f..dur,
            onValueChangeFinished = {
                val s = range.start.toLong().takeIf { it > 1000 }?.let { it / 1000 * 1000 }
                val e = range.endInclusive.toLong().takeIf { it < item.durationMs - 1000 }?.let { it / 1000 * 1000 }
                Transitions.setCue(item.key, Transitions.Cue(s, e))
            },
            colors = SliderDefaults.colors(
                thumbColor = Txt, activeTrackColor = Txt, inactiveTrackColor = Color.White.copy(alpha = 0.14f),
            ),
            modifier = Modifier.blockDismiss(),
        )
        Row {
            Text("Début ${fmt(range.start.toLong())}", color = if (range.start > 500f) Txt else Txt3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Fin ${fmt(range.endInclusive.toLong())}", color = if (range.endInclusive < dur - 500f) Txt else Txt3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Pastille entre deux sons : on la touche pour changer de transition. */
@Composable
private fun PairChip(explicit: Transitions.Kind?, s: Transitions.Settings, onChange: (Transitions.Kind) -> Unit) {
    val order = listOf(Transitions.Kind.Auto, Transitions.Kind.Fade, Transitions.Kind.Rise, Transitions.Kind.Cut, Transitions.Kind.Pause)
    val current = explicit ?: Transitions.Kind.Auto
    val haptic = rememberHaptic()
    val label = when (current) {
        Transitions.Kind.Auto -> if (s.fadeAll) "Auto · ${s.defaultKind.label.lowercase()} ${s.fadeSec} s" else "Auto · enchaînement normal"
        Transitions.Kind.Fade -> "Fondu ${s.fadeSec} s"
        Transitions.Kind.Rise -> "Montée"
        Transitions.Kind.Cut -> "Coupure nette"
        Transitions.Kind.Pause -> "Pause ${s.fadeSec.coerceIn(1, 4)} s"
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(PillShape)
                .then(if (explicit != null) Modifier.background(Txt) else Modifier.glass(PillShape, fill = 0.08f, edge = 0.14f))
                .bounceClick(pressedScale = 0.94f) { haptic(); onChange(order[(order.indexOf(current) + 1) % order.size]) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = if (explicit != null) Ink else Txt2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pair") { l ->
                Text(l, color = if (explicit != null) Ink else Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SmallPill(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Box(
        Modifier.clip(shape)
            .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (on) Ink else Txt, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- réglages généraux (dans Réglages)

@Composable
fun TransitionSettingsCard() {
    val s by Transitions.settings.collectAsState()
    val shape = Rounded(22)
    Column(Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Transitions entre tous les sons", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text("Appliquée partout, sauf là où tu en as choisi une autre", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(Space.s))
            GlassSwitch(s.fadeAll) { Transitions.setSettings(s.copy(fadeAll = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Transitions.Kind.Fade, Transitions.Kind.Rise, Transitions.Kind.Pause).forEach { k ->
                SmallPill(k.label, s.defaultKind == k) { Transitions.setSettings(s.copy(defaultKind = k, fadeAll = true)) }
            }
        }
        Text("Durée du fondu : ${s.fadeSec} s", color = Txt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = s.fadeSec.toFloat(),
            onValueChange = { Transitions.setSettings(s.copy(fadeSec = it.toInt().coerceIn(1, 12))) },
            valueRange = 1f..12f,
            steps = 10,
            colors = SliderDefaults.colors(thumbColor = Txt, activeTrackColor = Txt, inactiveTrackColor = Color.White.copy(alpha = 0.14f)),
            modifier = Modifier.blockDismiss(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DJ auto", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Saute les intros de plus de 20 s (arrive juste avant la voix)", color = Txt2, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(Space.s))
            GlassSwitch(s.autoDj) { Transitions.setSettings(s.copy(autoDj = it)) }
        }
        Text(
            "Tidal ne joue qu'un son à la fois : le fondu baisse puis remonte le volume, les deux sons ne se mélangent pas.",
            color = Txt3, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}
