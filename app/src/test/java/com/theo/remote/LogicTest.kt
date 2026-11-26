package com.theo.remote

import com.theo.remote.ui.settingsGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test fun versionNumbers() {
        assertEquals(23, Updater.number("v1.23"))
        assertEquals(23, Updater.number("1.23"))
        assertEquals(0, Updater.number(""))
        assertEquals(0, Updater.number("abc"))
    }

    @Test fun releaseNotes() {
        val body = "Nouveautés\n- Les sons du téléphone restent après une mise à jour\n  - Tests automatiques\n- \nautre"
        assertEquals(listOf("Les sons du téléphone restent après une mise à jour", "Tests automatiques"), WhatsNew.notesLines(body))
    }

    @Test fun settingsGroups() {
        val groups = setOf("Lecture", "Affichage", "Playlists", "Notifications", "Avancé")
        listOf("Lecture", "Paroles et recherche", "Affichage", "Fun et découverte", "Stats", "Tes notes",
            "Playlists", "Fichiers locaux", "Tes goûts", "Widgets et notifications", "Données")
            .forEach { assert(settingsGroup(it) in groups) { "$it n'a pas de groupe" } }
    }
}
