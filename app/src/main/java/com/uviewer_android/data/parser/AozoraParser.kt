package com.uviewer_android.data.parser

import java.util.regex.Pattern
import kotlin.text.RegexOption
import kotlin.text.MatchResult
import java.net.URLEncoder

object AozoraParser {
    
    // Helper to encode filename segments while preserving path structure and protocols
    private fun encodeFileName(path: String): String {
        return try {
            path.split("/").joinToString("/") { segment ->
                // Skip protocol parts (e.g., "file:", "http:")
                if (segment.endsWith(":")) segment 
                else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        } catch (e: Exception) {
            path
        }
    }
    
    // Convert Aozora ruby to HTML <ruby>
    
    fun parse(text: String, lineOffset: Int = 0, imageRootPath: String = ""): String {
        // 1. 텍스트 내의 <img ...> 태그를 찾아서 임시 마커로 변경 (보호 처리)
        //    이렇게 안 하면 replace("<", "&lt;") 때 태그가 다 깨짐
        val imgTagMap = mutableMapOf<String, String>()
        var protectedText = text.replace(Regex("<img[^>]*src=\"([^\"]*)\"[^>]*>")) { match ->
            val originalTag = match.value
            val src = match.groupValues[1]
            val marker = "%%%IMG_MARKER_${imgTagMap.size}%%%"
            
            // 이미지가 http나 file로 시작하지 않으면 경로 추가
            val newSrc = if (src.startsWith("http") || src.startsWith("file://")) {
                src
            } else {
                val rootPath = imageRootPath.removePrefix("file://")
                val imgFile = java.io.File(rootPath, src)
                "file://${imgFile.absolutePath}"
            }
            
            // 파일명 인코딩 적용 (일본어/공백 대응)
            val encodedSrc = encodeFileName(newSrc)
            val newTag = originalTag.replace(src, encodedSrc)
            imgTagMap[marker] = newTag
            marker
        }

        // [수정 1] 마커를 마크다운 문법(__)과 겹치지 않는 특수 문자로 변경 (%%% 사용)
        val markerStart = "%%%BOLD_START%%%"
        val markerEnd = "%%%BOLD_END%%%"

        val boldStartPattern = "［＃(?:여기서 태그 시작 |ここから太字)］"
        val boldEndPattern = "［＃(?:여기서 태그 끝 | 여기서 태그 끝 |太字終わり)］"
        val boldRegex = Regex("$boldStartPattern(.*?)$boldEndPattern", RegexOption.DOT_MATCHES_ALL)

        val markedText = boldRegex.replace(protectedText) { match: MatchResult ->
            val content = match.groupValues[1]
            val startRegex = Regex(boldStartPattern)
            val parts = content.split(startRegex)
            
            if (parts.size == 1) {
                "$markerStart${content}$markerEnd"
            } else {
                // 중복 태그 처리: 마지막 시작 태그만 유효
                val prefix = parts.dropLast(1).joinToString("")
                val boldContent = parts.last()
                "${prefix}$markerStart${boldContent}$markerEnd"
            }
        }

        val lines = markedText.split(Regex("\\r?\\n|\\r"))
        var isBold = false
        
        val parsedLines = lines.mapIndexed { index, line ->
            var l = line
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("&lt;br&gt;", "<br/>", ignoreCase = true)
                .replace("&lt;br/&gt;", "<br/>", ignoreCase = true)

            // [중요] 보호했던 이미지 태그 복구
            if (l.contains("%%%IMG_MARKER_")) {
                imgTagMap.forEach { (marker, tag) ->
                    l = l.replace(marker, tag)
                }
            }

            // Ruby logic
            fun getRubyHtml(base: String, ruby: String): String {
                val needCompression = when {
                    base.length == 1 && ruby.length >= 3 -> true
                    base.length == 2 && ruby.length >= 5 -> true
                    base.length == 3 && ruby.length >= 7 -> true
                    else -> false
                }
                return if (needCompression) {
                    "<ruby>$base<rt class=\"ruby-wide\"><span>$ruby</span></rt></ruby>"
                } else {
                    "<ruby>$base<rt>$ruby</rt></ruby>"
                }
            }
            l = l.replace(Regex("[｜|](.+?)《(.+?)》")) { m -> getRubyHtml(m.groupValues[1], m.groupValues[2]) }
            l = l.replace(Regex("[｜|](.+?)[(（](.+?)[)）]")) { m -> getRubyHtml(m.groupValues[1], m.groupValues[2]) }
            l = l.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)《(.+?)》")) { m -> getRubyHtml(m.groupValues[1], m.groupValues[2]) }
            l = l.replace(Regex("([\\u4E00-\\u9FFF\\u3400-\\u4DBF]+)[(（]([\\u3040-\\u309F\\u30A0-\\u30FF]+)[)）]")) { m -> getRubyHtml(m.groupValues[1], m.groupValues[2]) }

            // [수정] Aozora 스타일 이미지 태그 경로 수정
            fun makeImgTag(fileName: String): String {
                val fullPath = if (fileName.startsWith("http") || fileName.startsWith("file://")) {
                    fileName
                } else {
                    val rootPath = imageRootPath.removePrefix("file://")
                    val imgFile = java.io.File(rootPath, fileName)
                    "file://${imgFile.absolutePath}"
                }
                
                val encodedPath = encodeFileName(fullPath)
                return "<img src=\"$encodedPath\" alt=\"$fileName\" loading=\"lazy\" onerror=\"this.style.display='none';\" />"
            }

            l = l.replace(Regex("［＃挿絵（(.+?)）入る］")) { m -> makeImgTag(m.groupValues[1]) }
            l = l.replace(Regex("［＃.+?（(.+?)）］")) { m -> makeImgTag(m.groupValues[1]) }
            l = l.replace(Regex("(?i)［＃(.+?\\.(?:jpg|jpeg|png|gif|webp|avif))］")) { m -> makeImgTag(m.groupValues[1]) }

            // Headers
            l = l.replace(Regex("［＃大見出し］(.+?)［＃大見出し終わり］"), "<h1 class=\"aozora-title\">$1</h1>")
            l = l.replace(Regex("［＃中見出し］(.+?)［＃中見出し終わり］"), "<h2 class=\"aozora-title\">$1</h2>")
            l = l.replace(Regex("［＃小見出し］(.+?)［＃小見出し終わり］"), "<h3 class=\"aozora-title\">$1</h3>")
            
            // Markdown style bold support
            l = l.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            l = l.replace(Regex("__(.*?)__"), "<b>$1</b>")
            
            // [수정 2] 변경된 마커(%%%)를 감지하여 실제 태그로 변환
            val wasBoldAtStart = isBold
            if (l.contains(markerStart)) {
                isBold = true
                l = l.replace(markerStart, "<b>")
            }
            if (l.contains(markerEnd)) {
                isBold = false
                l = l.replace(markerEnd, "</b>")
            }
            
            var lineContent = l
            // Handle cross-line bold state
            if (wasBoldAtStart && !lineContent.startsWith("<b>") && !lineContent.contains("<b>")) {
                 lineContent = "<b>$lineContent"
            }
            if (isBold && !lineContent.endsWith("</b>") && !lineContent.contains("</b>")) {
                 lineContent = "$lineContent</b>"
            }

            // Page Break & Indent
            lineContent = lineContent.replace(Regex("［＃改페이지］"), "<div style=\"break-after: page; height: 100vh; width: 1px;\"></div>")
            lineContent = lineContent.replace(Regex("［＃改頁］"), "<div style=\"break-after: page; height: 100vh; width: 1px;\"></div>")
            lineContent = lineContent.replace(Regex("［＃ここから(\\d+)字下げ］"), "<div style=\"margin-inline-start: $1em;\">")
            lineContent = lineContent.replace("［＃ここで字下げ終わり］", "</div>")

            var classes = mutableListOf<String>()
            var styles = mutableListOf<String>()
            
            if (lineContent.contains("［＃센터］")) {
                lineContent = lineContent.replace("［＃센터］", "").replace("［＃센터終わり］", "")
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

    fun extractTitles(text: String, lineOffset: Int = 0): List<Pair<String, Int>> {
        val titles = mutableListOf<Pair<String, Int>>()
        val lines = text.split(Regex("\\r?\\n|\\r"))
        val pattern = Regex("［＃[大中小]見出し］(.+?)［＃[大中小]見出し終わり］")
        lines.forEachIndexed { index, line ->
            pattern.find(line)?.let {
                titles.add(it.groupValues[1] to (index + lineOffset + 1))
            }
        }
        return titles
    }

    fun wrapInHtml(bodyContent: String, isVertical: Boolean = false, font: String = "serif", fontSize: Int = 16, backgroundColor: String = "#ffffff", textColor: String = "#000000", sideMargin: Int = 8): String {
        val writingMode = "horizontal-tb"
        val fontFamily = when(font) {
            "serif" -> "'Sawarabi Mincho', serif"
            "sans-serif" -> "'Sawarabi Gothic', sans-serif"
            else -> "serif"
        }
        
        val marginEm = sideMargin / 20.0
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
                <script>
                    function jumpToBottom() {
                        setTimeout(function() {
                            var spacer = document.getElementById('end-marker');
                            if (spacer) {
                                spacer.scrollIntoView(false);
                            } else {
                                window.scrollTo(0, document.body.scrollHeight);
                            }
                        }, 50); 
                    }
                </script>
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
                        padding-bottom: 100vh;
                        line-height: 1.8;
                        overflow-x: ${if (isVertical) "auto" else "hidden"};
                        overflow-y: ${if (isVertical) "hidden" else "visible"};
                        height: ${if (isVertical) "100vh" else "auto"};
                        min-height: 100vh;
                        width: ${if (isVertical) "auto" else "100%"};
                    }
                    p, div, h1, h2, h3 {
                        padding: 0 ${marginEm}em;
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
                        object-fit: contain; /* 비율 유지 */
                    }
                    /* 이미지가 로드되지 않았을 때의 스타일 보완 */
                    img[style*="display: none"] {
                        margin: 0 !important;
                        height: 0 !important;
                    }

                    h1.aozora-title { font-size: 1.3em; }
                    h2.aozora-title { font-size: 1.15em; }
                    h3.aozora-title { font-size: 1.05em; }
                    
                    h1, h2, h3 {
                        text-align: center;
                        margin: 1.5em 0;
                    }
                    rt {
                        font-size: 0.5em;
                        text-align: center;
                    }

                    rt.ruby-wide {
                        margin-left: -0.3em;
                        margin-right: -0.3em;
                    }

                    rt.ruby-wide span {
                        display: inline-block;
                        transform: scaleX(0.75);
                        transform-origin: center bottom;
                        white-space: nowrap;
                    }
                    /* Table styling */
                    table {
                        width: 100%;
                        table-layout: fixed;
                        border-collapse: collapse;
                        margin: 1em 0;
                    }
                    th, td {
                        border: 1px solid #888;
                        padding: 8px;
                        white-space: normal;
                        word-wrap: break-word;
                        overflow-wrap: break-word;
                        vertical-align: top;
                    }
                    .table-container {
                        padding: 0 !important;
                        margin: 1em 0;
                    }
                    /* Support for Vertical text centering and padding */
                    body.vertical {
                        padding: 2em 1.5em;
                        padding-left: 50vh;
                    }
                </style>
            </head>
            <body class="${if (isVertical) "vertical" else ""}">
                $bodyContent
                <div style="height: 50vh; width: 100%; clear: both;"></div>
                <div id="end-marker" style="height: 1px; width: 100%; clear: both;"></div>
            </body>
            </html>
        """.trimIndent()
    }
}
