package com.ark.kover.core.launcher

import android.content.Context
import android.net.Uri
import com.ark.kover.core.model.Asset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and writes artwork in an application-wide scope.
 *
 * Applying an image used to run in the editor's `viewModelScope`, so leaving the
 * screen (Settings, Library, back) cancelled the download and nothing was written.
 * This service finishes the job even when the user navigates away; the editor only
 * listens for the result to refresh its previews.
 */
@Singleton
class AssetApplyService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val switchable: SwitchableLauncherAdapter,
    private val httpClient: OkHttpClient,
) {
    data class Result(
        val system: String,
        val gameName: String,
        val kind: String,
        val success: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 32)
    val results = _results.asSharedFlow()

    /** Launcher type captured now, so a later switch cannot redirect the write. */
    fun apply(
        launcherType: String,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        asset: Asset,
    ) {
        val launcher = switchable.adapterFor(launcherType)
        scope.launch {
            val bytes = download(asset)
            val ok = bytes != null && runCatching {
                launcher.writeAsset(
                    context.contentResolver,
                    launcherDirUri,
                    system,
                    gameName,
                    kind,
                    asset,
                    bytes,
                )
            }.onFailure {
                android.util.Log.e("AssetApply", "write failed for $system/$gameName/$kind", it)
            }.isSuccess
            android.util.Log.d(
                "AssetApply",
                "apply ${if (ok) "done" else "failed"}: $system/$gameName -> $kind",
            )
            _results.tryEmit(Result(system, gameName, kind, ok))
        }
    }

    private fun download(asset: Asset): ByteArray? = try {
        if (asset.url.startsWith("http://") || asset.url.startsWith("https://")) {
            httpClient.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } else {
            File(asset.url).takeIf { it.exists() }?.readBytes()
        }
    } catch (e: Exception) {
        android.util.Log.e("AssetApply", "download failed: ${asset.url}", e)
        null
    }
}
