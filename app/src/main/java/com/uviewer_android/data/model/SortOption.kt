package com.uviewer_android.data.model

enum class SortOption {
    NAME,
    DATE_ASC,
    DATE_DESC,
    SIZE_ASC,
    SIZE_DESC;

    fun nextToggleOption(): SortOption = when (this) {
        NAME -> DATE_DESC
        DATE_DESC -> DATE_ASC
        DATE_ASC -> NAME
        SIZE_ASC, SIZE_DESC -> NAME
    }
}
