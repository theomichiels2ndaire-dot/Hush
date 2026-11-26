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
}
