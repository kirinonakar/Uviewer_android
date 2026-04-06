package com.uviewer_android.ui.viewer

object ViewerScripts {

    fun getScrollLogic(
        isVertical: Boolean,
        enableAutoLoading: Boolean,
        targetLine: Int,
        totalLines: Int,
        linePrefix: String,
        isImageOnly: Boolean = false
    ): String {
        return """
            (function() {
                var isVertical = $isVertical;
                var enableAutoLoading = $enableAutoLoading;
                
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

                 window.detectAndReportLine = function() {
                     var width = window.innerWidth;
                     var height = window.innerHeight;
                     var points = [];
                     
                     if (isVertical) {
                         var offsetsX = [10, 30, 60, 100, 150, 200]; 
                         var offsetsY = [0.1, 0.25, 0.5, 0.75, 0.9];
                         for (var i = 0; i < offsetsX.length; i++) {
                             var x = width - offsetsX[i];
                             if (x < 0) break;
                             for (var j = 0; j < offsetsY.length; j++) {
                                 points.push({x: x, y: height * offsetsY[j]});
                             }
                         }
                     } else {
                         var offsetsY = [10, 30, 60, 100];
                         var offsetsX = [0.1, 0.25, 0.5, 0.75, 0.9];
                         for (var i = 0; i < offsetsY.length; i++) {
                             var y = offsetsY[i];
                             for (var j = 0; j < offsetsX.length; j++) {
                                 points.push({x: width * offsetsX[j], y: y});
                             }
                         }
                     }
                     
                     var foundLineStr = null;
                     for (var i = 0; i < points.length; i++) {
                         var el = document.elementFromPoint(points[i].x, points[i].y);
                         while (el && el.tagName !== 'BODY' && el.tagName !== 'HTML') {
                             if (el.id && el.id.startsWith('line-')) {
                                 foundLineStr = el.id.replace('line-', '');
                                 break;
                             }
                             el = el.parentElement;
                         }
                         if (foundLineStr) break;
                     }
                     if (foundLineStr) {
                         Android.onLineChangedStr(foundLineStr);
                     }
                 };

                 window.getVisualLines = function() {
                     var w = window.innerWidth;
                     var h = window.innerHeight;
                     var textLines = [];
                     var seenRuby = new Set();
                     var padding = isVertical ? w * 2 : h * 2;
                     
                     var walker = document.createTreeWalker(
                         document.body,
                         NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
                         {
                             acceptNode: function(node) {
                                 if (node.nodeType === 1) {
                                      if (node.classList && node.classList.contains('image-page-wrapper')) return NodeFilter.FILTER_ACCEPT;
                                      if (node.tagName === 'IMG' || node.tagName === 'SVG' || node.tagName === 'FIGURE') return NodeFilter.FILTER_ACCEPT;
                                      var tag = node.tagName;
                                      if (tag === 'P' || tag === 'DIV' || tag === 'TABLE' || tag === 'SECTION') {
                                          var r = node.getBoundingClientRect();
                                          if (!isVertical) {
                                              if (r.bottom < -padding || r.top > h + padding) return NodeFilter.FILTER_REJECT;
                                          } else {
                                              if (r.left > w + padding || r.right < -padding) return NodeFilter.FILTER_REJECT;
                                          }
                                      }
                                      return NodeFilter.FILTER_SKIP;
                                 }
                                 if (node.nodeType === 3 && node.nodeValue.trim().length > 0) return NodeFilter.FILTER_ACCEPT;
                                 return NodeFilter.FILTER_SKIP;
                             }
                         }
                     );
                     
                     var node;
                     while ((node = walker.nextNode())) {
                         if (node.nodeType === 1 && node.classList && node.classList.contains('image-page-wrapper')) {
                             var r = node.getBoundingClientRect();
                             textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: true });
                             continue;
                         }

                         var el = node.parentElement;
                         if (!el) continue;
                         if (node.nodeType === 1 && (node.tagName === 'IMG' || node.tagName === 'SVG' || node.tagName === 'FIGURE') && el.classList.contains('image-page-wrapper')) continue;
                         
                         var rubyParent = el.closest('ruby');
                         if (rubyParent) {
                             if (seenRuby.has(rubyParent)) continue;
                             seenRuby.add(rubyParent);
                             var rubyWalker = document.createTreeWalker(rubyParent, NodeFilter.SHOW_TEXT, null);
                             var rNode;
                             var rRects = [];
                             while ((rNode = rubyWalker.nextNode())) {
                                 if (rNode.nodeValue.trim().length === 0) continue;
                                 var range = document.createRange();
                                 range.selectNodeContents(rNode);
                                 var rects = range.getClientRects();
                                 for (var i = 0; i < rects.length; i++) {
                                     if (rects[i].width > 0 && rects[i].height > 0) rRects.push({
                                         top: rects[i].top, bottom: rects[i].bottom, left: rects[i].left, right: rects[i].right
                                     });
                                 }
                             }
                             if (rRects.length > 0) {
                                 var parts = [];
                                 for (var i = 0; i < rRects.length; i++) {
                                     var current = rRects[i];
                                     var added = false;
                                     for (var j = 0; j < parts.length; j++) {
                                         var p = parts[j];
                                         var hOverlap = Math.min(p.right, current.right) - Math.max(p.left, current.left);
                                         var vOverlap = Math.min(p.bottom, current.bottom) - Math.max(p.top, current.top);
                                         var isSamePart = false;
                                         if (!isVertical) {
                                             if (hOverlap > -10 && vOverlap > -10) isSamePart = true;
                                         } else {
                                             if (vOverlap > -10 && hOverlap > -10) isSamePart = true;
                                         }
                                         if (isSamePart) {
                                             p.top = Math.min(p.top, current.top);
                                             p.bottom = Math.max(p.bottom, current.bottom);
                                             p.left = Math.min(p.left, current.left);
                                             p.right = Math.max(p.right, current.right);
                                             added = true;
                                             break;
                                         }
                                     }
                                     if (!added) {
                                         parts.push({ top: current.top, bottom: current.bottom, left: current.left, right: current.right, isImageWrapper: false });
                                     }
                                 }
                                 for (var i = 0; i < parts.length; i++) {
                                     textLines.push(parts[i]);
                                 }
                             }
                         } else {
                              var rects; if (node.nodeType === 1) { rects = [node.getBoundingClientRect()]; } else { var range = document.createRange(); range.selectNodeContents(node); rects = range.getClientRects(); }
                              var isImg = node.nodeType === 1 && (node.tagName === 'IMG' || node.tagName === 'SVG' || node.tagName === 'FIGURE');
                             for (var i = 0; i < rects.length; i++) {
                                 var r = rects[i];
                                 if (r.width > 0 && r.height > 0) { var isBlank = node.nodeType === 1 && node.classList && node.classList.contains("blank-line"); textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: isImg, isBlankLine: isBlank }); }
                             }
                         }
                     }
                     
                     if (!isVertical) {
                         textLines.sort(function(a, b) { var diff = a.top - b.top; return diff !== 0 ? diff : a.left - b.left; });
                         var lines = [];
                         if (textLines.length === 0) return lines;
                         var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: !!textLines[0].isImageWrapper };
                         for (var i = 1; i < textLines.length; i++) {
                             var r = textLines[i];
                             var vOverlap = Math.min(currentLine.bottom, r.bottom) - Math.max(currentLine.top, r.top);
                             var minHeight = Math.min(currentLine.bottom - currentLine.top, r.bottom - r.top);
                             if (vOverlap > Math.max(2, minHeight * 0.6)) { 
                                 currentLine.top = Math.min(currentLine.top, r.top);
                                 currentLine.bottom = Math.max(currentLine.bottom, r.bottom);
                                 currentLine.left = Math.min(currentLine.left, r.left);
                                 currentLine.right = Math.max(currentLine.right, r.right);
                                 currentLine.isImageWrapper = currentLine.isImageWrapper || !!r.isImageWrapper;
                             } else {
                                 lines.push(currentLine);
                                 currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: !!r.isImageWrapper };
                             }
                         }
                         lines.push(currentLine);
                         return lines;
                     } else {
                         textLines.sort(function(a, b) { var diff = b.right - a.right; return diff !== 0 ? diff : a.top - b.top; });
                         var lines = [];
                         if (textLines.length === 0) return lines;
                         var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: !!textLines[0].isImageWrapper };
                         for (var i = 1; i < textLines.length; i++) {
                             var r = textLines[i];
                             var hOverlap = Math.min(currentLine.right, r.right) - Math.max(currentLine.left, r.left);
                             var minWidth = Math.min(currentLine.right - currentLine.left, r.right - r.left);
                             if (hOverlap > Math.max(2, minWidth * 0.6)) { 
                                 currentLine.top = Math.min(currentLine.top, r.top);
                                 currentLine.bottom = Math.max(currentLine.bottom, r.bottom);
                                 currentLine.left = Math.min(currentLine.left, r.left);
                                 currentLine.right = Math.max(currentLine.right, r.right);
                                 currentLine.isImageWrapper = currentLine.isImageWrapper || !!r.isImageWrapper;
                             } else {
                                 lines.push(currentLine);
                                 currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: !!r.isImageWrapper };
                             }
                         }
                         lines.push(currentLine);
                         return lines;
                     }
                 };

                  window.calculateMasks = function() {
                      var masks = { top: 0, bottom: 0, left: 0, right: 0 };
                      var lines = window.getVisualLines();
                      if (lines.length === 0) return masks;
                      var w = window.innerWidth;
                      var h = window.innerHeight;
                      
                      if (!isVertical) {
                          var visible = lines.filter(function(l) { return l.bottom > 0.05 && l.top < h - 0.05; });
                          if (visible.length === 0) return masks;
                          
                          var textVisible = visible.filter(function(l) { return !l.isImageWrapper; });
                          var imageVisible = visible.filter(function(l) { return l.isImageWrapper; });
                          
                          var cutTop = textVisible.filter(function(l) { return l.top < -0.1; });
                          if (cutTop.length > 0) {
                              var maxB = 0;
                              for (var i = 0; i < cutTop.length; i++) { if (cutTop[i].bottom > maxB) maxB = cutTop[i].bottom; }
                              masks.top = Math.ceil(maxB + 1);
                          }
                          
                          var cutBottom = textVisible.filter(function(l) { return l.bottom > h + 0.1; });
                          if (cutBottom.length > 0) {
                              var minT = h;
                              for (var i = 0; i < cutBottom.length; i++) { if (cutBottom[i].top < minT) minT = cutBottom[i].top; }
                              masks.bottom = Math.ceil(h - minT + 1);
                          }

                          imageVisible.forEach(function(img) {
                              if (img.top < masks.top) masks.top = Math.max(0, Math.floor(img.top));
                              if (img.bottom > h - masks.bottom) masks.bottom = Math.max(0, Math.floor(h - img.bottom));
                          });

                          var fullText = textVisible.filter(function(l) { return l.top >= -0.1 && l.bottom <= h + 0.1; });
                          if (fullText.length > 0) {
                              var minTVal = h, maxBVal = 0;
                              fullText.forEach(function(l) {
                                  if (l.top < minTVal) minTVal = l.top;
                                  if (l.bottom > maxBVal) maxBVal = l.bottom;
                              });
                              if (masks.top > minTVal) masks.top = Math.max(0, Math.floor(minTVal));
                              if (masks.bottom > (h - maxBVal)) masks.bottom = Math.max(0, Math.floor(h - maxBVal));
                          }
                       } else {
                           var visible = lines.filter(function(l) { return l.right > 0.05 && l.left < w - 0.05; });
                           if (visible.length === 0) return masks;
                           
                           var textVisible = visible.filter(function(l) { return !l.isImageWrapper; });
                           var imageVisible = visible.filter(function(l) { return l.isImageWrapper; });
                           
                           var fullyVisibleText = textVisible.filter(function(l) { return l.left >= -2 && l.right <= w + 2; });
                           if (fullyVisibleText.length > 0) {
                               var lastFull = fullyVisibleText[fullyVisibleText.length - 1];
                               var cutLeft = textVisible.filter(function(l) { return l.left < lastFull.left - 2; });
                               if (cutLeft.length > 0) {
                                   masks.left = Math.ceil(lastFull.left);
                               }
                               
                               var firstFull = fullyVisibleText[0];
                               var cutRight = textVisible.filter(function(l) { return l.right > firstFull.right + 2; });
                               if (cutRight.length > 0) {
                                   masks.right = Math.ceil(w - firstFull.right);
                               }
                           } else if (textVisible.length > 0) {
                               var theLine = textVisible[0];
                               if (theLine.left < 0) masks.left = 0;
                               if (theLine.right > w) masks.right = 0;
                           }

                           imageVisible.forEach(function(img) {
                               if (img.left < masks.left) masks.left = Math.max(0, Math.floor(img.left));
                               if (img.right > w - masks.right) masks.right = Math.max(0, Math.floor(w - img.right));
                           });
                       }
                       if (isVertical) {
                           if (window._scrollDir === 1) {
                               masks.right = 0; 
                           }
                           if (window._scrollDir === -1) { 
                               masks.left = 0;
                               masks.right = 0; 
                           }
                       }
                       return masks;
                  };

                 window.updateMask = function() {
                     var masks = window.calculateMasks();
                     Android.updateTopMask(masks.top > 0 ? masks.top : 0);
                     Android.updateBottomMask(masks.bottom > 0 ? masks.bottom : 0);
                     Android.updateLeftMask(masks.left > 0 ? masks.left : 0);
                     Android.updateRightMask(masks.right > 0 ? masks.right : 0);
                 };

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
             })();
        """.trimIndent()
    }

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