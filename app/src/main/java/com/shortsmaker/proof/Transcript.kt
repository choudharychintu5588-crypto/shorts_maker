package com.shortsmaker.proof

import org.json.JSONObject

data class Word(val text: String, val t0: Long, val t1: Long)
data class Segment(val t0: Long, val t1: Long, val text: String, val words: List<Word>)

data class Transcript(val language: String, val segments: List<Segment>) {
    val words: List<Word> get() = segments.flatMap { it.words }
    val fullText: String get() = segments.joinToString(" ") { it.text.trim() }

    companion object {
        fun parse(json: String): Transcript {
            val o = JSONObject(json)
            val segs = o.getJSONArray("segments")
            val out = ArrayList<Segment>(segs.length())
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val ws = s.getJSONArray("words")
                val words = ArrayList<Word>(ws.length())
                for (k in 0 until ws.length()) {
                    val w = ws.getJSONObject(k)
                    words.add(Word(w.getString("w"), w.getLong("t0"), w.getLong("t1")))
                }
                out.add(Segment(s.getLong("t0"), s.getLong("t1"), s.getString("text"), words))
            }
            return Transcript(o.optString("language", ""), out)
        }
    }
}
