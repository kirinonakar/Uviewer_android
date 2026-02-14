package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.parser.AozoraParser
import com.uviewer_android.data.parser.EpubParser
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.Charset

data class DocumentViewerUiState(
    val content: String = "",
    val url: String? = null,
    val baseUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isVertical: Boolean = false,
    val fontSize: Int = 18,
    val fontFamily: String = "serif",
    val docBackgroundColor: String = UserPreferencesRepository.DOC_BG_WHITE,
    val epubChapters: List<com.uviewer_android.data.model.EpubSpineItem> = emptyList(),
    val currentChapterIndex: Int = 0,
    val totalLines: Int = 0,
    val currentLine: Int = 1,
    val fileName: String? = null,
    val currentChunkIndex: Int = 0,
    val hasMoreContent: Boolean = false,
    val manualEncoding: String? = null,
    val loadProgress: Float = 1f
)

class DocumentViewerViewModel(
    application: Application,
    private val fileRepository: FileRepository,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao,
    private val bookmarkDao: com.uviewer_android.data.BookmarkDao,
    private val favoriteDao: com.uviewer_android.data.FavoriteDao,
    private val userPreferencesRepository: UserPreferencesRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DocumentViewerUiState())
    val uiState: StateFlow<DocumentViewerUiState> = _uiState.asStateFlow()

    // Expose flows for FontSettingsDialog
    val fontSize = userPreferencesRepository.fontSize
    val fontFamily = userPreferencesRepository.fontFamily
    val docBackgroundColor = userPreferencesRepository.docBackgroundColor

    // Cache for raw content to re-process (e.g. toggle vertical)
    private var largeTextReader: com.uviewer_android.data.utils.LargeTextReader? = null
    companion object {
        const val LINES_PER_CHUNK = 2000
    }
    private var currentFilePath: String = ""
    private var currentFileType: FileEntry.FileType? = null
    private var isWebDavContext: Boolean = false
    private var serverIdContext: Int? = null

    init {
        viewModelScope.launch {
            combine(
                userPreferencesRepository.fontSize,
                userPreferencesRepository.fontFamily,
                userPreferencesRepository.docBackgroundColor
            ) { size, family, color ->
                _uiState.value = _uiState.value.copy(
                    fontSize = size,
                    fontFamily = family,
                    docBackgroundColor = color
                )
                if (largeTextReader != null && currentFileType == FileEntry.FileType.TEXT) {
                    loadTextChunk(_uiState.value.currentChunkIndex)
                }
            }.collect {}
        }
    }

    fun loadDocument(filePath: String, type: FileEntry.FileType, isWebDav: Boolean, serverId: Int?, initialLine: Int? = null) {
        currentFilePath = filePath
        currentFileType = type
        isWebDavContext = isWebDav
        serverIdContext = serverId
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, fileName = File(filePath).name)
            
            // Add to Recent (and retrieve saved position)
            var savedLine = initialLine ?: try {
                val existing = recentFileDao.getFile(filePath)
                existing?.pageIndex ?: 1
            } catch (e: Exception) {
                1
            }
            if (savedLine < 1) savedLine = 1
            
            try {
                val title = File(filePath).name
                recentFileDao.insertRecent(
                    com.uviewer_android.data.RecentFile(
                        path = filePath,
                        title = title,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = type.name,
                        lastAccessed = System.currentTimeMillis(),
                        pageIndex = savedLine // Preserve saved line
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                if (type == FileEntry.FileType.EPUB) {
                    val epubFile = if (isWebDav && serverId != null) {
                         val context = getApplication<Application>()
                         val cacheDir = context.cacheDir
                         val tempFile = File(cacheDir, "temp_" + File(filePath).name)
                         webDavRepository.downloadFile(serverId, filePath, tempFile)
                         tempFile
                    } else {
                        File(filePath)
                    }
                    
                    // Unzip EPUB
                    val context = getApplication<Application>()
                    val cacheDir = context.cacheDir
                    val unzipDir = File(cacheDir, "epub_${epubFile.name}_unzipped")
                    if (unzipDir.exists()) unzipDir.deleteRecursively()
                    unzipDir.mkdirs()
                    
                    EpubParser.unzip(epubFile, unzipDir)
                    val book = EpubParser.parse(unzipDir)

                    _uiState.value = _uiState.value.copy(
                        epubChapters = book.spine,
                        currentChapterIndex = 0,
                        isLoading = false,
                        currentLine = 1 // EPUB uses chapter index, reset line? Or restore? 
                        // EPUB restoration is complex (chapter + percentage). 
                        // For now sticking to chapter 0 as requested, or maybe basic chapter restore could be added later.
                        // But user specifically asked for "Document bookmark restoration (save/restore line position)" which implies Text/Aozora mostly.
                    )
                    loadChapter(0) // Load first chapter
                } else {
                    // Text / HTML / CSV
                    val file = if (isWebDav && serverId != null) {
                         val context = getApplication<Application>()
                         val cacheDir = context.cacheDir
                         val tempFile = File(cacheDir, "temp_" + File(filePath).name)
                         webDavRepository.downloadFile(serverId, filePath, tempFile)
                         tempFile
                    } else {
                        File(filePath)
                    }

                    val reader = com.uviewer_android.data.utils.LargeTextReader(file)
                    largeTextReader = reader
                    reader.indexFile(_uiState.value.manualEncoding) { progress ->
                        _uiState.value = _uiState.value.copy(loadProgress = progress)
                    }

                    val totalLines = reader.getTotalLines()
                    
                    // Background pass to extract titles for TOC
                    viewModelScope.launch {
                        val sampleText = reader.readLines(1, 5000) // Scan first 5000 lines for headers
                        val chapters = AozoraParser.extractTitles(sampleText).map { (title, line) ->
                            com.uviewer_android.data.model.EpubSpineItem(
                                title = title,
                                href = "line-$line",
                                id = "line-$line"
                            )
                        }
                        _uiState.value = _uiState.value.copy(epubChapters = chapters)
                    }

                    if (type == FileEntry.FileType.CSV || (type == FileEntry.FileType.TEXT && filePath.lowercase().endsWith(".csv"))) {
                        // For CSV, just load the whole thing or large chunk for table view
                        val contentString = reader.readLines(1, totalLines.coerceAtMost(2000))
                        val rows = contentString.lines().filter { it.isNotBlank() }
                        val sb = StringBuilder()
                        sb.append("<table>")
                        rows.forEach { row ->
                            sb.append("<tr>")
                            val cells = row.split(",")
                            cells.forEach { cell ->
                                sb.append("<td style='border:1px solid #ccc; padding: 4px;'>${cell.trim()}</td>")
                            }
                            sb.append("</tr>")
                        }
                        sb.append("</table>")
                        
                         _uiState.value = _uiState.value.copy(
                            content = sb.toString(),
                            isLoading = false,
                            totalLines = totalLines,
                            currentLine = 1,
                            currentChunkIndex = 0,
                            hasMoreContent = totalLines > 2000
                        )
                    } else {
                        // Text / Aozora / HTML
                        // Update TOC headers by reading the whole file in background? 
                        // Or just first few throusand lines?
                        // For efficiency, scan headers during indexing or in a separate pass.
                        
                        val startLine = savedLine
                        val chunkIdx = (startLine - 1) / LINES_PER_CHUNK
                        
                        _uiState.value = _uiState.value.copy(
                            currentChunkIndex = chunkIdx,
                            totalLines = totalLines,
                            currentLine = savedLine
                        )
                        loadTextChunk(chunkIdx)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun updateProgress(line: Int) {
        if (currentFilePath.isNotEmpty()) {
             viewModelScope.launch {
                 try {
                     val title = File(currentFilePath).name
                     recentFileDao.insertRecent(
                        com.uviewer_android.data.RecentFile(
                            path = currentFilePath,
                            title = title,
                            isWebDav = isWebDavContext,
                            serverId = serverIdContext,
                            type = currentFileType?.name ?: "TEXT",
                            lastAccessed = System.currentTimeMillis(),
                            pageIndex = line
                        )
                    )
                 } catch (e: Exception) { e.printStackTrace() }
             }
        }
    }
    private fun loadTextChunk(chunkIndex: Int) {
        val reader = largeTextReader ?: return
        val startLine = chunkIndex * LINES_PER_CHUNK + 1
        val totalLines = reader.getTotalLines()
        if (startLine > totalLines) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val chunkText = reader.readLines(startLine, LINES_PER_CHUNK)
            val globalLineOffset = startLine - 1
            
            val processed = if (currentFileType == FileEntry.FileType.HTML || currentFilePath.endsWith(".html", ignoreCase = true)) {
                val colors = getColors()
                val fontFamily = when(_uiState.value.fontFamily) {
                    "serif" -> "'Sawarabi Mincho', serif"
                    "sans-serif" -> "'Sawarabi Gothic', sans-serif"
                    else -> "serif"
                }
                val resetCss = """
                    <style>
                        /* Ignore fixed layout constraints aggressively, but spare ruby elements */
                        *:not(ruby):not(rt):not(rp) { 
                            max-width: 100% !important; 
                            box-sizing: border-box !important; 
                            overflow-wrap: break-word !important; 
                            width: auto !important; 
                            height: auto !important; 
                            float: none !important; 
                            position: static !important; 
                            margin-left: 0 !important;
                            margin-right: 0 !important;
                            padding-left: 0 !important;
                            padding-right: 0 !important;
                            text-indent: 0 !important;
                        }
                        html, body {
                            width: 100% !important;
                            height: auto !important;
                            overflow-x: hidden !important;
                            margin: 0 !important;
                            padding: 0 !important;
                        }
                        body { 
                            background-color: ${colors.first} !important; 
                            color: ${colors.second} !important; 
                            font-family: $fontFamily !important;
                            font-size: ${_uiState.value.fontSize}px !important;
                            padding: 1.2em !important;
                            display: block !important;
                            line-height: 1.8 !important;
                        }
                        p, div, article, section, h1, h2, h3, h4, h5, h6 {
                            width: 100% !important;
                            display: block !important;
                            margin-top: 0.5em !important;
                            margin-bottom: 0.5em !important;
                        }
                        img { 
                            max-width: 100% !important; 
                            height: auto !important; 
                            display: block !important; 
                            margin: 1em auto !important; 
                        }
                        /* Support for tables in small screens */
                        table {
                            display: block !important;
                            overflow-x: auto !important;
                            width: 100% !important;
                        }
                        rt {
                            text-align: center !important;
                        }
                        .ruby-3-inner {
                            display: inline-block !important;
                            transform: scaleX(0.75) !important;
                            transform-origin: center bottom !important;
                            white-space: nowrap !important;
                        }
                        /* Hide potentially problematic layout elements */
                        iframe, script, noscript, style:not([data-app-style]) { display: none !important; }
                    </style>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
                """.trimIndent()
                resetCss + chunkText
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    var htmlBody = if (currentFilePath.endsWith(".md", ignoreCase = true)) {
                        convertMarkdownToHtml(chunkText)
                    } else {
                        AozoraParser.parse(chunkText, globalLineOffset)
                    }
                    
                    if (!isWebDavContext) {
                        val parentDir = java.io.File(currentFilePath).parentFile
                        htmlBody = resolveLocalImages(htmlBody, parentDir)
                    }

                    val colors = getColors()
                    AozoraParser.wrapInHtml(
                        htmlBody, 
                        false, 
                        _uiState.value.fontFamily, 
                        _uiState.value.fontSize, 
                        colors.first, 
                        colors.second
                    )
                }
            }

            _uiState.value = _uiState.value.copy(
                content = processed,
                isLoading = false,
                currentChunkIndex = chunkIndex,
                hasMoreContent = (startLine + LINES_PER_CHUNK) <= totalLines
            )
        }
    }

    fun nextChunk() {
        if (_uiState.value.hasMoreContent) {
            val nextChunkIdx = _uiState.value.currentChunkIndex + 1
            val targetLine = nextChunkIdx * LINES_PER_CHUNK + 1
            
            _uiState.value = _uiState.value.copy(currentLine = targetLine, isLoading = true)
            loadTextChunk(nextChunkIdx)
        }
    }
    
    fun prevChunk() {
        if (_uiState.value.currentChunkIndex > 0) {
            val prevChunkIdx = _uiState.value.currentChunkIndex - 1
            val totalLines = largeTextReader?.getTotalLines() ?: 0
            // When going back, we want to go to the LAST line of the previous chunk
            val targetLine = (prevChunkIdx + 1) * LINES_PER_CHUNK 
            val clampedLine = targetLine.coerceAtMost(totalLines)
            
            _uiState.value = _uiState.value.copy(currentLine = clampedLine, isLoading = true)
            loadTextChunk(prevChunkIdx)
        }
    }

    fun jumpToLine(line: Int) {
        val reader = largeTextReader ?: return
        if (line < 1 || line > reader.getTotalLines()) return
        
        val targetChunk = (line - 1) / LINES_PER_CHUNK
        
        // TOC synchronization logic
        val chapterIdx = _uiState.value.epubChapters.indexOfLast { 
            it.href.startsWith("line-") && (it.href.removePrefix("line-").toIntOrNull() ?: 0) <= line 
        }.coerceAtLeast(0)

        if (targetChunk != _uiState.value.currentChunkIndex) {
            // [Modified] Set isLoading to true immediately to signal UI to lock reporters
            _uiState.value = _uiState.value.copy(
                currentLine = line, 
                currentChapterIndex = chapterIdx,
                isLoading = true 
            )
            loadTextChunk(targetChunk)
        } else {
            // Even if same chunk, update currentLine to target
            _uiState.value = _uiState.value.copy(
                currentLine = line, 
                currentChapterIndex = chapterIdx
            )
        }
    }

    fun toggleBookmark(path: String, line: Int, isWebDav: Boolean, serverId: Int?, type: String) {
        viewModelScope.launch {
            val fileName = File(path).name
            val bookmarkTitle = if (type == "DOCUMENT" || type == "TEXT" || type == "PDF") "$fileName - line $line" else fileName
            
            // Bookmark (Position)
            bookmarkDao.insertBookmark(
                com.uviewer_android.data.Bookmark(
                    title = bookmarkTitle,
                    path = path,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    type = type,
                    position = line,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Favorite (File)
            favoriteDao.insertFavorite(
                com.uviewer_android.data.FavoriteItem(
                    title = bookmarkTitle,
                    path = path,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    type = type,
                    position = line
                )
            )
        }
    }

    fun loadChapter(index: Int) {
        val chapters = _uiState.value.epubChapters
        if (index in chapters.indices) {
            val chapter = chapters[index]
            
            if (currentFileType == FileEntry.FileType.EPUB) {
                val chapterFile = File(chapter.href)
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(isLoading = true) 
                    val rawContent = try {
                         chapterFile.readText()
                    } catch (e: Exception) {
                        "Error reading chapter: ${e.message}"
                    }

                    val colors = getColors()
                    val fontFamily = when(_uiState.value.fontFamily) {
                        "serif" -> "'Sawarabi Mincho', serif"
                        "sans-serif" -> "'Sawarabi Gothic', sans-serif"
                        else -> "serif"
                    }
                    val resetCss = """
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
                        <style data-app-style="true">
                            *:not(ruby):not(rt):not(rp) { 
                                max-width: 100% !important; 
                                box-sizing: border-box !important; 
                                overflow-wrap: break-word !important; 
                            }
                            html, body {
                                width: 100% !important;
                                margin: 0 !important;
                                padding: 0 !important;
                                background-color: ${colors.first} !important;
                                color: ${colors.second} !important;
                            }
                            body { 
                                font-family: $fontFamily !important;
                                font-size: ${_uiState.value.fontSize}px !important;
                                padding: 1.2em !important;
                                line-height: 1.8 !important;
                            }
                            rt {
                                text-align: center !important;
                            }
                            .ruby-3-inner {
                                display: inline-block !important;
                                transform: scaleX(0.75) !important;
                                transform-origin: center bottom !important;
                                white-space: nowrap !important;
                            }
                            /* Layout ignore fixes */
                            [style*="width"]:not(ruby):not(rt):not(rp), 
                            [style*="margin-left"]:not(ruby):not(rt):not(rp), 
                            [style*="margin-right"]:not(ruby):not(rt):not(rp) {
                                width: auto !important;
                                margin-left: 0 !important;
                                margin-right: 0 !important;
                            }
                        </style>
                    """.trimIndent()

                    val baseUrl = "file://${chapterFile.parent}/"
                    
                    _uiState.value = _uiState.value.copy(
                        url = null, 
                        baseUrl = baseUrl,
                        content = resetCss + rawContent, 
                        currentChapterIndex = index,
                        isLoading = false,
                        currentLine = 1,
                        totalLines = rawContent.lines().size
                    )
                }
            } else {
                // For Text files
                if (chapter.href.startsWith("line-")) {
                    val line = chapter.href.removePrefix("line-").toIntOrNull() ?: 1
                    jumpToLine(line)
                }
            }
        }
    }

    fun nextChapter() {
        val next = _uiState.value.currentChapterIndex + 1
        if (next < _uiState.value.epubChapters.size) {
            loadChapter(next)
        }
    }

    fun prevChapter() {
        val prev = _uiState.value.currentChapterIndex - 1
        if (prev >= 0) {
            loadChapter(prev)
        }
    }



    fun setFontSize(size: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setFontSize(size)
        }
    }

    fun setFontFamily(family: String) {
        viewModelScope.launch {
            userPreferencesRepository.setFontFamily(family)
        }
    }

    fun setDocBackgroundColor(color: String) {
        viewModelScope.launch {
            userPreferencesRepository.setDocBackgroundColor(color)
        }
    }

    fun setManualEncoding(encoding: String?, isWebDav: Boolean, serverId: Int?) {
        _uiState.value = _uiState.value.copy(manualEncoding = encoding)
        // Reload
        if (currentFilePath.isNotEmpty() && currentFileType != null) {
            loadDocument(currentFilePath, currentFileType!!, isWebDav, serverId)
        }
    }

    private fun getColors(): Pair<String, String> {
        return when (_uiState.value.docBackgroundColor) {
             UserPreferencesRepository.DOC_BG_SEPIA -> "#f5f5dc" to "#5b4636"
             UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#cccccc"
             else -> "#ffffff" to "#000000"
        }
    }

    private fun convertMarkdownToHtml(md: String): String {
        var html = md
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        
        // Headers
        html = html.replace(Regex("^# (.*)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        html = html.replace(Regex("^## (.*)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        html = html.replace(Regex("^### (.*)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        
        // Bold
        html = html.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
        html = html.replace(Regex("__(.*?)__"), "<b>$1</b>")
        
        // Italic
        html = html.replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
        html = html.replace(Regex("_(.*?)_"), "<i>$1</i>")
        
        // Links
        html = html.replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">$1</a>")
        
        // Images handled by resolveLocalImages later
        html = html.replace(Regex("!\\[(.*?)\\]\\((.*?)\\)"), "<img src=\"$2\" alt=\"$1\" />")
        
        // Paragraphs
        html = html.split(Regex("\\n\\n+")).joinToString("") { p ->
            if (p.startsWith("<h") || p.startsWith("<img")) p else "<p>${p.replace("\n", " ")}</p>"
        }
        
        return html
    }

    private fun resolveLocalImages(content: String, parentDir: java.io.File?): String {
        if (parentDir == null) return content
        var result = content
        val imgRegex = Regex("<img\\s+[^>]*src=\"([^\"]+)\"[^>]*>")
        imgRegex.findAll(content).forEach { match ->
            val originalSrc = match.groups[1]?.value ?: return@forEach
            if (originalSrc.startsWith("http") || originalSrc.startsWith("data:")) return@forEach
            
            val imgFile = java.io.File(parentDir, originalSrc)
            if (imgFile.exists()) {
                result = result.replace(match.value, match.value.replace(originalSrc, "file://${imgFile.absolutePath}"))
            } else {
                // Hide missing image tag as requested
                result = result.replace(match.value, "")
            }
        }
        return result
    }
}
