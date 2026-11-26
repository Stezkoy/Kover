package com.ark.kover.core.translation

import com.ark.kover.core.model.Metadata

/** Description to translate: the main field or any localized variant the source shipped. */
fun Metadata.effectiveDescription(): String =
    desc.ifBlank { descByLang["en"].orEmpty() }
        .ifBlank { descByLang.values.firstOrNull { it.isNotBlank() }.orEmpty() }

/**
 * Cheap script-based check for "already in the target language".
 *
 * Only the non-Latin targets can be recognized reliably from the script (Cyrillic for RU,
 * kana for JA, Hangul for KO, Han for ZH). Latin languages (DE/ES/FR/IT/PT/EN) are never
 * skipped: Italian and Spanish look identical to a character counter, and skipping a
 * German description that is being translated to English would be a data loss.
 *
 * The translator itself detects the source language, so an unnecessary call is cheap:
 * MyMemory returns the text unchanged when source == target.
 */
fun looksLikeTargetLanguage(text: String, target: String): Boolean {
    if (text.isBlank()) return false
    var cyrillic = 0
    var kana = 0
    var hangul = 0
    var han = 0
    var latin = 0
    for (ch in text) {
        when {
            ch in '\u0400'..'\u04FF' -> cyrillic++
            ch in '\u3040'..'\u30FF' -> kana++
            ch in '\uAC00'..'\uD7AF' -> hangul++
            ch in '\u4E00'..'\u9FFF' -> han++
            ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
        }
    }
    return when (target.lowercase()) {
        "ru" -> cyrillic > 0 && cyrillic * 5 >= cyrillic + latin
        "ja" -> kana > 0
        "ko" -> hangul > 0
        "zh" -> han > 0 && kana == 0
        else -> false
    }
}
