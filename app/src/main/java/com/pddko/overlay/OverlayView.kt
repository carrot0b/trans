package com.pddko.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** 번역 박스를 그리는 전체화면 투명 뷰. 복사 모드에서만 터치를 받는다. */
@SuppressLint("ViewConstructor")
class OverlayView(ctx: Context, private val listener: Listener) : View(ctx) {

    interface Listener {
        fun onExitCopyMode()
        fun onCopy(text: String, label: String)
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var boxes: List<Box> = emptyList()
    private var predict = 0f
    private var selected: Box? = null
    private val loc = IntArray(2)

    var copyMode = false
        set(v) {
            field = v
            selected = null
            invalidate()
        }

    private val bgPaint = Paint()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.rgb(224, 46, 36)
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.rgb(25, 118, 210)
    }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 33, 33, 33) }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 25, 118, 210) }
    private val chipRects = arrayOf(RectF(), RectF())
    private val chipLabels = arrayOf("번역 복사", "원문 복사")
    private val hintRect = RectF()

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            val b = hit(e.rawX, e.rawY) ?: return
            selected = b
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = e.rawX
            val y = e.rawY
            val s = selected
            if (s != null) {
                if (chipRects[0].contains(x, y)) {
                    listener.onCopy(s.ko, "번역"); selected = null; invalidate(); return true
                }
                if (chipRects[1].contains(x, y)) {
                    listener.onCopy(s.zh, "원문"); selected = null; invalidate(); return true
                }
            }
            val b = hit(x, y)
            if (b != null) {
                selected = b; invalidate()
            } else {
                listener.onExitCopyMode()
            }
            return true
        }
    })

    fun setBoxes(b: List<Box>, predictDy: Float) {
        boxes = b
        predict = predictDy
        invalidate()
    }

    private fun hit(x: Float, y: Float): Box? =
        boxes.lastOrNull { y in it.clipTop..it.clipBottom && it.rect.contains(x, y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (copyMode) gd.onTouchEvent(event) else false

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        for (b in boxes) {
            val dy = if (b.moving && !copyMode) predict else 0f
            canvas.save()
            canvas.clipRect(-10000f, b.clipTop, 100000f, b.clipBottom)
            canvas.translate(0f, dy)
            bgPaint.color = b.bg
            canvas.drawRect(b.rect, bgPaint)
            canvas.save()
            val textTop = b.rect.top + (b.rect.height() - b.layout.height) / 2f
            canvas.translate(b.rect.left + b.padX, textTop)
            b.layout.draw(canvas)
            canvas.restore()
            if (copyMode) canvas.drawRect(b.rect, if (b === selected) selPaint else outline)
            canvas.restore()
        }

        if (copyMode) drawCopyUi(canvas)
        canvas.restore()
    }

    private fun drawCopyUi(canvas: Canvas) {
        // 상단 안내
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val hint = "길게 누르거나 탭해서 복사 · 빈 곳을 탭하면 종료"
        val tw = chipText.measureText(hint) + dp(24f)
        hintRect.set((sw - tw) / 2f, dp(40f), (sw + tw) / 2f, dp(40f) + dp(34f))
        canvas.drawRoundRect(hintRect, dp(17f), dp(17f), hintBg)
        canvas.drawText(hint, hintRect.centerX(), hintRect.centerY() + dp(5f), chipText)

        val s = selected ?: run {
            chipRects[0].setEmpty(); chipRects[1].setEmpty(); return
        }
        val ch = dp(38f)
        val cw = dp(92f)
        val gap = dp(8f)
        var top = s.rect.top - gap - ch
        if (top < dp(84f)) top = s.rect.bottom + gap
        var left = s.rect.left.coerceAtLeast(dp(8f))
        if (left + cw * 2 + gap > sw - dp(8f)) left = sw - dp(8f) - cw * 2 - gap
        for (i in 0..1) {
            val l = left + i * (cw + gap)
            chipRects[i].set(l, top, l + cw, top + ch)
            canvas.drawRoundRect(chipRects[i], dp(19f), dp(19f), chipBg)
            canvas.drawText(chipLabels[i], chipRects[i].centerX(), chipRects[i].centerY() + dp(5f), chipText)
        }
    }
}
