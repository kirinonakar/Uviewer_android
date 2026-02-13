package com.uviewer_android.data.parser

import java.util.regex.Pattern

object AozoraParser {
    
    // Convert Aozora ruby to HTML <ruby>
    // Pattern: |Kanji《Ruby》 or Kanji《Ruby》 (where implicit Kanji detection is harder but supported by some regexes)
    // Here focusing on explicit |Kanji《Ruby》 or simple cases.
    // Standard Aozora: 《Ruby》 after Kanji.
    
    fun parse(text: String): String {
        var html = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Ruby pattern: ｜Kanji《Ruby》
        // Also handling simple Kanji《Ruby》 without separators if possible, but reliability varies.
        // Let's implement robust one:
        // 1. |Kanji《Ruby》 -> <ruby>Kanji<rt>Ruby</rt></ruby>
        // 2. ｜Kanji《Ruby》 -> <ruby>Kanji<rt>Ruby</rt></ruby> (Fullwidth bar)
        // 3. (Kanji)《Ruby》 -> <ruby>Kanji<rt>Ruby</rt></ruby> (Common implementation detail?)
        // Standard is: specific kanji sequence followed by 《...》
        
        // Regex for |Kanji《Ruby》
        html = html.replace(Regex("[｜|](.+?)《(.+?)》"), "<ruby>$1<rt>$2</rt></ruby>")
        
        // Regex for Kanji《Ruby》 (simplified, might be greedy or wrong for non-kanji, but common Aozora heuristic)
        // Actually Aozora specs say if no separator, it applies to previous Kanji block.
        // Let's use a simpler approach for now:
        // Match Kanji block followed by 《...》
        html = html.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)《(.+?)》"), "<ruby>$1<rt>$2</rt></ruby>")

        // Image tags: ［＃Image file="foo.jpg"］
        // Regex: ［＃.+?（(.+?)）］ or similar. Aozora image format varies.
        // Typical: <img src="foo.jpg">
        // Let's assume user wants to see images referenced.
        // Format: ［＃插画（image.jpg）］
        html = html.replace(Regex("［＃插画（(.+?)）］"), "<img src=\"$1\" />")

        // Newlines to <br>
        html = html.replace("\n", "<br/>")

        return html
    }

    fun wrapInHtml(bodyContent: String, isVertical: Boolean = false, font: String = "serif", fontSize: Int = 16, backgroundColor: String = "#ffffff"): String {
        val writingMode = if (isVertical) "vertical-rl" else "horizontal-tb"
        val fontFamily = if (font == "serif") "serif" else "sans-serif"
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <style>
                    body {
                        font-family: $fontFamily;
                        font-size: ${fontSize}px;
                        background-color: $backgroundColor;
                        writing-mode: $writingMode;
                        text-orientation: upright;
                        margin: 20px;
                        line-height: 1.8;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                    }
                </style>
            </head>
            <body>
                $bodyContent
            </body>
            </html>
        """.trimIndent()
    }
}
