package com.uviewer_android.data.utils

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object EncodingDetector {

    fun detectEncoding(bytes: ByteArray): Charset {
        if (bytes.isEmpty()) return StandardCharsets.UTF_8

        // 1. Check for BOM
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF) return StandardCharsets.UTF_8
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE
        }

        // 2. Is valid UTF-8?
        if (isValidUtf8(bytes)) return StandardCharsets.UTF_8

        // 3. HTML Meta Charset Check
        val htmlCharset = detectHtmlCharset(bytes)
        if (htmlCharset != null) return htmlCharset

        // 4. Heuristic Scoring
        val sjisScore = getSjisScore(bytes)
        val eucKrScore = getEucKrScore(bytes)
        val johabScore = getJohabScore(bytes)

        // Winner takes all
        if (sjisScore > eucKrScore && sjisScore > johabScore && sjisScore > 0) {
            return try { Charset.forName("Shift_JIS") } catch (e: Exception) { StandardCharsets.UTF_8 }
        }
        if (eucKrScore > sjisScore && eucKrScore > johabScore && eucKrScore > 0) {
            return try { Charset.forName("EUC-KR") } catch (e: Exception) { StandardCharsets.UTF_8 }
        }
        if (johabScore > sjisScore && johabScore > eucKrScore && johabScore > 0) {
            return try { Charset.forName("x-Johab") } catch (e: Exception) { StandardCharsets.UTF_8 }
        }

        // Default preference if scores match
        if (eucKrScore > 0 && eucKrScore >= sjisScore) {
            return try { Charset.forName("EUC-KR") } catch (e: Exception) { StandardCharsets.UTF_8 }
        }
        if (sjisScore > 0) {
            return try { Charset.forName("Shift_JIS") } catch (e: Exception) { StandardCharsets.UTF_8 }
        }
        if (johabScore > 0) {
            return try { Charset.forName("x-Johab") } catch (e: Exception) { StandardCharsets.UTF_8 }
        }

        // 5. Final Johab check
        if (containsJohabPattern(bytes)) {
            return try { Charset.forName("x-Johab") } catch (e: Exception) { StandardCharsets.UTF_8 }
        }

        // Default Fallbacks
        return try { Charset.forName("EUC-KR") } catch (e: Exception) { 
            try { Charset.forName("Shift_JIS") } catch (e2: Exception) { StandardCharsets.UTF_8 }
        }
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
            if (i + count >= bytes.size) return false
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
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            if (b in 0xA1..0xDF) {
                if (i + 1 < bytes.size && (bytes[i + 1].toInt() and 0xFF) < 0x80) score += 1
                i++
                continue
            }
            if (i + 1 >= bytes.size) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            if ((b in 0x81..0x9F) || (b in 0xE0..0xFC)) {
                val validSecond = (b2 in 0x40..0x7E) || (b2 in 0x80..0xFC)
                if (validSecond) {
                    if (b == 0x82 || b == 0x83) score += 5 else score += 1
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
        while (i < bytes.size) {
            val b1 = bytes[i].toInt() and 0xFF
            if (b1 < 0x80) {
                i++
                continue
            }
            if (i + 1 >= bytes.size) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            if (b1 in 0xB0..0xC8 && b2 in 0xA1..0xFE) {
                score += 2
                i += 2
                continue
            }
            i++
        }
        return score
    }

    private fun getJohabScore(bytes: ByteArray): Int {
        var score = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            if (i + 1 >= bytes.size) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            if (b in 0x84..0xD3) {
                if ((b2 in 0x5B..0x60) || (b2 in 0x7B..0x7E)) {
                    score += 3
                    i += 2
                    continue
                }
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

    private fun containsJohabPattern(bytes: ByteArray): Boolean {
        var johabOnlyPairCount = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            if (i + 1 >= bytes.size) break
            val b2 = bytes[i + 1].toInt() and 0xFF
            if (b in 0x84..0xD3) {
                val johabOnlySecond = (b2 in 0x5B..0x60) || (b2 in 0x7B..0x7E)
                if (johabOnlySecond) {
                    johabOnlyPairCount++
                    i += 2
                    if (johabOnlyPairCount >= 2) return true
                    continue
                }
            }
            if (b >= 0x81) {
                if ((b2 in 0x41..0x7E) || (b2 in 0x81..0xFE)) {
                    i += 2
                    continue
                }
            }
            i++
        }
        return false
    }
}
