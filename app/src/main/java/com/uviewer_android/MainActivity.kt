package com.uviewer_android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import com.uviewer_android.data.repository.UserPreferencesRepository
import com.uviewer_android.ui.MainScreen
import com.uviewer_android.ui.theme.UviewerTheme
import android.os.Build
import android.os.Environment
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.Manifest
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val _keyEvents = MutableSharedFlow<Int>()
    val keyEvents = _keyEvents.asSharedFlow()

    var volumeKeyPagingActive = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val appContainer = (application as UviewerApplication).container
            val pagingEnabled = appContainer.userPreferencesRepository.volumeKeyPaging.value
            
            if (pagingEnabled && volumeKeyPagingActive) {
                lifecycleScope.launch {
                    _keyEvents.emit(keyCode)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var shouldResumeState by androidx.compose.runtime.mutableStateOf(false)
    private var resumePathState by androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("action") == "resume") {
            shouldResumeState = true
            resumePathState = intent.getStringExtra("playing_path")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        
        var intentPath: String? = null
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            intentPath = handleIntent(intent)
        }
        if (savedInstanceState == null && intent?.getStringExtra("action") == "resume") {
            shouldResumeState = true
            resumePathState = intent.getStringExtra("playing_path")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                 try {
                     val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                     intent.addCategory("android.intent.category.DEFAULT")
                     intent.data = Uri.parse("package:$packageName")
                     startActivity(intent)
                 } catch (e: Exception) {
                     val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                     startActivity(intent)
                 }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }
        setContent {
            val appContainer = (application as UviewerApplication).container
            val themeMode by appContainer.userPreferencesRepository.themeMode.collectAsState()
            val language by appContainer.userPreferencesRepository.language.collectAsState()

            LaunchedEffect(language) {
                val localeList = if (language == UserPreferencesRepository.LANG_SYSTEM) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(language)
                }
                if (AppCompatDelegate.getApplicationLocales() != localeList) {
                    AppCompatDelegate.setApplicationLocales(localeList)
                }
            }

            val darkTheme = when(themeMode) {
                UserPreferencesRepository.THEME_LIGHT -> false
                UserPreferencesRepository.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }

            LaunchedEffect(darkTheme) {
                val window = (this@MainActivity as Activity).window
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }

            UviewerTheme(darkTheme = darkTheme) {
                MainScreen(
                    activity = this@MainActivity, 
                    initialIntentPath = intentPath, 
                    shouldResume = shouldResumeState,
                    resumeSpecificPath = resumePathState,
                    onHandledResume = { 
                        shouldResumeState = false 
                        resumePathState = null
                    }
                )
            }
        }
    }

    private fun handleIntent(intent: Intent): String? {
        val uri = intent.data ?: return null
        return if (uri.scheme == "file") {
            uri.path
        } else if (uri.scheme == "content") {
            try {
                val cursor = contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val path = it.getString(0)
                        if (path != null) return path
                    }
                }
                
                // Fallback: Copy to temp file if path not found (common for some providers)
                val fileName = getFileName(uri) ?: "temp_file"
                val tempFile = java.io.File(cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = cursor.getString(index)
            }
        }
        return name
    }
}
