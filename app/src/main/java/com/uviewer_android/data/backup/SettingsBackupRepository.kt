package com.uviewer_android.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.uviewer_android.data.AppDatabase
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SettingsBackupRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val preferences: UserPreferencesRepository
) {
    suspend fun export(uri: Uri, systemLanguage: String) = withContext(Dispatchers.IO) {
        val backup = database.withTransaction {
            val servers = database.webDavServerDao().getAllServers().first()
            val serverIds = servers.map { it.id }.toSet()
            SettingsBackup(
                preferences.backupPreferences(), systemLanguage,
                // Deleting a server can leave saved items behind. Keep those items exportable.
                database.favoriteDao().getAllFavorites().first().map { it.copy(serverId = it.serverId?.takeIf(serverIds::contains)) },
                database.bookmarkDao().getAllBookmarks().first().map { it.copy(serverId = it.serverId?.takeIf(serverIds::contains)) },
                servers
            )
        }
        val bytes = SettingsBackupCodec.encode(backup)
        // Check that anything we export is also importable before opening/truncating the target.
        SettingsBackupCodec.decode(bytes.inputStream())
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open backup" }.use {
            it.write(bytes)
            it.flush()
        }
    }

    suspend fun read(uri: Uri): SettingsBackup = withContext(Dispatchers.IO) {
        checkNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open backup" }.use {
            SettingsBackupCodec.decode(it)
        }
    }

    suspend fun restore(backup: SettingsBackup) {
        // Once confirmed, finish the short commit even if a locale change recreates the activity.
        withContext(Dispatchers.IO + NonCancellable) {
            val previous = preferences.backupPreferences()
            try {
                database.withTransaction {
                    val existing = database.webDavServerDao().getAllServers().first().toMutableList()
                    val serverIds = backup.servers.associate { server ->
                        val match = existing.firstOrNull { it.name == server.name && it.url == server.url }
                        val id = match?.id ?: database.webDavServerDao().insertServer(server.copy(id = 0)).toInt().also {
                            existing += server.copy(id = it)
                        }
                        server.id to id
                    }
                    database.favoriteDao().deleteAll()
                    database.bookmarkDao().deleteAll()
                    backup.favorites.forEach {
                        database.favoriteDao().insertFavorite(it.copy(id = 0, serverId = it.serverId?.let(serverIds::getValue)))
                    }
                    backup.bookmarks.forEach {
                        database.bookmarkDao().insertBookmark(it.copy(id = 0, serverId = it.serverId?.let(serverIds::getValue)))
                    }
                    preferences.writeBackupPreferences(backup.preferences)
                }
            } catch (error: Exception) {
                try {
                    preferences.writeBackupPreferences(previous)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            }
            withContext(Dispatchers.Main) { preferences.refreshAfterRestore() }
        }
    }
}
