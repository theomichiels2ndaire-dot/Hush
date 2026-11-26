package com.theo.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    @Test fun normalize() {
        assertEquals("jokair", Fuzzy.norm("Jok'Air"))
        assertEquals("eve", Fuzzy.norm("Ève"))
    }

    @Test fun prefixFromFirstLetters() {
        assertTrue(Fuzzy.score("dr", "God's Plan Drake") > 0)
        assertTrue(Fuzzy.score("god pl", "God's Plan Drake") > 0)
    }

    @Test fun typos() {
        assertTrue(Fuzzy.score("drak", "Drake") > 0)
        assertTrue(Fuzzy.score("darke", "Drake") > 0)
        assertTrue(Fuzzy.score("ninhoo", "Ninho") > 0)
        assertTrue(Fuzzy.score("jokair", "Bonbon à la menthe Jok'Air") > 0)
    }

    @Test fun noFalseMatch() {
        assertEquals(0, Fuzzy.score("drake", "Ninho"))
        assertEquals(0, Fuzzy.score("abc", "Drake"))
    }

    @Test fun exactBeatsTypo() {
        assertTrue(Fuzzy.score("drake", "Drake") > Fuzzy.score("drak", "Drake 2"))
    }

    @Test fun correction() {
        val vocab = listOf("Drake", "Ninho", "Jok'Air", "Koba LaD")
        assertEquals("drake", Fuzzy.correct("drak", vocab))
        assertEquals("ninho", Fuzzy.correct("nihno", vocab))
        assertNull(Fuzzy.correct("drake", vocab))
        assertNull(Fuzzy.correct("zzzz", vocab))
        // « les rois du monde » ne doit pas devenir « les crois du monde »
        assertNull(Fuzzy.correct("les rois du monde", listOf("Je crois", "Drake")))
    }

    @Test fun distance() {
        assertEquals(1, Fuzzy.distance("darke", "drake"))
        assertEquals(1, Fuzzy.distance("drak", "drake"))
        assertEquals(0, Fuzzy.distance("a", "a"))
    }
}
