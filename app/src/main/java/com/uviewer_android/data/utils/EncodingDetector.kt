package com.uviewer_android.data.utils

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object EncodingDetector {

    fun detectEncoding(bytes: ByteArray): Charset {
        val name = detectEncodingName(bytes)
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            StandardCharsets.UTF_8
        }
    }

    fun detectEncodingName(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "UTF-8"

        // 1. Check for BOM
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF) return "UTF-8"
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return "UTF-16LE"
            if (b0 == 0xFE && b1 == 0xFF) return "UTF-16BE"
        }

        // Check for ISO-2022-JP (JIS) escape sequences FIRST (stateful 7-bit encoding)
        if (isJis(bytes)) return "ISO-2022-JP"

        // 2. Is valid UTF-8?
        if (isValidUtf8(bytes)) return "UTF-8"

        // 3. HTML Meta Charset Check
        val htmlCharset = detectHtmlCharset(bytes)
        if (htmlCharset != null) return htmlCharset.name()

        // 4. Heuristic Scoring
        val sjisScore = getSjisScore(bytes)
        val eucKrScore = getEucKrScore(bytes)
        val johabScore = getJohabScore(bytes)
        val johabMarkerPairCount = countJohabMarkerPairs(bytes)
        val gbkScore = getGbkScore(bytes)
        val gb18030Score = getGb18030Score(bytes)
        val big5Score = getBig5Score(bytes)

        val maxScore = maxOf(sjisScore, eucKrScore, johabScore, gbkScore, gb18030Score, big5Score)

        if (maxScore > 0) {
            if (maxScore == eucKrScore) return "EUC-KR"
            if (maxScore == sjisScore) return "Shift_JIS"

            val gbkFamilyScoreIsWinning = maxScore == gbkScore || maxScore == gb18030Score
            val chineseScoreIsWinning = gbkFamilyScoreIsWinning || maxScore == big5Score
            if (chineseScoreIsWinning &&
                shouldPreferJohabOverChineseScores(bytes.size, johabScore, johabMarkerPairCount, gbkScore, gb18030Score, big5Score)) {
                return "JO-HAB"
            }

            if (chineseScoreIsWinning &&
                shouldPreferEucKrOverChineseScores(bytes, eucKrScore)) {
                return "EUC-KR"
            }

            if (maxScore == gb18030Score && gb18030Score > gbkScore) return "GB18030"
            if (maxScore == gbkScore) return "GBK"
            if (maxScore == big5Score) return "Big5"
            if (maxScore == johabScore) return "JO-HAB"
        }

        if (johabScore > 0 || johabMarkerPairCount >= 2) return "JO-HAB"

        return "EUC-KR"
    }

    private fun countJohabMarkerPairs(bytes: ByteArray): Int {
        var johabOnlyPairCount = 0
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            if (i + 1 >= len) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            if (b in 0x84..0xD3) {
                val johabOnlySecond = (b2 in 0x5B..0x60) || (b2 in 0x7B..0x7E)
                if (johabOnlySecond) {
                    johabOnlyPairCount++
                    i += 2
                    continue
                }
                if ((b2 in 0x41..0x7E) || (b2 in 0x81..0xFE)) {
                    i += 2
                    continue
                }
            }
            i++
        }
        return johabOnlyPairCount
    }

    private fun shouldPreferJohabOverChineseScores(
        byteCount: Int,
        johabScore: Int,
        johabMarkerPairCount: Int,
        gbkScore: Int,
        gb18030Score: Int,
        big5Score: Int
    ): Boolean {
        val requiredMarkerPairs = if (byteCount < 1024) 1 else if (byteCount >= 16 * 1024) 8 else 2
        if (johabScore <= 0 || johabMarkerPairCount < requiredMarkerPairs) {
            return false
        }
        val chineseScore = maxOf(gbkScore, gb18030Score, big5Score)
        return chineseScore <= 0 || johabScore.toLong() * 4 >= chineseScore.toLong() * 3
    }

    private fun shouldPreferEucKrOverChineseScores(bytes: ByteArray, eucKrScore: Int): Boolean {
        if (eucKrScore <= 0) {
            return false
        }

        val profile = getTextScriptProfile(bytes)
        val requiredHangulCount = if (bytes.size < 1024) 8 else 32
        if (profile.hangulCount < requiredHangulCount) {
            return false
        }

        if (profile.cjkCount * 3 > profile.hangulCount) {
            return false
        }

        return profile.badCharacterCount <= maxOf(2, profile.hangulCount / 6)
    }

    private fun getTextScriptProfile(bytes: ByteArray): TextScriptProfile {
        val sampleLimit = 128 * 1024
        val sampleLength = minOf(bytes.size, sampleLimit)
        val cs = try { Charset.forName("EUC-KR") } catch (e: Exception) { java.nio.charset.StandardCharsets.UTF_8 }
        val text = String(bytes, 0, sampleLength, cs)

        var hangulCount = 0
        var cjkCount = 0
        var badCharacterCount = 0
        for (i in 0 until text.length) {
            val ch = text[i]
            if (isHangul(ch)) {
                hangulCount++
            } else if (isCjk(ch)) {
                cjkCount++
            } else if (ch == '\uFFFD' || ch == '?') {
                badCharacterCount++
            }
        }
        return TextScriptProfile(hangulCount, cjkCount, badCharacterCount)
    }

    private fun isHangul(ch: Char): Boolean {
        return (ch in '\uAC00'..'\uD7A3') ||
               (ch in '\u1100'..'\u11FF') ||
               (ch in '\u3130'..'\u318F')
    }

    private fun isCjk(ch: Char): Boolean {
        return (ch in '\u4E00'..'\u9FFF') ||
               (ch in '\u3400'..'\u4DBF')
    }

    private class TextScriptProfile(
        val hangulCount: Int,
        val cjkCount: Int,
        val badCharacterCount: Int
    )

    @Suppress("unused")
    private fun isStrictJohab(bytes: ByteArray): Boolean {
        var i = 0
        val len = bytes.size
        var johabFirstByteCount = 0
        var totalMultibyte = 0
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            if (b in 0x80..0x83) {
                i++
                continue
            }
            if (i + 1 >= len) {
                i++
                continue
            }
            val b2 = bytes[i + 1].toInt() and 0xFF
            totalMultibyte++
            
            // First byte 0x84-0xA0: Johab-only range
            if (b in 0x84..0xA0) {
                if ((b2 in 0x41..0x7E) || (b2 in 0x81..0xFE)) {
                    johabFirstByteCount++
                    i += 2
                    continue
                }
                i++
                continue
            }
            
            if (b in 0xA1..0xFE) {
                if ((b2 in 0x41..0xFE) && b2 != 0x7F) {
                    i += 2
                    continue
                }
                i++
                continue
            }
            i++
        }
        
        if (johabFirstByteCount >= 50) return true
        if (totalMultibyte >= 100 && johabFirstByteCount >= (totalMultibyte * 15 / 100)) return true
        return false
    }

    private fun getJohabScore(bytes: ByteArray): Int {
        var score = 0
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            if (i + 1 >= len) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            
            // Johab First Byte: 0x84-0xD3
            if (b in 0x84..0xD3) {
                // Check for Johab-ONLY second byte ranges: 0x5B-0x60, 0x7B-0x7E
                if ((b2 in 0x5B..0x60) || (b2 in 0x7B..0x7E)) {
                    score += 3
                    i += 2
                    continue
                }
                
                // Normal Johab second byte: 0x41-0x7E or 0x81-0xFE
                if ((b2 in 0x41..0x7E) || (b2 in 0x81..0xFE)) {
                    score += 1
                    i += 2
                    continue
                }
            }
            i++
        }
        return score
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b <= 0x7F) {
                i++
                continue
            }
            val count = when {
                b and 0xE0 == 0xC0 -> 1
                b and 0xF0 == 0xE0 -> 2
                b and 0xF8 == 0xF0 -> 3
                else -> return false
            }
            
            if (i + count >= bytes.size) {
                // If truncated, check the remaining bytes
                for (j in 1 until (bytes.size - i)) {
                    val nextB = bytes[i + j].toInt() and 0xFF
                    if (nextB and 0xC0 != 0x80) return false
                }
                return true
            }
            
            for (j in 1..count) {
                val nextB = bytes[i + j].toInt() and 0xFF
                if (nextB and 0xC0 != 0x80) return false
            }
            i += count + 1
        }
        return true
    }

    private fun detectHtmlCharset(bytes: ByteArray): Charset? {
        val head = String(bytes.take(2048).toByteArray(), StandardCharsets.US_ASCII).lowercase()
        if (head.contains("charset=")) {
            val match = Regex("charset=['\"]?([a-zA-Z0-9_-]+)").find(head)
            if (match != null) {
                try {
                    return Charset.forName(match.groupValues[1])
                } catch (e: Exception) {}
            }
        }
        return null
    }

    private fun getSjisScore(bytes: ByteArray): Int {
        var score = 0
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            
            // Half-width Katakana (0xA1-0xDF)
            if (b in 0xA1..0xDF) {
                 if (i + 1 < len && (bytes[i + 1].toInt() and 0xFF) < 0x80) score += 1
                 i++
                 continue
            }
            
            if (i + 1 >= len) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            
            // SJIS First Byte: 0x81-0x9F, 0xE0-0xFC
            if ((b in 0x81..0x9F) || (b in 0xE0..0xFC)) {
                // Valid SJIS second byte: 0x40-0x7E or 0x80-0xFC
                val validSecond = (b2 in 0x40..0x7E) || (b2 in 0x80..0xFC)
                if (validSecond) {
                    // 0x82, 0x83 are Hiragana and Katakana - VERY strong signal for Japanese
                    if (b == 0x82 || b == 0x83) {
                        score += 5
                    } else {
                        score += 1
                    }
                    i += 2
                    continue
                }
            }
            i++
        }
        return score
    }

    private fun getEucKrScore(bytes: ByteArray): Int {
        var score = 0
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b1 = bytes[i].toInt() and 0xFF
            if (b1 < 0x80) {
                i++
                continue
            }
            if (i + 1 >= len) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            
            // Standard EUC-KR Hangul: 0xB0-0xC8 first, 0xA1-0xFE second
            // This is the strongest signal for Korean text.
            if (b1 in 0xB0..0xC8 && b2 in 0xA1..0xFE) {
                score += 2
                i += 2
                continue
            }
            
            // Note: Per reference code, we explicitly DO NOT count CP949 Extended Range (0x81-0xA0) 
            // or Symbols (0xA1-0xAF) here to avoid overlap with SJIS and Johab.
            
            i++
        }
        return score
    }



    private fun isJis(bytes: ByteArray): Boolean {
        var i = 0
        val len = bytes.size
        while (i < len - 2) {
            if (bytes[i].toInt() == 0x1B) { // ESC
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                if (b1 == 0x24 && (b2 == 0x40 || b2 == 0x42)) {
                    return true
                }
                if (b1 == 0x28 && (b2 == 0x42 || b2 == 0x4A || b2 == 0x49)) {
                    return true
                }
            }
            i++
        }
        return false
    }

    private fun getGb18030Score(bytes: ByteArray): Int {
        var score = 0
        var i = 0
        val len = bytes.size
        var hasFourByteSequence = false
        while (i < len) {
            val b1 = bytes[i].toInt() and 0xFF
            if (b1 < 0x80) {
                i++
                continue
            }
            
            // Check for 4-byte sequence:
            if (i + 3 < len) {
                val b2 = bytes[i + 1].toInt() and 0xFF
                val b3 = bytes[i + 2].toInt() and 0xFF
                val b4 = bytes[i + 3].toInt() and 0xFF
                if (b1 in 0x81..0xFE && b2 in 0x30..0x39 && b3 in 0x81..0xFE && b4 in 0x30..0x39) {
                    score += 4
                    hasFourByteSequence = true
                    i += 4
                    continue
                }
            }
            
            if (i + 1 >= len) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            if (b1 in 0xB0..0xF7 && b2 in 0xA1..0xFE) {
                score += 2
                i += 2
                continue
            }
            i++
        }
        if (hasFourByteSequence) {
            score += 10000
        }
        return score
    }

    private fun getGbkScore(bytes: ByteArray): Int {
        var score = 0
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b1 = bytes[i].toInt() and 0xFF
            if (b1 < 0x80) {
                i++
                continue
            }
            if (i + 1 >= len) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            
            if (b1 in 0xB0..0xF7 && b2 in 0xA1..0xFE) {
                score += 2
                i += 2
                continue
            }
            i++
        }
        return score
    }

    private fun getBig5Score(bytes: ByteArray): Int {
        var score = 0
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b1 = bytes[i].toInt() and 0xFF
            if (b1 < 0x80) {
                i++
                continue
            }
            if (i + 1 >= len) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            
            if (b1 in 0xA4..0xF9 && (b2 in 0x40..0x7E || b2 in 0xA1..0xFE)) {
                score += 2
                i += 2
                continue
            }
            i++
        }
        return score
    }

    fun getCharset(name: String?): Charset {
        if (name == null) return StandardCharsets.UTF_8
        try {
            return Charset.forName(name)
        } catch (e: Exception) {
        }
        return StandardCharsets.UTF_8
    }
}
