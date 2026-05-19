package com.uviewer_android.ui.viewer

internal object ViewerScrollPagingScript {
    fun install(): String {
        return """
                 window.jumpToBottom = function() {
                     if (isVertical) {
                         window.scrollTo(-1000000, 0);
                         var w = document.documentElement.clientWidth;
                         var lines = window.getVisualLines();
                         if (lines.length > 0) {
                             var lastLine = lines[lines.length - 1];
                             if (lastLine.left < 0) {
                                 window.scrollBy({ left: lastLine.left, behavior: 'instant' });
                             }
                         }
                     } else {
                         window.scrollTo(0, 1000000);
                         var lines = window.getVisualLines();
                         if (lines.length > 0) {
                             var lastLine = lines[lines.length - 1];
                             var h = window.innerHeight;
                             if (lastLine.bottom > h) {
                                 window.scrollBy({ top: lastLine.bottom - h, behavior: 'instant' });
                             }
                         }
                     }
                 };

                 window.pageDown = function() {
                     window._scrollDir = 1;
                     var w = isVertical ? document.documentElement.clientWidth : window.innerWidth;
                     var h = window.innerHeight;
                     var isAtBottom = false;
                     var lines = window.getVisualLines(); // 1번만 호출하여 재사용

                     if (!isVertical) {
                         if (h + window.pageYOffset >= document.documentElement.scrollHeight - 20) isAtBottom = true;
                         if (lines.length > 0) {
                             var lastVisible = lines.filter(function(l) { return l.bottom > -2 && l.top < h + 2; }).pop();
                             if (lastVisible && lines.indexOf(lastVisible) === lines.length - 1) isAtBottom = true;
                         }
                     } else {
                         var maxScrollX = Math.max(0, document.documentElement.scrollWidth - w);
                         var currentScrollX = Math.abs(window.pageXOffset);
                         var reachedScrollEnd = (currentScrollX >= (maxScrollX - 10));

                         if (lines.length > 0) {
                             var visibleLines = lines.filter(function(l) { return l.left < w && l.right > 0; });
                             if (visibleLines.length > 0) {
                                  var lastVisible = visibleLines[visibleLines.length - 1]; 
                                  if (lines.indexOf(lastVisible) === lines.length - 1) {
                                      var isLastFullyVisible = (lastVisible.left >= -5);
                                      if (reachedScrollEnd && isLastFullyVisible) {
                                          isAtBottom = true;
                                      } else {
                                          isAtBottom = false;
                                      }
                                      
                                      if (!isAtBottom && reachedScrollEnd && window._lastScrollX === window.pageXOffset) {
                                          if (lastVisible.left >= -10) isAtBottom = true;
                                      }
                                  }
                             } else {
                                 isAtBottom = reachedScrollEnd;
                             }
                         } else {
                             isAtBottom = reachedScrollEnd;
                         }
                     }

                     if (isAtBottom) { window.isScrolling = true; Android.autoLoadNext(); return; }
                     
                     if (!isVertical) {
                         var visible = lines.filter(function(l) { return l.bottom > 2 && l.top < h - 2; });
                         var scrollDelta = h;
                         if (visible.length > 0) {
                             // [수정] mask 판정 범위(0.1)에 맞게 기준 강화
                             var fullyVisible = visible.filter(function(l) { return l.top >= -0.1 && l.bottom <= h + 0.1; });
                             if (fullyVisible.length > 0) {
                                 var lastFull = fullyVisible[fullyVisible.length - 1];
                                 var idx = lines.indexOf(lastFull);
                                 if (idx >= 0 && idx < lines.length - 1) {
                                     scrollDelta = lines[idx + 1].top;
                                 } else {
                                     scrollDelta = h;
                                 }
                             } else {
                                 var last = visible[visible.length - 1];
                                 if (last.top > 10) {
                                     scrollDelta = last.top;
                                 } else {
                                     scrollDelta = h - 40;
                                 }
                             }
                         }
                         window.scrollBy({ top: Math.max(1, Math.min(scrollDelta - 10, h - 20)), behavior: 'instant' }); 
                     } else {
                         var visible = lines.filter(function(l) { return l.left < w - 2 && l.right > 2; });
                         var scrollDelta = -w;
                         if (visible.length > 0) {
                             var fullyVisible = visible.filter(function(l) { return l.left >= -2 && l.right <= w + 2; });
                             if (fullyVisible.length > 0) {
                                 var lastFull = fullyVisible[fullyVisible.length - 1];
                                 scrollDelta = lastFull.left - w;
                             } else {
                                 var last = visible[visible.length - 1]; 
                                 if (last.left < 2) {
                                     if (last.right < w - 10) {
                                         scrollDelta = last.right - w;
                                     } else {
                                         scrollDelta = -(w - 40);
                                     }
                                 }
                             }
                         }
                         window.scrollBy({ left: Math.min(-20, Math.max(scrollDelta, -w)), behavior: 'instant' });
                     }
                     window.detectAndReportLine(); window.updateMask();
                     window._lastScrollX = window.pageXOffset;
                 };

                 window.pageUp = function() {
                     window._scrollDir = -1;
                     var w = isVertical ? document.documentElement.clientWidth : window.innerWidth;
                     var h = window.innerHeight;
                     var isAtTop = false;
                     var lines = window.getVisualLines(); // 1번만 호출하여 재사용
                     
                     if (!isVertical) { 
                         if (window.pageYOffset <= 20) isAtTop = true;
                         if (lines.length > 0) {
                             var firstVisible = lines.find(function(l) { return l.bottom > -2 && l.top < h + 2; });
                             if (firstVisible && lines.indexOf(firstVisible) === 0) isAtTop = true;
                         }
                     } else { 
                         if (window.pageXOffset >= -5) isAtTop = true; 
                         if (lines.length > 0) {
                             var firstLine = lines[0];
                             var isFirstFullyVisible = (firstLine.right <= w + 5) || ((firstLine.right - firstLine.left) >= w - 10);
                             isAtTop = isAtTop && isFirstFullyVisible;
                         }
                     }

                     if (isAtTop) { window.isScrolling = true; Android.autoLoadPrev(); return; }

                     if (!isVertical) {
                         var visible = lines.filter(function(l) { return l.bottom > 2 && l.top < h - 2; });
                         var scrollDelta = -h;
                         if (visible.length > 0) {
                             // [수정] mask 판정 범위(0.1)에 맞게 기준 강화
                             var fullyVisible = visible.filter(function(l) { return l.top >= -0.1 && l.bottom <= h + 0.1; });
                             if (fullyVisible.length > 0) {
                                 var firstFull = fullyVisible[0];
                                 var idx = lines.indexOf(firstFull);
                                 if (idx > 0) {
                                     scrollDelta = lines[idx - 1].bottom - h;
                                 } else {
                                     scrollDelta = -h;
                                 }
                             } else {
                                 var first = visible[0];
                                 if (first.bottom < h - 10) {
                                     scrollDelta = first.bottom - h;
                                 } else {
                                     scrollDelta = -(h - 40);
                                 }
                             }
                         }
                         window.scrollBy({ top: Math.max(-h + 20, Math.min(scrollDelta + 10, -1)), behavior: 'instant' }); 
                     } else {
                         var visible = lines.filter(function(l) { return l.left < w - 2 && l.right > 2; });
                         var scrollDelta = w;
                         if (visible.length > 0) {
                             var fullyVisible = visible.filter(function(l) { return l.left >= -2 && l.right <= w + 2; });
                             if (fullyVisible.length > 0) {
                                 var firstFull = fullyVisible[0];
                                 scrollDelta = firstFull.right; 
                             } else {
                                 var first = visible[0];
                                 if (first.right > w + 2) {
                                     if (first.left > 10) {
                                         scrollDelta = first.left;
                                     } else {
                                         scrollDelta = w - 40;
                                     }
                                 }
                             }
                         }
                         window.scrollBy({ left: Math.min(w, Math.max(scrollDelta, 20)), behavior: 'instant' });
                     }
                     window.detectAndReportLine(); window.updateMask();
                 };
        """.trimIndent()
    }
}
