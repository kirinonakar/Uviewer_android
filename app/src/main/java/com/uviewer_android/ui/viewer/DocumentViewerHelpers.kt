package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.uviewer_android.data.repository.UserPreferencesRepository
import com.uviewer_android.data.repository.WebDavRepository
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

internal fun DocumentViewerUiState.documentColors(): Pair<String, String> {
    return when (docBackgroundColor) {
        UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb" to "#322D29"
        UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#cccccc"
        UserPreferencesRepository.DOC_BG_COMFORT -> "#E9E2E4" to "#343426"
        UserPreferencesRepository.DOC_BG_CUSTOM -> customDocBackgroundColor to customDocTextColor
        else -> "#ffffff" to "#000000"
    }
}

internal fun resolveDocumentLanguageTag(application: Application, lang: String): String {
    val systemLocales = AppCompatDelegate.getApplicationLocales()
    val systemLang = if (!systemLocales.isEmpty) {
        systemLocales.get(0)?.language ?: ""
    } else {
        application.resources.configuration.locales[0].language
    }

    if (systemLang == UserPreferencesRepository.LANG_JA || lang == UserPreferencesRepository.LANG_JA) {
        return UserPreferencesRepository.LANG_JA
    }

    return if (lang == UserPreferencesRepository.LANG_SYSTEM) {
        systemLang
    } else {
        lang
    }
}

internal suspend fun resolveDocumentLocalImages(
    content: String,
    parentDir: File?,
    webDavRepository: WebDavRepository,
    application: Application,
    serverId: Int?,
    parentPath: String? = null
): String {
    var result = content
    val imgRegex = Regex("<img\\s+[^>]*src=\"([^\"]+)\"[^>]*>")
    val matches = imgRegex.findAll(content).toList()

    fun encodeFileName(path: String): String {
        return try {
            path.split("/").joinToString("/") { segment ->
                if (segment.endsWith(":") && segment.length <= 6) segment
                else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        } catch (e: Exception) {
            path
        }
    }

    for (match in matches) {
        val originalSrc = match.groups[1]?.value ?: continue
        if (originalSrc.startsWith("http") || originalSrc.startsWith("data:")) continue

        if (serverId != null && parentPath != null) {
            val cacheBase = File(application.getExternalFilesDir("cache") ?: application.cacheDir, "webdav_img_cache/$serverId")
            if (!cacheBase.exists()) cacheBase.mkdirs()

            val decodedSrc = try {
                URLDecoder.decode(originalSrc, "UTF-8")
            } catch (e: Exception) {
                originalSrc
            }
            val cleanSrc = decodedSrc.removePrefix("file://").removePrefix("/")
            val webDavPath = if (parentPath.isEmpty()) cleanSrc else "$parentPath/$cleanSrc"
            val fileName = URLEncoder.encode(webDavPath, "UTF-8").takeLast(100)
            val cachedFile = File(cacheBase, fileName)

            if (cachedFile.exists()) {
                val encoded = encodeFileName("file://${cachedFile.absolutePath}")
                result = result.replace(match.value, match.value.replace(originalSrc, encoded))
            } else {
                try {
                    webDavRepository.downloadFile(serverId, webDavPath, cachedFile)
                    val encoded = encodeFileName("file://${cachedFile.absolutePath}")
                    result = result.replace(match.value, match.value.replace(originalSrc, encoded))
                } catch (e: Exception) {
                    // Missing inline images should not block document rendering.
                }
            }
        } else if (parentDir != null) {
            val decodedSrc = try {
                URLDecoder.decode(originalSrc, "UTF-8")
            } catch (e: Exception) {
                originalSrc
            }
            val cleanSrc = decodedSrc.removePrefix("file://")
            var imgFile = File(parentDir, cleanSrc)

            if (!imgFile.exists() && cleanSrc.startsWith("/")) {
                val absFile = File(cleanSrc)
                if (absFile.exists()) imgFile = absFile
            }

            if (!imgFile.exists()) {
                val searchName = cleanSrc.substringAfterLast("/")
                val decodedSearchName = try {
                    URLDecoder.decode(searchName, "UTF-8")
                } catch (e: Exception) {
                    searchName
                }

                val found = parentDir.walkTopDown()
                    .maxDepth(3)
                    .find { it.name == searchName || it.name == decodedSearchName }
                if (found != null) imgFile = found
            }

            if (imgFile.exists()) {
                val separator = File.separator
                val encoded = encodeFileName("file:///${imgFile.absolutePath.replace(separator, "/")}")
                result = result.replace(match.value, match.value.replace(originalSrc, encoded))
            }
        }
    }
    return result
}
