package com.uviewer_android.ui.viewer

internal object ViewerScrollEventsScript {
    fun install(): String {
        return """
                  var scrollTimer = null;
                  window.onscroll = function() {
                       if (window.isSystemScrolling) return; 

                      if (scrollTimer) clearTimeout(scrollTimer);
                      scrollTimer = setTimeout(function() {
                           if (window.isSystemScrolling) return; 

                          window.detectAndReportLine();
                          window.updateMask();
                          
                          if (window.isScrolling) return; 
                          
                          window.checkPreload();
                      }, 150); 
                  };

                 setTimeout(window.updateMask, 100);

                 setTimeout(function() {
                     window.checkPreload();
                 }, 600);
        """.trimIndent()
    }
}
