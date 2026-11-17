package io.github.grailnight.videosticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strip of thumbnails covering the whole clip with a selection window on top.
 * Drag the window to move the fragment, drag its edges to change the length.
 */
class TimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val handleWidth = 10 * density
    private val touchSlop = 22 * density
    private val sidePadding = 12 * density

    private var durationUs = 0L
    private var startUs = 0L
    private var windowUs = 0L
    private var minWindowUs = 1L
    private var maxWindowUs = 1L

    private var thumbnails = arrayOfNulls<Bitmap>(0)

    /** Reports the selection while the user drags it. */
    var onSelectionChanged: ((startUs: Long, windowUs: Long) -> Unit)? = null

    private val strip = RectF()
    private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyPaint = Paint().apply { color = 0xFF3A3A3A.toInt() }
    private val dimPaint = Paint().apply { color = 0xA0000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2AABEE.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2AABEE.toInt() }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
    }

    private enum class Drag { NONE, MOVE, LEFT, RIGHT }

    private var drag = Drag.NONE
    private var grabOffsetUs = 0L

    /** How many thumbnails fit; ask after layout. */
    val thumbnailCount: Int
        get() = thumbnails.size

    fun setClip(durationUs: Long, thumbnailCount: Int) {
        this.durationUs = max(1L, durationUs)
        thumbnails = arrayOfNulls(thumbnailCount)
        invalidate()
    }

    fun setThumbnail(index: Int, bitmap: Bitmap) {
        if (index in thumbnails.indices) {
            thumbnails[index] = bitmap
            invalidate()
        }
    }

    fun setLimits(minWindowUs: Long, maxWindowUs: Long) {
        this.minWindowUs = max(1L, minWindowUs)
        this.maxWindowUs = max(this.minWindowUs, maxWindowUs)
    }

    fun setSelection(startUs: Long, windowUs: Long) {
        this.startUs = startUs
        this.windowUs = windowUs
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        strip.set(sidePadding, 6 * density, w - sidePadding, h - 6 * density)
    }

    private fun timeToX(us: Long): Float = strip.left + strip.width() * us / durationUs.toFloat()

    private fun xToTime(x: Float): Long =
        ((x - strip.left) / strip.width() * durationUs).toLong().coerceIn(0, durationUs)

    override fun onDraw(canvas: Canvas) {
        if (strip.isEmpty) return
        canvas.drawRect(strip, emptyPaint)

        val count = thumbnails.size
        if (count > 0) {
            val cell = strip.width() / count
            val target = RectF()
            val source = Rect()
            for (i in 0 until count) {
                val bitmap = thumbnails[i] ?: continue
                target.set(strip.left + i * cell, strip.top, strip.left + (i + 1) * cell, strip.bottom)
                // Centre-crop the thumbnail into its cell.
                val cellAspect = target.width() / target.height()
                val bitmapAspect = bitmap.width.toFloat() / bitmap.height
                if (bitmapAspect > cellAspect) {
                    val w = (bitmap.height * cellAspect).toInt()
                    source.set((bitmap.width - w) / 2, 0, (bitmap.width + w) / 2, bitmap.height)
                } else {
                    val h = (bitmap.width / cellAspect).toInt()
                    source.set(0, (bitmap.height - h) / 2, bitmap.width, (bitmap.height + h) / 2)
                }
                canvas.drawBitmap(bitmap, source, target, thumbPaint)
            }
        }

        if (durationUs <= 0 || windowUs <= 0) return
        val left = timeToX(startUs)
        val right = timeToX(startUs + windowUs)
        canvas.drawRect(strip.left, strip.top, left, strip.bottom, dimPaint)
        canvas.drawRect(right, strip.top, strip.right, strip.bottom, dimPaint)

        canvas.drawRect(left, strip.top, right, strip.bottom, framePaint)
        val radius = 3 * density
        canvas.drawRoundRect(left - handleWidth, strip.top, left, strip.bottom, radius, radius, handlePaint)
        canvas.drawRoundRect(right, strip.top, right + handleWidth, strip.bottom, radius, radius, handlePaint)
        val gripTop = strip.centerY() - 8 * density
        val gripBottom = strip.centerY() + 8 * density
        canvas.drawLine(left - handleWidth / 2, gripTop, left - handleWidth / 2, gripBottom, gripPaint)
        canvas.drawLine(right + handleWidth / 2, gripTop, right + handleWidth / 2, gripBottom, gripPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || strip.isEmpty || durationUs <= 0 || windowUs <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val left = timeToX(startUs)
                val right = timeToX(startUs + windowUs)
                val x = event.x
                // The handles hang outside the window, so the outer side of each edge grabs it;
                // the inside moves the window. A touch elsewhere brings the window there.
                drag = when {
                    x < left && left - x <= touchSlop -> Drag.LEFT
                    x > right && x - right <= touchSlop -> Drag.RIGHT
                    x in left..right && right - left > 3 * touchSlop && x - left < touchSlop / 2 -> Drag.LEFT
                    x in left..right && right - left > 3 * touchSlop && right - x < touchSlop / 2 -> Drag.RIGHT
                    else -> Drag.MOVE
                }
                grabOffsetUs = if (x in left..right) xToTime(x) - startUs else windowUs / 2
                parent?.requestDisallowInterceptTouchEvent(true)
                if (drag == Drag.MOVE && x !in left..right) applyDrag(x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                applyDrag(event.x)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun applyDrag(x: Float) {
        val time = xToTime(x)
        val end = startUs + windowUs
        var newStart = startUs
        var newWindow = windowUs
        when (drag) {
            Drag.MOVE -> newStart = (time - grabOffsetUs).coerceIn(0, max(0L, durationUs - windowUs))
            Drag.LEFT -> {
                newStart = time.coerceIn(max(0L, end - maxWindowUs), max(0L, end - minWindowUs))
                newWindow = end - newStart
            }
            Drag.RIGHT -> {
                val newEnd = time.coerceIn(
                    min(durationUs, startUs + minWindowUs), min(durationUs, startUs + maxWindowUs)
                )
                newWindow = newEnd - startUs
            }
            Drag.NONE -> return
        }
        if (newStart == startUs && newWindow == windowUs) return
        if (abs(newWindow) <= 0) return
        startUs = newStart
        windowUs = newWindow
        invalidate()
        onSelectionChanged?.invoke(startUs, windowUs)
    }
}
