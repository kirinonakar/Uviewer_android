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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DocumentViewerUiState(
    val content: String = "", // HTML or Text content
    val url: String? = null, // URL to load
    val isLoading: Boolean = false,
    val error: String? = null,
    val isVertical: Boolean = false,
    val fontSize: Int = 18,
    val fontType: String = "serif",
    val epubChapters: List<com.uviewer_android.data.model.EpubSpineItem> = emptyList(),
    val currentChapterIndex: Int = -1
)

class DocumentViewerViewModel(
    application: Application,
    private val fileRepository: FileRepository,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao,
    private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DocumentViewerUiState())
    val uiState: StateFlow<DocumentViewerUiState> = _uiState.asStateFlow()

    private var rawContentCache: String? = null
    private var currentFileType: FileEntry.FileType? = null
    private var currentBook: com.uviewer_android.data.model.EpubBook? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                userPreferencesRepository.fontSize,
                userPreferencesRepository.fontFamily
            ) { size, family ->
                 Pair(size, family)
            }.collect { (size, family) ->
                _uiState.value = _uiState.value.copy(
                    fontSize = size,
                    fontType = family
                )
                if (rawContentCache != null && currentFileType == FileEntry.FileType.TEXT) {
                    val htmlBody = AozoraParser.parse(rawContentCache!!)
                    val processedContent = AozoraParser.wrapInHtml(htmlBody, _uiState.value.isVertical, family, size)
                    _uiState.value = _uiState.value.copy(content = processedContent)
                }
            }
        }
    }

    fun loadDocument(filePath: String, type: FileEntry.FileType, isWebDav: Boolean, serverId: Int?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            currentFileType = type
            
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
                        currentChapterIndex = 0
                    )
                    
                    loadChapter(0)

                } else {
                    val rawContent = if (isWebDav && serverId != null) {
                        webDavRepository.readFileContent(serverId, filePath)
                    } else {
                        fileRepository.readFileContent(filePath)
                    }
                    
                    rawContentCache = rawContent

                    val processedContent = when (type) {
                        FileEntry.FileType.TEXT -> {
                            val htmlBody = AozoraParser.parse(rawContent)
                            AozoraParser.wrapInHtml(htmlBody, _uiState.value.isVertical, _uiState.value.fontType, _uiState.value.fontSize)
                        }
                        else -> "Unsupported format"
                    }

                    _uiState.value = _uiState.value.copy(
                        content = processedContent,
                        url = null,
                        isLoading = false
                    )
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
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
            val processedContent = AozoraParser.wrapInHtml(htmlBody, newVertical, _uiState.value.fontType, _uiState.value.fontSize)
            _uiState.value = _uiState.value.copy(content = processedContent)
        }
    }
}
