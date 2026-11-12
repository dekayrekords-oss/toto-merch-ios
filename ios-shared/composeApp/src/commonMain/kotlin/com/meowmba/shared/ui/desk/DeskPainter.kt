package com.meowmba.shared.ui.desk

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Port of docs/design/pro-desk-avatar.html's SVG. All functions draw in scene units
 * (412 × 960 viewBox); the caller scales the DrawScope. Three layers:
 * [drawRoom] (static, cached), [drawObjects] (redrawn when DeskState changes),
 * and [drawCat] (animated only while walking/blinking).
 */
internal object DeskPainter {
    val INK = Color(0xFF172D34)
    internal val PAPER = Color(0xFFF4EEDB)
    private const val EXTEND = 2400f

    /** Handwriting on in-world paper. Patrick Hand (OFL) is not bundled yet; system cursive stands in. */
    /** Patrick Hand; set from compose resources by the Desk screen. */
    var hand: FontFamily = FontFamily.Serif
    var mono: FontFamily = FontFamily.Monospace

    internal fun DrawScope.stroked(color: Color, w: Float) = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)

    internal fun DrawScope.rrect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 0f, stroke: Float = 0f, fill: Brush? = null) {
        if (fill != null) drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(r)) else drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r))
        if (stroke > 0f) drawRoundRect(INK, Offset(x, y), Size(w, h), CornerRadius(r), style = Stroke(stroke))
    }

    internal fun DrawScope.line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float) =
        drawLine(color, Offset(x1, y1), Offset(x2, y2), w, StrokeCap.Round)

    private fun DrawScope.width(m: TextMeasurer, s: String, size: Float): Float =
        m.measure(s, TextStyle(fontFamily = hand, fontSize = (size / (density * fontScale)).sp)).size.width.toFloat()

    /** One line that fits [max]: the largest size down to [minSize], then an ellipsis. */
    internal fun DrawScope.fitted(m: TextMeasurer, s: String, max: Float, size: Float, minSize: Float): Pair<String, Float> {
        var sz = size
        while (sz > minSize && width(m, s, sz) > max) sz -= 1f
        if (width(m, s, sz) <= max) return s to sz
        var cut = s
        while (cut.length > 1 && width(m, "$cut…", sz) > max) cut = cut.dropLast(1)
        return "${cut.trimEnd()}…" to sz
    }

    /** Word-wrapped lines that fit [max], at most [maxLines]; the last one ellipsised if needed. */
    internal fun DrawScope.wrapped(m: TextMeasurer, s: String, max: Float, size: Float, maxLines: Int): List<String> {
        val lines = ArrayList<String>(); var cur = ""
        for (w in s.split(' ').filter { it.isNotBlank() }) {
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && width(m, next, size) > max) { lines += cur; cur = w } else cur = next
        }
        if (cur.isNotEmpty()) lines += cur
        if (lines.size <= maxLines) return lines.map { fitted(m, it, max, size, size).first }
        return lines.take(maxLines - 1) + fitted(m, lines.drop(maxLines - 1).joinToString(" ") + "…", max, size, size).first
    }

    /**
     * [size] is in scene units, like the SVG's font-size. The DrawScope is already scaled to the
     * scene, so convert back through density/fontScale to avoid scaling text twice.
     */
    internal fun DrawScope.text(m: TextMeasurer, s: String, x: Float, baseline: Float, size: Float, color: Color,
                               family: FontFamily = hand, weight: FontWeight? = null, center: Boolean = false) {
        val sp = (size / (density * fontScale)).sp
        val layout = m.measure(s, TextStyle(fontFamily = family, fontSize = sp, color = color, fontWeight = weight))
        val left = if (center) x - layout.size.width / 2f else x
        drawText(layout, topLeft = Offset(left, baseline - layout.firstBaseline))
    }

    // ---- static room --------------------------------------------------------------------------

    fun DrawScope.drawRoom() {
        // Walls and floors of all three rooms (and their extension past the edges), then the office.
        with(DeskApartment) { drawShell() }
        // Wall clock face
        drawCircle(PAPER, 36f, Offset(72f, 112f)); drawCircle(INK, 36f, Offset(72f, 112f), style = Stroke(3f))
        drawCircle(Color(0xFFD5CDB4), 30f, Offset(72f, 112f), style = Stroke(1.5f))
        line(INK, 72f, 84f, 72f, 88f, 2.5f); line(INK, 72f, 136f, 72f, 140f, 2.5f); line(INK, 44f, 112f, 48f, 112f, 2.5f); line(INK, 96f, 112f, 100f, 112f, 2.5f)
        // Curtains on the office window (the sky/moon/text are dynamic)
        line(INK, 224f, 58f, 400f, 58f, 3.5f)
        for (cx in listOf(228f, 382f)) {
            val c = Path().apply { moveTo(cx, 58f); lineTo(cx + 18f, 58f); quadraticTo(cx + 12f, 150f, cx + 16f, 238f); lineTo(cx + 2f, 238f); quadraticTo(cx - 2f, 150f, cx, 58f); close() }
            drawPath(c, Color(0xFF5E7A76)); drawPath(c, INK, style = Stroke(2f, join = StrokeJoin.Round))
        }
        // Corkboard frame
        rrect(Color(0xFFB98A5C), 22f, 178f, 190f, 170f, 6f, 3f)
        rrect(Color(0xFFC99A68), 30f, 186f, 174f, 154f, 3f)
        listOf(Triple(52f, 206f, 1.5f), Triple(170f, 318f, 1.5f), Triple(96f, 326f, 1.3f), Triple(188f, 214f, 1.3f))
            .forEach { (cx, cy, r) -> drawCircle(Color(0xFF9C7249), r, Offset(cx, cy)) }
        // The computer: a beige CRT sitting on an old Gateway 2000 desktop case
        rrect(Color(0xFFD8CDB2), 20f, 556f, 180f, 42f, 4f, 3f)
        rrect(Color(0xFF2B3A3F), 118f, 564f, 70f, 9f, 2f, 1.5f); rrect(Color(0xFF2B3A3F), 118f, 580f, 70f, 9f, 2f, 1.5f)
        line(Color(0xFF8D8472), 140f, 568.5f, 166f, 568.5f, 1.5f)
        rrect(Color(0xFFBFB49A), 30f, 575f, 13f, 13f, 3f, 1.5f)
        drawCircle(Color(0xFF7BE06A), 2.2f, Offset(52f, 582f))
        // Gateway's cow-spot box on the case front
        rrect(Color(0xFFFBF8F0), 62f, 565f, 44f, 24f, 2f, 1.5f)
        listOf(Triple(70f, 571f, 3.2f), Triple(83f, 580f, 4f), Triple(97f, 570f, 2.6f), Triple(100f, 583f, 2.2f))
            .forEach { (cx, cy, r) -> drawCircle(Color(0xFF1C1B19), r, Offset(cx, cy)) }
        rrect(Color(0xFFBFB49A), 76f, 550f, 68f, 8f, 2f, 2f)                    // monitor foot
        rrect(Color(0xFFCFC4A8), 24f, 410f, 172f, 142f, 10f, 3f)                // CRT bezel
        rrect(Color(0xFF0C1A1F), 36f, 422f, 148f, 112f, 8f, 2f)                 // screen
        drawCircle(Color(0xFF7BE06A), 2f, Offset(180f, 543f))
        line(Color(0xFFB5AA8E), 40f, 543f, 120f, 543f, 2f)
        // Desk
        drawRect(Brush.verticalGradient(listOf(Color(0xFFC98E52), Color(0xFFB27A42)), 598f, 624f), Offset(0f, 598f), Size(412f, 26f))
        drawRect(INK, Offset(0f, 598f), Size(412f, 26f), style = Stroke(3f))
        rrect(Color(0xFF8E5E33), 14f, 624f, 384f, 110f, 0f, 3f)
        rrect(Color(0xFFA26C3C), 30f, 640f, 150f, 40f, 4f, 2.5f)
        rrect(Color(0xFFA26C3C), 30f, 688f, 150f, 34f, 4f, 2.5f)
        rrect(INK, 92f, 656f, 26f, 6f, 3f); rrect(INK, 92f, 702f, 26f, 6f, 3f)
        // The right half gets a cabinet door, so the desk is balanced instead of a bare panel.
        rrect(Color(0xFFA26C3C), 232f, 640f, 150f, 82f, 4f, 2.5f)
        rrect(Color(0xFF96633A), 244f, 652f, 126f, 58f, 3f, 1.5f)
        drawCircle(Color(0xFFE0B66A), 4f, Offset(252f, 681f)); drawCircle(INK, 4f, Offset(252f, 681f), style = Stroke(1.5f))
        line(Color(0xFFA8743F), 40f, 606f, 100f, 606f, 2f); line(Color(0xFFA8743F), 150f, 612f, 240f, 612f, 2f); line(Color(0xFFA8743F), 300f, 606f, 370f, 606f, 2f)
        // Jar body + lid (coins/label are dynamic)
        rrect(Color(0x599FC7C4), 286f, 548f, 44f, 52f, 10f, 2.5f)
        rrect(Color(0xFF6E8C8A), 284f, 540f, 48f, 10f, 3f, 2.5f)
        // (Letter tray is drawn in drawObjects, on top of its envelopes.)
        // Wall lamp: a swing arm from a bracket under the window, the shade over the desk.
        rrect(Color(0xFF5E7A76), 374f, 318f, 16f, 26f, 3f, 2f)
        val arm = Path().apply { moveTo(382f, 331f); lineTo(360f, 384f); lineTo(334f, 436f) }
        drawPath(arm, INK, style = stroked(INK, 7f)); drawPath(arm, Color(0xFF5E7A76), style = stroked(INK, 3.5f))
        drawCircle(Color(0xFF5E7A76), 4f, Offset(360f, 384f)); drawCircle(INK, 4f, Offset(360f, 384f), style = Stroke(2f))
        val shade = Path().apply { moveTo(302f, 468f); quadraticTo(318f, 430f, 354f, 440f); lineTo(352f, 450f); lineTo(310f, 472f); close() }
        drawPath(shade, Color(0xFFC6EB78)); drawPath(shade, INK, style = Stroke(2.5f, join = StrokeJoin.Round))
        drawOval(Color(0xFFFFF3C4), Offset(318f, 458f), Size(18f, 8f)); drawOval(INK, Offset(318f, 458f), Size(18f, 8f), style = Stroke(1.5f))
        // The other two rooms' furniture
        with(DeskApartment) { drawLivingRoom(); drawBedroom() }
    }

    // ---- data-driven objects -------------------------------------------------------------------

    fun DrawScope.drawObjects(s: DeskState, m: TextMeasurer) {
        // Lamp glow (idea queued) — behind everything else on the desk
        if (s.lampOn) {
            val glow = Path().apply { moveTo(306f, 470f); lineTo(214f, 598f); lineTo(360f, 598f); lineTo(346f, 452f); close() }
            drawPath(glow, Brush.radialGradient(listOf(Color(0x73FFD98A), Color(0x00FFD98A)), Offset(326f, 464f), 150f))
        }
        // Window: sky, moon/sun, hills, frame, sill, handwritten weather
        drawRoundRect(Brush.verticalGradient(
            if (s.night) listOf(Color(0xFF0D2130), Color(0xFF2A4E61)) else listOf(Color(0xFF6FA7C4), Color(0xFFBFDCE6)), 66f, 242f),
            Offset(238f, 66f), Size(148f, 176f), CornerRadius(6f))
        if (s.night) {
            // One crescent moon: the full disc minus an offset one, outlined as a single shape.
            val moon = Path().apply {
                op(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(344f, 104f), 15f)) },
                    Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(352f, 98f), 13f)) },
                    androidx.compose.ui.graphics.PathOperation.Difference)
            }
            drawPath(moon, Color(0xFFE9EFD9)); drawPath(moon, INK, style = Stroke(2f, join = StrokeJoin.Round))
            listOf(Triple(268f, 96f, 1.6f), Triple(300f, 120f, 1.2f), Triple(276f, 150f, 1.4f), Triple(364f, 160f, 1.2f))
                .forEach { (cx, cy, r) -> drawCircle(Color(0xFFE9EFD9), r, Offset(cx, cy)) }
        } else {
            drawCircle(Color(0xFFFFE27A), 14f, Offset(344f, 104f)); drawCircle(INK, 14f, Offset(344f, 104f), style = Stroke(2f))
        }
        val hills = Path().apply {
            moveTo(246f, 214f); relativeQuadraticTo(18f, -26f, 44f, -8f); relativeQuadraticTo(14f, -16f, 34f, -4f)
            relativeQuadraticTo(22f, -10f, 36f, 8f); relativeLineTo(0f, 12f); relativeLineTo(-114f, 0f); close()
        }
        drawPath(hills, if (s.night) Color(0xFF16303E) else Color(0xFF7FA58A))
        drawRoundRect(INK, Offset(238f, 66f), Size(148f, 176f), CornerRadius(6f), style = Stroke(3f))
        line(INK, 312f, 66f, 312f, 242f, 3f); line(INK, 238f, 154f, 386f, 154f, 3f)
        rrect(Color(0xFF8C6A48), 230f, 240f, 164f, 12f, 3f, 2.5f)
        s.weatherLine?.let { text(m, it, 312f, 272f, 17f, Color(0xFFBED0CD), center = true) }

        // Clock hands
        val hourA = ((((s.clockHour % 12) * kotlin.math.PI / 180.0) * 30 + s.clockMinute * 0.5) - 90.0)
        val minA = ((s.clockMinute * 6.0 - 90.0) * kotlin.math.PI / 180.0)
        line(INK, 72f, 112f, 72f + 18f * cos(hourA).toFloat(), 112f + 18f * sin(hourA).toFloat(), 3.5f)
        line(INK, 72f, 112f, 72f + 24f * cos(minA).toFloat(), 112f + 24f * sin(minA).toFloat(), 2.5f)
        drawCircle(Color(0xFFFF7B72), 3f, Offset(72f, 112f)); drawCircle(INK, 3f, Offset(72f, 112f), style = Stroke(1.5f))

        // Corkboard index card: project + tester-day tally
        run {
            rotate(-2.5f, Offset(104f, 250f)) {
                rrect(PAPER, 40f, 200f, 132f, 92f, 3f, 2.5f)
                line(Color(0xFFE08C84), 40f, 222f, 172f, 222f, 1.2f)
                // The card is 115 units wide inside: long goals shrink a little, then wrap onto the card, never past its edge.
                val title = fitted(m, s.projectTitle.ifBlank { "Your projects" }, 115f, 17f, 13f)
                text(m, title.first, 50f, 217f, title.second, INK)
                if (s.tallyTotal > 0) {
                    text(m, "tester days", 50f, 242f, 15f, Color(0xFF3A4F55))
                    repeat(s.tallyTotal) { i ->
                        val x = 50f + i * 8.6f
                        if (i < s.tallyDone) drawRoundRect(Color(0xFFC6EB78), Offset(x, 250f), Size(6f, 16f), CornerRadius(1.5f))
                        drawRoundRect(INK, Offset(x, 250f), Size(6f, 16f), CornerRadius(1.5f), style = Stroke(1.6f))
                    }
                }
                // Without a tally the status sits under the title instead of leaving a gap.
                if (s.projectStatus.isNotBlank()) {
                    val lines = wrapped(m, s.projectStatus, 115f, 13f, if (s.tallyTotal > 0) 1 else 3)
                    lines.forEachIndexed { i, l -> text(m, l, 50f, (if (s.tallyTotal > 0) 284f else 242f) + i * 16f, 13f, Color(0xFF3A4F55)) }
                }
                else if (s.projectTitle.isBlank()) text(m, "Tap to open", 50f, 251f, 15f, Color(0xFF3A4F55))
                drawCircle(Color(0xFFFF7B72), 5f, Offset(106f, 200f)); drawCircle(INK, 5f, Offset(106f, 200f), style = Stroke(2f))
            }
        }
        if (s.pinnedNote.isNotEmpty()) rotate(4f, Offset(170f, 318f)) {
            rrect(Color(0xFFC7F4ED), 138f, 296f, 62f, 40f, 2f, 2f)
            s.pinnedNote.forEachIndexed { i, l -> text(m, l, 146f, 314f + i * 15f, 13f, INK) }
            drawCircle(Color(0xFFC6EB78), 4f, Offset(169f, 296f)); drawCircle(INK, 4f, Offset(169f, 296f), style = Stroke(1.8f))
        }

        // Monitor text: the screen is bigger now, so nothing covers it (static cursor: no blink loop, for heat)
        s.monitorLines.forEachIndexed { i, l ->
            text(m, l.text.take(22), 44f, 444f + i * 18f, 11f, if (l.highlight) Color(0xFFC6EB78) else Color(0xFF8FB0AC), family = mono)
        }
        drawRect(Color(0xFFC6EB78), Offset(44f, 444f + s.monitorLines.size * 18f - 8f), Size(7f, 11f))
        text(m, "GATEWAY 2000", 150f, 596f, 6.5f, Color(0xFF6B6250), family = FontFamily.SansSerif, weight = FontWeight.Bold, center = true)

        // Sticky note: next calendar item, stuck to the wall beside the monitor (it no longer hides the screen)
        rotate(5f, Offset(234f, 446f)) {
            drawRect(Color(0xFFFFE27A), Offset(206f, 418f), Size(58f, 56f)); drawRect(INK, Offset(206f, 418f), Size(58f, 56f), style = Stroke(2.5f))
            s.sticky.ifEmpty { listOf("Next up", "Tap for tasks") }.forEachIndexed { i, l -> text(m, l.take(9), 211f, 437f + i * 15f, if (i == 0) 15f else 12.5f, INK) }
        }

        // Jar: coins + handwritten note (empty jar when nothing is due)
        s.jarNote?.let { note ->
            listOf(Offset(302f, 592f), Offset(314f, 588f), Offset(306f, 584f)).forEach { c ->
                drawOval(Color(0xFFFFC56F), Offset(c.x - 9f, c.y - 4f), Size(18f, 8f))
                drawOval(INK, Offset(c.x - 9f, c.y - 4f), Size(18f, 8f), style = Stroke(1.8f))
            }
            // The note is a label stuck on the jar itself, not a card floating beside it.
            rrect(PAPER, 289f, 558f, 38f, 20f, 2f, 1.8f)
            text(m, note.take(7), 308f, 573f, 12f, Color(0xFFB3261E), center = true)
        }

        // Letter tray: envelopes pile up with PEOPLE items; badge shows the count
        val envelopes = s.trayCount.coerceAtMost(3)
        if (envelopes >= 2) rotate(-10f, Offset(360f, 560f)) { envelope(344f, 532f, PAPER) }
        if (envelopes >= 3) rotate(6f, Offset(368f, 560f)) { envelope(350f, 538f, Color(0xFFFFE2B5)) }
        if (envelopes >= 1) rrect(Color(0xFFC7F4ED), 340f, 552f, 62f, 18f, 2f, 2f)
        val tray = Path().apply { moveTo(336f, 570f); lineTo(406f, 570f); lineTo(400f, 600f); lineTo(342f, 600f); close() }
        drawPath(tray, Color(0xFF5E7A76)); drawPath(tray, INK, style = Stroke(2.5f, join = StrokeJoin.Round))
        if (s.trayCount > 0) {
            drawCircle(Color(0xFFF2B8B5), 11f, Offset(398f, 534f)); drawCircle(INK, 11f, Offset(398f, 534f), style = Stroke(2f))
            text(m, if (s.trayCount > 9) "9+" else s.trayCount.toString(), 398f, 539f, 13f, Color(0xFF601410),
                family = FontFamily.SansSerif, weight = FontWeight.Black, center = true)
        }
        with(DeskApartment) { drawLivingRoomObjects(s, m); drawBedroomObjects(s, m) }
    }

    private fun DrawScope.envelope(x: Float, y: Float, fill: Color) {
        rrect(fill, x, y, 42f, 30f, 2f, 2f)
        val flap = Path().apply { moveTo(x, y); relativeLineTo(21f, 15f); relativeLineTo(21f, -15f) }
        drawPath(flap, INK, style = Stroke(1.8f))
    }

    // ---- micro cat (viewBox 60 × 50, drawn at 110 × 92 scene units) -------------------------------

    private val CAT_BODY = Color(0xFF0E1D23)
    private val CAT_LINE = Color(0xFF4F7079)
    private val CAT_PALE = Color(0xFFBED0CD)
    private val PINK = Color(0xFFF2B8C8)

    /**
     * [walkFrame] 0/1 swings the legs; [blink] 0 = open … 1 = shut; [tail] degrees of sway;
     * [gaze] −1 … 1 shifts the irises toward a tapped object; [facingLeft] mirrors the cat;
     * [face] is the expression, independent of the [pose].
     */
    fun DrawScope.drawCat(x: Float, y: Float, walking: Boolean, walkFrame: Int, blink: Float, tail: Float, gaze: Float, facingLeft: Boolean,
                          pose: DeskCatPose = DeskCatPose.REST, phase: Float = 0f, face: DeskCatFace = DeskCatBehavior.defaultFace(pose)) {
        val k = 110f / 60f
        // Stable support keeps the writing cat grounded while it reaches the board.
        if (pose == DeskCatPose.WRITE) {
            line(Color(0xFF8E5E33), x + 31f, y + 78f, x + 31f, 610f, 3f)
            line(Color(0xFF8E5E33), x + 55f, y + 78f, x + 55f, 610f, 3f)
            line(Color(0xFFC98E52), x + 25f, y + 86f, x + 61f, y + 86f, 4f)
            for (rung in 0..6) line(Color(0xFFC98E52), x + 29f, y + 110f + rung * 27f, x + 57f, y + 110f + rung * 27f, 4f)
            // A temporary blank note, below the real project card; no invented goal text.
            rrect(PAPER, x + 1f, y + 1f, 37f, 42f, 2f, 1f)
            drawCircle(Color(0xFFE08C84), 2.4f, Offset(x + 19f, y + 4f))
            val inkLength = 9f + (sin(phase) + 1f) * 7f
            for (row in 0..2) line(Color(0xFF527482), x + 7f, y + 14f + row * 8f, x + 7f + inkLength, y + 14f + row * 8f, 1.1f)
        }
        if (pose == DeskCatPose.TYPE) {
            rrect(Color(0xFF527482), x - 42f, y + 75f, 80f, 16f, 3f, 1.5f)
            for (row in 0..1) for (i in 0..8) rrect(if (i == (kotlin.math.abs(sin(phase)) * 8).toInt()) Color(0xFFC6EB78) else PAPER,
                x - 37f + i * 8f, y + 78f + row * 6f, 5f, 3f, 1f)
        }
        withTransform({
            translate(x, y)
            scale(k, k, Offset.Zero)
            if (facingLeft) scale(-1f, 1f, Offset(30f, 25f))
        }) {
            val pulse = sin(phase)
            if (pose == DeskCatPose.SLEEP) { curledCat(face, phase, tail); return@withTransform }
            drawOval(Color(0x73050D10), Offset(13f, 44.4f), Size(34f, 5.2f))
            // Up on the hind legs against the post: the whole cat leans back from its hind paws.
            withTransform({ if (pose == DeskCatPose.SCRATCH) rotate(-38f + pulse * 3f, Offset(22f, 45f)) }) {
                val sitting = pose in SITTING
                if (pose == DeskCatPose.PERCH || sitting && pose != DeskCatPose.EAT) {
                    // A sitting cat's tail curls along the floor or hangs off the perch.
                    val t = if (pose == DeskCatPose.PERCH) Path().apply { moveTo(16f, 38f); cubicTo(10f, 44f, 10f, 54f, 14f, 60f + pulse) }
                            else Path().apply { moveTo(16f, 40f); cubicTo(8f, 44f, 12f, 47f, 24f, 46.5f) }
                    drawPath(t, CAT_LINE, style = Stroke(5.6f, cap = StrokeCap.Round)); drawPath(t, CAT_BODY, style = Stroke(3.8f, cap = StrokeCap.Round))
                } else rotate(tail, Offset(16f, 31f)) {
                    val t = Path().apply { moveTo(16f, 31f); cubicTo(7.5f, 30f, 4.5f, 20.5f, 9.5f, 13f) }
                    drawPath(t, CAT_LINE, style = Stroke(5.6f, cap = StrokeCap.Round)); drawPath(t, CAT_BODY, style = Stroke(3.8f, cap = StrokeCap.Round))
                }
                val swing = if (walking) (if (walkFrame == 1) 22f else -22f) else 0f
                if (sitting) {
                    // Haunches instead of hind legs.
                    drawOval(CAT_BODY, Offset(14f, 33f), Size(20f, 13f)); drawOval(CAT_LINE, Offset(14f, 33f), Size(20f, 13f), style = Stroke(1.3f))
                } else { leg(20.5f, 36f, 44.5f, swing); leg(25.5f, 37f, 45f, -swing) }
                val bob = when {
                    walking && walkFrame == 1 -> -1.2f
                    pose == DeskCatPose.SNIFF -> pulse * 1.5f
                    pose == DeskCatPose.BOB -> -kotlin.math.abs(pulse) * 2.2f
                    face == DeskCatFace.PURR -> pulse * .35f
                    else -> 0f
                }
                withTransform({
                    translate(0f, bob)
                    if (pose == DeskCatPose.STRETCH) scale(1f + .12f * (pulse + 1f), .92f - .05f * pulse, Offset(20f, 44f))
                }) {
                    if (sitting) {
                        // Upright chest for sitting poses.
                        drawOval(CAT_BODY, Offset(22f, 24f), Size(18f, 20f)); drawOval(CAT_LINE, Offset(22f, 24f), Size(18f, 20f), style = Stroke(1.3f))
                    } else {
                        drawOval(CAT_BODY, Offset(14.8f, 24.2f), Size(26.4f, 17.6f)); drawOval(CAT_LINE, Offset(14.8f, 24.2f), Size(26.4f, 17.6f), style = Stroke(1.3f))
                    }
                    val bothPawsBusy = pose in BUSY_PAWS
                    if (!bothPawsBusy) leg(33.5f, 37.5f, 45f, -swing)
                    if (!bothPawsBusy && pose != DeskCatPose.GROOM && pose != DeskCatPose.WRITE) leg(38.5f, 36.5f, 44.6f, swing)
                    // Eating lowers the head into the bowl; watching tips it up at the TV; curious tilts it.
                    withTransform({
                        when {
                            pose == DeskCatPose.EAT -> { translate(3f, 9f); rotate(22f, Offset(40.5f, 21.5f)) }
                            pose == DeskCatPose.WATCH || pose == DeskCatPose.PERCH && face != DeskCatFace.HAPPY -> rotate(-10f, Offset(40.5f, 30f))
                            face == DeskCatFace.CURIOUS -> rotate(11f + pulse * 1.5f, Offset(40.5f, 30f))
                            face == DeskCatFace.SLEEPY -> translate(0f, 1.2f)
                        }
                    }) { head(face, blink, gaze, pulse, phase) }
                    poseExtras(pose, pulse, phase)
                }
            }
            faceExtras(face, phase)
        }
    }

    private val SITTING = setOf(DeskCatPose.PERCH, DeskCatPose.WATCH, DeskCatPose.GAME, DeskCatPose.BOB, DeskCatPose.READ, DeskCatPose.LISTEN, DeskCatPose.TALK)
    private val BUSY_PAWS = setOf(DeskCatPose.TYPE, DeskCatPose.LETTER, DeskCatPose.STRETCH, DeskCatPose.GAME, DeskCatPose.READ, DeskCatPose.SCRATCH, DeskCatPose.DIG)

    /** Ears, head, eyes and mouth for one expression. */
    private fun DrawScope.head(face: DeskCatFace, blink: Float, gaze: Float, pulse: Float, phase: Float) {
        // Ears: flattened when annoyed, pricked when surprised, soft when sleepy.
        val earTurn = when (face) { DeskCatFace.ANNOYED -> 28f; DeskCatFace.SLEEPY, DeskCatFace.ASLEEP -> 12f; DeskCatFace.SURPRISED -> -6f; else -> 0f }
        val lift = if (face == DeskCatFace.SURPRISED) -1.4f else 0f
        withTransform({ translate(0f, lift); rotate(-earTurn, Offset(35f, 12.5f)) }) {
            ear(31.6f, 13.6f, 33.3f, 3.6f, 38.8f, 10.3f); tri(Color(0xFF2C4A52), 33.5f, 11.4f, 34.4f, 6.4f, 37.1f, 9.9f)
        }
        // Curious: one ear swivels on its own.
        val rightTurn = earTurn + if (face == DeskCatFace.CURIOUS) -8f + pulse * 4f else 0f
        withTransform({ translate(0f, lift); rotate(rightTurn, Offset(46f, 12f)) }) {
            ear(42.2f, 10f, 47.9f, 4f, 49.4f, 14.2f); tri(Color(0xFF2C4A52), 43.8f, 10.2f, 47.1f, 6.8f, 47.8f, 12.1f)
        }
        drawCircle(CAT_BODY, 12.8f, Offset(40.5f, 21.5f)); drawCircle(CAT_LINE, 12.8f, Offset(40.5f, 21.5f), style = Stroke(1.3f))
        val blush = if (face == DeskCatFace.LOVE || face == DeskCatFace.PURR || face == DeskCatFace.HAPPY) 0x99 else 0x4D
        drawOval(PINK.copy(alpha = blush / 255f), Offset(31.4f, 26.1f), Size(4f, 2.2f)); drawOval(PINK.copy(alpha = blush / 255f), Offset(45.6f, 26.1f), Size(4f, 2.2f))
        when (face) {
            DeskCatFace.HAPPY, DeskCatFace.PURR, DeskCatFace.LOVE -> { arcEye(35.6f, up = true); arcEye(45.4f, up = true) }
            DeskCatFace.ASLEEP, DeskCatFace.YAWN -> { arcEye(35.6f, up = false); arcEye(45.4f, up = false) }
            else -> {
                val lid = when (face) { DeskCatFace.SLEEPY -> .62f; DeskCatFace.FOCUSED -> .38f; DeskCatFace.ANNOYED -> .5f; DeskCatFace.CHEW -> .3f; else -> 0f }
                val pupil = when (face) { DeskCatFace.SURPRISED -> 1.1f; DeskCatFace.PLAYFUL -> 3.3f; DeskCatFace.CURIOUS -> 2.7f; DeskCatFace.FOCUSED -> 1.6f; else -> 2.05f }
                val big = if (face == DeskCatFace.SURPRISED) 1.12f else 1f
                withTransform({ scale(big, big * (1f - 0.9f * blink.coerceIn(0f, 1f)), Offset(40.5f, 21.8f)) }) {
                    eye(35.6f, gaze, pupil, lid, face == DeskCatFace.ANNOYED); eye(45.4f, gaze, pupil, lid, face == DeskCatFace.ANNOYED)
                }
            }
        }
        tri(PINK, 39.5f, 27.6f, 41.5f, 27.6f, 40.5f, 28.9f)
        mouth(face, pulse, phase)
        val w = Color(0xB3BED0CD)
        line(w, 35f, 28.2f, 28.4f, 27.4f, 0.55f); line(w, 35.2f, 29.3f, 28.9f, 30.4f, 0.55f)
        line(w, 46f, 28.2f, 52.6f, 27.4f, 0.55f); line(w, 45.8f, 29.3f, 52.1f, 30.4f, 0.55f)
    }

    private fun DrawScope.arcEye(cx: Float, up: Boolean) {
        val p = Path().apply {
            moveTo(cx - 4f, if (up) 23f else 21f)
            quadraticTo(cx, if (up) 17.6f else 25f, cx + 4f, if (up) 23f else 21f)
        }
        drawPath(p, CAT_PALE, style = Stroke(1.5f, cap = StrokeCap.Round))
    }

    private fun DrawScope.mouth(face: DeskCatFace, pulse: Float, phase: Float) {
        when (face) {
            DeskCatFace.SURPRISED -> { drawOval(Color(0xFF3A1F26), Offset(39.4f, 29f), Size(2.2f, 2.8f)); drawOval(CAT_PALE, Offset(39.4f, 29f), Size(2.2f, 2.8f), style = Stroke(0.5f)) }
            DeskCatFace.YAWN -> {
                val open = 3.2f + kotlin.math.abs(pulse) * 2f
                drawOval(Color(0xFF3A1F26), Offset(38.2f, 28.7f), Size(4.6f, open)); drawOval(PINK, Offset(39f, 28.7f + open * .55f), Size(3f, open * .4f))
            }
            DeskCatFace.CHEW -> drawOval(Color(0xFF3A1F26), Offset(39.2f, 29.2f), Size(2.6f, .6f + kotlin.math.abs(sin(phase * 2f)) * 1.6f))
            DeskCatFace.ANNOYED -> drawPath(Path().apply { moveTo(38.9f, 30.3f); quadraticTo(40.5f, 29.1f, 42.1f, 30.3f) }, CAT_PALE, style = Stroke(0.7f, cap = StrokeCap.Round))
            else -> {
                val smile = face == DeskCatFace.HAPPY || face == DeskCatFace.PURR || face == DeskCatFace.LOVE || face == DeskCatFace.PLAYFUL
                val dip = if (smile) 1.5f else .9f
                val m = Path().apply { moveTo(38.6f, 29.4f); quadraticTo(39.6f, 29.4f + dip, 40.5f, 29.5f); quadraticTo(41.4f, 29.4f + dip, 42.4f, 29.4f) }
                drawPath(m, CAT_PALE, style = Stroke(0.7f, cap = StrokeCap.Round))
                if (face == DeskCatFace.PLAYFUL) drawOval(PINK, Offset(40f, 30f), Size(1.8f, 2.1f)) // the tongue "blep"
            }
        }
    }

    /** Things above or around the head: hearts, Zs, question and exclamation marks, purr lines. */
    private fun DrawScope.faceExtras(face: DeskCatFace, phase: Float) {
        val drift = (phase / (2 * kotlin.math.PI).toFloat()) % 1f
        when (face) {
            DeskCatFace.LOVE -> heart(51f, 4f - drift * 4f, 2.6f)
            DeskCatFace.ASLEEP -> { zee(50f, 7f - drift * 5f, 2.4f); zee(54.5f, 1.5f - drift * 4f, 1.7f) }
            DeskCatFace.SURPRISED -> { line(Color(0xFFFFE27A), 51f, 1f, 51f, 5.5f, 1.2f); drawCircle(Color(0xFFFFE27A), .7f, Offset(51f, 7.3f)) }
            DeskCatFace.CURIOUS -> {
                val q = Path().apply { moveTo(49.5f, 3.2f); quadraticTo(50.5f, .6f, 52.6f, 1.6f); quadraticTo(54.2f, 3f, 51.6f, 4.8f); lineTo(51.6f, 6f) }
                drawPath(q, Color(0xFFC6EB78), style = Stroke(1f, cap = StrokeCap.Round)); drawCircle(Color(0xFFC6EB78), .6f, Offset(51.6f, 7.6f))
            }
            DeskCatFace.PURR -> for (i in 0..1) {
                val r = 3f + i * 2.2f + drift * 2f
                drawArc(CAT_PALE.copy(alpha = .6f * (1f - drift)), -35f, 70f, false, Offset(24f - r, 30f - r), Size(r * 2, r * 2), style = Stroke(.6f))
            }
            DeskCatFace.ANNOYED -> { line(Color(0xFFF2B8B5), 50.5f, 4f, 53.5f, 2f, .9f); line(Color(0xFFF2B8B5), 51f, 6.5f, 54.5f, 6f, .9f) }
            else -> Unit
        }
    }

    private fun DrawScope.heart(cx: Float, cy: Float, s: Float) {
        val h = Path().apply {
            moveTo(cx, cy + s * .9f)
            cubicTo(cx - s * 1.4f, cy, cx - s * .8f, cy - s * .9f, cx, cy - s * .25f)
            cubicTo(cx + s * .8f, cy - s * .9f, cx + s * 1.4f, cy, cx, cy + s * .9f)
            close()
        }
        drawPath(h, Color(0xFFFF7B8F))
    }

    private fun DrawScope.zee(x: Float, y: Float, s: Float) {
        val z = Path().apply { moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s) }
        drawPath(z, CAT_PALE, style = Stroke(.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** Paws, props and particles that belong to a pose. */
    private fun DrawScope.poseExtras(pose: DeskCatPose, pulse: Float, phase: Float) {
        val w = Color(0xB3BED0CD)
        when (pose) {
            DeskCatPose.GROOM -> {
                val pawY = 25f + 4f * pulse
                line(CAT_LINE, 38f, 36f, 43f, pawY, 5.4f); line(CAT_BODY, 38f, 36f, 43f, pawY, 3.6f)
                drawOval(CAT_LINE, Offset(40.5f, pawY - 2f), Size(5f, 4f))
                if (pulse > .1f) drawOval(PINK, Offset(40f, 29f), Size(2.5f, 2f + pulse * 2))
            }
            DeskCatPose.STRETCH -> {
                line(CAT_LINE, 33f, 36f, 49f, 45f, 5.4f); line(CAT_BODY, 33f, 36f, 49f, 45f, 3.6f)
                line(CAT_LINE, 38f, 36f, 54f, 43f, 5.4f); line(CAT_BODY, 38f, 36f, 54f, 43f, 3.6f)
            }
            DeskCatPose.SNIFF -> { drawCircle(PINK, 1.8f, Offset(52f, 27f)); line(w, 51f, 24f, 56f, 22f, 0.7f); line(w, 51f, 26f, 57f, 26f, 0.7f) }
            DeskCatPose.TYPE -> {
                val left = 4f * maxOf(0f, pulse); val right = 4f * maxOf(0f, -pulse)
                line(CAT_LINE, 33f, 36f, 50f, 44f - left, 5.4f); line(CAT_BODY, 33f, 36f, 50f, 44f - left, 3.6f)
                line(CAT_LINE, 38f, 35f, 55f, 44f - right, 5.4f); line(CAT_BODY, 38f, 35f, 55f, 44f - right, 3.6f)
            }
            DeskCatPose.LETTER -> {
                val lift = 2f + 2f * pulse
                withTransform({ translate(0f, -lift); rotate(pulse * 6f, Offset(45f, 42f)) }) {
                    rrect(PAPER, 37f, 34f, 22f, 15f, 1f, 1f)
                    line(INK, 37f, 34f, 48f, 42f, 1f); line(INK, 59f, 34f, 48f, 42f, 1f)
                    line(CAT_LINE, 33f, 36f, 38f, 43f, 4f); line(CAT_LINE, 39f, 36f, 57f, 43f, 4f)
                }
            }
            DeskCatPose.WRITE -> {
                val stroke = 3f * pulse
                line(CAT_LINE, 38f, 36f, 49f + stroke, 21f, 5.4f)
                line(CAT_BODY, 38f, 36f, 49f + stroke, 21f, 3.6f)
                line(Color(0xFFFFE27A), 47f + stroke, 26f, 55f + stroke, 8f, 2.3f)
                line(INK, 55f + stroke, 8f, 56f + stroke, 5f, 1.2f)
            }
            DeskCatPose.LISTEN -> { line(Color(0xFFC6EB78), 34f, 6f, 31f, 2f, 1f); line(Color(0xFFC6EB78), 47f, 5f, 50f, 1f, 1f) }
            DeskCatPose.TALK -> { drawOval(PINK, Offset(39f, 29f), Size(3f, 1.2f + kotlin.math.abs(pulse) * 3f)) }
            DeskCatPose.GAME -> {
                // A little controller, thumbs mashing the buttons.
                rrect(Color(0xFF8D8A84), 36f, 36f, 14f, 7f, 2f, .8f)
                drawCircle(if (pulse > 0) Color(0xFFE5484D) else Color(0xFF8B1E22), 1.1f, Offset(46.5f, 38.5f))
                drawCircle(Color(0xFF1C1B19), 1f, Offset(39.5f, 39.5f))
                line(CAT_LINE, 32f, 35f, 38f, 39f + pulse, 4.6f); line(CAT_BODY, 32f, 35f, 38f, 39f + pulse, 3f)
                line(CAT_LINE, 37f, 34f, 47f, 39f - pulse, 4.6f); line(CAT_BODY, 37f, 34f, 47f, 39f - pulse, 3f)
            }
            DeskCatPose.READ -> {
                val turn = (pulse + 1f) / 2f
                val book = Path().apply { moveTo(36f, 35f); lineTo(44f, 37f); lineTo(52f, 35f); lineTo(52f, 44f); lineTo(44f, 46f); lineTo(36f, 44f); close() }
                drawPath(book, PAPER); drawPath(book, INK, style = Stroke(.8f))
                line(INK, 44f, 37f, 44f, 46f, .6f)
                line(Color(0xFFD5CDB4), 44f, 38f, 44f + 7f * turn, 36.5f, .6f)
                line(CAT_LINE, 33f, 36f, 38f, 42f, 4.4f); line(CAT_LINE, 37f, 35f, 51f, 42f, 4.4f)
            }
            DeskCatPose.BOB -> {
                val d = ((phase / (2 * kotlin.math.PI).toFloat()) % 1f)
                note(52f + d * 3f, 12f - d * 8f); note(56f - d * 2f, 20f - d * 10f)
                leg(33.5f, 37.5f, 45f, 0f)
            }
            DeskCatPose.DIG -> {
                // One paw scoops; bits of litter fly out behind.
                val scoop = pulse * 18f
                rotate(scoop, Offset(36f, 37f)) { line(CAT_LINE, 36f, 37f, 36f, 45f, 5.4f); line(CAT_BODY, 36f, 37f, 36f, 45f, 3.6f) }
                val d = ((phase / (2 * kotlin.math.PI).toFloat()) % 1f)
                for (i in 0..2) drawCircle(Color(0xFFE3D3A8), .8f, Offset(20f - i * 3f - d * 6f, 40f - d * 8f + i * 2f))
            }
            DeskCatPose.SCRATCH -> {
                val a = pulse * 3f
                line(CAT_LINE, 36f, 32f, 50f, 22f + a, 5f); line(CAT_BODY, 36f, 32f, 50f, 22f + a, 3.4f)
                line(CAT_LINE, 38f, 34f, 52f, 28f - a, 5f); line(CAT_BODY, 38f, 34f, 52f, 28f - a, 3.4f)
            }
            DeskCatPose.PERCH, DeskCatPose.WATCH -> { leg(33.5f, 37.5f, 45f, 0f); leg(37.5f, 37f, 45f, 0f) }
            else -> Unit
        }
    }

    private fun DrawScope.note(x: Float, y: Float) {
        drawCircle(Color(0xFFC6EB78), 1.1f, Offset(x, y)); line(Color(0xFFC6EB78), x + 1f, y, x + 1f, y - 4f, .6f); line(Color(0xFFC6EB78), x + 1f, y - 4f, x + 2.6f, y - 3.2f, .6f)
    }

    /** Curled up asleep: a round loaf with the tail wrapped around, breathing slowly. */
    private fun DrawScope.curledCat(face: DeskCatFace, phase: Float, tail: Float) {
        val breathe = 1f + sin(phase * .5f) * .03f
        drawOval(Color(0x73050D10), Offset(10f, 44.4f), Size(40f, 5.2f))
        withTransform({ scale(1f, breathe, Offset(30f, 46f)) }) {
            drawOval(CAT_BODY, Offset(12f, 30f), Size(36f, 16f)); drawOval(CAT_LINE, Offset(12f, 30f), Size(36f, 16f), style = Stroke(1.3f))
            val t = Path().apply { moveTo(13f, 40f); cubicTo(12f, 47f, 30f, 48f, 42f, 45f) }
            drawPath(t, CAT_LINE, style = Stroke(5.2f, cap = StrokeCap.Round)); drawPath(t, CAT_BODY, style = Stroke(3.5f, cap = StrokeCap.Round))
            // Head resting on the paws, low at the front.
            withTransform({ translate(-2f, 13f); scale(.86f, .86f, Offset(40.5f, 21.5f)) }) { head(face, 0f, 0f, 0f, phase) }
            drawOval(CAT_BODY, Offset(34f, 42f), Size(9f, 4f)); drawOval(CAT_LINE, Offset(34f, 42f), Size(9f, 4f), style = Stroke(.9f))
        }
        faceExtras(face, phase)
    }

    private fun DrawScope.leg(x: Float, top: Float, bottom: Float, swing: Float) = rotate(swing, Offset(x, top)) {
        line(CAT_LINE, x, top, x, bottom, 5.4f); line(CAT_BODY, x, top, x, bottom, 3.6f)
    }

    private fun DrawScope.ear(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }
        drawPath(p, CAT_BODY); drawPath(p, CAT_LINE, style = Stroke(1.3f, join = StrokeJoin.Round))
    }

    private fun DrawScope.tri(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, c)

    /** Lumen-style glossy lens eye (mint #4DFF85 iris), with a pupil size and an eyelid for the expression. */
    private fun DrawScope.eye(cx: Float, gaze: Float, pupil: Float = 2.05f, lid: Float = 0f, flatLid: Boolean = false) {
        val sclera = Brush.radialGradient(0f to Color(0xFFF7F9F2), .55f to Color(0xFFD9E6E2), 1f to Color(0xFF7E9A9B),
            center = Offset(cx - 1.4f, 20f), radius = 8.5f)
        drawCircle(sclera, 5.7f, Offset(cx, 21.8f)); drawCircle(Color(0xFF07131A), 5.7f, Offset(cx, 21.8f), style = Stroke(0.7f))
        val ix = cx + gaze.coerceIn(-1f, 1f)
        val iris = Brush.radialGradient(0f to Color(0xFFC9FFD9), .45f to Color(0xFF4DFF85), .8f to Color(0xFF1D7A55), 1f to Color(0xFF0A2330),
            center = Offset(ix, 22.6f), radius = 4.9f)
        drawCircle(iris, 4.1f, Offset(ix, 22.2f))
        drawCircle(Color(0xFF020A14), pupil, Offset(ix, 22.3f))
        drawCircle(Color.White, 1.2f, Offset(cx - 1.5f, 20f))
        if (lid > 0f) {
            // The upper lid comes down over the lens; a flat edge reads as unimpressed.
            val edge = 16.1f + 11.4f * lid
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(cx - 5.9f, 15.9f, cx + 5.9f, 27.7f)) }) {
                drawRect(CAT_BODY, Offset(cx - 6f, 15.5f), Size(12f, edge - 15.5f))
                val l = Path().apply { moveTo(cx - 6f, edge); if (flatLid) lineTo(cx + 6f, edge - .6f) else quadraticTo(cx, edge + 1.6f, cx + 6f, edge) }
                drawPath(l, CAT_LINE, style = Stroke(.9f))
            }
        }
    }
}
