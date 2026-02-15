package com.uviewer_android.data.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.zip.ZipFile

object ThumbnailUtils {
    private val semaphore = Semaphore(2)

    suspend fun getFirstImageFromZip(file: File, maxWidth: Int = 200): Bitmap? {
        return semaphore.withPermit {
            try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { !it.isDirectory && isImageFile(it.name) }
                        .sortedBy { it.name }
                        .toList()

                    if (entries.isNotEmpty()) {
                        val entry = entries[0]
                        zip.getInputStream(entry).use { inputStream ->
                            val bytes = inputStream.readBytes()
                            
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            
                            options.inSampleSize = calculateInSampleSize(options, maxWidth, maxWidth)
                            options.inJustDecodeBounds = false
                            
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            return bitmap?.let {
                                if (it.width > maxWidth || it.height > maxWidth) {
                                    val scale = Math.min(maxWidth.toFloat() / it.width, maxWidth.toFloat() / it.height)
                                    val scaled = Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                                    if (scaled != it) it.recycle()
                                    scaled
                                } else it
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lowerName = name.lowercase()
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || 
               lowerName.endsWith(".png") || lowerName.endsWith(".webp") || 
               lowerName.endsWith(".gif") || lowerName.endsWith(".bmp")
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
