package com.theo.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueLogicTest {
    @Test fun moveDown() = assertEquals(listOf("b", "c", "a", "d"), QueueLogic.move(listOf("a", "b", "c", "d"), 0, 2))
    @Test fun moveUp() = assertEquals(listOf("d", "a", "b", "c"), QueueLogic.move(listOf("a", "b", "c", "d"), 3, 0))
    @Test fun moveInvalidKeepsOrder() {
        val l = listOf("a", "b")
        assertEquals(l, QueueLogic.move(l, 0, 5))
        assertEquals(l, QueueLogic.move(l, -1, 0))
        assertEquals(l, QueueLogic.move(l, 1, 1))
    }
    @Test fun remove() {
        assertEquals(listOf("a", "c"), QueueLogic.removeAt(listOf("a", "b", "c"), 1))
        assertEquals(listOf("a"), QueueLogic.removeAt(listOf("a"), 3))
    }

    @Test fun rightSong() {
        assertTrue(QueueLogic.isRightSong("God's Plan", "Drake", "Gods plan", "Drake"))
        assertTrue(QueueLogic.isRightSong("Jefe", "Ninho, Jul", "Jefe", "Ninho"))
        assertTrue(QueueLogic.isRightSong("Jefe", "Ninho", "Jefe", ""))
    }
    @Test fun wrongSong() {
        // même titre, autre artiste : pas le bon (c'était la cause des sons « qui ont rien à voir »)
        assertFalse(QueueLogic.isRightSong("Jefe", "Someone Else", "Jefe", "Ninho"))
        assertFalse(QueueLogic.isRightSong("Jefe (Remix)", "Ninho", "Jefe", "Ninho"))
    }

    @Test fun mainArtist() {
        assertEquals("ninho", QueueLogic.mainArtist("Ninho, Jul"))
        assertEquals("drake", QueueLogic.mainArtist("Drake feat. Future"))
        assertEquals("koba lad", QueueLogic.mainArtist("Koba LaD & Niska"))
    }

    @Test fun spreadKeepsAllSongs() {
        val songs = listOf("a1" to "A", "a2" to "A", "b1" to "B", "a3" to "A", "c1" to "C", "b2" to "B")
        val out = QueueLogic.spreadArtists(songs) { it.second }
        assertEquals(songs.toSet(), out.toSet())
        assertEquals(songs.size, out.size)
    }

    @Test fun spreadAvoidsSameArtistInARow() {
        val songs = listOf("a1" to "A", "a2" to "A", "a3" to "A", "b1" to "B", "b2" to "B", "c1" to "C")
        val out = QueueLogic.spreadArtists(songs) { it.second }
        // 3 A pour 3 autres : on peut toujours les séparer
        for (i in 1 until out.size) assertTrue("$out", out[i].second != out[i - 1].second)
    }

    @Test fun spreadSingleArtistStillWorks() {
        val songs = listOf("a1" to "A", "a2" to "A")
        assertEquals(2, QueueLogic.spreadArtists(songs) { it.second }.size)
    }

    @Test fun fairShuffleKeepsEverything() {
        val l = (1..50).toList()
        val out = QueueLogic.fairShuffle(l, java.util.Random(7))
        assertEquals(l.toSet(), out.toSet())
        assertEquals(50, out.size)
        assertTrue(out != l)
    }

    @Test fun fairShuffleIsEven() {
        // chaque son a autant de chances d'être le premier
        val counts = IntArray(5)
        val r = java.util.Random(1)
        repeat(50_000) { counts[QueueLogic.fairShuffle(listOf(0, 1, 2, 3, 4), r)[0]]++ }
        counts.forEach { assertTrue("${counts.toList()}", it in 9_000..11_000) }
    }

    @Test fun freshFirst() {
        val day = 24 * 3600_000L
        val now = 100 * day
        val played = mapOf("today" to now - 1000, "old" to now - 30 * day, "never" to 0L, "twoDays" to now - 2 * day)
        val out = QueueLogic.freshOrder(played.keys.toList(), { played[it]!! }, now, java.util.Random(3))
        assertEquals("today", out.last())
        assertTrue(out.indexOf("twoDays") > out.indexOf("old") && out.indexOf("twoDays") > out.indexOf("never"))
    }

    @Test fun spreadWindowOfThree() {
        val songs = listOf("a1" to "A", "a2" to "A", "b1" to "B", "b2" to "B", "c1" to "C", "c2" to "C", "d1" to "D", "d2" to "D")
        val out = QueueLogic.spreadArtists(songs, window = 3) { it.second }
        for (i in 1 until out.size) for (k in 1..3) if (i - k >= 0) assertTrue("$out", out[i].second != out[i - k].second)
    }

    @Test fun ghostSkips() {
        // 3e « suivant » en rafale, écran éteint : bloqué
        assertTrue(QueueLogic.isGhostSkip(listOf(0L, 10_000L), 21_000L, screenOn = false))
        // écran allumé : c'est toi, jamais bloqué
        assertFalse(QueueLogic.isGhostSkip(listOf(0L, 10_000L), 21_000L, screenOn = true))
        // pas en rafale
        assertFalse(QueueLogic.isGhostSkip(listOf(0L, 10_000L), 60_000L, screenOn = false))
        assertFalse(QueueLogic.isGhostSkip(listOf(10_000L), 15_000L, screenOn = false))
    }

    @Test fun versions() {
        assertTrue(QueueLogic.hasVersion("My House (Challenge Version)"))
        assertTrue(QueueLogic.hasVersion("Cake (Sped Up)"))
        assertFalse(QueueLogic.hasVersion("Pilote (feat. Hamza)"))
        assertFalse(QueueLogic.hasVersion("Jamais"))
    }

    @Test fun preciseQuery() {
        assertEquals("\"My House (Challenge Version)\" Beyoncé", QueueLogic.preciseQuery("My House (Challenge Version)", "Beyoncé", "My House Challenge Version Beyoncé"))
        assertEquals("\"Karma\" JUL", QueueLogic.preciseQuery("Karma", "", "Karma JUL"))
    }
}
