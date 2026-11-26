package com.theo.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Les sons du téléphone ne doivent jamais disparaître, même si l'appli est coupée en plein enregistrement. */
class SafeFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun f() = File(tmp.root, "local_music.json")

    @Test fun writeThenRead() {
        val f = f()
        assertTrue(SafeFile.write(f, """{"tracks":[1,2,3]}"""))
        assertEquals("""{"tracks":[1,2,3]}""", SafeFile.read(f))
    }

    @Test fun truncatedFileFallsBackToBackup() {
        val f = f()
        SafeFile.write(f, """{"tracks":[1]}""")
        SafeFile.write(f, """{"tracks":[1,2]}""")
        // coupure en pleine écriture : fichier tronqué
        f.writeText("""{"tracks":[1,2""")
        assertEquals("""{"tracks":[1]}""", SafeFile.read(f))
    }

    @Test fun emptyFileFallsBackToBackup() {
        val f = f()
        SafeFile.write(f, """{"a":1}""")
        SafeFile.write(f, """{"a":2}""")
        f.writeText("")
        assertEquals("""{"a":1}""", SafeFile.read(f))
    }

    @Test fun nothingReadableGivesNull() {
        val f = f()
        f.writeText("{oops")
        assertNull(SafeFile.read(f))
        assertTrue(SafeFile.exists(f))
    }

    @Test fun missingFile() {
        assertNull(SafeFile.read(f()))
        assertFalse(SafeFile.exists(f()))
    }

    @Test fun leftoverTempFileIsIgnored() {
        val f = f()
        SafeFile.write(f, """{"a":1}""")
        File(tmp.root, "local_music.json.tmp").writeText("{brok")
        assertEquals("""{"a":1}""", SafeFile.read(f))
        assertTrue(SafeFile.write(f, """{"a":3}"""))
        assertEquals("""{"a":3}""", SafeFile.read(f))
    }

    @Test fun validity() {
        assertTrue(SafeFile.isValid("[]"))
        assertTrue(SafeFile.isValid(""" {"x":[1]} """))
        assertFalse(SafeFile.isValid(""))
        assertFalse(SafeFile.isValid("""{"x":"""))
    }
}
