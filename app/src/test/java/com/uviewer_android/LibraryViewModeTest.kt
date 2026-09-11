package com.uviewer_android

import com.uviewer_android.data.model.LibraryViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewModeTest {
    @Test
    fun cyclesThroughTwoColumnsFourColumnsAndList() {
        val modes = generateSequence(LibraryViewMode.GRID_2) { it.next() }.take(4).toList()
        assertEquals(
            listOf(LibraryViewMode.GRID_2, LibraryViewMode.GRID_4, LibraryViewMode.LIST, LibraryViewMode.GRID_2),
            modes
        )
    }

    @Test
    fun preservesLegacyPreferenceWhenNoValidLayoutIsStored() {
        for (storedValue in listOf(null, "unknown")) {
            assertEquals(LibraryViewMode.GRID_2, LibraryViewMode.fromStoredValue(storedValue, true))
            assertEquals(LibraryViewMode.LIST, LibraryViewMode.fromStoredValue(storedValue, false))
        }
    }

    @Test
    fun savedLayoutTakesPrecedenceOverLegacyPreference() {
        for (mode in LibraryViewMode.entries) {
            assertEquals(mode, LibraryViewMode.fromStoredValue(mode.name, true))
            assertEquals(mode, LibraryViewMode.fromStoredValue(mode.name, false))
        }
    }
}
