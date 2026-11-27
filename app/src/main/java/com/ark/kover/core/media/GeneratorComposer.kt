package com.ark.kover.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.ark.kover.core.generator.GeneratorPreset
import com.ark.kover.core.generator.GeneratorSize
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/** Bitmaps available for one game. Any of them may be null. */
data class GeneratorArt(
    val cover: Bitmap? = null,
    val screenshot: Bitmap? = null,
    val logo: Bitmap? = null,
    val fanart: Bitmap? = null,
    /** Used as a text logo when none of the sources returned an image. */
    val title: String = "",
)

/**
 * Renders the four generator layouts approved in docs/miximages.
 *
 * Coordinates are the same as the preview script: 1024×1024 and 1280×960.
 * Missing pieces degrade gracefully (e.g. P2 falls back to a screenshot as
 * background). When no source has a logo, the game title is drawn in the logo
 * place so a pack never silently ends up without a logo; P4 falls back to the
 * cover only when even the title is empty.
 */
@Singleton
class GeneratorComposer @Inject constructor() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        setShadowLayer(26f, 0f, 12f, 0x99000000.toInt())
    }

    fun compose(
        preset: GeneratorPreset,
        size: GeneratorSize,
        art: GeneratorArt,
    ): Bitmap? {
        val width = size.width
        val height = size.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val ok = when (preset) {
            GeneratorPreset.CLASSIC -> drawClassic(canvas, width, height, art)
            GeneratorPreset.HERO -> drawHero(canvas, width, height, art)
            GeneratorPreset.ICON_COVER -> drawIconCover(canvas, width, height, art)
            GeneratorPreset.MINIMAL -> drawMinimal(canvas, width, height, art)
        }
        if (!ok) {
            result.recycle()
            return null
        }
        return result
    }

    // ------------------------------------------------------------------ P1

    private fun drawClassic(canvas: Canvas, w: Int, h: Int, art: GeneratorArt): Boolean {
        val shot = art.screenshot ?: art.fanart ?: art.cover ?: return false
        val sw = if (w == h) 920 else 1150
        val sh = if (w == h) 690 else 862
        val pad = if (w == h) 11 else 14
        val fw = sw + pad * 2
        val fh = sh + pad * 2
        val fx = (w - fw) / 2f
        val fy = (h - fh) / 2f
        drawFramedShot(canvas, shot, RectF(fx, fy, fx + fw, fy + fh), pad, if (w == h) 26f else 30f)

        val logoW = if (w == h) 480 else 600
        val logoH = if (w == h) 200 else 240
        val logoX = (w - logoW) / 2f
        val logoY = if (w == h) 40f else 30f
        val logo = art.logo
        if (logo != null) {
            val scaled = scaledLogo(logo, logoW, logoH)
            if (scaled != null) {
                val lx = (w - scaled.width) / 2f
                drawWithShadow(
                    canvas,
                    scaled,
                    RectF(lx, logoY, lx + scaled.width, logoY + scaled.height),
                    22f,
                )
            }
        } else {
            drawTitleFallback(
                canvas = canvas,
                title = art.title,
                bounds = RectF(logoX, logoY, logoX + logoW, logoY + logoH),
                align = Paint.Align.CENTER,
                maxTextSize = if (w == h) 86f else 102f,
                shadow = 18f,
            )
        }

        art.cover?.let { cover ->
            val ch = if (w == h) 405f else 480f
            val cw = ch * cover.width / cover.height
            val margin = if (w == h) 48f else 50f
            val left = margin
            val top = h - margin - ch
            drawPhoto(canvas, cover, RectF(left, top, left + cw, top + ch), 14f, 26f)
        }
        return true
    }

    // ------------------------------------------------------------------ P2

    private fun drawHero(canvas: Canvas, w: Int, h: Int, art: GeneratorArt): Boolean {
        val background = art.fanart ?: art.screenshot ?: art.cover ?: return false
        drawCover(canvas, background, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        canvas.drawColor(0x33000000)
        drawVerticalGradient(canvas, w, h, 0, 175)

        val shot = art.screenshot ?: art.fanart ?: art.cover ?: return false
        val sw = if (w == h) 660 else 800
        val sh = if (w == h) 495 else 600
        val pad = if (w == h) 8 else 10
        val fw = sw + pad * 2
        val fh = sh + pad * 2
        val right = if (w == h) 48f else 60f
        val fx = w - right - fw
        val fy = (h - fh) / 2f
        drawFramedShot(canvas, shot, RectF(fx, fy, fx + fw, fy + fh), pad, if (w == h) 22f else 26f)

        val logoW = if (w == h) 510 else 690
        val logoH = if (w == h) 200 else 270
        val logoX = if (w == h) 48f else 60f
        val logoY = if (w == h) 48f else 56f
        val logo = art.logo
        if (logo != null) {
            val scaled = scaledLogo(logo, logoW, logoH)
            if (scaled != null) {
                drawWithShadow(
                    canvas,
                    scaled,
                    RectF(logoX, logoY, logoX + scaled.width, logoY + scaled.height),
                    24f,
                )
            }
        } else {
            drawTitleFallback(
                canvas = canvas,
                title = art.title,
                bounds = RectF(logoX, logoY, logoX + logoW, logoY + logoH),
                align = Paint.Align.LEFT,
                maxTextSize = if (w == h) 88f else 104f,
                shadow = 20f,
            )
        }

        art.cover?.let { cover ->
            val ch = if (w == h) 450f else 540f
            val cw = ch * cover.width / cover.height
            val margin = if (w == h) 48f else 60f
            val left = margin
            val top = h - margin - ch
            drawPhoto(canvas, cover, RectF(left, top, left + cw, top + ch), 14f, 26f)
        }
        return true
    }

    // ------------------------------------------------------------------ P3

    private fun drawIconCover(canvas: Canvas, w: Int, h: Int, art: GeneratorArt): Boolean {
        val background = art.cover ?: art.fanart ?: art.screenshot ?: return false
        val blurred = blurred(background, w, h)
        canvas.drawBitmap(blurred, 0f, 0f, bitmapPaint)
        blurred.recycle()
        canvas.drawColor(0x88000000.toInt())
        drawVerticalGradient(canvas, w, h, 0, 140)

        val cover = art.cover ?: art.fanart ?: art.screenshot ?: return false
        val ch = if (w == h) 740f else 680f
        val cw = ch * cover.width / cover.height
        val left = (w - cw) / 2f
        val top = 40f
        drawPhoto(canvas, cover, RectF(left, top, left + cw, top + ch), 28f, 38f)

        val logoW = if (w == h) 400 else 420
        val logoH = if (w == h) 150 else 160
        val logoX = (w - logoW) / 2f
        val logoY = h - 60f - logoH
        val logo = art.logo
        if (logo != null) {
            val scaled = scaledLogo(logo, logoW, logoH)
            if (scaled != null) {
                val lx = (w - scaled.width) / 2f
                drawWithShadow(
                    canvas,
                    scaled,
                    RectF(lx, logoY, lx + scaled.width, logoY + scaled.height),
                    24f,
                )
            }
        } else {
            drawTitleFallback(
                canvas = canvas,
                title = art.title,
                bounds = RectF(logoX, logoY, logoX + logoW, logoY + logoH),
                align = Paint.Align.CENTER,
                maxTextSize = if (w == h) 84f else 92f,
                shadow = 20f,
            )
        }
        return true
    }

    // ------------------------------------------------------------------ P4

    private fun drawMinimal(canvas: Canvas, w: Int, h: Int, art: GeneratorArt): Boolean {
        val background = art.screenshot ?: art.fanart ?: art.cover ?: return false
        val blurred = blurred(background, w, h)
        canvas.drawBitmap(blurred, 0f, 0f, bitmapPaint)
        blurred.recycle()
        canvas.drawColor(0x46000000)
        drawVerticalGradient(canvas, w, h, 20, 140)

        val logoW = if (w == h) (w * 0.66f).toInt() else (w * 0.50f).toInt()
        val logoH = if (w == h) 220 else 200
        val logoX = (w - logoW) / 2f
        val logoY = (h - logoH) / 2f
        val logo = art.logo
        if (logo != null) {
            val scaled = scaledLogo(logo, logoW, logoH)
            if (scaled != null) {
                val cx = w / 2f
                val cy = h / 2f
                drawGlow(canvas, scaled, cx, cy)
                val left = cx - scaled.width / 2f
                val top = cy - scaled.height / 2f
                drawWithShadow(canvas, scaled, RectF(left, top, left + scaled.width, top + scaled.height), 22f)
                return true
            }
        }
        if (drawTitleFallback(
                canvas = canvas,
                title = art.title,
                bounds = RectF(logoX, logoY, logoX + logoW, logoY + logoH),
                align = Paint.Align.CENTER,
                maxTextSize = if (w == h) 92f else 104f,
                shadow = 22f,
            )
        ) {
            return true
        }

        val cover = art.cover ?: return true
        val ch = h * 0.55f
        val cw = ch * cover.width / cover.height
        val left = (w - cw) / 2f
        val top = (h - ch) / 2f
        drawPhoto(canvas, cover, RectF(left, top, left + cw, top + ch), 20f, 26f)
        return true
    }

    // ------------------------------------------------------------------ helpers

    private fun sourceRect(src: Bitmap, fit: Boolean, dst: RectF): Rect {
        val scale = if (fit) {
            max(dst.width() / src.width, dst.height() / src.height)
        } else {
            min(dst.width() / src.width, dst.height() / src.height)
        }
        val cropW = (dst.width() / scale).coerceAtMost(src.width.toFloat())
        val cropH = (dst.height() / scale).coerceAtMost(src.height.toFloat())
        val left = (src.width - cropW) / 2f
        val top = (src.height - cropH) / 2f
        return Rect(left.toInt(), top.toInt(), (left + cropW).toInt(), (top + cropH).toInt())
    }

    private fun drawCover(canvas: Canvas, src: Bitmap, dst: RectF) {
        canvas.drawBitmap(src, sourceRect(src, true, dst), dst, bitmapPaint)
    }

    private fun drawPhoto(canvas: Canvas, src: Bitmap, dst: RectF, radius: Float, shadow: Float) {
        drawShadow(canvas, dst, radius, shadow)
        val path = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        drawCover(canvas, src, dst)
        canvas.restore()
    }

    private fun drawWithShadow(
        canvas: Canvas,
        src: Bitmap,
        dst: RectF,
        shadow: Float,
    ) {
        // Logos are transparent PNG/WebP. Drawing a filled rounded rectangle behind
        // them leaves a visible dark plate; a shadow layer only shadows the actual
        // alpha silhouette, which is what we want.
        val paintShadow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            setShadowLayer(shadow, 0f, 10f, 0x99000000.toInt())
        }
        canvas.drawBitmap(src, sourceRect(src, false, dst), dst, paintShadow)
    }

    private fun drawShadow(canvas: Canvas, dst: RectF, radius: Float, blur: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44000000
            setShadowLayer(blur, 0f, 12f, 0x99000000.toInt())
        }
        canvas.drawRoundRect(dst, radius, radius, p)
    }

    private fun drawFramedShot(
        canvas: Canvas,
        shot: Bitmap,
        frame: RectF,
        pad: Int,
        radius: Float,
    ) {
        val color = frameColor(shot)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            setShadowLayer(if (frame.width() > 1000) 30f else 24f, 0f, 14f, 0x99000000.toInt())
        }
        canvas.drawRoundRect(frame, radius, radius, framePaint)
        val inner = RectF(
            frame.left + pad,
            frame.top + pad,
            frame.right - pad,
            frame.bottom - pad,
        )
        val path = Path().apply { addRoundRect(inner, radius - pad, radius - pad, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        drawCover(canvas, shot, inner)
        canvas.restore()
    }

    private fun frameColor(shot: Bitmap): Int {
        val one = Bitmap.createScaledBitmap(shot, 1, 1, true)
        val c = one.getPixel(0, 0)
        one.recycle()
        // Neutral dark frame: a raw average can turn bright red/green for colourful
        // screenshots and distract from the artwork.
        val lum = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
            .coerceIn(30f, 130f)
        val v = (lum * 0.45f).toInt().coerceIn(16, 54)
        return Color.rgb(v, v, v)
    }

    private fun drawVerticalGradient(
        canvas: Canvas,
        w: Int,
        h: Int,
        topAlpha: Int,
        bottomAlpha: Int,
    ) {
        val shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(topAlpha, 0, 0, 0),
                Color.argb(bottomAlpha, 0, 0, 0),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun blurred(src: Bitmap, w: Int, h: Int): Bitmap {
        val fitted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawCover(Canvas(fitted), src, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        val smallW = 64
        val smallH = max(1, (smallW * h / w.toFloat()).toInt())
        var small = Bitmap.createScaledBitmap(fitted, smallW, smallH, true)
        fitted.recycle()
        val midW = max(16, w / 12)
        val midH = max(16, h / 12)
        val mid = Bitmap.createScaledBitmap(small, midW, midH, true)
        small.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(mid, null, Rect(0, 0, w, h), bitmapPaint)
        mid.recycle()
        return out
    }

    private fun scaledLogo(src: Bitmap, maxW: Int, maxH: Int): Bitmap? {
        val trimmed = trimAlpha(src) ?: src
        val scale = min(maxW / trimmed.width.toFloat(), maxH / trimmed.height.toFloat())
        if (scale <= 0f) return null
        val out = Bitmap.createScaledBitmap(
            trimmed,
            max(1, (trimmed.width * scale).toInt()),
            max(1, (trimmed.height * scale).toInt()),
            true,
        )
        if (trimmed !== src) trimmed.recycle()
        return out
    }

    /**
     * Draws the game title in the logo slot when none of the sources returned a
     * logo image. White bold text with a soft shadow, wrapped into at most two
     * lines and shrunk to fit the slot.
     */
    private fun drawTitleFallback(
        canvas: Canvas,
        title: String,
        bounds: RectF,
        align: Paint.Align,
        maxTextSize: Float,
        shadow: Float,
    ): Boolean {
        val text = title.trim()
        if (text.isEmpty() || bounds.width() <= 0f || bounds.height() <= 0f) return false
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F4F4.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = align
            setShadowLayer(shadow, 0f, 8f, 0xCC000000.toInt())
        }
        var size = maxTextSize
        paint.textSize = size
        var lines = splitTitle(text, paint, bounds.width())
        while (size > 22f && (
                lines.any { paint.measureText(it) > bounds.width() } ||
                    lineHeight(paint) * lines.size > bounds.height()
                )
        ) {
            size *= 0.88f
            paint.textSize = size
            lines = splitTitle(text, paint, bounds.width())
        }
        val lh = lineHeight(paint)
        var baseline = bounds.centerY() - (lh * lines.size) / 2f - paint.fontMetrics.ascent
        val x = when (align) {
            Paint.Align.LEFT -> bounds.left
            Paint.Align.RIGHT -> bounds.right
            else -> bounds.centerX()
        }
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline += lh
        }
        return true
    }

    /** Splits a title into at most two lines at the space closest to the middle. */
    private fun splitTitle(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return listOf(text)
        var best = 1
        var bestDiff = Float.MAX_VALUE
        for (i in 1 until words.size) {
            val left = words.subList(0, i).joinToString(" ")
            val diff = kotlin.math.abs(paint.measureText(left) - maxWidth / 2f)
            if (diff < bestDiff) {
                bestDiff = diff
                best = i
            }
        }
        return listOf(
            words.subList(0, best).joinToString(" "),
            words.subList(best, words.size).joinToString(" "),
        )
    }

    private fun lineHeight(paint: Paint): Float =
        (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 1.08f

    private fun trimAlpha(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (Color.alpha(pixels[row + x]) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return null
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun drawGlow(canvas: Canvas, logo: Bitmap, cx: Float, cy: Float) {
        val color = averageColor(logo)
        if (color == 0) return
        val radius = max(logo.width, logo.height) * 0.95f
        val shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    }

    private fun averageColor(src: Bitmap): Int {
        val stepX = max(1, src.width / 48)
        val stepY = max(1, src.height / 48)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        for (y in 0 until src.height step stepY) {
            for (x in 0 until src.width step stepX) {
                val c = src.getPixel(x, y)
                if (Color.alpha(c) > 80) {
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(c)
                    count++
                }
            }
        }
        if (count == 0L) return 0
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }
}
