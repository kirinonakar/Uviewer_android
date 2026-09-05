package com.uviewer_android

import com.uviewer_android.data.utils.isAnimatedAvif
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedAvifDecoderTest {
    @Test
    fun `detects avis as the major brand`() {
        val source = ftyp("avis")

        assertTrue(source.isAnimatedAvif())
        assertEquals(16L, source.size)
    }

    @Test
    fun `detects avis as a compatible brand`() {
        val source = ftyp("avif", "avis")

        assertTrue(source.isAnimatedAvif())
        assertEquals(20L, source.size)
    }

    @Test
    fun `does not classify a still avif as animated`() {
        assertFalse(ftyp("avif", "mif1").isAnimatedAvif())
    }

    private fun ftyp(majorBrand: String, vararg compatibleBrands: String): Buffer =
        Buffer().apply {
            writeInt(16 + compatibleBrands.size * 4)
            writeUtf8("ftyp")
            writeUtf8(majorBrand)
            writeInt(0)
            compatibleBrands.forEach(::writeUtf8)
        }
}
