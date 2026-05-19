package com.uviewer_android.ui.viewer

import android.app.Application
import com.uviewer_android.data.parser.EpubParser
import com.uviewer_android.data.repository.WebDavRepository
import com.uviewer_android.data.utils.CacheManager
import java.io.File

internal object DocumentFileResolver {
    suspend fun resolveReadableFile(
        application: Application,
        filePath: String,
        isWebDav: Boolean,
        serverId: Int?,
        cacheManager: CacheManager,
        webDavRepository: WebDavRepository
    ): File {
        if (!isWebDav || serverId == null) return File(filePath)

        val cacheDir = application.getExternalFilesDir("cache") ?: application.cacheDir
        val tempFile = File(cacheDir, "temp_" + File(filePath).name)
        if (tempFile.exists()) {
            cacheManager.touch(tempFile)
            return tempFile
        }

        val fileSize = webDavRepository.getFileSize(serverId, filePath)
        cacheManager.ensureCapacity(fileSize)
        webDavRepository.downloadFile(serverId, filePath, tempFile)
        return tempFile
    }

    suspend fun prepareEpubUnzipDir(
        application: Application,
        epubFile: File,
        cacheManager: CacheManager
    ): File {
        val cacheDir = application.getExternalFilesDir("cache") ?: application.cacheDir
        val unzipDir = File(cacheDir, "epub_${epubFile.name}_unzipped")
        val successFile = File(unzipDir, ".unzip_success")

        if (unzipDir.exists() && successFile.exists()) {
            cacheManager.touch(unzipDir)
            return unzipDir
        }

        cacheManager.ensureCapacity(epubFile.length() * 3)
        if (unzipDir.exists()) unzipDir.deleteRecursively()
        unzipDir.mkdirs()
        EpubParser.unzip(epubFile, unzipDir)
        return unzipDir
    }

    fun baseUrlFor(file: File): String? {
        val separator = File.separator
        return file.parentFile?.let { parent ->
            "file:///${parent.absolutePath.replace(separator, "/")}/"
        }
    }
}
