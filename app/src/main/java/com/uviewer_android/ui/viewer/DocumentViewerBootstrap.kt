package com.uviewer_android.ui.viewer

import org.json.JSONObject

internal object DocumentViewerBootstrap {
    fun script(url: String, scrollLogic: String): String = """
        (function() {
            var expectedUrl = ${JSONObject.quote(url)};
            function prepare() {
                if (window.location.href !== expectedUrl) return;
                if (typeof window.renderMath === 'function') window.renderMath();
                var fontsReady = document.fonts ? document.fonts.ready : Promise.resolve();
                var imagesReady = Array.from(document.images).map(function(image) {
                    if (image.complete) return Promise.resolve();
                    return new Promise(function(resolve) {
                        image.addEventListener('load', resolve, { once: true });
                        image.addEventListener('error', resolve, { once: true });
                    });
                });
                Promise.all([fontsReady].concat(imagesReady)).then(function() {
                    requestAnimationFrame(function() {
                        if (window.location.href !== expectedUrl) return;
                        $scrollLogic
                        Android.onViewerReady(expectedUrl);
                    });
                });
            }
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', prepare, { once: true });
            } else {
                prepare();
            }
        })();
    """.trimIndent()
}
