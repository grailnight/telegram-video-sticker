package io.github.grailnight.videosticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min

enum class Shape { RECT, ROUNDED, CIRCLE }
enum class CaptionPosition { TOP, CENTER, BOTTOM }

/** Caption and shape mask. The same drawing code feeds the on-screen preview and the encoder. */
object Overlays {

    private const val CORNER_FRACTION = 0.16f

    /** Draws the caption into a w x h area whose top-left corner is the canvas origin. */
    fun drawCaption(canvas: Canvas, w: Float, h: Float, text: String, position: CaptionPosition) {
        val caption = text.trim()
        if (caption.isEmpty() || w < 8 || h < 8) return

        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        val maxWidth = (w * 0.92f).toInt().coerceAtLeast(1)
        var size = h * 0.17f
        var layout: StaticLayout
        while (true) {
            fill.textSize = size
            layout = buildLayout(caption, fill, maxWidth)
            if ((layout.height <= h * 0.55f && layout.lineCount <= 3) || size <= h * 0.05f) break
            size *= 0.9f
        }
        val outline = TextPaint(fill).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = size * 0.16f
            strokeJoin = Paint.Join.ROUND
        }
        val margin = h * 0.04f
        val top = when (position) {
            CaptionPosition.TOP -> margin
            CaptionPosition.CENTER -> (h - layout.height) / 2f
            CaptionPosition.BOTTOM -> h - layout.height - margin
        }
        canvas.save()
        canvas.translate((w - maxWidth) / 2f, top)
        buildLayout(caption, outline, maxWidth).draw(canvas)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    fun captionBitmap(width: Int, height: Int, text: String, position: CaptionPosition): Bitmap? {
        if (text.isBlank()) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawCaption(Canvas(bitmap), width.toFloat(), height.toFloat(), text, position)
        return bitmap
    }

    /** Outline of the visible part of the sticker inside [rect]. */
    fun shapePath(shape: Shape, rect: RectF): Path = Path().apply {
        when (shape) {
            Shape.RECT -> addRect(rect, Path.Direction.CW)
            Shape.ROUNDED -> {
                val radius = min(rect.width(), rect.height()) * CORNER_FRACTION
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            Shape.CIRCLE -> addOval(rect, Path.Direction.CW)
        }
    }

    /** White where the sticker stays visible, transparent elsewhere; null when nothing is cut. */
    fun maskBitmap(width: Int, height: Int, shape: Shape): Bitmap? {
        if (shape == Shape.RECT) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        Canvas(bitmap).drawPath(shapePath(shape, RectF(0f, 0f, width.toFloat(), height.toFloat())), paint)
        return bitmap
    }
}
