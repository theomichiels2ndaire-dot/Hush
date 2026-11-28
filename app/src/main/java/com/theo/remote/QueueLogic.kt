package com.theo.remote

import java.util.Locale

/**
 * Règles de la file d'attente, du lancement et du mélange, sans rien d'Android :
 * testées automatiquement avant chaque version, pour qu'un bug corrigé ne revienne pas.
 */
object QueueLogic {
    /** Déplace un son dans la file (ordre inchangé si les places sont invalides). */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> =
        if (from !in list.indices || to !in list.indices || from == to) list
        else list.toMutableList().apply { add(to, removeAt(from)) }

    /** Retire le son à cette place. */
    fun <T> removeAt(list: List<T>, index: Int): List<T> = list.filterIndexed { i, _ -> i != index }

    fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Même titre (sans tenir compte des majuscules, accents de ponctuation, espaces). */
    fun sameTitle(actual: String, expected: String) = norm(actual) == norm(expected)

    /**
     * Le son lancé par Tidal est-il bien celui demandé ? Même titre, et même artiste
     * (un son du même nom par quelqu'un d'autre n'est pas le bon).
     */
    fun isRightSong(actualTitle: String, actualArtist: String, expectedTitle: String, expectedArtist: String): Boolean {
        if (!sameTitle(actualTitle, expectedTitle)) return false
        if (expectedArtist.isBlank()) return true
        val a = norm(actualArtist); val e = norm(expectedArtist)
        return a.contains(e) || e.contains(a)
    }

    /** Artiste principal (« Ninho, Jul » → « ninho », « Drake feat. X » → « drake »). */
    fun mainArtist(a: String) = a.split(",", "&", " feat", " ft.").first().trim().lowercase(Locale.ROOT)

    /**
     * Sauts fantômes : des « suivant » qui arrivent en rafale (chaque son coupé après quelques secondes),
     * écran éteint. Au 3e d'affilée, on bloque cette source pendant une minute.
     * [skips] = heures des derniers « suivant » acceptés venant de cette source (ms), du plus ancien au plus récent.
     */
    fun isGhostSkip(skips: List<Long>, now: Long, screenOn: Boolean, gapMs: Long = 20_000): Boolean {
        if (screenOn) return false
        if (skips.size < 2) return false
        val a = skips[skips.size - 2]; val b = skips[skips.size - 1]
        return b - a < gapMs && now - b < gapMs
    }

    /** Le titre précise une version (« Challenge Version », « Remix », « Sped Up »…). */
    fun hasVersion(title: String): Boolean = Regex("""\(([^)]*)\)\s*$""").containsMatchIn(title.trim()) &&
        !Regex("""\((feat|ft|with)[ .]""", RegexOption.IGNORE_CASE).containsMatchIn(title)

    /** « "My House (Challenge Version)" Artiste » : le titre exact entre guillemets, puis l'artiste. */
    fun preciseQuery(expected: String, artist: String, query: String): String {
        val who = artist.ifBlank {
            // pas d'artiste connu : ce qui reste de la recherche une fois les mots du titre enlevés
            val titleWords = Fuzzy.words(expected).toSet()
            query.split(" ").filter { it.isNotBlank() && Fuzzy.norm(it) !in titleWords }.joinToString(" ")
        }
        return "\"$expected\" $who".trim()
    }

    /** Mélange équitable (Fisher–Yates) : chaque ordre a exactement la même chance. */
    fun <T> fairShuffle(list: List<T>, rnd: java.util.Random): List<T> {
        val a = list.toMutableList()
        for (i in a.lastIndex downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a
    }

    /**
     * « Frais d'abord » : les sons pas écoutés depuis longtemps (ou jamais) passent en premier,
     * ceux d'aujourd'hui tout à la fin ; dans chaque groupe, l'ordre est vraiment au hasard.
     */
    fun <T> freshOrder(list: List<T>, lastPlayed: (T) -> Long, now: Long, rnd: java.util.Random): List<T> {
        val day = 24 * 3600_000L
        fun bucket(x: T): Int {
            val p = lastPlayed(x)
            val age = now - p
            return when {
                p <= 0L || age >= 7 * day -> 0
                age >= 3 * day -> 1
                age >= day -> 2
                else -> 3
            }
        }
        return list.groupBy { bucket(it) }.toSortedMap().values.flatMap { fairShuffle(it, rnd) }
    }

    /**
     * Espace les artistes : jamais le même artiste dans les [window] derniers sons quand c'est possible
     * (sinon au moins pas deux d'affilée), en gardant au mieux l'ordre donné.
     */
    fun <T> spreadArtists(list: List<T>, window: Int = 3, artist: (T) -> String): List<T> {
        val pool = list.map { it to mainArtist(artist(it)) }.toMutableList()
        val out = ArrayList<T>(pool.size)
        val recent = ArrayDeque<String>()
        while (pool.isNotEmpty()) {
            // on ne regarde qu'un peu devant (rapide même pour 5000 sons) : l'ordre reste aléatoire
            val look = minOf(pool.size, 60)
            var i = (0 until look).firstOrNull { pool[it].second !in recent } ?: -1
            if (i < 0) i = (0 until look).firstOrNull { pool[it].second != recent.lastOrNull() } ?: 0
            val (item, a) = pool.removeAt(i)
            out += item
            recent.addLast(a)
            while (recent.size > window) recent.removeFirst()
        }
        return out
    }
}
