package com.uviewer_android.ui.viewer

internal object ViewerScrollPreloadScript {
    fun install(isImageOnly: Boolean): String {
        return """
                  window.MAX_CHUNKS = 12; 
                  
                  window.checkPreload = function() {
                      if (!enableAutoLoading) return; if (window.isScrolling) return; if ($isImageOnly) return;
                      var w = window.innerWidth;
                      var h = window.innerHeight;
                      var preloadMarginX = w * 1.5; 
                      var preloadMarginY = h * 1.5;

                      if (isVertical) {
                          var scrollW = document.documentElement.scrollWidth;
                          var maxScrollX = Math.max(0, scrollW - w);
                          
                          var distToEnd = maxScrollX - Math.abs(window.pageXOffset); 
                          var distToStart = Math.abs(window.pageXOffset);            

                          if (distToEnd <= preloadMarginX) {
                              if(Android.autoLoadNextBg) {
                                  window.isScrolling = true; 
                                  Android.autoLoadNextBg();
                              }
                          } else if (distToStart <= preloadMarginX && distToStart > 10) { 
                              if(Android.autoLoadPrevBg) {
                                  window.isScrolling = true; 
                                  Android.autoLoadPrevBg();
                              }
                          }
                      } else {
                          var scrollPosition = h + window.pageYOffset;
                          var bottomPosition = document.documentElement.scrollHeight;
                          var distToEnd = bottomPosition - scrollPosition;
                          var distToStart = window.pageYOffset;

                          if (distToEnd <= preloadMarginY) {
                              if(Android.autoLoadNextBg) {
                                  window.isScrolling = true; 
                                  Android.autoLoadNextBg();
                              }
                          } else if (distToStart <= preloadMarginY && distToStart > 10) {
                              if(Android.autoLoadPrevBg) {
                                  window.isScrolling = true; 
                                  Android.autoLoadPrevBg();
                              }
                          }
                      }
                       if (window.isScrolling) { if (window._scrollingLockTimeout) clearTimeout(window._scrollingLockTimeout); window._scrollingLockTimeout = setTimeout(function() { window.isScrolling = false; }, 5000); }
                  };
        """.trimIndent()
    }
}
