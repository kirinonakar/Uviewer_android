package com.uviewer_android.ui.viewer

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)


class SharpenTransformation(private val intensity: Int) : coil.transform.Transformation {
    override val cacheKey: String = "sharpen_$intensity"
    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        if (intensity <= 0) return input
        val width = input.width
        val height = input.height

        // Safety check: Skip sharpening for extremely large images to prevent OOM
        // 20MP (approx 4500x4500) is a reasonable threshold for most mobile devices
        if (width * height > 20_000_000) {
            android.util.Log.w("Sharpen", "Image resolution too high (${width}x${height}), skipping sharpening to prevent OOM")
            return input
        }

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val outputPixels = IntArray(width * height)
        val alpha = intensity.toFloat() / 20f
        val center = 1f + 4f * alpha
        val neighbor = -alpha
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val p = pixels[idx]
                val pl = pixels[idx - 1]
                val pr = pixels[idx + 1]
                val pt = pixels[idx - width]
                val pb = pixels[idx + width]
                
                var r = (((p shr 16) and 0xFF) * center + (((pl shr 16) and 0xFF) + ((pr shr 16) and 0xFF) + ((pt shr 16) and 0xFF) + ((pb shr 16) and 0xFF)) * neighbor).toInt()
                var g = (((p shr 8) and 0xFF) * center + (((pl shr 8) and 0xFF) + ((pr shr 8) and 0xFF) + ((pt shr 8) and 0xFF) + ((pb shr 8) and 0xFF)) * neighbor).toInt()
                var b = ((p and 0xFF) * center + ((pl and 0xFF) + (pr and 0xFF) + (pt and 0xFF) + (pb and 0xFF)) * neighbor).toInt()
                
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                outputPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val output = android.graphics.Bitmap.createBitmap(width, height, input.config ?: android.graphics.Bitmap.Config.ARGB_8888)
        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return output
    }
}
