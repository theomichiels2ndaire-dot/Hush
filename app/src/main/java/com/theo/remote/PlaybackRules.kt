package com.theo.remote

/**
 * Les règles qui décident quand un son est fini et quand Hush enchaîne.
 * Sans rien d'Android : testées avant chaque version, pour que les sons ne se coupent plus en plein milieu.
 */
object PlaybackRules {
    /**
     * On ne croit la position que si Tidal l'a donnée APRÈS le changement de son (sinon c'est celle de l'ancien
     * son → fausse fin), que le son tourne depuis plus de 4 s, et qu'elle n'est pas extrapolée depuis trop longtemps.
     */
    fun trustPosition(positionUpdatedAt: Long, positionMs: Long, keyChangedAt: Long, durationMs: Long, now: Long): Boolean {
        val fresh = positionUpdatedAt == 0L || positionUpdatedAt >= keyChangedAt - 300 || positionMs < 10_000
        val settled = now - keyChangedAt > 4000
        val sane = positionUpdatedAt == 0L || now - positionUpdatedAt < durationMs + 5000
        return fresh && settled && sane
    }

    /**
     * Le son est-il vraiment fini ?
     * - en lecture : il reste moins de 1,2 s ET Tidal lui-même a donné une position proche de la fin
     *   (l'horloge de Hush seule peut être en avance) ;
     * - en pause/arrêt : Tidal s'est arrêté à moins de 5 s de la fin.
     */
    fun isEnding(
        isPlaying: Boolean, durationMs: Long, reportedPos: Long, livePos: Long,
        nearEndRecently: Boolean,
    ): Boolean {
        val remaining = durationMs - livePos
        val reportedLeft = durationMs - reportedPos
        return if (isPlaying) remaining in 0..1200 && reportedLeft < 15_000
        else reportedLeft < 5000 && ((livePos > 0 && remaining < 1500) || nearEndRecently)
    }

    /**
     * Tidal a enchaîné tout seul sur son propre son à la fin du nôtre, alors que Hush avait prévu la suite :
     * on rattrape (seulement si c'était bien la fin, pas un saut de ta part).
     */
    fun isMissedHandoff(hadPrevious: Boolean, prevRemaining: Long, firedForPrev: Boolean, launchedByHush: Boolean, hasPending: Boolean): Boolean =
        hadPrevious && prevRemaining <= 20_000 && !firedForPrev && !launchedByHush && hasPending
}
