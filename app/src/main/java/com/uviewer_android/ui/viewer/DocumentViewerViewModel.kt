package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.parser.EpubParser
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.Charset


class DocumentViewerViewModel(
    application: Application,
    private val fileRepository: FileRepository,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao,
    private val bookmarkDao: com.uviewer_android.data.BookmarkDao,
    private val favoriteDao: com.uviewer_android.data.FavoriteDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val cacheManager: com.uviewer_android.data.utils.CacheManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DocumentViewerUiState())
    val uiState: StateFlow<DocumentViewerUiState> = _uiState.asStateFlow()

    // Expose flows for FontSettingsDialog
    val fontSize = userPreferencesRepository.fontSize
    val fontFamily = userPreferencesRepository.fontFamily
    val docBackgroundColor = userPreferencesRepository.docBackgroundColor
    val sideMargin = userPreferencesRepository.sideMargin
    val isVerticalReading = userPreferencesRepository.isVerticalReading

    // Cache for raw content to re-process (e.g. toggle vertical)
    private var largeTextReader: com.uviewer_android.data.utils.LargeTextReader? = null
    companion object {
        const val LINES_PER_CHUNK = 2000
    }
    private var loadedStartChunk = 0
    private var loadedEndChunk = 0
    private var loadedStartChapter = 0
    private var loadedEndChapter = 0
    private var currentFilePath: String = ""
    private var currentFileType: FileEntry.FileType? = null
    private var isWebDavContext: Boolean = false
    private var serverIdContext: Int? = null

    // EPUB 세로모드용: Aozora처럼 플랫 텍스트로 변환한 캐시
    private var epubFlatTextLines: List<String>? = null
    private var epubChapterStartLines: Map<Int, Int> = emptyMap() // chapterIndex -> startLine (1-based)
    private var epubUnzipDir: File? = null
    private var epubBook: com.uviewer_android.data.model.EpubBook? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                listOf(
                    userPreferencesRepository.fontSize,
                    userPreferencesRepository.fontFamily,
                    userPreferencesRepository.docBackgroundColor,
                    userPreferencesRepository.customDocBackgroundColor,
                    userPreferencesRepository.customDocTextColor,
                    userPreferencesRepository.sideMargin,
                    userPreferencesRepository.isVerticalReading,
                    userPreferencesRepository.appLanguage
                )
            ) { args: Array<Any> ->
                val size = args[0] as Int
                val family = args[1] as String
                val color = args[2] as String
                val customBg = args[3] as String
                val customText = args[4] as String
                val margin = args[5] as Int
                val vertical = args[6] as Boolean
                val lang = args[7] as String
                
                _uiState.value = _uiState.value.copy(
                    fontSize = size,
                    fontFamily = family,
                    docBackgroundColor = color,
                    customDocBackgroundColor = customBg,
                    customDocTextColor = customText,
                    sideMargin = margin,
                    isVertical = vertical,
                    language = resolveLanguageTag(lang)
                )
                if (currentFileType == FileEntry.FileType.TEXT && largeTextReader != null) {
                    loadTextChunk(_uiState.value.currentChunkIndex)
                } else if (currentFileType == FileEntry.FileType.EPUB && _uiState.value.epubChapters.isNotEmpty()) {
                    // EPUB: 플랫 텍스트 파이프라인 재생성 (가로/세로 공용)
                    if (epubBook != null) {
                        val book = epubBook!!
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                            val (flatLines, chapterStarts, _) = EpubParser.extractFlatContent(book.spine, _uiState.value.isVertical)
                            epubFlatTextLines = flatLines
                            epubChapterStartLines = chapterStarts
                            val savedLine = _uiState.value.currentLine
                            val chunkIdx = (savedLine - 1) / LINES_PER_CHUNK
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(
                                    totalLines = flatLines.size,
                                    currentChunkIndex = chunkIdx
                                )
                                loadedStartChunk = chunkIdx
                                loadedEndChunk = chunkIdx
                                loadEpubFlatChunk(chunkIdx, updateType = 0)
                            }
                        }
                    }
                }
            }.collect {}

        }
    }

    fun loadDocument(filePath: String, type: FileEntry.FileType, isWebDav: Boolean, serverId: Int?, initialLine: Int? = null) {
        // If already loaded the same file and no explicit line jump requested, ignore to keep current position (e.g. during theme changes)
        if (currentFilePath == filePath && _uiState.value.content.isNotEmpty() && initialLine == null) {
            return
        }

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
                    val application = getApplication<Application>()
                    val epubFile = DocumentFileResolver.resolveReadableFile(
                        application = application,
                        filePath = filePath,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        cacheManager = cacheManager,
                        webDavRepository = webDavRepository
                    )
                    val unzipDir = DocumentFileResolver.prepareEpubUnzipDir(
                        application = application,
                        epubFile = epubFile,
                        cacheManager = cacheManager
                    )
                    val book = EpubParser.parse(unzipDir)
                    epubUnzipDir = unzipDir
                    epubBook = book

                    _uiState.value = _uiState.value.copy(
                        epubChapters = book.spine,
                        currentChapterIndex = 0,
                        isLoading = false,
                        currentLine = 1
                    )

                    // [핵심 변경] 모든 챕터를 Aozora처럼 플랫 텍스트 파이프라인으로 변환하여 사용
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            val (flatLines, chapterStarts, chapterLineCounts) = EpubParser.extractFlatContent(book.spine, _uiState.value.isVertical)
                            epubFlatTextLines = flatLines
                            epubChapterStartLines = chapterStarts

                            val flatPosition = DocumentEpubFlatMapper.mapSavedPosition(
                                savedLine = savedLine,
                                flatLines = flatLines,
                                chapterStarts = chapterStarts,
                                chapterLineCounts = chapterLineCounts,
                                spine = book.spine,
                                linesPerChunk = LINES_PER_CHUNK
                            )

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(
                                    totalLines = flatLines.size,
                                    currentLine = flatPosition.flatStartLine,
                                    currentChapterIndex = flatPosition.currentChapterIndex,
                                    currentChunkIndex = flatPosition.chunkIndex,
                                    epubChapters = flatPosition.tocChapters
                                )
                                loadedStartChunk = flatPosition.chunkIndex
                                loadedEndChunk = flatPosition.chunkIndex
                                loadEpubFlatChunk(flatPosition.chunkIndex, updateType = 0)
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(isLoading = false, error = "EPUB processing error: ${e.message}")
                            }
                        }
                    }
                } else {
                    // Text / HTML / CSV
                    val file = DocumentFileResolver.resolveReadableFile(
                        application = getApplication(),
                        filePath = filePath,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        cacheManager = cacheManager,
                        webDavRepository = webDavRepository
                    )

                    val reader = com.uviewer_android.data.utils.LargeTextReader(file)
                    largeTextReader = reader
                    reader.indexFile(_uiState.value.manualEncoding) { progress ->
                        _uiState.value = _uiState.value.copy(loadProgress = progress)
                    }

                    val totalLines = reader.getTotalLines()
                    
                    // Background pass to extract titles for TOC - Scan entire file in chunks
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        DocumentTextTocScanner.scan(
                            reader = reader,
                            totalLines = totalLines,
                            currentLine = { _uiState.value.currentLine },
                            onUpdate = { chapters, currentIndex ->
                                _uiState.value = _uiState.value.copy(
                                    epubChapters = chapters,
                                    currentChapterIndex = currentIndex
                                )
                            }
                        )
                    }

                    if (type == FileEntry.FileType.CSV || (type == FileEntry.FileType.TEXT && filePath.lowercase().endsWith(".csv"))) {
                        // For CSV, just load the whole thing or large chunk for table view
                        val contentString = reader.readLines(1, totalLines.coerceAtMost(2000))
                        
                         _uiState.value = _uiState.value.copy(
                            content = DocumentCsvRenderer.render(contentString),
                            isLoading = false,
                            totalLines = totalLines,
                            currentLine = 1,
                            currentChunkIndex = 0,
                            hasMoreContent = totalLines > 2000
                        )
                    } else {
                        // Text / Aozora / HTML
                        
                        val startLine = savedLine
                        val chunkIdx = (startLine - 1) / LINES_PER_CHUNK

                        // Set Base URL for WebDAV to support relative remote images
                        // [Modification] Use local cache dir as base for WebDAV to allow file:// resources
                        val baseUrl = DocumentFileResolver.baseUrlFor(file)
                        // If it's WebDAV, we still might need the remote URL for some cases, but for images and basic security, file:// base is better.
                        // val remoteBaseUrl = if (isWebDav && serverId != null) { ... } else null
                        
                        _uiState.value = _uiState.value.copy(
                            currentChunkIndex = chunkIdx,
                            totalLines = totalLines,
                            currentLine = savedLine,
                            baseUrl = baseUrl
                        )
                        loadedStartChunk = chunkIdx
                        loadedEndChunk = chunkIdx
                        loadTextChunk(chunkIdx, updateType = 0)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun updateProgress(line: Int) {
        val filePath = currentFilePath
        if (filePath.isEmpty()) return
        viewModelScope.launch {
            try {
                val saveData = DocumentProgressSaver.compute(
                    line = line,
                    filePath = filePath,
                    fileType = currentFileType,
                    uiState = _uiState.value,
                    isEpubFlat = epubFlatTextLines != null,
                    epubChapterStartLines = epubChapterStartLines
                ) ?: return@launch
                DocumentProgressSaver.update(recentFileDao, filePath, saveData)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * onDispose에서 호출: ViewModel 소멸 전에 반드시 DB에 기록을 완료해야 하므로
     * runBlocking을 사용하여 동기적으로 저장합니다.
     */
    fun saveProgressBlocking(line: Int) {
        val filePath = currentFilePath
        if (filePath.isEmpty()) return
        try {
            val saveData = DocumentProgressSaver.compute(
                line = line,
                filePath = filePath,
                fileType = currentFileType,
                uiState = _uiState.value,
                isEpubFlat = epubFlatTextLines != null,
                epubChapterStartLines = epubChapterStartLines
            ) ?: return
            DocumentProgressSaver.updateBlocking(recentFileDao, filePath, saveData)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setCurrentLine(line: Int) {
        val chapters = _uiState.value.epubChapters
        // EPUB: 챕터 인덱스를 라인 번호에서 추적 (가로/세로 공용 플랫 파이프라인)
        val isEpubFlat = currentFileType == FileEntry.FileType.EPUB && epubFlatTextLines != null
        if ((currentFileType != FileEntry.FileType.EPUB || isEpubFlat) && chapters.isNotEmpty()) {
            val index = chapters.indexOfLast { 
                it.href.startsWith("line-") && (it.href.removePrefix("line-").toIntOrNull() ?: 0) <= line 
            }.coerceAtLeast(0)
            
            if (index != _uiState.value.currentChapterIndex || line != _uiState.value.currentLine) {
                _uiState.value = _uiState.value.copy(currentLine = line, currentChapterIndex = index)
            }
        } else {
            if (line != _uiState.value.currentLine) {
                _uiState.value = _uiState.value.copy(currentLine = line)
            }
        }
    }

    fun setEpubPosition(chapterIndex: Int, line: Int) {
        // [수정] 통합된 플랫 파이프라인에서는 setCurrentLine이 대부분의 처리를 담당하지만,
        // 가로모드 호환성을 위해 남겨둡니다. (플랫 모드에선 chapterIndex 무시)
    }

    private fun loadTextChunk(chunkIndex: Int, updateType: Int = 0) {
        val reader = largeTextReader ?: return
        val startLine = chunkIndex * LINES_PER_CHUNK + 1
        val totalLines = reader.getTotalLines()
        if (startLine > totalLines) return

        viewModelScope.launch {
            if (updateType == 0 || updateType == 3) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
            if (updateType == 0) {
                loadedStartChunk = chunkIndex
                loadedEndChunk = chunkIndex
            }
            val chunkText = reader.readLines(startLine, LINES_PER_CHUNK)
            val globalLineOffset = startLine - 1
            
            val processed = DocumentChunkRenderer.renderTextChunk(
                chunkText = chunkText,
                chunkIndex = chunkIndex,
                globalLineOffset = globalLineOffset,
                filePath = currentFilePath,
                fileType = currentFileType,
                isWebDavContext = isWebDavContext,
                serverIdContext = serverIdContext,
                uiState = _uiState.value,
                languageTag = resolveLanguageTag(userPreferencesRepository.appLanguage.value),
                colors = getColors(),
                application = getApplication(),
                webDavRepository = webDavRepository
            )

            val isRefreshing = updateType == 0 || updateType == 3
            _uiState.value = _uiState.value.copy(
                content = processed,
                isLoading = false,
                currentChunkIndex = if (isRefreshing) chunkIndex else _uiState.value.currentChunkIndex,
                hasMoreContent = (startLine + LINES_PER_CHUNK) <= totalLines,
                currentLine = if (isRefreshing) _uiState.value.currentLine else _uiState.value.currentLine, // Keep existing if not refreshing
                contentUpdateType = updateType,
                appendTrigger = System.currentTimeMillis(),
                isImageOnlyChapter = false
            )
        }
    }

    /**
     * EPUB 세로모드 전용: 플랫 텍스트를 Aozora 파서로 처리하여 HTML 청크를 생성합니다.
     * loadTextChunk()과 동일한 구조이지만, LargeTextReader 대신 epubFlatTextLines를 사용합니다.
     */
    private fun loadEpubFlatChunk(chunkIndex: Int, updateType: Int = 0) {
        val flatLines = epubFlatTextLines ?: return
        val startLine = chunkIndex * LINES_PER_CHUNK + 1
        val totalLines = flatLines.size
        if (startLine > totalLines) return

        viewModelScope.launch {
            if (updateType == 0 || updateType == 3) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
            if (updateType == 0) {
                loadedStartChunk = chunkIndex
                loadedEndChunk = chunkIndex
            }
            val endLine = (startLine + LINES_PER_CHUNK - 1).coerceAtMost(totalLines)
            val globalLineOffset = startLine - 1

            val processed = DocumentChunkRenderer.renderEpubFlatChunk(
                flatLines = flatLines,
                startLine = startLine,
                endLine = endLine,
                globalLineOffset = globalLineOffset,
                chunkIndex = chunkIndex,
                uiState = _uiState.value,
                languageTag = resolveLanguageTag(userPreferencesRepository.appLanguage.value),
                colors = getColors()
            )

            val isRefreshing = updateType == 0 || updateType == 3
            _uiState.value = _uiState.value.copy(
                content = processed,
                isLoading = false,
                currentChunkIndex = if (isRefreshing) chunkIndex else _uiState.value.currentChunkIndex,
                hasMoreContent = (startLine + LINES_PER_CHUNK) <= totalLines,
                totalLines = totalLines,
                currentLine = if (isRefreshing) _uiState.value.currentLine else _uiState.value.currentLine,
                contentUpdateType = updateType,
                appendTrigger = System.currentTimeMillis(),
                isImageOnlyChapter = false
            )
        }
    }

    private fun resolveLanguageTag(lang: String): String {
        return resolveDocumentLanguageTag(getApplication(), lang)
    }

    fun nextChunk() {
        val isEpubFlat = currentFileType == FileEntry.FileType.EPUB && epubFlatTextLines != null
        if (isEpubFlat) {
            val totalLines = epubFlatTextLines!!.size
            if ((loadedEndChunk + 1) * LINES_PER_CHUNK < totalLines) {
                loadedEndChunk++
                loadEpubFlatChunk(loadedEndChunk, updateType = 1)
            }
        } else if (_uiState.value.hasMoreContent) {
            loadedEndChunk++
            loadTextChunk(loadedEndChunk, updateType = 1) // Append
        }
    }
    
    fun prevChunk() {
        val isEpubFlat = currentFileType == FileEntry.FileType.EPUB && epubFlatTextLines != null
        if (isEpubFlat) {
            if (loadedStartChunk > 0) {
                loadedStartChunk--
                loadEpubFlatChunk(loadedStartChunk, updateType = 2)
            }
        } else if (loadedStartChunk > 0) {
            loadedStartChunk--
            loadTextChunk(loadedStartChunk, updateType = 2) // Prepend
        }
    }

    fun jumpToLine(line: Int) {
        // EPUB 세로모드(플랫): 일반 텍스트와 동일하게 라인 기반 점프
        val isEpubFlat = currentFileType == FileEntry.FileType.EPUB && epubFlatTextLines != null
        if (isEpubFlat) {
            val flatLines = epubFlatTextLines!!
            if (line < 1 || line > flatLines.size) return
            
            val targetChunk = (line - 1) / LINES_PER_CHUNK
            val chapterIdx = _uiState.value.epubChapters.indexOfLast { 
                it.href.startsWith("line-") && (it.href.removePrefix("line-").toIntOrNull() ?: 0) <= line 
            }.coerceAtLeast(0)

            loadedStartChunk = targetChunk
            loadedEndChunk = targetChunk
            
            _uiState.value = _uiState.value.copy(
                currentLine = line, 
                currentChapterIndex = chapterIdx,
                isLoading = true 
            )
            loadEpubFlatChunk(targetChunk, updateType = 3)
            return
        }

        if (currentFileType == FileEntry.FileType.EPUB) {
            if (line < 1 || line > _uiState.value.totalLines) return
            _uiState.value = _uiState.value.copy(currentLine = line)
            return
        }
        val reader = largeTextReader ?: return
        if (line < 1 || line > reader.getTotalLines()) return
        
        val targetChunk = (line - 1) / LINES_PER_CHUNK
        
        val chapterIdx = _uiState.value.epubChapters.indexOfLast { 
            it.href.startsWith("line-") && (it.href.removePrefix("line-").toIntOrNull() ?: 0) <= line 
        }.coerceAtLeast(0)

        loadedStartChunk = targetChunk
        loadedEndChunk = targetChunk
        
        _uiState.value = _uiState.value.copy(
            currentLine = line, 
            currentChapterIndex = chapterIdx,
            isLoading = true 
        )
        // [수정됨] updateType을 0(전체 리로드)에서 3(내용 교체)으로 변경하여 깜빡임 제거
        loadTextChunk(targetChunk, updateType = 3)
    }

    fun updateSearchQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchState = ViewerTextSearchState())
            return
        }

        val currentLine = _uiState.value.currentLine
        _uiState.value = _uiState.value.copy(
            searchState = _uiState.value.searchState.copy(
                query = query,
                isSearching = true
            )
        )

        searchJob = viewModelScope.launch {
            delay(250)
            val matches = kotlinx.coroutines.withContext(Dispatchers.Default) {
                findDocumentSearchMatches(query)
            }
            val startIndex = matches.indexOfFirst { it.line >= currentLine }
                .let { if (it >= 0) it else if (matches.isNotEmpty()) 0 else -1 }

            _uiState.value = _uiState.value.copy(
                searchState = ViewerTextSearchState(
                    query = query,
                    matches = matches,
                    currentIndex = startIndex,
                    isSearching = false
                )
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(searchState = ViewerTextSearchState())
    }

    fun nextSearchMatch() {
        val state = _uiState.value.searchState
        _uiState.value = _uiState.value.copy(
            searchState = state.copy(
                currentIndex = ViewerSearchUtils.nextIndex(state.currentIndex, state.matches.size)
            )
        )
    }

    fun previousSearchMatch() {
        val state = _uiState.value.searchState
        _uiState.value = _uiState.value.copy(
            searchState = state.copy(
                currentIndex = ViewerSearchUtils.previousIndex(state.currentIndex, state.matches.size)
            )
        )
    }

    fun getCurrentMatchRelativeIndex(): Int {
        val searchState = _uiState.value.searchState
        val currentMatch = searchState.currentMatch ?: return -1

        val startLine = loadedStartChunk * LINES_PER_CHUNK + 1
        val endLine = (loadedEndChunk + 1) * LINES_PER_CHUNK

        val loadedMatches = searchState.matches.filter { it.line in startLine..endLine }
        return loadedMatches.indexOf(currentMatch)
    }

    private suspend fun findDocumentSearchMatches(query: String): List<ViewerTextSearchMatch> {
        return DocumentSearchEngine.findMatches(
            query = query,
            filePath = currentFilePath,
            fileType = currentFileType,
            reader = largeTextReader,
            epubFlatTextLines = epubFlatTextLines
        )
    }

    fun toggleBookmark(path: String, line: Int, isWebDav: Boolean, serverId: Int?, type: String) {
        viewModelScope.launch {
            DocumentBookmarkSaver.save(
                bookmarkDao = bookmarkDao,
                favoriteDao = favoriteDao,
                path = path,
                line = line,
                isWebDav = isWebDav,
                serverId = serverId,
                type = type,
                uiState = _uiState.value,
                epubChapterStartLines = epubChapterStartLines,
                isEpubFlat = epubFlatTextLines != null
            )
        }
    }

    fun loadChapter(index: Int, initialLine: Int = 1, updateType: Int = 0, isBackground: Boolean = false) {
        val chapters = _uiState.value.epubChapters
        if (index in chapters.indices) {
            val chapter = chapters[index]
            
            if (currentFileType == FileEntry.FileType.EPUB) {
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                    if (updateType == 0) {
                        loadedStartChapter = index
                        loadedEndChapter = index
                    }
                    val chapterContent = DocumentEpubChapterRenderer.render(
                        chapterHref = chapter.href,
                        chapterIndex = index,
                        uiState = _uiState.value,
                        colors = getColors()
                    )
                    
                    val newCounts = _uiState.value.chapterLineCounts.toMutableMap()
                    newCounts[index] = chapterContent.lineCount

                    // [수정됨] 이미지만 있는 챕터 판별: 텍스트 본문(<p>)이 거의 없고 이미지 래퍼가 존재하는 경우
                    // 기존 15라인 이하 기준을 3라인 이하로 엄격하게 줄여 '짧은 텍스트 챕터'가 이미지 챕터로 오인되는 것을 방지
                    val isImageOnly = chapterContent.isImageOnly
                    
                    // [핵심 추가] 세로쓰기(RTL) 모드에서는 챕터 전환 시 항상 '전체 리로드(Refresh)'를 수행하도록 변경 (사용자 요청)
                    // 복잡한 세로 레이아웃 병합 시 발생하는 스크롤 계산 오류 및 건너뜀 버그를 근본적으로 방지합니다.
                    val isVerticalMode = _uiState.value.isVertical

                    // [핵심 수정] 백그라운드 자동 로딩인데 대상이 이미지 챕터이거나 '세로모드'인 경우
                    // 화면 리로드가 발생해 현재 글을 덮어씌우는 버그를 막기 위해 로딩을 취소하고 롤백합니다.
                    if (isBackground && (isImageOnly || isVerticalMode)) {
                        if (updateType == 1) loadedEndChapter--
                        if (updateType == 2) loadedStartChapter++
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            jsUnlockTrigger = System.currentTimeMillis() // JS 락 해제 트리거
                        )
                        return@launch
                    }

                    // [핵심 수정] 전체 버퍼 내용이 아닌 수치화된 플래그를 사용하여 이전 챕터의 이미지 여부를 정확히 판단
                    val wasImageOnly = _uiState.value.isImageOnlyChapter

                    // [수정됨] 이미지만 있는 챕터, 직전이 이미지였던 경우, 또는 세로모드인 경우 전체 리로드(0) 수행
                    val effectiveUpdateType = if (isImageOnly || wasImageOnly || isVerticalMode) 0 else updateType

                    // 강제 새로고침이 발생하면 이어서 붙여오던 청크(Chunk) 인덱스 기록을 초기화
                    if (effectiveUpdateType == 0 && updateType != 0) {
                        loadedStartChapter = index
                        loadedEndChapter = index
                    }

                    val isRefreshing = effectiveUpdateType == 0
                    _uiState.value = _uiState.value.copy(
                        url = null, 
                        baseUrl = chapterContent.baseUrl,
                        content = chapterContent.content,
                        currentChapterIndex = if (isRefreshing) index else _uiState.value.currentChapterIndex,
                        isLoading = false,
                        currentLine = if (isRefreshing) (if (initialLine == -1) chapterContent.lineCount else initialLine) else _uiState.value.currentLine,
                        totalLines = if (isRefreshing) chapterContent.lineCount else _uiState.value.totalLines,
                        contentUpdateType = effectiveUpdateType, 
                        appendTrigger = System.currentTimeMillis(),
                        chapterLineCounts = newCounts,
                        isImageOnlyChapter = isImageOnly || isVerticalMode
                    )

                    // [수정] 짧은 텍스트 챕터(예: 한 페이지가 채 안 되는 10라인 이하) 로드 시, 
                    // 이미지가 없는 순수 텍스트라면 사용자 편의를 위해 다음 챕터를 자동으로 미리 로드하여 이어서 보여줍니다.
                    // 핵심 수정: updateType이 아닌 effectiveUpdateType == 0을 확인하여, 
                    // 이미지 챕터(1) 직후에 짧은 텍스트 챕터(2)가 떴을 때 즉시 텍스트 챕터(3)를 이어서 붙이도록 처리
                    /* if (effectiveUpdateType == 0 && lineCount > 0 && lineCount <= 10 && !isImageOnly && index + 1 < _uiState.value.epubChapters.size) {
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(300) // 초기 렌더링 안정화 대기
                            nextChapter(isBackground = true) // [수정] 명시적으로 백그라운드 플래그 전달
                        }
                    } */
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

    fun jumpToChapter(index: Int) {
        // EPUB 세로모드(플랫): 라인 기반 점프로 리다이렉트
        val isEpubFlat = currentFileType == FileEntry.FileType.EPUB && _uiState.value.isVertical && epubFlatTextLines != null
        if (isEpubFlat) {
            val targetLine = epubChapterStartLines[index] ?: 1
            jumpToLine(targetLine)
            return
        }
        loadedStartChapter = index
        loadedEndChapter = index
        _uiState.value = _uiState.value.copy(
            currentLine = 1, 
            currentChapterIndex = index, 
            isLoading = true
        )
        loadChapter(index, initialLine = 1, updateType = 0)
    }

    fun nextChapter(isBackground: Boolean = false) {
        // EPUB 세로모드(플랫): 청크 기반 이동으로 리다이렉트
        val isEpubFlat = currentFileType == FileEntry.FileType.EPUB && _uiState.value.isVertical && epubFlatTextLines != null
        if (isEpubFlat) {
            nextChunk()
            return
        }
        if (loadedEndChapter + 1 < _uiState.value.epubChapters.size) {
            loadedEndChapter++
            loadChapter(loadedEndChapter, updateType = 1, isBackground = isBackground)
        }
    }

    fun prevChapter(isBackground: Boolean = false) {
        // EPUB 세로모드(플랫): 청크 기반 이동으로 리다이렉트
        val isEpubFlat = currentFileType == FileEntry.FileType.EPUB && _uiState.value.isVertical && epubFlatTextLines != null
        if (isEpubFlat) {
            prevChunk()
            return
        }
        if (loadedStartChapter > 0) {
            loadedStartChapter--
            // [수정됨] 전체 리로드(0)로 강제 전환될 경우를 대비해 이전 챕터의 마지막 라인으로 이동하도록 -1 전달
            loadChapter(loadedStartChapter, initialLine = -1, updateType = 2, isBackground = isBackground)
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

    fun toggleVerticalReading() {
        viewModelScope.launch {
            userPreferencesRepository.setIsVerticalReading(!_uiState.value.isVertical)
        }
    }

    fun setManualEncoding(encoding: String?, isWebDav: Boolean, serverId: Int?) {
        _uiState.value = _uiState.value.copy(manualEncoding = encoding)
        // Reload
        if (currentFilePath.isNotEmpty() && currentFileType != null) {
            val currentLine = _uiState.value.currentLine
            loadDocument(currentFilePath, currentFileType!!, isWebDav, serverId, initialLine = currentLine)
        }
    }

    private fun getColors(): Pair<String, String> {
        return _uiState.value.documentColors()
    }

}

