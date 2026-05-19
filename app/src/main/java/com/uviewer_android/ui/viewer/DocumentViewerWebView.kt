package com.uviewer_android.ui.viewer

import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.repository.UserPreferencesRepository

@Composable
fun DocumentViewerWebView(
    modifier: Modifier = Modifier,
    filePath: String,
    type: FileEntry.FileType,
    uiState: DocumentViewerUiState,
    targetDocColor: Color,
    viewModel: DocumentViewerViewModel,
    webViewRefState: MutableState<WebView?>,
    currentLineState: MutableIntState,
    pageLoadingState: MutableState<Boolean>,
    navigatingState: MutableState<Boolean>,
    isInteractingWithSlider: Boolean,
    onToggleFullScreen: () -> Unit,
    applyDocumentSearchHighlight: () -> Unit
) {
    var webViewRef by webViewRefState
    var currentLine by currentLineState
    var isPageLoading by pageLoadingState
    var isNavigating by navigatingState
    var bottomMaskHeight by remember { mutableFloatStateOf(0f) }
    var topMaskHeight by remember { mutableFloatStateOf(0f) }
    var leftMaskWidth by remember { mutableFloatStateOf(0f) }
    var rightMaskWidth by remember { mutableFloatStateOf(0f) }
    Box(modifier = modifier) {
                            AndroidView(
                         modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            object : WebView(context) {
                                fun getHorizontalScrollRangePublic(): Int = computeHorizontalScrollRange()
                            }.apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun onLineChangedStr(lineStr: String) {
                                        post {
                                            if (isPageLoading || isInteractingWithSlider) return@post
                                            
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
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) {
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
                                             if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) {
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
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) {
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
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) {
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

                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.allowViewerFileUrlAccess()
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    settings.safeBrowsingEnabled = false
                                }
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
                                isHorizontalScrollBarEnabled = uiState.isVertical
                                isVerticalScrollBarEnabled = !uiState.isVertical
                                
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val latestState = viewModel.uiState.value
                                        val targetLine = latestState.currentLine
                                        val totalLines = latestState.totalLines
                                        val enableAutoLoading = type == FileEntry.FileType.TEXT || type == FileEntry.FileType.EPUB
                                        val isVertical = latestState.isVertical

                                         val linePrefix = ""
                                         val jsScrollLogic = ViewerScripts.getScrollLogic(
                                             isVertical = isVertical,
                                             enableAutoLoading = enableAutoLoading,
                                             targetLine = targetLine,
                                             totalLines = totalLines,
                                             linePrefix = linePrefix,
                                             isImageOnly = latestState.isImageOnlyChapter
                                         )
                                        
                                        view?.evaluateJavascript(jsScrollLogic) {
                                            applyDocumentSearchHighlight()
                                            webViewRef?.postDelayed({
                                                isPageLoading = false
                                                isNavigating = false
                                            }, if (latestState.isImageOnlyChapter) 300L else 600L) // [수정됨] 이미지 챕터는 300ms, 일반은 600ms로 단축하여 반응성 개선
                                        }
                                    }
                                }
                                webViewRef = this
                                
                                val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                                        val width = width
                                        val x = e.x
                                        // Custom instant scrolling via JS to avoid animation
                                        if (viewModel.uiState.value.isVertical) {
                                            // 세로쓰기: 오른쪽 터치 = 이전(pageUp), 왼쪽 터치 = 다음(pageDown)
                                            if (x < width / 3) {
                                                webViewRef?.evaluateJavascript("window.pageDown();", null) // 앞으로
                                            } else if (x > width * 2 / 3) {
                                                webViewRef?.evaluateJavascript("window.pageUp();", null)   // 뒤로
                                            } else {
                                                onToggleFullScreen()
                                            }
                                        } else {
                                            // 가로쓰기: 왼쪽 터치 = 이전(pageUp), 오른쪽 터치 = 다음(pageDown)
                                            if (x < width / 3) {
                                                webViewRef?.evaluateJavascript("window.pageUp();", null)
                                            } else if (x > width * 2 / 3) {
                                                webViewRef?.evaluateJavascript("window.pageDown();", null)
                                            } else {
                                                onToggleFullScreen()
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
                            val currentHash = uiState.content.hashCode()
                            val previousHash = (wv.tag as? Int) ?: 0
                             
                             val (bgColor, textColor) = when (uiState.docBackgroundColor) {
            UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb" to "#322D29"
            UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#cccccc"
            UserPreferencesRepository.DOC_BG_COMFORT -> "#E9E2E4" to "#343426"
            UserPreferencesRepository.DOC_BG_CUSTOM -> uiState.customDocBackgroundColor to uiState.customDocTextColor
            else -> "#ffffff" to "#000000"
        }

val style = ViewerScripts.getStyleSheet(
    isVertical = uiState.isVertical,
    bgColor = bgColor,
    textColor = textColor,
    fontFamily = uiState.fontFamily,
    fontSize = uiState.fontSize,
    sideMargin = uiState.sideMargin
)
                                   // Inject style intelligently and prevent Quirks Mode
                                   // [추가/수정] 뷰포트 메타 태그가 있으면 교체하고, 없으면 새로 삽입하여 세로쓰기 레이아웃이 잘리지 않게 함
                                   val viewportTag = if (uiState.isVertical) {
                                       "<meta name=\"viewport\" content=\"height=device-height, initial-scale=1.0, user-scalable=yes, minimum-scale=1.0, maximum-scale=5.0\">"
                                   } else {
                                       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=yes\">"
                                   }
                                   
                                   var finalContent = uiState.content
                                   if (finalContent.contains("<meta name=\"viewport\"", ignoreCase = true)) {
                                       finalContent = finalContent.replace("<meta name=\"viewport\"[^>]*>".toRegex(RegexOption.IGNORE_CASE), viewportTag)
                                   } else if (finalContent.contains("<head>", ignoreCase = true)) {
                                       finalContent = finalContent.replace("<head>", "<head>$viewportTag", ignoreCase = true)
                                   }

                                   val contentWithStyle = if (finalContent.contains("</head>", ignoreCase = true)) {
                                       finalContent.replace("</head>", "$style</head>", ignoreCase = true)
                                   } else if (finalContent.contains("<body", ignoreCase = true)) {
                                       val match = "<body[^>]*>".toRegex(RegexOption.IGNORE_CASE).find(finalContent)
                                       if (match != null) {
                                           finalContent.substring(0, match.range.last + 1) + style + finalContent.substring(match.range.last + 1)
                                       } else {
                                           "$style$finalContent"
                                       }
                                   } else {
                                       // 태그가 없는 순수 텍스트라면, 표준 HTML 뼈대를 만들어 감싸줌 (높이 확보의 핵심)
                                       """
                                       <!DOCTYPE html>
                                       <html>
                                       <head>
                                           <meta charset="utf-8">
                                           $viewportTag
                                           <link rel="stylesheet" href="file:///android_asset/katex/katex.min.css">
                                           <script src="file:///android_asset/katex/katex.min.js"></script>
                                           <script src="file:///android_asset/katex/contrib/auto-render.min.js"></script>
                                           <script>
                                               function renderMath() {
                                                   if (typeof renderMathInElement === 'function') {
                                                       renderMathInElement(document.body, {
                                                           delimiters: [
                                                               {left: "$$", right: "$$", display: true},
                                                               {left: "$", right: "$", inline: true},
                                                               {left: "\\(", right: "\\)", inline: true},
                                                               {left: "\\[", right: "\\]", display: true}
                                                           ],
                                                           throwOnError : false
                                                       });
                                                   }
                                               }
                                               window.renderMath = renderMath;
                                               window.addEventListener('DOMContentLoaded', renderMath);
                                               setTimeout(renderMath, 100);
                                               setTimeout(renderMath, 500);
                                           </script>
                                           $style
                                       </head>
                                       <body>
                                           $finalContent
                                       </body>
                                       </html>
                                       """.trimIndent()
                                   }
      
                                   if (uiState.contentUpdateType == 0) { // 전체 리로드(점프, 최초 진입)일 때만 실행
                                       if (contentWithStyle.hashCode() != previousHash) {
                                           wv.tag = contentWithStyle.hashCode()
                                           isPageLoading = true
                                           // If navigation was triggered, ensure isNavigating lock is on
                                           isNavigating = true 
                                           
                                           if (uiState.url != null) {
                                               wv.loadUrl(uiState.url)
                                           } else {
                                               // Use provided baseUrl or fallback to parent directory of filePath
                                               val baseUrl = uiState.baseUrl ?: (if (filePath.startsWith("/")) "file:///${java.io.File(filePath).parent?.replace(java.io.File.separator, "/")}/" else null)
                                               wv.loadDataWithBaseURL(baseUrl, contentWithStyle, "text/html", "UTF-8", null)
                                           }
                                       }
                                   }
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
                                          }
}
