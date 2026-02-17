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

        // 2. Is valid UTF-8?
        if (isValidUtf8(bytes)) return "UTF-8"

        // 3. HTML Meta Charset Check
        val htmlCharset = detectHtmlCharset(bytes)
        if (htmlCharset != null) return htmlCharset.name()

        // 4. Heuristic Scoring
        val sjisScore = getSjisScore(bytes)
        val eucKrScore = getEucKrScore(bytes)
        val johabScore = getJohabScore(bytes)

        // Winner takes all
        if (sjisScore > eucKrScore && sjisScore > johabScore && sjisScore > 0) {
            return "Shift_JIS"
        }
        if (eucKrScore > sjisScore && eucKrScore > johabScore && eucKrScore > 0) {
            return "EUC-KR"
        }
        if (johabScore > sjisScore && johabScore > eucKrScore && johabScore > 0) {
             return "JO-HAB"
        }

        // Default preference if scores match
        // Johab is rarest, so lowest priority in tie-break
        if (eucKrScore > 0 && eucKrScore >= sjisScore) {
             return "EUC-KR"
        }
        if (sjisScore > 0) {
            return "Shift_JIS"
        }
        if (johabScore > 0) {
             return "JO-HAB"
        }
        
        // 5. Try Johab pattern check
        if (containsJohabPattern(bytes)) {
            return "JO-HAB"
        }

        // Default Fallbacks
        return "EUC-KR"
    }

    private fun containsJohabPattern(bytes: ByteArray): Boolean {
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
            
            // Check for Johab-ONLY patterns:
            // First byte 0x84-0xD3, second byte in ranges CP949 doesn't use
            if (b in 0x84..0xD3) {
                // Johab-only second byte ranges: 0x5B-0x60, 0x7B-0x7E
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



    fun getCharset(name: String?): Charset {
        if (name == null) return StandardCharsets.UTF_8
        try {
            return Charset.forName(name)
        } catch (e: Exception) {
        }
        return StandardCharsets.UTF_8
    }
}
