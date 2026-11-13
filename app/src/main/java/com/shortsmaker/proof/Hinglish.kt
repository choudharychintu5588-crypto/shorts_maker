package com.shortsmaker.proof

/**
 * Turns Hindi written in Devanagari into Roman letters ("Hinglish") the way people type it on a phone.
 * Rule based: a word list for very common words, plus general rules (including dropping the silent "a").
 * It is good, not perfect - the app says so.
 */
object Hinglish {
    private val dict: Map<String, String> = mapOf(
        "क्या" to "kya", "है" to "hai", "हैं" to "hain", "हूँ" to "hoon", "हूं" to "hoon", "हो" to "ho",
        "था" to "tha", "थी" to "thi", "थे" to "the", "नहीं" to "nahi", "नही" to "nahi", "में" to "mein",
        "मैं" to "main", "मैंने" to "maine", "मेरा" to "mera", "मेरी" to "meri", "मेरे" to "mere",
        "तेरा" to "tera", "तेरी" to "teri", "तुम" to "tum", "तू" to "tu", "तुझे" to "tujhe", "तुम्हें" to "tumhe",
        "तुम्हारा" to "tumhara", "आप" to "aap", "आपका" to "aapka", "आपकी" to "aapki", "हम" to "hum",
        "हमारा" to "hamara", "वो" to "woh", "वह" to "woh", "यह" to "yeh", "ये" to "ye", "यहाँ" to "yahan",
        "यहां" to "yahan", "वहाँ" to "wahan", "वहां" to "wahan", "कहाँ" to "kahan", "कहां" to "kahan",
        "कब" to "kab", "कैसे" to "kaise", "कैसा" to "kaisa", "कैसी" to "kaisi", "क्यों" to "kyun",
        "क्योंकि" to "kyunki", "और" to "aur", "या" to "ya", "लेकिन" to "lekin", "पर" to "par", "से" to "se",
        "को" to "ko", "का" to "ka", "की" to "ki", "के" to "ke", "भी" to "bhi", "ही" to "hi", "तो" to "toh",
        "जो" to "jo", "कि" to "ki", "अब" to "ab", "फिर" to "phir", "कुछ" to "kuch", "सब" to "sab",
        "बहुत" to "bahut", "बस" to "bas", "अच्छा" to "accha", "अच्छी" to "acchi", "ठीक" to "theek",
        "सही" to "sahi", "गलत" to "galat", "भाई" to "bhai", "यार" to "yaar", "दोस्त" to "dost", "लोग" to "log",
        "बात" to "baat", "काम" to "kaam", "घर" to "ghar", "पैसा" to "paisa", "पैसे" to "paise", "दिन" to "din",
        "रात" to "raat", "समय" to "samay", "हाँ" to "haan", "हां" to "haan", "ना" to "na", "मत" to "mat",
        "चाहिए" to "chahiye", "चाहता" to "chahta", "चाहती" to "chahti", "करना" to "karna", "करो" to "karo",
        "कर" to "kar", "किया" to "kiya", "करता" to "karta", "करती" to "karti", "करते" to "karte",
        "गया" to "gaya", "गई" to "gayi", "गए" to "gaye", "जाना" to "jaana", "आना" to "aana", "आया" to "aaya",
        "आई" to "aayi", "देखो" to "dekho", "देख" to "dekh", "सुनो" to "suno", "बोलो" to "bolo", "बोल" to "bol",
        "बोला" to "bola", "बताओ" to "batao", "बता" to "bata", "पता" to "pata", "मतलब" to "matlab",
        "सच" to "sach", "मजा" to "maza", "मज़ा" to "maza", "पागल" to "pagal", "अरे" to "arre", "ओए" to "oye",
        "वाह" to "waah", "इस" to "is", "उस" to "us", "इसे" to "ise", "उसे" to "use", "उसका" to "uska",
        "उसकी" to "uski", "अपना" to "apna", "अपनी" to "apni", "एक" to "ek", "दो" to "do", "तीन" to "teen",
        "सा" to "sa", "सी" to "si", "जी" to "ji", "भगवान" to "bhagwan", "लड़का" to "ladka", "लड़की" to "ladki",
        "ओके" to "ok", "वीडियो" to "video", "चैनल" to "channel", "सबस्क्राइब" to "subscribe",
        "लाइक" to "like", "फोन" to "phone", "टाइम" to "time", "दोस्तों" to "doston", "सबको" to "sabko",
    )

    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng", 'च' to "ch", 'छ' to "chh", 'ज' to "j",
        'झ' to "jh", 'ञ' to "ny", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
        'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh",
        'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s",
        'ह' to "h", 'ळ' to "l",
        'क़' to "q", 'ख़' to "kh", 'ग़' to "g", 'ज़' to "z", 'ड़' to "r", 'ढ़' to "rh",
        'फ़' to "f", 'य़' to "y",
    )
    private val nuktaMap = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'य' to "y")

    private val independentVowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri", 'ए' to "e",
        'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'ॅ' to "e", 'े' to "e", 'ै' to "ai", 'ॉ' to "o", 'ो' to "o", 'ौ' to "au",
    )
    private const val VIRAMA = '्'
    private const val NUKTA = '़'
    private val labial = setOf('प', 'फ', 'ब', 'भ', 'म')

    private fun isDeva(c: Char) = c in 'ऀ'..'ॿ'

    private class U(var onset: String, var vowel: String?, var nasal: String = "") {
        var deleted = false
        val cluster: Boolean get() = onsetCount > 1
        var onsetCount = 1
        val hasVowel: Boolean get() = if (vowel == null) !deleted else vowel!!.isNotEmpty()
    }

    fun romanize(text: String): String {
        if (text.none { isDeva(it) }) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isDeva(c) && c != '।' && c != '॥') {
                var j = i
                while (j < text.length && isDeva(text[j]) && text[j] != '।' && text[j] != '॥') j++
                sb.append(word(text.substring(i, j)))
                i = j
            } else {
                when (c) {
                    '।', '॥' -> sb.append('.')
                    in '०'..'९' -> sb.append('0' + (c - '०'))
                    else -> sb.append(c)
                }
                i++
            }
        }
        return sb.toString()
    }

    private fun word(w: String): String {
        dict[w]?.let { return it }
        val units = ArrayList<U>()
        var i = 0
        val n = w.length
        while (i < n) {
            val c = w[i]
            when {
                consonants.containsKey(c) -> {
                    var onset = consonants[c]!!
                    var consCount = 1
                    i++
                    if (i < n && w[i] == NUKTA) { onset = nuktaMap[c] ?: onset; i++ }
                    var vowel: String? = null
                    var closed = false
                    while (i < n) {
                        val d = w[i]
                        if (d == VIRAMA) {
                            i++
                            if (i < n && consonants.containsKey(w[i])) {
                                var nx = consonants[w[i]]!!
                                val cc = w[i]
                                i++
                                if (i < n && w[i] == NUKTA) { nx = nuktaMap[cc] ?: nx; i++ }
                                onset += nx
                                consCount++
                                continue
                            }
                            closed = true   // halant at the end: no vowel at all
                            break
                        }
                        if (matras.containsKey(d)) { vowel = matras[d]; i++ }
                        break
                    }
                    val u = U(onset, if (closed) "" else vowel)
                    u.onsetCount = consCount
                    units.add(u)
                }
                independentVowels.containsKey(c) -> { units.add(U("", independentVowels[c])); i++ }
                c == 'ं' || c == 'ँ' -> {
                    val last = units.lastOrNull()
                    if (last != null) {
                        val next = w.getOrNull(i + 1)
                        last.nasal = if (next != null && next in labial) "m" else "n"
                    }
                    i++
                }
                c == 'ः' -> { units.lastOrNull()?.let { it.nasal = "h" }; i++ }
                else -> i++
            }
        }
        if (units.isEmpty()) return ""

        // Long vowels at the end of a word are written short (kya, mera, paani).
        if (units.size > 1) {
            val last = units.last()
            last.vowel = when (last.vowel) { "aa" -> "a"; "ee" -> "i"; "oo" -> "u"; else -> last.vowel }
        }
        // Silent "a" (schwa) rules.
        if (units.size > 1) {
            val last = units.last()
            if (last.vowel == null) last.deleted = true
            for (k in units.size - 2 downTo 1) {
                val u = units[k]
                if (u.vowel == null && !u.cluster && !units[k + 1].cluster && units[k - 1].hasVowel && units[k + 1].hasVowel) u.deleted = true
            }
        }
        val out = StringBuilder()
        for (u in units) {
            out.append(u.onset)
            if (u.vowel != null) out.append(u.vowel) else if (!u.deleted) out.append('a')
            out.append(u.nasal)
        }
        return out.toString().replace("aai", "ai")
    }
}
