package com.uviewer_android.ui.viewer

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.R
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LLM_SELECTION_ACTION_ID = 0x554C4D

@Composable
internal fun DocumentViewerWebView(
    modifier: Modifier = Modifier,
    filePath: String,
    type: FileEntry.FileType,
    uiState: DocumentViewerUiState,
    targetDocColor: Color,
    viewModel: DocumentViewerViewModel,
    pageNavigation: DocumentPageNavigation,
    onPageRequested: (Boolean) -> Unit,
    webViewRefState: MutableState<WebView?>,
    currentLineState: MutableIntState,
    pageLoadingState: MutableState<Boolean>,
    navigatingState: MutableState<Boolean>,
    isInteractingWithSlider: Boolean,
    onToggleFullScreen: () -> Unit,
    applyDocumentSearchHighlight: () -> Unit,
    isScrollRestoring: () -> Boolean,
    onLlmSelected: (String) -> Unit
) {
    val viewerContext = androidx.compose.ui.platform.LocalContext.current
    val resources = remember(viewerContext.applicationContext) { ViewerResourceLoader(viewerContext.applicationContext) }
    var webViewRef by webViewRefState
    var currentLine by currentLineState
    var isPageLoading by pageLoadingState
    var isNavigating by navigatingState
    var isFirstFrameReady by remember(filePath) { mutableStateOf(false) }
    var bottomMaskHeight by remember { mutableFloatStateOf(0f) }
    var topMaskHeight by remember { mutableFloatStateOf(0f) }
    var leftMaskWidth by remember { mutableFloatStateOf(0f) }
    var rightMaskWidth by remember { mutableFloatStateOf(0f) }
    var loadedRequest by remember(resources, webViewRef, filePath) { mutableStateOf<DocumentWebPageRequest?>(null) }
    val pageRequest = DocumentWebPageRequest.from(uiState)
    LaunchedEffect(resources, webViewRef, pageRequest, uiState.contentUpdateType, uiState.isLoading) {
        val webView = webViewRef ?: return@LaunchedEffect
        if (uiState.isLoading || pageRequest.content.isEmpty() || uiState.contentUpdateType != 0) return@LaunchedEffect
        if (pageRequest == loadedRequest) return@LaunchedEffect
        pageNavigation.beginPreparation()
        isFirstFrameReady = false
        isPageLoading = true
        isNavigating = true
        // HTML styling and sanitizing are CPU work; only publish the current request after it completes.
        val prepared = withContext(Dispatchers.Default) {
            resources.buildDocument(pageRequest.styledHtml(), pageRequest.baseUrl, pageRequest.resourceRoots)
        }
        val url = resources.activateDocument(prepared, pageRequest.resourceRoots)
        loadedRequest = pageRequest
        pageNavigation.beginLoad(url)
        webView.loadUrl(url)
    }
    Box(modifier = modifier) {
                            AndroidView(
                         modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            object : WebView(context) {
                                fun getHorizontalScrollRangePublic(): Int = computeHorizontalScrollRange()

                                private fun wrapSelectionActionModeCallback(
                                    original: android.view.ActionMode.Callback
                                ): android.view.ActionMode.Callback {
                                    return object : android.view.ActionMode.Callback2() {
                                        override fun onGetContentRect(
                                            mode: android.view.ActionMode,
                                            view: android.view.View,
                                            outRect: android.graphics.Rect
                                        ) {
                                            // Preserve WebView's selection bounds so the floating
                                            // toolbar follows the text instead of the whole view.
                                            if (original is android.view.ActionMode.Callback2) {
                                                original.onGetContentRect(mode, view, outRect)
                                            } else {
                                                super.onGetContentRect(mode, view, outRect)
                                            }
                                        }

                                        override fun onCreateActionMode(
                                            mode: android.view.ActionMode?,
                                            menu: android.view.Menu?
                                        ): Boolean {
                                            // Add before the WebView's built-in actions so the
                                            // same-order item is inserted at the beginning.
                                            addLlmSelectionItem(menu)
                                            val created = original.onCreateActionMode(mode, menu)
                                            if (created) addLlmSelectionItem(menu)
                                            return created
                                        }

                                        override fun onPrepareActionMode(
                                            mode: android.view.ActionMode?,
                                            menu: android.view.Menu?
                                        ): Boolean {
                                            addLlmSelectionItem(menu)
                                            val changed = original.onPrepareActionMode(mode, menu)
                                            addLlmSelectionItem(menu)
                                            return changed
                                        }

                                        override fun onActionItemClicked(
                                            mode: android.view.ActionMode?,
                                            item: android.view.MenuItem?
                                        ): Boolean {
                                            if (item?.itemId == LLM_SELECTION_ACTION_ID) {
                                                evaluateJavascript(
                                                    "(function(){var s=window.getSelection&&window.getSelection();return s?s.toString():'';})()"
                                                ) { encoded ->
                                                    decodeSelectedText(encoded)?.let { selected ->
                                                        if (selected.isNotBlank()) onLlmSelected(selected)
                                                    }
                                                    // Read the selection before finishing the action
                                                    // mode; finishing first can clear the WebView
                                                    // selection before the JavaScript callback runs.
                                                    mode?.finish()
                                                }
                                                return true
                                            }
                                            return original.onActionItemClicked(mode, item)
                                        }

                                        override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                                            original.onDestroyActionMode(mode)
                                        }
                                    }
                                }

                                private fun addLlmSelectionItem(menu: android.view.Menu?) {
                                    if (menu?.findItem(LLM_SELECTION_ACTION_ID) == null) {
                                        menu?.add(
                                            android.view.Menu.NONE,
                                            LLM_SELECTION_ACTION_ID,
                                            0,
                                            context.getString(R.string.llm_action)
                                        )?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                                    }
                                }

                                override fun startActionMode(
                                    callback: android.view.ActionMode.Callback?,
                                    type: Int
                                ): android.view.ActionMode? {
                                    return if (callback == null) {
                                        super.startActionMode(null, type)
                                    } else {
                                        super.startActionMode(wrapSelectionActionModeCallback(callback), type)
                                    }
                                }

                                @Suppress("DEPRECATION")
                                override fun startActionMode(
                                    callback: android.view.ActionMode.Callback?
                                ): android.view.ActionMode? {
                                    return if (callback == null) {
                                        super.startActionMode(null)
                                    } else {
                                        super.startActionMode(wrapSelectionActionModeCallback(callback))
                                    }
                                }

                                override fun startActionModeForChild(
                                    child: android.view.View,
                                    callback: android.view.ActionMode.Callback?,
                                    type: Int
                                ): android.view.ActionMode? {
                                    return if (callback == null) {
                                        super.startActionModeForChild(child, null, type)
                                    } else {
                                        super.startActionModeForChild(
                                            child,
                                            wrapSelectionActionModeCallback(callback),
                                            type
                                        )
                                    }
                                }

                                @Suppress("DEPRECATION")
                                override fun startActionModeForChild(
                                    child: android.view.View,
                                    callback: android.view.ActionMode.Callback?
                                ): android.view.ActionMode? {
                                    return if (callback == null) {
                                        super.startActionModeForChild(child, null)
                                    } else {
                                        super.startActionModeForChild(
                                            child,
                                            wrapSelectionActionModeCallback(callback)
                                        )
                                    }
                                }
                            }.apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun onViewerReady(url: String) {
                                        post {
                                            if (!pageNavigation.finishInitialization(url) { forward ->
                                                evaluateJavascript(if (forward) "window.pageDown();" else "window.pageUp();", null)
                                            }) return@post
                                            isPageLoading = false
                                            isNavigating = false
                                            // Publish the first frame only after layout and the saved position agree.
                                            postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                                                override fun onComplete(requestId: Long) {
                                                    if (!pageNavigation.isCurrent(url)) return
                                                    isFirstFrameReady = true
                                                    viewModel.onFirstDocumentFrame()
                                                    applyDocumentSearchHighlight()
                                                }
                                            })
                                        }
                                    }

                                    @android.webkit.JavascriptInterface
                                    fun onLineChangedStr(lineStr: String) {
                                        post {
                                            if (isPageLoading || isInteractingWithSlider || isScrollRestoring()) return@post
                                            
                                            val ln = lineStr.toIntOrNull() ?: return@post
                                            if (ln != currentLine) {
                                                currentLine = ln
                                                viewModel.setCurrentLine(ln)
                                            }
                                        }
                                    }
                                    @android.webkit.JavascriptInterface
                                    fun autoLoadNext() {
                                        post {
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating || isScrollRestoring()) {
                                                webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                                return@post
                                            }
                                            
                                            isNavigating = true
                                            // EPUB: 청크 기반 이동 (통합됨)
                                            val isEpubFlat = type == FileEntry.FileType.EPUB
                                            if (isEpubFlat) {
                                                viewModel.nextChunk()
                                            } else if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.hasMoreContent) {
                                                viewModel.nextChunk()
                                            } else if (type == FileEntry.FileType.EPUB) {
                                                viewModel.nextChapter()
                                            } else {
                                                isNavigating = false
                                                webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                            }
                                        }
                                    }
                                     @android.webkit.JavascriptInterface
                                     fun autoLoadPrev() {
                                         post {
                                             if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating || isScrollRestoring()) {
                                                  webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                                  return@post
                                             }
                                             
                                             // EPUB: 청크 기반 이동 (통합됨)
                                             val isEpubFlat = type == FileEntry.FileType.EPUB
                                             if (isEpubFlat) {
                                                 viewModel.prevChunk()
                                             } else if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.currentChunkIndex > 0) {
                                                 viewModel.prevChunk()
                                             } else if (type == FileEntry.FileType.EPUB) {
                                                 viewModel.prevChapter()
                                             } else {
                                                  webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                             }
                                         }
                                     }

                                    // [추가] 백그라운드 자동 로딩 전용 브릿지 함수 (이미지 전용 챕터 무한 루프 방지용)
                                    @android.webkit.JavascriptInterface
                                    fun autoLoadNextBg() {
                                        post {
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating || isScrollRestoring()) {
                                                webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                                return@post
                                            }
                                            isNavigating = true
                                            val isEpubFlat = type == FileEntry.FileType.EPUB
                                            if (isEpubFlat) {
                                                viewModel.nextChunk()
                                            } else if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.hasMoreContent) {
                                                viewModel.nextChunk()
                                            } else if (type == FileEntry.FileType.EPUB) {
                                                viewModel.nextChapter(isBackground = true)
                                            } else {
                                                isNavigating = false
                                                webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                            }
                                        }
                                    }

                                    @android.webkit.JavascriptInterface
                                    fun autoLoadPrevBg() {
                                        post {
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating || isScrollRestoring()) {
                                                webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                                return@post
                                            }
                                            isNavigating = true
                                            val isEpubFlat = type == FileEntry.FileType.EPUB
                                            if (isEpubFlat) {
                                                viewModel.prevChunk()
                                            } else if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.currentChunkIndex > 0) {
                                                viewModel.prevChunk()
                                            } else if (type == FileEntry.FileType.EPUB) {
                                                viewModel.prevChapter(isBackground = true)
                                            } else {
                                                isNavigating = false
                                                webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                            }
                                        }
                                    }

                                    @android.webkit.JavascriptInterface
                                    fun updateBottomMask(height: Float) {
                                        post {
                                             bottomMaskHeight = height
                                        }
                                    }
                                    
                                    @android.webkit.JavascriptInterface
                                    fun updateTopMask(height: Float) {
                                        post {
                                             topMaskHeight = height
                                        }
                                    }

                                    @android.webkit.JavascriptInterface
                                    fun updateLeftMask(width: Float) {
                                        post {
                                             leftMaskWidth = width
                                        }
                                    }
                                    
                                    @android.webkit.JavascriptInterface
                                    fun updateRightMask(width: Float) {
                                        post {
                                             rightMaskWidth = width
                                        }
                                    }
                                }, "Android")

                                settings.configureViewerSecurity()
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
                                isHorizontalScrollBarEnabled = uiState.isVertical
                                isVerticalScrollBarEnabled = !uiState.isVertical
                                
                                webViewClient = object : ViewerWebViewClient(resources) {
                                    private fun initializePage(view: WebView?, url: String?) {
                                        if (view == null || !pageNavigation.beginInitialization(url)) return
                                        val latestState = viewModel.uiState.value
                                        val jsScrollLogic = ViewerScripts.getScrollLogic(
                                            isVertical = latestState.isVertical,
                                            enableAutoLoading = type == FileEntry.FileType.TEXT || type == FileEntry.FileType.EPUB,
                                            targetLine = latestState.currentLine,
                                            totalLines = latestState.totalLines,
                                            linePrefix = "",
                                            isImageOnly = latestState.isImageOnlyChapter
                                        )
                                        view.evaluateJavascript(DocumentViewerBootstrap.script(url!!, jsScrollLogic), null)
                                    }

                                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                                        initializePage(view, url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        initializePage(view, url)
                                    }

                                }
                                webViewRef = this

                                fun toggleFullScreenPreservingFirstLine() {
                                    evaluateJavascript(
                                        "if (typeof window.captureViewerScrollState === 'function') " +
                                            "window.captureViewerScrollState();"
                                    ) {
                                        post { onToggleFullScreen() }
                                    }
                                }
                                
                                val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                                        val width = width
                                        val x = e.x
                                        // Custom instant scrolling via JS to avoid animation
                                        if (viewModel.uiState.value.isVertical) {
                                            // 세로쓰기: 오른쪽 터치 = 이전(pageUp), 왼쪽 터치 = 다음(pageDown)
                                            if (x < width / 3) {
                                                onPageRequested(true) // 앞으로
                                            } else if (x > width * 2 / 3) {
                                                onPageRequested(false)   // 뒤로
                                            } else {
                                                toggleFullScreenPreservingFirstLine()
                                            }
                                        } else {
                                            // 가로쓰기: 왼쪽 터치 = 이전(pageUp), 오른쪽 터치 = 다음(pageDown)
                                            if (x < width / 3) {
                                                onPageRequested(false)
                                            } else if (x > width * 2 / 3) {
                                                onPageRequested(true)
                                            } else {
                                                toggleFullScreenPreservingFirstLine()
                                            }
                                        }
                                        return true
                                    }
                                    
                                    override fun onDown(e: android.view.MotionEvent): Boolean {
                                        return true
                                    }
                                })
                                
                                setOnTouchListener { _, event ->
                                    gestureDetector.onTouchEvent(event) 
                                    false
                                }
                                
                                setOnScrollChangeListener { _, _, _, _, _ ->
                                     // JS handle scroll
                                }
                            }
                        },
                        update = { webView ->
                            val wv = webView as android.webkit.WebView
                            wv.isHorizontalScrollBarEnabled = uiState.isVertical
                            wv.isVerticalScrollBarEnabled = !uiState.isVertical

                             }
                          )
                          if (bottomMaskHeight > 0f && !uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.BottomStart)
                                                                       .fillMaxWidth()
                                                                       .height(bottomMaskHeight.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
                                                           if (topMaskHeight > 0f && !uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.TopStart) // Top Mask
                                                                       .fillMaxWidth()
                                                                       .height(topMaskHeight.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
                                                           if (leftMaskWidth > 0f && uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.TopStart)
                                                                       .fillMaxHeight()
                                                                       .width(leftMaskWidth.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
                                                           if (rightMaskWidth > 0f && uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.TopEnd)
                                                                       .fillMaxHeight()
                                                                       .width(rightMaskWidth.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
                          DocumentLoadingOverlay(
                              uiState = uiState,
                              visible = uiState.isLoading || !isFirstFrameReady,
                              modifier = Modifier.background(targetDocColor)
                          )
                                          }
}

private fun decodeSelectedText(encoded: String?): String? {
    if (encoded.isNullOrBlank() || encoded == "null") return null
    return try {
        org.json.JSONArray("[$encoded]").optString(0).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        encoded.trim().trim('"').takeIf { it.isNotBlank() }
    }
}
