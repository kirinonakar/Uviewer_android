package com.uviewer_android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uviewer_android.data.*
import com.uviewer_android.data.backup.*
import com.uviewer_android.data.llm.LlmProvider
import com.uviewer_android.data.llm.LlmThinkingLevel
import com.uviewer_android.data.model.LibraryViewMode
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsBackupTest {
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun sample() = SettingsBackup(
        preferences = BackupPreferences.defaults + mapOf(
            "font_size" to 27, "side_margin" to 16, "is_vertical_reading" to true,
            "theme_mode" to "dark", "language" to "ko", "max_cache_size" to 5 * 1024 * 1024 * 1024L,
            "library_view_layout" to "GRID_4", "library_sort_option" to "DATE_DESC",
            "llm_model_google" to "custom-model", "llm_thinking_google" to "high",
            "llm_prompt_presets" to """[{"id":"custom","name":"번역 / 翻訳","prompt":"translate\nthis text"}]""",
            "llm_selected_prompt_preset" to "custom", "llm_system_prompt" to "translate\nthis text"
        ),
        systemLanguage = "ja",
        favorites = listOf(
            FavoriteItem(11, "책 #1.epub", "/books/책 #1.epub", true, 7, "EPUB", true, 3_000_015, "제4장", 0.75f, 9, 1234567890123L),
            FavoriteItem(12, "로컬 이미지", "/storage/emulated/0/Pictures/a.jpg", false, null, "IMAGE", true, 5, null, 0.5f, 2, 1234567890124L),
            FavoriteItem(13, "일반 즐겨찾기", "/storage/emulated/0/book.txt", type = "TEXT", timestamp = 1234567890125L)
        ),
        bookmarks = listOf(Bookmark(21, "책의 위치", "/books/책 #1.epub", true, 7, "EPUB", 3_000_015, "제4장", 1234567890123L)),
        servers = listOf(WebDavServer(7, "Books NAS", "https://example.com/dav"))
    )

    @Test fun codecPreservesAllPortableDataAndRejectsMalformedFiles() {
        val backup = sample()
        val bytes = SettingsBackupCodec.encode(backup)
        assertEquals(backup, SettingsBackupCodec.decode(bytes.inputStream()))
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("format", "other-app") },
            { it.put("version", 2) },
            { it.remove("bookmarks") },
            { it.getJSONObject("settings").put("font_size", "27") },
            { it.getJSONObject("settings").put("font_size", 999) },
            { it.getJSONObject("settings").put("volume_key_paging", 1) },
            { it.getJSONObject("settings").put("llm_prompt_presets", "not JSON") },
            { it.getJSONArray("favorites").getJSONObject(0).put("serverId", 999) },
            { it.getJSONArray("favorites").getJSONObject(0).put("progress", "0.5") },
            { it.getJSONArray("favorites").getJSONObject(0).put("type", "unsupported-type") },
            { it.getJSONArray("servers").getJSONObject(0).put("url", "https://user:pass@example.com/dav") },
            { it.getJSONArray("servers").put(it.getJSONArray("servers").getJSONObject(0)) }
        )
        mutations.forEach { mutate ->
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            mutate(json)
            assertThrows(Exception::class.java) { SettingsBackupCodec.decode(json.toString().byteInputStream()) }
        }
        assertThrows(Exception::class.java) { SettingsBackupCodec.decode("not a backup".byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupCodec.decode(ByteArray(SettingsBackupCodec.MAX_BYTES + 1).inputStream())
        }
    }

    @Test fun emptyBackupAndMissingOptionalSettingsUseDefaults() {
        val empty = SettingsBackup(BackupPreferences.defaults, "system", emptyList(), emptyList(), emptyList())
        val json = JSONObject(SettingsBackupCodec.encode(empty).toString(Charsets.UTF_8))
        json.put("settings", JSONObject())
        assertEquals(empty, SettingsBackupCodec.decode(json.toString().byteInputStream()))
    }

    private fun isolatedContext(name: String) = object : ContextWrapper(application) {
        override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences =
            application.getSharedPreferences(name, mode)
    }

    @Test fun itemsWhoseServerWasDeletedCanStillBeBackedUp() = runBlocking {
        val name = "backup_deleted_server_${UUID.randomUUID()}"
        val context = isolatedContext(name)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(application.cacheDir, "$name.json")
        try {
            val preferences = UserPreferencesRepository(context)
            val repository = SettingsBackupRepository(context, database, preferences)
            database.favoriteDao().insertFavorite(sample().favorites.first())
            database.bookmarkDao().insertBookmark(sample().bookmarks.first())
            repository.export(Uri.fromFile(file), "system")
            val backup = repository.read(Uri.fromFile(file))
            assertTrue(backup.servers.isEmpty())
            assertNull(backup.favorites.single().serverId)
            assertNull(backup.bookmarks.single().serverId)
            assertEquals("/books/책 #1.epub", backup.favorites.single().path)
            assertEquals(3_000_015, backup.bookmarks.single().position)
            repository.restore(backup)
            assertEquals(1, database.favoriteDao().getAllFavorites().first().size)
        } finally {
            database.close()
            file.delete()
            application.deleteSharedPreferences(name)
        }
    }

    @Test fun fileRoundTripReplacesItemsRemapsServersAndRefreshesLivePreferences() = runBlocking {
        val name = "backup_test_${UUID.randomUUID()}"
        val context = isolatedContext(name)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(application.cacheDir, "$name.json")
        try {
            val preferences = UserPreferencesRepository(context)
            val repository = SettingsBackupRepository(context, database, preferences)
            val original = sample()
            // Occupy the source server ID with a different server; its credentials/session must keep working.
            val unrelated = WebDavServer(7, "Existing server", "https://other.example.com/")
            database.webDavServerDao().insertServer(unrelated)
            database.favoriteDao().insertFavorite(FavoriteItem(title = "Old", path = "/old", type = "TEXT"))
            database.bookmarkDao().insertBookmark(Bookmark(title = "Old", path = "/old", type = "TEXT"))
            database.recentFileDao().insertRecent(RecentFile("/recent", "Keep history", type = "TEXT"))
            context.getSharedPreferences("user_prefs", 0).edit().putString("llm_api_key_google", "do-not-export").commit()

            repository.restore(original)
            assertEquals(27, preferences.fontSize.value)
            assertEquals(16, preferences.sideMargin.value)
            assertTrue(preferences.isVerticalReading.value)
            assertEquals("dark", preferences.themeMode.value)
            assertEquals("ko", preferences.appLanguage.value)
            assertEquals(5 * 1024 * 1024 * 1024L, preferences.maxCacheSize.value)
            assertEquals(LibraryViewMode.GRID_4, preferences.getLibraryViewMode())
            assertEquals("DATE_DESC", preferences.getLibrarySortOption())
            assertEquals("custom-model", preferences.getLlmModelName(LlmProvider.GOOGLE))
            assertEquals(LlmThinkingLevel.HIGH, preferences.getLlmThinkingLevel(LlmProvider.GOOGLE))
            assertEquals("custom", preferences.selectedLlmPromptPresetId.value)
            assertEquals("translate\nthis text", preferences.llmSystemPrompt.value)
            assertTrue(preferences.llmPromptPresets.value.any { it.id == "custom" })
            assertEquals(1, preferences.restoreGeneration.value)
            val servers = database.webDavServerDao().getAllServers().first()
            assertTrue(servers.contains(unrelated))
            val importedServer = servers.single { it.name == "Books NAS" }
            assertNotEquals(7, importedServer.id)
            val favorites = database.favoriteDao().getAllFavorites().first()
            assertEquals(3, favorites.size)
            original.favorites.forEach { expected ->
                assertEquals(expected.copy(id = 0, serverId = expected.serverId?.let { importedServer.id }),
                    favorites.single { it.path == expected.path }.copy(id = 0))
            }
            assertEquals(listOf(2, 9), favorites.filter { it.isPinned }.sortedBy { it.pinOrder }.map { it.pinOrder })
            assertEquals(original.bookmarks.single().copy(id = 0, serverId = importedServer.id),
                database.bookmarkDao().getAllBookmarks().first().single().copy(id = 0))
            assertNotNull(database.recentFileDao().getFile("/recent"))

            repository.export(Uri.fromFile(file), original.systemLanguage)
            assertFalse(file.readText().contains("do-not-export"))
            val exported = repository.read(Uri.fromFile(file))
            preferences.setFontSize(18)
            repository.restore(exported)
            repository.restore(exported) // Reimport does not duplicate servers or bookmarks.
            assertEquals(27, preferences.fontSize.value)
            assertEquals(2, database.webDavServerDao().getAllServers().first().size)
            assertEquals(1, database.bookmarkDao().getAllBookmarks().first().size)
            assertEquals("do-not-export", context.getSharedPreferences("user_prefs", 0).getString("llm_api_key_google", null))
            assertEquals("ja", exported.systemLanguage)
        } finally {
            database.close()
            file.delete()
            application.deleteSharedPreferences(name)
        }
    }

    @Test fun databaseFailureRollsBackReplacedItemsAndAddedServers() = runBlocking {
        val name = "backup_rollback_${UUID.randomUUID()}"
        val context = isolatedContext(name)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val preferences = UserPreferencesRepository(context)
            preferences.setFontSize(20)
            val oldFavorite = FavoriteItem(1, "Old", "/old", type = "TEXT")
            val oldBookmark = Bookmark(1, "Old", "/old", type = "TEXT")
            database.favoriteDao().insertFavorite(oldFavorite)
            database.bookmarkDao().insertBookmark(oldBookmark)
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_restore BEFORE INSERT ON bookmarks BEGIN SELECT RAISE(ABORT, 'test failure'); END"
                )
            }
            try {
                SettingsBackupRepository(context, database, preferences).restore(sample())
                fail("Restore must fail")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(listOf(oldFavorite), database.favoriteDao().getAllFavorites().first())
            assertEquals(listOf(oldBookmark), database.bookmarkDao().getAllBookmarks().first())
            assertTrue(database.webDavServerDao().getAllServers().first().isEmpty())
            assertEquals(20, preferences.fontSize.value)
            assertEquals(0, preferences.restoreGeneration.value)
        } finally {
            database.close()
            application.deleteSharedPreferences(name)
        }
    }

    @Test fun failedPreferencesCommitRollsBackDatabaseAndPreferences() = runBlocking {
        val name = "backup_commit_failure_${UUID.randomUUID()}"
        var failCommit = true
        val stored = application.getSharedPreferences(name, 0)
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences =
                object : SharedPreferences by stored {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = stored.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun commit(): Boolean {
                                val success = delegate.commit()
                                return if (failCommit) { failCommit = false; false } else success
                            }
                        }
                    }
                }
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val preferences = UserPreferencesRepository(context)
            preferences.setFontSize(20)
            val old = FavoriteItem(1, "Old", "/old", type = "TEXT")
            database.favoriteDao().insertFavorite(old)
            try {
                SettingsBackupRepository(context, database, preferences).restore(sample())
                fail("Restore must fail")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf(old), database.favoriteDao().getAllFavorites().first())
            assertTrue(database.webDavServerDao().getAllServers().first().isEmpty())
            assertEquals(20, UserPreferencesRepository(context).fontSize.value)
            assertEquals(20, preferences.fontSize.value)
        } finally {
            database.close()
            application.deleteSharedPreferences(name)
        }
    }
}
