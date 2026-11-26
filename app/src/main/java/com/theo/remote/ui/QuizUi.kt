package com.theo.remote.ui

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theo.remote.Blocked
import com.theo.remote.History
import com.theo.remote.LastFm
import com.theo.remote.Lyrics
import com.theo.remote.TidalRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quiz paroles : une ligne d'un de tes sons, trouve la suite… ou le titre. */
object LyricsQuiz {
    data class SongLyrics(val title: String, val artist: String, val cover: Any?, val lines: List<Lyrics.Line>)

    data class Question(
        val index: Int,
        val total: Int,
        val song: SongLyrics,
        /** true : trouver la ligne suivante ; false : trouver le titre */
        val nextLine: Boolean,
        val prompt: List<String>,
        val choices: List<String>,
        val correct: Int,
        val lineTime: Long?,
        val startedAt: Long,
    ) {
        fun remaining(now: Long = SystemClock.elapsedRealtime()) = (ROUND_MS - (now - startedAt)).coerceAtLeast(0)
    }

    sealed class Phase {
        data object Off : Phase()
        data class Loading(val text: String) : Phase()
        data class Ask(val q: Question, val picked: Int?) : Phase()
        data class Reveal(val q: Question, val picked: Int?, val gained: Int) : Phase()
        data class Done(val score: Int, val good: Int, val total: Int) : Phase()
    }

    const val ROUND_MS = 15_000L
    val phase = MutableStateFlow<Phase>(Phase.Off)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var answer: Int? = null
    private var score = 0
    private var good = 0

    private fun clean(x: String) = x.trim().replace(Regex("\\s+"), " ")

    private suspend fun load(e: Ev): SongLyrics? {
        val r = runCatching { Lyrics.fetch(e.title, e.artist, "", e.durMs) }.getOrNull() ?: return null
        val lines = r.synced?.filter { it.text.isNotBlank() }
            ?: r.plain?.lines()?.filter { it.isNotBlank() }?.map { Lyrics.Line(-1L, it) }
            ?: return null
        val ok = lines.map { Lyrics.Line(it.timeMs, clean(it.text)) }.filter { it.text.length >= 8 && !it.text.startsWith("[") }
        return if (ok.size >= 8) SongLyrics(e.title, e.artist, e.cover, ok) else null
    }

    fun start(rounds: Int = 10) {
        stop()
        score = 0; good = 0
        job = scope.launch {
            phase.value = Phase.Loading("Je prépare les paroles de tes sons…")
            val pool = withContext(Dispatchers.Default) {
                val ev = buildEvents(History.entries.value, LastFm.scrobbles.value)
                val recent = ev.filter { it.ts >= System.currentTimeMillis() - 120L * 24 * 3600_000 }.ifEmpty { ev }
                recent.groupBy { it.key }.values.sortedByDescending { it.size }.take(50)
                    .map { l -> l.firstOrNull { it.cover != null } ?: l.first() }
                    .filterNot { Blocked.isBlocked(it.title, it.artist) }.shuffled()
            }
            val songs = ArrayList<SongLyrics>()
            val iter = pool.iterator()
            // assez de paroles pour commencer, le reste arrive pendant la partie
            while (songs.size < minOf(rounds, 6) && iter.hasNext()) withContext(Dispatchers.IO) { load(iter.next()) }?.let { s -> songs += s }
            if (songs.size < 4) {
                phase.value = Phase.Off
                TidalRemote.showToast("Pas assez de paroles trouvées pour tes sons")
                return@launch
            }
            val more = launch(Dispatchers.IO) { while (songs.size < rounds + 4 && iter.hasNext()) load(iter.next())?.let { s -> synchronized(songs) { songs += s } } }
            val used = HashSet<String>()
            var played = 0
            for (i in 0 until rounds) {
                val candidates = synchronized(songs) { songs.filter { it.title !in used } }
                val song = candidates.randomOrNull() ?: break
                used += song.title
                val all = synchronized(songs) { songs.toList() }
                val nextLine = Math.random() < 0.6
                val q = if (nextLine) {
                    // une ligne qui n'est pas répétée (le refrain revient trop souvent)
                    val idx = (1 until song.lines.size - 1).filter { k ->
                        val t = song.lines[k + 1].text
                        song.lines.count { it.text == t } == 1 && song.lines[k].text != t
                    }.randomOrNull() ?: (1 until song.lines.size - 1).random()
                    val right = song.lines[idx + 1].text
                    val pool = (all.filter { it !== song }.flatMap { s -> s.lines.map { it.text } } + song.lines.map { it.text })
                        .filter { it != right && it != song.lines[idx].text }.distinct().shuffled().take(3)
                    val choices = (pool + right).shuffled()
                    Question(i, rounds, song, true, listOf(song.lines[idx - 1].text, song.lines[idx].text), choices, choices.indexOf(right),
                        song.lines[idx].timeMs.takeIf { it >= 0 }, SystemClock.elapsedRealtime())
                } else {
                    val start = (0 until song.lines.size - 1).random()
                    val others = all.filter { it.title != song.title }.map { it.title }.distinct().shuffled().take(3)
                    val choices = (others + song.title).shuffled()
                    Question(i, rounds, song, false, listOf(song.lines[start].text, song.lines[start + 1].text), choices, choices.indexOf(song.title),
                        song.lines[start].timeMs.takeIf { it >= 0 }, SystemClock.elapsedRealtime())
                }
                if (q.choices.size < 4) continue
                answer = null
                phase.value = Phase.Ask(q, null)
                while (q.remaining() > 0 && answer == null) delay(100)
                val a = answer
                val gained = if (a == q.correct) ((100 + 900 * q.remaining().toFloat() / ROUND_MS) / 10).toInt() * 10 else 0
                if (gained > 0) good++
                score += gained
                played++
                phase.value = Phase.Reveal(q, a, gained)
                delay(4000)
            }
            more.cancel()
            phase.value = Phase.Done(score, good, played)
            com.theo.remote.Rewards.addGameXp(score)
        }
    }

    fun pick(i: Int) {
        val p = phase.value as? Phase.Ask ?: return
        if (answer != null) return
        answer = i
        phase.value = p.copy(picked = i)
    }

    fun stop() {
        job?.cancel(); job = null
        phase.value = Phase.Off
    }
}

@Composable
fun LyricsQuizPanel() {
    val ctx = LocalContext.current
    val p by LyricsQuiz.phase.collectAsState()
    val scroll = rememberScrollState()
    DismissWhenAtTop(scroll)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = Space.gutter).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PanelTitle("Quiz paroles")
        when (val ph = p) {
            is LyricsQuiz.Phase.Off -> QuizSetup(ctx)
            is LyricsQuiz.Phase.Loading -> Text(ph.text, color = Txt2, fontSize = 15.sp)
            is LyricsQuiz.Phase.Ask -> QuizAsk(ph.q, ph.picked)
            is LyricsQuiz.Phase.Reveal -> QuizReveal(ph.q, ph.picked, ph.gained)
            is LyricsQuiz.Phase.Done -> QuizDone(ctx, ph)
        }
    }
}

@Composable
private fun QuizSetup(ctx: Context) {
    val best = remember { ctx.getSharedPreferences("hush", Context.MODE_PRIVATE).getInt("lyricsQuizBest", 0) }
    Text("Hush affiche des paroles de tes sons préférés. Trouve la ligne qui suit, ou le titre du son. 15 secondes par question, plus tu vas vite, plus tu marques.", color = Txt2, fontSize = 15.sp, lineHeight = 20.sp)
    if (best > 0) Text("Ton record : $best points", color = Ok, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        PillButton("10 questions") { LyricsQuiz.start(10) }
        PillButton("5 questions", filled = false) { LyricsQuiz.start(5) }
    }
}

@Composable
private fun QuizAsk(q: LyricsQuiz.Question, picked: Int?) {
    var now by androidx.compose.runtime.remember(q.index) { androidx.compose.runtime.mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(q.index) { while (true) { now = SystemClock.elapsedRealtime(); delay(100) } }
    val left = q.remaining(now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Question ${q.index + 1} / ${q.total}", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("${left / 1000 + 1} s", color = if (left < 5000) Ko else Txt, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
    Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(Color.White.copy(alpha = 0.10f))) {
        Box(Modifier.fillMaxWidth(left.toFloat() / LyricsQuiz.ROUND_MS).height(6.dp).clip(PillShape).background(Txt))
    }
    QuizLyricsCard(q)
    Text(if (q.nextLine) "Quelle est la ligne suivante ?" else "C'est quel son ?", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    q.choices.forEachIndexed { i, c ->
        QuizChoice(c, when { picked == i -> 1; picked != null -> 2; else -> 0 }) { LyricsQuiz.pick(i) }
    }
}

@Composable
private fun QuizLyricsCard(q: LyricsQuiz.Question) {
    Column(Modifier.fillMaxWidth().glass(Rounded(22), fill = 0.08f, edge = 0.14f).padding(Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        q.prompt.forEachIndexed { i, line ->
            Text(
                "« $line »", color = if (i == q.prompt.lastIndex) Txt else Txt2, fontSize = if (i == q.prompt.lastIndex) 20.sp else 16.sp,
                fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, lineHeight = 26.sp,
            )
        }
        if (q.nextLine) Text("${q.song.title} · ${q.song.artist}", color = Txt3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuizReveal(q: LyricsQuiz.Question, picked: Int?, gained: Int) {
    Text(
        when { picked == null -> "Temps écoulé !"; gained > 0 -> "Bien joué ! +$gained"; else -> "Raté…" },
        color = if (gained > 0) Ok else if (picked == null) Txt else Ko, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
    )
    QuizLyricsCard(q)
    q.choices.forEachIndexed { i, c -> QuizChoice(c, when { i == q.correct -> 3; i == picked -> 4; else -> 2 }) {} }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cover(model = q.song.cover, radius = 10.dp, modifier = Modifier.padding(end = 12.dp).size(52.dp))
        Column(Modifier.weight(1f)) {
            Text(q.song.title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(q.song.artist, color = Txt2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "Écouter ›", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.95f) {
                LyricsQuiz.stop()
                TidalRemote.playTitle(q.song.title, q.song.artist)
                q.lineTime?.let { t -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ TidalRemote.seekTo((t - 2000).coerceAtLeast(0)) }, 2500) }
            }.padding(6.dp),
        )
    }
}

@Composable
private fun QuizDone(ctx: Context, d: LyricsQuiz.Phase.Done) {
    val prefs = remember { ctx.getSharedPreferences("hush", Context.MODE_PRIVATE) }
    val oldBest = remember { prefs.getInt("lyricsQuizBest", 0) }
    LaunchedEffect(d.score) { if (d.score > oldBest) prefs.edit().putInt("lyricsQuizBest", d.score).apply() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Spacer(Modifier.height(Space.l))
        Text("Partie terminée", color = Txt2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("${d.score} points", color = Txt, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
        Text("${d.good} bonne${if (d.good > 1) "s" else ""} réponse${if (d.good > 1) "s" else ""} sur ${d.total}", color = Txt2, fontSize = 15.sp)
        if (d.score > oldBest && oldBest > 0) Text("Nouveau record !", color = Ok, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(Space.s))
        PillButton("Rejouer") { LyricsQuiz.start(d.total.coerceAtLeast(5)) }
        PillButton("Terminer", filled = false) { LyricsQuiz.stop() }
    }
}

/** state : 0 normal, 1 choisi, 2 grisé, 3 bonne réponse, 4 mauvaise réponse */
@Composable
private fun QuizChoice(text: String, state: Int, onClick: () -> Unit) {
    val shape = Rounded(18)
    val bg = when (state) { 1 -> Color.White.copy(alpha = 0.28f); 3 -> Ok.copy(alpha = 0.85f); 4 -> Ko.copy(alpha = 0.8f); 2 -> Color.White.copy(alpha = 0.04f); else -> Color.White.copy(alpha = 0.09f) }
    val fg = if (state == 3) Ink else if (state == 2) Txt3 else Txt
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(bg).glass(shape, fill = 0f, edge = 0.12f)
            .bounceClick(pressedScale = 0.96f, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, textAlign = TextAlign.Start) }
}
