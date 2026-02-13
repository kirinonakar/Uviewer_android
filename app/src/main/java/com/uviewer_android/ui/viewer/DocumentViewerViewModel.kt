package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.parser.AozoraParser
import com.uviewer_android.data.parser.EpubParser
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import com.uviewer_android.data.repository.UserPreferencesRepository

    data class DocumentViewerUiState(
        val content: String = "",
        val url: String? = null,
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
        val hasMoreContent: Boolean = false
    )

    class DocumentViewerViewModel(
        application: Application,
        private val fileRepository: FileRepository,
        private val webDavRepository: WebDavRepository,
        private val recentFileDao: com.uviewer_android.data.RecentFileDao,
        private val bookmarkDao: com.uviewer_android.data.BookmarkDao,
        private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository
    ) : AndroidViewModel(application) {

        private val _uiState = MutableStateFlow(DocumentViewerUiState())
        val uiState: StateFlow<DocumentViewerUiState> = _uiState.asStateFlow()

        private var rawContentCache: String? = null
        private var currentFileType: FileEntry.FileType? = null
        private var currentBook: com.uviewer_android.data.model.EpubBook? = null
        private var currentFilePath: String = ""
        private val CHUNK_SIZE = 3000

        init {
            viewModelScope.launch {
                combine(
                    userPreferencesRepository.fontSize,
                    userPreferencesRepository.fontFamily,
                    userPreferencesRepository.docBackgroundColor
                ) { size, family, docBgColor ->
                     Triple(size, family, docBgColor)
                }.collect { (size, family, docBgColor) ->
                    // Prevent redundant updates if values haven't effectively changed or content is empty
                    if (_uiState.value.fontSize != size || _uiState.value.fontFamily != family || _uiState.value.docBackgroundColor != docBgColor) {
                         _uiState.value = _uiState.value.copy(
                            fontSize = size,
                            fontFamily = family,
                            docBackgroundColor = docBgColor
                        )
                        if (rawContentCache != null && currentFileType == FileEntry.FileType.TEXT) {
                            val htmlBody = AozoraParser.parse(rawContentCache!!)
                            val processedContent = AozoraParser.wrapInHtml(htmlBody, _uiState.value.isVertical, family, size, docBgColor)
                            // Only update content if styles changed, this logic is already in collect
                             _uiState.value = _uiState.value.copy(content = processedContent)
                        }
                    }
                }
            }
        }

    val language: StateFlow<String> = userPreferencesRepository.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.LANG_SYSTEM)

    val fontSize: StateFlow<Int> = userPreferencesRepository.fontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 18)

    val fontFamily: StateFlow<String> = userPreferencesRepository.fontFamily
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "serif")

    val docBackgroundColor: StateFlow<String> = userPreferencesRepository.docBackgroundColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.DOC_BG_WHITE)

        fun loadDocument(filePath: String, type: FileEntry.FileType, isWebDav: Boolean, serverId: Int?) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                currentFileType = type
                currentFilePath = filePath
                
                try {
                    val title = File(filePath).name
                    val typeStr = if (type == FileEntry.FileType.EPUB) "EPUB" else "TEXT"
                    recentFileDao.insertRecent(
                        com.uviewer_android.data.RecentFile(
                            path = filePath,
                            title = title,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = typeStr,
                            lastAccessed = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {}

                try {
                    if (type == FileEntry.FileType.EPUB) {
                        val context = getApplication<Application>()
                        val cacheDir = context.cacheDir
                        // ... EPUB logic same as before, simplified for diff ...
                         val epubFile = if (isWebDav && serverId != null) {
                             val fileName = File(filePath).name
                             val tempFile = File(cacheDir, "temp_$fileName")
                             webDavRepository.downloadFile(serverId, filePath, tempFile)
                             tempFile
                        } else {
                            File(filePath)
                        }

                        val unzipDir = File(cacheDir, "epub_${epubFile.name}_unzipped")
                        if (unzipDir.exists()) unzipDir.deleteRecursively()
                        EpubParser.unzip(epubFile, unzipDir)

                        val book = EpubParser.parse(unzipDir)
                        currentBook = book
                        
                        _uiState.value = _uiState.value.copy(
                            epubChapters = book.spine,
                            currentChapterIndex = 0,
                            currentChunkIndex = 0,
                            hasMoreContent = false,
                            fileName = File(filePath).name
                        )
                        
                        loadChapter(0)

                    } else {
                        // Text Logic with Chunking
                        if (type == FileEntry.FileType.TEXT && !isWebDav) { // Local Text Only for Chunking for now
                            loadTextChunk(0)
                        } else {
                            val rawContent = if (isWebDav && serverId != null) {
                                webDavRepository.readFileContent(serverId, filePath)
                            } else {
                                fileRepository.readFileContent(filePath)
                            }
                            
                            rawContentCache = rawContent
                            processContent(rawContent, type)
                        }
                    }

                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
            }
        }
        
        private suspend fun loadTextChunk(chunkIndex: Int) {
             val (content, hasMore) = fileRepository.readLinesChunk(currentFilePath, chunkIndex * CHUNK_SIZE, CHUNK_SIZE)
             rawContentCache = content
             val processedContent = AozoraParser.wrapInHtml(
                 AozoraParser.parse(content), 
                 _uiState.value.isVertical, 
                 _uiState.value.fontFamily, 
                 _uiState.value.fontSize, 
                 _uiState.value.docBackgroundColor
             )
             
             _uiState.value = _uiState.value.copy(
                 content = processedContent,
                 isLoading = false,
                 totalLines = content.lines().size,
                 currentLine = 1,
                 currentChunkIndex = chunkIndex,
                 hasMoreContent = hasMore,
                 url = null,
                 fileName = File(currentFilePath).name
             )
        }
        
        fun nextChunk() {
            if (_uiState.value.hasMoreContent) {
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                    loadTextChunk(_uiState.value.currentChunkIndex + 1)
                }
            }
        }
        
        fun prevChunk() {
            if (_uiState.value.currentChunkIndex > 0) {
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                    loadTextChunk(_uiState.value.currentChunkIndex - 1)
                }
            }
        }

        private fun processContent(rawContent: String, type: FileEntry.FileType) {
             val processedContent = when (type) {
                FileEntry.FileType.TEXT -> {
                    val htmlBody = AozoraParser.parse(rawContent)
                    AozoraParser.wrapInHtml(htmlBody, _uiState.value.isVertical, _uiState.value.fontFamily, _uiState.value.fontSize, _uiState.value.docBackgroundColor)
                }
                FileEntry.FileType.HTML -> rawContent
                else -> "Unsupported format"
            }

            _uiState.value = _uiState.value.copy(
                content = processedContent,
                url = null,
                isLoading = false,
                totalLines = if (type == FileEntry.FileType.TEXT) rawContent.lines().size else 0,
                currentLine = 1,
                hasMoreContent = false,
                currentChunkIndex = 0,
                fileName = File(currentFilePath).name
            )
        }

        fun toggleBookmark(path: String, line: Int, isWebDav: Boolean, serverId: Int?, type: String) {
            viewModelScope.launch {
                val title = File(path).name
                bookmarkDao.insertBookmark(
                    com.uviewer_android.data.Bookmark(
                        title = title,
                        path = path,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = type,
                        position = line,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        fun loadChapter(index: Int) {
            val chapters = _uiState.value.epubChapters
            if (index in chapters.indices) {
                val chapter = chapters[index]
                val chapterFile = File(chapter.href)
                _uiState.value = _uiState.value.copy(
                    url = "file://${chapterFile.absolutePath}",
                    content = "",
                    currentChapterIndex = index,
                    isLoading = false
                )
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

        fun toggleVerticalMode() {
            val newVertical = !_uiState.value.isVertical
            _uiState.value = _uiState.value.copy(isVertical = newVertical)
            
            if (rawContentCache != null && currentFileType == FileEntry.FileType.TEXT) {
                val htmlBody = AozoraParser.parse(rawContentCache!!)
                val processedContent = AozoraParser.wrapInHtml(htmlBody, newVertical, _uiState.value.fontFamily, _uiState.value.fontSize, _uiState.value.docBackgroundColor)
                _uiState.value = _uiState.value.copy(content = processedContent)
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
    }
