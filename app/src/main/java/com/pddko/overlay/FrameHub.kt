package com.pddko.overlay

import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * 화면 캡처 프레임을 받아 번역 엔진에 넘겨주는 허브.
 * latest / consumer 는 handler 스레드에서만 다룬다.
 */
object FrameHub {
    interface Consumer {
        fun onFrame(image: Image, arrivalMs: Long)
    }

    private val thread = HandlerThread("pdd-frames").apply { start() }
    val handler = Handler(thread.looper)

    @Volatile
    var running = false
        private set

    private var latest: Image? = null
    var consumer: Consumer? = null

    fun attach() {
        running = true
    }

    fun onImageAvailable(reader: ImageReader) {
        val img = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        latest?.close()
        latest = img
        try {
            consumer?.onFrame(img, SystemClock.uptimeMillis())
        } catch (e: Exception) {
            // 엔진 오류가 캡처 전체를 멈추지 않도록
        }
    }

    fun latestImage(): Image? = latest

    fun detach() {
        running = false
        latest?.close()
        latest = null
    }
}
