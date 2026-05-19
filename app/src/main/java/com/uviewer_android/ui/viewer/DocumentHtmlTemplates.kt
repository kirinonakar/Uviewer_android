package com.uviewer_android.ui.viewer

internal object DocumentHtmlTemplates {
    fun textHtmlResetCss(
        uiState: DocumentViewerUiState,
        colors: Pair<String, String>,
        fontFamily: String
    ): String {
        return """
            <style>
                /* 모든 요소 box-sizing 적용 */
                * {
                    box-sizing: border-box !important;
                }

                html {
                    width: 100vw !important;
                    height: 100vh !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    overflow-x: ${if (uiState.isVertical) "scroll" else "hidden"} !important;
                    overflow-y: ${if (uiState.isVertical) "hidden" else "scroll"} !important;
                    overscroll-behavior: none !important;
                    touch-action: ${if (uiState.isVertical) "pan-x" else "pan-y"} !important;
                    overflow-anchor: none !important;

                    /* [추가] 뷰포트 스크롤 좌표계를 위해 html에도 writing-mode 적용 */
                    writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                    -webkit-writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                }

                body {
                    /* 세로쓰기 설정 */
                    writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                    -webkit-writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;

                    height: 100vh !important;
                    min-height: 100vh !important;
                    width: ${if (uiState.isVertical) "auto" else "100%"} !important;

                    margin: 0 !important;
                    padding: 0 !important;

                    overflow: visible !important;
                    overflow-anchor: none !important;

                    background-color: ${colors.first} !important;
                    color: ${colors.second} !important;
                    font-family: $fontFamily !important;
                    font-size: ${uiState.fontSize}px !important;
                    line-height: 1.8 !important;

                    /* 안전 영역 설정 (여백은 문단으로 이동) */
                    padding-top: env(safe-area-inset-top, 0) !important;
                    padding-bottom: env(safe-area-inset-bottom, 0) !important;
                    padding-left: 0 !important;
                    padding-right: 0 !important;
                    display: block !important;
                }

                /* [문제 해결의 핵심] 문단(div, p)의 높이를 100%로 강제해야 함. */
                p, div, article, section, h1, h2, h3, h4, h5, h6 {
                    display: block !important;
                    height: auto !important;
                    width: auto !important;
                    margin-top: 0 !important;
                    margin-bottom: ${if (uiState.isVertical) "0" else "0.5em"} !important;
                    margin-left: ${if (uiState.isVertical) "1em" else "0"} !important;

                    padding-left: ${if (uiState.isVertical) "1.2em" else "${uiState.sideMargin / 20.0}em"} !important;
                    padding-right: ${if (uiState.isVertical) "1.2em" else "${uiState.sideMargin / 20.0}em"} !important;
                    padding-top: ${if (uiState.isVertical) "1.2em" else "0"} !important;
                    padding-bottom: ${if (uiState.isVertical) "1.2em" else "0"} !important;

                    white-space: normal !important;
                    overflow-wrap: break-word !important;
                    box-sizing: border-box !important;
                    text-align: left !important;
                }
                .content-chunk {
                    display: flow-root !important;
                    overflow-anchor: none !important;
                }

                img {
                    max-width: 100% !important;
                    max-height: 100% !important;
                    width: auto !important;
                    height: auto !important;
                    display: block !important;
                    margin: 1em auto !important;
                }
                /* Support for tables in small screens */
                table {
                    width: 100% !important;
                    table-layout: fixed !important;
                    border-collapse: collapse !important;
                    margin: 1em 0 !important;
                    display: table !important; /* Ensure it's not block to respect table-layout */
                }
                th, td {
                    border: 1px solid #888 !important;
                    padding: 8px !important;
                    white-space: normal !important;
                    word-wrap: break-word !important;
                    overflow-wrap: break-word !important;
                    vertical-align: top !important;
                }
                rt {
                    font-size: 0.5em !important;
                    text-align: center !important;
                }
                .ruby-wide {
                    margin-left: -0.3em !important;
                    margin-right: -0.3em !important;
                }
                .ruby-wide span {
                    display: inline-block !important;
                    transform: scaleX(0.75) !important;
                    transform-origin: center bottom !important;
                    white-space: nowrap !important;
                }
                /* Hide potentially problematic layout elements */
                /* Ensure code blocks wrap properly */
                pre, code {
                    white-space: pre-wrap !important;
                    word-break: break-all !important;
                    overflow-wrap: break-word !important;
                }
                /* Hide potentially problematic layout elements */
                iframe, script, noscript, style:not([data-app-style]) { display: none !important; }
            </style>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
        """.trimIndent()
    }

    fun wrapRawHtmlChunk(chunkText: String, languageTag: String, resetCss: String): String {
        return """
            <!DOCTYPE html>
            <html lang="$languageTag">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
                $resetCss
            </head>
            <body>
                $chunkText
            </body>
            </html>
        """.trimIndent()
    }

    fun epubChapterResetCss(
        uiState: DocumentViewerUiState,
        colors: Pair<String, String>,
        fontFamily: String
    ): String {
        return """
            <style data-app-style="true">
                *:not(ruby):not(rt):not(rp) {
                    max-width: 100% !important;
                    box-sizing: border-box !important;
                    overflow-wrap: break-word !important;
                }
                html {
                    width: 100vw !important;
                    height: 100vh !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    overflow-x: ${if (uiState.isVertical) "auto" else "hidden"} !important;
                    overflow-y: ${if (uiState.isVertical) "hidden" else "auto"} !important;
                    overflow-anchor: none !important;
                    writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                    -webkit-writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                }
                body {
                    width: ${if (uiState.isVertical) "auto" else "100%"} !important;
                    height: 100vh !important;
                    min-height: 100vh !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    background-color: ${colors.first} !important;
                    color: ${colors.second} !important;
                    font-family: $fontFamily !important;
                    font-size: ${uiState.fontSize}px !important;
                    writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                    -webkit-writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                    text-orientation: mixed !important;
                    overflow: visible !important;
                    overflow-anchor: none !important;
                    padding-top: env(safe-area-inset-top, 0) !important;
                    padding-bottom: env(safe-area-inset-bottom, 0) !important;
                    padding-left: 0 !important;
                    padding-right: 0 !important;
                    line-height: 1.8 !important;
                }
                rt {
                    font-size: 0.5em !important;
                    text-align: center !important;
                }
                .ruby-wide {
                    margin-left: -0.3em !important;
                    margin-right: -0.3em !important;
                }
                .ruby-wide span {
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
                /* Table wrapping support */
                table {
                    width: 100% !important;
                    table-layout: fixed !important;
                    border-collapse: collapse !important;
                    margin: 1em 0 !important;
                }
                p, div, article, section, h1, h2, h3, h4, h5, h6 {
                    display: block !important;
                    height: auto !important;
                    width: auto !important;
                    margin-top: 0 !important;
                    margin-bottom: ${if (uiState.isVertical) "0" else "0.5em"} !important;
                    margin-left: ${if (uiState.isVertical) "1em" else "0"} !important;
                    padding-left: ${if (uiState.isVertical) "1.2em" else "${uiState.sideMargin / 20.0}em"} !important;
                    padding-right: ${if (uiState.isVertical) "1.2em" else "${uiState.sideMargin / 20.0}em"} !important;
                    white-space: normal !important;
                    overflow-wrap: break-word !important;
                    text-align: left !important;
                }
                .content-chunk {
                    display: flow-root !important;
                    overflow-anchor: none !important;
                }
                /* Remove padding for images to make them edge-to-edge */
                div:has(img), p:has(img), div:has(svg), p:has(svg), div:has(figure), p:has(figure), .image-page-wrapper {
                    padding: 0 !important;
                    margin: 0 !important;
                }
                .image-page-wrapper {
                    width: 100vw !important;
                    height: 100vh !important;
                    min-width: 100vw !important;  /* 추가: 축소 방지 */
                    min-height: 100vh !important; /* 추가: 축소 방지 */
                    flex-shrink: 0 !important;    /* 추가: flex 레이아웃에서 찌그러짐 방지 */
                    display: flex !important;
                    justify-content: center !important;
                    align-items: center !important;
                    overflow: hidden !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    box-sizing: border-box !important;
                    break-inside: avoid !important; /* 대체 속성 */
                }
                img, svg, figure {
                    max-width: 100% !important;
                    max-height: 100% !important;
                    width: auto !important;
                    height: auto !important;
                    display: block !important;
                    margin: 0 auto !important;
                    object-fit: contain !important;
                }
                th, td {
                    border: 1px solid #888 !important;
                    padding: 8px !important;
                    white-space: normal !important;
                    word-wrap: break-word !important;
                    overflow-wrap: break-word !important;
                    vertical-align: top !important;
                }
            </style>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
        """.trimIndent()
    }
}
