package com.theo.remote

/**
 * Aléatoire malin : jamais deux sons du même artiste à la suite,
 * et les sons écoutés ces derniers jours passent plutôt en fin de liste.
 */
object SmartShuffle {
    fun <T> order(list: List<T>, title: (T) -> String, artist: (T) -> String): List<T> {
        if (!Features.flags.value.smartShuffle || list.size < 3) return list.shuffled()
        val now = System.currentTimeMillis()
        val last = History.entries.value.associate { it.key to it.lastPlayed }
        // mélange pondéré : un peu de hasard + une pénalité pour ce qui vient de passer
        val scored = list.map { x ->
            val played = last[History.keyOf(title(x), artist(x))] ?: 0L
            val penalty = when {
                now - played < 24 * 3600_000L -> 0.7
                now - played < 3 * 24 * 3600_000L -> 0.35
                else -> 0.0
            }
            x to (Math.random() + penalty)
        }.sortedBy { it.second }.map { it.first }
        // on évite deux fois le même artiste d'affilée
        return QueueLogic.spreadArtists(scored, artist)
    }
}
