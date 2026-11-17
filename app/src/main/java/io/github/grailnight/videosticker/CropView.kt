package io.github.grailnight.videosticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a video frame with a draggable, resizable selection frame.
 * The selection is exposed as [crop]: a rectangle in 0..1 coordinates of the displayed frame.
 */
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val inset = 14 * density
    private val touchSlop = 28 * density
    private val minSide = 40 * density
    private val maxHeight = (360 * density).toInt()

    private var bitmap: Bitmap? = null
    private var frameWidth = 16
    private var frameHeight = 9

    /** Where the frame is drawn inside the view. */
    private val image = RectF()

    /** Selection in view pixels, always inside [image]. */
    private val box = RectF()

    private val normalized = RectF(0f, 0f, 1f, 1f)

    var squareLocked = false
        set(value) {
            field = value
            if (value) makeSquare()
            invalidate()
        }

    /** Selected area, 0..1 relative to the displayed frame (top-left origin). */
    val crop: RectF
        get() = RectF(normalized)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2AABEE.toInt() }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    // Drag state
    private var dragLeft = false
    private var dragTop = false
    private var dragRight = false
    private var dragBottom = false
    private var dragMove = false
    private var lastX = 0f
    private var lastY = 0f

    /** Sets the frame size (after rotation) and resets the selection to the whole frame. */
    fun setFrameSize(width: Int, height: Int) {
        frameWidth = max(1, width)
        frameHeight = max(1, height)
        bitmap = null
        normalized.set(0f, 0f, 1f, 1f)
        requestLayout()
        layoutImage()
        if (squareLocked) makeSquare()
        invalidate()
    }

    fun setFrame(frame: Bitmap?) {
        bitmap = frame
        invalidate()
    }

    fun resetCrop() {
        normalized.set(0f, 0f, 1f, 1f)
        toBox()
        if (squareLocked) makeSquare()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val wanted = ((width - 2 * inset) * frameHeight / frameWidth + 2 * inset).toInt()
        setMeasuredDimension(width, min(wanted, maxHeight))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutImage()
        if (squareLocked) makeSquare()
    }

    private fun layoutImage() {
        val availW = width - 2 * inset
        val availH = height - 2 * inset
        if (availW <= 0 || availH <= 0) return
        val scale = min(availW / frameWidth, availH / frameHeight)
        val w = frameWidth * scale
        val h = frameHeight * scale
        val left = (width - w) / 2
        val top = (height - h) / 2
        image.set(left, top, left + w, top + h)
        toBox()
    }

    private fun toBox() {
        box.set(
            image.left + normalized.left * image.width(),
            image.top + normalized.top * image.height(),
            image.left + normalized.right * image.width(),
            image.top + normalized.bottom * image.height()
        )
    }

    private fun fromBox() {
        if (image.width() <= 0 || image.height() <= 0) return
        normalized.set(
            ((box.left - image.left) / image.width()).coerceIn(0f, 1f),
            ((box.top - image.top) / image.height()).coerceIn(0f, 1f),
            ((box.right - image.left) / image.width()).coerceIn(0f, 1f),
            ((box.bottom - image.top) / image.height()).coerceIn(0f, 1f)
        )
    }

    private fun makeSquare() {
        if (image.isEmpty) return
        val side = min(box.width(), box.height())
        val cx = box.centerX()
        val cy = box.centerY()
        box.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        fromBox()
    }

    override fun onDraw(canvas: Canvas) {
        if (image.isEmpty) return
        val frame = bitmap
        if (frame != null) {
            canvas.drawBitmap(frame, null, image, bitmapPaint)
        } else {
            canvas.drawRect(image, dimPaint)
        }

        // Dim everything outside the selection.
        canvas.drawRect(image.left, image.top, image.right, box.top, dimPaint)
        canvas.drawRect(image.left, box.bottom, image.right, image.bottom, dimPaint)
        canvas.drawRect(image.left, box.top, box.left, box.bottom, dimPaint)
        canvas.drawRect(box.right, box.top, image.right, box.bottom, dimPaint)

        // Rule-of-thirds grid and border.
        for (i in 1..2) {
            val x = box.left + box.width() * i / 3
            val y = box.top + box.height() * i / 3
            canvas.drawLine(x, box.top, x, box.bottom, gridPaint)
            canvas.drawLine(box.left, y, box.right, y, gridPaint)
        }
        canvas.drawRect(box, borderPaint)

        val r = 7 * density
        for (x in floatArrayOf(box.left, box.right)) {
            for (y in floatArrayOf(box.top, box.bottom)) {
                canvas.drawCircle(x, y, r, handlePaint)
                canvas.drawCircle(x, y, r, handleRingPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || image.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!beginDrag(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMove) {
                    moveBy(event.x - lastX, event.y - lastY)
                } else if (squareLocked) {
                    resizeSquare(event.x, event.y)
                } else {
                    resizeFree(event.x, event.y)
                }
                lastX = event.x
                lastY = event.y
                fromBox()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragMove = false
                dragLeft = false
                dragTop = false
                dragRight = false
                dragBottom = false
                return true
            }
        }
        return false
    }

    private fun beginDrag(x: Float, y: Float): Boolean {
        val inX = x > box.left - touchSlop && x < box.right + touchSlop
        val inY = y > box.top - touchSlop && y < box.bottom + touchSlop
        if (!inX || !inY) return false

        // Edges only grab within the slop; for small boxes the slop shrinks so the middle still moves.
        val slopX = min(touchSlop, box.width() / 3)
        val slopY = min(touchSlop, box.height() / 3)
        val dl = abs(x - box.left)
        val dr = abs(x - box.right)
        val dt = abs(y - box.top)
        val db = abs(y - box.bottom)
        dragLeft = dl <= slopX && dl <= dr
        dragRight = !dragLeft && dr <= slopX
        dragTop = dt <= slopY && dt <= db
        dragBottom = !dragTop && db <= slopY

        val anyEdge = dragLeft || dragRight || dragTop || dragBottom
        if (anyEdge && squareLocked) {
            // A square is resized from its corners: pick the corner nearest to the finger.
            dragLeft = dl <= dr
            dragRight = !dragLeft
            dragTop = dt <= db
            dragBottom = !dragTop
        }
        dragMove = !anyEdge
        return true
    }

    private fun moveBy(dx: Float, dy: Float) {
        val clampedDx = dx.coerceIn(image.left - box.left, image.right - box.right)
        val clampedDy = dy.coerceIn(image.top - box.top, image.bottom - box.bottom)
        box.offset(clampedDx, clampedDy)
    }

    private fun resizeFree(x: Float, y: Float) {
        val minW = min(minSide, image.width())
        val minH = min(minSide, image.height())
        if (dragLeft) box.left = x.coerceIn(image.left, box.right - minW)
        if (dragRight) box.right = x.coerceIn(box.left + minW, image.right)
        if (dragTop) box.top = y.coerceIn(image.top, box.bottom - minH)
        if (dragBottom) box.bottom = y.coerceIn(box.top + minH, image.bottom)
    }

    private fun resizeSquare(x: Float, y: Float) {
        // The corner opposite to the dragged one stays in place.
        val anchorX = if (dragLeft) box.right else box.left
        val anchorY = if (dragTop) box.bottom else box.top
        val roomX = if (dragLeft) anchorX - image.left else image.right - anchorX
        val roomY = if (dragTop) anchorY - image.top else image.bottom - anchorY
        val maxSideHere = min(roomX, roomY)
        val wantedX = if (dragLeft) anchorX - x else x - anchorX
        val wantedY = if (dragTop) anchorY - y else y - anchorY
        val side = max(wantedX, wantedY).coerceIn(min(minSide, maxSideHere), maxSideHere)
        box.set(
            if (dragLeft) anchorX - side else anchorX,
            if (dragTop) anchorY - side else anchorY,
            if (dragLeft) anchorX else anchorX + side,
            if (dragTop) anchorY else anchorY + side
        )
    }
}
