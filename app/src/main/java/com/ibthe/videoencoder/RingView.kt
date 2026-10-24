package com.ibthe.videoencoder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/** Круговой индикатор: в ожидании — вращающиеся точки, при кодировании — дуга прогресса. */
class RingView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val sw = 12 * d
    private val lime = 0xFFD4FF3A.toInt()
    private val rect = RectF()
    private var shown = 0f
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = sw; color = 0x14FFFFFF; strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = sw; color = lime; strokeCap = Paint.Cap.ROUND
    }
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0x99D4FF3A.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 14000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; if (idle) invalidate() }
    }

    var idle: Boolean = true
        set(v) { field = v; invalidate() }

    fun setProgress(f: Float, animate: Boolean) {
        anim?.cancel()
        val t = f.coerceIn(0f, 1f)
        if (!animate) { shown = t; invalidate(); return }
        anim = ValueAnimator.ofFloat(shown, t).apply {
            duration = 500; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); spin.start() }
    override fun onDetachedFromWindow() { spin.cancel(); anim?.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val half = sw / 2 + 2 * d
        rect.set(half, half, w - half, h - half)
        val r = rect.width() / 2
        val gap = (2 * Math.PI.toFloat() * r) / 56f
        dots.pathEffect = DashPathEffect(floatArrayOf(0.1f, gap), 0f)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (idle) {
            c.save(); c.rotate(phase * 360f, cx, cy)
            c.drawArc(rect, 0f, 360f, false, dots)
            c.restore()
        } else {
            c.drawArc(rect, 0f, 360f, false, track)
            if (shown > 0f) {
                c.save(); c.rotate(-90f, cx, cy)
                c.drawArc(rect, 0f, 360f * shown, false, arc)
                c.restore()
            }
        }
    }
}
