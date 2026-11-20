package sk.suncon.slnkomer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Zameriavač: stred = smer, kam mieri plocha (obrazovka alebo zadná strana telefónu).
 * Bodka = slnko. Vzdialenosť od stredu = uhlová odchýlka (okraj = 90°).
 */
class TargetView(context: Context) : View(context) {

    private var hasSun = false
    private var sx = 0.0
    private var sy = 0.0
    private var angle = 0.0

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF334155.toInt(); strokeWidth = dp(1f)
    }
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF475569.toInt(); strokeWidth = dp(1.5f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt(); textSize = dp(11f)
    }
    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt(); textSize = dp(11f); textAlign = Paint.Align.CENTER
    }
    private val okZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x2622C55E
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x80FBBF24.toInt(); strokeWidth = dp(1.5f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFE2E8F0.toInt(); strokeWidth = dp(1.5f)
    }

    /** x, y = zložky smeru k slnku v súradniciach telefónu, angleDeg = uhol od osi zameriavača */
    fun setSun(x: Double, y: Double, angleDeg: Double) {
        hasSun = true; sx = x; sy = y; angle = angleDeg
        invalidate()
    }

    fun clearSun() {
        hasSun = false
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val size = min(w, dp(360f).toInt())
        setMeasuredDimension(w, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val rad = min(width, height) * 0.44f

        canvas.drawCircle(cx, cy, rad * 5f / 90f, okZonePaint)
        for (a in 15..90 step 15) {
            val r = rad * a / 90f
            canvas.drawCircle(cx, cy, r, if (a == 90) outerPaint else ringPaint)
            if (a % 30 == 0) canvas.drawText("$a°", cx + dp(3f), cy - r + dp(12f), labelPaint)
        }
        canvas.drawLine(cx - rad, cy, cx + rad, cy, ringPaint)
        canvas.drawLine(cx, cy - rad, cx, cy + rad, ringPaint)
        canvas.drawText("horný okraj telefónu ▲", cx, dp(11f), topPaint)

        if (hasSun) {
            val h = hypot(sx, sy).let { if (it < 1e-9) 1e-9 else it }
            val rr = (min(angle, 95.0) / 90.0 * rad).toFloat()
            val x = cx + (sx / h).toFloat() * rr
            val y = cy - (sy / h).toFloat() * rr
            canvas.drawLine(cx, cy, x, y, linePaint)
            val col = when {
                angle < 5 -> 0xFF22C55E.toInt()
                angle < 15 -> 0xFFFBBF24.toInt()
                else -> 0xFFF97316.toInt()
            }
            glowPaint.shader = RadialGradient(
                x, y, dp(24f), intArrayOf(col, 0x00FBBF24), null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, dp(24f), glowPaint)
            sunPaint.color = col
            canvas.drawCircle(x, y, dp(10f), sunPaint)
        }
        val c = dp(8f)
        canvas.drawLine(cx - c, cy, cx + c, cy, crossPaint)
        canvas.drawLine(cx, cy - c, cx, cy + c, crossPaint)
    }
}
