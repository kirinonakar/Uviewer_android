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
    val manualEncoding: String? = null
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
    private var rawContentCache: String? = null
    private var lineOffsets = listOf<Int>()
    private var currentFileType: FileEntry.FileType? = null
    private var currentFilePath: String = ""
    private var isWebDavContext: Boolean = false
    private var serverIdContext: Int? = null

    private val CHUNK_SIZE = 100000 // Increased chunk size for better scrolling 100k chars ~ 200kb

    init {
        viewModelScope.launch {
            combine(
                userPreferencesRepository.fontSize,
                userPreferencesRepository.fontFamily,
                userPreferencesRepository.docBackgroundColor
            ) { size, family, color ->
                Triple(size, family, color)
            }.collect { (size, family, color) ->
                _uiState.value = _uiState.value.copy(
                    fontSize = size,
                    fontFamily = family,
                    docBackgroundColor = color
                )
                // If we have content and it's Aozora, re-wrap needed?
                // AozoraParser.wrapInHtml uses these values.
                if (rawContentCache != null && currentFileType == FileEntry.FileType.TEXT) {
                    processTextContent(rawContentCache!!)
                }
            }
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
                     val rawBytes = if (isWebDav && serverId != null) {
                         // For large files, we should stream, but for now download full
                         // TODO: Implement proper streaming or partial read
                         val context = getApplication<Application>()
                         val cacheDir = context.cacheDir
                         val tempFile = File(cacheDir, "temp_" + File(filePath).name)
                         webDavRepository.downloadFile(serverId, filePath, tempFile)
                         tempFile.readBytes()
                    } else {
                        File(filePath).readBytes()
                    }
                    
                    val contentString = decodeBytes(rawBytes, _uiState.value.manualEncoding)
                    rawContentCache = contentString

                    // Calculate line offsets for global navigation
                    lineOffsets = mutableListOf<Int>().apply {
                        add(0)
                        var pos = contentString.indexOf('\n')
                        while (pos != -1) {
                            add(pos + 1)
                            pos = contentString.indexOf('\n', pos + 1)
                        }
                    }
                    
                    if (type == FileEntry.FileType.CSV || (type == FileEntry.FileType.TEXT && filePath.lowercase().endsWith(".csv"))) {
                        // Simple CSV to HTML Table
                        val rows = contentString.lines().filter { it.isNotBlank() }
                        val sb = StringBuilder()
                        sb.append("<table>")
                        rows.forEach { row ->
                            sb.append("<tr>")
                            val cells = row.split(",") // Basic split, regex for quotes needed for robustness
                            cells.forEach { cell ->
                                sb.append("<td style='border:1px solid #ccc; padding: 4px;'>${cell.trim()}</td>")
                            }
                            sb.append("</tr>")
                        }
                        sb.append("</table>")
                        
                         _uiState.value = _uiState.value.copy(
                            content = sb.toString(),
                            isLoading = false,
                            totalLines = rows.size,
                            currentLine = 1,
                            currentChunkIndex = 0,
                            hasMoreContent = false
                        )
                    } else if (type == FileEntry.FileType.HTML || filePath.lowercase().endsWith(".html") || filePath.lowercase().endsWith(".htm")) {
                        // Direct HTML - do not Aozora parse
                         _uiState.value = _uiState.value.copy(
                             content = contentString,
                             isLoading = false,
                             totalLines = lineOffsets.size,
                             currentLine = savedLine
                         )
                    } else {
                        // Text / Aozora
                        val chapters = mutableListOf<com.uviewer_android.data.model.EpubSpineItem>()
                        val lines = contentString.lines()
                        lines.forEachIndexed { index, line ->
                            val trimmed = line.trim()
                            if (trimmed.startsWith("#") || 
                                Regex("［＃[大中小]見出し］(.+?)［＃[大中小]見出し終わり］").containsMatchIn(trimmed) ||
                                (trimmed.startsWith("第") && trimmed.contains("章") && trimmed.length < 50)) {
                                
                                val title = if (trimmed.startsWith("［＃")) {
                                    trimmed.replace(Regex("［＃.+?見出し］"), "").replace(Regex("［＃.+?見出し終わり］"), "")
                                } else {
                                    trimmed.removePrefix("#").trim()
                                }

                                chapters.add(com.uviewer_android.data.model.EpubSpineItem(
                                    title = title,
                                    href = "line-${index + 1}",
                                    id = "line-${index + 1}"
                                ))
                            }
                        }

                        processTextContent(contentString, false)
                        
                        _uiState.value = _uiState.value.copy(
                            epubChapters = chapters,
                            totalLines = lineOffsets.size,
                            currentLine = savedLine
                        )
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
    private fun processTextContent(text: String, isHtml: Boolean = false) {
        val chunkIndex = _uiState.value.currentChunkIndex
        val startIndex = chunkIndex * CHUNK_SIZE
        if (startIndex >= text.length) return
        
        val endIndex = (startIndex + CHUNK_SIZE).coerceAtMost(text.length)
        var chunk = text.substring(startIndex, endIndex)
        
        if (endIndex < text.length) {
            val lastNewline = chunk.lastIndexOf('\n')
            if (lastNewline > 0) {
                chunk = chunk.substring(0, lastNewline)
            }
        }
        
        val hasMore = (startIndex + chunk.length) < text.length
        
        // Calculate global line offset for the parser
        var globalLineOffset = 0
        for (i in 0 until startIndex) {
            if (text[i] == '\n') globalLineOffset++
        }
        
        viewModelScope.launch {
            val processed = if (isHtml) {
                 chunk 
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val htmlBody = AozoraParser.parse(chunk, globalLineOffset)
                    AozoraParser.wrapInHtml(
                        htmlBody, 
                        false, 
                        _uiState.value.fontFamily, 
                        _uiState.value.fontSize, 
                        _uiState.value.docBackgroundColor
                    )
                }
            }
            
            _uiState.value = _uiState.value.copy(
                content = processed,
                isLoading = false,
                totalLines = lineOffsets.size,
                currentLine = _uiState.value.currentLine, // Maintain or it will be 1
                hasMoreContent = hasMore
            )
        }
    }
    
    fun nextChunk() {
        if (_uiState.value.hasMoreContent) {
            _uiState.value = _uiState.value.copy(currentChunkIndex = _uiState.value.currentChunkIndex + 1)
            rawContentCache?.let { processTextContent(it) }
        }
    }
    
    fun prevChunk() {
        if (_uiState.value.currentChunkIndex > 0) {
             _uiState.value = _uiState.value.copy(currentChunkIndex = _uiState.value.currentChunkIndex - 1)
             rawContentCache?.let { processTextContent(it) }
        }
    }

    fun jumpToLine(line: Int) {
        if (line < 1 || lineOffsets.isEmpty()) return
        val offset = lineOffsets.getOrNull(line - 1) ?: return
        val targetChunk = offset / CHUNK_SIZE
        
        if (targetChunk != _uiState.value.currentChunkIndex) {
            _uiState.value = _uiState.value.copy(
                currentChunkIndex = targetChunk,
                currentLine = line,
                isLoading = true
            )
            rawContentCache?.let { processTextContent(it, false) }
        } else {
            _uiState.value = _uiState.value.copy(currentLine = line)
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

                    val baseUrl = "file://${chapterFile.parent}/"
                    
                    _uiState.value = _uiState.value.copy(
                        url = null, 
                        baseUrl = baseUrl,
                        content = rawContent, 
                        currentChapterIndex = index,
                        isLoading = false,
                        currentLine = 1,
                        totalLines = rawContent.lines().size
                    )
                }
            } else {
                // For Text files, "Next Chapter" means jump to the line of the next header
                if (chapter.href.startsWith("line-")) {
                    val line = chapter.href.removePrefix("line-").toIntOrNull() ?: 1
                    _uiState.value = _uiState.value.copy(
                        currentChapterIndex = index,
                        currentLine = line
                    )
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

    private fun decodeBytes(bytes: ByteArray, manualEncoding: String?): String {
        val decoded = if (manualEncoding != null) {
            try {
                String(bytes, java.nio.charset.Charset.forName(manualEncoding))
            } catch (e: Exception) {
                String(bytes, com.uviewer_android.data.utils.EncodingDetector.detectEncoding(bytes))
            }
        } else {
            String(bytes, com.uviewer_android.data.utils.EncodingDetector.detectEncoding(bytes))
        }
        // Normalize to NFC to fix "Jobi-hyeong" (Separated Jamo) issues
        return java.text.Normalizer.normalize(decoded, java.text.Normalizer.Form.NFC)
    }
    
    private fun getColors(): Pair<String, String> {
        return when (_uiState.value.docBackgroundColor) {
             UserPreferencesRepository.DOC_BG_SEPIA -> "#f5f5dc" to "#5b4636"
             UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#e0e0e0"
             else -> "#ffffff" to "#000000"
        }
    }
}
