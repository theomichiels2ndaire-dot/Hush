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
     * Évite deux sons du même artiste d'affilée quand c'est possible, en gardant au mieux l'ordre donné.
     */
    fun <T> spreadArtists(list: List<T>, artist: (T) -> String): List<T> {
        val pool = list.toMutableList()
        val out = ArrayList<T>(pool.size)
        while (pool.isNotEmpty()) {
            val prev = out.lastOrNull()?.let { mainArtist(artist(it)) }
            val i = pool.indexOfFirst { mainArtist(artist(it)) != prev }.let { if (it < 0) 0 else it }
            out += pool.removeAt(i)
        }
        return out
    }
}
