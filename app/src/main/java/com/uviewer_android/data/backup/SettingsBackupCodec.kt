package com.uviewer_android.data.backup

import com.uviewer_android.data.Bookmark
import com.uviewer_android.data.FavoriteItem
import com.uviewer_android.data.WebDavServer
import com.uviewer_android.data.model.FileEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.ByteArrayOutputStream

data class SettingsBackup(
    val preferences: Map<String, Any>,
    val systemLanguage: String,
    val favorites: List<FavoriteItem>,
    val bookmarks: List<Bookmark>,
    val servers: List<WebDavServer>
)

object SettingsBackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun encode(backup: SettingsBackup): ByteArray {
        val json = JSONObject()
            .put("format", "uviewer-settings-backup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", JSONObject(backup.preferences))
            .put("systemLanguage", backup.systemLanguage)
            .put("servers", JSONArray().apply {
                backup.servers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
            })
            .put("favorites", JSONArray().apply {
                backup.favorites.forEach {
                    put(JSONObject().put("id", it.id).put("title", it.title).put("path", it.path)
                        .put("isWebDav", it.isWebDav).put("serverId", it.serverId ?: JSONObject.NULL)
                        .put("type", it.type).put("isPinned", it.isPinned).put("position", it.position)
                        .put("positionTitle", it.positionTitle ?: JSONObject.NULL).put("progress", it.progress)
                        .put("pinOrder", it.pinOrder).put("timestamp", it.timestamp))
                }
            })
            .put("bookmarks", JSONArray().apply {
                backup.bookmarks.forEach {
                    put(JSONObject().put("id", it.id).put("title", it.title).put("path", it.path)
                        .put("isWebDav", it.isWebDav).put("serverId", it.serverId ?: JSONObject.NULL)
                        .put("type", it.type).put("position", it.position)
                        .put("positionTitle", it.positionTitle ?: JSONObject.NULL).put("timestamp", it.timestamp))
                }
            })
        return json.toString(2).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "Backup is too large" }
        }
    }

    fun decode(input: InputStream): SettingsBackup {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_BYTES) { "Backup is too large" }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.string("format") == "uviewer-settings-backup")
        require(json.integer("version") == 1) { "Unsupported backup version" }
        val preferences = BackupPreferences.validate(json.getJSONObject("settings"))
        val language = json.string("systemLanguage")
        require(language in listOf("system", "en", "ko", "ja"))
        val servers = json.objects("servers").map {
            WebDavServer(it.integer("id"), it.string("name"), it.string("url").also { url ->
                val uri = java.net.URI(url)
                require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
            })
        }
        require(servers.all { it.id > 0 } && servers.map { it.id }.distinct().size == servers.size)
        val serverIds = servers.map { it.id }.toSet()
        fun JSONObject.serverId(): Int? = if (isNull("serverId")) null else integer("serverId").also { require(it in serverIds) }
        val favorites = json.objects("favorites").map {
            FavoriteItem(
                id = it.integer("id"), title = it.string("title"), path = it.string("path").also { p -> require(p.isNotBlank()) },
                isWebDav = it.boolean("isWebDav"), serverId = it.serverId(), type = it.itemType(),
                isPinned = it.boolean("isPinned"), position = it.integer("position").also { p -> require(p >= 0) },
                positionTitle = it.nullableString("positionTitle"), progress = (it.get("progress") as Number).toFloat().also { p -> require(p.isFinite() && p in 0f..1f) },
                pinOrder = it.integer("pinOrder"), timestamp = it.long("timestamp")
            )
        }
        val bookmarks = json.objects("bookmarks").map {
            Bookmark(
                id = it.integer("id"), title = it.string("title"), path = it.string("path").also { p -> require(p.isNotBlank()) },
                isWebDav = it.boolean("isWebDav"), serverId = it.serverId(), type = it.itemType(),
                position = it.integer("position").also { p -> require(p >= 0) },
                positionTitle = it.nullableString("positionTitle"), timestamp = it.long("timestamp")
            )
        }
        require(favorites.all { it.id > 0 } && favorites.map { it.id }.distinct().size == favorites.size)
        require(bookmarks.all { it.id > 0 } && bookmarks.map { it.id }.distinct().size == bookmarks.size)
        return SettingsBackup(preferences, language, favorites, bookmarks, servers)
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = getJSONArray(key)
        require(array.length() <= 50_000)
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun JSONObject.string(key: String) = get(key) as String
    private fun JSONObject.itemType() = string("type").also { type ->
        require(type.uppercase() == "DOCUMENT" || FileEntry.FileType.entries.any { it.name == type.uppercase() })
    }
    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else string(key)
    private fun JSONObject.boolean(key: String) = get(key) as Boolean
    private fun JSONObject.long(key: String): Long {
        val value = get(key) as Number
        return value.toLong().also { require(value.toDouble() == it.toDouble()) }
    }
    private fun JSONObject.integer(key: String): Int = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
}
