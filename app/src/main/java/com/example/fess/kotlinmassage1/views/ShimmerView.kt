package com.example.fess.kotlinmassage1.views

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Шиммер-контейнер для скелетонов списка диалогов (пункт 12: индикатор загрузки).
 *
 * Библиотеки типа Facebook ShimmerLayout сюда НЕ берём осознанно — по той же
 * причине, что выпилили Groupie (jcenter мёртв, JitPack нестабилен): лишний
 * remote-артефакт ради одной анимации не окупает риска сборки. Реализация
 * полностью на стандартных AndroidX-компонентах:
 *  - наследник ConstraintLayout, чтобы работать ЗАМЕНИТЕЛЕМ корня ячеек
 *    latest_message_row / latest_image_message_row (все id и constraints
 *    сохранены — рантайм-код findViewById в строках не меняется);
 *  - поверх children рисуется бегущая световая полоса (LinearGradient +
 *    LocalMatrix-сдвиг), альфа которой пульсирует через ObjectAnimator;
 *  - анимация живёт только пока view видим (onVisibilityAggregated / attach),
 *    так что при появлении реальных данных shimmer сам останавливается и
 *    не жрёт CPU/батарею.
 *
 * Атрибуты xml (shimmer_base_color, shimmer_highlight_color,
 * shimmer_band_width_fraction, shimmer_duration_ms, shimmer_auto_alpha) —
 * см. res/values/attrs.xml; читаются через obtainStyledAttributes(intArrayOf(id)).
 */
class ShimmerConstraintLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val baseColor: Int
    private val highlightColor: Int
    private val bandWidthFraction: Float
    private val durationMs: Long
    /** Период пульсации альфы блика; 0 => равномерный блик без затухания. */
    private val alphaPeriodMs: Long

    private var animator: ObjectAnimator? = null
    private val gradientTransform = android.graphics.Matrix()
    private var shader: Shader? = null
    private var shaderForWidth = 0
    private val blikPaint = Paint()

    /**
     * Прогресс положения блика 0..1 (обновляется ObjectAnimator'ом по имени
     * "shimmerProgress"). Альфа при этом пульсирует отдельно (см. drawBlik) —
     * как в FB ShimmerLayout: движение + затухание независимы.
     */
    var shimmerProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    init {
        baseColor = readAttr("shimmer_base_color", DEFAULT_BASE) { it.getColor(0, DEFAULT_BASE) }
        highlightColor = readAttr("shimmer_highlight_color", DEFAULT_HIGHLIGHT) { it.getColor(0, DEFAULT_HIGHLIGHT) }
        bandWidthFraction = readAttr("shimmer_band_width_fraction", 0.35f) { it.getFloat(0, 0.35f) }
        durationMs = readAttr("shimmer_duration_ms", 1400) { it.getInt(0, 1400) }.toLong()
        val autoAlpha = readAttr("shimmer_auto_alpha", true) { it.getBoolean(0, true) }
        alphaPeriodMs = if (autoAlpha) (durationMs / 2).coerceAtLeast(200L) else 0L
    }

    /**
     * Чтение одного атрибута из declare-styleable по имени. Атрибуты запрашиваем
     * поштучно (intArrayOf(id)), чтобы не зависеть от порядка объявления в attrs.xml.
     */
    private inline fun <T> readAttr(name: String, default: T, extract: (android.content.res.TypedArray) -> T): T {
        val id = resources.getIdentifier(name, "attr", context.packageName)
        // Атрибут не объявлен в styleable или view создан кодом без attrs — берём дефолт.
        if (id == 0 || attrs == null) return default
        val ta = context.obtainStyledAttributes(attrs, intArrayOf(id))
        try {
            return extract(ta)
        } finally {
            ta.recycle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startShimmer()
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    /** Смена видимости предка/самого view (API 24+): включаем/гасим анимацию сами. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startShimmer() else stopShimmer()
    }

    fun startShimmer() {
        if (animator?.isRunning == true) return
        animator = ObjectAnimator.ofFloat(this, "shimmerProgress", 0f, 1f).apply {
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            duration = durationMs
            start()
        }
    }

    fun stopShimmer() {
        animator?.cancel()
        animator = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawBlik(canvas)
    }

    private fun drawBlik(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // шейдер перестраиваем только при смене ширины контейнера
        if (shader == null || shaderForWidth != canvas.width) {
            shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(baseColor, highlightColor, baseColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            shaderForWidth = canvas.width
        }

        // полоса шире канвы: сдвиг от -band до +w => непрерывный проход без «дыр»
        val band = w * bandWidthFraction.coerceIn(0.05f, 1f)
        val total = w + band
        gradientTransform.setTranslate(-band + shimmerProgress * total, 0f)
        shader!!.setLocalMatrix(gradientTransform)
        blikPaint.shader = shader

        // Альфа = треугольная волна 0->MAX->0 (пульсация), если auto_alpha включён.
        val wave = if (alphaPeriodMs > 0) {
            val cycle = alphaPeriodMs * 2
            val t = (System.currentTimeMillis() % cycle).toFloat() / alphaPeriodMs
            (if (t > 1f) 2f - t else t).coerceIn(0f, 1f)
        } else 1f
        blikPaint.alpha = (wave * MAX_ALPHA).toInt()
        canvas.drawRect(0f, 0f, w, h, blikPaint)
    }

    companion object {
        /** Потолок альфы блика (< 255 — скелетоны остаются мягкими, не «выбеливаются»). */
        private const val MAX_ALPHA = 90
        /** Цвет «тела» скелетона и цвет бегущего блика по умолчанию. */
        private val DEFAULT_BASE = Color.parseColor("#BDBDBD")
        private val DEFAULT_HIGHLIGHT = Color.WHITE
    }
}
