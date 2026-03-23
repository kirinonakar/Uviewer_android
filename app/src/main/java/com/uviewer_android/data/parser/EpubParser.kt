package com.uviewer_android.data.parser

import com.uviewer_android.data.model.EpubBook
import com.uviewer_android.data.model.EpubSpineItem
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object EpubParser {

    @Throws(IOException::class)
    fun unzip(zipFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val file = File(targetDir, entry!!.name)
                // Vulnerability check: Zip Path Traversal
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw IOException("Zip Path Traversal Attempt: ${entry!!.name}")
                }
                
                if (entry!!.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        val buffer = ByteArray(8192) // Increased buffer for many images
                        var count: Int
                        while (zis.read(buffer).also { count = it } != -1) {
                            fos.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        // Create a success signal for atomicity
        File(targetDir, ".unzip_success").createNewFile()
    }

    fun parse(epubRoot: File): EpubBook {
        // 1. Find Open Packaging Format (OPF) file via META-INF/container.xml
        val containerFile = File(epubRoot, "META-INF/container.xml")
        if (!containerFile.exists()) throw IOException("Invalid EPUB: META-INF/container.xml not found")
        
        val containerDoc = Jsoup.parse(containerFile.inputStream(), "UTF-8", containerFile.absolutePath, Parser.xmlParser())
        val opfPathRaw = containerDoc.select("rootfile").attr("full-path")
        if (opfPathRaw.isEmpty()) throw IOException("Invalid EPUB: OPF path not found in container.xml")
        
        val opfFile = resolveFile(epubRoot, opfPathRaw)
        val opfDir = opfFile.parentFile ?: epubRoot
        
        val opfDoc = if (opfFile.exists()) {
            Jsoup.parse(opfFile.inputStream(), "UTF-8", opfFile.absolutePath, Parser.xmlParser())
        } else {
            throw IOException("Invalid EPUB: OPF file not found at ${opfFile.absolutePath}")
        }
        
        // Metadata
        val title = opfDoc.select("metadata > dc|title").text() // Namespace handling tricky in Jsoup select without setup?
        // Jsoup select supports namespaces with `|` if configured or just by tag name if unique.
        // Actually Jsoup XML parser preserves namespaces but selector syntax is standard CSS.
        // `dc\:title` or just `title` might work depending.
        // Let's try `dc:title` first, or simple `title` if unique.
        // Often `dc:title` works. Or strictly select by tag name.
        val titleText = opfDoc.getElementsByTag("dc:title").first()?.text() 
            ?: opfDoc.getElementsByTag("title").first()?.text() ?: "Unknown Title"
            
        val authorText = opfDoc.getElementsByTag("dc:creator").first()?.text()

        // Manifest (id -> href)
        val manifest = mutableMapOf<String, String>()
        val manifestItems = opfDoc.getElementsByTag("item")
        for (item in manifestItems) {
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                manifest[id] = href
            }
        }

        // Spine (ordered list of ids)
        val spine = mutableListOf<EpubSpineItem>()
        val spineItems = opfDoc.getElementsByTag("itemref")
        for (itemref in spineItems) {
            val idref = itemref.attr("idref")
            val relativeHref = manifest[idref]
            if (relativeHref != null) {
                val file = resolveFile(opfDir, relativeHref)
                spine.add(EpubSpineItem(href = file.absolutePath, id = idref))
            }
        }

        // TOC Parsing (NCX)
        val spineTag = opfDoc.getElementsByTag("spine").first()
        val ncxId = spineTag?.attr("toc") ?: ""
        val pageDirection = spineTag?.attr("page-progression-direction") ?: "ltr"
        
        val ncxHref = manifest[ncxId]
        val tocMap = mutableMapOf<String, String>() // href -> title
        if (ncxHref != null) {
            val ncxFile = resolveFile(opfDir, ncxHref)
            if (ncxFile.exists()) {
                val ncxDoc = Jsoup.parse(ncxFile.inputStream(), "UTF-8", ncxFile.absolutePath, Parser.xmlParser())
                val navPoints = ncxDoc.getElementsByTag("navPoint")
                for (point in navPoints) {
                    val label = point.select("navLabel > text").first()?.text()
                    val src = point.select("content").attr("src")
                    if (src.isNotEmpty()) {
                        val cleanSrc = try { java.net.URLDecoder.decode(src, "UTF-8") } catch (e: Exception) { src }.substringBefore("#")
                        if (label != null) {
                            tocMap[cleanSrc] = label
                        }
                    }
                }
            }
        }

        // Apply titles to spine items
        val finalSpine = spine.mapIndexed { idx, item ->
            // Try to match href (absolute) back to manifest relative href for TOC lookup
            val relativeHref = File(item.href).relativeTo(opfDir).path.replace("\\", "/")
            val title = tocMap[relativeHref] ?: item.title ?: "Chapter ${idx + 1}"
            item.copy(title = title)
        }

        return EpubBook(
            title = titleText,
            author = authorText,
            coverPath = null, // TODO: Parse cover logic
            spine = finalSpine,
            rootDir = epubRoot.absolutePath,
            pageProgressionDirection = pageDirection
        )
    }

    private fun resolveFile(baseDir: File, relativePath: String): File {
        // 1. Decode URL encoding (e.g. %20 -> space)
        val decodedPath = try { java.net.URLDecoder.decode(relativePath, "UTF-8") } catch (e: Exception) { relativePath }
        
        // 2. Remove leading slashes and fix separators
        val cleanPath = decodedPath.trimStart('/', '\\').replace('\\', File.separatorChar).replace('/', File.separatorChar)
        
        // 3. Try exact match
        val file = File(baseDir, cleanPath)
        if (file.exists()) return file
        
        // 4. Try case-insensitive matching
        return findFileCaseInsensitive(baseDir, cleanPath) ?: file
    }

    private fun findFileCaseInsensitive(parent: File, path: String): File? {
        val parts = path.split(File.separatorChar).filter { it.isNotEmpty() }
        var current = parent
        for (part in parts) {
            val children = current.listFiles() ?: return null
            val match = children.find { it.name.equals(part, ignoreCase = true) }
            if (match == null) return null
            current = match
        }
        return current
    }

    /**
     * EPUB의 모든 챕터를 Aozora처럼 단순한 텍스트 라인으로 변환합니다.
     * 복잡한 HTML 레이아웃을 무시하고 텍스트, 이미지, 루비만 유지합니다.
     * @return Triple(전체 텍스트, 챕터별 시작라인 맵, 챕터별 라인수 맵)
     */
    fun extractFlatContent(
        spine: List<EpubSpineItem>,
        isVertical: Boolean = false
    ): Triple<List<String>, Map<Int, Int>, Map<Int, Int>> {
        val allLines = mutableListOf<String>()
        val chapterStartLines = mutableMapOf<Int, Int>() // chapterIndex -> startLine (1-based)
        val chapterLineCounts = mutableMapOf<Int, Int>() // chapterIndex -> lineCount

        for ((chapterIndex, chapter) in spine.withIndex()) {
            val cleanHref = chapter.href.substringBefore("#")
            val chapterFile = File(cleanHref)
            if (!chapterFile.exists()) continue

            try {
                val rawHtml = chapterFile.readText()
                // Use XML parser for XHTML to preserve SVG <image> tags (HTML parser turns them into <img>)
                val doc = Jsoup.parse(rawHtml, "", Parser.xmlParser())
                val baseDir = chapterFile.parentFile

                // 이미지 및 SVG 경로 전환
                if (baseDir != null) {
                    // img 태그
                    doc.select("img").forEach { img ->
                        var src = img.attr("src")
                        if (src.isNotEmpty() && !src.startsWith("http") && !src.startsWith("file://") && !src.startsWith("data:")) {
                            // Strip query/fragment
                            val cleanSrc = src.substringBefore("?").substringBefore("#")
                            val imgFile = if (baseDir != null) resolveFile(baseDir, cleanSrc) else File(cleanSrc)
                            
                            val encodedPath = imgFile.absolutePath.split(File.separatorChar).joinToString("/") { segment ->
                                if (segment.isEmpty()) "" 
                                else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                            }
                            img.attr("src", "file:///$encodedPath")
                        }
                        // Remove hindering attributes
                        img.removeAttr("loading")
                        img.removeAttr("onerror")
                        img.removeAttr("style")
                    }
                    // SVG image 태그 (xlink:href 및 href 공용)
                    doc.select("image").forEach { img ->
                        var src = img.attr("xlink:href").ifEmpty { img.attr("href") }
                        if (src.isNotEmpty() && !src.startsWith("http") && !src.startsWith("file://") && !src.startsWith("data:")) {
                            val cleanSrc = src.substringBefore("?").substringBefore("#")
                            val imgFile = if (baseDir != null) resolveFile(baseDir, cleanSrc) else File(cleanSrc)
                            val encodedPath = imgFile.absolutePath.split(File.separatorChar).joinToString("/") { segment ->
                                if (segment.isEmpty()) ""
                                else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                            }
                            val absSrc = "file:///$encodedPath"
                            if (img.hasAttr("xlink:href")) img.attr("xlink:href", absSrc)
                            if (img.hasAttr("href")) img.attr("href", absSrc)
                        }
                        img.removeAttr("style")
                    }
                }

                // Fixed-layout EPUB(만화)의 경우 body 태그가 없거나 Jsoup XML 파서에서 인식되지 않을 수 있음
                val body = doc.getElementsByTag("body").first() ?: doc.getElementsByTag("html").first() ?: doc
                val chapterLines = mutableListOf<String>()

                // body를 순회하면서 텍스트, 이미지, 루비만 추출
                flattenNode(body, chapterLines, isVertical)

                // 챕터 사이에 빈 줄 추가 (시각적 구분) - 시작 라인 기록 전에 추가
                if (allLines.isNotEmpty() && chapterLines.isNotEmpty()) {
                    allLines.add("") // 빈 줄로 챕터 구분
                }

                // 빈 줄 추가 후 시작 라인 기록 (정확한 위치)
                chapterStartLines[chapterIndex] = allLines.size + 1

                // 빈 줄 연속 제거 (앞뒤 트림)
                val trimmedLines = chapterLines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                chapterLineCounts[chapterIndex] = trimmedLines.size
                allLines.addAll(trimmedLines)
            } catch (e: Exception) {
                chapterStartLines[chapterIndex] = allLines.size + 1
                allLines.add("Error reading chapter: ${e.message}")
                chapterLineCounts[chapterIndex] = 1
            }
        }

        return Triple(allLines, chapterStartLines, chapterLineCounts)
    }

    /**
     * HTML 노드를 재귀적으로 순회하며 텍스트, 이미지, 루비를 플랫 라인으로 추출합니다.
     */
    private fun flattenNode(
        node: org.jsoup.nodes.Element,
        lines: MutableList<String>,
        isVertical: Boolean
    ) {
        for (child in node.childNodes()) {
            when (child) {
                is org.jsoup.nodes.TextNode -> {
                    val text = child.wholeText.trim()
                    if (text.isNotEmpty()) {
                        val processedText = if (isVertical) {
                            val puncRegex = Regex("([!\\?！？]+)")
                            text.replace(puncRegex) { match ->
                                "<span class=\"tcy\">${match.value}</span>"
                            }
                        } else text
                        
                        // 현재 줄에 텍스트 추가 (마지막 줄이 비어있지 않으면 이어 붙이기)
                        if (lines.isNotEmpty() && lines.last().isNotEmpty() && !isBlockBoundary(child)) {
                            lines[lines.size - 1] = lines.last() + processedText
                        } else if (lines.isEmpty() || lines.last().isNotEmpty()) {
                            lines.add(processedText)
                        } else {
                            lines[lines.size - 1] = processedText
                        }
                    }
                }
                is org.jsoup.nodes.Element -> {
                    val tag = child.tagName().lowercase()
                    when {
                        // 이미지: Aozora 스타일로 img 태그 보존. 앞뒤 줄바꿈으로 단독 라인 확보
                        tag == "img" || tag == "image" -> {
                            val src = if (tag == "img") child.attr("src") else child.attr("xlink:href").ifEmpty { child.attr("href") }
                            if (src.isNotEmpty()) {
                                if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                                    lines.add("")
                                }
                                val tagHtml = if (tag == "img") "<img src=\"$src\" alt=\"\" />" 
                                              else child.outerHtml() // image는 SVG context가 아닐 수 있으므로 전체 보존
                                lines.add(tagHtml)
                                lines.add("")
                            }
                        }
                        // SVG: 전체 보존 (커버 이미지 등)
                        tag == "svg" -> {
                            if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                                lines.add("")
                            }
                            // SVG 내부의 image 태그들도 이미 위에서 절대경로로 바뀌었으므로 그대로 사용
                            lines.add(child.outerHtml())
                            lines.add("")
                        }
                        // 루비: HTML 태그 보존
                        tag == "ruby" -> {
                            val rubyHtml = extractRubyHtml(child)
                            if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                                lines[lines.size - 1] = lines.last() + rubyHtml
                            } else if (lines.isEmpty() || lines.last().isNotEmpty()) {
                                lines.add(rubyHtml)
                            } else {
                                lines[lines.size - 1] = rubyHtml
                            }
                        }
                        // 블록 요소: 새 줄 시작
                        tag in setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
                            "li", "blockquote", "pre", "article", "section", "br", "hr",
                            "tr", "figure", "figcaption", "dt", "dd") -> {
                            if (tag == "br") {
                                lines.add("")
                            } else if (tag == "hr") {
                                lines.add("") // hr을 빈 줄로
                            } else {
                                // 현재 줄이 비어있지 않으면 새 줄 시작
                                if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                                    lines.add("")
                                }
                                flattenNode(child, lines, isVertical)
                                // 블록 끝나면 줄바꿈
                                if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                                    lines.add("")
                                }
                            }
                        }
                        // 인라인 요소: 재귀적으로 내용 추출
                        tag in setOf("span", "em", "strong", "b", "i", "u", "a",
                            "small", "sub", "sup", "abbr", "cite", "code", "mark") -> {
                            // strong/b는 볼드 태그 보존
                            if (tag == "strong" || tag == "b") {
                                val innerText = extractInlineText(child, isVertical)
                                val boldHtml = "<b>$innerText</b>"
                                if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                                    lines[lines.size - 1] = lines.last() + boldHtml
                                } else if (lines.isEmpty()) {
                                    lines.add(boldHtml)
                                } else {
                                    lines[lines.size - 1] = boldHtml
                                }
                            } else if (tag == "em" || tag == "i") {
                                val innerText = extractInlineText(child, isVertical)
                                val italicHtml = "<i>$innerText</i>"
                                if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                                    lines[lines.size - 1] = lines.last() + italicHtml
                                } else if (lines.isEmpty()) {
                                    lines.add(italicHtml)
                                } else {
                                    lines[lines.size - 1] = italicHtml
                                }
                            } else {
                                flattenNode(child, lines, isVertical)
                            }
                        }
                        // 스크립트, 스타일 무시
                        tag in setOf("script", "style", "noscript", "link", "meta") -> {
                            // 무시
                        }
                        // 기타: 재귀 처리
                        else -> {
                            flattenNode(child, lines, isVertical)
                        }
                    }
                }
            }
        }
    }

    /**
     * ruby 요소에서 HTML을 추출합니다.
     */
    private fun extractRubyHtml(ruby: org.jsoup.nodes.Element): String {
        val baseText = StringBuilder()
        val rtText = StringBuilder()
        
        for (child in ruby.childNodes()) {
            when {
                child is org.jsoup.nodes.TextNode -> baseText.append(child.wholeText)
                child is org.jsoup.nodes.Element && child.tagName().lowercase() == "rt" -> {
                    rtText.append(child.text())
                }
                child is org.jsoup.nodes.Element && child.tagName().lowercase() == "rp" -> {
                    // rp 무시
                }
                child is org.jsoup.nodes.Element && child.tagName().lowercase() == "rb" -> {
                    baseText.append(child.text())
                }
                child is org.jsoup.nodes.Element -> {
                    baseText.append(child.text())
                }
            }
        }

        return if (rtText.isNotEmpty()) {
            "<ruby>${baseText.toString().trim()}<rt>${rtText.toString().trim()}</rt></ruby>"
        } else {
            baseText.toString().trim()
        }
    }

    /**
     * 인라인 요소에서 텍스트와 루비를 추출합니다.
     */
    private fun extractInlineText(element: org.jsoup.nodes.Element, isVertical: Boolean): String {
        val sb = StringBuilder()
        for (child in element.childNodes()) {
            when {
                child is org.jsoup.nodes.TextNode -> {
                    val text = child.wholeText
                    if (isVertical) {
                        val puncRegex = Regex("([!\\?！？]+)")
                        sb.append(text.replace(puncRegex) { match ->
                            "<span class=\"tcy\">${match.value}</span>"
                        })
                    } else {
                        sb.append(text)
                    }
                }
                child is org.jsoup.nodes.Element && child.tagName().lowercase() == "ruby" -> {
                    sb.append(extractRubyHtml(child))
                }
                child is org.jsoup.nodes.Element && child.tagName().lowercase() == "img" -> {
                    // 인라인 이미지는 무시하거나 별도 처리
                }
                child is org.jsoup.nodes.Element -> {
                    sb.append(extractInlineText(child, isVertical))
                }
            }
        }
        return sb.toString()
    }

    /**
     * 텍스트 노드가 블록 경계에 있는지 확인합니다.
     */
    private fun isBlockBoundary(node: org.jsoup.nodes.Node): Boolean {
        val prev = node.previousSibling()
        if (prev is org.jsoup.nodes.Element) {
            val tag = prev.tagName().lowercase()
            return tag in setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
                "li", "blockquote", "pre", "br", "hr", "figure", "img", "image", "svg")
        }
        return false
    }

    fun prepareHtmlForViewer(html: String, resetCss: String, baseDir: File? = null, idPrefix: String = "", isVertical: Boolean = false): Pair<String, Int> {
        val doc = Jsoup.parse(html)
        
        // [추가] 이미지 경로를 절대 경로로 변경 및 한 페이지에 하나씩 표시되도록 래핑
        if (baseDir != null) {
            // [수정됨] 이미지 경로 처리 및 래핑 로직
            val images = doc.select("img")
            for (img in images) {
                val src = img.attr("src")
                if (src.isNotEmpty() && !src.startsWith("http") && !src.startsWith("file://")) {
                    val imgFile = resolveFile(baseDir, src)
                    val encodedPath = imgFile.absolutePath.split(File.separatorChar).joinToString("/") { segment ->
                        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                    }
                    img.attr("src", "file:///$encodedPath")
                }
                
                // [수정됨] 래퍼가 이미 있으면 중복 래핑 방지
                val parent = img.parent()
                if (parent != null && parent.className() == "image-page-wrapper") {
                    // Skip
                } else if (parent != null && (parent.tagName() == "p" || parent.tagName() == "div") && parent.text().isBlank() && parent.childrenSize() == 1) {
                    parent.tagName("div")
                    parent.addClass("image-page-wrapper")
                    parent.removeAttr("style")
                } else {
                    img.wrap("<div class=\"image-page-wrapper\"></div>")
                }
            }

            // SVG 자체와 Figure도 래핑
            val rootMedia = doc.select("body svg, body figure")
            for (media in rootMedia) {
                val parent = media.parent()
                if (parent != null && parent.className() != "image-page-wrapper") {
                    if ((parent.tagName() == "p" || parent.tagName() == "div") && parent.text().isBlank() && parent.childrenSize() == 1) {
                        parent.tagName("div")
                        parent.addClass("image-page-wrapper")
                        parent.removeAttr("style")
                    } else if (parent.tagName() == "body" || parent.tagName() == "article" || parent.tagName() == "section") {
                        media.wrap("<div class=\"image-page-wrapper\"></div>")
                    }
                }
            }

            // [핵심 추가] 래핑 후 남은 잉여 빈 p 태그들을 일괄 삭제 (레이아웃 밀림 완벽 차단)
            doc.select("p:empty").remove()
        }

        // --- [핵심 추가] 세로모드 문장부호 縦中横 (tcy) 자동 적용 ---
        if (isVertical) {
            // Jsoup은 본문 텍스트의 꺾쇠(<, >)를 자동으로 이스케이프(&lt;) 처리합니다.
            // 따라서 (?![^<]*>) 부정 전방탐색을 사용하면 HTML 태그 속성 내부를 안전하게 피해 본문만 치환할 수 있습니다.
            val puncRegex = Regex("([!\\?！？]+)(?![^<]*>)")
            var bodyHtml = doc.body().html()
            bodyHtml = bodyHtml.replace(puncRegex) { match ->
                "<span class=\"tcy\">${match.value}</span>"
            }
            doc.body().html(bodyHtml)
        }

        // 1. Inject styling and scroll script into head
        doc.head().append(resetCss)
        doc.head().append("""
            <style>
                ruby.bouten {
                    ruby-align: center;
                }
                ruby.bouten rt {
                    font-size: 0.4em;
                    line-height: 0;
                    opacity: 0.8;
                    transform: translateY(0.2em);
                }
                rt.ruby-wide {
                    margin-left: -0.5em;
                    margin-right: -0.5em;
                }
                rt.ruby-wide span {
                    display: inline-block;
                    transform: scaleX(0.6);
                    transform-origin: center bottom;
                    white-space: nowrap;
                }
                
                /* --- [핵심 추가] tcy(縦中横) 스타일 정의 --- */
                .tcy {
                    text-combine-upright: all;
                    -webkit-text-combine: horizontal;
                }
                
                /* --- [핵심 추가] 이미지 페이지 래퍼 스타일 --- */
                .image-page-wrapper {
                    display: flex !important;
                    flex-direction: column !important;
                    justify-content: center !important;
                    align-items: center !important;
                    width: 100vw !important;
                    min-width: 100vw !important;
                    max-width: 100vw !important;
                    height: 100vh !important;
                    min-height: 100vh !important;
                    max-height: 100vh !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    page-break-after: always !important;
                    break-after: page !important;
                    page-break-before: always !important;
                    break-before: page !important;
                    overflow: hidden !important;
                    flex-shrink: 0 !important;
                    clear: both !important;
                }
                .image-page-wrapper img {
                    margin: 0 !important;
                    max-width: 100% !important;
                    max-height: 100% !important;
                    object-fit: contain !important;
                }
                .image-page-wrapper svg {
                    width: 100vw !important;
                    height: 100vh !important;
                    max-width: 100vw !important;
                    max-height: 100vh !important;
                    object-fit: contain !important; /* Preserve aspect ratio within viewport */
                }
                .image-page-wrapper svg image {
                    width: 100% !important;
                    height: 100% !important;
                }
            </style>
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
                        // 1단계: 인접한 모든 루비를 무조건 하나로 병합 (조각난 루비 길이 오작동 방지)
                        var rubies = Array.from(document.querySelectorAll('ruby'));
                        for (var i = 0; i < rubies.length; i++) {
                            var ruby = rubies[i];
                            if (!ruby.parentNode) continue; // 이미 앞선 병합 과정에서 삭제된 노드 패스

                            var next = ruby.nextSibling;
                            while (next) {
                                // 의미 없는 빈 공백 텍스트 노드는 무시하고 제거
                                if (next.nodeType === 3 && next.textContent.trim() === '') {
                                    var toRemove = next;
                                    next = next.nextSibling;
                                    toRemove.parentNode.removeChild(toRemove);
                                    continue;
                                }
                                // 다음 노드가 루비라면 내용물 병합
                                if (next.nodeType === 1 && next.tagName === 'RUBY') {
                                    var base1 = Array.from(ruby.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                    var rt1 = Array.from(ruby.querySelectorAll('rt')).map(function(r) { return r.textContent; }).join('');
                                    
                                    var base2 = Array.from(next.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                    var rt2 = Array.from(next.querySelectorAll('rt')).map(function(r) { return r.textContent; }).join('');
                                    
                                    ruby.innerHTML = '';
                                    ruby.appendChild(document.createTextNode(base1 + base2));
                                    var newRt = document.createElement('rt');
                                    newRt.textContent = rt1 + rt2;
                                    ruby.appendChild(newRt);
                                    
                                    var toRemove = next;
                                    next = next.nextSibling;
                                    toRemove.parentNode.removeChild(toRemove);
                                } else {
                                    break; // 루비가 아니면 병합 중단
                                }
                            }
                        }

                        // 2단계: 병합이 완료된 루비들을 대상으로 길이 검사 및 앞뒤 1글자 텍스트 흡수
                        var mergedRubies = Array.from(document.querySelectorAll('ruby'));
                        for (var i = 0; i < mergedRubies.length; i++) {
                            var ruby = mergedRubies[i];
                            if (!ruby.parentNode) continue;

                            var baseText = Array.from(ruby.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                            var rubyText = Array.from(ruby.querySelectorAll('rt')).map(function(rt) { return rt.textContent; }).join('');
                            if (rubyText.trim().length === 0) continue;

                            var baseLen = Array.from(baseText.trim()).length;
                            var rubyLen = Array.from(rubyText.trim()).length;

                            // 가로, 세로 공통: 1자에 3자, 2자에 4자, 3자 이상에 5자 이상
                            var needsMerge = (baseLen === 1 && rubyLen >= 3) || 
                                             (baseLen === 2 && rubyLen >= 4) || 
                                             (baseLen >= 3 && rubyLen >= 5);

                            if (needsMerge) {
                                var prev = ruby.previousSibling;
                                var next = ruby.nextSibling;
                                
                                // 앞쪽 일반 텍스트 1자 흡수 (Array.from으로 유니코드 이모지/한자 깨짐 방지)
                                if (prev && prev.nodeType === 3 && prev.textContent.length > 0) {
                                    var chars = Array.from(prev.textContent);
                                    var lastChar = chars.pop();
                                    baseText = lastChar + baseText;
                                    prev.textContent = chars.join('');
                                }
                                
                                // 뒤쪽 일반 텍스트 1자 흡수
                                if (next && next.nodeType === 3 && next.textContent.length > 0) {
                                    var chars = Array.from(next.textContent);
                                    var firstChar = chars.shift();
                                    baseText = baseText + firstChar;
                                    next.textContent = chars.join('');
                                }

                                // 3단계: 장평 축소 없이 루비 재구성 및 중앙 정렬 적용
                                ruby.innerHTML = ''; 
                                ruby.appendChild(document.createTextNode(baseText));
                                var rt = document.createElement('rt');
                                rt.textContent = rubyText;
                                ruby.appendChild(rt);
                                ruby.style.rubyAlign = 'center';
                            }
                        }
                    }
                    window.addEventListener('DOMContentLoaded', fixRubySpacing);
            </script>
        """.trimIndent())
        
        // 2. Inject line IDs into body block elements
        val body = doc.body()
        val blockTags = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "article", "section")
        val elements = body.select(blockTags.joinToString(", "))
        var count = 0
        for (el in elements) {
            // Only assign ID if it doesn't contain other block elements (leaf block)
            // or if it has its own direct text content (some EPUBs use generic divs for text).
            val hasBlockChild = el.children().any { it.tagName() in blockTags }
            if (hasBlockChild && el.ownText().isBlank()) continue
            
            count++
            el.attr("id", "line-$idPrefix$count")
            if (el.text().isBlank() && el.select("img, svg, figure").isEmpty()) {
                el.addClass("blank-line")
            }
        }
        
        // If no block elements found, wrap the whole body text to ensure at least one line exists
        if (count == 0) {
            val content = body.html()
            body.html("<div id=\"line-${idPrefix}1\">$content</div>")
            count = 1
        }
        
        // 추가 예외 처리: img나 svg, figure가 혼자 덩그러니 있는 경우에도 라인 ID 부여
        val mediaElements = body.select("img, svg, figure")
        for (media in mediaElements) {
            // [수정] 래퍼가 있으면 래퍼에 ID 부여 (페이지 이동 시 스냅 기준이 됨)
            val target = if (media.parent()?.className() == "image-page-wrapper") media.parent()!! else media
            if (!target.hasAttr("id") || !target.attr("id").startsWith("line-")) {
                count++
                target.attr("id", "line-$idPrefix$count")
            }
        }
        
        // 3. Wrap body content in a chunk div for sliding window
        val originalBodyHtml = body.html()
        body.html("<div class=\"content-chunk\" data-index=\"${idPrefix.removeSuffix("-")}\">$originalBodyHtml</div>")
        
        // Append robust spacer and marker to prevent last line cutting. 
        if (isVertical) {
            body.append("<div id=\"end-marker\" style=\"display:inline-block; width:1px; height:1px;\"></div>")
        } else {
            body.append("<div id=\"end-marker\" style=\"height: 1px; width: 100%; clear: both;\"></div>") 
        }
        
        return doc.outerHtml() to count
    }
}
