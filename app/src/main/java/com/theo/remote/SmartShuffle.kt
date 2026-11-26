package com.theo.remote

/**
 * Aléatoire malin et vraiment au hasard : mélange équitable, les sons pas écoutés depuis longtemps
 * d'abord, ceux d'aujourd'hui à la fin, et jamais le même artiste dans les 3 derniers sons.
 */
object SmartShuffle {
    private val rnd = java.security.SecureRandom()

    fun <T> order(list: List<T>, title: (T) -> String, artist: (T) -> String): List<T> {
        if (!Features.flags.value.smartShuffle || list.size < 3) return QueueLogic.fairShuffle(list, rnd)
        val now = System.currentTimeMillis()
        val last = History.entries.value.associate { it.key to it.lastPlayed }
        val fresh = QueueLogic.freshOrder(list, { last[History.keyOf(title(it), artist(it))] ?: 0L }, now, rnd)
        return QueueLogic.spreadArtists(fresh, window = 3, artist = artist)
    }
}
