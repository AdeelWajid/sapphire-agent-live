package com.sapphire.agent.live

/**
 * Detects farewell phrases (Allah hafiz, Khuda hafiz, bye, …)
 * matching the web portal / server logic.
 */
object FarewellDetector {
    private val arabicAllah = Regex("""اللہ\s*حافظ""")
    private val arabicKhuda = Regex("""خدا\s*حافظ""")

    fun isFarewell(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        if (arabicAllah.containsMatchIn(raw) || arabicKhuda.containsMatchIn(raw)) return true

        val t = raw
            .lowercase()
            .replace(Regex("""[^a-z0-9\u0600-\u06ff\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (t.isEmpty()) return false

        return Regex("""allah\s*ha+fi[zs]""").containsMatchIn(t) ||
            Regex("""alla+h\s*ha+fi[zs]""").containsMatchIn(t) ||
            Regex("""khuda\s*ha+fi[zs]""").containsMatchIn(t) ||
            Regex("""allahhafiz""").containsMatchIn(t) ||
            Regex("""khudahafiz""").containsMatchIn(t) ||
            Regex("""good\s*bye""").containsMatchIn(t) ||
            Regex("""goodbye""").containsMatchIn(t) ||
            Regex("""bye\s*bye""").containsMatchIn(t) ||
            Regex("""end (the )?call""").containsMatchIn(t) ||
            Regex("""call end""").containsMatchIn(t) ||
            Regex("""call band""").containsMatchIn(t)
    }

    fun isEndCallOffer(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        if (Regex("""کال\s*ختم""").containsMatchIn(raw) || Regex("""کال\s*بند""").containsMatchIn(raw)) {
            return true
        }
        val t = raw
            .lowercase()
            .replace(Regex("""[^a-z0-9\u0600-\u06ff\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return Regex("""end (the )?call""").containsMatchIn(t) ||
            Regex("""call end""").containsMatchIn(t) ||
            Regex("""call band""").containsMatchIn(t) ||
            Regex("""khatam kar""").containsMatchIn(t) ||
            Regex("""band kar""").containsMatchIn(t)
    }

    fun isAffirmative(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        if (Regex("""^(جی|ہاں|هان|درست|ٹھیک|کریں|کر دو|بند)\b""").containsMatchIn(raw)) {
            return true
        }
        val t = raw
            .lowercase()
            .replace(Regex("""[^a-z0-9\u0600-\u06ff\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return Regex("""^(yes|yeah|yep|ok|okay|sure|haan|ji|jee|theek|sahi|bilkul)(\b|$)""")
            .containsMatchIn(t) ||
            Regex("""\b(yes|haan|ji|jee|theek hai|sahi hai|bilkul|end kar do|band kar do|khatam kar do)\b""")
                .containsMatchIn(t)
    }
}
