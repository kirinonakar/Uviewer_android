package com.uviewer_android.ui.viewer

internal object ViewerScrollMaskScript {
    fun install(): String {
        return """
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

                 window.updateMask = function(force) {
                      var masks = window.calculateMasks();
                      
                      var now = Date.now();
                      if (!force && window._lastBridgeCall && (now - window._lastBridgeCall < 50)) return;
                      window._lastBridgeCall = now;

                      Android.updateTopMask(masks.top > 0 ? masks.top : 0);
                      Android.updateBottomMask(masks.bottom > 0 ? masks.bottom : 0);
                      Android.updateLeftMask(masks.left > 0 ? masks.left : 0);
                      Android.updateRightMask(masks.right > 0 ? masks.right : 0);
                  };
        """.trimIndent()
    }
}
