package com.pddko.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * 접근성 서비스:
 *  - 지금 화면 맨 앞 앱이 핀둬둬인지 감지 → 자동 켜기/끄기
 *  - 번역 오버레이 창(TYPE_ACCESSIBILITY_OVERLAY)을 띄움
 *    (이 창 종류여야 Android 12+ 에서도 아래 앱으로 터치가 그대로 전달됨)
 */
class TranslatorService : AccessibilityService(), Engine.Ui, OverlayView.Listener {

    companion object {
        const val PDD = "com.xunmeng.pinduoduo"

        @Volatile
        var instance: TranslatorService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: OverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var engine: Engine? = null

    private var inPdd = false
    private var active = false
    private var userPaused = false
    private var copyMode = false

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val e = Engine(this)
        engine = e
        FrameHub.handler.post { FrameHub.consumer = e }
        createWindows()
        recheckSoon()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val t = event?.eventType ?: return
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            t == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) recheckSoon()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        FrameHub.handler.post { FrameHub.consumer = null }
        engine?.shutdown()
        engine = null
        try {
            overlay?.let { wm?.removeView(it) }
            button?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        overlay = null
        button = null
        super.onDestroy()
    }

    // ---------------- 앞 화면 감지 ----------------

    private val recheck = Runnable { evaluate() }

    fun recheckSoon() {
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, 120)
    }

    private fun evaluate() {
        val pkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        // 우리 앱 창이나 알 수 없는 경우엔 이전 판단 유지
        if (pkg != null && pkg != packageName) inPdd = pkg == PDD

        val want = inPdd && FrameHub.running && !userPaused
        if (want != active) {
            active = want
            if (!want && copyMode) setCopyMode(false)
            engine?.setActive(want)
            overlay?.visibility = if (want) View.VISIBLE else View.GONE
        }
        button?.visibility = if (inPdd && FrameHub.running) View.VISIBLE else View.GONE
        updateButtonStyle()
        updateButtonRect()
    }

    // ---------------- 창 만들기 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun createWindows() {
        val w = wm ?: return
        val ov = OverlayView(this, this)
        val op = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        ov.visibility = View.GONE
        w.addView(ov, op)
        overlay = ov
        overlayParams = op

        val size = dp(48f)
        val dm = resources.displayMetrics
        val btn = TextView(this).apply {
            text = "译→한"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = dp(6f).toFloat()
        }
        val bp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - dp(10f)
            y = dm.heightPixels / 3
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            btn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            togglePause()
        }
        btn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bp.x; startY = bp.y
                    dragging = false; longFired = false
                    main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > dp(8f)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bp.x = startX + dx.toInt()
                        bp.y = startY + dy.toInt()
                        w.updateViewLayout(v, bp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) updateButtonRect()
                    else if (!longFired) onButtonTap()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        btn.visibility = View.GONE
        w.addView(btn, bp)
        button = btn
        buttonParams = bp
        updateButtonStyle()
    }

    private fun updateButtonRect() {
        val b = button ?: return
        b.post {
            if (b.visibility != View.VISIBLE) {
                engine?.buttonRect = null; return@post
            }
            val l = IntArray(2)
            b.getLocationOnScreen(l)
            engine?.buttonRect = RectF(
                l[0].toFloat(), l[1].toFloat(),
                (l[0] + b.width).toFloat(), (l[1] + b.height).toFloat()
            )
        }
    }

    private fun updateButtonStyle() {
        val b = button ?: return
        val (label, color) = when {
            userPaused -> "OFF" to Color.argb(200, 120, 120, 120)
            copyMode -> "복사" to Color.argb(235, 25, 118, 210)
            else -> "译→한" to Color.argb(220, 224, 46, 36)
        }
        b.text = label
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1.5f), Color.WHITE)
        }
    }

    // ---------------- 버튼 동작 ----------------

    /** 탭: 복사 모드 켜기/끄기 */
    private fun onButtonTap() {
        if (userPaused) {
            togglePause(); return
        }
        if (active) setCopyMode(!copyMode)
    }

    /** 길게 누르기: 번역 일시정지/재개 */
    private fun togglePause() {
        userPaused = !userPaused
        if (userPaused && copyMode) setCopyMode(false)
        Toast.makeText(this, if (userPaused) "번역 일시정지 (다시 길게 누르면 재개)" else "번역 재개", Toast.LENGTH_SHORT).show()
        evaluate()
    }

    private fun setCopyMode(on: Boolean) {
        val op = overlayParams ?: return
        val ov = overlay ?: return
        copyMode = on
        op.flags = if (on) {
            op.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            op.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm?.updateViewLayout(ov, op)
        } catch (e: Exception) {
        }
        ov.copyMode = on
        engine?.setPaused(on)
        updateButtonStyle()
    }

    // ---------------- Engine.Ui ----------------

    override fun showBoxes(boxes: List<Box>, predictDy: Float) {
        overlay?.setBoxes(boxes, predictDy)
    }

    override fun setHidden(hidden: Boolean) {
        overlay?.alpha = if (hidden) 0f else 1f
    }

    // ---------------- OverlayView.Listener ----------------

    override fun onExitCopyMode() = setCopyMode(false)

    override fun onCopy(text: String, label: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "$label 복사됨: ${text.take(30)}", Toast.LENGTH_SHORT).show()
        }
    }
}
