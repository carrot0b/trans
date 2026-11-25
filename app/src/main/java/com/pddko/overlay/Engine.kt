package com.pddko.overlay

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 번역 엔진 (FrameHub 스레드에서 동작).
 *
 * 흐름
 *  1. 매 프레임: 스크롤량 추정 → 번역 박스를 같은 만큼 이동 (손가락을 따라 움직임)
 *  2. 화면이 0.35초 멈추면: 오버레이를 잠깐 숨기고 깨끗한 화면을 캡처 → OCR → 번역 → 박스 교체
 *  3. OCR 도중 또 스크롤되면 그동안의 이동량을 결과에 반영
 */
class Engine(private val ui: Ui) : FrameHub.Consumer {

    interface Ui {
        fun showBoxes(boxes: List<Box>, predictDy: Float)
        fun setHidden(hidden: Boolean)
    }

    private enum class State { IDLE, HIDING, RUNNING }

    private val main = Handler(Looper.getMainLooper())
    private val fh = FrameHub.handler
    private val ocr = OcrPipeline()
    private val ocrExec = Executors.newSingleThreadExecutor()

    private var tracker: MotionTracker? = null
    private var mask = BooleanArray(0)
    private var boxes = ArrayList<Box>()
    private val history = ArrayDeque<List<RectF>>()

    private var active = false
    private var paused = false
    private var generation = 0

    private var stickyTop = 0f
    private var stickyBottom = 0f
    private var lastMotionMs = 0L
    private var lastPred = 0f

    private var state = State.IDLE
    private var hideAt = 0L
    private var again = false
    private val shiftLog = ArrayList<FloatArray>()
    private var bigSinceCapture = false

    @Volatile
    var buttonRect: RectF? = null

    init {
        ocr.ensureModel()
    }

    fun setActive(on: Boolean) {
        fh.post {
            if (on == active) return@post
            active = on
            generation++
            fh.removeCallbacks(ocrTrigger)
            fh.removeCallbacks(hideTimeout)
            fh.removeCallbacks(idle)
            boxes.clear()
            history.clear()
            tracker?.reset()
            if (state == State.HIDING) main.post { ui.setHidden(false) }
            state = State.IDLE
            again = false
            publish(0f)
            if (on) scheduleOcr(450)
        }
    }

    fun setPaused(p: Boolean) {
        fh.post {
            paused = p
            tracker?.reset()
            if (!p) scheduleOcr(300)
        }
    }

    fun shutdown() {
        fh.post {
            active = false
            generation++
            fh.removeCallbacks(ocrTrigger)
            fh.removeCallbacks(hideTimeout)
            fh.removeCallbacks(idle)
        }
        ocrExec.execute { ocr.close() }
        ocrExec.shutdown()
    }

    // ---------------- 프레임 처리 ----------------

    override fun onFrame(image: Image, arrivalMs: Long) {
        if (!active || paused) return
        var t = tracker
        if (t == null || !t.matches(image.width, image.height)) {
            t = MotionTracker(image.width, image.height)
            tracker = t
            mask = BooleanArray(t.gw * t.gh)
        }
        val g = t.sample(image)
        buildMask(t)
        val m = t.estimate(g, mask)
        if (m != null) onMotion(m, arrivalMs)

        if (state == State.HIDING && hideAt > 0 && arrivalMs - hideAt >= 50) capture(image)
    }

    private fun onMotion(m: MotionTracker.Motion, now: Long) {
        if (now - lastMotionMs > 500) {
            stickyTop = m.top; stickyBottom = m.bottom
        } else {
            stickyTop = min(stickyTop, m.top); stickyBottom = max(stickyBottom, m.bottom)
        }
        val dt = (now - lastMotionMs).coerceIn(8L, 100L).toFloat()
        lastMotionMs = now

        if (m.bigChange) {
            // 화면 전환 등: 해당 영역 번역은 지우고 다시 인식
            boxes.removeAll { it.rect.centerY() in m.top..m.bottom }
            bigSinceCapture = true
            publish(0f)
        } else {
            val dy = m.dy
            for (b in boxes) {
                if (b.rect.centerY() in stickyTop..stickyBottom) {
                    b.rect.offset(0f, dy)
                    b.clipTop = stickyTop
                    b.clipBottom = stickyBottom
                    b.moving = true
                }
            }
            boxes.removeAll { it.rect.bottom < it.clipTop || it.rect.top > it.clipBottom }
            shiftLog.add(floatArrayOf(stickyTop, stickyBottom, dy))
            // 화면 표시 지연(약 1~2프레임)만큼 앞질러 그려서 끌림을 줄임
            val v = dy / dt
            publish((v * 24f).coerceIn(-150f, 150f))
        }
        fh.removeCallbacks(idle)
        fh.postDelayed(idle, 90)
        scheduleOcr(350)
    }

    private val idle = Runnable {
        if (lastPred != 0f) publish(0f)
    }

    private fun buildMask(t: MotionTracker) {
        java.util.Arrays.fill(mask, false)
        val s = t.s.toFloat()
        val extra = abs(lastPred)
        fun mark(r: RectF) {
            val x0 = max(0, (r.left / s).toInt() - 2)
            val x1 = min(t.gw - 1, (r.right / s).toInt() + 2)
            val y0 = max(0, ((r.top - extra) / s).toInt() - 2)
            val y1 = min(t.gh - 1, ((r.bottom + extra) / s).toInt() + 2)
            for (y in y0..y1) {
                val base = y * t.gw
                for (x in x0..x1) mask[base + x] = true
            }
        }
        for (b in boxes) mark(b.rect)
        for (h in history) for (r in h) mark(r)
        buttonRect?.let { mark(it) }
    }

    private fun publish(pred: Float) {
        lastPred = pred
        val snap = boxes.map { it.copy() }
        history.addLast(snap.map { RectF(it.rect) })
        while (history.size > 4) history.removeFirst()
        main.post { ui.showBoxes(snap, pred) }
    }

    // ---------------- OCR ----------------

    private fun scheduleOcr(delay: Long) {
        fh.removeCallbacks(ocrTrigger)
        fh.postDelayed(ocrTrigger, delay)
    }

    private val ocrTrigger = Runnable {
        if (!active || paused) return@Runnable
        if (state != State.IDLE) {
            again = true; return@Runnable
        }
        state = State.HIDING
        hideAt = 0L
        val gen = generation
        main.post {
            ui.setHidden(true)
            val t = SystemClock.uptimeMillis()
            fh.post {
                if (gen == generation && state == State.HIDING) {
                    hideAt = t
                    fh.postDelayed(hideTimeout, 220)
                }
            }
        }
    }

    /** 오버레이를 숨겨도 새 프레임이 안 오면(= 화면에 박스가 없었음) 마지막 프레임 사용 */
    private val hideTimeout = Runnable {
        if (state == State.HIDING) {
            val img = FrameHub.latestImage()
            if (img != null) capture(img) else {
                state = State.IDLE
                main.post { ui.setHidden(false) }
            }
        }
    }

    private fun capture(img: Image) {
        fh.removeCallbacks(hideTimeout)
        val bmp = try {
            toBitmap(img)
        } catch (e: Exception) {
            null
        }
        main.post { ui.setHidden(false) }
        if (bmp == null) {
            state = State.IDLE; return
        }
        state = State.RUNNING
        shiftLog.clear()
        bigSinceCapture = false
        val gen = generation
        ocrExec.execute {
            val result = try {
                ocr.process(bmp)
            } catch (e: Exception) {
                null
            } finally {
                bmp.recycle()
            }
            fh.post { applyResult(result, gen) }
        }
    }

    private fun applyResult(result: List<Box>?, gen: Int) {
        if (gen != generation) return
        state = State.IDLE
        if (result != null && !bigSinceCapture) {
            for (b in result) {
                for (s in shiftLog) {
                    if (b.rect.centerY() in s[0]..s[1]) {
                        b.rect.offset(0f, s[2])
                        b.clipTop = s[0]
                        b.clipBottom = s[1]
                    }
                }
            }
            boxes = ArrayList(result.filter { it.rect.bottom > it.clipTop && it.rect.top < it.clipBottom })
            publish(lastPred)
        }
        when {
            result == null -> scheduleOcr(2000)
            again || bigSinceCapture -> {
                again = false; scheduleOcr(250)
            }
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val w = img.width
        val h = img.height
        val padW = rs / ps
        val buf = plane.buffer
        buf.rewind()
        val need = rs * h
        val src: ByteBuffer = if (buf.remaining() >= need) buf else {
            val copy = ByteBuffer.allocateDirect(need)
            copy.put(buf)
            copy.rewind()
            copy
        }
        val full = Bitmap.createBitmap(padW, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(src)
        buf.rewind()
        if (padW == w) return full
        val c = Bitmap.createBitmap(full, 0, 0, w, h)
        full.recycle()
        return c
    }
}
