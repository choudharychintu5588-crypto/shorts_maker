package com.shortsmaker.proof

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Sentence(val words: List<Word>) {
    val t0: Long get() = words.first().t0
    val t1: Long get() = words.last().t1
    val text: String get() = words.joinToString(" ") { it.text }
}

data class Moment(
    val startMs: Long, val endMs: Long, val score: Int, val words: List<Word>,
    val hook: String, val reason: String, val funnyHits: Int,
)

object SentenceSplitter {
    private val enders = charArrayOf('.', '?', '!', '।', '…', '॥')

    fun split(words: List<Word>, gapMs: Long = 900, maxWords: Int = 28): List<Sentence> {
        val out = ArrayList<Sentence>()
        var cur = ArrayList<Word>()
        for ((i, w) in words.withIndex()) {
            cur.add(w)
            val next = words.getOrNull(i + 1)
            val endsSentence = w.text.isNotEmpty() && w.text.last() in enders
            val bigGap = next != null && next.t0 - w.t1 >= gapMs
            if (endsSentence || bigGap || cur.size >= maxWords || next == null) {
                out.add(Sentence(cur)); cur = ArrayList()
            }
        }
        return out
    }
}

/** Loudness of the audio in 500 ms steps (used to spot laughter / excited speech). */
object AudioEnergy {
    const val WINDOW_MS = 500

    fun rmsWindows(pcm: File): FloatArray {
        val perWindow = 16000 * WINDOW_MS / 1000
        val total = pcm.length() / 2
        val n = ((total + perWindow - 1) / perWindow).toInt()
        val out = FloatArray(n)
        RandomAccessFile(pcm, "r").use { f ->
            val buf = ByteArray(perWindow * 2)
            for (w in 0 until n) {
                val read = f.read(buf)
                if (read <= 0) break
                val cnt = read / 2
                var sum = 0.0
                for (k in 0 until cnt) {
                    val v = ((buf[2 * k + 1].toInt() shl 8) or (buf[2 * k].toInt() and 0xFF)).toShort().toInt()
                    sum += v.toDouble() * v
                }
                out[w] = (sqrt(sum / max(cnt, 1)) / 32768.0).toFloat()
            }
        }
        return out
    }
}

object Lexicon {
    val funny = setOf(
        "haha", "hahaha", "hehe", "lol", "pagal", "paagal", "mazaak", "mazak", "maza", "funny", "comedy", "joke",
        "hasna", "hasi", "bakwas", "bakwaas", "gazab", "kamaal", "kamal", "arre", "arey", "oye", "abe", "bhai", "yaar",
        "बकवास", "पागल", "मज़ा", "मजा", "मज़ाक", "मजाक", "हंसी", "हँसी", "अरे", "ओए", "यार", "भाई", "कमाल", "गज़ब", "गजब",
        "हाहा", "हा", "wow", "omg", "crazy", "insane", "ridiculous", "hilarious", "silly",
    )
    val hook = setOf(
        "kya", "kyun", "kaise", "kabhi", "suno", "dekho", "sach", "secret", "raaz", "sabse", "pehli", "wait", "listen",
        "imagine", "believe", "why", "how", "never", "nobody", "everyone", "truth", "shocking",
        "क्या", "क्यों", "कैसे", "सुनो", "देखो", "सच", "राज", "सबसे", "कभी",
    )
    val stop = setOf(
        "the", "and", "that", "this", "with", "have", "from", "they", "your", "what", "about", "there", "would",
        "which", "their", "when", "will", "were", "been", "into", "than", "then", "them", "just", "like", "also",
        "hai", "hain", "tha", "thi", "the", "kya", "toh", "nahi", "mein", "main", "aur", "bhi", "woh", "yeh", "kuch",
        "koi", "kar", "karo", "karna", "wala", "wali", "liye", "hota", "hoga", "hoon", "aap", "hum", "tum", "mera",
        "meri", "mere", "unka", "iska", "isko", "usko", "jab", "tab", "abhi", "phir", "bahut", "sirf", "lekin",
    )
    fun clean(w: String): String = w.lowercase().trim { !it.isLetterOrDigit() }
}

class MomentSelector(
    private val minMs: Long, private val maxMs: Long, private val count: Int,
) {
    private class Cand(val i: Int, val j: Int, val score: Double, val reason: String, val funny: Int)

    fun select(words: List<Word>, energy: FloatArray?, totalMs: Long): List<Moment> {
        if (words.size < 8) return emptyList()
        val sentences = SentenceSplitter.split(words)
        var result = pick(sentences, energy, minMs, maxMs, count, emptyList())
        if (result.size < count) {
            // not enough long-enough moments: allow shorter ones (never below 10 s) to fill the list
            val relaxedMin = max(10_000L, (minMs * 0.6).toLong())
            result = pick(sentences, energy, relaxedMin, maxMs, count, result)
        }
        return result.sortedByDescending { it.score }.map { padded(it, words, totalMs) }
    }

    private fun padded(m: Moment, all: List<Word>, totalMs: Long): Moment {
        val first = m.words.first()
        val last = m.words.last()
        val idxFirst = all.indexOfFirst { it.t0 == first.t0 && it.text == first.text }
        val idxLast = all.indexOfLast { it.t1 == last.t1 && it.text == last.text }
        val prevEnd = if (idxFirst > 0) all[idxFirst - 1].t1 else 0L
        val nextStart = if (idxLast >= 0 && idxLast + 1 < all.size) all[idxLast + 1].t0 else totalMs
        val start = max(max(prevEnd + 40, first.t0 - 250), 0L).coerceAtMost(first.t0)
        val end = min(min(nextStart - 40, last.t1 + 400), totalMs).coerceAtLeast(last.t1)
        return m.copy(startMs = start, endMs = end)
    }

    private fun pick(
        sentences: List<Sentence>, energy: FloatArray?, minMs: Long, maxMs: Long, count: Int, already: List<Moment>,
    ): List<Moment> {
        val gMean: Double
        val gStd: Double
        if (energy != null && energy.isNotEmpty()) {
            gMean = energy.average()
            gStd = sqrt(energy.map { (it - gMean) * (it - gMean) }.average()) + 1e-6
        } else { gMean = 0.0; gStd = 1.0 }

        val cands = ArrayList<Cand>()
        for (i in sentences.indices) {
            var j = i
            while (j < sentences.size) {
                val dur = sentences[j].t1 - sentences[i].t0
                if (dur > maxMs) break
                if (dur >= minMs) cands.add(score(sentences, i, j, energy, gMean, gStd, dur, maxMs))
                j++
            }
        }
        val chosen = ArrayList<Moment>(already)
        val minGap = 2_000L
        for (c in cands.sortedByDescending { it.score }) {
            if (chosen.size >= count) break
            val s = sentences[c.i].t0
            val e = sentences[c.j].t1
            if (chosen.any { s < it.endMs + minGap && e > it.startMs - minGap }) continue
            val ws = (c.i..c.j).flatMap { sentences[it].words }
            chosen.add(Moment(s, e, c.score.toInt().coerceIn(1, 100), ws, sentences[c.i].text, c.reason, c.funny))
        }
        return chosen
    }

    private fun score(
        sentences: List<Sentence>, i: Int, j: Int, energy: FloatArray?, gMean: Double, gStd: Double, dur: Long, maxMs: Long,
    ): Cand {
        val ws = (i..j).flatMap { sentences[it].words }
        val reasons = ArrayList<String>()
        var sc = 0.0

        val funny = ws.count { Lexicon.clean(it.text) in Lexicon.funny }
        sc += min(funny, 4) * 7.0
        if (funny >= 2) reasons.add("funny / playful words")

        val first = sentences[i]
        val firstWord = Lexicon.clean(first.words.first().text)
        var hook = 0.0
        if (first.words.last().text.endsWith("?")) hook += 9
        if (firstWord in Lexicon.hook) hook += 7
        val excl = ws.count { it.text.endsWith("!") }
        hook += min(excl, 3) * 3.0
        sc += hook
        if (hook >= 9) reasons.add("strong opening")

        if (energy != null && energy.isNotEmpty()) {
            val a = (sentences[i].t0 / AudioEnergy.WINDOW_MS).toInt().coerceIn(0, energy.size - 1)
            val b = (sentences[j].t1 / AudioEnergy.WINDOW_MS).toInt().coerceIn(a, energy.size - 1)
            var sum = 0.0; var peak = 0f
            for (k in a..b) { sum += energy[k]; if (energy[k] > peak) peak = energy[k] }
            val mean = sum / (b - a + 1)
            val z = ((mean - gMean) / gStd).coerceIn(0.0, 2.0)
            val peaky = ((peak - gMean) / gStd).coerceIn(0.0, 4.0)
            sc += z / 2.0 * 15.0 + peaky / 4.0 * 10.0
            if (z > 0.8 || peaky > 2.5) reasons.add("lively, high-energy audio")
        }

        val sec = dur / 1000.0
        val rate = ws.size / max(sec, 1.0)
        sc += when {
            rate in 1.8..4.2 -> 10.0
            rate in 1.2..5.0 -> 5.0
            else -> 0.0
        }
        val speechMs = ws.sumOf { max(0L, it.t1 - it.t0) }
        val density = speechMs / max(dur, 1L).toDouble()
        sc += (density.coerceIn(0.0, 0.85) / 0.85) * 10.0

        val prevGap = if (i > 0) sentences[i].t0 - sentences[i - 1].t1 else 1000L
        if (prevGap >= 300) sc += 5.0
        val lastText = sentences[j].words.last().text
        if (lastText.isNotEmpty() && lastText.last() in SentenceSplitter_enders) sc += 5.0

        sc += (1.0 - abs(dur - maxMs * 0.85) / maxMs.toDouble()).coerceIn(0.0, 1.0) * 5.0
        // map the raw 0..~100 value to a friendlier engagement score
        val shown = (sc * 1.1 + 8).coerceIn(1.0, 99.0)
        if (reasons.isEmpty()) reasons.add("clear, well-paced speech")
        return Cand(i, j, shown, reasons.joinToString(", "), funny)
    }

    private val SentenceSplitter_enders = charArrayOf('.', '?', '!', '।', '…', '॥')
}

data class ShortInfo(val title: String, val hashtags: String, val description: String)

object Titles {
    fun make(moment: Moment, romanWords: List<Word>): ShortInfo {
        val sentences = SentenceSplitter.split(romanWords)
        var first = sentences.firstOrNull()?.text ?: ""
        first = first.trim().trim('.', ',', '।', '…', ' ')
        val emoji = if (moment.funnyHits >= 2) "😂" else "🔥"
        var title = first
        if (title.length > 62) {
            title = title.substring(0, 62).substringBeforeLast(' ', title.substring(0, 62)).trim()
            title += "…"
        }
        if (title.isBlank()) title = "Best moment"
        title = title.replaceFirstChar { it.uppercase() } + " $emoji"

        val freq = HashMap<String, Int>()
        for (w in romanWords) {
            val c = Lexicon.clean(w.text)
            if (c.length >= 4 && c !in Lexicon.stop && c.all { it in 'a'..'z' }) freq[c] = (freq[c] ?: 0) + 1
        }
        val kws = freq.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3).map { "#" + it.key }
        val tags = (listOf("#shorts", "#hinglish", "#viral") + kws).joinToString(" ")

        val desc = sentences.take(2).joinToString(" ") { it.text.trim() }.take(180).trim()
        return ShortInfo(title, tags, if (desc.isBlank()) title else desc)
    }
}
