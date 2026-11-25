package com.ark.kover.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Recreates ES-DE's miximage layout:
 *
 * 640x480 (or 1280x960 when the screenshot is at least 640 px wide):
 *  - centered screenshot 530x400 with a rounded frame sampled from the screenshot,
 *  - marquee in the top-right corner,
 *  - 3D box (or cover) in the bottom-left corner,
 *  - physical media next to the box.
 */
object MixImageComposer {

    data class Sources(
        val screenshot: File?,
        val marquee: File?,
        val box: File?,
        val boxIs3D: Boolean = true,
        val media: File?,
    )

    fun compose(sources: Sources): ByteArray? {
        val screenshot = sources.screenshot?.let { decode(it) } ?: return null
        val multiplier = if (screenshot.width < 640) 1 else 2

        val canvasWidth = 640 * multiplier
        val canvasHeight = 480 * multiplier
        val shotWidth = 530 * multiplier
        val shotHeight = 400 * multiplier
        val shotOffsetX = 20 * multiplier
        val shotX = canvasWidth / 2 - shotWidth / 2 + shotOffsetX
        val shotY = canvasHeight / 2 - shotHeight / 2

        val canvas = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val draw = Canvas(canvas)

        // Screenshot, center-cropped to the 530x400 box.
        val fittedShot = cropToAspect(screenshot, shotWidth.toFloat() / shotHeight.toFloat())
            .let { Bitmap.createScaledBitmap(it, shotWidth, shotHeight, true) }

        val frameColor = sampleFrameColor(fittedShot)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = frameColor }
        val frameInset = 6 * multiplier
        val radius = 8f * multiplier
        draw.drawRoundRect(
            RectF(
                shotX - frameInset.toFloat(),
                shotY - frameInset.toFloat(),
                shotX + shotWidth + frameInset.toFloat(),
                shotY + shotHeight + frameInset.toFloat(),
            ),
            radius,
            radius,
            framePaint,
        )
        draw.drawBitmap(fittedShot, shotX.toFloat(), shotY.toFloat(), null)

        // Marquee, top-right.
        val marquee = sources.marquee?.let { decode(it) }
        if (marquee != null) {
            val trimmed = trimTransparent(marquee)
            val targetWidth = 310 * multiplier
            val targetHeight = 230 * multiplier
            val size = marqueeSize(trimmed.width, trimmed.height, targetWidth, targetHeight)
            val scaled = Bitmap.createScaledBitmap(trimmed, size.first, size.second, true)
            drawWithShadow(
                draw,
                scaled,
                (canvasWidth - scaled.width - 12 * multiplier).toFloat(),
                (12 * multiplier).toFloat(),
                multiplier,
            )
        }

        // 3D box (or cover), bottom-left.
        val box = sources.box?.let { decode(it) }
        var boxWidth = 0
        var boxX = 0
        if (box != null) {
            val trimmed = trimTransparent(box)
            val targetHeight = 300 * multiplier
            val targetWidth = if (sources.boxIs3D) 310 * multiplier else 250 * multiplier
            val scale = targetHeight.toFloat() / trimmed.height
            var width = (trimmed.width * scale).toInt().coerceAtLeast(1)
            var height = targetHeight
            if (width > targetWidth) {
                width = targetWidth
                height = (trimmed.height * (targetWidth.toFloat() / trimmed.width)).toInt()
                    .coerceAtLeast(1)
            }
            val scaled = Bitmap.createScaledBitmap(trimmed, width, height, true)
            boxWidth = scaled.width
            boxX = 8 * multiplier
            drawWithShadow(
                draw,
                scaled,
                boxX.toFloat(),
                (canvasHeight - scaled.height - 8 * multiplier).toFloat(),
                multiplier,
            )
        }

        // Physical media, next to the box.
        val media = sources.media?.let { decode(it) }
        if (media != null) {
            val trimmed = trimTransparent(media)
            val maxWidth = 150 * multiplier
            val maxHeight = 120 * multiplier
            val scale = minOf(
                maxWidth.toFloat() / trimmed.width,
                maxHeight.toFloat() / trimmed.height,
            )
            val width = (trimmed.width * scale).toInt().coerceAtLeast(1)
            val height = (trimmed.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(trimmed, width, height, true)
            drawWithShadow(
                draw,
                scaled,
                (boxX + boxWidth + 16 * multiplier).toFloat(),
                (canvasHeight - scaled.height - 8 * multiplier).toFloat(),
                multiplier,
            )
        }

        val out = ByteArrayOutputStream()
        canvas.compress(Bitmap.CompressFormat.PNG, 100, out)
        canvas.recycle()
        return out.toByteArray()
    }

    private fun drawWithShadow(draw: Canvas, bitmap: Bitmap, x: Float, y: Float, multiplier: Int) {
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(
                0x66000000,
                android.graphics.PorterDuff.Mode.SRC_IN,
            )
        }
        val offset = 5f * multiplier
        draw.drawBitmap(bitmap, x + offset, y + offset, shadowPaint)
        draw.drawBitmap(bitmap, x, y, null)
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun cropToAspect(source: Bitmap, aspect: Float): Bitmap {
        val current = source.width.toFloat() / source.height
        if (kotlin.math.abs(current - aspect) < 0.01f) return source
        return if (current > aspect) {
            val width = (source.height * aspect).toInt()
            val x = (source.width - width) / 2
            Bitmap.createBitmap(source, x, 0, width, source.height)
        } else {
            val height = (source.width / aspect).toInt()
            val y = (source.height - height) / 2
            Bitmap.createBitmap(source, 0, y, source.width, height)
        }
    }

    private fun trimTransparent(source: Bitmap): Bitmap {
        if (source.hasAlpha().not()) return source
        var minX = source.width
        var minY = source.height
        var maxX = -1
        var maxY = -1
        val step = (source.width / 120).coerceAtLeast(1)
        for (y in 0 until source.height step step) {
            for (x in 0 until source.width step step) {
                if (Color.alpha(source.getPixel(x, y)) > 8) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX <= minX || maxY <= minY) return source
        return Bitmap.createBitmap(source, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun rotate90(source: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Mimics ES-DE's surface-area based marquee sizing. */
    private fun marqueeSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Pair<Int, Int> {
        val ratio = width.toFloat() / height
        var modifier = 0.5f + ratio / 6.5f
        if (ratio >= 4f) modifier += (ratio / 40f).coerceAtMost(0.3f)
        modifier = modifier.coerceIn(0f, 1f)
        val adjustedWidth = targetWidth * modifier
        var scale = adjustedWidth / width
        if (scale * height > targetHeight) {
            scale = targetHeight.toFloat() / height
        }
        return Pair(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun sampleFrameColor(bitmap: Bitmap): Int {
        var r = 0
        var g = 0
        var b = 0
        var count = 0
        val border = 6
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (x < border || y < border || x > bitmap.width - border || y > bitmap.height - border) {
                    val pixel = bitmap.getPixel(x, y)
                    r += Color.red(pixel)
                    g += Color.green(pixel)
                    b += Color.blue(pixel)
                    count++
                }
            }
        }
        if (count == 0) return Color.BLACK
        return Color.rgb(
            (r / count * 0.6f).toInt().coerceIn(0, 255),
            (g / count * 0.6f).toInt().coerceIn(0, 255),
            (b / count * 0.6f).toInt().coerceIn(0, 255),
        )
    }
}
