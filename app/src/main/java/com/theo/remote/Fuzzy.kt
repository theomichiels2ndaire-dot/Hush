package com.theo.remote

import java.text.Normalizer

/**
 * Recherche tolérante : trouve « Drake » en tapant « drak » ou « darke », sans accents ni majuscules.
 * Pur Kotlin (testé automatiquement avant chaque version).
 */
object Fuzzy {
    private val marks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** « Jok'Air » → « jokair », « Ève » → « eve ». */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "").replace(nonWord, "")

    fun words(s: String): List<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "")
            .split(nonWord).filter { it.isNotEmpty() }

    /** Distance d'édition avec inversion de deux lettres (« darke » → « drake » = 1). */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[a.length][b.length]
    }

    /** Fautes tolérées selon la longueur du mot tapé. */
    fun allowed(len: Int) = when { len <= 3 -> 0; len <= 6 -> 1; else -> 2 }

    /**
     * Un mot tapé correspond à un mot du texte : début identique (« dra » → « drake »),
     * ou presque pareil avec une ou deux fautes (début compris : « drak » ≈ « drake »).
     */
    private fun wordScore(typed: String, word: String): Int {
        if (word.startsWith(typed)) return if (word.length == typed.length) 100 else 80
        val tol = allowed(typed.length)
        if (tol == 0) return 0
        // on compare au début du mot de la même longueur (on tape souvent le début), et au mot entier
        val head = word.take(typed.length)
        val dist = minOf(distance(typed, head), distance(typed, word))
        return if (dist <= tol) 60 - dist * 15 else 0
    }

    /**
     * Note de correspondance entre ce qui est tapé et un texte (titre + artiste). 0 = ne correspond pas.
     * Chaque mot tapé doit trouver un mot du texte.
     */
    fun score(query: String, text: String): Int {
        val q = words(query)
        if (q.isEmpty()) return 0
        val w = words(text)
        if (w.isEmpty()) return 0
        var total = 0
        for (t in q) {
            val best = w.maxOf { wordScore(t, it) }
            if (best == 0) {
                // mots collés (« jokair » pour « jok air ») : on regarde le texte entier sans espaces
                if (norm(text).contains(t)) total += 50 else return 0
            } else total += best
        }
        return total / q.size
    }

    /**
     * Corrige les fautes d'après des noms connus (tes artistes, tes sons) : « drak » → « drake ».
     * Renvoie null si rien à corriger.
     */
    fun correct(query: String, vocab: Collection<String>): String? {
        val known = HashSet<String>()
        vocab.forEach { known.addAll(words(it)) }
        if (known.isEmpty()) return null
        val typed = words(query)
        if (typed.isEmpty()) return null
        var changed = false
        val out = typed.map { t ->
            if (t in known || t.length < 4) return@map t
            // mot de 4 lettres (« rois », « love »…) : souvent juste. On ne le complète que s'il manque
            // juste la fin (« drak » → « drake »), jamais en changeant le début (« rois » ≠ « crois »)
            val short = t.length == 4
            val tol = allowed(t.length)
            val best = known.asSequence()
                .filter { kotlin.math.abs(it.length - t.length) <= tol }
                .filter { !short || (it.startsWith(t) && it.length == t.length + 1) }
                .map { it to distance(t, it) }
                .filter { it.second in 1..tol }
                .minByOrNull { it.second }
            if (best != null) { changed = true; best.first } else t
        }
        return if (changed) out.joinToString(" ") else null
    }
}
