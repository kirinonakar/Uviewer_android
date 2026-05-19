package com.uviewer_android.ui.viewer

import com.uviewer_android.data.repository.UserPreferencesRepository

data class DocumentViewerUiState(
    val content: String = "",
    val url: String? = null,
    val baseUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isVertical: Boolean = false,
    val fontSize: Int = 18,
    val fontFamily: String = "serif",
    val docBackgroundColor: String = UserPreferencesRepository.DOC_BG_COMFORT,
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
    val loadProgress: Float = 1f,
    val contentUpdateType: Int = 0,
    val appendTrigger: Long = 0L,
    val chapterLineCounts: Map<Int, Int> = emptyMap(),
    val isImageOnlyChapter: Boolean = false,
    val jsUnlockTrigger: Long = 0L,
    val language: String = UserPreferencesRepository.LANG_EN,
    val searchState: ViewerTextSearchState = ViewerTextSearchState()
)
