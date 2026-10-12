package io.github.knap43.musicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackNamingTest {
    @Test
    fun tagsWin() {
        assertEquals("Man Next Door" to 4, trackTitleAndNumber("Man Next Door", 4, "1 - Man Next Door"))
    }

    @Test
    fun numberFromFileNameWhenTitleIsTagged() {
        assertEquals("Man Next Door" to 1, trackTitleAndNumber("Man Next Door", null, "1 - Man Next Door"))
    }

    @Test
    fun numberFromTitleTag() {
        assertEquals("1 - Man Next Door" to 1, trackTitleAndNumber("1 - Man Next Door", null, "whatever"))
    }

    @Test
    fun titleAndNumberFromFileName() {
        assertEquals("Man Next Door" to 12, trackTitleAndNumber(null, null, "12. Man Next Door"))
    }

    @Test
    fun noNumberLeavesItUnnumbered() {
        assertEquals("Man Next Door" to null, trackTitleAndNumber(null, null, "Man Next Door"))
    }
}
