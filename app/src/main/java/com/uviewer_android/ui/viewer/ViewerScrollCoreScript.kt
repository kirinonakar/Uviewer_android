package com.uviewer_android.ui.viewer

internal object ViewerScrollCoreScript {
    fun install(targetLine: Int, totalLines: Int, linePrefix: String): String {
        return """
                 // 1. 시스템(JS) 스크롤과 유저 스크롤을 구분하기 위한 락(Lock) 변수
                 window.isSystemScrolling = true;
                 window.sysScrollTimer = null;
                 
                 // 브라우저의 강제 스크롤 복원 기능 비활성화
                 if ('scrollRestoration' in history) {
                     history.scrollRestoration = 'manual';
                 }

                 // 2. JS가 강제로 스크롤을 조작할 때 사용할 안전한 래퍼 함수
                 window.safeScrollBy = function(x, y) {
                     window.isSystemScrolling = true; 
                     window.scrollBy(x, y);
                     
                     if (window.sysScrollTimer) clearTimeout(window.sysScrollTimer);
                     window.sysScrollTimer = setTimeout(function() {
                         window.isSystemScrolling = false;
                     }, 250); 
                 };
                
                 // 1. Restore scroll position
                 function doInitialScroll() {
                     if ($targetLine === $totalLines && $totalLines > 1) {
                          if (typeof jumpToBottom === 'function') { jumpToBottom(); }
                          else {
                               if (isVertical) window.scrollTo(-1000000, 0); 
                               else window.scrollTo(0, 1000000);
                          }
                     } else {
                         var el = document.getElementById('line-${linePrefix}$targetLine'); 
                         if (el) {
                             el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                         } else {
                             if (isVertical) {
                                 window.scrollTo(document.documentElement.scrollWidth, 0);
                                 window.scrollTo(0, 0);
                             } else {
                                 window.scrollTo(0, 0);
                             }
                         }
                     }
                 }
                 
                 doInitialScroll();
                 setTimeout(doInitialScroll, 50);
                 setTimeout(doInitialScroll, 200);

                 if (window.sysScrollTimer) clearTimeout(window.sysScrollTimer);
                 window.sysScrollTimer = setTimeout(function() {
                     window.isSystemScrolling = false;
                 }, 500);
        """.trimIndent()
    }
}
