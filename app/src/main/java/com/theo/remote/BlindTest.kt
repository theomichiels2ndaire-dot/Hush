package com.theo.remote

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Blind test : Hush lance un extrait, il faut trouver le bon titre parmi 4.
 * En solo dans l'appli, ou en soirée : les invités répondent sur leur téléphone (page du QR code).
 */
object BlindTest {

    /** Un son du jeu (result = son Tidal précis si on l'a, sinon on cherche titre + artiste). */
    data class Song(val title: String, val artist: String, val cover: Any?, val result: TidalApi.Result? = null) {
        val key get() = norm(title) + "|" + norm(artist.split(",", "&").first())
    }

    /** Niveaux : facile = 4 choix avec l'artiste, extrait au refrain ; moyen = 6 titres seuls, extrait au hasard ;
     *  difficile = il faut taper le titre. */
    enum class Level(val label: String, val desc: String, val choices: Int, val roundMs: Long, val mult: Float, val typed: Boolean, val showArtist: Boolean) {
        Easy("Facile", "4 choix avec l'artiste, 25 s, extrait au refrain", 4, 25_000L, 1f, false, true),
        Medium("Moyen", "6 titres sans l'artiste, 15 s, extrait au hasard", 6, 15_000L, 1.5f, false, false),
        Hard("Difficile", "Tu tapes le titre toi-même, 30 s", 0, 30_000L, 2f, true, false),
    }

    data class Round(
        val index: Int,
        val total: Int,
        val answer: Song,
        val choices: List<Song>,
        val correct: Int,
        val startedAt: Long,
        val durationMs: Long,
        val level: Level = Level.Easy,
    ) {
        fun remaining(now: Long = SystemClock.elapsedRealtime()) = (durationMs - (now - startedAt)).coerceAtLeast(0)
    }

    sealed class Phase {
        data object Off : Phase()
        data class Loading(val index: Int, val total: Int) : Phase()
        data class Playing(val round: Round) : Phase()
        data class Reveal(val round: Round) : Phase()
        data object Finished : Phase()
    }

    const val HOST = "Toi"
    private const val ROUND_MS = 20_000L
    private const val REVEAL_MS = 4_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _phase = MutableStateFlow<Phase>(Phase.Off)
    val phase: StateFlow<Phase> = _phase

    /** Points de chaque joueur. */
    private val _scores = MutableStateFlow<Map<String, Int>>(emptyMap())
    val scores: StateFlow<Map<String, Int>> = _scores

    /** Réponses de la manche en cours : joueur → (choix, points gagnés). */
    private val answers = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val _answered = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val answered: StateFlow<Map<String, Pair<Int, Int>>> = _answered

    /** Réponses tapées (niveau difficile) : joueur → texte. */
    private val typed = ConcurrentHashMap<String, String>()
    private val _typed = MutableStateFlow<Map<String, String>>(emptyMap())
    val typedAnswers: StateFlow<Map<String, String>> = _typed

    /** Mode soirée : les invités peuvent répondre depuis la page du QR code. */
    @Volatile var party = false
        private set

    val active get() = _phase.value !is Phase.Off && _phase.value !is Phase.Finished

    fun start(pool: List<Song>, rounds: Int = 10, withParty: Boolean, level: Level = Level.Easy) {
        stop()
        val songs = pool.distinctBy { it.key }.shuffled()
        if (songs.size < maxOf(4, level.choices)) {
            TidalRemote.showToast("Il faut au moins ${maxOf(4, level.choices)} sons différents pour ce niveau")
            return
        }
        party = withParty
        _scores.value = mapOf(HOST to 0)
        History.paused = true // les extraits ne comptent pas dans tes stats
        val total = rounds.coerceAtMost(songs.size)
        val picks = songs.take(total)
        job = scope.launch {
            try {
                picks.forEachIndexed { i, answer ->
                    _phase.value = Phase.Loading(i, total)
                    answers.clear(); _answered.value = emptyMap()
                    typed.clear(); _typed.value = emptyMap()
                    val choices = if (level.typed) emptyList() else (songs.filter { it.key != answer.key }.shuffled().take(level.choices - 1) + answer).shuffled()

                    // lance le son puis saute au milieu (pas d'intro trop facile… ni trop dure)
                    answer.result?.let { TidalRemote.play(it) } ?: TidalRemote.playTitle(answer.title, answer.artist)
                    val ready = waitForTrack(answer)
                    val dur = TidalRemote.state.value.track?.durationMs ?: 0L
                    if (ready && dur > 90_000) {
                        // facile : vers le refrain ; sinon n'importe où dans le son
                        val frac = if (level == Level.Easy) 0.42 else 0.15 + kotlin.random.Random.nextDouble() * 0.6
                        TidalRemote.seekTo((dur * frac).toLong())
                    }
                    delay(600)

                    val round = Round(i, total, answer, choices, choices.indexOf(answer), SystemClock.elapsedRealtime(), level.roundMs, level)
                    _phase.value = Phase.Playing(round)
                    while (round.remaining() > 0 && !everyoneAnswered()) delay(200)

                    _phase.value = Phase.Reveal(round)
                    delay(REVEAL_MS)
                }
                _phase.value = Phase.Finished
                Rewards.addGameXp(_scores.value[HOST] ?: 0)
            } finally {
                History.paused = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        History.paused = false
        _phase.value = Phase.Off
        party = false
    }

    /** Un joueur répond (une seule fois par manche). */
    fun answer(player: String, choice: Int): Boolean {
        val p = _phase.value as? Phase.Playing ?: return false
        val r = p.round
        if (answers.containsKey(player) || choice !in r.choices.indices) return false
        val gained = if (choice == r.correct) points(r) else 0
        answers[player] = choice to gained
        _answered.value = HashMap(answers)
        _scores.value = _scores.value.toMutableMap().apply { merge(player, gained, Int::plus) }
        return true
    }

    private fun points(r: Round): Int {
        val left = r.remaining().toFloat() / r.durationMs
        return (((100 + 900 * left) * r.level.mult) / 10).toInt() * 10
    }

    /** Niveau difficile : un joueur tape le titre (une seule fois par manche). */
    fun answerText(player: String, text: String): Boolean {
        val p = _phase.value as? Phase.Playing ?: return false
        val r = p.round
        if (!r.level.typed || answers.containsKey(player) || text.isBlank()) return false
        val ok = matches(text, r.answer.title)
        val gained = if (ok) points(r) else 0
        answers[player] = (if (ok) 0 else -1) to gained
        typed[player] = text.trim()
        _answered.value = HashMap(answers)
        _typed.value = HashMap(typed)
        _scores.value = _scores.value.toMutableMap().apply { merge(player, gained, Int::plus) }
        return true
    }

    /** Titre tapé « assez proche » : sans accents ni ponctuation, petites fautes acceptées. */
    fun matches(guess: String, title: String): Boolean {
        fun clean(x: String) = java.text.Normalizer.normalize(x.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
            .replace(Regex("\\s+(feat|ft)\\.?\\s.*$"), "")
            .replace(Regex("[^a-z0-9]"), "")
        val g = clean(guess); val t = clean(title)
        if (g.isEmpty() || t.isEmpty()) return false
        if (g == t) return true
        if (t.length >= 4 && g.length >= (t.length * 0.6).toInt() && (t.contains(g) || g.contains(t))) return true
        return levenshtein(g, t) <= maxOf(1, t.length / 6)
    }

    private fun levenshtein(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Un invité arrive dans la partie. */
    fun join(player: String) {
        if (!_scores.value.containsKey(player)) _scores.value = _scores.value + (player to 0)
    }

    private fun everyoneAnswered(): Boolean {
        val players = _scores.value.keys
        // en solo, on passe dès que tu as répondu ; en soirée, quand tout le monde a répondu
        return players.isNotEmpty() && players.all { answers.containsKey(it) }
    }

    private suspend fun waitForTrack(s: Song): Boolean {
        val want = norm(s.title)
        var waited = 0
        while (waited < 7000) {
            val t = TidalRemote.state.value.track
            if (t != null && (norm(t.title).startsWith(want.take(12)) || want.startsWith(norm(t.title).take(12)))) return true
            delay(200); waited += 200
        }
        return false
    }

    private fun norm(x: String) = x.lowercase(Locale.ROOT).replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
}
