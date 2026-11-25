package com.ark.kover.core.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free translation: MyMemory first, Google's public gtx endpoint as a fallback.
 *
 * Long descriptions are split into <=450 character chunks (MyMemory rejects longer
 * texts with HTTP 500) and joined back afterwards. The source language is detected
 * automatically when [from] is "auto".
 */
@Singleton
class MyMemoryTranslator @Inject constructor(
    private val httpClient: OkHttpClient,
) : Translator {

    override val id = "mymemory"
    override val displayName = "MyMemory"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isConfigured(): Boolean = true

    override suspend fun translate(text: String, from: String, to: String): String {
        if (text.isBlank()) return text
        val source = if (from == "auto" || from.isBlank()) detectLanguage(text) else from
        if (source.equals(to, ignoreCase = true)) return text

        val chunks = chunk(text, 450)
        val out = StringBuilder()
        for (chunk in chunks) {
            val translated = translateChunk(chunk, source, to)
                ?: translateWithGtx(chunk, to)
                ?: return text
            if (out.isNotEmpty()) out.append(' ')
            out.append(translated.trim())
        }
        return out.toString()
    }

    private fun translateChunk(text: String, from: String, to: String): String? {
        return try {
            val url = "https://api.mymemory.translated.net/get"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("langpair", "$from|$to")
                .build()
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = json.parseToJsonElement(body).jsonObject
            val status = root["responseStatus"]?.jsonPrimitive?.content
            if (status != null && status != "200") return null
            val translated = root["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.content
            translated?.takeIf { it.isNotBlank() && !it.contains("MYMEMORY WARNING") }
        } catch (e: Exception) {
            null
        }
    }

    /** Public gtx endpoint: no key, auto-detects the source language. */
    private fun translateWithGtx(text: String, to: String): String? {
        return try {
            val url = "https://translate.googleapis.com/translate_a/single"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", to)
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build()
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = json.parseToJsonElement(body) as? JsonArray ?: return null
            val sentences = root.firstOrNull() as? JsonArray ?: return null
            val builder = StringBuilder()
            sentences.forEach { item ->
                val part = (item as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content
                if (!part.isNullOrBlank()) builder.append(part)
            }
            builder.toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Splits text on sentence boundaries, keeping each chunk under [limit] chars. */
    private fun chunk(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val result = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[.!?…])\\s+|\\n+"))
        val current = StringBuilder()
        for (sentence in sentences) {
            for (part in splitLong(sentence, limit)) {
                if (current.isEmpty()) {
                    current.append(part)
                } else if (current.length + 1 + part.length <= limit) {
                    current.append(' ').append(part)
                } else {
                    result.add(current.toString())
                    current.setLength(0)
                    current.append(part)
                }
                if (current.length >= limit) {
                    result.add(current.toString())
                    current.setLength(0)
                }
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.ifEmpty { listOf(text) }
    }

    private fun splitLong(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val parts = mutableListOf<String>()
        val builder = StringBuilder()
        text.split(' ').forEach { word ->
            if (builder.isNotEmpty() && builder.length + 1 + word.length > limit) {
                parts.add(builder.toString())
                builder.setLength(0)
            }
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(word)
        }
        if (builder.isNotEmpty()) parts.add(builder.toString())
        return parts
    }

    private fun detectLanguage(text: String): String {
        var cyrillic = 0
        var hiragana = 0
        var hangul = 0
        var cjk = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in '\u0400'..'\u04FF' -> cyrillic++
                ch in '\u3040'..'\u30FF' -> hiragana++
                ch in '\uAC00'..'\uD7AF' -> hangul++
                ch in '\u4E00'..'\u9FFF' -> cjk++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }
        return when {
            hiragana > 0 -> "ja"
            hangul > 0 -> "ko"
            cjk > 0 && cyrillic == 0 -> "zh"
            cyrillic > latin / 2 && cyrillic > 0 -> "ru"
            else -> "en"
        }
    }
}
