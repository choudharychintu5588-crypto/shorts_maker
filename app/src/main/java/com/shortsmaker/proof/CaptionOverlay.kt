package com.shortsmaker.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay

data class CaptionStyle(
    val textSizePx: Float = 76f,
    val normalColor: Int = Color.WHITE,
    val highlightColor: Int = Color.rgb(255, 230, 0),
    val outlineColor: Int = Color.BLACK,
    val outlineWidthPx: Float = 12f,
    val bottomMarginPx: Float = 380f,   // distance from bottom edge to text baseline
    val wordsPerLine: Int = 3,
)

/**
 * Draws word-by-word highlighted captions as a full-frame transparent image on top of the video.
 * `words` use ORIGINAL video times in ms; `clipStartMs` converts them to clip time.
 */
@UnstableApi
class CaptionOverlay(
    words: List<Word>, private val clipStartMs: Long, private val style: CaptionStyle,
    private val width: Int = 1080, private val height: Int = 1920,
) : BitmapOverlay() {

    private class Chunk(val words: List<Word>, val start: Long, var end: Long)

    private val chunks: List<Chunk>
    private val empty: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val pingPong = arrayOf(
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
    private var which = 0
    private var lastKey = -1L
    private var lastBitmap: Bitmap = empty

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); this.style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    init {
        val list = ArrayList<Chunk>()
        var cur = ArrayList<Word>()
        fun flush() {
            if (cur.isNotEmpty()) { list.add(Chunk(cur, cur.first().t0, cur.last().t1 + 250)); cur = ArrayList() }
        }
        for (w in words.filter { it.t1 >= clipStartMs }) {
            cur.add(w)
            val endsSentence = w.text.last() in ".?!।,"
            if (cur.size >= style.wordsPerLine || endsSentence) flush()
        }
        flush()
        // a chunk stays up until the next one starts (but never longer than +600 ms after its last word if there is a gap)
        for (i in 0 until list.size - 1) {
            val gapEnd = list[i + 1].start
            list[i].end = minOf(gapEnd, list[i].words.last().t1 + 600).coerceAtLeast(list[i].words.last().t1)
        }
        chunks = list
    }

    /** What would be shown at this clip time: chunk index and highlighted word index (or -1). Used by tests too. */
    fun stateAt(clipTimeMs: Long): Pair<Int, Int> {
        val t = clipTimeMs + clipStartMs
        for ((ci, c) in chunks.withIndex()) {
            if (t >= c.start && t < c.end) {
                var active = 0
                for ((wi, w) in c.words.withIndex()) if (t >= w.t0) active = wi
                return ci to active
            }
        }
        return -1 to -1
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val (ci, wi) = stateAt(presentationTimeUs / 1000)
        if (ci < 0) { lastKey = -1; lastBitmap = empty; return empty }
        val key = ci * 1000L + wi
        if (key == lastKey) return lastBitmap
        which = 1 - which
        val bmp = pingPong[which]
        draw(bmp, chunks[ci], wi)
        lastKey = key
        lastBitmap = bmp
        return bmp
    }

    private fun draw(bmp: Bitmap, chunk: Chunk, active: Int) {
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        var size = style.textSizePx
        val maxW = width * 0.90f
        fill.textSize = size
        val gap = size * 0.28f
        fun totalWidth(): Float = chunk.words.sumOf { fill.measureText(it.text).toDouble() }.toFloat() + gap * (chunk.words.size - 1)
        var total = totalWidth()
        while (total > maxW && size > 28f) { size -= 4f; fill.textSize = size; total = totalWidth() }
        stroke.textSize = size
        stroke.strokeWidth = style.outlineWidthPx * (size / style.textSizePx)
        stroke.color = style.outlineColor
        var x = (width - total) / 2f
        val y = height - style.bottomMarginPx
        for ((i, w) in chunk.words.withIndex()) {
            canvas.drawText(w.text, x, y, stroke)
            fill.color = if (i == active) style.highlightColor else style.normalColor
            canvas.drawText(w.text, x, y, fill)
            x += fill.measureText(w.text) + gap
        }
    }
}
