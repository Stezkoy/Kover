package com.ark.kover.core.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** A failed request that took longer than this is not retried, to avoid doubling timeouts. */
private const val QUICK_FAILURE_LIMIT_MS = 15_000L
private const val RETRY_DELAY_MS = 700L

/**
 * Free translation.
 *
 * 1. Google's fast web endpoint (clients5, the one Chrome's dictionary uses) — usually
 *    answers in 1–3 seconds even for a long description.
 * 2. MyMemory — slower fallback; sometimes queues for tens of seconds.
 * 3. Google gtx — last resort (blocked on some networks).
 *
 * Long descriptions are split into <=450 character chunks (MyMemory rejects longer texts
 * with HTTP 500) and joined back afterwards. A quick failure (drop, HTTP error) is retried
 * once; a slow timeout is not, so a dead provider cannot stall the queue twice.
 */
@Singleton
class MyMemoryTranslator @Inject constructor(
    private val httpClient: OkHttpClient,
) : Translator {

    override val id = "mymemory"
    override val displayName = "Google + MyMemory"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Fast providers get a hard cap; MyMemory keeps the shared (longer) timeout. */
    private val fastClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun isConfigured(): Boolean = true

    override suspend fun translate(text: String, from: String, to: String): String =
        when (val outcome = translateWithOutcome(text, from, to)) {
            is TranslationOutcome.Translated -> outcome.text
            else -> text
        }

    override suspend fun translateWithOutcome(
        text: String,
        from: String,
        to: String,
    ): TranslationOutcome {
        if (text.isBlank()) return TranslationOutcome.Failed
        val source = if (from == "auto" || from.isBlank()) detectLanguage(text) else from
        if (source.equals(to, ignoreCase = true)) return TranslationOutcome.SameLanguage

        val chunks = chunk(text, 450)
        val out = StringBuilder()
        for ((index, chunk) in chunks.withIndex()) {
            // Bulk runs can be long: give cancellation a chance between chunks.
            currentCoroutineContext().ensureActive()
            if (index > 0) delay(200)
            val translated = translateChunk(chunk, source, to)
                ?: return TranslationOutcome.Failed
            if (out.isNotEmpty()) out.append(' ')
            out.append(translated.trim())
        }
        val result = out.toString().trim()
        return when {
            result.isBlank() -> TranslationOutcome.Failed
            result.equals(text.trim(), ignoreCase = true) -> TranslationOutcome.SameLanguage
            else -> TranslationOutcome.Translated(result)
        }
    }

    /**
     * Provider chain for one chunk. A quick failure is retried once;
     * a slow timeout is returned as a failure without another long wait.
     */
    private suspend fun translateChunk(text: String, from: String, to: String): String? {
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val started = System.currentTimeMillis()
            val translated = translateWithClients5(text, to)
                ?: translateWithMyMemory(text, from, to)
                ?: translateWithGtx(text, to)
            if (translated != null) return translated
            val elapsed = System.currentTimeMillis() - started
            if (attempt == 0 && elapsed < QUICK_FAILURE_LIMIT_MS) {
                android.util.Log.d(
                    "Translator",
                    "quick failure in ${elapsed}ms, retrying chunk",
                )
                delay(RETRY_DELAY_MS)
            } else {
                return null
            }
        }
        return null
    }

    /** Fast Google web endpoint used by Chrome's dictionary; returns [text, source]. */
    private suspend fun translateWithClients5(text: String, to: String): String? {
        return try {
            val url = "https://clients5.google.com/translate_a/t"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client", "dict-chrome-ex")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", to)
                .addQueryParameter("q", text)
                .build()
            execute(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build(),
                fastClient,
            ).use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = json.parseToJsonElement(body) as? JsonArray ?: return null
                val first = root.firstOrNull() as? JsonArray ?: return null
                val parts = first.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (parts.isEmpty()) return null
                // [["translated text", "en"]]; the last string is the detected source.
                val translated = if (
                    parts.size >= 2 &&
                    parts.last().matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z]+)?"))
                ) {
                    parts.dropLast(1).joinToString(" ")
                } else {
                    parts.joinToString(" ")
                }
                translated.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun translateWithMyMemory(text: String, from: String, to: String): String? {
        return try {
            val url = "https://api.mymemory.translated.net/get"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("langpair", "$from|$to")
                .build()
            execute(Request.Builder().url(url).build()).use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                val status = root["responseStatus"]?.jsonPrimitive?.content
                if (status != null && status != "200") return null
                val translated = root["responseData"]?.jsonObject?.get("translatedText")
                    ?.jsonPrimitive?.content
                translated?.takeIf { it.isNotBlank() && !it.contains("MYMEMORY WARNING") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Public gtx endpoint: no key, auto-detects the source language. */
    private suspend fun translateWithGtx(text: String, to: String): String? {
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
            execute(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build(),
                fastClient,
            ).use { response ->
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * OkHttp async call as a cancellable suspend function: the auto-translate Stop button
     * aborts the in-flight request immediately instead of waiting for the network timeout.
     */
    private suspend fun execute(
        request: Request,
        client: OkHttpClient = httpClient,
    ): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
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
