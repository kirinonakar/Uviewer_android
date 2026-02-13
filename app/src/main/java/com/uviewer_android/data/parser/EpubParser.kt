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
        
        val containerDoc = Jsoup.parse(containerFile, "UTF-8", Parser.xmlParser())
        val opfPath = containerDoc.select("rootfile").attr("full-path")
        if (opfPath.isEmpty()) throw IOException("Invalid EPUB: OPF path not found in container.xml")
        
        val opfFile = File(epubRoot, opfPath)
        val opfDir = opfFile.parentFile ?: epubRoot // Directory containing OPF, relative paths base
        
        val opfDoc = Jsoup.parse(opfFile, "UTF-8", Parser.xmlParser())
        
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
                // Resolve href relative to OPF directory, then make relative to epubRoot?
                // Actually easier to keep relative to opfDir or resolve to absolute path.
                // Let's resolve to absolute path or keep relative to opfDir?
                // WebView with base URL needs consistency.
                // Let's store full path relative to system root or epub root?
                // Let's store absolute path for simplicity in loading.
                val file = File(opfDir, href)
                spine.add(EpubSpineItem(href = file.absolutePath, id = idref))
            }
        }

        return EpubBook(
            title = titleText,
            author = authorText,
            coverPath = null, // TODO: Parse cover logic (meta name="cover" -> item id -> href)
            spine = spine,
            rootDir = epubRoot.absolutePath
        )
    }
}
