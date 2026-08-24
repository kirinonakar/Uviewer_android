package com.uviewer_android.ui.viewer

internal object ViewerScrollEventsScript {
    fun install(): String {
        return """
                  window.captureViewerScrollState = function() {
                      var viewportWidth = window.innerWidth || 0;
                      var viewportHeight = window.innerHeight || 0;
                      var anchorLine = null;

                      if (typeof window.getVisualLines === 'function') {
                          var visualLines = window.getVisualLines();
                          var visibleLines = visualLines.filter(function(line) {
                              if (isVertical) {
                                  return line.left < viewportWidth && line.right > 0;
                              }
                              return line.bottom > 0 && line.top < viewportHeight;
                          });
                          if (visibleLines.length > 0) anchorLine = visibleLines[0];
                      }

                      var state = {
                          x: window.pageXOffset || 0,
                          y: window.pageYOffset || 0,
                          vertical: !!isVertical,
                          scrollWidth: document.documentElement.scrollWidth || 0,
                          scrollHeight: document.documentElement.scrollHeight || 0,
                          viewportWidth: viewportWidth,
                          viewportHeight: viewportHeight,
                          anchorId: anchorLine && anchorLine.element ? anchorLine.element.id : null,
                          anchorOffset: anchorLine
                              ? (isVertical ? viewportWidth - anchorLine.right : anchorLine.top)
                              : null
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
                      var anchorId = state.anchorId || null;
                      var anchorOffset = Number(state.anchorOffset);
                      var hasAnchor = !!anchorId && Number.isFinite(anchorOffset);
                      var attempts = 0;

                      function restoreFirstVisibleLine() {
                          if (!hasAnchor || typeof window.getVisualLines !== 'function') return;

                          var lines = window.getVisualLines().filter(function(line) {
                              return line.element && line.element.id === anchorId;
                          });
                          if (lines.length === 0) return;

                          var viewportWidth = window.innerWidth || 0;
                          var bestLine = lines[0];
                          var bestDistance = Infinity;
                          for (var i = 0; i < lines.length; i++) {
                              var currentOffset = isVertical
                                  ? viewportWidth - lines[i].right
                                  : lines[i].top;
                              var distance = Math.abs(currentOffset - anchorOffset);
                              if (distance < bestDistance) {
                                  bestDistance = distance;
                                  bestLine = lines[i];
                              }
                          }

                          if (isVertical) {
                              var desiredRight = viewportWidth - anchorOffset;
                              window.scrollBy({
                                  left: bestLine.right - desiredRight,
                                  behavior: 'instant'
                              });
                          } else {
                              window.scrollBy({
                                  top: bestLine.top - anchorOffset,
                                  behavior: 'instant'
                              });
                          }
                      }

                      function applyRestore() {
                          attempts++;
                          window.scrollTo(targetX, targetY);
                          restoreFirstVisibleLine();
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

                  var resizeTimer = null;
                  var lastViewportWidth = window.innerWidth || 0;
                  var lastViewportHeight = window.innerHeight || 0;
                  window.addEventListener('resize', function() {
                      var nextWidth = window.innerWidth || 0;
                      var nextHeight = window.innerHeight || 0;
                      if (nextWidth === lastViewportWidth && nextHeight === lastViewportHeight) return;

                      lastViewportWidth = nextWidth;
                      lastViewportHeight = nextHeight;
                      var stateBeforeResize = window._viewerScrollState;
                      if (!stateBeforeResize) return;

                      window.isSystemScrolling = true;
                      if (resizeTimer) clearTimeout(resizeTimer);
                      resizeTimer = setTimeout(function() {
                          window.restoreViewerScrollState(stateBeforeResize);
                      }, 0);
                  });

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
