package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView с жестами «как в WhatsApp»: pinch-зум, перетаскивание увеличенной
 * картинки, двойной тап = приблизить/отдалить. Работает через Matrix (scaleType
 * MUST be MATRIX), без внешних зависимостей.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrixValues = FloatArray(9)
    private var scaledTouchSlop = 24f

    enum class DragMode { NONE, DRAG, ZOOM }

    private var mode = DragMode.NONE
    private val startP = PointF()
    private var anim: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = DragMode.ZOOM
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                imageMatrix.getValues(matrixValues)
                val fitScale = minOf(
                    width.toFloat() / (drawable?.intrinsicWidth ?: 1).coerceAtLeast(1),
                    height.toFloat() / (drawable?.intrinsicHeight ?: 1).coerceAtLeast(1)
                )
                val cur = matrixValues[Matrix.MSCALE_X]
                if (cur < fitScale * 2.4f) animateTo(fitScale * 2.5f, e.x, e.y) else animateTo(fitScale, width / 2f, height / 2f)
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fitToView()
    }

    /** Подгонка под экран при первой отрисовке и после поворота. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    private fun fitToView() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        val scale = minOf(vw / dw, vh / dh)   // fitCenter вручную: картинка целиком
        resetMatrix(scale, vw / 2f - dw * scale / 2f, vh / 2f - dh * scale / 2f)
    }

    private fun resetMatrix(scale: Float, dx: Float, dy: Float) {
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    /** Минимальный масштаб = «картинка целиком» (fitToView), максимальный — 10x от него. */
    private fun clampFactor(factor: Float): Float {
        val d = drawable ?: return factor
        val fit = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        imageMatrix.getValues(matrixValues)
        val cur = matrixValues[Matrix.MSCALE_X]
        val target = (cur * factor).coerceIn(fit, fit * 10f)
        return target / cur
    }

    private fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
        val m = imageMatrix
        m.postScale(clampFactor(factor), clampFactor(factor), focusX, focusY)
        imageMatrix = m
        clampTranslation()
    }

    /** Плавный анимированный переход к целевому масштабу вокруг точки (fx, fy). */
    private fun animateTo(targetScale: Float, fx: Float, fy: Float) {
        anim?.cancel()
        imageMatrix.getValues(matrixValues)
        val from = matrixValues[Matrix.MSCALE_X]
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener { a ->
                val t = a.animatedFraction
                val s = from + (targetScale - from) * t
                val step = s / imageMatrix.let { it.getValues(matrixValues); matrixValues[Matrix.MSCALE_X] }
                if (step != 1f && step.isFinite()) {
                    val m = imageMatrix
                    m.postScale(step, step, fx, fy)
                    imageMatrix = m
                    clampTranslation()
                }
            }
            start()
        }
    }

    /** Не выпускаем картинку за границы вьюхи по осям, пока она меньше экрана — центруем. */
    private fun clampTranslation() {
        val d = drawable ?: return
        val values = matrixValues
        imageMatrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val dx = values[Matrix.MTRANS_X]
        val dy = values[Matrix.MTRANS_Y]
        val imgW = d.intrinsicWidth * scale
        val imgH = d.intrinsicHeight * scale
        val vw = width.toFloat()
        val vh = height.toFloat()
        var fixX = 0f
        var fixY = 0f
        if (imgW <= vw) fixX = (vw - imgW) / 2f - dx else {
            if (dx > 0) fixX = -dx
            if (dx + imgW < vw) fixX = vw - (dx + imgW)
        }
        if (imgH <= vh) fixY = (vh - imgH) / 2f - dy else {
            if (dy > 0) fixY = -dy
            if (dy + imgH < vh) fixY = vh - (dy + imgH)
        }
        if (fixX != 0f || fixY != 0f) imageMatrix.postTranslate(fixX, fixY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = DragMode.NONE
                startP.set(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DragMode.NONE && !scaleDetector.isInProgress) {
                    val dx = event.x - startP.x
                    val dy = event.y - startP.y
                    if (Math.abs(dx) > scaledTouchSlop || Math.abs(dy) > scaledTouchSlop) {
                        mode = DragMode.DRAG
                    }
                }
                if (mode == DragMode.DRAG) {
                    imageMatrix.postTranslate(event.x - startP.x, event.y - startP.y)
                    clampTranslation()
                    startP.set(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = DragMode.NONE
            }
        }
        return true
    }
}
