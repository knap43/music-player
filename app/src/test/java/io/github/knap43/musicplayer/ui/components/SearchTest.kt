package io.github.knap43.musicplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    @Test
    fun ignoresCaseAndAccents() {
        assertTrue(fieldsMatch(queryTokens("beyonce"), "Halo", "Beyoncé"))
        assertTrue(fieldsMatch(queryTokens("SIGUR ROS"), "Hoppípolla", "Sigur Rós"))
    }

    @Test
    fun everyWordMustMatchSomewhere() {
        val tokens = queryTokens("  daft   discovery ")
        assertEquals(listOf("daft", "discovery"), tokens)
        assertTrue(fieldsMatch(tokens, "One More Time", "Daft Punk", "Discovery"))
        assertFalse(fieldsMatch(tokens, "Around the World", "Daft Punk", "Homework"))
    }

    @Test
    fun nullFieldsAreSkipped() {
        assertTrue(fieldsMatch(queryTokens("intro"), "Intro", null))
        assertFalse(fieldsMatch(queryTokens("null"), "Intro", null))
    }
}
