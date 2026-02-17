package com.uviewer_android.data.utils

import java.io.File
import java.io.FileInputStream

object JohabConverter {

    // [초성 매핑] bit 2~20 → 유니코드 초성 인덱스 0~18 (bit - 2)
    // ㄱ(0)~ㅎ(18), bit 6=ㄸ, bit 15=ㅉ, bit 17=ㅋ 는 문자에 따라 사용
    private val CHO_MAP = IntArray(32) { -1 }.apply {
        for (bit in 2..20) this[bit] = bit - 2
    }

    // [중성 매핑] CP1361 Johab 기준
    // bit 8, 9, 16, 17, 24, 25 는 사용되지 않는 gap 위치
    private val JUNG_MAP = IntArray(32) { -1 }.apply {
        this[3] = 0;  this[4] = 1;  this[5] = 2;  this[6] = 3;  this[7] = 4   // ㅏ ㅐ ㅑ ㅒ ㅓ
        // bit 8, 9 = gap
        this[10] = 5; this[11] = 6; this[12] = 7; this[13] = 8; this[14] = 9  // ㅔ ㅕ ㅖ ㅗ ㅘ
        this[15] = 10                                                           // ㅙ
        // bit 16, 17 = gap
        this[18] = 11; this[19] = 12; this[20] = 13                            // ㅚ ㅛ ㅜ
        this[21] = 14; this[22] = 15; this[23] = 16                            // ㅝ ㅞ ㅟ
        // bit 24, 25 = gap
        this[26] = 17; this[27] = 18; this[28] = 19; this[29] = 20            // ㅠ ㅡ ㅢ ㅣ
    }

    // [종성 매핑] CP1361 Johab 기준 — 핵심 수정 포인트!
    // bit  1     = 없음(0)
    // bit  2~17  = ㄱ(1)~ㅁ(16) : idx = bit - 1
    // bit  18    = gap (CP1361에서 비어있는 위치, 사용 안 됨)
    // bit  19~29 = ㅂ(17)~ㅎ(27) : idx = bit - 2
    //
    // 기존 코드의 버그: bit 2..28 전체를 bit-1로 계산했기 때문에
    //   bit 22 → 21(ㅇ)로 틀리게 매핑 (실제는 20=ㅆ) ← "있" 깨짐 원인
    //   bit 23 → 22(ㅈ)로 틀리게 매핑 (실제는 21=ㅇ) ← "방" 깨짐 원인
    private val JONG_MAP = IntArray(32) { 0 }.apply {
        this[1] = 0                             // 없음
        for (bit in 2..17)  this[bit] = bit - 1 // ㄱ(1) ~ ㅁ(16)
        // bit 18 = gap → 0 유지
        for (bit in 19..29) this[bit] = bit - 2 // ㅂ(17) ~ ㅎ(27)
    }

    fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) return "File not found"
        val bytes = FileInputStream(file).use { it.readBytes() }
        return decode(bytes)
    }

    fun decode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b1 = data[i].toInt() and 0xFF

            // ASCII (1바이트)
            if (b1 < 0x80) {
                sb.append(b1.toChar())
                i++
                continue
            }

            // 조합형 한글 (2바이트)
            if (i + 1 >= data.size) break
            val b2 = data[i + 1].toInt() and 0xFF
            val code = (b1 shl 8) or b2

            val choBit  = (code shr 10) and 0x1F
            val jungBit = (code shr 5)  and 0x1F
            val jongBit =  code         and 0x1F

            val choIdx  = CHO_MAP[choBit]
            val jungIdx = JUNG_MAP[jungBit]
            val jongIdx = JONG_MAP[jongBit]  // 0 = 받침 없음

            if (choIdx != -1 && jungIdx != -1) {
                val unicode = 0xAC00 + (choIdx * 588) + (jungIdx * 28) + jongIdx
                sb.append(unicode.toChar())
            } else {
                // 한글 범위 밖 (한자, 특수문자 등)
                sb.append('?')
            }
            i += 2
        }
        return sb.toString()
    }
}
