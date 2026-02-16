package com.uviewer_android.data.utils

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ArchiveExtractor {
    fun extract(archiveFile: File, targetDir: File) {
        val extension = archiveFile.extension.lowercase()
        when (extension) {
            "zip", "cbz", "epub" -> unzip(archiveFile, targetDir)
            "rar" -> unrar(archiveFile, targetDir)
            "7z" -> un7z(archiveFile, targetDir)
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val file = File(targetDir, entry!!.name)
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    // Skip or throw
                    continue
                }
                if (entry!!.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                }
            }
        }
    }

    private fun unrar(rarFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        try {
            Archive(rarFile).use { archive ->
                var header: FileHeader? = archive.nextFileHeader()
                while (header != null) {
                    val file = File(targetDir, header.fileName)
                    if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        header = archive.nextFileHeader()
                        continue
                    }
                    if (header.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            archive.extractFile(header, fos)
                        }
                    }
                    header = archive.nextFileHeader()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw IOException("RAR Extraction failed: ${e.message}")
        }
    }

    private fun un7z(sevenZFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        try {
            SevenZFile(sevenZFile).use { s7z ->
                var entry = s7z.nextEntry
                while (entry != null) {
                    val file = File(targetDir, entry.name)
                    if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        entry = s7z.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            val buffer = ByteArray(8192)
                            var n: Int
                            while (s7z.read(buffer).also { n = it } != -1) {
                                fos.write(buffer, 0, n)
                            }
                        }
                    }
                    entry = s7z.nextEntry
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw IOException("7Z Extraction failed: ${e.message}")
        }
    }
}
