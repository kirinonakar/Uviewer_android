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
    val customDocBackgroundColor: String = "#FFFFFF",
    val customDocTextColor: String = "#000000",
    val sideMargin: Int = 8,
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
    val sideMargin = userPreferencesRepository.sideMargin

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
                listOf(
                    userPreferencesRepository.fontSize,
                    userPreferencesRepository.fontFamily,
                    userPreferencesRepository.docBackgroundColor,
                    userPreferencesRepository.customDocBackgroundColor,
                    userPreferencesRepository.customDocTextColor,
                    userPreferencesRepository.sideMargin
                )
            ) { args: Array<Any> ->
                val size = args[0] as Int
                val family = args[1] as String
                val color = args[2] as String
                val customBg = args[3] as String
                val customText = args[4] as String
                val margin = args[5] as Int
                
                _uiState.value = _uiState.value.copy(
                    fontSize = size,
                    fontFamily = family,
                    docBackgroundColor = color,
                    customDocBackgroundColor = customBg,
                    customDocTextColor = customText,
                    sideMargin = margin
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
                        currentLine = 1
                    )
                    
                    val chapterIdx = savedLine / 1000000
                    val lineInChapter = savedLine % 1000000
                    if (chapterIdx in book.spine.indices) {
                        loadChapter(chapterIdx, lineInChapter)
                    } else {
                        loadChapter(0)
                    }
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
                    
                    // Background pass to extract titles for TOC - Scan entire file in chunks
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val allChapters = mutableListOf<com.uviewer_android.data.model.EpubSpineItem>()
                        val scanChunkSize = 10000
                        for (startLine in 1..totalLines step scanChunkSize) {
                            val chunkText = reader.readLines(startLine, scanChunkSize)
                            val chunkChapters = AozoraParser.extractTitles(chunkText, startLine - 1).map { (title, line) ->
                                com.uviewer_android.data.model.EpubSpineItem(
                                    title = title,
                                    href = "line-$line",
                                    id = "line-$line"
                                )
                            }
                            allChapters.addAll(chunkChapters)
                            // Update UI incrementally if list is long
                            if (allChapters.size % 50 == 0 || startLine + scanChunkSize > totalLines) {
                                _uiState.value = _uiState.value.copy(epubChapters = allChapters.toList())
                            }
                        }
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
                     val fileName = File(currentFilePath).name
                     val savePosition = if (currentFileType == FileEntry.FileType.EPUB) {
                         _uiState.value.currentChapterIndex * 1000000 + line
                     } else {
                         line
                     }
                     
                     val title = if (currentFileType == FileEntry.FileType.EPUB) {
                         val ch = _uiState.value.currentChapterIndex + 1
                         val total = _uiState.value.totalLines
                         val pct = if (total > 0) (line * 100 / total) else 0
                         "$fileName - ch$ch $pct%"
                     } else {
                         fileName
                     }
                     
                     recentFileDao.insertRecent(
                        com.uviewer_android.data.RecentFile(
                            path = currentFilePath,
                            title = title,
                            isWebDav = isWebDavContext,
                            serverId = serverIdContext,
                            type = currentFileType?.name ?: "TEXT",
                            lastAccessed = System.currentTimeMillis(),
                            pageIndex = savePosition
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
                            padding: 1.2em ${_uiState.value.sideMargin / 20.0}em !important;
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
                            margin: 0 -0.4em !important;
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
                        htmlBody = resolveLocalImages(htmlBody, parentDir, null)
                    } else if (serverIdContext != null) {
                        val parentPath = if (currentFilePath.contains("/")) currentFilePath.substringBeforeLast("/") else ""
                        htmlBody = resolveLocalImages(htmlBody, null, serverIdContext, parentPath)
                    }


                    val colors = getColors()
                    AozoraParser.wrapInHtml(
                        htmlBody, 
                        false, 
                        _uiState.value.fontFamily, 
                        _uiState.value.fontSize, 
                        colors.first, 
                        colors.second,
                        _uiState.value.sideMargin
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
        if (currentFileType == FileEntry.FileType.EPUB) {
            if (line < 1 || line > _uiState.value.totalLines) return
            _uiState.value = _uiState.value.copy(currentLine = line)
            return
        }
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
            val savePosition = if (type == "EPUB") {
                _uiState.value.currentChapterIndex * 1000000 + line
            } else {
                line
            }

            val bookmarkTitle = when (type) {
                "EPUB" -> {
                    val ch = _uiState.value.currentChapterIndex + 1
                    val total = _uiState.value.totalLines
                    val pct = if (total > 0) (line * 100 / total) else 0
                    "$fileName - ch$ch $pct%"
                }
                "DOCUMENT", "TEXT", "PDF" -> "$fileName - line $line"
                else -> fileName
            }
            
            // Bookmark (Position)
            bookmarkDao.insertBookmark(
                com.uviewer_android.data.Bookmark(
                    title = bookmarkTitle,
                    path = path,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    type = type,
                    position = savePosition,
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
                    position = savePosition
                )
            )
        }
    }

    fun loadChapter(index: Int, initialLine: Int = 1) {
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
                                padding: 1.2em ${_uiState.value.sideMargin / 20.0}em !important;
                                line-height: 1.8 !important;
                            }
                            rt {
                                text-align: center !important;
                            }
                            .ruby-3-inner {
                                display: inline-block !important;
                                transform: scaleX(0.75) !important;
                                transform-origin: center bottom !important;
                                margin: 0 -0.4em !important;
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
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
                    """.trimIndent()

                    val processedResult = EpubParser.prepareHtmlForViewer(rawContent, resetCss)
                    val processedContent = processedResult.first
                    val lineCount = processedResult.second

                    val baseUrl = "file://${chapterFile.parent}/"
                    
                    _uiState.value = _uiState.value.copy(
                        url = null, 
                        baseUrl = baseUrl,
                        content = processedContent, 
                        currentChapterIndex = index,
                        isLoading = false,
                        currentLine = if (initialLine == -1) lineCount else initialLine,
                        totalLines = lineCount
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
            loadChapter(prev, initialLine = -1)
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

    fun setSideMargin(margin: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setSideMargin(margin)
        }
    }

    fun setCustomDocBackgroundColor(color: String) {
        viewModelScope.launch {
            userPreferencesRepository.setCustomDocBackgroundColor(color)
        }
    }

    fun setCustomDocTextColor(color: String) {
        viewModelScope.launch {
            userPreferencesRepository.setCustomDocTextColor(color)
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
             UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb" to "#322D29"
             UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#cccccc"
             UserPreferencesRepository.DOC_BG_CUSTOM -> _uiState.value.customDocBackgroundColor to _uiState.value.customDocTextColor
             else -> "#ffffff" to "#000000"
        }
    }


    private fun convertMarkdownToHtml(md: String): String {
        var html = md
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        
        // Tables - Improved Regex and parsing
        val tableRegex = Regex("(^(?:\\|.*\\|.*\\|*)+(?:\\r?\\n\\|[ :\\-\\| ]+\\|*)+(?:\\r?\\n(?:\\|.*\\|.*\\|*)+)*)", RegexOption.MULTILINE)
        html = html.replace(tableRegex) { match ->
            val lines = match.value.trim().split(Regex("\\r?\\n"))
            if (lines.size < 2) return@replace match.value
            
            val header = lines[0].trim().trim('|').split("|").map { it.trim() }
            val rows = lines.drop(2).map { row ->
                row.trim().trim('|').split("|").map { it.trim() }
            }
            
            val sb = StringBuilder("<div class='table-container' style='overflow-x: auto; margin: 1em 0; background-color: transparent;'>")
            sb.append("<table style='border-collapse: collapse; min-width: 100%; border: 1px solid #888; white-space: nowrap !important; word-break: normal !important; overflow-wrap: normal !important;'>")
            sb.append("<thead style='background-color: rgba(128,128,128,0.2);'><tr>")
            header.forEach { sb.append("<th style='border: 1px solid #888; padding: 8px; text-align: center; white-space: nowrap !important;'>$it</th>") }
            sb.append("</tr></thead><tbody>")
            rows.forEach { row ->
                sb.append("<tr>")
                // Fill missing cells if any
                for (i in 0 until header.size) {
                    val cell = row.getOrNull(i) ?: ""
                    sb.append("<td style='border: 1px solid #888; padding: 8px; white-space: nowrap !important;'>$cell</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</tbody></table></div>")
            sb.toString()
        }

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

        // Newlines to <br> (only for lines that are not inside HTML tags we just created)
        var insideTag = false
        html = html.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("<table") || trimmed.startsWith("<div class='table-container'")) {
                insideTag = true
                line
            } else if (trimmed.endsWith("</table>") || trimmed.endsWith("</div>")) {
                insideTag = false
                line
            } else if (insideTag || (trimmed.startsWith("<") && trimmed.endsWith(">"))) {
                line
            } else {
                "$line<br/>"
            }
        }
        
        // Images handled by resolveLocalImages later
        html = html.replace(Regex("!\\[(.*?)\\]\\((.*?)\\)"), "<img src=\"$2\" alt=\"$1\" />")
        
        // Paragraphs with line break preservation
        html = html.split(Regex("\\n\\n+")).joinToString("") { p ->
            if (p.startsWith("<h") || p.startsWith("<img") || p.startsWith("<table")) {
                p
            } else {
                val content = p.trim().replace("\n", "<br/>")
                if (content.isEmpty()) "" else "<p>$content</p>"
            }
        }
        
        return html
    }

    private suspend fun resolveLocalImages(content: String, parentDir: java.io.File?, serverId: Int?, parentPath: String? = null): String {
        var result = content
        val imgRegex = Regex("<img\\s+[^>]*src=\"([^\"]+)\"[^>]*>")
        val matches = imgRegex.findAll(content).toList()
        
        for (match in matches) {
            val originalSrc = match.groups[1]?.value ?: continue
            if (originalSrc.startsWith("http") || originalSrc.startsWith("data:")) continue
            
            if (serverId != null && parentPath != null) {
                // WebDAV resolution
                val context = getApplication<Application>()
                val cacheBase = File(context.cacheDir, "webdav_img_cache/$serverId")
                if (!cacheBase.exists()) cacheBase.mkdirs()
                
                val webDavPath = if (parentPath.isEmpty()) originalSrc else "$parentPath/$originalSrc"
                val fileName = java.net.URLEncoder.encode(webDavPath, "UTF-8").takeLast(100)
                val cachedFile = File(cacheBase, fileName)
                
                if (cachedFile.exists()) {
                    result = result.replace(match.value, match.value.replace(originalSrc, "file://${cachedFile.absolutePath}"))
                } else {
                    try {
                        webDavRepository.downloadFile(serverId, webDavPath, cachedFile)
                        result = result.replace(match.value, match.value.replace(originalSrc, "file://${cachedFile.absolutePath}"))
                    } catch (e: Exception) {
                        // Hide missing image or attempt subfolder search logic? 
                        // For WebDAV, listing subfolders recursively is expensive. 
                        // User request specifically mentions "같은경로, 하위폴더 모두 대응".
                        // For local it's easier. For WebDAV let's assume if it's not found, we hide it.
                        result = result.replace(match.value, "")
                    }
                }
            } else if (parentDir != null) {
                // Local resolution
                var imgFile = java.io.File(parentDir, originalSrc)
                if (!imgFile.exists()) {
                    // Search in subfolders as requested
                    val found = parentDir.walkTopDown()
                        .maxDepth(3) // limit depth to avoid excessive search
                        .find { it.name == originalSrc.substringAfterLast("/") }
                    if (found != null) imgFile = found
                }

                if (imgFile.exists()) {
                    result = result.replace(match.value, match.value.replace(originalSrc, "file://${imgFile.absolutePath}"))
                } else {
                    // Hide missing image tag as requested
                    result = result.replace(match.value, "")
                }
            }
        }
        return result
    }

}
