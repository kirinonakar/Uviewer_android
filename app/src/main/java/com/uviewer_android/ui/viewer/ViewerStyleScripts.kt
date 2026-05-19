package com.uviewer_android.ui.viewer

internal object ViewerStyleScripts {
    fun getStyleSheet(
        isVertical: Boolean,
        bgColor: String,
        textColor: String,
        fontFamily: String,
        fontSize: Int,
        sideMargin: Int
    ): String {
        return """
            <style>
                /* 1. 스크롤 방향 강제 및 오버스크롤(튕김) 방지 */
                html {
                    width: ${if (isVertical) "auto" else "100vw"} !important;
                    height: ${if (isVertical) "100vh" else "auto"} !important;
                    min-width: 100vw !important;
                    min-height: 100vh !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    /* 세로쓰기일 땐 X축만, 가로쓰기일 땐 Y축만 허용 */
                    overflow-x: ${if (isVertical) "scroll" else "hidden"} !important;
                    overflow-y: ${if (isVertical) "hidden" else "scroll"} !important;
                    /* 튕김 효과 방지 */
                    overscroll-behavior: none !important;
                    /* 터치 동작 제한 (세로쓰기면 좌우 스크롤만 허용) */
                    touch-action: ${if (isVertical) "pan-x" else "pan-y"} !important;
                    overflow-anchor: none !important;
                    
                    /* [중요] html에도 writing-mode 적용하여 브라우저 좌표축 동기화 */
                    writing-mode: ${if (isVertical) "vertical-rl" else "horizontal-tb"} !important;
                    -webkit-writing-mode: ${if (isVertical) "vertical-rl" else "horizontal-tb"} !important;
                }
            
                 body {
                     min-width: 100vw !important;
                     min-height: 100vh !important;
                     height: ${if (isVertical) "100vh" else "auto"} !important; /* 가로쓰기 시 auto로 두어야 무한 세로 스크롤 가능 */
                     width: ${if (isVertical) "auto" else "100%"} !important; /* 세로쓰기 시 auto로 두어야 무한 가로 스크롤 가능 */
                     margin: 0 !important;
                     padding: 0 !important; /* body 패딩 제거 (좌표 계산 오차 원인) */
                     padding-left: 0 !important;
                     padding-right: 0 !important;
                     
                     background-color: $bgColor !important;
                     color: $textColor !important;
                     
                     writing-mode: ${if (isVertical) "vertical-rl" else "horizontal-tb"} !important;
                     -webkit-writing-mode: ${if (isVertical) "vertical-rl" else "horizontal-tb"} !important;
                     
                     font-family: $fontFamily !important;
                     font-size: ${fontSize}px !important;
                     line-height: 1.8 !important;
                     
                     /* 안전 영역 패딩 */
                     padding-top: env(safe-area-inset-top, 0) !important;
                     padding-bottom: env(safe-area-inset-bottom, 0) !important;
                     overflow-anchor: none !important;
                 }
                 
                 /* 2. 문단 설정: 여백이 터치 감지를 방해하지 않도록 조정 */
                 p, div, h1, h2, h3, h4, h5, h6 {
                     display: block !important; 
                     height: auto !important;
                     width: auto !important;
                     margin-top: 0 !important;
                     /* 줄 간격 */
                     margin-bottom: ${if (isVertical) "0" else "0.5em"} !important;
                     margin-left: ${if (isVertical) "0.4em" else "0"} !important;
                     
                     /* 전체 여백 적용 */
                     padding-left: ${if (isVertical) "0.3em" else "${sideMargin / 20.0}em"} !important;
                     padding-right: ${if (isVertical) "0.3em" else "${sideMargin / 20.0}em"} !important;
                     padding-top: ${if (isVertical) "${sideMargin / 20.0}em" else "0"} !important;
                     padding-bottom: ${if (isVertical) "${sideMargin / 20.0}em" else "0"} !important;
                     
                      box-sizing: border-box !important;
                      text-align: left !important;
                  }
                   .content-chunk {
                       overflow-anchor: none !important;
                       margin: 0 !important;
                       padding: 0 !important;
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
                img[style*="display: none"] {
                     margin: 0 !important;
                     height: 0 !important;
                 }
                mark {
                    background-color: rgba(255, 221, 87, 0.58) !important;
                    color: inherit !important;
                    border-radius: 0.12em !important;
                    padding: 0 0.08em !important;
                }
                /* Table wrapping support */
                table {
                    width: 100% !important;
                    table-layout: fixed !important;
                    border-collapse: collapse !important;
                    margin: 1em 0 !important;
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

                div[id^="line-"] {
                    break-inside: avoid !important;
                }
                 .blank-line {
                     margin: 0 !important;
                     padding: 0 !important;
                     width: ${if (isVertical) "0.2em" else "auto"} !important;
                     min-width: ${if (isVertical) "0.2em" else "auto"} !important;
                     height: ${if (isVertical) "auto" else "0.2em"} !important;
                     min-height: ${if (isVertical) "auto" else "0.2em"} !important;
                     display: block !important;
                 }
               .tcy { text-combine-upright: all !important; -webkit-text-combine: horizontal !important; }
            </style>
        """.trimIndent()
    }
}
