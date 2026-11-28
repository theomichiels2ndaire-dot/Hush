package com.theo.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les garanties anti-coupure : chaque test correspond à un bug déjà vu. */
class PlaybackRulesTest {
    private val dur = 200_000L

    @Test fun endOfSongWhilePlaying() =
        assertTrue(PlaybackRules.isEnding(isPlaying = true, durationMs = dur, reportedPos = 195_000, livePos = 199_500, nearEndRecently = false))

    @Test fun hushClockAheadDoesNotCut() =
        // l'horloge de Hush croit la fin arrivée, mais Tidal a donné une position loin de la fin
        assertFalse(PlaybackRules.isEnding(isPlaying = true, durationMs = dur, reportedPos = 60_000, livePos = 199_500, nearEndRecently = false))

    @Test fun pauseBeforeTheEndDoesNotSkip() =
        // pause 15 s avant la fin, même si l'horloge de Hush avait cru à la fin
        assertFalse(PlaybackRules.isEnding(isPlaying = false, durationMs = dur, reportedPos = 185_000, livePos = 199_000, nearEndRecently = true))

    @Test fun tidalStoppedAtTheEnd() =
        assertTrue(PlaybackRules.isEnding(isPlaying = false, durationMs = dur, reportedPos = 199_000, livePos = 199_000, nearEndRecently = false))

    @Test fun positionOfPreviousSongIsIgnored() =
        // position donnée avant le changement de son (celle de l'ancien son) : pas fiable
        assertFalse(PlaybackRules.trustPosition(positionUpdatedAt = 1_000, positionMs = 190_000, keyChangedAt = 10_000, durationMs = dur, now = 20_000))

    @Test fun justStartedIsNotTrusted() =
        assertFalse(PlaybackRules.trustPosition(positionUpdatedAt = 10_100, positionMs = 0, keyChangedAt = 10_000, durationMs = dur, now = 12_000))

    @Test fun normalPositionIsTrusted() =
        assertTrue(PlaybackRules.trustPosition(positionUpdatedAt = 10_100, positionMs = 0, keyChangedAt = 10_000, durationMs = dur, now = 30_000))

    @Test fun staleExtrapolationIsNotTrusted() =
        assertFalse(PlaybackRules.trustPosition(positionUpdatedAt = 10_100, positionMs = 0, keyChangedAt = 10_000, durationMs = dur, now = 10_100 + dur + 6000))

    @Test fun userSkipIsNotCaughtUp() =
        // tu as passé le son avec plus de 20 s restantes : Hush ne relance rien par-dessus
        assertFalse(PlaybackRules.isMissedHandoff(true, prevRemaining = 90_000, firedForPrev = false, launchedByHush = false, hasPending = true))

    @Test fun realEndIsCaughtUp() =
        assertTrue(PlaybackRules.isMissedHandoff(true, prevRemaining = 3_000, firedForPrev = false, launchedByHush = false, hasPending = true))

    @Test fun ownLaunchIsNotCaughtUp() =
        assertFalse(PlaybackRules.isMissedHandoff(true, prevRemaining = 3_000, firedForPrev = false, launchedByHush = true, hasPending = true))
}
