package com.uviewer_android

import com.uviewer_android.ui.viewer.DocumentPageNavigation
import org.junit.Assert.*
import org.junit.Test

class DocumentPageNavigationTest {
    @Test fun backgroundPreparationQueuesInputAndRejectsOldPageCallbacks() {
        val navigation = DocumentPageNavigation()
        val executed = mutableListOf<Boolean>()
        navigation.beginLoad("old")
        navigation.beginInitialization("old")
        navigation.finishInitialization("old") { executed.add(it) }
        navigation.beginPreparation()
        navigation.request(true) { executed.add(it) }
        assertFalse(navigation.isReady)
        assertFalse(navigation.isCurrent("old"))
        assertFalse(navigation.beginInitialization("old"))
        assertTrue(executed.isEmpty())
        navigation.beginLoad("new")
        navigation.beginInitialization("new")
        navigation.finishInitialization("new") { executed.add(it) }
        assertEquals(listOf(true), executed)
    }

    @Test fun tapsBeforePageInitializationAreExecutedOnceAndInOrder() {
        val navigation = DocumentPageNavigation()
        val executed = mutableListOf<Boolean>()
        navigation.request(true) { executed.add(it) }
        navigation.beginLoad("page1")
        navigation.request(false) { executed.add(it) }
        assertTrue(executed.isEmpty())
        assertTrue(navigation.beginInitialization("page1"))
        assertFalse(navigation.beginInitialization("page1"))
        assertTrue(navigation.finishInitialization("page1") { executed.add(it) })
        assertEquals(listOf(true, false), executed)
        assertFalse(navigation.beginInitialization("page1"))
        assertFalse(navigation.finishInitialization("page1") { executed.add(it) })
        navigation.request(true) { executed.add(it) }
        assertEquals(listOf(true, false, true), executed)
    }

    @Test fun callbacksFromReplacedPagesCannotRestoreOrConsumePendingInput() {
        val navigation = DocumentPageNavigation()
        val executed = mutableListOf<Boolean>()
        navigation.beginLoad("old")
        assertTrue(navigation.beginInitialization("old"))
        navigation.beginLoad("new")
        navigation.request(true) { executed.add(it) }
        assertFalse(navigation.finishInitialization("old") { executed.add(it) })
        assertFalse(navigation.beginInitialization("old"))
        assertFalse(navigation.isReady)
        assertTrue(executed.isEmpty())
        assertTrue(navigation.beginInitialization("new"))
        navigation.finishInitialization("new") { executed.add(it) }
        assertEquals(listOf(true), executed)
    }
}
