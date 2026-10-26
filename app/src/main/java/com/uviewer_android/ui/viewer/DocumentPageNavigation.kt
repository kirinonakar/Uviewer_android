package com.uviewer_android.ui.viewer

/** Main-thread state shared by touch and volume-key paging. */
internal class DocumentPageNavigation {
    private var url: String? = null
    private var initializing = false
    var isReady: Boolean = false
        private set
    private val pending = ArrayDeque<Boolean>()
    fun isCurrent(url: String?): Boolean = url != null && this.url == url

    fun beginPreparation() {
        url = null
        initializing = false
        isReady = false
    }

    fun beginLoad(url: String) {
        this.url = url
        initializing = false
        isReady = false
    }

    fun beginInitialization(url: String?): Boolean {
        if (url == null || this.url != url || initializing || isReady) return false
        initializing = true
        return true
    }

    fun finishInitialization(url: String?, execute: (Boolean) -> Unit): Boolean {
        if (url == null || this.url != url || !initializing || isReady) return false
        initializing = false
        isReady = true
        while (pending.isNotEmpty()) execute(pending.removeFirst())
        return true
    }

    fun request(forward: Boolean, execute: (Boolean) -> Unit) {
        if (isReady) execute(forward) else pending.addLast(forward)
    }
}
