package com.meowmba.shared.attach

/**
 * Photos, camera shots and documents handed to Meow (chat "+", Flow "+"), ported from Android
 * `core/attach/Attachments` (build 171). The host reads the file on the phone (iOS: Vision OCR for
 * images, PDFKit for PDFs, plain text files directly) and this turns it into Meow's turn text.
 * Nothing here uploads anything.
 */
object Attachments {
    enum class Kind { PHOTO, IMAGE, FILE }

    data class Read(val name: String, val kind: Kind, val mime: String, val text: String, val tracking: List<Tracking>)

    data class Tracking(val carrier: String, val number: String) {
        val url: String get() = when (carrier) {
            "UPS" -> "https://www.ups.com/track?tracknum=$number"
            "USPS" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$number"
            "FedEx" -> "https://www.fedex.com/fedextrack/?trknbr=$number"
            "DHL" -> "https://www.dhl.com/us-en/home/tracking.html?tracking-id=$number"
            "Amazon" -> "https://www.amazon.com/progress-tracker/package?trackingId=$number"
            else -> "https://www.google.com/search?q=track+package+$number"
        }
    }

    /** Prompt-sized budget for extracted text. */
    const val MAX_TEXT = 6_000

    fun read(name: String, kind: Kind, mime: String, text: String): Read {
        val trimmed = text.trim().take(MAX_TEXT)
        return Read(name, kind, mime, trimmed, findTracking(trimmed))
    }

    /** What Meow is told about the attachment, alongside anything the user typed. */
    fun describe(r: Read, note: String?): String = buildString {
        note?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
        append(when (r.kind) { Kind.PHOTO -> "[I took a photo"; Kind.IMAGE -> "[I shared a picture"; Kind.FILE -> "[I shared a file" })
        append(": ").append(r.name).append("]")
        if (r.tracking.isNotEmpty()) append("\nTracking number: ").append(r.tracking.joinToString { "${it.number} (${it.carrier})" })
        if (r.text.isNotBlank()) append("\nText in it:\n").append(r.text)
        else if (r.kind != Kind.FILE) append("\n(No readable text found in the image.)")
    }

    private val PATTERNS = listOf(
        "UPS" to Regex("\\b1Z[0-9A-Z]{16}\\b"),
        "USPS" to Regex("\\b(9[1-5]\\d{2}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{2}|(?:EA|EC|CP|RA|RR|LZ|LX)\\d{9}US)\\b"),
        "Amazon" to Regex("\\bTBA\\d{12}\\b"),
        "FedEx" to Regex("\\b(\\d{12}|\\d{15}|\\d{20})\\b"),
        "DHL" to Regex("\\b(JD\\d{18}|\\d{10})\\b"),
    )

    /** Tracking numbers in [text], most specific carrier first; each number reported once. */
    fun findTracking(text: String): List<Tracking> {
        val upper = text.uppercase()
        val seen = HashSet<String>()
        val out = ArrayList<Tracking>()
        for ((carrier, re) in PATTERNS) for (m in re.findAll(upper)) {
            val n = m.value.replace(" ", "")
            // A 10–20 digit run inside a longer number (or a phone number with context) is not a tracking number.
            if (carrier == "DHL" && !Regex("DHL|WAYBILL|AIRWAYBILL").containsMatchIn(upper)) continue
            if (carrier == "FedEx" && !Regex("FEDEX|FED EX|TRACKING").containsMatchIn(upper)) continue
            if (seen.none { it.contains(n) || n.contains(it) } && seen.add(n)) out += Tracking(carrier, n)
        }
        return out
    }
}
