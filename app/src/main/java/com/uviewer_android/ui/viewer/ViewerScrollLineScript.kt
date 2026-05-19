package com.uviewer_android.ui.viewer

internal object ViewerScrollLineScript {
    fun install(): String {
        return """
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
                      var padding = isVertical ? w * 0.5 : h * 0.5;
                      
                      var chunks = document.querySelectorAll('.content-chunk');
                      var visibleChunks = Array.from(chunks).filter(function(chunk) {
                          var r = chunk.getBoundingClientRect();
                          if (isVertical) return r.right > -padding && r.left < w + padding;
                          return r.bottom > -padding && r.top < h + padding;
                      });

                      if (visibleChunks.length === 0) visibleChunks = [document.body];

                      for (var c = 0; c < visibleChunks.length; c++) {
                          var walker = document.createTreeWalker(
                              visibleChunks[c],
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
                                  textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: true, element: node });
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
                                              parts.push({ top: current.top, bottom: current.bottom, left: current.left, right: current.right, isImageWrapper: false, element: el.closest('[id^="line-"]') });
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
                                      if (r.width > 0 && r.height > 0) { var isBlank = node.nodeType === 1 && node.classList && node.classList.contains("blank-line"); var lineElement = (node.nodeType === 1 ? node : el).closest('[id^="line-"]'); textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: isImg, isBlankLine: isBlank, element: lineElement }); }
                                  }
                              }
                          }
                      }
                     
                     if (!isVertical) {
                         textLines.sort(function(a, b) { var diff = a.top - b.top; return diff !== 0 ? diff : a.left - b.left; });
                         var lines = [];
                         if (textLines.length === 0) return lines;
                         var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: !!textLines[0].isImageWrapper, element: textLines[0].element };
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
                                 if (!currentLine.element && r.element) currentLine.element = r.element;
                             } else {
                                 lines.push(currentLine);
                                 currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: !!r.isImageWrapper, element: r.element };
                             }
                         }
                         lines.push(currentLine);
                         return lines;
                     } else {
                         textLines.sort(function(a, b) { var diff = b.right - a.right; return diff !== 0 ? diff : a.top - b.top; });
                         var lines = [];
                         if (textLines.length === 0) return lines;
                         var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: !!textLines[0].isImageWrapper, element: textLines[0].element };
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
                                 if (!currentLine.element && r.element) currentLine.element = r.element;
                             } else {
                                 lines.push(currentLine);
                                 currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: !!r.isImageWrapper, element: r.element };
                             }
                         }
                         lines.push(currentLine);
                         return lines;
                     }
                 };
        """.trimIndent()
    }
}
