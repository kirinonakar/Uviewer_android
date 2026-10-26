package com.uviewer_android

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uviewer_android.data.repository.UserPreferencesRepository
import com.uviewer_android.ui.viewer.DocumentViewerViewModel
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentInitialSettingsTest {
    @Test fun savedLayoutSettingsArePresentBeforeTheFirstDocumentCanRender() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as UviewerApplication
        val name = "document_settings_test_${UUID.randomUUID()}"
        val isolatedContext = object : ContextWrapper(application) {
            override fun getSharedPreferences(ignored: String?, mode: Int) = application.getSharedPreferences(name, mode)
        }
        val preferences = UserPreferencesRepository(isolatedContext).apply {
            setFontSize(27)
            setFontFamily("sans-serif")
            setSideMargin(16)
            setIsVerticalReading(true)
        }
        try {
            instrumentation.runOnMainSync {
                val container = application.container
                val model = DocumentViewerViewModel(application, container.fileRepository, container.webDavRepository,
                    container.database.recentFileDao(), container.database.bookmarkDao(), container.database.favoriteDao(),
                    preferences, container.cacheManager, container.llmClient)
                try {
                    val firstState = model.uiState.value
                    assertEquals(27, firstState.fontSize)
                    assertEquals("sans-serif", firstState.fontFamily)
                    assertEquals(16, firstState.sideMargin)
                    assertTrue(firstState.isVertical)
                } finally {
                    model.viewModelScope.cancel()
                }
            }
        } finally {
            application.deleteSharedPreferences(name)
        }
    }
}
