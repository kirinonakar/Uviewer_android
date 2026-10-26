package com.uviewer_android

import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settingsOpensAndKeepsBackupLaunchersRegisteredAcrossAppLanguages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as UviewerApplication
        val preferences = application.container.userPreferencesRepository
        val previousLanguage = preferences.appLanguage.value
        // Keep the platform permission screen out of this isolated emulator UI test.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            instrumentation.uiAutomation.executeShellCommand(
                "appops set ${application.packageName} MANAGE_EXTERNAL_STORAGE allow"
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            instrumentation.runOnMainSync { preferences.setAppLanguage("en") }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithContentDescription("Settings").performClick()
            for (language in listOf("en", "ko", "ja")) {
                compose.runOnIdle { preferences.setAppLanguage(language) }
                val configuration = Configuration(application.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                }
                val resources = application.createConfigurationContext(configuration).resources
                val restore = resources.getString(R.string.backup_import)
                val export = resources.getString(R.string.backup_export)
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(restore))
                compose.onNodeWithText(restore).assertIsDisplayed()
                compose.onNodeWithText(export).assertIsDisplayed()
            }
            // Recreating the host must restore the settings destination and register launchers again.
            scenario.recreate()
            val configuration = Configuration(application.resources.configuration).apply {
                setLocale(Locale.JAPANESE)
            }
            val restore = application.createConfigurationContext(configuration).getString(R.string.backup_import)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(restore))
            compose.onNodeWithText(restore).assertIsDisplayed()
        } finally {
            scenario?.close()
            instrumentation.runOnMainSync { preferences.setAppLanguage(previousLanguage) }
        }
    }
}
