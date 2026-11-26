package com.theo.remote.ui

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.LyricQuotes
import com.theo.remote.Recognizer
import com.theo.remote.TidalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.random.Random

// =====================================================================================
// Défis de la semaine
// =====================================================================================

object Challenges {
    data class Challenge(val id: String, val title: String, val desc: String, val value: Int, val goal: Int) {
        val progress get() = (value.toFloat() / goal).coerceIn(0f, 1f)
        val done get() = value >= goal
    }

    fun weekStart(): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis > System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, -1)
    }.timeInMillis

    fun compute(ev: List<Ev>): List<Challenge> {
        val start = weekStart()
        val week = ev.filter { it.ts >= start }
        val before = ev.filter { it.ts < start }
        val cal = Calendar.getInstance()
        fun day(t: Long) = cal.apply { timeInMillis = t }.get(Calendar.DAY_OF_YEAR)
        fun hour(t: Long) = cal.apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

        val knownArtists = before.map { mainArtist(it.artist).lowercase() }.toHashSet()
        val newArtists = week.map { mainArtist(it.artist).lowercase() }.toSet().count { it !in knownArtists }
        val minutes = (week.sumOf { it.durMs } / 60_000).toInt()
        val days = week.map { day(it.ts) }.toSet().size
        val variety = week.map { it.key }.toSet().size
        val mornings = week.filter { hour(it.ts) < 9 }.map { day(it.ts) }.toSet().size
        val lastBefore = HashMap<String, Long>()
        before.forEach { e -> if ((lastBefore[e.key] ?: 0) < e.ts) lastBefore[e.key] = e.ts }
        val revived = week.map { it.key }.toSet().count { k -> lastBefore[k]?.let { it < start - 90L * 24 * 3600_000 } == true }
        val artists = week.map { mainArtist(it.artist).lowercase() }.toSet().size
        val found = Recognizer.history.value.count { it.at >= start }

        val pool = listOf(
            Challenge("newArtists", "Explorateur", "Écoute 5 artistes que tu n'avais jamais écoutés", newArtists, 5),
            Challenge("hours", "Gros son", "3 h de musique cette semaine", minutes, 180),
            Challenge("days", "Régulier", "De la musique 5 jours sur 7", days, 5),
            Challenge("variety", "Curieux", "30 sons différents cette semaine", variety, 30),
            Challenge("mornings", "Matinal", "3 matins en musique avant 9 h", mornings, 3),
            Challenge("revived", "Archéologue", "Ressors 5 sons pas écoutés depuis 3 mois", revived, 5),
            Challenge("artists", "Éclectique", "10 artistes différents cette semaine", artists, 10),
            Challenge("shazam", "Détective", "Reconnais 2 sons avec « Quel est ce son ? »", found, 2),
        )
        // 3 défis, les mêmes toute la semaine, différents la semaine suivante
        val seed = (start / (7L * 24 * 3600_000)).toInt()
        return pool.shuffled(Random(seed)).take(3)
    }

    /** Retient les défis réussis ; renvoie ceux qui viennent de l'être. */
    fun recordDone(context: Context, list: List<Challenge>): List<Challenge> {
        val p = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
        val wk = weekStart().toString()
        val done = p.getStringSet("challengesDone", emptySet()).orEmpty()
        val fresh = list.filter { it.done && "$wk:${it.id}" !in done }
        if (fresh.isNotEmpty()) p.edit().putStringSet("challengesDone", done + fresh.map { "$wk:${it.id}" }).apply()
        return fresh
    }

    fun totalDone(context: Context) = context.getSharedPreferences("hush", Context.MODE_PRIVATE).getStringSet("challengesDone", emptySet()).orEmpty().size
}

@Composable
fun ChallengesSection(events: List<Ev>) {
    val ctx = LocalContext.current
    val list by produceState<List<Challenges.Challenge>>(emptyList(), events) {
        value = withContext(Dispatchers.Default) { Challenges.compute(events) }
        Challenges.recordDone(ctx, value).firstOrNull()?.let { TidalRemote.showToast("Défi réussi : ${it.title}") }
    }
    if (list.isEmpty()) return
    val daysLeft = ((Challenges.weekStart() + 7L * 24 * 3600_000 - System.currentTimeMillis()) / (24L * 3600_000)).toInt() + 1
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Défis de la semaine", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("encore $daysLeft j · ${Challenges.totalDone(ctx)} réussis", color = Txt3, fontSize = 12.sp)
        }
        list.forEach { c ->
            Column(Modifier.fillMaxWidth().glass(Rounded(18), fill = if (c.done) 0.12f else 0.07f, edge = 0.12f).padding(Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, color = if (c.done) Ok else Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text(if (c.done) "Réussi ✓" else "${c.value.coerceAtMost(c.goal)} / ${c.goal}", color = if (c.done) Ok else Txt2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(c.desc, color = Txt2, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(Color.White.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(c.progress).height(6.dp).clip(PillShape).background(if (c.done) Ok else Txt))
                }
            }
        }
    }
}

// =====================================================================================
// Sons d'une époque
// =====================================================================================

object Eras {
    data class Era(val label: String, val from: Long, val to: Long, val plays: Int, val cover: Any?)

    val open = MutableStateFlow<Era?>(null)

    private val SEASONS = listOf("Hiver", "Printemps", "Été", "Automne")

    /** Saisons passées où tu as assez écouté (la plus récente d'abord). */
    fun compute(ev: List<Ev>): List<Era> {
        if (ev.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        val limit = System.currentTimeMillis() - 30L * 24 * 3600_000
        data class K(val season: Int, val year: Int)
        fun keyOf(t: Long): K {
            cal.timeInMillis = t
            val m = cal.get(Calendar.MONTH) // 0 = janvier
            var y = cal.get(Calendar.YEAR)
            val s = when (m) { 11, 0, 1 -> 0; in 2..4 -> 1; in 5..7 -> 2; else -> 3 }
            if (m == 11) y += 1 // décembre compte pour l'hiver de l'année suivante
            return K(s, y)
        }
        fun range(k: K): Pair<Long, Long> {
            val c = Calendar.getInstance()
            c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            when (k.season) {
                0 -> { c.set(Calendar.YEAR, k.year - 1); c.set(Calendar.MONTH, 11) }
                1 -> { c.set(Calendar.YEAR, k.year); c.set(Calendar.MONTH, 2) }
                2 -> { c.set(Calendar.YEAR, k.year); c.set(Calendar.MONTH, 5) }
                else -> { c.set(Calendar.YEAR, k.year); c.set(Calendar.MONTH, 8) }
            }
            val a = c.timeInMillis
            c.add(Calendar.MONTH, 3)
            return a to c.timeInMillis
        }
        return ev.filter { it.ts < limit }.groupBy { keyOf(it.ts) }
            .filter { it.value.size >= 15 }
            .map { (k, list) ->
                val (a, b) = range(k)
                val top = list.groupingBy { it.key }.eachCount().maxByOrNull { it.value }?.key
                Era("${SEASONS[k.season]} ${k.year}", a, b, list.size, list.firstOrNull { it.key == top && it.cover != null }?.cover)
            }
            .sortedByDescending { it.from }
    }
}

@Composable
fun ErasRow(events: List<Ev>) {
    val eras by produceState<List<Eras.Era>>(emptyList(), events) { value = withContext(Dispatchers.Default) { Eras.compute(events) } }
    if (eras.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Sons d'une époque", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text("Ce que tu écoutais à chaque saison", color = Txt2, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(eras, key = { it.label }) { e ->
                Column(Modifier.width(140.dp).clip(Rounded(14)).bounceClick(pressedScale = 0.95f) { Eras.open.value = e; Panels.show(Panels.Kind.Era) }) {
                    Box {
                        Cover(model = e.cover, radius = 14.dp, modifier = Modifier.size(140.dp))
                        Box(Modifier.size(140.dp).clip(Rounded(14)).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
                        Text(e.label, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${e.plays} écoutes", color = Txt2, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun EraPanel() {
    val era by Eras.open.collectAsState()
    val e = era ?: return
    val entries by History.entries.collectAsState()
    val scrobbles by LastFm.scrobbles.collectAsState()
    val top by produceState<List<Pair<Ev, Int>>?>(null, e, entries, scrobbles) {
        value = withContext(Dispatchers.Default) {
            val ev = buildEvents(entries, scrobbles).filter { it.ts in e.from until e.to }
            val latest = HashMap<String, Ev>()
            ev.forEach { x -> val o = latest[x.key]; if (o == null || o.cover == null) latest[x.key] = x }
            ev.groupingBy { it.key }.eachCount().entries.sortedByDescending { it.value }.take(40).mapNotNull { (k, c) -> latest[k]?.let { it to c } }
                .filterNot { com.theo.remote.Blocked.isBlocked(it.first.title, it.first.artist) }
        }
    }
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = list, contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp)) {
        item {
            PanelTitle(e.label)
            Text("Tes sons les plus écoutés à cette époque · ${e.plays} écoutes", color = Txt2, fontSize = 13.sp)
            Spacer(Modifier.height(Space.m))
            val l = top.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PillButton("Remonter le temps") { if (l.isNotEmpty()) TidalRemote.playSequence(l.map { it.first.title to it.first.artist }) }
                PillButton("Aléatoire", filled = false) { if (l.isNotEmpty()) TidalRemote.playSequence(com.theo.remote.SmartShuffle.order(l, { it.first.title }, { it.first.artist }).map { it.first.title to it.first.artist }) }
            }
            Spacer(Modifier.height(Space.m))
            if (top == null) SkeletonList(6)
        }
        itemsIndexed(top.orEmpty(), key = { i, it -> "$i${it.first.key}" }) { i, (ev, c) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .bounceClick(pressedScale = 0.97f, onLongClick = { TrackMenu.open(TrackMenu.Target(ev.title, ev.artist, ev.cover)) }) {
                        TidalRemote.playSequence(top.orEmpty().drop(i).map { it.first.title to it.first.artist })
                    }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = Txt3, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Cover(model = ev.cover, radius = 6.dp, modifier = Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ev.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ev.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${c}×", color = Txt3, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// =====================================================================================
// Paroles favorites
// =====================================================================================

@Composable
fun QuotesPanel() {
    val quotes by LyricQuotes.quotes.collectAsState()
    val list = rememberLazyListState()
    DismissWhenAtTop(list)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item {
            PanelTitle("Tes paroles")
            Text(
                if (quotes.isEmpty()) "Dans l'écran des paroles, fais un appui long sur une ligne pour la garder ici."
                else "${quotes.size} ligne${if (quotes.size > 1) "s" else ""} gardée${if (quotes.size > 1) "s" else ""} · toucher pour réécouter ce passage",
                color = Txt2, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(Space.s))
        }
        items(quotes, key = { it.at.toString() + it.text.hashCode() }) { q ->
            val shape = Rounded(20)
            Box(
                Modifier.fillMaxWidth().glass(shape, fill = 0.08f, edge = 0.14f)
                    .bounceClick(pressedScale = 0.97f) {
                        TidalRemote.playTitle(q.title, q.artist)
                        // on saute au passage une fois le son lancé
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ TidalRemote.seekTo((q.timeMs - 1500).coerceAtLeast(0)) }, 2500)
                    }
                    .padding(Space.m),
            ) {
                Column(Modifier.padding(end = 28.dp)) {
                    Text("« ${q.text} »", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, lineHeight = 25.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("${q.title} · ${q.artist}", color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(30.dp).clip(PillShape).bounceClick(pressedScale = 0.85f) { LyricQuotes.remove(q) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = "Retirer", tint = Txt3, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}
