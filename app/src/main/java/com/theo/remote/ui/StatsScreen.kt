package com.theo.remote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.TidalRemote
import com.theo.remote.LastFm
import androidx.compose.runtime.produceState
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Période affichée. */
private enum class Period(val label: String, val days: Int?) { Week("7 jours", 7), Month("30 jours", 30), All("Tout", null) }

/** Quelle carte est ouverte en grand (façon Onda : petites cartes qui s'ouvrent). */
private enum class Card { None, Plays, Time, TopTrack, TopArtist, Hour, Streak }

@Composable
fun StatsScreen() {
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    var period by rememberSaveable { mutableStateOf(Period.Week) }
    var open by rememberSaveable { mutableStateOf(Card.None) }
    var wrapped by rememberSaveable { mutableStateOf<Int?>(null) }
    // calcul en arrière-plan : avec un gros historique Last.fm, ça évite de figer l'écran
    val excludeVersion by com.theo.remote.StatsExclude.version.collectAsState()
    var wrappedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    val events by produceState<List<Ev>?>(null, entries, scrobbles, excludeVersion) {
        value = withContext(Dispatchers.Default) { buildEvents(entries, scrobbles) }
    }
    val statsState by produceState<Stats?>(null, events, period) {
        val ev = events ?: return@produceState
        value = withContext(Dispatchers.Default) {
            computeStats(ev, period.days?.let { System.currentTimeMillis() - it * 24L * 60 * 60 * 1000 } ?: 0L)
        }
    }
    // mois proposés pour le récap : celui-ci, puis le précédent s'il a des écoutes
    val recapMonths by produceState(emptyList<Int>(), events) {
        val ev = events ?: return@produceState
        value = withContext(Dispatchers.Default) {
            (0..11).filter { o -> val (a, b) = monthRange(o); ev.count { it.ts in a until b } >= 10 }.take(6)
        }
    }

    wrapped?.let { offset ->
        events?.let { ev -> WrappedScreen(ev, offset, onClose = { wrapped = null }) }
    }
    wrappedYear?.let { y ->
        events?.let { ev -> WrappedScreen(ev, 0, onClose = { wrappedYear = null }, year = y) }
    }
    val wrapYear by com.theo.remote.Features.flags.collectAsState()

    val statsScroll = rememberScrollState()
    LaunchedEffect(Unit) { NavEvents.reselect.collect { if (it == "Stats") statsScroll.animateScrollTo(0) } }
    // couleur de ton son n°1 : elle teinte toute la page (façon Replay)
    val acc = rememberModelCoverColors(statsState?.topTracks?.firstOrNull()?.first?.cover)
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxWidth().height(520.dp).background(
            Brush.verticalGradient(listOf(acc.a.copy(alpha = 0.55f), acc.b.copy(alpha = 0.18f), Color.Transparent)),
        ),
    )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(statsScroll)
            .padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text("Stats", color = Txt, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
        LevelCard()
        LocalStatsCard()

        // sélecteur de période en un seul bloc (segment)
        Row(Modifier.fillMaxWidth().glass(PillShape, fill = 0.07f, edge = 0.12f).padding(4.dp)) {
            Period.entries.forEach { p ->
                val on = p == period
                val bg by androidx.compose.animation.animateColorAsState(if (on) Txt else Color.Transparent, label = "seg")
                Box(
                    Modifier.weight(1f).height(38.dp).clip(PillShape).background(bg).bounceClick { period = p },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p.label, color = if (on) Ink else Txt2, fontSize = 13.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold)
                }
            }
        }

        if (recapMonths.isNotEmpty() || wrapYear.wrappedYear) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (wrapYear.wrappedYear) {
                    val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                    YearRecapCard(y) { wrappedYear = y }
                    // en janvier, l'année d'avant reste à portée de main
                    if (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) == java.util.Calendar.JANUARY) YearRecapCard(y - 1) { wrappedYear = y - 1 }
                }
                recapMonths.forEach { o -> RecapCard(o) { wrapped = o } }
            }
        }

        // mur de pochettes (option) : tout ce que tu as écouté, en grille
        if (wrapYear.coverWall) WallEntryCard(acc) { Panels.show(Panels.Kind.CoverWall) }

        val st = statsState
        if (st == null) {
            SkeletonLines(4)
            LastFmCard()
            return@Column
        }
        val topHour = st.perHour.indices.maxByOrNull { st.perHour[it] }?.takeIf { st.perHour[it] > 0 }

        if (st.plays == 0) {
            EmptyState("Pas encore d'écoutes", "Hush note tout ce que tu écoutes dans Tidal : reviens dans quelques heures.")
            LastFmCard()
            return@Column
        }

        // grand bloc : temps d'écoute, comparaison et graphique par jour
        val prevMinutes = remember(events, period) {
            val d = period.days ?: return@remember null
            val ev = events ?: return@remember null
            val now = System.currentTimeMillis(); val len = d * 24L * 60 * 60 * 1000
            ev.filter { it.ts in (now - 2 * len) until (now - len) }.sumOf { it.durMs } / 60_000
        }
        HeroTime(st.minutes, prevMinutes, st.perDayMinutes.takeLast(if (period == Period.Week) 7 else 14), acc) { open = Card.Time }
        if (st.topArtists.isNotEmpty()) TopArtistBars(st.topArtists.take(5), acc)

        // carte ouverte en grand, ou grille de petites cartes
        AnimatedContent(
            targetState = open,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.96f)) togetherWith fadeOut(tween(160)) using
                    SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.85f, stiffness = 380f) }
            },
            label = "statsCard",
        ) { card ->
            if (card == Card.None) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        SmallCard("Écoutes", "${st.plays}", "sur ${period.label.lowercase()}", Modifier.weight(1f)) { open = Card.Plays }
                        SmallCard("Temps d'écoute", fmtMinutes(st.minutes), "environ", Modifier.weight(1f)) { open = Card.Time }
                    }
                    st.topTracks.firstOrNull()?.let { (e, c) ->
                        CoverCard("Son n°1", e.title, "${e.artist} · $c écoute${if (c > 1) "s" else ""}", e.cover, acc) { open = Card.TopTrack }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        st.topArtists.firstOrNull()?.let { (a, c) ->
                            SmallCard("Artiste n°1", a, "$c écoute${if (c > 1) "s" else ""}", Modifier.weight(1f)) { open = Card.TopArtist }
                        }
                        SmallCard("Heure préférée", topHour?.let { "${it}h" } ?: "—", "là où tu écoutes le plus", Modifier.weight(1f)) { open = Card.Hour }
                    }
                    SmallCard("Série", "${st.streak} jour${if (st.streak > 1) "s" else ""}", "d'affilée avec de la musique", Modifier.fillMaxWidth()) { open = Card.Streak }
                }
            } else {
                BigCard(onClose = { open = Card.None }) {
                    when (card) {
                        Card.Plays -> {
                            BigTitle("Écoutes", "${st.plays}")
                            Bars(st.perDay.map { it.first to it.second.toFloat() }) { "${it.toInt()}" }
                        }
                        Card.Time -> {
                            BigTitle("Temps d'écoute", fmtMinutes(st.minutes))
                            Bars(st.perDayMinutes.map { it.first to it.second.toFloat() }) { fmtMinutes(it.toLong()) }
                            Text("Estimé à partir de la durée des sons lancés.", color = Txt3, fontSize = 12.sp)
                        }
                        Card.TopTrack -> {
                            BigTitle("Tes sons", "Top ${st.topTracks.size}")
                            st.topTracks.forEachIndexed { i, (e, c) ->
                                RankRow(i + 1, e.title, e.artist, c, e.cover) {
                                    TidalRemote.playTitle(e.title, e.artist)
                                }
                            }
                        }
                        Card.TopArtist -> {
                            BigTitle("Tes artistes", "Top ${st.topArtists.size}")
                            val open2 = LocalOpenDetail.current
                            st.topArtists.forEachIndexed { i, (a, c) ->
                                RankRow(i + 1, a, null, c, null) { open2(Detail.ArtistByName(a)) }
                            }
                        }
                        Card.Hour -> {
                            BigTitle("Heure préférée", topHour?.let { "${it}h" } ?: "—")
                            Bars((0 until 24).map { (if (it % 6 == 0) "${it}h" else "") to st.perHour[it].toFloat() }, thin = true) { "" }
                        }
                        Card.Streak -> {
                            BigTitle("Série", "${st.streak} jour${if (st.streak > 1) "s" else ""}")
                            Text(
                                if (st.streak >= 2) "Tu as écouté de la musique ${st.streak} jours d'affilée. Continue !"
                                else "Écoute un son demain pour lancer une série.",
                                color = Txt2, fontSize = 15.sp, lineHeight = 21.sp,
                            )
                            Spacer(Modifier.height(Space.m))
                            Bars(st.perDay.map { it.first to it.second.toFloat() }) { "" }
                        }
                        Card.None -> {}
                    }
                }
            }
        }
        events?.let { StatsExtras(it) }
        BadgesSection()
        LastFmCard()
    }
    }
}

/** Couleurs d'une pochette quelle que soit sa source (lien Tidal ou fichier en cache). */
@Composable
private fun rememberModelCoverColors(model: Any?): CoverColors {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(model) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(model) {
        if (model == null) return@LaunchedEffect
        val req = coil.request.ImageRequest.Builder(ctx).data(model).size(160).allowHardware(false).build()
        bmp = (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }
    return rememberCoverColors(bmp)
}

/** Le gros chiffre du temps d'écoute, l'écart avec la période d'avant et un graphique par jour. */
@Composable
private fun HeroTime(minutes: Long, prev: Long?, days: List<Pair<String, Long>>, acc: CoverColors, onClick: () -> Unit) {
    val shape = Rounded(28)
    val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val best = days.indices.maxByOrNull { days[it].second }
    Column(
        Modifier.fillMaxWidth()
            .tintShadow(24.dp, shape, acc.a)
            .clip(shape)
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(acc.a, Color.Black, 0.25f), androidx.compose.ui.graphics.lerp(acc.b, Color.Black, 0.55f))))
            .glare()
            .bounceClick(pressedScale = 0.98f, onClick = onClick).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Temps d'écoute", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtMinutes(minutes), color = Color.White, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp)
            Spacer(Modifier.width(10.dp))
            if (prev != null && prev > 0) {
                val pct = ((minutes - prev) * 100 / prev).toInt()
                Text(
                    (if (pct >= 0) "+" else "") + "$pct % vs avant",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = 10.dp).clip(PillShape).background(if (pct >= 0) Color(0x5533D17A) else Color(0x55FF4D4D)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (days.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { i, (_, m) ->
                    val f = (m.toFloat() / max).coerceIn(0.04f, 1f)
                    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
                    LaunchedEffect(f) { grow.animateTo(f, androidx.compose.animation.core.tween(500, delayMillis = i * 30)) }
                    Box(
                        Modifier.weight(1f).fillMaxHeight(grow.value.coerceAtLeast(0.02f)).clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = if (i == best) 0.95f else 0.38f)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEachIndexed { i, (label, _) ->
                    Text(
                        label.take(3), color = Color.White.copy(alpha = if (i == best) 1f else 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Top artistes avec une barre de progression. */
@Composable
private fun TopArtistBars(list: List<Pair<String, Int>>, acc: CoverColors) {
    val open = LocalOpenDetail.current
    val max = (list.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Top artistes", color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp))
        list.forEachIndexed { i, (a, c) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.98f) { open(Detail.ArtistByName(a)) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = if (i == 0) Txt else Txt2, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp))
                Box(
                    Modifier.size(44.dp).tintShadow(10.dp, PillShape, acc.a).clip(PillShape)
                        .background(Brush.linearGradient(listOf(acc.a, androidx.compose.ui.graphics.lerp(acc.b, Color.Black, 0.4f)))),
                    contentAlignment = Alignment.Center,
                ) { Text(a.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(Color.White.copy(alpha = 0.08f))) {
                        Box(Modifier.fillMaxWidth(c.toFloat() / max).height(6.dp).clip(PillShape).background(Brush.horizontalGradient(listOf(acc.b, acc.a))))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("$c", color = Txt2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SmallCard(label: String, value: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = Rounded(22)
    Column(
        modifier
            .heightIn(min = 120.dp)
            .glass(shape, fill = 0.10f, edge = 0.12f)
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(Space.m),
    ) {
        Text(label, color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Space.s))
        Text(value, color = Txt, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Txt2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CoverCard(label: String, title: String, sub: String, cover: Any?, acc: CoverColors, onClick: () -> Unit) {
    val shape = Rounded(24)
    Row(
        Modifier
            .fillMaxWidth()
            .tintShadow(18.dp, shape, acc.a)
            .clip(shape)
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(acc.b, Color.Black, 0.35f), androidx.compose.ui.graphics.lerp(acc.a, Color.Black, 0.2f))))
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model = cover, radius = 14.dp, modifier = Modifier.size(96.dp).tintShadow(16.dp, Rounded(14), Color.Black))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Space.xs))
            Text(title, color = Color.White, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BigCard(onClose: () -> Unit, content: @Composable () -> Unit) {
    val shape = Rounded(26)
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape, fill = 0.10f, edge = 0.14f)
            .bounceClick(pressedScale = 0.99f, onClick = onClose)
            .padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        content()
        Spacer(Modifier.height(Space.s))
        Text("Toucher pour refermer", color = Txt3, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun BigTitle(label: String, value: String) {
    Text(label, color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Text(value, color = Txt, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(Space.s))
}

@Composable
private fun RankRow(rank: Int, title: String, sub: String?, count: Int, cover: Any?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.97f, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(28.dp))
        if (cover != null) {
            Cover(model = cover, radius = 6.dp, modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(Space.s + Space.xs))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Petit graphique en barres qui pousse à l'ouverture. */
@Composable
private fun Bars(data: List<Pair<String, Float>>, thin: Boolean = false, valueLabel: (Float) -> String) {
    val max = (data.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(1f)
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val grow by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 200f), label = "bars")
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(if (thin) 2.dp else Space.s), verticalAlignment = Alignment.Bottom) {
        data.forEach { (label, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!thin) {
                    val txt = if (v > 0) valueLabel(v) else ""
                    if (txt.isNotBlank()) Text(txt, color = Txt2, fontSize = 10.sp, maxLines = 1)
                }
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val h = size.height * (v / max) * grow
                    drawRoundRect(
                        color = if (v > 0) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                        topLeft = Offset(0f, size.height - h.coerceAtLeast(3f)),
                        size = Size(size.width, h.coerceAtLeast(3f)),
                        cornerRadius = CornerRadius(if (thin) 2f else 8f),
                    )
                }
                Spacer(Modifier.height(Space.xs))
                Text(label, color = Txt2, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** Carte qui ouvre le récap de l'année, façon Wrapped. */
@Composable
private fun YearRecapCard(year: Int, onClick: () -> Unit) {
    val shape = Rounded(22)
    val ongoing = year == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    Column(
        Modifier
            .width(170.dp)
            .height(112.dp)
            .clip(shape)
            .background(flatOr(listOf(Color(0xFF2A2A5E), Color(0xFFB9A6FF)), flat = Surface2))
            .glass(shape, fill = 0.04f, edge = 0.18f)
            .bounceClick(pressedScale = 0.96f, onClick = onClick)
            .padding(Space.m),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Ton année", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Column {
            Text("$year", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (ongoing) "en cours" else "à revoir", color = Txt.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

/** Carte qui ouvre le récap d'un mois, façon Wrapped. */
@Composable
private fun RecapCard(offset: Int, onClick: () -> Unit) {
    val shape = Rounded(22)
    val name = monthName(offset)
    Column(
        Modifier
            .width(170.dp)
            .height(112.dp)
            .clip(shape)
            .background(
                flatOr(
                    if (offset % 2 == 0) listOf(Color(0xFF5B3CF0), Color(0xFFE0458B)) else listOf(Color(0xFF0E8C7F), Color(0xFF2F5FE0)),
                    flat = Surface2,
                ),
            )
            .glass(shape, fill = 0.04f, edge = 0.18f)
            .bounceClick(pressedScale = 0.96f, onClick = onClick)
            .padding(Space.m),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Ton récap", color = Txt2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Column {
            Text(name.replaceFirstChar { it.uppercase() }, color = Txt, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (offset == 0) "en cours" else "à revoir", color = Txt.copy(alpha = 0.75f), fontSize = 12.sp)
        }
    }
}

/** Import de l'historique Last.fm (pseudo + clé API gratuite). */
@Composable
private fun LastFmCard() {
    val status by LastFm.status.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    var user by rememberSaveable { mutableStateOf(LastFm.user) }
    var key by rememberSaveable { mutableStateOf(LastFm.apiKey) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shape = Rounded(22)

    Column(
        Modifier.fillMaxWidth().glass(shape, fill = 0.07f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).bounceClick(pressedScale = 0.98f) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Historique Last.fm", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    when (val st = status) {
                        is LastFm.Status.Running -> "Import… page ${st.page}/${st.pages} · ${st.count} écoutes"
                        is LastFm.Status.Failed -> st.message
                        is LastFm.Status.Done -> "${scrobbles.size} écoutes importées (+${st.count})"
                        else -> if (scrobbles.isNotEmpty()) "${scrobbles.size} écoutes importées" else "Pour que tes stats ne partent pas de zéro"
                    },
                    color = if (status is LastFm.Status.Failed) Ko else Txt2, fontSize = 13.sp,
                )
            }
            Text(if (expanded) "Fermer" else if (scrobbles.isEmpty()) "Importer" else "Mettre à jour", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        (status as? LastFm.Status.Running)?.let { r ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(PillShape).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(r.page.toFloat() / r.pages.coerceAtLeast(1)).height(4.dp).background(Txt))
            }
        }
        androidx.compose.animation.AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                LfField(user, { user = it }, "Ton pseudo Last.fm")
                LfField(key, { key = it }, "Clé API Last.fm")
                Text(
                    "Clé gratuite : crée-la sur last.fm/api/account/create (nom d'appli au choix), puis colle « API key » ici.",
                    color = Txt3, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).bounceClick(pressedScale = 0.98f) {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.last.fm/api/account/create"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PillButton(if (scrobbles.isEmpty()) "Importer" else "Mettre à jour") {
                        if (user.isNotBlank() && key.isNotBlank()) { LastFm.import(user, key); expanded = false }
                    }
                    if (scrobbles.isNotEmpty()) PillButton("Effacer l'import", filled = false) { LastFm.forget() }
                }
            }
        }
    }
}

@Composable
private fun LfField(value: String, onChange: (String) -> Unit, hint: String) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().height(46.dp).glass(shape, fill = 0.08f, edge = 0.14f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, color = Txt3, fontSize = 15.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Txt),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Ombre teintée (couleur de la pochette au lieu du noir). */
private fun Modifier.tintShadow(elevation: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape, color: Color): Modifier =
    this.then(androidx.compose.ui.Modifier.shadow(elevation, shape, ambientColor = color, spotColor = color))

/** Carte d'entrée du mur de pochettes. */
@Composable
private fun WallEntryCard(acc: CoverColors, onClick: () -> Unit) {
    val shape = Rounded(22)
    val recent by History.entries.collectAsState()
    val covers = remember(recent) { recent.sortedByDescending { it.lastPlayed }.mapNotNull { e -> e.coverUrl ?: e.artPath?.let { java.io.File(it) } }.take(4) }
    Row(
        Modifier.fillMaxWidth().glass(shape, fill = 0.10f).bounceClick(pressedScale = 0.97f, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // petit aperçu : les 4 dernières pochettes qui se chevauchent
        Box(Modifier.width(118.dp).height(56.dp)) {
            covers.forEachIndexed { i, c ->
                Cover(model = c, radius = 10.dp, modifier = Modifier.padding(start = (i * 20).dp).size(56.dp).tintShadow(8.dp, RoundedCornerShape(10.dp), Color.Black))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Ton mur", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("Tout ce que tu as écouté, en pochettes", color = Txt2, fontSize = 13.sp, maxLines = 2)
        }
    }
}
