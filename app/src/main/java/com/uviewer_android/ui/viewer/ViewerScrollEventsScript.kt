package com.uviewer_android.ui.viewer

internal object ViewerScrollEventsScript {
    fun install(): String {
        return """
                  window.captureViewerScrollState = function() {
                      var state = {
                          x: window.pageXOffset || 0,
                          y: window.pageYOffset || 0,
                          vertical: !!isVertical,
                          scrollWidth: document.documentElement.scrollWidth || 0,
                          scrollHeight: document.documentElement.scrollHeight || 0,
                          viewportWidth: window.innerWidth || 0,
                          viewportHeight: window.innerHeight || 0
                      };
                      window._viewerScrollState = state;
                      return JSON.stringify(state);
                  };

                  window.restoreViewerScrollState = function(stateJson) {
                      var state = null;
                      if (stateJson) {
                          try {
                              state = (typeof stateJson === 'string') ? JSON.parse(stateJson) : stateJson;
                          } catch (e) {
                              state = null;
                          }
                      }
                      if (!state) state = window._viewerScrollState;
                      if (!state) return false;

                      window._viewerScrollState = state;
                      window._scrollDir = 0;
                      window.isSystemScrolling = true;

                      var targetX = Number(state.x) || 0;
                      var targetY = Number(state.y) || 0;
                      var attempts = 0;
                      function applyRestore() {
                          attempts++;
                          window.scrollTo(targetX, targetY);
                          if (typeof window.updateMask === 'function') window.updateMask(true);
                          if (attempts >= 5) {
                              window.captureViewerScrollState();
                              if (window.sysScrollTimer) clearTimeout(window.sysScrollTimer);
                              window.sysScrollTimer = setTimeout(function() {
                                  window.isSystemScrolling = false;
                                  if (typeof window.updateMask === 'function') window.updateMask(true);
                              }, 80);
                          }
                      }

                      applyRestore();
                      setTimeout(applyRestore, 50);
                      setTimeout(applyRestore, 120);
                      setTimeout(applyRestore, 250);
                      setTimeout(applyRestore, 500);
                      return true;
                  };

                  var scrollTimer = null;
                  window.onscroll = function() {
                      if (window.isSystemScrolling) return;

                      if (scrollTimer) clearTimeout(scrollTimer);
                      scrollTimer = setTimeout(function() {
                          if (window.isSystemScrolling) return;

                          if (typeof window.captureViewerScrollState === 'function') window.captureViewerScrollState();
                          window.detectAndReportLine();
                          window.updateMask();
                          
                          if (window.isScrolling) return; 
                          
                          window.checkPreload();
                      }, 150); 
                  };

                 setTimeout(window.updateMask, 100);
                 setTimeout(function() {
                     if (typeof window.captureViewerScrollState === 'function') window.captureViewerScrollState();
                 }, 250);

                 setTimeout(function() {
                     window.checkPreload();
                 }, 600);
        """.trimIndent()
    }
}
