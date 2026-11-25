package com.ark.kover.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Generates poster frames for non-image ES-DE media:
 * the first frame of a video and the first page of a PDF manual.
 * Results are cached in the app cache directory.
 */
object MediaPreview {

    fun poster(context: Context, path: String, kind: String): String? {
        val source = File(path)
        if (!source.isFile) return null
        val dir = File(context.cacheDir, "media_previews").apply { mkdirs() }
        // v2: black-frame detection + screenshot fallback changed the output.
        val out = File(dir, "v2_${kind}_${path.hashCode()}_${source.length()}.jpg")
        if (out.isFile && out.length() > 0) return out.absolutePath

        val bitmap = runCatching {
            when (kind) {
                "videos" -> videoFrame(source)
                "manuals" -> pdfPage(source)
                else -> null
            }
        }.getOrNull() ?: return null

        return runCatching {
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            }
            bitmap.recycle()
            out.absolutePath
        }.getOrNull()
    }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val samples = if (durationMs > 1000) {
                listOf(durationMs / 10, durationMs / 4, durationMs / 2, durationMs * 3 / 4)
            } else {
                listOf(0L, 500L, 1_000L)
            }
            var fallback: Bitmap? = null
            for (positionMs in samples) {
                val frame = retriever.getFrameAtTime(
                    positionMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: continue
                if (fallback == null) fallback = frame
                if (isBright(frame)) return frame
            }
            // All sampled frames are black: let the caller fall back to a screenshot.
            fallback?.recycle()
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Rejects pitch-black opening frames so slots do not look empty. */
    private fun isBright(bitmap: Bitmap): Boolean {
        var sum = 0L
        var count = 0
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += (r * 299 + g * 587 + b * 114) / 1000
                count++
                x += stepX
            }
            y += stepY
        }
        return count > 0 && sum / count > 18
    }

    private fun pdfPage(file: File): Bitmap? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (renderer.pageCount <= 0) return null
            val page = renderer.openPage(0)
            try {
                val width = 480
                val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }
}
