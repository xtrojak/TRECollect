package com.trec.trecollect.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView that supports pinch-to-zoom and panning.
 * Image starts fit-center; user can zoom (1x–4x relative to fit) and pan when zoomed.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val viewRect = RectF()
    private val drawableRect = RectF()

    private var fitScale = 1f
    private var currentScale = 1f
    private val maxScaleFactor = 4f

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = (currentScale * scaleFactor).coerceIn(fitScale, fitScale * maxScaleFactor)
            if (newScale != currentScale) {
                val focusX = detector.focusX
                val focusY = detector.focusY
                matrix.postScale(newScale / currentScale, newScale / currentScale, focusX, focusY)
                currentScale = newScale
                constrainTranslation()
                imageMatrix = matrix
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            centerImage()
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (width > 0 && height > 0 && drawable != null) {
            post { centerImage() }
        }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        if (width > 0 && height > 0 && bm != null) {
            post { centerImage() }
        }
    }

    private fun centerImage() {
        val drawable = drawable ?: return
        val dWidth = drawable.intrinsicWidth.toFloat()
        val dHeight = drawable.intrinsicHeight.toFloat()
        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        if (dWidth <= 0 || dHeight <= 0 || vWidth <= 0 || vHeight <= 0) return

        matrix.reset()
        fitScale = (vWidth / dWidth).coerceAtMost(vHeight / dHeight)
        currentScale = fitScale
        matrix.setScale(fitScale, fitScale)
        val dx = (vWidth - dWidth * fitScale) / 2f
        val dy = (vHeight - dHeight * fitScale) / 2f
        matrix.postTranslate(dx, dy)
        imageMatrix = matrix
    }

    private fun constrainTranslation() {
        val d = drawable ?: return
        drawableRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(drawableRect)
        viewRect.set(0f, 0f, width.toFloat(), height.toFloat())
        var dx = 0f
        var dy = 0f
        if (drawableRect.width() <= viewRect.width()) {
            dx = viewRect.centerX() - drawableRect.centerX()
        } else {
            if (drawableRect.left > viewRect.left) dx = viewRect.left - drawableRect.left
            if (drawableRect.right < viewRect.right) dx = viewRect.right - drawableRect.right
        }
        if (drawableRect.height() <= viewRect.height()) {
            dy = viewRect.centerY() - drawableRect.centerY()
        } else {
            if (drawableRect.top > viewRect.top) dy = viewRect.top - drawableRect.top
            if (drawableRect.bottom < viewRect.bottom) dy = viewRect.bottom - drawableRect.bottom
        }
        matrix.postTranslate(dx, dy)
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    matrix.postTranslate(dx, dy)
                    constrainTranslation()
                    imageMatrix = matrix
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
        }
        scaleGestureDetector.onTouchEvent(event)
        return true
    }
}
