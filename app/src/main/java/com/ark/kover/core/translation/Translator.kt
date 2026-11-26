package com.ark.kover.core.translation

/**
 * Result of a single translation attempt.
 *
 * [Translator.translate] returns the original text when it cannot translate, which is
 * fine for the metadata editor but useless for a bulk job: the auto-translate screen has
 * to tell "already in the target language" from "provider failed" for its report.
 */
sealed interface TranslationOutcome {
    data class Translated(val text: String) : TranslationOutcome
    data object SameLanguage : TranslationOutcome
    data object Failed : TranslationOutcome
}

/**
 * Translates text between languages.
 */
interface Translator {
    val id: String
    val displayName: String

    fun isConfigured(): Boolean

    /**
     * Translate text from source language to target language.
     * Returns the translated text, or the original if translation fails.
     */
    suspend fun translate(text: String, from: String, to: String): String

    /** Detailed variant used by bulk tools; defaults to [translate] plus a comparison. */
    suspend fun translateWithOutcome(
        text: String,
        from: String,
        to: String,
    ): TranslationOutcome {
        val result = translate(text, from, to)
        return when {
            result.isBlank() -> TranslationOutcome.Failed
            result.trim().equals(text.trim(), ignoreCase = true) -> TranslationOutcome.SameLanguage
            else -> TranslationOutcome.Translated(result)
        }
    }
}
