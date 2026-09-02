package com.uviewer_android.ui.viewer

sealed class LlmUiState {
    data object Idle : LlmUiState()

    data class Loading(
        val selectedText: String
    ) : LlmUiState()

    data class Success(
        val selectedText: String,
        val response: String
    ) : LlmUiState()

    data class Error(
        val selectedText: String,
        val message: String
    ) : LlmUiState()
}
