package com.uviewer_android.data.parser

import java.util.regex.Pattern

object AozoraParser {
    
    // Convert Aozora ruby to HTML <ruby>
    // Pattern: |Kanji《Ruby》 or Kanji《Ruby》 (where implicit Kanji detection is harder but supported by some regexes)
    // Here focusing on explicit |Kanji《Ruby》 or simple cases.
    // Standard Aozora: 《Ruby》 after Kanji.
    
    fun parse(text: String): String {
        val lines = text.split(Regex("\\r?\\n|\\r"))
        val parsedLines = lines.mapIndexed { index, line ->
            var l = line
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            // Ruby pattern: ｜Kanji《Ruby》
            l = l.replace(Regex("[｜|](.+?)《(.+?)》"), "<ruby>$1<rt>$2</rt></ruby>")
            
            // Regex for implicit Kanji《Ruby》
            l = l.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)《(.+?)》"), "<ruby>$1<rt>$2</rt></ruby>")

            // Image tags
            // Pattern 1: ［＃插画（image.jpg）］ or ［＃Image（image.jpg）］
            l = l.replace(Regex("［＃.+?（(.+?)）］"), "<img src=\"$1\" />")
            // Pattern 2: ［＃image.jpg］ (Simple format)
            l = l.replace(Regex("［＃(.+?\\.(?:jpg|jpeg|png|gif|webp))］"), "<img src=\"$1\" />")

            // Headers
            l = l.replace(Regex("［＃大見出し］(.+?)［＃大見出し終わり］"), "<h1>$1</h1>")
            l = l.replace(Regex("［＃中見出し］(.+?)［＃中見出し終わり］"), "<h2>$1</h2>")
            l = l.replace(Regex("［＃小見出し］(.+?)［＃小見出し終わり］"), "<h3>$1</h3>")
            l = l.replace(Regex("［＃４段階見出し］(.+?)［＃４段階見出し終わり］"), "<h4>$1</h4>")
            l = l.replace(Regex("［＃５段階見出し］(.+?)［＃５段階見出し終わり］"), "<h5>$1</h5>")

            // Center tag
            var classes = mutableListOf<String>()
            var styles = mutableListOf<String>()
            
            if (l.contains("［＃センター］")) {
                l = l.replace("［＃センター］", "").replace("［＃センター終わり］", "")
                classes.add("center")
            }
            
            // 地からN字上げ
            val indentMatch = Regex("［＃地から(\\d+)字上げ］").find(l)
            if (indentMatch != null) {
                val n = indentMatch.groupValues[1]
                l = l.replace(Regex("［＃地から(\\d+)字上げ］"), "")
                styles.add("padding-bottom: ${n}em")
            }

            val classAttr = if (classes.isNotEmpty()) " class=\"${classes.joinToString(" ")}\"" else ""
            val styleAttr = if (styles.isNotEmpty()) " style=\"${styles.joinToString("; ")}\"" else ""
            
            if (l.isBlank()) {
                "<div id=\"line-${index + 1}\" $classAttr $styleAttr>&nbsp;</div>"
            } else {
                "<div id=\"line-${index + 1}\" $classAttr $styleAttr>$l</div>"
            }
        }

        return parsedLines.joinToString("\n")
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
                        margin: 0;
                        padding: 1em;
                        line-height: 1.8;
                    }
                    div {
                        min-height: 1.2em;
                    }
                    .center {
                        text-align: center;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                    }
                    h1, h2, h3, h4, h5 {
                        text-align: center;
                        margin: 1em 0;
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
