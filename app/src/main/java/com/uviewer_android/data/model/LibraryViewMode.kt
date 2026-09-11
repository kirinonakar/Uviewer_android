package com.uviewer_android.data.model

enum class LibraryViewMode(val columns: Int) {
    GRID_2(2),
    GRID_4(4),
    LIST(0);

    val isGrid: Boolean get() = this != LIST

    fun next(): LibraryViewMode = when (this) {
        GRID_2 -> GRID_4
        GRID_4 -> LIST
        LIST -> GRID_2
    }

    companion object {
        fun fromStoredValue(value: String?, legacyIsGrid: Boolean): LibraryViewMode =
            entries.firstOrNull { it.name == value } ?: if (legacyIsGrid) GRID_2 else LIST
    }
}
