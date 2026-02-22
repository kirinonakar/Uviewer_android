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
                        val buffer = ByteArray(1024)
                        var count: Int
                        while (zis.read(buffer).also { count = it } != -1) {
                            fos.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    fun parse(epubRoot: File): EpubBook {
        // 1. Find Open Packaging Format (OPF) file via META-INF/container.xml
        val containerFile = File(epubRoot, "META-INF/container.xml")
        if (!containerFile.exists()) throw IOException("Invalid EPUB: META-INF/container.xml not found")
        
        val containerDoc = Jsoup.parse(containerFile.inputStream(), "UTF-8", containerFile.absolutePath, Parser.xmlParser())
        val opfPath = containerDoc.select("rootfile").attr("full-path")
        if (opfPath.isEmpty()) throw IOException("Invalid EPUB: OPF path not found in container.xml")
        
        val opfFile = File(epubRoot, opfPath)
        val opfDir = opfFile.parentFile ?: epubRoot // Directory containing OPF, relative paths base
        
        val opfDoc = Jsoup.parse(opfFile.inputStream(), "UTF-8", opfFile.absolutePath, Parser.xmlParser())
        
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
        val manifestItems = opfDoc.select("manifest > item")
        for (item in manifestItems) {
            val id = item.attr("id")
            val href = item.attr("href")
            manifest[id] = href
        }

        // Spine (ordered list of ids)
        val spine = mutableListOf<EpubSpineItem>()
        val spineItems = opfDoc.select("spine > itemref")
        for (itemref in spineItems) {
            val idref = itemref.attr("idref")
            val href = manifest[idref]
            if (href != null) {
                val file = File(opfDir, href)
                spine.add(EpubSpineItem(href = file.absolutePath, id = idref))
            }
        }

        // TOC Parsing (NCX)
        val ncxId = opfDoc.select("spine").attr("toc")
        val ncxHref = manifest[ncxId]
        val tocMap = mutableMapOf<String, String>() // href -> title
        if (ncxHref != null) {
            val ncxFile = File(opfDir, ncxHref)
            if (ncxFile.exists()) {
                val ncxDoc = Jsoup.parse(ncxFile.inputStream(), "UTF-8", ncxFile.absolutePath, Parser.xmlParser())
                val navPoints = ncxDoc.select("navPoint")
                for (point in navPoints) {
                    val label = point.select("navLabel > text").first()?.text()
                    val src = point.select("content").attr("src")
                    // src might have fragments (#...), strip them for matching href
                    val cleanSrc = src.substringBefore("#")
                    if (label != null) {
                        tocMap[cleanSrc] = label
                    }
                }
            }
        }

        // Apply titles to spine items
        val finalSpine = spine.map { item ->
            // Try to match href (absolute) back to manifest relative href for TOC lookup
            val relativeHref = File(item.href).relativeTo(opfDir).path.replace("\\", "/")
            item.copy(title = tocMap[relativeHref])
        }

        return EpubBook(
            title = titleText,
            author = authorText,
            coverPath = null, // TODO: Parse cover logic
            spine = finalSpine,
            rootDir = epubRoot.absolutePath
        )
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
                    val imgFile = File(baseDir, src)
                    val separator = File.separator
                    val encodedPath = imgFile.canonicalPath.split(separator).joinToString("/") { segment ->
                        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                    }
                    img.attr("src", "file:///$encodedPath")
                }
                
                // p 태그 안에 div를 넣으면 HTML이 깨지며 빈 p 태그가 생성됨.
                // 이미지가 단독으로 있는 p/div 라면 태그 자체를 래퍼로 변환.
                val parent = img.parent()
                if (parent != null && (parent.tagName() == "p" || parent.tagName() == "div") && parent.text().isBlank() && parent.childrenSize() == 1) {
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
