package com.pddko.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * 캡처 이미지 → 중국어 OCR → 한국어 번역 → 원문과 같은 색/크기의 Box 목록.
 * 반드시 백그라운드 스레드에서 호출.
 */
class OcrPipeline {
    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )
    private val cache = LruCache<String, String>(4000)
    private val han = Regex("\\p{IsHan}")
    private val hanSpace = Regex("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})")

    private class Chunk(val zh: String, val rect: Rect, val lines: List<Rect>, val lineH: Float)
    private class Style(val bg: Int, val fg: Int, val bold: Boolean)

    fun ensureModel() {
        translator.downloadModelIfNeeded()
    }

    fun close() {
        recognizer.close()
        translator.close()
    }

    fun process(bmp: Bitmap): List<Box> {
        val text = Tasks.await(
            recognizer.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS
        )
        val units = ArrayList<Chunk>()
        for (block in text.textBlocks) {
            val lines = block.lines.filter {
                it.boundingBox != null && han.containsMatchIn(it.text)
            }
            if (lines.isEmpty()) continue
            if (isParagraph(lines)) {
                val rects = lines.map { it.boundingBox!! }
                val u = Rect(rects[0])
                rects.forEach { u.union(it) }
                val h = rects.map { it.height() }.average().toFloat()
                units += Chunk(joinZh(lines.map { clean(it.text) }), u, rects, h)
            } else {
                for (l in lines) {
                    val r = l.boundingBox!!
                    units += Chunk(clean(l.text), r, listOf(r), r.height().toFloat())
                }
            }
        }
        if (units.isEmpty()) return emptyList()

        // 번역 (캐시에 없는 것만, 병렬)
        val need = units.map { it.zh }.distinct().filter { cache.get(it) == null }
        if (need.isNotEmpty()) {
            val tasks = need.map { translator.translate(it) }
            try {
                Tasks.await(Tasks.whenAllComplete(tasks), 8, TimeUnit.SECONDS)
            } catch (e: Exception) {
            }
            need.forEachIndexed { i, src ->
                val t = tasks[i]
                if (t.isComplete && t.isSuccessful) t.result?.let { cache.put(src, it) }
            }
        }

        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val out = ArrayList<Box>()
        for (u in units) {
            val ko = cache.get(u.zh) ?: continue
            if (ko.isBlank() || ko == u.zh) continue
            val st = style(px, w, h, u.lines)
            out += buildBox(u, ko, st, h.toFloat())
        }
        return out
    }

    // ---------- 문단 판별 / 텍스트 정리 ----------

    private fun isParagraph(lines: List<Text.Line>): Boolean {
        if (lines.size < 2) return false
        val rs = lines.map { it.boundingBox!! }
        val h0 = rs.map { it.height() }.average()
        if (rs.any { it.height() < h0 * 0.7 || it.height() > h0 * 1.4 }) return false
        val left0 = rs[0].left
        if (rs.any { abs(it.left - left0) > h0 * 1.2 }) return false
        for (i in 1 until rs.size) {
            val gap = rs[i].top - rs[i - 1].bottom
            if (gap > h0 * 1.0 || gap < -h0 * 0.5) return false
        }
        return true
    }

    private fun clean(s: String) = s.replace(hanSpace, "").trim()

    private fun joinZh(parts: List<String>): String {
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty()) {
                val a = sb.last()
                val b = p.firstOrNull() ?: continue
                val cjkJoin = isCjk(a) || isCjk(b)
                if (!cjkJoin) sb.append(' ')
            }
            sb.append(p)
        }
        return sb.toString()
    }

    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN ||
            c in '　'..'〿' || c in '＀'..'￯'

    // ---------- 색상 추출 ----------

    private val hist = IntArray(4096)
    private val sr = LongArray(4096)
    private val sg = LongArray(4096)
    private val sb = LongArray(4096)

    private fun resetHist() {
        hist.fill(0); sr.fill(0); sg.fill(0); sb.fill(0)
    }

    private fun addHist(c: Int) {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        hist[k]++; sr[k] += r.toLong(); sg[k] += g.toLong(); sb[k] += b.toLong()
    }

    private fun modeColor(): Int? {
        var bi = -1
        var bc = 0
        for (i in 0 until 4096) if (hist[i] > bc) {
            bc = hist[i]; bi = i
        }
        if (bi < 0) return null
        val n = hist[bi].toLong()
        return Color.rgb((sr[bi] / n).toInt(), (sg[bi] / n).toInt(), (sb[bi] / n).toInt())
    }

    private fun dist2(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return dr * dr + dg * dg + db * db
    }

    private inline fun forEachSample(px: IntArray, w: Int, h: Int, rects: List<Rect>, f: (Int) -> Unit) {
        for (r in rects) {
            val l = r.left.coerceIn(0, w - 1)
            val rr = r.right.coerceIn(l + 1, w)
            val t = r.top.coerceIn(0, h - 1)
            val b = r.bottom.coerceIn(t + 1, h)
            val step = max(1, (b - t) / 16)
            var y = t
            while (y < b) {
                val base = y * w
                var x = l
                while (x < rr) {
                    f(px[base + x]); x += step
                }
                y += step
            }
        }
    }

    private fun style(px: IntArray, w: Int, h: Int, rects: List<Rect>): Style {
        // 배경색 = 박스 안에서 가장 많이 나오는 색
        resetHist()
        var total = 0
        forEachSample(px, w, h, rects) { addHist(it); total++ }
        val bg = modeColor() ?: Color.WHITE

        // 글자색 = 배경과 확실히 다른 픽셀들 중 가장 많은 색
        resetHist()
        var ink = 0
        forEachSample(px, w, h, rects) {
            if (dist2(it, bg) > 3600) {
                addHist(it); ink++
            }
        }
        val contrast = if (luma(bg) > 140) Color.rgb(34, 34, 34) else Color.WHITE
        var fg = if (ink >= 8) modeColor() ?: contrast else contrast
        if (dist2(fg, bg) < 3600) fg = contrast
        val bold = total > 0 && ink.toFloat() / total > 0.33f
        return Style(bg or 0xFF000000.toInt(), fg or 0xFF000000.toInt(), bold)
    }

    private fun luma(c: Int) =
        (((c shr 16) and 0xff) * 77 + ((c shr 8) and 0xff) * 150 + (c and 0xff) * 29) shr 8

    // ---------- 글자 크기 맞추기 ----------

    private fun buildBox(u: Chunk, ko: String, st: Style, screenH: Float): Box {
        val lineH = u.lineH
        val pad = max(2f, lineH * 0.12f)
        val rect = RectF(u.rect)
        rect.inset(-pad, -pad)
        val padX = pad
        val availW = (rect.width() - 2 * padX).toInt().coerceAtLeast(1)
        val availH = rect.height() - 2 * pad

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = st.fg
        tp.typeface = if (st.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        var size = lineH * 0.88f
        val minSize = max(lineH * 0.5f, 9f)
        tp.textSize = size
        var layout = make(ko, tp, availW)
        while (layout.height > availH * 1.08f && size > minSize) {
            size = max(minSize, size * 0.92f)
            tp.textSize = size
            layout = make(ko, tp, availW)
        }
        if (layout.height > availH) rect.bottom = rect.top + layout.height + 2 * pad
        return Box(rect, 0f, screenH, st.bg, st.fg, layout, padX, ko, u.zh)
    }

    private fun make(text: String, tp: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
}
