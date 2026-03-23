package com.uviewer_android.data.utils

import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ArchiveExtractor {

    fun extract(archiveFile: File, targetDir: File) {
        val extension = archiveFile.extension.lowercase()
        when (extension) {
            "zip", "cbz", "epub" -> unzip(archiveFile, targetDir)
            "rar", "cbr", "7z", "cb7" -> extractWithSevenZip(archiveFile, targetDir)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RAR4 / RAR5 / 7Z — 포맷 자동 감지, 네이티브 라이브러리 자동 로드
    // ──────────────────────────────────────────────────────────────
    private fun extractWithSevenZip(archiveFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()

        try {
            RandomAccessFile(archiveFile, "r").use { raf ->
                SevenZip.openInArchive(null, RandomAccessFileInStream(raf)).use { inArchive ->
                    inArchive.extract(null, false, object : IArchiveExtractCallback {

                        private var currentOut: FileOutputStream? = null
                        private var currentTemp: File? = null
                        private var currentDest: File? = null

                        override fun getStream(
                            index: Int,
                            extractAskMode: ExtractAskMode
                        ): ISequentialOutStream? {
                            currentOut?.runCatching { close() }
                            currentOut = null

                            if (extractAskMode != ExtractAskMode.EXTRACT) return null

                            val isDir = inArchive.getProperty(index, PropID.IS_FOLDER)
                                as? Boolean ?: return null
                            if (isDir) return null

                            val path = inArchive.getProperty(index, PropID.PATH)
                                as? String ?: return null

                            val normalized = path.replace('\\', '/').trimStart('/')
                            val dest = File(targetDir, normalized)

                            if (!dest.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                                return null
                            }

                            dest.parentFile?.mkdirs()
                            val temp = File(dest.parentFile, "${dest.name}.tmp")
                            currentTemp = temp
                            currentDest = dest

                            val fos = FileOutputStream(temp)
                            currentOut = fos

                            return ISequentialOutStream { data ->
                                fos.write(data)
                                data.size
                            }
                        }

                        override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                        override fun setOperationResult(result: ExtractOperationResult) {
                            currentOut?.runCatching { close() }
                            currentOut = null

                            val temp = currentTemp
                            val dest = currentDest

                            if (result == ExtractOperationResult.OK && temp != null && dest != null) {
                                temp.renameTo(dest)
                            } else {
                                temp?.delete()
                            }

                            currentTemp = null
                            currentDest = null
                        }

                        override fun setTotal(total: Long) {}
                        override fun setCompleted(complete: Long) {}
                    })
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            throw IOException("${archiveFile.extension.uppercase()} 압축 해제 실패: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ZIP / CBZ / EPUB
    // ──────────────────────────────────────────────────────────────
    private fun unzip(zipFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val normalized = entry!!.name.replace('\\', '/').trimStart('/')
                val file = File(targetDir, normalized)

                if (!file.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                    continue
                }

                if (entry!!.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    val tempFile = File(file.parentFile, "${file.name}.tmp")
                    try {
                        FileOutputStream(tempFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        tempFile.renameTo(file)
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
            }
        }
    }
}
