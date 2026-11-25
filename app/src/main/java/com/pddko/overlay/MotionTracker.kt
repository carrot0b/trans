package com.pddko.overlay

import android.media.Image
import kotlin.math.abs
import kotlin.math.min

/**
 * 연속된 두 프레임을 비교해 '세로로 얼마나 스크롤됐는지' 추정한다.
 * - 화면을 1/4로 줄인 흑백 격자에서 비교 (빠름)
 * - 변화가 있는 행만 골라 스크롤 영역(band)을 찾음 → 고정 헤더/하단 탭은 자동 제외
 * - 번역 박스가 그려진 자리는 mask 로 빼고 비교 (자기 자신을 추적하지 않도록)
 */
class MotionTracker(val screenW: Int, val screenH: Int) {
    val s = 4
    val gw = screenW / s
    val gh = screenH / s

    private var prev: IntArray? = null
    private var spare = IntArray(gw * gh)

    class Motion(val top: Float, val bottom: Float, val dy: Float, val bigChange: Boolean)

    fun matches(w: Int, h: Int) = w == screenW && h == screenH

    fun reset() {
        prev = null
    }

    /** RGBA 프레임 → 1/4 축소 흑백 (각 칸 4점 평균). */
    fun sample(image: Image): IntArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val limit = buf.limit()
        val out = spare
        for (gy in 0 until gh) {
            val y0 = (gy * s + 1) * rs
            val y1 = (gy * s + 3) * rs
            var o = gy * gw
            for (gx in 0 until gw) {
                val x0 = (gx * s + 1) * ps
                val x1 = (gx * s + 3) * ps
                var acc = 0
                acc += lum(buf, y0 + x0, limit)
                acc += lum(buf, y0 + x1, limit)
                acc += lum(buf, y1 + x0, limit)
                acc += lum(buf, y1 + x1, limit)
                out[o] = acc shr 2
                o++
            }
        }
        return out
    }

    private fun lum(buf: java.nio.ByteBuffer, i: Int, limit: Int): Int {
        if (i + 2 >= limit) return 0
        val r = buf.get(i).toInt() and 0xff
        val g = buf.get(i + 1).toInt() and 0xff
        val b = buf.get(i + 2).toInt() and 0xff
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private fun keep(g: IntArray) {
        val old = prev
        prev = g
        spare = old ?: IntArray(gw * gh)
    }

    /** null = 의미 있는 움직임 없음 */
    fun estimate(g: IntArray, mask: BooleanArray): Motion? {
        val p = prev
        if (p == null) {
            keep(g); return null
        }

        // 1) 변화가 있는 행 찾기
        var moving = 0
        var top = -1
        var bottom = -1
        for (y in 0 until gh) {
            var sum = 0
            var n = 0
            val base = y * gw
            var x = 0
            while (x < gw) {
                val i = base + x
                if (!mask[i]) {
                    sum += abs(g[i] - p[i]); n++
                }
                x += 2
            }
            val d = if (n >= gw / 8) sum.toFloat() / n else 0f
            if (d > 3f) {
                moving++
                if (top < 0) top = y
                bottom = y
            }
        }
        // 카운트다운 숫자처럼 작은 변화는 무시
        if (moving < 6 || bottom - top < 10) {
            keep(g); return null
        }

        val bandH = bottom - top + 1
        val maxD = min(120, (bandH * 0.9f).toInt())

        fun cost(dy: Int, rowStep: Int, colStep: Int): Float {
            if (bandH - abs(dy) < bandH * 0.35f) return Float.MAX_VALUE
            var sum = 0L
            var n = 0
            var y = top
            while (y <= bottom) {
                val yy = y - dy
                if (yy in top..bottom) {
                    val b1 = y * gw
                    val b0 = yy * gw
                    var x = 0
                    while (x < gw) {
                        if (!mask[b1 + x] && !mask[b0 + x]) {
                            sum += abs(g[b1 + x] - p[b0 + x]); n++
                        }
                        x += colStep
                    }
                }
                y += rowStep
            }
            return if (n < 60) Float.MAX_VALUE else sum.toFloat() / n
        }

        // 2) 거친 탐색
        var best = 0
        var bestC = Float.MAX_VALUE
        for (dy in -maxD..maxD) {
            val c = cost(dy, 2, 4)
            if (c < bestC) {
                bestC = c; best = dy
            }
        }
        // 3) 정밀 탐색
        var fine = best
        var fineC = Float.MAX_VALUE
        for (dy in best - 2..best + 2) {
            val c = cost(dy, 1, 2)
            if (c < fineC) {
                fineC = c; fine = dy
            }
        }
        val c0 = cost(0, 1, 2)
        keep(g)

        if (fineC == Float.MAX_VALUE) {
            return if (moving >= 25) Motion(top * s.toFloat(), (bottom + 1) * s.toFloat(), 0f, true) else null
        }
        val big = moving >= 25 && fineC > 12f && fineC > c0 * 0.6f
        if (big) return Motion(top * s.toFloat(), (bottom + 1) * s.toFloat(), 0f, true)
        if (fine == 0) return null

        // 4) 소수점 보정 (포물선 근사)
        val cm = cost(fine - 1, 1, 2)
        val cp = cost(fine + 1, 1, 2)
        var sub = 0f
        if (cm != Float.MAX_VALUE && cp != Float.MAX_VALUE) {
            val den = cm - 2 * fineC + cp
            if (den > 0.0001f) sub = (0.5f * (cm - cp) / den).coerceIn(-0.5f, 0.5f)
        }
        return Motion(top * s.toFloat(), (bottom + 1) * s.toFloat(), (fine + sub) * s, false)
    }
}
