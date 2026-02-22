package com.uviewer_android.ui.viewer

object ViewerScripts {

    fun getScrollLogic(
        isVertical: Boolean,
        pagingMode: Int,
        enableAutoLoading: Boolean,
        targetLine: Int,
        totalLines: Int,
        linePrefix: String
    ): String {
        return """
            (function() {
                var isVertical = $isVertical;
                var pagingMode = $pagingMode;
                var enableAutoLoading = $enableAutoLoading;
                
                 // 1. 시스템(JS) 스크롤과 유저 스크롤을 구분하기 위한 락(Lock) 변수
                 window.isSystemScrolling = false;
                 window.sysScrollTimer = null;

                 // 2. JS가 강제로 스크롤을 조작할 때 사용할 안전한 래퍼 함수
                 window.safeScrollBy = function(x, y) {
                     window.isSystemScrolling = true; // 스크롤 이벤트 무시 시작
                     window.scrollBy(x, y);
                     
                     // 보정이 끝난 후 충분한 시간(250ms)이 지난 뒤에 락을 풉니다.
                     if (window.sysScrollTimer) clearTimeout(window.sysScrollTimer);
                     window.sysScrollTimer = setTimeout(function() {
                         window.isSystemScrolling = false;
                     }, 250); 
                 };
                
                 // 1. Restore scroll position
                 if ($targetLine === $totalLines && $totalLines > 1) {
                      if (typeof jumpToBottom === 'function') { jumpToBottom(); }
                      else {
                           if (isVertical) window.scrollTo(-1000000, 0); 
                           else window.scrollTo(0, 1000000);
                      }
                 } else {
                     var el = document.getElementById('line-${linePrefix}$targetLine'); 
                     if(el) {
                         el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                     } else if ($targetLine === 1) {
                         if (isVertical) window.scrollTo(1000000, 0); 
                         else window.scrollTo(0, 0);
                     }
                 }

                   // [수정됨] 청크 개수 제한을 5로 늘려 이전2 + 현재 + 이후2 구조가 가능하게 함
                  window.MAX_CHUNKS = 5; 
                  
                  // [추가됨] 앞뒤 청크를 미리(미리보기 화면의 1.5배 전부터) 불러오는 독립 함수
                  window.checkPreload = function() {
                      if (!enableAutoLoading) return; if (window.isScrolling) return;
                      var w = window.innerWidth;
                      var h = window.innerHeight;
                      // 여유 마진을 화면의 1.5배로 크게 잡음 (사용자가 도달하기 전에 미리 로드)
                      var preloadMarginX = w * 0.7; 
                      var preloadMarginY = h * 0.7;

                      if (isVertical) {
                          var maxScrollX = document.documentElement.scrollWidth - window.innerWidth;
                          if (window.pageXOffset <= -(maxScrollX - preloadMarginX)) {
                              window.isScrolling = true; Android.autoLoadNext();
                          } else if (window.pageXOffset >= -preloadMarginX) { 
                              window.isScrolling = true; Android.autoLoadPrev();
                          }
                      } else {
                          var scrollPosition = window.innerHeight + window.pageYOffset;
                          var bottomPosition = document.documentElement.scrollHeight;
                          if (scrollPosition >= bottomPosition - preloadMarginY) {
                              window.isScrolling = true; Android.autoLoadNext();
                          } else if (window.pageYOffset <= preloadMarginY) {
                              window.isScrolling = true; Android.autoLoadPrev();
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
                          var endMarker = document.getElementById('end-marker');
                          
                          var chunkWrapper = document.createElement('div');
                          chunkWrapper.className = 'content-chunk';
                          chunkWrapper.dataset.index = chunkIndex;
                          
                          var hr = document.createElement('hr');
                          hr.style.cssText = "border: none; border-top: 1px dashed #888; margin: 3em 1em; width: 80%; opacity: 0.5;";
                          chunkWrapper.appendChild(hr);

                          Array.from(doc.body.childNodes).forEach(function(node) {
                              if (node.id === 'end-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                              chunkWrapper.appendChild(node);
                          });

                          if (endMarker) container.insertBefore(chunkWrapper, endMarker);
                          else container.appendChild(chunkWrapper);

                          // 이미지 로딩 등으로 인한 높이 변화 대기 후 GC 실행
                           setTimeout(function() {
                              window.enforceChunkLimit(true);
                              window.updateMask();
                              window.isScrolling = false; 
                              window.checkPreload(); // 추가됨: 로드 직후 한 번 더 공간 검사
                          }, 100);
                      } catch(e) { console.error(e); window.isScrolling = false; }
                  };

                  window.prependHtmlBase64 = function(base64Str, chunkIndex) {
                      try {
                          var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                          var parser = new DOMParser();
                          var doc = parser.parseFromString(htmlStr, 'text/html');
                          var container = document.body;
                          
                          var chunkWrapper = document.createElement('div');
                          chunkWrapper.className = 'content-chunk';
                          chunkWrapper.dataset.index = chunkIndex;

                          Array.from(doc.body.childNodes).forEach(function(node) {
                              if (node.id === 'end-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                              chunkWrapper.appendChild(node);
                          });

                          var hr = document.createElement('hr');
                          hr.style.cssText = "border: none; border-top: 1px dashed #888; margin: 3em 1em; width: 80%; opacity: 0.5;";
                          chunkWrapper.appendChild(hr);

                          container.insertBefore(chunkWrapper, container.firstChild);

                          // [핵심 해결책] ResizeObserver: 이미지가 비동기 로딩되며 크기가 커질 때마다 실시간으로 스크롤 역산
                          var lastWidth = 0;
                          var lastHeight = 0;
                          
                          var ro = new ResizeObserver(function(entries) {
                              for (var i = 0; i < entries.length; i++) {
                                  var entry = entries[i];
                                  var newWidth = entry.contentRect.width;
                                  var newHeight = entry.contentRect.height;
                                  
                                  var diffW = newWidth - lastWidth;
                                  var diffH = newHeight - lastHeight;
                                  
                                  lastWidth = newWidth;
                                  lastHeight = newHeight;

                                  // 이미지가 커지면서 밀어낸 공간만큼 스크롤을 이동시켜 시야를 꽉 잡아줌
                                  if (isVertical) {
                                      window.safeScrollBy(-diffW, 0); 
                                  } else {
                                      window.safeScrollBy(0, diffH);
                                  }
                              }
                          });
                          
                          // 관찰 시작 (이때 초기 DOM 크기에 대한 첫 번째 보정이 자동으로 발생합니다)
                          ro.observe(chunkWrapper);

                          // 이미지가 전부 로딩될 넉넉한 시간(2초) 뒤에 관찰을 끄고 메모리 확보
                          setTimeout(function() {
                              ro.disconnect();
                          }, 2000);

                          // 로딩 락은 짧게 해제하여 유저가 계속 스크롤 할 수 있게 허용
                           setTimeout(function() {
                              window.enforceChunkLimit(false); 
                              window.updateMask();
                              window.isScrolling = false; 
                              window.checkPreload(); // 추가됨
                          }, 150);
                      } catch(e) { console.error(e); window.isScrolling = false; }
                  };

                  // [추가됨] 깜빡임 없는 슬라이더 점프를 위한 함수
                  window.replaceHtmlBase64 = function(base64Str, chunkIndex, targetLine) {
                      try {
                          var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                          var parser = new DOMParser();
                          var doc = parser.parseFromString(htmlStr, 'text/html');
                          var container = document.body;

                          // 화면을 비우지 않고 기존 청크만 즉시 삭제 및 교체 (깜빡임 최소화)
                          var chunks = document.querySelectorAll('.content-chunk');
                          chunks.forEach(function(c) { c.parentNode.removeChild(c); });

                          var chunkWrapper = document.createElement('div');
                          chunkWrapper.className = 'content-chunk';
                          chunkWrapper.dataset.index = chunkIndex;

                          Array.from(doc.body.childNodes).forEach(function(node) {
                              if (node.id === 'end-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                              chunkWrapper.appendChild(node);
                          });

                          container.insertBefore(chunkWrapper, container.firstChild);

                          // 내용 렌더링 후 목표 라인으로 즉각 스크롤 이동
                          setTimeout(function() {
                              var el = document.getElementById('line-' + targetLine);
                              if(el) {
                                  el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                              }
                              window.updateMask();
                              window.isScrolling = false;
                              window.checkPreload(); 
                              
                              // [추가됨] 점프가 끝난 즉시 안드로이드에 현재 라인 번호를 쏴줍니다.
                              window.detectAndReportLine();
                          }, 50);
                      } catch(e) { console.error(e); window.isScrolling = false; }
                  };

                  window.enforceChunkLimit = function(isAppend) {
                      var chunks = document.querySelectorAll('.content-chunk');
                      if (chunks.length > window.MAX_CHUNKS) {
                          if (isAppend) {
                              // 뒤로 스크롤 중: 맨 앞(위/오른쪽)의 가장 오래된 청크 삭제
                              var oldestChunk = chunks[0];
                              var oldScrollWidth = document.documentElement.scrollWidth;
                              var oldScrollHeight = document.documentElement.scrollHeight;

                              oldestChunk.parentNode.removeChild(oldestChunk);

                              var newScrollWidth = document.documentElement.scrollWidth;
                              var newScrollHeight = document.documentElement.scrollHeight;

                              // [핵심] 줄어든 크기만큼 즉시 스크롤을 당겨옴
                              if (isVertical) {
                                  var diff = oldScrollWidth - newScrollWidth;
                                  window.safeScrollBy(diff, 0);
                              } else {
                                  var diff = oldScrollHeight - newScrollHeight;
                                  window.safeScrollBy(0, -diff);
                              }
                          } else {
                              // 앞으로 스크롤 중: 맨 뒤(아래/왼쪽)의 청크 삭제 (보정 불필요)
                              var newestChunk = chunks[chunks.length - 1];
                              newestChunk.parentNode.removeChild(newestChunk);
                          }
                      }
                  };

                 window.detectAndReportLine = function() {
                     var width = window.innerWidth;
                     var height = window.innerHeight;
                     var points = [];
                     if (isVertical) {
                         var offsetsX = [5, 15, 30, 50, 80];
                         var offsetsY = [0.1, 0.25, 0.5, 0.75, 0.9];
                         for (var i = 0; i < offsetsX.length; i++) {
                             for (var j = 0; j < offsetsY.length; j++) {
                                 if (width - offsetsX[i] > 0) {
                                     points.push({x: width - offsetsX[i], y: height * offsetsY[j]});
                                 }
                             }
                         }
                     } else {
                         var cx = width / 2;
                         var ty = 20;
                         points = [{x: cx, y: ty}, {x: cx - 50, y: ty}, {x: cx + 50, y: ty}, {x: cx, y: ty + 40}];
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
                     var padding = isVertical ? w : h;
                     
                     var walker = document.createTreeWalker(
                         document.body,
                         NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
                         {
                             acceptNode: function(node) {
                                 if (node.nodeType === 1) {
                                      // [수정] 스크롤 엔진이 이미지 영역의 경계를 알 수 있도록 ACCEPT로 변경
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
                         // [추가] 이미지 래퍼 자체를 거대한 텍스트 라인처럼 배열에 추가
                         if (node.nodeType === 1 && node.classList && node.classList.contains('image-page-wrapper')) {
                             var r = node.getBoundingClientRect();
                             textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: true });
                             continue;
                         }

                         var el = node.parentElement;
                         if (!el) continue;
                         // 이미 래퍼를 통해 영역을 잡았으므로 내부 이미지는 무시
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
                                         parts.push({ top: current.top, bottom: current.bottom, left: current.left, right: current.right });
                                     }
                                 }
                                 for (var i = 0; i < parts.length; i++) {
                                     textLines.push(parts[i]);
                                 }
                             }
                         } else {
                              var rects; if (node.nodeType === 1) { rects = [node.getBoundingClientRect()]; } else { var range = document.createRange(); range.selectNodeContents(node); rects = range.getClientRects(); }
                             for (var i = 0; i < rects.length; i++) {
                                 var r = rects[i];
                                 if (r.width > 0 && r.height > 0) textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right });
                             }
                         }
                     }
                     
                     if (!isVertical) {
                         textLines.sort(function(a, b) { var diff = a.top - b.top; return diff !== 0 ? diff : a.left - b.left; });
                         var lines = [];
                         if (textLines.length === 0) return lines;
                         var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: textLines[0].isImageWrapper };
                         for (var i = 1; i < textLines.length; i++) {
                             var r = textLines[i];
                             var vOverlap = Math.min(currentLine.bottom, r.bottom) - Math.max(currentLine.top, r.top);
                             var minHeight = Math.min(currentLine.bottom - currentLine.top, r.bottom - r.top);
                             if (vOverlap > Math.max(2, minHeight * 0.6)) { 
                                 currentLine.top = Math.min(currentLine.top, r.top);
                                 currentLine.bottom = Math.max(currentLine.bottom, r.bottom);
                                 currentLine.left = Math.min(currentLine.left, r.left);
                                 currentLine.right = Math.max(currentLine.right, r.right);
                                 currentLine.isImageWrapper = currentLine.isImageWrapper || r.isImageWrapper;
                             } else {
                                 lines.push(currentLine);
                                 currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: r.isImageWrapper };
                             }
                         }
                         lines.push(currentLine);
                         return lines;
                     } else {
                         textLines.sort(function(a, b) { var diff = b.right - a.right; return diff !== 0 ? diff : a.top - b.top; });
                         var lines = [];
                         if (textLines.length === 0) return lines;
                         var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: textLines[0].isImageWrapper };
                         for (var i = 1; i < textLines.length; i++) {
                             var r = textLines[i];
                             var hOverlap = Math.min(currentLine.right, r.right) - Math.max(currentLine.left, r.left);
                             var minWidth = Math.min(currentLine.right - currentLine.left, r.right - r.left);
                             if (hOverlap > Math.max(2, minWidth * 0.6)) { 
                                 currentLine.top = Math.min(currentLine.top, r.top);
                                 currentLine.bottom = Math.max(currentLine.bottom, r.bottom);
                                 currentLine.left = Math.min(currentLine.left, r.left);
                                 currentLine.right = Math.max(currentLine.right, r.right);
                                 currentLine.isImageWrapper = currentLine.isImageWrapper || r.isImageWrapper;
                             } else {
                                 lines.push(currentLine);
                                 currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: r.isImageWrapper };
                             }
                         }
                         lines.push(currentLine);
                         return lines;
                     }
                 };

                  window.calculateMasks = function() {
                      var masks = { top: 0, bottom: 0, left: 0, right: 0 };
                      if (pagingMode !== 1) return masks;
                      var lines = window.getVisualLines();
                      if (lines.length === 0) return masks;
                      var w = window.innerWidth;
                      var h = window.innerHeight;
                      
                      if (!isVertical) {
                          var visible = lines.filter(function(l) { return l.bottom > 0.05 && l.top < h - 0.05; });
                          if (visible.length === 0) return masks;
                          
                          var textVisible = visible.filter(function(l) { return !l.isImageWrapper; });
                          var imageVisible = visible.filter(function(l) { return l.isImageWrapper; });
                          
                          var cutTop = textVisible.filter(function(l) { return l.top < -0.05; });
                          if (cutTop.length > 0) {
                              var maxB = 0;
                              for (var i = 0; i < cutTop.length; i++) { if (cutTop[i].bottom > maxB) maxB = cutTop[i].bottom; }
                              masks.top = Math.ceil(maxB + 1);
                          }
                          
                          var cutBottom = textVisible.filter(function(l) { return l.bottom > h + 0.05; });
                          if (cutBottom.length > 0) {
                              var minT = h;
                              for (var i = 0; i < cutBottom.length; i++) { if (cutBottom[i].top < minT) minT = cutBottom[i].top; }
                              masks.bottom = Math.ceil(h - minT + 1);
                          }

                          // Adjust for images: don't cover them
                          imageVisible.forEach(function(img) {
                              if (img.top < masks.top) masks.top = Math.max(0, Math.floor(img.top));
                              if (img.bottom > h - masks.bottom) masks.bottom = Math.max(0, Math.floor(h - img.bottom));
                          });

                          // Protect fully visible text from being covered by adjacent masks
                          var fullText = textVisible.filter(function(l) { return l.top >= -0.05 && l.bottom <= h + 0.05; });
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
                          
                          // [Vertical Next Mode Masking]
                          // Hide text that is partially cut on the left edge (start of next page area)
                          var cutLeft = textVisible.filter(function(l) { return l.left < -0.05; });
                          if (cutLeft.length > 0) {
                              var maxR = 0;
                              for (var i = 0; i < cutLeft.length; i++) { 
                                  // If any part of the line (base or ruby) is cut, hide the whole line
                                  if (cutLeft[i].right > maxR) maxR = cutLeft[i].right; 
                              }
                              masks.left = Math.ceil(maxR + 1);
                          }
                          
                          // [Protection] Never mask text that is fully visible on the left side
                          var leftVisibleText = textVisible.filter(function(l) { return l.left >= -0.05; });
                          if (leftVisibleText.length > 0) {
                              var minL = w;
                              for (var i = 0; i < leftVisibleText.length; i++) {
                                  if (leftVisibleText[i].left < minL) minL = leftVisibleText[i].left;
                              }
                              // Ensure the mask stops exactly where the first uncut line begins
                              if (masks.left > minL - 2) masks.left = Math.max(0, Math.floor(minL - 2));
                          }

                          // [Vertical Mode Right Mask] 
                          // Normally 0 for vertical-rl next page, but calculate if needed for consistency
                          var cutRight = textVisible.filter(function(l) { return l.right > w + 0.05; });
                          if (cutRight.length > 0) {
                              var minLForRight = w;
                              for (var i = 0; i < cutRight.length; i++) { if (cutRight[i].left < minLForRight) minLForRight = cutRight[i].left; }
                              masks.right = Math.ceil(w - minLForRight + 1);
                              
                              // Protect text fully visible on the right
                              var rightVisibleText = textVisible.filter(function(l) { return l.right <= w + 0.05; });
                              if (rightVisibleText.length > 0) {
                                  var maxRForRight = 0;
                                  for (var i = 0; i < rightVisibleText.length; i++) {
                                      if (rightVisibleText[i].right > maxRForRight) maxRForRight = rightVisibleText[i].right;
                                  }
                                  if (masks.right > (w - maxRForRight)) masks.right = Math.max(0, Math.floor(w - maxRForRight));
                              }
                          }

                          // [Images Protection] Never cover images, cut or not
                          imageVisible.forEach(function(img) {
                              if (img.left < masks.left) masks.left = Math.max(0, Math.floor(img.left));
                              if (img.right > w - masks.right) masks.right = Math.max(0, Math.floor(w - img.right));
                          });
                      }
                       // Navigation direction filter: hide masks on the side we're coming from
                       if (!isVertical) {
                           if (window._scrollDir === 1) { masks.top = 0; }
                           if (window._scrollDir === -1) { masks.bottom = 0; }
                       } else {
                           if (window._scrollDir === 1) {
                               masks.right = 0; // Hides previous page content on the right, keep left for next
                           }
                           if (window._scrollDir === -1) { 
                               masks.left = 0;
                               masks.right = 0; // Show everything on previous
                           }
                       }
                       return masks;
                  };

                 window.updateMask = function() {
                     if (pagingMode !== 1) {
                         Android.updateBottomMask(0); Android.updateTopMask(0); Android.updateLeftMask(0); Android.updateRightMask(0);
                         return;
                     }
                     var masks = window.calculateMasks();
                     Android.updateTopMask(masks.top > 0 ? masks.top : 0);
                     Android.updateBottomMask(masks.bottom > 0 ? masks.bottom : 0);
                     Android.updateLeftMask(masks.left > 0 ? masks.left : 0);
                     Android.updateRightMask(masks.right > 0 ? masks.right : 0);
                 };

                 window.jumpToBottom = function() {
                     var FS = parseFloat(window.getComputedStyle(document.body).fontSize) || 16;
                     var gap = FS * 0.8;
                     if (isVertical) {
                         window.scrollTo(-1000000, 0);
                         if (pagingMode === 1) {
                             var w = document.documentElement.clientWidth;
                             var lines = window.getVisualLines();
                             if (lines.length > 0) {
                                 var farRightLine = lines[0];
                                 if (farRightLine.right > w) {
                                     var scrollDelta = farRightLine.right - (w - gap);
                                     window.scrollBy({ left: scrollDelta, behavior: 'instant' });
                                 }
                             }
                         }
                     } else {
                         window.scrollTo(0, 1000000);
                         if (pagingMode === 1) {
                             var lines = window.getVisualLines();
                             if (lines.length > 0) {
                                 var topCutLine = lines[0];
                                 if (topCutLine.top < 0) {
                                     var scrollDelta = topCutLine.top - gap;
                                     window.scrollBy({ top: scrollDelta, behavior: 'instant' });
                                 }
                             }
                         }
                     }
                 };

                 window.pageDown = function() {
                     window._scrollDir = 1;
                     var w = isVertical ? document.documentElement.clientWidth : window.innerWidth;
                     var h = window.innerHeight;
                     var isAtBottom = false;
                     if (!isVertical) {
                         if (h + window.pageYOffset >= document.documentElement.scrollHeight - 20) isAtBottom = true;
                     } else {
                         var maxScrollX = document.documentElement.scrollWidth - w;
                         if (window.pageXOffset <= -(maxScrollX - 20)) isAtBottom = true;
                     }
                     if (pagingMode === 1) {
                          var lines = window.getVisualLines();
                          if (lines.length > 0) {
                              var lastVisible = lines.filter(function(l) { return isVertical ? (l.left < w + 2 && l.right > -2) : (l.bottom > -2 && l.top < h + 2); }).pop();
                              if (lastVisible && lines.indexOf(lastVisible) === lines.length - 1) isAtBottom = true;
                           }
                       }

                      if (isAtBottom) { window.isScrolling = true; Android.autoLoadNext(); return; }
                     if (pagingMode === 1) {
                         var lines = window.getVisualLines();
                         var FS = parseFloat(window.getComputedStyle(document.body).fontSize) || 16;
                         var gap = (lines.some(function(l) { return (l.bottom - l.top > h * 0.8) || (l.right - l.left > w * 0.8); })) ? 0 : FS * 0.8;
                         if (!isVertical) {
                             var visible = lines.filter(function(l) { return l.bottom > -2 && l.top < h + 2; });
                             var scrollDelta = h;
                             if (visible.length > 0) {
                                 var last = visible[visible.length - 1];
                                 // [수정] 대상이 이미지인 경우 gap을 0으로 만들어 오차 없이 정렬
                                 if (last.bottom > h + 2 && last.top < h) scrollDelta = last.top - (last.isImageWrapper ? 0 : gap);
                                 else {
                                     var idx = lines.indexOf(last);
                                     if (idx >= 0 && idx < lines.length - 1) {
                                         var nextLine = lines[idx + 1];
                                         scrollDelta = nextLine.top - (nextLine.isImageWrapper ? 0 : gap);
                                     }
                                 }
                             }
                             window.scrollBy({ top: Math.min(scrollDelta, h), behavior: 'instant' });
                           } else {
                               var visible = lines.filter(function(l) { return l.left < w + 2 && l.right > -2; });
                               var scrollDelta = -w;
                               if (visible.length > 0) {
                                   var last = visible[visible.length - 1]; // 가시 영역의 가장 왼쪽 줄
                                   if (last.left < -0.05) {
                                       // 왼쪽이 잘려 있다면 해당 줄의 오른쪽 끝을 화면 오른쪽(w-gap)에 맞춤 (루비 노출)
                                       scrollDelta = last.right + (last.isImageWrapper ? 0 : gap * 1.25) - w;
                                   } else {
                                       var idx = lines.indexOf(last);
                                       if (idx >= 0 && idx < lines.length - 1) {
                                           var nextLine = lines[idx + 1]; // 다음 페이지의 첫 줄
                                           var alignDelta = nextLine.right + (nextLine.isImageWrapper ? 0 : gap * 1.25) - w; var skipDelta = -(w - last.left + 5); scrollDelta = Math.min(alignDelta, skipDelta);
                                       }
                                   }
                               }
                               window.scrollBy({ left: Math.max(scrollDelta, -w * 1.5), behavior: 'instant' });
                           }
                     } else {
                         var moveSize = (isVertical ? w : h) - 40;
                         if (isVertical) window.scrollBy({ left: -moveSize, behavior: 'instant' });
                         else window.scrollBy({ top: moveSize, behavior: 'instant' });
                     }
                     window.detectAndReportLine(); window.updateMask();
                 };

                 window.pageUp = function() {
                     window._scrollDir = -1;
                     var w = isVertical ? document.documentElement.clientWidth : window.innerWidth;
                     var h = window.innerHeight;
                     var isAtTop = false;
                     if (!isVertical) { if (window.pageYOffset <= 20) isAtTop = true; }
                     else { if (window.pageXOffset >= -20) isAtTop = true; }
                     if (pagingMode === 1) {
                          var lines = window.getVisualLines();
                          if (lines.length > 0) {
                              var firstVisible = lines.find(function(l) { return isVertical ? (l.left < w + 2 && l.right > -2) : (l.bottom > -2 && l.top < h + 2); });
                              if (firstVisible && lines.indexOf(firstVisible) === 0) isAtTop = true;
                          }
                      }

                      if (isAtTop) { window.isScrolling = true; Android.autoLoadPrev(); return; }
                     if (pagingMode === 1) {
                         var lines = window.getVisualLines();
                         var FS = parseFloat(window.getComputedStyle(document.body).fontSize) || 16;
                         var gap = (lines.some(function(l) { return (l.bottom - l.top > h * 0.8) || (l.right - l.left > w * 0.8); })) ? 0 : FS * 0.8;
                         if (!isVertical) {
                             var firstIdx = lines.findIndex(function(l) { return l.top >= -2; });
                             var prevIdx = firstIdx > 0 ? firstIdx - 1 : -1;
                             if (prevIdx >= 0) {
                                 var targetBottom = lines[prevIdx].bottom;
                                 var topIdx = prevIdx;
                                 for (var i = prevIdx; i >= 0; i--) { if (targetBottom - lines[i].top <= h - gap) topIdx = i; else break; }
                                 var targetLine = lines[topIdx];
                                 // [수정] 대상이 이미지인 경우 gap을 0으로 처리
                                 window.scrollBy({ top: Math.max(targetLine.top - (targetLine.isImageWrapper ? 0 : gap), -h), behavior: 'instant' });
                             } else window.scrollBy({ top: -h, behavior: 'instant' });
                           } else {
                                var firstVisible = lines.find(function(l) { return l.right < w + 2 && l.left > -2; });
                                var scrollDelta = w;
                                if (firstVisible) {
                                    var firstIdx = lines.indexOf(firstVisible);
                                    if (firstIdx > 0) {
                                        var prevIdx = firstIdx - 1; // 화면 오른쪽 바깥의 첫 번째 줄 (이전 페이지의 왼쪽 끝)
                                        var targetLeft = lines[prevIdx].left; // 이전 페이지 블록의 왼쪽 기준 좌표
                                        var topIdx = prevIdx;
                                        
                                        // 오른쪽(과거 방향)으로 탐색하며 한 화면 너비(w)에 들어오는 가장 첫 줄(오른쪽 끝)을 찾음
                                        for (var i = prevIdx; i >= 0; i--) {
                                            // 현재 탐색 중인 줄의 오른쪽 끝 - 기준점의 왼쪽 끝 = 누적된 너비
                                            if (lines[i].right - targetLeft <= w - gap) {
                                                topIdx = i;
                                            } else {
                                                break;
                                            }
                                        }
                                        var targetLine = lines[topIdx];
                                        // 찾아낸 첫 줄을 화면 오른쪽 끝(w)에 맞추기 위한 스크롤 이동량 계산
                                        scrollDelta = targetLine.right + (targetLine.isImageWrapper ? 0 : gap * 1.25) - w;
                                    }
                                }
                                window.scrollBy({ left: Math.min(scrollDelta, w * 1.5), behavior: 'instant' });
                           }
                     } else {
                         var moveSize = (isVertical ? w : h) - 40;
                         if (isVertical) window.scrollBy({ left: moveSize, behavior: 'instant' });
                     else window.scrollBy({ top: -moveSize, behavior: 'instant' });
                     }
                     window.detectAndReportLine(); window.updateMask();
                 };

                   // [수정됨] 기존 window.onscroll 전체를 아래 코드로 교체
                  var scrollTimer = null;
                  window.onscroll = function() {
                       if (window.isSystemScrolling) return; 

                      if (scrollTimer) clearTimeout(scrollTimer);
                      scrollTimer = setTimeout(function() {
                           if (window.isSystemScrolling) return; 

                          window.detectAndReportLine();
                          window.updateMask();
                          
                          if (window.isScrolling) return; 
                          
                          // 복잡한 여백 계산은 checkPreload가 담당
                          window.checkPreload();
                      }, 150); 
                  };

                 setTimeout(window.updateMask, 100);
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
                    width: 100vw !important;
                    height: 100vh !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    /* 세로쓰기일 땐 X축만, 가로쓰기일 땐 Y축만 허용 */
                    overflow-x: ${if (isVertical) "scroll" else "hidden"} !important;
                    overflow-y: ${if (isVertical) "hidden" else "scroll"} !important;
                    /* 튕김 효과 방지 */
                    overscroll-behavior: none !important;
                    /* 터치 동작 제한 (세로쓰기면 좌우 스크롤만 허용) */
                    touch-action: ${if (isVertical) "pan-x" else "pan-y"} !important;
                    overflow-anchor: auto !important;
                    
                    /* [중요] html에도 writing-mode 적용하여 브라우저 좌표축 동기화 */
                    writing-mode: ${if (isVertical) "vertical-rl" else "horizontal-tb"} !important;
                    -webkit-writing-mode: ${if (isVertical) "vertical-rl" else "horizontal-tb"} !important;
                }
            
                 body {
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
                     overflow-anchor: auto !important;
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
                      overflow-anchor: auto !important;
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
               </style>
        """.trimIndent()
    }
}