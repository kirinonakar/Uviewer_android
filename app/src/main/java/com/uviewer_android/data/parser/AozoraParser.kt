package com.uviewer_android.data.parser

import java.util.regex.Pattern
import kotlin.text.RegexOption
import kotlin.text.MatchResult
import java.net.URLEncoder

object AozoraParser {
    
    // Helper to encode filename segments while preserving path structure and protocols
    private fun encodeFileName(path: String): String {
        return try {
            if (path.startsWith("http")) {
                val protocol = if (path.startsWith("https")) "https://" else "http://"
                val rest = path.removePrefix(protocol)
                return protocol + rest.split("/").joinToString("/") { segment ->
                    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                }
            }
            path.split("/").joinToString("/") { segment ->
                // Skip protocol parts (e.g., "file:")
                if (segment.endsWith(":") && segment.length <= 6) segment 
                else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        } catch (e: Exception) {
            path
        }
    }
    
    // Convert Aozora ruby to HTML <ruby>
    
    fun parse(text: String, lineOffset: Int = 0, imageRootPath: String = "", isVertical: Boolean = false): String {
        // 1. 텍스트 내의 <img ...> 태그를 찾아서 임시 마커로 변경 (보호 처리)
        //    이렇게 안 하면 replace("<", "&lt;") 때 태그가 다 깨짐
        val imgTagMap = mutableMapOf<String, String>()
        var protectedText = text.replace(Regex("<img[^>]*src=\"([^\"]*)\"[^>]*>")) { match ->
            val originalTag = match.value
            val src = match.groupValues[1]
            val marker = "%%%IMG_MARKER_${imgTagMap.size}%%%"
            
            // 이미지가 http나 file로 시작하지 않고 imageRootPath가 있으면 경로 추가
            val newSrc = if (src.startsWith("http") || src.startsWith("file://") || imageRootPath.isEmpty()) {
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

        // [수정 1] 볼드체 처리 로직 강화
        // 마커를 마크다운 문법(__)과 겹치지 않는 특수 문자로 변경 (%%% 사용)
        val markerStart = "%%%BOLD_START%%%"
        val markerEnd = "%%%BOLD_END%%%"

        // Aozora 및 Narou 스타일 태그 패턴 정의
        // 여러번 시작 태그가 나오면 마지막 것만 유효하게 처리하는 로직은 아래 markedText 블록에 있음
        val boldStartPattern = "［＃(?:여기서 태그 시작|ここから太字)］"
        val boldEndPattern = "［＃(?:여기서 태그 끝|ここで太字終わり|太字終わり)］"
        
        // DOT_MATCHES_ALL을 사용하여 줄바꿈이 있어도 태그 사이를 매칭
        val boldRegex = Regex("$boldStartPattern(.*?)$boldEndPattern", RegexOption.DOT_MATCHES_ALL)

        val markedText = boldRegex.replace(protectedText) { match: MatchResult ->
            val content = match.groupValues[1]
            val startRegex = Regex(boldStartPattern)
            
            // 내용 안에 또 다른 시작 태그가 있는지 확인
            val parts = content.split(startRegex)
            
            if (parts.size == 1) {
                // 시작 태그가 하나뿐인 정상적인 경우
                "$markerStart${content}$markerEnd"
            } else {
                // 중복 태그 처리: 내용 중에 시작 태그가 또 있다면, 마지막 시작 태그 이전의 내용은 볼드 처리 안 함
                // 예: A [Start] B [Start] C [End] -> A B <b>C</b>
                val prefix = parts.dropLast(1).joinToString("") // 앞부분 (볼드 아님)
                val boldContent = parts.last() // 마지막 태그 뒷부분 (볼드 적용)
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

            // [수정 2] 방점 (Bouten) 처리 - 로직 완전 변경
            // 문제 해결: 복잡한 역참조 정규식 대신, 태그를 먼저 찾고 해당 단어를 타겟팅하여 치환
            // 1. 방점 태그 패턴을 모두 찾음: ［＃「단어」에傍点］
            val boutenRegex = Regex("［＃「(.+?)」に傍点］")
            val boutenMatches = boutenRegex.findAll(l).toList() // 리스트로 변환하여 고정

            // 2. 발견된 태그들에 대해 순차적으로 치환 수행
            boutenMatches.forEach { match ->
                val targetWord = match.groupValues[1] // 태그 안의 단어 (예: ズレ)
                val fullTag = match.value             // 태그 전체 (예: ［＃「ズレ」に傍点］)
                
                // 정규식 특수문자가 단어에 포함될 경우를 대비해 quote 처리
                val safeWord = Pattern.quote(targetWord)
                val safeTag = Pattern.quote(fullTag)

                // "단어" 바로 뒤에 "태그"가 붙어있는 패턴을 찾음 (예: ズレ［＃「ズレ」에傍点］)
                val targetPattern = Regex("$safeWord$safeTag")

                l = l.replace(targetPattern) {
                    // 글자마다 루비 점 찍기 (단어를 글자 단위로 분리)
                    targetWord.map { char ->
                        "<ruby class=\"bouten\">$char<rt>﹅</rt></ruby>"
                    }.joinToString("")
                }
            }

            // 縦中横 (tate-chu-yoko) 처리: ［＃「XX」は縦中横］
            val tcyRegex = Regex("［＃「(.+?)」は縦中横］")
            val tcyMatches = tcyRegex.findAll(l).toList()
            tcyMatches.forEach { match ->
                val targetWord = match.groupValues[1]
                val fullTag = match.value
                val safeWord = Pattern.quote(targetWord)
                val safeTag = Pattern.quote(fullTag)
                val targetPattern = Regex("$safeWord$safeTag")
                l = l.replace(targetPattern) {
                    if (isVertical) {
                        "<span class=\"tcy\">$targetWord</span>"
                    } else {
                        targetWord
                    }
                }
            }

            // [추가] 특정 단어 볼드 처리: ［＃「단어」은/는/は太字］
            val boldSpecificRegex = Regex("［＃「(.+?)」[は는은]太字］")
            val boldSpecificMatches = boldSpecificRegex.findAll(l).toList()
            boldSpecificMatches.forEach { match ->
                val targetWord = match.groupValues[1]
                val fullTag = match.value
                val safeWord = Pattern.quote(targetWord)
                val safeTag = Pattern.quote(fullTag)
                val targetPattern = Regex("$safeWord$safeTag")
                l = l.replace(targetPattern) { "<b>$targetWord</b>" }
            }

            // Ruby logic (일반 루비)
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

            // Aozora 스타일 이미지 태그 경로 수정
            fun makeImgTag(fileName: String): String {
                val fullPath = if (fileName.startsWith("http") || fileName.startsWith("file://") || imageRootPath.isEmpty()) {
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
            
            // 변경된 마커(%%%)를 감지하여 실제 태그로 변환
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
            // [수정 3] "改ページ" (가타카나) 추가 지원
            lineContent = lineContent.replace(Regex("［＃(?:改ページ|改페이지|改頁)］"), "<div style=\"break-after: page; height: 100vh; width: 1px;\"></div>")
            
            lineContent = lineContent.replace(Regex("［＃ここから(\\d+)字下げ］"), "<div style=\"margin-inline-start: $1em;\">")
            lineContent = lineContent.replace("［＃ここで字下げ終わり］", "</div>")
            
            lineContent = lineContent.replace("［＃ここから罫囲み］", "<div class=\"keigakomi\">")
            lineContent = lineContent.replace("［＃ここで罫囲み終わり］", "</div>")
            
            lineContent = lineContent.replace("［＃ここから２段階小さな文字］", "<span class=\"small-text-2\">")
            lineContent = lineContent.replace("［＃ここで小さな文字終わり］", "</span>")

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
        val writingMode = if (isVertical) "vertical-rl" else "horizontal-tb"
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

                    function fixRubySpacing() {
                    var isVertical = ${if (isVertical) "true" else "false"};
                    if (isVertical) {
                        var rubies = Array.from(document.querySelectorAll('ruby'));
                        for (var i = 0; i < rubies.length; i++) {
                            var ruby = rubies[i];
                            if (!ruby.parentNode) continue;
                            var baseText = Array.from(ruby.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('').normalize('NFC').trim();
                            var rubyText = Array.from(ruby.querySelectorAll('rt')).map(function(r) { return r.textContent; }).join('').normalize('NFC').trim();
                            if (rubyText.length === 0) continue;

                            // 구조 정규화 (순서 꼬임 방지)
                            while (ruby.firstChild) ruby.removeChild(ruby.firstChild);
                            ruby.appendChild(document.createTextNode(baseText));
                            var rt = document.createElement('rt');
                            rt.textContent = rubyText;
                            ruby.appendChild(rt);

                            var baseLen = baseText.length;
                            var rubyLen = rubyText.length;
                            var needsMerge = (baseLen === 1 && rubyLen >= 3) || 
                                             (baseLen === 2 && rubyLen >= 5) || 
                                             (baseLen === 3 && rubyLen >= 7) ||
                                             (baseLen >= 4 && rubyLen >= baseLen * 1.5);

                            if (needsMerge) {
                                // 1. 앞쪽 인접 루비 병합
                                while (ruby.previousSibling && ruby.previousSibling.tagName === 'RUBY') {
                                    var prev = ruby.previousSibling;
                                    var pRts = Array.from(prev.querySelectorAll('rt'));
                                    var pRubyText = pRts.map(function(r) { return r.textContent; }).join('');
                                    var pBase = Array.from(prev.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                    ruby.insertBefore(document.createTextNode(pBase), ruby.firstChild);
                                    rt.textContent = pRubyText + rt.textContent;
                                    prev.parentNode.removeChild(prev);
                                }
                                // 2. 뒤쪽 인접 루비 병합
                                while (ruby.nextSibling && ruby.nextSibling.tagName === 'RUBY') {
                                    var next = ruby.nextSibling;
                                    var nRts = Array.from(next.querySelectorAll('rt'));
                                    var nRubyText = nRts.map(function(r) { return r.textContent; }).join('');
                                    var nBase = Array.from(next.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                    ruby.insertBefore(document.createTextNode(nBase), rt);
                                    rt.textContent = rt.textContent + nRubyText;
                                    next.parentNode.removeChild(next);
                                }
                                // 3. 앞쪽 일반 텍스트 1자 흡수 (중앙 정렬)
                                var finalPrev = ruby.previousSibling;
                                if (finalPrev && finalPrev.nodeType === 3) {
                                    var txt = finalPrev.textContent;
                                    if (txt.length > 0) {
                                        ruby.insertBefore(document.createTextNode(txt[txt.length - 1]), ruby.firstChild);
                                        finalPrev.textContent = txt.substring(0, txt.length - 1);
                                    }
                                }
                                // 4. 뒤쪽 일반 텍스트 한 자 흡수
                                var finalNext = ruby.nextSibling;
                                if (finalNext && finalNext.nodeType === 3) {
                                    var txt = finalNext.textContent;
                                    if (txt.length > 0) {
                                        ruby.insertBefore(document.createTextNode(txt[0]), rt);
                                        finalNext.textContent = txt.substring(1);
                                    }
                                }
                            }
                        }
                    } else {
                        document.querySelectorAll('rt').forEach(function(el) {
                            var rubyText = el.textContent.trim();
                            var baseNode = el.previousSibling || el.parentElement.firstChild;
                            var baseText = baseNode ? baseNode.textContent.trim() : "";
                            if (baseText.length === 1 && rubyText.length >= 3) {
                                el.classList.add('ruby-wide');
                                el.innerHTML = '<span>' + rubyText + '</span>';
                            }
                        });
                    }
                }
                window.addEventListener('DOMContentLoaded', fixRubySpacing);
                </script>
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Sawarabi+Mincho&family=Sawarabi+Gothic&display=swap');
                    
                    html {
                        width: 100vw !important;
                        height: 100vh !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        overflow-x: ${if (isVertical) "scroll" else "hidden"} !important;
                        overflow-y: ${if (isVertical) "hidden" else "scroll"} !important;
                        overscroll-behavior: none !important;
                        touch-action: ${if (isVertical) "pan-x" else "pan-y"} !important;
                        writing-mode: $writingMode !important;
                        -webkit-writing-mode: $writingMode !important;
                    }
                    body {
                        font-family: $fontFamily;
                        font-size: ${fontSize}px;
                        background-color: $backgroundColor;
                        color: $textColor;
                        writing-mode: $writingMode;
                        -webkit-writing-mode: $writingMode;
                        text-orientation: mixed;
                        margin: 0;
                        padding: 0 !important;
                        line-height: 1.8;
                        overflow: visible !important;
                        height: 100vh !important;
                        min-height: 100vh !important;
                        width: ${if (isVertical) "auto" else "100%"};
                    }
                    p, div, h1, h2, h3, h4, h5, h6 {
                        display: block !important;
                        height: auto !important;
                        width: auto !important;
                        margin-top: 0 !important;
                        margin-bottom: ${if (isVertical) "0" else "0.5em"} !important;
                        margin-left: ${if (isVertical) "1em" else "0"} !important;
                        padding-left: ${if (isVertical) "0" else "${marginEm}em"} !important;
                        padding-right: ${if (isVertical) "0" else "${marginEm}em"} !important;
                        padding-top: ${if (isVertical) "${marginEm}em" else "0"} !important;
                        padding-bottom: ${if (isVertical) "${marginEm}em" else "0"} !important;
                        box-sizing: border-box !important;
                    }

                    div:has(img), p:has(img) {
                        padding: 0 !important;
                    }
                    .center {
                        text-align: center;
                    }
                    .keigakomi {
                        border: 1px solid currentColor;
                        padding: 0.5em;
                        margin: 1em 0;
                    }
                    .small-text-2 {
                        font-size: 0.7em !important;
                    }
                    .tcy {
                        text-combine-upright: all;
                        -webkit-text-combine: horizontal;
                    }
                    img {
                        max-width: 100% !important;
                        max-height: 100% !important;
                        width: auto !important;
                        height: auto !important;
                        display: block;
                        margin: 1em auto;
                        vertical-align: middle;
                        object-fit: contain;
                    }
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
                    
                    /* [수정 2-1] 방점 스타일: 행간 벌어짐 최소화 및 글자별 배치 */
                    ruby.bouten {
                        ruby-align: center; /* 점이 글자 중앙에 오도록 */
                    }
                    ruby.bouten rt {
                        font-size: 0.4em; /* 점 크기 */
                        line-height: 0;   /* rt가 행간에 영향을 주지 않도록 0으로 설정 */
                        opacity: 0.8;
                        transform: translateY(0.2em); /* 점을 글자 쪽으로 살짝 붙임 (필요시 조정) */
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
                </style>
            </head>
            <body class="${if (isVertical) "vertical" else ""}">
                $bodyContent
                ${if (isVertical) 
                    """<div id="end-marker" style="display:inline-block; width:1px; height:100vh;"></div>"""
                  else 
                    """<div style="height: 50vh; width: 100%; clear: both;"></div><div id="end-marker" style="height: 1px; width: 100%; clear: both;"></div>"""}
            </body>
            </html>
        """.trimIndent()
    }
}
