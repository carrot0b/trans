package com.pddko.overlay

import android.graphics.RectF
import android.text.StaticLayout

/** 화면 위에 덮어 그릴 번역 한 덩어리 (좌표는 실제 화면 픽셀 기준). */
class Box(
    val rect: RectF,
    var clipTop: Float,
    var clipBottom: Float,
    val bg: Int,
    val fg: Int,
    val layout: StaticLayout,
    val padX: Float,
    val ko: String,
    val zh: String,
) {
    var moving = false

    fun copy(): Box =
        Box(RectF(rect), clipTop, clipBottom, bg, fg, layout, padX, ko, zh).also { it.moving = moving }
}
