package com.uviewer_android.data.parser

import java.util.regex.Pattern

object AozoraParser {
    
    // Convert Aozora ruby to HTML <ruby>
    // Pattern: |Kanji《Ruby》 or Kanji《Ruby》 (where implicit Kanji detection is harder but supported by some regexes)
    // Here focusing on explicit |Kanji《Ruby》 or simple cases.
    // Standard Aozora: 《Ruby》 after Kanji.
    
    fun parse(text: String, lineOffset: Int = 0): String {
        val lines = text.split(Regex("\\r?\\n|\\r"))
        var isBold = false
        val parsedLines = lines.mapIndexed { index, line ->
            var l = line
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            // Ruby pattern: ｜Kanji《Ruby》 or ｜Kanji(Ruby) or ｜Kanji（Ruby）
            l = l.replace(Regex("[｜|](.+?)《(.+?)》")) { m ->
                val ruby = m.groupValues[2]
                val className = if (ruby.length == 3) " class=\"ruby-3\"" else ""
                "<ruby>${m.groupValues[1]}<rt$className>$ruby</rt></ruby>"
            }
            l = l.replace(Regex("[｜|](.+?)[(（](.+?)[)）]")) { m ->
                val ruby = m.groupValues[2]
                val className = if (ruby.length == 3) " class=\"ruby-3\"" else ""
                "<ruby>${m.groupValues[1]}<rt$className>$ruby</rt></ruby>"
            }
            
            // Regex for implicit Kanji《Ruby》 or Kanji(Ruby)
            l = l.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)《(.+?)》")) { m ->
                val ruby = m.groupValues[2]
                val className = if (ruby.length == 3) " class=\"ruby-3\"" else ""
                "<ruby>${m.groupValues[1]}<rt$className>$ruby</rt></ruby>"
            }
            l = l.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)[(（]([\\u3040-\\u309F\\u30A0-\\u30FF]+)[)）]")) { m ->
                val ruby = m.groupValues[2]
                val className = if (ruby.length == 3) " class=\"ruby-3\"" else ""
                "<ruby>${m.groupValues[1]}<rt$className>$ruby</rt></ruby>"
            }

            // Image tags - Case insensitive for extensions
            l = l.replace(Regex("［＃挿絵（(.+?)）入る］"), "<img src=\"$1\" />") // Specific format with "入る"
            l = l.replace(Regex("［＃.+?（(.+?)）］"), "<img src=\"$1\" />")
            l = l.replace(Regex("(?i)［＃(.+?\\.(?:jpg|jpeg|png|gif|webp))］"), "<img src=\"$1\" />")

            // Headers - Adding id for TOC navigation
            l = l.replace(Regex("［＃大見出し］(.+?)［＃大見出し終わり］"), "<h1 class=\"aozora-title\">$1</h1>")
            l = l.replace(Regex("［＃中見出し］(.+?)［＃中見出し終わり］"), "<h2 class=\"aozora-title\">$1</h2>")
            l = l.replace(Regex("［＃小見出し］(.+?)［＃小見出し終わり］"), "<h3 class=\"aozora-title\">$1</h3>")
            
            // Bold - Maintain state across lines
            if (l.contains("［＃ここから太字］") || l.contains("［＃ 여기서 태그 시작 ］")) {
                isBold = true
                l = l.replace(Regex("［＃(?:ここから太字| 여기서 태그 시작 )］"), "<b>")
            }
            if (l.contains("［＃ここで太字終わり］") || l.contains("［＃太字終わり］") || l.contains("［＃ 여기서 태그 끝 ］")) {
                isBold = false
                l = l.replace(Regex("［＃(?:여기서 태그 끝 |ここで太字終わり|太字終わり)］"), "</b>")
            }
            // If currently bold and no close tag on this line, or if just opened, wrap/ensure validity?
            // HTML parsers (browsers) are lenient, but if we have <div><b>...</div><div>...</b></div> it might break.
            // Valid HTML approach: Close </b> at end of div if bold is active, open <b> at start of next div.
            
            // However, Aozora "bold block" might span lines.
            // Simple approach: Replace tags with span/b.
            // If the browser closes <b> automatically at block end (div), we need to re-open it.
            
            // Implementation:
            // If isBold was true at start of line, prepend <b>
            // If isBold is true at end of line, append </b>
            
            var lineContent = l
            if (isBold && !lineContent.startsWith("<b>") && !lineContent.contains("［＃ここから太字］")) {
                 lineContent = "<b>$lineContent"
            }
            if (isBold && !lineContent.endsWith("</b>") && !lineContent.contains("［＃ここで太字終わり］")) {
                 lineContent = "$lineContent</b>"
            }

            // Page Break
            lineContent = lineContent.replace(Regex("［＃改ページ］"), "<div style=\"break-after: page; height: 100vh; width: 1px;\"></div>")
            lineContent = lineContent.replace(Regex("［＃改頁］"), "<div style=\"break-after: page; height: 100vh; width: 1px;\"></div>")
            
            // Indent
            lineContent = lineContent.replace(Regex("［＃ここから(\\d+)字下げ］"), "<div style=\"margin-inline-start: $1em;\">")
            lineContent = lineContent.replace("［＃ここで字下げ終わり］", "</div>")

            // Center tag
            var classes = mutableListOf<String>()
            var styles = mutableListOf<String>()
            
            if (lineContent.contains("［＃センター］")) {
                lineContent = lineContent.replace("［＃センター］", "").replace("［＃センター終わり］", "")
                classes.add("center")
            }
            
            val indentMatch = Regex("［＃地から(\\d+)字上げ］").find(lineContent)
            if (indentMatch != null) {
                val n = indentMatch.groupValues[1]
                lineContent = lineContent.replace(Regex("［＃地から(\\d+)字上げ］"), "")
                styles.add("padding-bottom: ${n}em")
            }

            val classAttr = if (classes.isNotEmpty()) " class=\"${classes.joinToString(" ")}\"" else ""
            val styleAttr = if (styles.isNotEmpty()) " style=\"${styles.joinToString("; ")}\"" else ""
            
            if (lineContent.isBlank()) {
                "<div id=\"line-${index + lineOffset + 1}\" $classAttr $styleAttr>&nbsp;</div>"
            } else {
                "<div id=\"line-${index + lineOffset + 1}\" $classAttr $styleAttr>$lineContent</div>"
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
        val writingMode = "horizontal-tb"
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
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
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
                        padding: 0;
                        line-height: 1.8;
                        overflow-x: ${if (isVertical) "auto" else "hidden"};
                        overflow-y: ${if (isVertical) "hidden" else "auto"};
                        height: 100vh;
                        width: ${if (isVertical) "auto" else "100%"};
                    }
                    p, div, h1, h2, h3 {
                        padding: 0 1.2em;
                        min-height: 1.2em;
                    }
                    div:has(img), p:has(img) {
                        padding: 0 !important;
                    }
                    .center {
                        text-align: center;
                    }
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        margin: 1em auto;
                        vertical-align: middle;
                    }
                    h1.aozora-title { font-size: 1.3em; }
                    h2.aozora-title { font-size: 1.15em; }
                    h3.aozora-title { font-size: 1.05em; }
                    
                    h1, h2, h3 {
                        text-align: center;
                        margin: 1.5em 0;
                    }
                    rt.ruby-3 {
                        display: inline-block;
                        transform: scaleX(0.75);
                        transform-origin: center;
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
