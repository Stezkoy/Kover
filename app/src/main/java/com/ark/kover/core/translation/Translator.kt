package com.ark.kover.core.translation

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
}
