package com.uviewer_android.ui.viewer

internal object ViewerScrollChunkScript {
    fun install(): String {
        return """
                  window.appendHtmlBase64 = function(base64Str, chunkIndex) {
                      try {
                          var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                          var parser = new DOMParser();
                          var doc = parser.parseFromString(htmlStr, 'text/html');
                          var container = document.body;
                          var spacer = document.getElementById('viewer-end-spacer');
                          var endMarker = document.getElementById('end-marker');
                          
                          var chunkWrapper = document.createElement('div');
                          chunkWrapper.className = 'content-chunk';
                          chunkWrapper.dataset.index = chunkIndex;
                          
                          Array.from(doc.body.childNodes).forEach(function(node) {
                              if (node.id === 'end-marker' || node.id === 'viewer-end-spacer' || node.id === 'start-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                              chunkWrapper.appendChild(node);
                          });

                          if (spacer) container.insertBefore(chunkWrapper, spacer);
                          else if (endMarker) container.insertBefore(chunkWrapper, endMarker);
                          else container.appendChild(chunkWrapper);

                          setTimeout(function() {
                              window.enforceChunkLimit(true);
                              window.updateMask();
                              if (typeof renderMath === 'function') { renderMath(); }
                              window.isScrolling = false; 
                          }, 50);

                          setTimeout(function() { window.checkPreload(); }, 600);
                      } catch(e) { console.error(e); window.isScrolling = false; }
                  };

                  window.prependHtmlBase64 = function(base64Str, chunkIndex) {
                      try {
                          var beforeScrollX = window.pageXOffset;
                          var beforeScrollY = window.pageYOffset;
                          var oldScrollWidth = document.documentElement.scrollWidth;
                          var oldScrollHeight = document.documentElement.scrollHeight;

                          var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                          var parser = new DOMParser();
                          var doc = parser.parseFromString(htmlStr, 'text/html');
                          var container = document.body;
                          var startMarker = document.getElementById('start-marker');
                          
                          var chunkWrapper = document.createElement('div');
                          chunkWrapper.className = 'content-chunk';
                          chunkWrapper.dataset.index = chunkIndex;
                          
                          Array.from(doc.body.childNodes).forEach(function(node) {
                              if (node.id === 'start-marker' || node.id === 'end-marker' || node.id === 'viewer-end-spacer' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                              chunkWrapper.appendChild(node);
                          });

                          if (startMarker && startMarker.nextSibling) {
                              container.insertBefore(chunkWrapper, startMarker.nextSibling);
                          } else {
                              container.insertBefore(chunkWrapper, container.firstChild);
                          }

                          var newScrollWidth = document.documentElement.scrollWidth;
                          var newScrollHeight = document.documentElement.scrollHeight;
                          if (isVertical) {
                              var widthDiff = newScrollWidth - oldScrollWidth;
                              window.scrollTo(beforeScrollX - widthDiff, beforeScrollY);
                          } else {
                              var heightDiff = newScrollHeight - oldScrollHeight;
                              window.scrollTo(beforeScrollX, beforeScrollY + heightDiff);
                          }

                          setTimeout(function() {
                              window.enforceChunkLimit(false);
                              window.updateMask();
                              if (isVertical) {
                                  var finalWidthDiff = document.documentElement.scrollWidth - oldScrollWidth;
                                  window.scrollTo(beforeScrollX - finalWidthDiff, beforeScrollY);
                              } else {
                                  var finalHeightDiff = document.documentElement.scrollHeight - oldScrollHeight;
                                  window.scrollTo(beforeScrollX, beforeScrollY + finalHeightDiff);
                              }
                              if (typeof renderMath === 'function') { renderMath(); }
                              window.isScrolling = false; 
                          }, 300);

                          setTimeout(function() { window.checkPreload(); }, 600);
                      } catch(e) { console.error(e); window.isScrolling = false; }
                  };

                  window.replaceHtmlBase64 = function(base64Str, chunkIndex, targetLine) {
                      try {
                          var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                          var parser = new DOMParser();
                          var doc = parser.parseFromString(htmlStr, 'text/html');
                          var container = document.body;

                          var chunks = document.querySelectorAll('.content-chunk');
                          chunks.forEach(function(c) { c.parentNode.removeChild(c); });

                          var chunkWrapper = document.createElement('div');
                          chunkWrapper.className = 'content-chunk';
                          chunkWrapper.dataset.index = chunkIndex;

                          Array.from(doc.body.childNodes).forEach(function(node) {
                              if (node.id === 'end-marker' || node.id === 'viewer-end-spacer' || node.id === 'start-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                              chunkWrapper.appendChild(node);
                          });

                          container.insertBefore(chunkWrapper, container.firstChild);

                          setTimeout(function() {
                              var el = document.getElementById('line-' + targetLine);
                              if(el) {
                                  el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                              }
                              window.updateMask();
                              if (typeof renderMath === 'function') { renderMath(); }
                              window.isScrolling = false;
                              window.detectAndReportLine();
                          }, 50);

                          setTimeout(function() {
                              window.checkPreload();
                          }, 400);
                      } catch(e) { console.error(e); window.isScrolling = false; }
                  };

                  window.enforceChunkLimit = function(isNext) {
                      var chunks = document.getElementsByClassName('content-chunk');
                      if (chunks.length > window.MAX_CHUNKS) { 
                          if (isNext) {
                              var firstChunk = chunks[0];
                              var r = firstChunk.getBoundingClientRect();
                              var chunkHeight = r.height;
                              var chunkWidth = r.width;

                              if (isVertical && window.pageXOffset > -chunkWidth) return;
                              if (!isVertical && window.pageYOffset < chunkHeight) return;
                              
                              firstChunk.parentNode.removeChild(firstChunk);
                              
                              if (isVertical) window.safeScrollBy(chunkWidth, 0); 
                              else window.safeScrollBy(0, -chunkHeight);
                          } else {
                              var lastChunk = chunks[chunks.length - 1];
                              var r = lastChunk.getBoundingClientRect();
                              var chunkHeight = r.height;
                              var chunkWidth = r.width;
                              
                              if (isVertical) {
                                  var maxScroll = document.documentElement.scrollWidth - window.innerWidth;
                                  if (window.pageXOffset < -(maxScroll - chunkWidth)) return;
                              } else {
                                  var maxScroll = document.documentElement.scrollHeight - window.innerHeight;
                                  if (window.pageYOffset > (maxScroll - chunkHeight)) return;
                              }
                              lastChunk.parentNode.removeChild(lastChunk);
                          }
                      }
                  };
        """.trimIndent()
    }
}
