package com.shortsmaker.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishTest {
    private fun check(vararg pairs: Pair<String, String>) {
        val bad = pairs.filter { Hinglish.romanize(it.first) != it.second }
            .joinToString("; ") { "'${it.first}' -> '${Hinglish.romanize(it.first)}' (wanted '${it.second}')" }
        assertTrue("Hinglish mismatches: $bad", bad.isEmpty())
    }

    @Test fun commonWords() = check(
        "क्या" to "kya", "नहीं" to "nahi", "है" to "hai", "मैं" to "main", "आप" to "aap", "भाई" to "bhai", "तो" to "toh",
    )

    @Test fun rulesForOtherWords() = check(
        "कमल" to "kamal", "समझ" to "samajh", "नमस्ते" to "namaste", "बदलना" to "badalna", "आज" to "aaj",
        "जनता" to "janta", "प्यार" to "pyaar", "ज़िन्दगी" to "zindagi", "सुबह" to "subah", "मुझे" to "mujhe",
        "दुनिया" to "duniya", "मोबाइल" to "mobail", "मित्र" to "mitr",
    )

    @Test fun sentencesAndPunctuation() = check(
        "क्या है।" to "kya hai.", "Hello दोस्तों" to "Hello doston", "२०२४" to "2024", "ok bhai" to "ok bhai",
    )

    @Test fun neverCrashesOnOddInput() {
        for (s in listOf("", "्", "ा", "क्", "ं", "अ‍", "ॐ", "कक्क्क्क्", "😂 क्या!!", "x ् y")) Hinglish.romanize(s)
    }
}

class UrlAndStreamTest {
    @Test fun youtubeLinks() {
        val id = "dQw4w9WgXcQ"
        val ok = listOf(
            "https://www.youtube.com/watch?v=$id", "http://youtube.com/watch?v=$id&t=30s", "https://youtu.be/$id",
            "https://youtu.be/$id?si=abc", "https://www.youtube.com/shorts/$id", "https://m.youtube.com/watch?v=$id",
            "youtube.com/watch?v=$id", "  https://music.youtube.com/watch?v=$id  ", "https://www.youtube.com/embed/$id",
            "look at this https://youtu.be/$id please", "https://www.youtube.com/watch?feature=share&v=$id",
        )
        for (u in ok) assertEquals("link: $u", id, YouTubeUrl.extractId(u))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeUrl.normalize("https://youtu.be/$id"))
    }

    @Test fun nonYoutubeLinksAreRejected() {
        for (u in listOf("", "hello", "https://example.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/",
            "https://www.youtube.com/playlist?list=PL123", "https://www.youtube.com/@channel", "https://youtu.be/short",
            "https://evil.com/youtube.com/watch?v=dQw4w9WgXcQ", "javascript:alert(1)")) {
            assertNull("should reject: $u", YouTubeUrl.extractId(u))
        }
    }

    private fun v(h: Int, br: Int, codec: String = "avc1.640028", c: String = "mp4") = StreamChoice("u$h$codec", h, codec, br, c, 1)
    private fun a(br: Int, c: String = "m4a") = StreamChoice("a$br", 0, "mp4a", br, c, 1)

    @Test fun picksVideoAndAudio() {
        val list = listOf(v(360, 500), v(720, 2000), v(1080, 4000), v(1080, 3000, "vp09.00", "webm"), v(1440, 9000, "av01"))
        assertEquals(720, StreamPicker.pickVideo(list, 720)!!.height)
        assertEquals(1080, StreamPicker.pickVideo(list, 1080)!!.height)
        assertEquals(360, StreamPicker.pickVideo(list, 240)!!.height)   // nothing below the limit: smallest available
        assertNull(StreamPicker.pickVideo(listOf(v(720, 1, "vp09", "webm")), 720))
        assertEquals(128_000, StreamPicker.pickAudio(listOf(a(48_000), a(128_000), a(256_000), a(128_000, "webm")))!!.bitrate)
        assertNull(StreamPicker.pickAudio(emptyList()))
    }

    @Test fun settingsStayInRange() {
        val s = Settings(clipCount = 99, minSec = 60, maxSec = 20, language = "xx", model = "huge").sanitized()
        assertEquals(12, s.clipCount); assertEquals("hi", s.language); assertEquals("base", s.model)
        assertTrue(s.minSec < s.maxSec); assertTrue(s.minSec >= 15 && s.maxSec in 20..60)
        assertEquals(3, Settings(clipCount = 0).sanitized().clipCount)
    }
}

class SelectionTest {
    /** 40 sentences of 5 words (2.5 s) with 0.5 s gaps; sentences 20-22 are full of funny words. */
    private fun transcript(): List<Word> {
        val out = ArrayList<Word>()
        var t = 0L
        for (k in 0 until 40) {
            val funny = k in 20..22
            val ws = if (funny) listOf("Haha", "pagal", "yaar", "kya", "baat!") else listOf("Today", "we", "talk", "about", "topic$k.")
            for (w in ws) { out.add(Word(w, t, t + 450)); t += 500 }
            t += 500
        }
        return out
    }

    @Test fun picksNonOverlappingMomentsOfTheRightLength() {
        val words = transcript()
        val total = words.last().t1 + 1000
        val m = MomentSelector(15_000, 25_000, 4).select(words, null, total)
        assertEquals(4, m.size)
        for (x in m) {
            val d = x.endMs - x.startMs
            assertTrue("length ${d}ms", d in 14_000..26_500)
            assertTrue(x.startMs >= 0 && x.endMs <= total)
            assertTrue(x.score in 1..100)
            assertTrue("moment must end on a sentence end", x.words.last().text.let { it.endsWith(".") || it.endsWith("!") || it.endsWith("?") })
        }
        for (i in m.indices) for (j in i + 1 until m.size) {
            assertTrue("moments overlap", m[i].endMs <= m[j].startMs || m[j].endMs <= m[i].startMs)
        }
        // best moment must contain the funny stretch (sentences 20-22 start at 20*3000 ms)
        val best = m.first()
        assertTrue("best moment should hold the funny part, was ${best.startMs}-${best.endMs}", best.startMs <= 62_000 && best.endMs >= 60_000)
        assertTrue(best.funnyHits >= 3)
        assertEquals(m.sortedByDescending { it.score }, m)
    }

    @Test fun tooLittleSpeechGivesNothing() {
        assertTrue(MomentSelector(15_000, 25_000, 3).select(listOf(Word("hi.", 0, 400)), null, 5000).isEmpty())
    }

    @Test fun relaxesWhenVideoIsShort() {
        val words = transcript().filter { it.t1 < 40_000 }
        val m = MomentSelector(30_000, 58_000, 5).select(words, null, 41_000)
        assertTrue("should still produce at least one Short from 40 s of speech", m.isNotEmpty())
    }

    @Test fun titlesAreFilled() {
        val words = transcript()
        val m = MomentSelector(15_000, 25_000, 1).select(words, null, words.last().t1 + 1000).first()
        val t = Titles.make(m, m.words)
        assertTrue(t.title.isNotBlank() && t.title.length < 90)
        assertTrue(t.hashtags.contains("#shorts") && t.hashtags.contains("#hinglish"))
        assertTrue(t.description.isNotBlank())
    }

    @Test fun transcriptJsonRoundTrip() {
        val t = Transcript("hi", listOf(Segment(0, 1000, "नमस्ते दुनिया", listOf(Word("नमस्ते", 0, 400), Word("दुनिया", 450, 900)))))
        val back = Transcript.parse(t.toJson())
        assertEquals(t, back)
        assertNotNull(back.words.firstOrNull())
    }
}
