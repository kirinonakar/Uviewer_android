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
            
            val processed = if (currentFileType == FileEntry.FileType.HTML || currentFilePath.endsWith(".html")) {
                chunkText
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val htmlBody = AozoraParser.parse(chunkText, globalLineOffset)
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
}
