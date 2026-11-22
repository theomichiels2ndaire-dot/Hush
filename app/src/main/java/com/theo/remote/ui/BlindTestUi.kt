package com.theo.remote.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.BlindTest
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.PartyServer
import com.theo.remote.TidalApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SRC_TOP = "top"
private const val SRC_FAV = "fav"

@Composable
fun BlindTestPanel() {
    val phase by BlindTest.phase.collectAsState()
    val ctx = LocalContext.current
    DisposableEffect(Unit) { onDispose { if (BlindTest.active) BlindTest.stop() } }
    val scroll = rememberScrollState()
    DismissWhenAtTop(scroll)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PanelTitle("Blind test")
        AnimatedContent(
            targetState = phase,
            contentKey = { p ->
                when (p) {
                    is BlindTest.Phase.Playing -> "play${p.round.index}"
                    is BlindTest.Phase.Reveal -> "rev${p.round.index}"
                    is BlindTest.Phase.Loading -> "load${p.index}"
                    else -> p::class.simpleName
                }
            },
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 10 } + scaleIn(tween(320), initialScale = 0.97f)) togetherWith fadeOut(tween(160))
            },
            label = "blind",
        ) { p ->
            when (p) {
                is BlindTest.Phase.Off -> Setup(ctx)
                is BlindTest.Phase.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 60.dp)) {
                    Text("Manche ${p.index + 1} / ${p.total}", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(Space.m))
                    Equalizer(big = true)
                    Spacer(Modifier.height(Space.m))
                    Text("Prépare-toi…", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                }
                is BlindTest.Phase.Playing -> PlayingRound(p.round)
                is BlindTest.Phase.Reveal -> RevealRound(p.round)
                is BlindTest.Phase.Finished -> Finished(ctx)
            }
        }
    }
}

// ---------------------------------------------------------------- préparation

@Composable
private fun Setup(ctx: Context) {
    val playlists by PlaylistStore.list.collectAsState()
    val party by PartyServer.status.collectAsState()
    var source by remember { mutableStateOf(SRC_TOP) }
    var playlist by remember { mutableStateOf<TidalApi.Playlist?>(null) }
    var rounds by remember { mutableStateOf(10) }
    var level by remember { mutableStateOf(BlindTest.Level.Easy) }
    var withFriends by remember { mutableStateOf(party.running) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val best = remember { ctx.getSharedPreferences("hush", Context.MODE_PRIVATE).getInt("blindBest", 0) }
    LaunchedEffect(Unit) { runCatching { PlaylistStore.load() } }
    LaunchedEffect(withFriends) { if (withFriends && !party.running) PartyServer.start(ctx) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(
            "Hush lance un extrait, trouve le bon titre. Plus tu réponds vite, plus tu gagnes de points.",
            color = Txt2, fontSize = 14.sp, lineHeight = 20.sp,
        )
        if (best > 0) Text("Ton record : $best points", color = Ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)

        SetupLabel("Les sons")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Pick("Tes plus écoutés", source == SRC_TOP) { source = SRC_TOP; playlist = null } }
            item { Pick("Ma Musique", source == SRC_FAV) { source = SRC_FAV; playlist = null } }
            items(playlists.orEmpty(), key = { it.id }) { p -> Pick(p.name, playlist?.id == p.id) { playlist = p; source = p.id } }
        }

        SetupLabel("Difficulté")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlindTest.Level.values().forEach { l -> Pick(l.label, level == l) { level = l } }
        }
        Text(level.desc + if (level.mult > 1f) " · points ×${if (level.mult == 1.5f) "1,5" else "2"}" else "", color = Txt3, fontSize = 12.sp)

        SetupLabel("Manches")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15).forEach { n -> Pick("$n", rounds == n) { rounds = n } }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Avec mes potes", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text("Ils répondent sur leur téléphone via le QR code", color = Txt2, fontSize = 13.sp)
            }
            GlassSwitch(withFriends) { withFriends = it }
        }
        if (withFriends) {
            party.error?.let { Text(it, color = Ko, fontSize = 13.sp, lineHeight = 18.sp) }
            val qr = remember(party.url) { party.url?.let { qrBitmap(it) } }
            if (qr != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(120.dp).clip(Rounded(16)).background(Color.White).padding(10.dp)) {
                        Image(qr.asImageBitmap(), contentDescription = "QR code de la soirée", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(Space.m))
                    Text(
                        "Ils scannent, mettent leur prénom, et les réponses apparaissent sur leur écran pendant la partie.",
                        color = Txt2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.s))
        PillButton(if (loading) "Préparation…" else "Lancer la partie") {
            if (loading) return@PillButton
            loading = true
            scope.launch {
                val pool = runCatching { buildPool(source, playlist) }.getOrDefault(emptyList())
                loading = false
                val need = maxOf(4, level.choices)
                if (pool.size < need) {
                    com.theo.remote.TidalRemote.showToast("Pas assez de sons ici pour jouer (il en faut $need)")
                } else {
                    BlindTest.start(pool, rounds, withFriends && party.running, level)
                }
            }
        }
    }
}

private suspend fun buildPool(source: String, playlist: TidalApi.Playlist?): List<BlindTest.Song> =
    buildPoolRaw(source, playlist).filterNot { com.theo.remote.Blocked.isBlocked(it.title, it.artist) }

private suspend fun buildPoolRaw(source: String, playlist: TidalApi.Playlist?): List<BlindTest.Song> = when {
    playlist != null -> {
        val out = ArrayList<TidalApi.Result>()
        var next: String? = null
        var pages = 0
        do {
            val page = TidalApi.playlistPage(playlist.id, next)
            out += page.tracks
            next = page.next
            pages++
        } while (next != null && pages < 4)
        out.map { BlindTest.Song(it.fullTitle, it.artistLine, it.coverUrl, it) }
    }
    source == SRC_FAV -> {
        val cached = cachedTracks[FAVORITES_ID]
        val list = if (!cached.isNullOrEmpty()) cached else {
            val out = ArrayList<TidalApi.Result>()
            var next: String? = null
            var pages = 0
            do {
                val page = TidalApi.favoritesPage(next)
                out += page.tracks
                next = page.next
                pages++
            } while (next != null && pages < 5)
            out
        }
        list.map { BlindTest.Song(it.fullTitle, it.artistLine, it.coverUrl, it) }
    }
    else -> withContext(Dispatchers.Default) {
        val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
        val counts = ev.groupingBy { it.key }.eachCount()
        val byKey = HashMap<String, Ev>()
        ev.forEach { e -> val old = byKey[e.key]; if (old == null || old.cover == null) byKey[e.key] = e }
        counts.entries.sortedByDescending { it.value }.take(60).mapNotNull { byKey[it.key] }
            .map { BlindTest.Song(it.title, it.artist, it.cover) }
    }
}

// ---------------------------------------------------------------- une manche

@Composable
private fun PlayingRound(r: BlindTest.Round) {
    val answered by BlindTest.answered.collectAsState()
    val scores by BlindTest.scores.collectAsState()
    val mine = answered[BlindTest.HOST]
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(r.index) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(100) } }
    val left = r.remaining(now)
    val haptic = rememberHaptic(strong = true)

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Manche ${r.index + 1} / ${r.total}", color = Txt2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("C'est quoi ce son ?", color = Txt, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            Timer(left.toFloat() / r.durationMs, (left / 1000 + 1).coerceAtMost(r.durationMs / 1000))
        }
        Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) { Equalizer(big = true) }

        if (r.level.typed) {
            // difficile : on tape le titre
            var text by remember(r.index) { mutableStateOf("") }
            val shape = Rounded(18)
            Box(Modifier.fillMaxWidth().glass(shape, fill = 0.10f, edge = 0.18f).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (text.isEmpty()) Text(if (mine == null) "Tape le titre du son…" else "Réponse envoyée", color = Txt3, fontSize = 17.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = text, onValueChange = { if (mine == null) text = it.take(80) }, singleLine = true, enabled = mine == null,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (mine == null && BlindTest.answerText(BlindTest.HOST, text)) haptic() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (mine == null) PillButton("Valider") { if (BlindTest.answerText(BlindTest.HOST, text)) haptic() }
            Text("Les petites fautes passent. Pas besoin du « feat. ».", color = Txt3, fontSize = 12.sp)
        } else r.choices.forEachIndexed { i, c ->
            val picked = mine?.first == i
            ChoiceButton(
                c.title, if (r.level.showArtist) c.artist else "",
                state = if (picked) ChoiceState.Picked else if (mine != null) ChoiceState.Dim else ChoiceState.Normal,
            ) {
                if (mine == null && BlindTest.answer(BlindTest.HOST, i)) haptic()
            }
        }
        if (mine != null) Text("Réponse enregistrée, attends la fin de la manche…", color = Txt2, fontSize = 13.sp)
        if (BlindTest.party) Scoreboard(scores, answered.keys)
    }
}

@Composable
private fun RevealRound(r: BlindTest.Round) {
    val answered by BlindTest.answered.collectAsState()
    val scores by BlindTest.scores.collectAsState()
    val mine = answered[BlindTest.HOST]
    val good = mine?.second?.let { it > 0 } == true

    Column(verticalArrangement = Arrangement.spacedBy(Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when { mine == null -> "Temps écoulé !"; good -> "Bien joué ! +${mine?.second ?: 0}"; else -> "Raté…" },
            color = if (good) Ok else if (mine == null) Txt else Ko, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
        )
        Cover(model = r.answer.cover, radius = 18.dp, modifier = Modifier.size(200.dp).shadow(28.dp, Rounded(18)))
        Text(r.answer.title, color = Txt, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(r.answer.artist, color = Txt2, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (r.level.typed) {
            val typed by BlindTest.typedAnswers.collectAsState()
            typed[BlindTest.HOST]?.let { Text("Ta réponse : « $it »", color = if (good) Ok else Ko, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        }
        r.choices.forEachIndexed { i, c ->
            ChoiceButton(
                c.title, c.artist,
                state = when {
                    i == r.correct -> ChoiceState.Right
                    mine?.first == i -> ChoiceState.Wrong
                    else -> ChoiceState.Dim
                },
            ) {}
        }
        if (BlindTest.party) Scoreboard(scores, emptySet()) else Text("Score : ${scores[BlindTest.HOST] ?: 0}", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Finished(ctx: Context) {
    val scores by BlindTest.scores.collectAsState()
    val mine = scores[BlindTest.HOST] ?: 0
    val prefs = remember { ctx.getSharedPreferences("hush", Context.MODE_PRIVATE) }
    val oldBest = remember { prefs.getInt("blindBest", 0) }
    LaunchedEffect(mine) { if (mine > oldBest) prefs.edit().putInt("blindBest", mine).apply() }
    val ranking = scores.entries.sortedByDescending { it.value }

    Column(verticalArrangement = Arrangement.spacedBy(Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Space.l))
        Text("Partie terminée", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (ranking.size > 1) {
            Text("${ranking.first().key} gagne !", color = Txt, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Scoreboard(scores, emptySet())
        } else {
            Text("$mine points", color = Txt, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
            if (mine > oldBest && oldBest > 0) Text("Nouveau record !", color = Ok, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            else if (oldBest > 0) Text("Ton record : ${maxOf(oldBest, mine)}", color = Txt2, fontSize = 15.sp)
        }
        Spacer(Modifier.height(Space.s))
        PillButton("Rejouer") { BlindTest.stop() }
    }
}

// ---------------------------------------------------------------- petits morceaux

private enum class ChoiceState { Normal, Picked, Dim, Right, Wrong }

@Composable
private fun ChoiceButton(title: String, artist: String, state: ChoiceState, onClick: () -> Unit) {
    val shape = Rounded(20)
    val bg by animateColorAsState(
        when (state) {
            ChoiceState.Picked -> Color.White.copy(alpha = 0.28f)
            ChoiceState.Right -> Ok.copy(alpha = 0.85f)
            ChoiceState.Wrong -> Ko.copy(alpha = 0.8f)
            ChoiceState.Dim -> Color.White.copy(alpha = 0.04f)
            ChoiceState.Normal -> Color.White.copy(alpha = 0.09f)
        },
        tween(250), label = "choice",
    )
    val fg = if (state == ChoiceState.Right) Ink else if (state == ChoiceState.Dim) Txt3 else Txt
    Column(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(bg).glass(shape, fill = 0f, edge = 0.12f)
            .bounceClick(pressedScale = 0.96f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = fg, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artist.isNotBlank()) Text(artist, color = fg.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Scoreboard(scores: Map<String, Int>, answeredNow: Set<String>) {
    val shape = Rounded(20)
    Column(Modifier.fillMaxWidth().glass(shape, fill = 0.06f, edge = 0.10f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Classement", color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        scores.entries.sortedByDescending { it.value }.take(10).forEachIndexed { i, (name, pts) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Txt2, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(24.dp))
                Text(name, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (name in answeredNow) Text("✓ ", color = Ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("$pts", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** Compte à rebours circulaire. */
@Composable
private fun Timer(progress: Float, seconds: Long) {
    Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 5.dp.toPx()
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, style = Stroke(w))
            drawArc(
                if (progress < 0.25f) Ko else Txt, -90f, 360f * progress.coerceIn(0f, 1f), false,
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        Text("$seconds", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Petites barres qui dansent : on entend le son, on ne le voit pas. */
@Composable
private fun Equalizer(big: Boolean) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = 9
    val values = (0 until bars).map { i ->
        t.animateFloat(
            0.15f, 1f,
            infiniteRepeatable(tween(420 + (i * 97) % 380, easing = LinearEasing), RepeatMode.Reverse),
            label = "eq$i",
        )
    }
    Canvas(Modifier.size(width = if (big) 180.dp else 60.dp, height = if (big) 80.dp else 24.dp)) {
        val w = size.width / (bars * 1.6f)
        values.forEachIndexed { i, v ->
            val h = size.height * v.value
            drawRoundRect(
                Color.White.copy(alpha = 0.9f),
                topLeft = Offset(i * w * 1.6f, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

@Composable
private fun SetupLabel(t: String) {
    Text(t, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
}

@Composable
private fun Pick(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Box(
        Modifier.clip(shape)
            .then(if (on) Modifier.background(Txt) else Modifier.glass(shape, fill = 0.08f, edge = 0.14f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (on) Ink else Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
