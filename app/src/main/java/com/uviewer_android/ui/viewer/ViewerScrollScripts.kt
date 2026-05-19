package com.uviewer_android.ui.viewer

internal object ViewerScrollScripts {
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

                ${ViewerScrollCoreScript.install(targetLine, totalLines, linePrefix)}
                ${ViewerScrollPreloadScript.install(isImageOnly)}
                ${ViewerScrollChunkScript.install()}
                ${ViewerScrollLineScript.install()}
                ${ViewerScrollMaskScript.install()}
                ${ViewerScrollPagingScript.install()}
                ${ViewerScrollEventsScript.install()}
            })();
        """.trimIndent()
    }
}
