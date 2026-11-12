package com.meowmba.shared.voice

/**
 * The 20M zipformer always-on model emits SHOUTING (all caps).
 * Turn that into readable sentence case; leave mixed-case text alone.
 */
object SpokenText {

    /**
     * Drop ASR junk so a stray "e" / "a b" does not become a turn.
     * Always-on AiAi and the 20M zipformer both emit these on silence.
     */
    fun isUsable(raw: String): Boolean {
        if (isNonSpeechAnnotation(raw)) return false
        val t = normalize(raw).trim()
        if (t.isEmpty()) return false
        val letters = t.filter { it.isLetter() }
        if (letters.length < 2) return false
        val compact = t.lowercase().replace(Regex("[^a-z0-9 ]+"), "").trim()
        if (compact in FILLERS || compact in FRAGMENTS) return false
        if (Regex("^[a-z](?: [a-z])+$").matches(compact)) return false
        val words = compact.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.any { it in FRAGMENTS } && words.size <= 2) return false
        if (words.size == 1) {
            val w = words[0]
            if (w.length <= 2 && w !in SHORT_OK) return false
            // Single-word cut-offs: "Thus", "But", "So" — the zipformer catches
            // the connector right before the user pauses, then fires a final.
            // They are never a complete turn (measured on device firing the
            // same greeting repeatedly). SHORT_OK words stay legal ("ok", "no").
            if (w in CUTOFF_MARKERS) return false
        }
        // Stutter repetition marks a tail of room noise / echo, not a real
        // utterance: "and and", "but i i am and and". A short run (<=4 words)
        // with one immediate repeat, or a longer run with two or more, is cut-off
        // garbage — natural "no no no" stays one word outside this rule.
        if (words.size <= 4) {
            for (i in 1 until words.size) {
                if (words[i] == words[i - 1]) return false
            }
        } else if (words.size <= 8) {
            var repeats = 0
            for (i in 1 until words.size) {
                if (words[i] == words[i - 1]) repeats++
            }
            if (repeats >= 2) return false
        }
        // A short string of ONLY connectors/pronouns/quantifiers with no content
        // word carries nothing to answer: "one of the fifteen", "not then i'm
        // just planning". Requires at least one word outside NON_CONTENT.
        if (words.size <= 4 && words.all { it in NON_CONTENT }) return false
        return true
    }

    /**
     * True when [raw] looks like a finished phrase, not a cut-off hypothesis.
     * Always-on used to commit "set a" / "what time" mid-sentence — that is
     * most of the "it heard me but got it wrong" reports.
     */
    fun looksFinished(raw: String): Boolean {
        if (!isUsable(raw)) return false
        val words = normalize(raw).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size == 1) {
            val w = words[0].lowercase().trimEnd('.', ',', '?', '!')
            return w in SHORT_OK || w.length >= 4
        }
        val last = words.last().lowercase().trimEnd('.', ',', '?', '!', ';', ':')
        return last !in UNFINISHED_TAILS
    }

    /**
     * Pick the best n-best hypothesis. Prefer the highest confidence among
     * usable strings; if the engine sent no scores, take the longest usable.
     */
    fun bestTranscript(matches: List<String>?, scores: FloatArray? = null): String {
        if (matches.isNullOrEmpty()) return ""
        data class Cand(val text: String, val score: Float, val usable: Boolean)
        val cands = matches.mapIndexed { i, raw ->
            val text = normalize(raw).trim()
            val score = scores?.getOrNull(i) ?: Float.NaN
            Cand(text, score, isUsable(text))
        }
        val usable = cands.filter { it.usable }
        if (usable.isNotEmpty()) {
            val scored = usable.filter { !it.score.isNaN() }
            if (scored.isNotEmpty()) return scored.maxBy { it.score }.text
            return usable.maxBy { it.text.length }.text
        }
        return cands.firstOrNull { it.text.isNotBlank() }?.text ?: ""
    }

    /**
     * Merge a final result with the last partial. Official wins unless it is
     * empty/junk and the partial is a complete usable phrase (AiAi on this
     * Fold often ends with an empty final).
     */
    fun preferFinal(official: String, partial: String): String {
        if (isNonSpeechAnnotation(official)) return ""
        val o = normalize(official).trim()
        val p = normalize(partial).trim()
        val oOk = isUsable(o)
        val pOk = isUsable(p)
        if (oOk && pOk) {
            return if (p.length >= o.length + 12 && looksFinished(p)) p else o
        }
        if (oOk) return o
        if (pOk) return p
        return o.ifBlank { p }
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val letters = trimmed.filter { it.isLetter() }
        if (letters.isEmpty()) return trimmed
        val upperShare = letters.count { it.isUpperCase() }.toFloat() / letters.length
        if (upperShare < 0.6f) return trimmed
        val lower = trimmed.lowercase()
        return sentenceCase(lower)
    }

    /**
     * Sentence-case plus the 20M zipformer's known greeting mangling.
     * Measured on device: "Yo, what's up buddy" → "Ovites up body" / "Ulat's up buddy".
     */
    fun polish(raw: String): String = repair(normalize(raw))

    /** Only explicit decoder annotations, not speech ABOUT music/noise. */
    fun isNonSpeechAnnotation(raw: String): Boolean {
        val trimmed = raw.trim().trimEnd('.', ',', '!', '?').trim()
        if (trimmed.isEmpty()) return false
        val annotations = NON_SPEECH_ANNOTATION.findAll(trimmed).toList()
        return annotations.isNotEmpty() &&
            NON_SPEECH_ANNOTATION.replace(trimmed, "").trim().isEmpty() &&
            annotations.all { it.groupValues[1].trim().lowercase() in NON_SPEECH_LABELS }
    }

    /** A successful noise/empty final must not resurrect a hallucinated partial.
     * Null is reserved for unavailable/failed refinement, where streaming wins.
     */
    internal fun refinedTranscript(streaming: String, refined: String?): String? = when {
        refined == null -> streaming.takeIf(::isUsable)
        else -> refined.takeIf(::isFinalTranscriptUsable)
    }

    /** Final ASR must preserve numbers, other scripts, and natural repetitions.
     * The streaming-preview heuristics are not acoustic confidence scores. */
    fun isFinalTranscriptUsable(text: String): Boolean =
        text.any { it.isLetterOrDigit() } && !isNonSpeechAnnotation(text)

    private val NON_SPEECH_ANNOTATION = Regex("[\\[(]([^\\[\\]()]+)[\\])]")
    private val NON_SPEECH_LABELS = setOf(
        "buzzing", "buzz", "music", "background music", "silence", "noise",
        "background noise", "static", "humming", "applause", "laughter", "laughing",
        "sigh", "sighing", "breathing", "coughing", "inaudible", "unintelligible",
        "blank_audio", "no speech", "wind", "rustling", "beep", "beeping",
    )

    internal fun repair(sentence: String): String {
        if (sentence.isBlank()) return sentence
        val compact = sentence.lowercase()
            .replace(Regex("[^a-z0-9'\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        var t = compact

        // --- Greetings / common phrases ---
        t = t.replace(
            Regex("""\b(ulat'?s|ulats|oot'?s|oots|ovites|ovite'?s|wot'?s|wats)\s+up\b"""),
            "what's up",
        )
        t = t.replace(Regex("""\bup body\b"""), "up buddy")
        t = t.replace(Regex("""\bbudd\b"""), "buddy")

        // --- YouTube (the 2023 zipformer doesn't know this word) ---
        // "You two" and "you to" are ordinary speech, not evidence of the
        // media app. Rewriting them globally corrupted even correct Whisper
        // finals ("I want you to..." became "I want YouTube..."). Ambiguous
        // app-name recovery belongs after explicit app/media intent, not ASR.
        t = t.replace(
            Regex("""\byou\s*tube\b"""),
            "YouTube",
        )
        t = t.replace(
            Regex("""\byo\s*tube\b"""),
            "YouTube",
        )
        // --- "meow" variants (not in the zipformer's vocabulary) ---
        t = t.replace(
            Regex("""\b(yo|yoh|yoa|yow|yom|yah|ya|ye|hee|hey|hi|oi|oh)\s*(meow|meo|meoo|miao|miau|mow|mo|mao|me ow|my ow|miow|miya|myo|me o|me oo|mem)\b"""),
            "meow",
        )
        t = t.replace(
            Regex("""\b(hey|hi|ok|okay|yo|oi)\s+meo\b"""),
            "hey meow",
        )
        // Bare "meow" the model sometimes emits as fragmented syllables
        t = t.replace(
            Regex("""\bme\s*o\s*w\b"""),
            "meow",
        )
        t = t.replace(
            Regex("""\bmy\s*o\s*w\b"""),
            "meow",
        )

        // --- "Hey Meowmba" / "Yo Meowmba" ---
        t = t.replace(
            Regex("""\b(yo|yoh|yah|ya|hee|hey|hi|oi|oh|ok|okay)\s*(me\s*m\s*ba|mem\s*ba|meo\s*mba|memba|meo\s*ba|mim\s*ba|mimba)\b"""),
            "hey meowmba",
        )

        if (t == compact) return sentence
        return sentenceCase(t)
    }

    private fun sentenceCase(lower: String): String = buildString(lower.length) {
        var capNext = true
        for (c in lower) {
            if (capNext && c.isLetter()) {
                append(c.titlecaseChar())
                capNext = false
            } else {
                append(c)
                if (c == '.' || c == '!' || c == '?') capNext = true
                if (c.isWhitespace() && lastOrNull() in setOf('.', '!', '?')) capNext = true
            }
        }
    }

    private val FILLERS = setOf(
        "uh", "um", "er", "ah", "eh", "mm", "mmm", "hmm", "hm",
        "huh", "oh", "ooh", "the", "a", "an", "and", "to", "of",
    )

    private val SHORT_OK = setOf(
        "ok", "no", "hi", "yo", "go", "up", "on", "off", "yes",
    )

    /** Last words that mean the decoder cut the user off mid-phrase. */
    private val UNFINISHED_TAILS = setOf(
        "a", "an", "the", "to", "of", "and", "or", "my", "your", "for",
        "with", "in", "on", "at", "i", "i'm", "im", "we", "it's", "its",
        "be", "is", "are", "was", "want", "wanna", "gonna", "gotta",
    )

    /**
     * Word-tail scraps AiAi / zipformer emit on room noise.
     * "ular" is the one that kept firing on this Fold.
     */
    private val FRAGMENTS = setOf(
        "ular", "ulas", "ula", "uler", "ulur",
        "tion", "sion", "ation", "ness", "ment", "ible", "able",
        "ally", "ially", "ious", "uous", "izing", "ising", "ated", "ator",
        "ical", "icle", "ency", "ance", "ence", "ough", "ight", "ound",
        "yeah", "yep", "mmm", "uhhuh", "uhuh", "mmhm",
    )

    /**
     * Discourse markers the zipformer catches as a lone word right before the
     * user pauses for a second — "Thus…", "But…", "So…". Alone they are never a
     * complete ask; they used to each trigger a reply (a run of identical
     * greetings on device), so a single one is not a usable turn.
     */
    private val CUTOFF_MARKERS = setOf(
        "thus", "but", "so", "then", "well", "now", "anyway", "anyhow",
        "because", "however", "therefore", "consequently", "meanwhile",
        "besides", "yeah", "hmm", "okay",
    )

    /**
     * Connectors / pronouns / helpers / small numbers that carry no subject or
     * predicate of their own. A short utterance made entirely of these is a
     * garbled fragment, not a question ("one of the fifteen", "but i i am and
     * and") — answerable nothing.
     */
    private val NON_CONTENT = FILLERS + setOf(
        "but", "or", "so", "then", "just", "now", "because", "thus", "however",
        "therefore", "anyway", "well", "i", "i'm", "im", "ive", "ill", "id",
        "you", "you're", "youre", "we", "we're", "were", "we've", "weve",
        "they", "they're", "theyre", "it", "it's", "its", "he", "she",
        "that", "this", "these", "those", "there", "my", "your", "our",
        "their", "am", "is", "are", "was", "been", "be", "not", "another",
        "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
        "eighty", "ninety", "hundred", "planning", "making",
    )
}
