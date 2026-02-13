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

            // Ruby pattern: ｜Kanji《Ruby》 or ｜Kanji(Ruby) or ｜Kanji（Ruby）
            l = l.replace(Regex("[｜|](.+?)《(.+?)》"), "<ruby>$1<rt>$2</rt></ruby>")
            l = l.replace(Regex("[｜|](.+?)[(（](.+?)[)）]"), "<ruby>$1<rt>$2</rt></ruby>")
            
            // Regex for implicit Kanji《Ruby》 or Kanji(Ruby)
            l = l.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)《(.+?)》"), "<ruby>$1<rt>$2</rt></ruby>")
            l = l.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)[(（]([\\u3040-\\u309F\\u30A0-\\u30FF]+)[)）]"), "<ruby>$1<rt>$2</rt></ruby>")

            // Image tags
            l = l.replace(Regex("［＃.+?（(.+?)）］"), "<img src=\"$1\" />")
            l = l.replace(Regex("［＃(.+?\\.(?:jpg|jpeg|png|gif|webp))］"), "<img src=\"$1\" />")

            // Headers - Adding id for TOC navigation
            l = l.replace(Regex("［＃大見出し］(.+?)［＃大見出し終わり］"), "<h1 class=\"aozora-title\">$1</h1>")
            l = l.replace(Regex("［＃中見出し］(.+?)［＃中見出し終わり］"), "<h2 class=\"aozora-title\">$1</h2>")
            l = l.replace(Regex("［＃小見出し］(.+?)［＃小見出し終わり］"), "<h3 class=\"aozora-title\">$1</h3>")

            // Center tag
            var classes = mutableListOf<String>()
            var styles = mutableListOf<String>()
            
            if (l.contains("［＃センター］")) {
                l = l.replace("［＃センター］", "").replace("［＃センター終わり］", "")
                classes.add("center")
            }
            
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

    fun extractTitles(text: String): List<Pair<String, Int>> {
        val titles = mutableListOf<Pair<String, Int>>()
        val lines = text.split(Regex("\\r?\\n|\\r"))
        val pattern = Regex("［＃[大中小]見出し］(.+?)［＃[大中小]見出し終わり］")
        lines.forEachIndexed { index, line ->
            pattern.find(line)?.let {
                titles.add(it.groupValues[1] to (index + 1))
            }
        }
        return titles
    }

    fun wrapInHtml(bodyContent: String, isVertical: Boolean = false, font: String = "serif", fontSize: Int = 16, backgroundColor: String = "#ffffff", textColor: String = "#000000"): String {
        val writingMode = if (isVertical) "vertical-rl" else "horizontal-tb"
        // Remove monospace, use standard CJK fonts
        val fontFamily = when(font) {
            "serif" -> "'Sawarabi Mincho', serif"
            "sans-serif" -> "'Sawarabi Gothic', sans-serif"
            else -> "serif"
        }
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Sawarabi+Mincho&family=Sawarabi+Gothic&display=swap');
                    body {
                        font-family: $fontFamily;
                        font-size: ${fontSize}px;
                        background-color: $backgroundColor;
                        color: $textColor;
                        writing-mode: $writingMode;
                        -webkit-writing-mode: $writingMode;
                        text-orientation: upright;
                        margin: 0;
                        padding: 1.5em;
                        line-height: 1.8;
                        overflow-x: ${if (isVertical) "auto" else "hidden"};
                        overflow-y: ${if (isVertical) "hidden" else "auto"};
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
                        display: block;
                        margin: 1em auto;
                    }
                    h1, h2, h3 {
                        text-align: center;
                        margin: 2em 0;
                    }
                    /* Support for Vertical text centering and padding */
                    body.vertical {
                        padding: 2em 1.5em;
                    }
                </style>
            </head>
            <body class="${if (isVertical) "vertical" else ""}">
                $bodyContent
            </body>
            </html>
        """.trimIndent()
    }
}
