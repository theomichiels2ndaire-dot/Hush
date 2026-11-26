package com.theo.remote.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Features
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Objectif du jour, comparaison des mois et carte des heures, selon les options. */
@Composable
fun StatsExtras(events: List<Ev>) {
    val f by Features.flags.collectAsState()
    if (f.goalMin > 0) GoalCard(events, f.goalMin)
    if (f.compareMonths) CompareMonthsCard(events)
    if (f.heatmap) HeatmapCard(events)
    com.theo.remote.Rewards.equipped.collectAsState().value
    if (f.rewards && com.theo.remote.Rewards.isOn("stats:pro")) ProStatsCard(events)
    if (f.challenges) ChallengesSection(events)
    if (f.eras) ErasRow(events)
    if (f.records) RecordsCard(events)
    if (f.idCard) IdCardEntry()
    if (f.feats) StatsEntry("🤝", "Featurings", "Qui a collaboré avec qui parmi tes artistes", Panels.Kind.Feats)
    if (f.lyricWords) StatsEntry("💬", "Tes mots", "Les mots qui reviennent le plus dans tes sons", Panels.Kind.Words)
    if (f.oneSong) StatsEntry("⭐", "Un son par artiste", "Ton son préféré de chacun de tes artistes", Panels.Kind.OneSong)
    if (f.albums) StatsEntry("💿", "Tes albums", "Ton classement d'albums, et ceux que tu as écoutés en entier", Panels.Kind.Albums)
    if (f.concerts) StatsEntry("🎤", "Concerts", "Les dates de tes artistes près de chez toi", Panels.Kind.Concerts)
}

// ---------------------------------------------------------------- objectif d'écoute

@Composable
private fun GoalCard(events: List<Ev>, goal: Int) {
    val minutes = remember(events) {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        events.filter { it.ts >= c.timeInMillis }.sumOf { it.durMs } / 60_000
    }
    val p = (minutes.toFloat() / goal).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(p) { anim.animateTo(p, spring(dampingRatio = 0.8f, stiffness = 120f)) }
    val accent = Ok
    Row(
        Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.08f, edge = 0.12f).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(84.dp)) {
                val w = 9.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, style = Stroke(w))
                drawArc(accent, -90f, 360f * anim.value, false, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("${(p * 100).toInt()} %", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text("Objectif du jour", color = Txt2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("${fmtMinutes(minutes)} / ${fmtMinutes(goal.toLong())}", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (p >= 1f) "Objectif atteint, bien joué !" else "Encore ${fmtMinutes(goal - minutes)} de musique",
                color = if (p >= 1f) Ok else Txt2, fontSize = 13.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- ce mois-ci vs le mois dernier

private data class MonthStats(val minutes: Long, val plays: Int, val artists: Int, val newTracks: Int, val topArtist: String?)

@Composable
private fun CompareMonthsCard(events: List<Ev>) {
    val data by produceState<Pair<MonthStats, MonthStats>?>(null, events) {
        value = withContext(Dispatchers.Default) {
            fun stats(offset: Int): MonthStats {
                val (a, b) = monthRange(offset)
                val inMonth = events.filter { it.ts in a until b }
                val before = events.filter { it.ts < a }.map { it.key }.toHashSet()
                return MonthStats(
                    minutes = inMonth.sumOf { it.durMs } / 60_000,
                    plays = inMonth.size,
                    artists = inMonth.map { mainArtist(it.artist).lowercase() }.toSet().size,
                    newTracks = inMonth.map { it.key }.toSet().count { it !in before },
                    topArtist = inMonth.groupingBy { mainArtist(it.artist) }.eachCount().maxByOrNull { it.value }?.key,
                )
            }
            stats(0) to stats(1)
        }
    }
    val (now, prev) = data ?: return
    if (now.plays == 0 && prev.plays == 0) return
    Column(
        Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.08f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("${monthName(0).replaceFirstChar { it.uppercase() }} vs ${monthName(1)}", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        CompareRow("Temps d'écoute", fmtMinutes(now.minutes), fmtMinutes(prev.minutes), now.minutes, prev.minutes)
        CompareRow("Écoutes", "${now.plays}", "${prev.plays}", now.plays.toLong(), prev.plays.toLong())
        CompareRow("Artistes différents", "${now.artists}", "${prev.artists}", now.artists.toLong(), prev.artists.toLong())
        CompareRow("Nouveaux sons", "${now.newTracks}", "${prev.newTracks}", now.newTracks.toLong(), prev.newTracks.toLong())
        Row {
            Text("Artiste n°1", color = Txt2, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(now.topArtist ?: "—", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("  ·  ${prev.topArtist ?: "—"}", color = Txt3, fontSize = 13.sp, maxLines = 1)
        }
        Text("Le mois en cours n'est pas fini : il rattrape au fil des jours.", color = Txt3, fontSize = 11.sp)
    }
}

@Composable
private fun CompareRow(label: String, now: String, prev: String, a: Long, b: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Txt2, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(now, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        val diff = if (b > 0) ((a - b) * 100 / b).toInt() else null
        Text(
            when {
                diff == null -> "  nouveau"
                diff >= 0 -> "  ↑ $diff %"
                else -> "  ↓ ${-diff} %"
            },
            color = if (diff == null || diff >= 0) Ok else Ko, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Text("  ($prev)", color = Txt3, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- quand tu écoutes (jour × heure)

@Composable
private fun HeatmapCard(events: List<Ev>) {
    val grid by produceState<Array<IntArray>?>(null, events) {
        value = withContext(Dispatchers.Default) {
            val g = Array(7) { IntArray(24) }
            val c = Calendar.getInstance()
            val since = System.currentTimeMillis() - 90L * 24 * 3600_000
            events.forEach { e ->
                if (e.ts < since) return@forEach
                c.timeInMillis = e.ts
                // lundi = 0 … dimanche = 6
                val d = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
                g[d][c.get(Calendar.HOUR_OF_DAY)]++
            }
            g
        }
    }
    val g = grid ?: return
    val max = g.maxOf { row -> row.maxOrNull() ?: 0 }.coerceAtLeast(1)
    if (g.all { row -> row.all { it == 0 } }) return
    val accent = Ok
    val best = run {
        var bd = 0; var bh = 0; var bv = -1
        for (d in 0 until 7) for (h in 0 until 24) if (g[d][h] > bv) { bv = g[d][h]; bd = d; bh = h }
        bd to bh
    }
    val days = listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")
    Column(
        Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.08f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("Quand tu écoutes", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("Ton moment fort : le ${listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")[best.first]} vers ${best.second}h · 3 derniers mois", color = Txt2, fontSize = 13.sp)
        Row {
            Column(Modifier.padding(end = 6.dp)) {
                days.forEach { Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) { Text(it, color = Txt3, fontSize = 11.sp) } }
            }
            Canvas(Modifier.weight(1f).height(18.dp * 7)) {
                val cw = size.width / 24f
                val ch = size.height / 7f
                for (d in 0 until 7) for (h in 0 until 24) {
                    val v = g[d][h].toFloat() / max
                    drawRoundRect(
                        if (v <= 0f) Color.White.copy(alpha = 0.05f) else accent.copy(alpha = 0.18f + 0.82f * v),
                        topLeft = Offset(h * cw + 1f, d * ch + 1f),
                        size = Size(cw - 2f, ch - 2f),
                        cornerRadius = CornerRadius(3f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp)) {
            listOf("0h", "6h", "12h", "18h", "23h").forEach { Text(it, color = Txt3, fontSize = 11.sp, modifier = Modifier.weight(1f)) }
        }
    }
}


// ---------------------------------------------------------------- stats avancées (récompense)

@Composable
private fun ProStatsCard(events: List<Ev>) {
    data class Pro(val perDay: FloatArray, val months: List<Pair<String, String>>)
    val pro by produceState<Pro?>(null, events) {
        value = withContext(Dispatchers.Default) {
            val c = Calendar.getInstance()
            // minutes moyennes par jour de la semaine (3 derniers mois)
            val since = System.currentTimeMillis() - 91L * 24 * 3600_000
            val mins = FloatArray(7)
            events.forEach { e ->
                if (e.ts < since) return@forEach
                c.timeInMillis = e.ts
                mins[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7] += e.durMs / 60_000f
            }
            for (i in 0 until 7) mins[i] /= 13f
            // artiste n° 1 de chaque mois de l'année en cours
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val byMonth = HashMap<Int, HashMap<String, Long>>()
            events.forEach { e ->
                c.timeInMillis = e.ts
                if (c.get(Calendar.YEAR) != year) return@forEach
                val a = e.artist.split(",", "&").first().trim()
                if (a.isEmpty()) return@forEach
                val m = byMonth.getOrPut(c.get(Calendar.MONTH)) { HashMap() }
                m[a] = (m[a] ?: 0L) + e.durMs
            }
            val names = listOf("Janv.", "Févr.", "Mars", "Avr.", "Mai", "Juin", "Juil.", "Août", "Sept.", "Oct.", "Nov.", "Déc.")
            val months = byMonth.keys.sorted().mapNotNull { k -> byMonth[k]?.maxByOrNull { it.value }?.let { names[k] to it.key } }
            Pro(mins, months)
        }
    }
    val p = pro ?: return
    if (p.perDay.all { it == 0f } && p.months.isEmpty()) return
    val accent = Ok
    val days = listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")
    val max = (p.perDay.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Column(
        Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.08f, edge = 0.12f).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("Stats avancées", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("Minutes par jour de la semaine, en moyenne · 3 derniers mois", color = Txt2, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            p.perDay.forEachIndexed { i, v ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${v.toInt()}", color = Txt2, fontSize = 11.sp)
                    Box(Modifier.fillMaxWidth().height((70f * v / max).coerceAtLeast(2f).dp).clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.35f + 0.65f * v / max)))
                    Text(days[i], color = Txt3, fontSize = 11.sp)
                }
            }
        }
        if (p.months.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Ton artiste de chaque mois", color = Txt2, fontSize = 13.sp)
            p.months.forEach { (m, a) ->
                Row {
                    Text(m, color = Txt3, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                    Text(a, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
