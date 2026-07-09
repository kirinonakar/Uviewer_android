package com.uviewer_android

import com.uviewer_android.data.utils.EncodingDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodingDetectorTest {

    @Test
    fun testDetectJis() {
        // JIS (ISO-2022-JP) escape sequence: ESC $ B (0x1B, 0x24, 0x42)
        val sample = byteArrayOf(
            0x1B, 0x24, 0x42, // Escape sequence to enter double-byte mode
            0x46, 0x7C,       // "日" in JIS X 0208
            0x1B, 0x28, 0x42  // Escape sequence to return to ASCII
        )
        val name = EncodingDetector.detectEncodingName(sample)
        assertEquals("ISO-2022-JP", name)
    }

    @Test
    fun testDetectGbk() {
        // "测试文本" in GBK: B2 E2 CA D4 CE C4 B1 BE
        val sample = byteArrayOf(
            0xB2.toByte(), 0xE2.toByte(), // 测
            0xCA.toByte(), 0xD4.toByte(), // 试
            0xCE.toByte(), 0xC4.toByte(), // 文
            0xB1.toByte(), 0xBE.toByte()  // 本
        )
        val name = EncodingDetector.detectEncodingName(sample)
        assertEquals("GBK", name)
    }

    @Test
    fun testDetectGb18030() {
        // A 4-byte GB18030 character sequence: e.g. 0x81, 0x30, 0x81, 0x30
        val sample = byteArrayOf(
            0x81.toByte(), 0x30.toByte(), 0x81.toByte(), 0x30.toByte()
        )
        val name = EncodingDetector.detectEncodingName(sample)
        assertEquals("GB18030", name)
    }

    @Test
    fun testDetectBig5() {
        // "你好" in Big5: A4 E3 A6 6E
        val sample = byteArrayOf(
            0xA4.toByte(), 0xE3.toByte(), // 你
            0xA6.toByte(), 0x6E.toByte()  // 好
        )
        val name = EncodingDetector.detectEncodingName(sample)
        assertEquals("Big5", name)
    }

    @Test
    fun testDetectEucKr() {
        // "안녕하세요" in EUC-KR: BE C8 B3 E7 C7 CF BC BC BF E4
        val sample = byteArrayOf(
            0xBE.toByte(), 0xC8.toByte(),
            0xB3.toByte(), 0xE7.toByte(),
            0xC7.toByte(), 0xCF.toByte(),
            0xBC.toByte(), 0xBC.toByte(),
            0xBF.toByte(), 0xE4.toByte()
        )
        val name = EncodingDetector.detectEncodingName(sample)
        assertEquals("EUC-KR", name)
    }

    @Test
    fun testDetectJohab() {
        // "안녕하세요. 이것은 조합형 텍스트 검증을 위한 샘플입니다." in Johab + Johab marker character (0x88 0x7B)
        val sample = byteArrayOf(
            0xB4.toByte(), 0x65.toByte(),
            0x91.toByte(), 0x77.toByte(),
            0xD0.toByte(), 0x61.toByte(),
            0xAD.toByte(), 0x41.toByte(),
            0xB6.toByte(), 0x61.toByte(),
            0x2E.toByte(), 0x20.toByte(),
            0xB7.toByte(), 0xA1.toByte(),
            0x88.toByte(), 0xF5.toByte(),
            0xB7.toByte(), 0x65.toByte(),
            0x20.toByte(),
            0xB9.toByte(), 0xA1.toByte(),
            0xD0.toByte(), 0x73.toByte(),
            0xD1.toByte(), 0x77.toByte(),
            0x20.toByte(),
            0xC9.toByte(), 0x42.toByte(),
            0xAF.toByte(), 0x61.toByte(),
            0xCB.toByte(), 0x61.toByte(),
            0x20.toByte(),
            0x88.toByte(), 0xF1.toByte(),
            0xBB.toByte(), 0x77.toByte(),
            0xB7.toByte(), 0x69.toByte(),
            0x20.toByte(),
            0xB6.toByte(), 0xE1.toByte(),
            0xD0.toByte(), 0x65.toByte(),
            0x20.toByte(),
            0xAC.toByte(), 0x91.toByte(),
            0xCF.toByte(), 0x69.toByte(),
            0xB7.toByte(), 0xB3.toByte(),
            0x93.toByte(), 0xA1.toByte(),
            0x94.toByte(), 0x61.toByte(),
            0x2E.toByte(),
            0x88.toByte(), 0x7B.toByte()
        )
        val name = EncodingDetector.detectEncodingName(sample)
        assertEquals("JO-HAB", name)
    }
}
