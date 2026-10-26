package com.uviewer_android

import com.uviewer_android.data.utils.NaturalPathComparator
import org.junit.Assert.*
import org.junit.Test

class NaturalPathComparatorTest {
    @Test fun sortsNumbersAndNestedChapterPaths() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "11.jpg"),
            listOf("10.jpg", "2.jpg", "11.jpg", "1.jpg").sortedWith(NaturalPathComparator))
        assertEquals(listOf("chapter2/1.jpg", "chapter2/10.jpg", "chapter10/2.jpg"),
            listOf("chapter10/2.jpg", "chapter2/10.jpg", "chapter2/1.jpg").sortedWith(NaturalPathComparator))
        assertTrue(NaturalPathComparator.compare("Ch2\\page9.JPG", "ch2/page10.jpg") < 0)
    }

    @Test fun longNumbersAndLeadingZerosAreDeterministic() {
        val names = listOf("page99999999999999999999.jpg", "page100000000000000000000.jpg", "page2.jpg", "page02.jpg", "page002.jpg")
        val sorted = names.sortedWith(NaturalPathComparator)
        assertEquals(listOf("page002.jpg", "page02.jpg", "page2.jpg") + names.take(2), sorted)
        for (a in names) for (b in names) assertEquals(
            NaturalPathComparator.compare(a, b).compareTo(0), -NaturalPathComparator.compare(b, a).compareTo(0))
    }
}
