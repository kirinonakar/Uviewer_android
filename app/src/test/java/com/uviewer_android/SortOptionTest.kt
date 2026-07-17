package com.uviewer_android

import com.uviewer_android.data.model.SortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class SortOptionTest {
    @Test
    fun `sort toggle cycles through name newest and oldest`() {
        val name = SortOption.NAME
        val newest = name.nextToggleOption()
        val oldest = newest.nextToggleOption()

        assertEquals(SortOption.DATE_DESC, newest)
        assertEquals(SortOption.DATE_ASC, oldest)
        assertEquals(SortOption.NAME, oldest.nextToggleOption())
    }
}
