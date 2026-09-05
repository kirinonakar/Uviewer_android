package com.uviewer_android.data.utils

import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import com.github.penfeizhou.animation.avif.AVIFDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import kotlinx.coroutines.runInterruptible
import okio.BufferedSource
import java.nio.ByteBuffer

/**
 * Coil decoder for AVIF image sequences.
 *
 * Android's built-in animated image decoder does not expose animated AVIF as an
 * AnimatedImageDrawable on all supported API levels, so use libavif through
 * APNG4Android for files that advertise the AVIF image-sequence brand.
 */
class AnimatedAvifDecoder(
    private val source: ImageSource,
) : Decoder {

    override suspend fun decode(): DecodeResult = runInterruptible {
        val encoded = source.source().readByteArray()
        check(encoded.isNotEmpty()) { "Animated AVIF source is empty." }

        val encodedBuffer = ByteBuffer.allocateDirect(encoded.size).apply {
            put(encoded)
            flip()
        }

        DecodeResult(
            drawable = AVIFDrawable(AvifByteBufferLoader(encodedBuffer)),
            isSampled = false,
        )
    }

    private class AvifByteBufferLoader(
        private val encoded: ByteBuffer,
    ) : ByteBufferLoader() {
        override fun getByteBuffer(): ByteBuffer = encoded.duplicate().apply {
            position(0)
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            return if (result.source.source().isAnimatedAvif()) {
                AnimatedAvifDecoder(result.source)
            } else {
                null
            }
        }

        override fun equals(other: Any?): Boolean = other is Factory

        override fun hashCode(): Int = javaClass.hashCode()
    }
}

/**
 * Detect the AVIF image-sequence brand in the ISO Base Media File Format ftyp box.
 * The source is only peeked, so other Coil decoders can still consume it.
 */
internal fun BufferedSource.isAnimatedAvif(): Boolean {
    val peek = peek()
    return try {
        if (!peek.request(16)) return false

        val declaredSize = peek.readInt().toLong() and 0xFFFF_FFFFL
        if (peek.readUtf8(4) != "ftyp") return false

        val headerSize = if (declaredSize == 1L) {
            if (!peek.request(8)) return false
            peek.readLong()
            16L
        } else {
            8L
        }

        if (declaredSize < headerSize + 8L) return false
        if (!peek.request(8)) return false

        val majorBrand = peek.readUtf8(4)
        peek.skip(4)
        if (majorBrand == "avis") return true

        var compatibleBrandsBytes = declaredSize - headerSize - 8L
        while (compatibleBrandsBytes >= 4L) {
            if (!peek.request(4)) return false
            if (peek.readUtf8(4) == "avis") return true
            compatibleBrandsBytes -= 4L
        }

        false
    } finally {
        peek.close()
    }
}
