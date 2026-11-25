package com.ark.kover.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.ark.kover.BuildConfig
import com.ark.kover.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val version: String,
    val url: String,
    val notes: String,
    val apkUrl: String? = null,
)

/**
 * Checks the latest GitHub release of the repository this app is built from.
 * No API key required (public repo, anonymous rate limit is enough).
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val httpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    var isChecking by mutableStateOf(false)
        private set
    var latest by mutableStateOf<UpdateInfo?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var lastCheckedAt by mutableStateOf(0L)
        private set

    // Download state for the in-app updater.
    var isDownloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var downloadedFile by mutableStateOf<File?>(null)
        private set
    var downloadError by mutableStateOf<String?>(null)
        private set

    /** Installed version, read from the package manager (BuildConfig can go stale on incremental builds). */
    @Suppress("DEPRECATION")
    val currentVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: BuildConfig.VERSION_NAME
        }.getOrDefault(BuildConfig.VERSION_NAME)

    fun isUpdateAvailable(): Boolean {
        val remote = latest ?: return false
        return isNewer(remote.version, currentVersion)
    }

    /** Called once per app start; respects the "check automatically" switch and 24h throttle. */
    suspend fun checkOnStartIfEnabled() {
        val s = settingsRepo.settings.first()
        if (!s.updateCheckEnabled) return
        if (System.currentTimeMillis() - s.lastUpdateCheckAt < 24 * 60 * 60 * 1000L) return
        check(silent = true)
    }

    suspend fun check(silent: Boolean = false) {
        if (isChecking) return
        isChecking = true
        lastError = null
        try {
            val result = withContext(Dispatchers.IO) { fetchLatest() }
            if (result != null) {
                latest = result
            } else {
                if (!silent) lastError = "no-releases"
            }
            lastCheckedAt = System.currentTimeMillis()
            settingsRepo.update { it.copy(lastUpdateCheckAt = lastCheckedAt) }
        } catch (e: Exception) {
            android.util.Log.e("UpdateRepository", "check failed", e)
            if (!silent) lastError = e.message ?: "error"
        } finally {
            isChecking = false
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Kover-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: return null
            val root = Json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: return null
            val url = root["html_url"]?.jsonPrimitive?.content ?: ""
            val notes = root["body"]?.jsonPrimitive?.content.orEmpty()
            val apkUrl = root["assets"]?.jsonArray.orEmpty()
                .firstOrNull { asset ->
                    asset.jsonObject["name"]?.jsonPrimitive?.content
                        ?.endsWith(".apk", ignoreCase = true) == true
                }
                ?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.content
            return UpdateInfo(version = tag, url = url, notes = notes, apkUrl = apkUrl)
        }
    }

    /** Streams the release APK into cacheDir and reports 0..1 progress. */
    suspend fun download() {
        val info = latest ?: return
        val url = info.apkUrl ?: run {
            downloadError = "no-apk"
            return
        }
        if (isDownloading) return
        isDownloading = true
        progress = 0f
        downloadError = null
        downloadedFile = null
        try {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "kover-${info.version}.apk")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Kover-Android")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty body")
                    val total = body.contentLength().coerceAtLeast(0L)
                    var read = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                read += n
                                if (total > 0) progress = (read.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                    if (target.length() < 1024) error("downloaded file is too small")
                    downloadedFile = target
                    progress = 1f
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("UpdateRepository", "download failed", e)
            downloadError = e.message ?: "error"
            downloadedFile = null
        } finally {
            isDownloading = false
        }
    }

    /** Opens the system package installer for the downloaded APK. */
    fun install(): Boolean {
        val file = downloadedFile ?: return false
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            android.util.Log.e("UpdateRepository", "install failed", it)
            false
        }
    }

    /** Forget the downloaded file (e.g. version changed). */
    fun resetDownload() {
        downloadedFile = null
        progress = 0f
        downloadError = null
    }

    companion object {
        const val GITHUB_REPO = "Stezkoy/Kover"

        /** Compares "v1.2.3" / "1.2" style versions. */
        fun isNewer(remote: String, current: String): Boolean {
            val a = remote.trim().removePrefix("v").split('.', '-', '+')
            val b = current.trim().removePrefix("v").split('.', '-', '+')
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i)?.toIntOrNull() ?: 0
                val y = b.getOrNull(i)?.toIntOrNull() ?: 0
                if (x != y) return x > y
            }
            return false
        }
    }
}
