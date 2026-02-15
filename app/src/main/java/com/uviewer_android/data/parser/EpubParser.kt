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

    fun prepareHtmlForViewer(html: String, resetCss: String): Pair<String, Int> {
        val doc = Jsoup.parse(html)
        
        // 1. Inject styling and scroll script into head
        doc.head().append(resetCss)
        doc.head().append("""
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
        """.trimIndent())
        
        // 2. Inject line IDs into body block elements
        val body = doc.body()
        val elements = body.select("p, div, li, h1, h2, h3, h4, h5, h6, blockquote, pre, article, section")
        var count = 0
        for (el in elements) {
            count++
            el.attr("id", "line-$count")
        }
        
        // If no block elements found, wrap the whole body text to ensure at least one line exists
        if (count == 0) {
            val content = body.html()
            body.html("<div id=\"line-1\">$content</div>")
            count = 1
        }
        
        // Append robust spacer and marker to prevent last line cutting. 
        body.append("""
            <div style="height: 50vh; width: 100%; clear: both;"></div>
            <div id="end-marker" style="height: 1px; width: 100%; clear: both;"></div>
        """.trimIndent())
        
        return doc.outerHtml() to count
    }
}
