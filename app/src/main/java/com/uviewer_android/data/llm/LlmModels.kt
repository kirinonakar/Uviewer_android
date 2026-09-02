package com.uviewer_android.data.llm

/** Providers supported by the in-app text explanation action. */
enum class LlmProvider(
    val storageKey: String,
    val defaultModel: String
) {
    GOOGLE("google", "gemini-3.6-flash"),
    OLLAMA_CLOUD("ollama_cloud", "gpt-oss:120b"),
    OPENCODE_GO("opencode_go", "gpt-5.6-luna"),
    ZEN("zen", "gpt-5.6-sol");

    companion object {
        fun fromStorageKey(value: String?): LlmProvider {
            return entries.firstOrNull { it.storageKey == value } ?: GOOGLE
        }
    }
}

enum class LlmThinkingLevel(val storageKey: String) {
    DEFAULT("default"),
    MINIMAL("minimal"),
    DISABLE("disable"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromStorageKey(value: String?): LlmThinkingLevel {
            return entries.firstOrNull { it.storageKey == value } ?: DEFAULT
        }

        fun optionsFor(provider: LlmProvider): List<LlmThinkingLevel> {
            return when (provider) {
                LlmProvider.GOOGLE -> listOf(DEFAULT, MINIMAL, LOW, MEDIUM, HIGH)
                LlmProvider.OLLAMA_CLOUD -> listOf(DEFAULT, DISABLE, LOW, MEDIUM, HIGH)
                LlmProvider.OPENCODE_GO,
                LlmProvider.ZEN -> listOf(DEFAULT, LOW, MEDIUM, HIGH)
            }
        }
    }
}

data class LlmModelOption(
    val id: String,
    val displayName: String = id
)

data class LlmPromptPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val isBuiltIn: Boolean = false
)

fun LlmThinkingLevel.isAllowedFor(provider: LlmProvider): Boolean {
    return this in LlmThinkingLevel.optionsFor(provider)
}
