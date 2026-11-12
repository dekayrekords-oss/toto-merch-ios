package com.meowmba.shared.agent.runtime

/**
 * Spoken forms for phone cleanup. Ported from
 * `core/cleanup/CleanupSpeech.kt`, unchanged in behavior.
 */
object CleanupSpeech {

    data class Parsed(val kind: String, val action: String, val title: String)

    private val CLEAN_CUES = listOf(
        "clean up", "cleanup", "clean my", "clean the", "tidy", "declutter",
        "organize", "organise", "free up space", "free space", "clear out",
        "clear my", "sort my", "sort the",
        "delete old", "remove old", "delete screenshot", "delete screenshots",
    )

    private val CONFIRM = listOf(
        "clean them", "delete them", "tidy them", "do it", "go ahead",
        "yes clean", "yes delete", "yes tidy", "clean those", "delete those",
        "go ahead and clean", "yes do it", "confirm",
    )

    fun isCleanup(raw: String): Boolean {
        val t = raw.lowercase()
        return CLEAN_CUES.any { it in t } ||
            (t.contains("organize") || t.contains("organise")) ||
            t.startsWith("clean ")
    }

    fun isConfirm(raw: String): Boolean {
        val t = raw.lowercase().trim()
        return CONFIRM.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") }
    }

    fun parse(raw: String): Parsed? {
        val t = raw.lowercase().trim()
        if (!isCleanup(t)) return null
        val kind = kindOf(t)
        val action = when {
            t.contains("organize") || t.contains("organise") || t.contains("sort ") -> "organize"
            t.contains("delete") || t.contains("remove") || t.contains("clear out") -> "tidy"
            else -> "scan"
        }
        val label = if (kind == "all") "phone" else kind
        val verb = when (action) {
            "organize" -> "organize"
            "tidy" -> "clean"
            else -> "look through"
        }
        return Parsed(kind = kind, action = action, title = "$verb $label")
    }

    fun kindOf(raw: String): String {
        val t = raw.lowercase()
        return when {
            listOf("screenshot", "photo", "photos", "picture", "pictures", "camera roll", "gallery")
                .any { it in t } -> "photos"
            listOf("video", "videos", "movie", "movies", "clip").any { it in t } -> "videos"
            listOf("music", "song", "songs", "audio", "mp3").any { it in t } -> "music"
            listOf("document", "documents", "pdf", "pdfs", "download", "downloads")
                .any { it in t } -> "documents"
            listOf("message", "messages", "text", "texts", "sms").any { it in t } -> "messages"
            Regex("\\bapps?\\b").containsMatchIn(t) -> "apps"
            listOf("file", "files", "storage").any { it in t } -> "files"
            else -> "all"
        }
    }
}
