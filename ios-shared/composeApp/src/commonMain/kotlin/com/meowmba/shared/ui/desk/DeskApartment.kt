package com.meowmba.shared.ui.desk

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.meowmba.shared.ui.desk.DeskPainter.INK
import com.meowmba.shared.ui.desk.DeskPainter.PAPER
import com.meowmba.shared.ui.desk.DeskPainter.line
import com.meowmba.shared.ui.desk.DeskPainter.rrect
import com.meowmba.shared.ui.desk.DeskPainter.text

/**
 * The rest of Meow's place around the office: the living room on the left (x −412…0) and the
 * bedroom on the right (x 412…824), in the Desk's 960-high scene units and cartoon style.
 * Static furniture is drawn once with the room layer; the screens, labels and the big window's
 * sky redraw only when [DeskState] changes. Nothing animates here, for heat.
 */
internal object DeskApartment {
    private const val EXTEND = 2400f
    private const val WOOD = 0xFF8C6A48
    private val SANS = FontFamily.SansSerif

    // ---- walls, floors and doorways --------------------------------------------------------------

    fun DrawScope.drawShell() {
        // Living room: warm wallpaper, plank floor. It also fills everything left of the apartment.
        stripes(-412f - EXTEND, 412f + EXTEND, Color(0xFF33262B), Color(0xFF372A2F))
        drawRect(Color(0xFF3E2B21), Offset(-412f - EXTEND, 740f), Size(412f + EXTEND, EXTEND))
        var px = -412f - EXTEND
        while (px < 0f) { line(Color(0xFF32231B), px, 742f, px, 790f, 2f); px += 46f }
        // Office: the original teal stripes.
        stripes(0f, 412f, Color(0xFF1B3139), Color(0xFF1D343C))
        drawRect(Color(0xFF12232A), Offset(0f, 740f), Size(412f, EXTEND))
        // Bedroom: dusk-blue wallpaper with small stars, soft carpet. Fills everything to the right.
        stripes(412f, 412f + EXTEND, Color(0xFF212C44), Color(0xFF232F48))
        for (row in 0..5) for (col in 0..7) drawCircle(Color(0x33E9EFD9), 1.3f, Offset(430f + col * 52f + (row % 2) * 26f, 60f + row * 90f))
        drawRect(Color(0xFF34405A), Offset(412f, 740f), Size(EXTEND, EXTEND))
        // Skirting along every room.
        drawRect(Color(0xFF0F1D23), Offset(-412f - EXTEND, 732f), Size(1236f + 2 * EXTEND, 10f))
        line(INK, -412f - EXTEND, 732f, 824f + EXTEND, 732f, 2f); line(INK, -412f - EXTEND, 742f, 824f + EXTEND, 742f, 2f)
        // Walls between rooms, with open doorways the cat walks through.
        doorway(0f); doorway(412f)
    }

    private fun DrawScope.stripes(from: Float, width: Float, base: Color, stripe: Color) {
        drawRect(base, Offset(from, -EXTEND), Size(width, 740f + EXTEND))
        var x = from
        while (x < from + width) { drawRect(stripe, Offset(x, -EXTEND), Size(13f, 740f + EXTEND)); x += 26f }
    }

    private fun DrawScope.doorway(x: Float) {
        drawRect(Color(0xFF0B161B), Offset(x - 9f, -EXTEND), Size(18f, 330f + EXTEND))
        rrect(Color(WOOD), x - 13f, 322f, 26f, 12f, 2f, 2f)
        rrect(Color(WOOD), x - 13f, 330f, 7f, 404f, 1f, 2f)
        rrect(Color(WOOD), x + 6f, 330f, 7f, 404f, 1f, 2f)
        drawRect(Color(0x33000000), Offset(x - 6f, 334f), Size(12f, 398f))
    }

    // ---- living room (left) ----------------------------------------------------------------------

    fun DrawScope.drawLivingRoom() {
        // Rug under the couch
        drawOval(Color(0xFF6B3A4A), Offset(-312f, 726f), Size(210f, 22f))
        val roomy = DeskLayout.roomy
        // Bookshelf: five shelves of audiobooks (labels are dynamic); wider when the phone is sideways.
        val shelfW = if (roomy) 118f else 82f
        rrect(Color(0xFF6B4A2B), -402f, 420f, shelfW, 320f, 3f, 3f)
        rrect(Color(0xFF4A321D), -396f, 428f, shelfW - 12f, 304f, 2f)
        val spines = listOf(0xFFB24A62, 0xFF1F4FBF, 0xFFC6EB78, 0xFFE0B66A, 0xFF5E7A76, 0xFF8A2E5C, 0xFFF2B8B5, 0xFF2F7A3E)
        for (shelf in 0..4) {
            val top = 432f + shelf * 60f
            var bx = -394f
            var i = shelf * 3
            while (bx < -402f + shelfW - 18f) {
                val w = 7f + (i * 5 % 4)
                val h = 38f + (i * 7 % 14)
                // The red "manual" book on the second shelf is drawn with its label later.
                if (!(shelf == 1 && bx > -374f && bx < -360f)) {
                    rrect(Color(spines[i % spines.size]), bx, top + 50f - h, w, h, 1f, 1.2f)
                }
                bx += w + 1.5f; i++
            }
            rrect(Color(0xFF6B4A2B), -398f, top + 50f, shelfW - 8f, 6f, 1f, 1.5f)
        }
        // Cat tower (it steps right to make room for the wider bookshelf)
        val tx = if (roomy) 32f else 0f
        rrect(Color(0xFF8E7A5A), -318f + tx, 728f, 48f, 12f, 3f, 2f)
        sisal(-300f + tx, 470f, 12f, 258f)
        rrect(Color(0xFF9C6B8A), -316f + tx, 556f, 44f, 10f, 4f, 2f)
        rrect(Color(0xFF9C6B8A), -320f + tx, 462f, 52f, 12f, 5f, 2.5f)
        // TV mount shadow (the screen is dynamic)
        val tv = tvBox()
        rrect(Color(0xFF151515), tv[0] - 6f, tv[1] - 6f, tv[2] + 12f, tv[3] + 12f, 6f, 3f)
        line(Color(0xFF2A2A2A), tv[0] + tv[2] / 2, tv[1] + tv[3] + 6f, tv[0] + tv[2] / 2, tv[1] + tv[3] + 18f, 4f)
        // Couch
        rrect(Color(0xFF2E5A52), -262f, 636f, 108f, 56f, 10f, 2.5f)
        rrect(Color(0xFF3F6F66), -258f, 642f, 50f, 44f, 8f, 1.5f); rrect(Color(0xFF3F6F66), -206f, 642f, 50f, 44f, 8f, 1.5f)
        rrect(Color(0xFF3F6F66), -262f, 684f, 108f, 28f, 6f, 2.5f)
        rrect(Color(0xFF2E5A52), -272f, 660f, 18f, 56f, 8f, 2.5f); rrect(Color(0xFF2E5A52), -162f, 660f, 18f, 56f, 8f, 2.5f)
        rrect(Color(0xFF274C45), -266f, 712f, 116f, 16f, 3f, 2f)
        line(INK, -256f, 728f, -256f, 738f, 4f); line(INK, -160f, 728f, -160f, 738f, 4f)
        // Stereo: two big speakers either side of a component rack
        // Stereo: slim speakers either side of a wide component rack, so the tuner can show the song.
        speaker(-150f); speaker(-38f)
        rrect(Color(0xFF2B2B2E), -120f, 576f, 80f, 164f, 3f, 2.5f)
        rrect(Color(0xFF3A3A3E), -118f, 578f, 76f, 14f, 2f, 1.5f)               // turntable
        drawOval(Color(0xFF151515), Offset(-110f, 579f), Size(40f, 8f)); drawCircle(Color(0xFFE5484D), 1.6f, Offset(-90f, 583f))
        line(Color(0xFFBDBDBD), -50f, 580f, -62f, 586f, 1.5f)
        rrect(Color(0xFF3A3A3E), -116f, 596f, 72f, 32f, 2f, 1.5f)               // tuner (display is dynamic)
        rrect(Color(0xFF3A3A3E), -116f, 632f, 72f, 24f, 2f, 1.5f)               // cassette deck
        rrect(Color(0xFF1A1A1C), -108f, 637f, 24f, 11f, 1f, 1f); rrect(Color(0xFF1A1A1C), -76f, 637f, 24f, 11f, 1f, 1f)
        rrect(Color(0xFF3A3A3E), -116f, 660f, 72f, 32f, 2f, 1.5f)               // amplifier
        for (k in 0..3) { drawCircle(Color(0xFFBDBDBD), 4f, Offset(-104f + k * 16f, 676f)); drawCircle(INK, 4f, Offset(-104f + k * 16f, 676f), style = Stroke(1f)) }
    }

    private fun DrawScope.speaker(x: Float) {
        rrect(Color(0xFF3B2A20), x, 600f, 28f, 140f, 3f, 2.5f)
        drawCircle(Color(0xFF1A1A1C), 5f, Offset(x + 14f, 616f)); drawCircle(Color(0xFF6E6E72), 2f, Offset(x + 14f, 616f))
        drawCircle(Color(0xFF1A1A1C), 11f, Offset(x + 14f, 650f)); drawCircle(Color(0xFF4A4A4E), 5f, Offset(x + 14f, 650f))
        drawCircle(Color(0xFF1A1A1C), 11f, Offset(x + 14f, 690f)); drawCircle(Color(0xFF4A4A4E), 5f, Offset(x + 14f, 690f))
        rrect(Color(0xFF1A1A1C), x + 7f, 718f, 14f, 6f, 3f)
    }

    private fun DrawScope.sisal(x: Float, top: Float, w: Float, h: Float) {
        rrect(Color(0xFFC9B38A), x, top, w, h, 2f, 2f)
        var y = top + 4f
        while (y < top + h) { line(Color(0xFFA8926A), x + 1f, y, x + w - 1f, y + 2f, 1f); y += 6f }
    }

    fun DrawScope.drawLivingRoomObjects(s: DeskState, m: TextMeasurer) {
        val r = s.room
        // Big-screen TV: what's on YouTube, or a quiet screen.
        val video = r.video
        // Everything on the screen scales with it (much bigger when the phone is sideways).
        val (x, y, w, h) = tvBox().let { listOf(it[0], it[1], it[2], it[3]) }
        val k = w / 186f
        val cx = x + w / 2
        drawRoundRect(if (video != null) Color(0xFF0F0F12) else Color(0xFF14191C), Offset(x, y), Size(w, h), CornerRadius(3f))
        when {
            !r.mediaAccess -> { text(m, "Allow media info", cx, y + h * .46f, 13f * k, Color(0xFF8FB0AC), family = SANS, center = true)
                text(m, "to see what's on", cx, y + h * .62f, 12f * k, Color(0xFF6E8C8A), family = SANS, center = true) }
            video == null -> { playBadge(cx, y + h * .39f, 9f * k, Color(0xFF5A3030)); text(m, "Nothing playing", cx, y + h * .72f, 13f * k, Color(0xFF6E8C8A), family = SANS, center = true) }
            else -> {
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF26324A), Color(0xFF0F0F12)), y, y + h), Offset(x, y), Size(w, h), CornerRadius(3f))
                playBadge(x + 14f * k, y + 16f * k, 7f * k, Color(0xFFE5302A))
                text(m, if (video.playing) "PLAYING" else "PAUSED", x + 26f * k, y + 20f * k, 8f * k, Color(0xFFFFFFFF), family = SANS, weight = FontWeight.Bold)
                wrap(video.title, 22).take(2).forEachIndexed { i, l -> text(m, l, x + 8f * k, y + 50f * k + i * 18f * k, 15f * k, Color.White, family = SANS, weight = FontWeight.Bold) }
                video.subtitle?.let { text(m, it.take(26), x + 8f * k, y + 90f * k, 12f * k, Color(0xFFB8C4D6), family = SANS) }
                drawRect(Color(0x55FFFFFF), Offset(x + 8f * k, y + h - 12f * k), Size(w - 16f * k, 3f * k))
                drawRect(Color(0xFFE5302A), Offset(x + 8f * k, y + h - 12f * k), Size((w - 16f * k) * (if (video.playing) .38f else .24f), 3f * k))
            }
        }
        // Tuner display: the song on the stereo
        val music = r.music
        drawRoundRect(Color(0xFF0B1A12), Offset(-113f, 599f), Size(66f, 26f), CornerRadius(1.5f))
        if (music != null) {
            text(m, music.title.take(11), -111f, 610f, 8.5f, Color(0xFF7BE06A), family = FontFamily.Monospace, weight = FontWeight.Bold)
            text(m, (music.subtitle ?: if (music.playing) "PLAY" else "PAUSE").take(12), -111f, 621f, 7.5f, Color(0xFF4FA048), family = FontFamily.Monospace)
            if (music.playing) { note(-138f, 586f); note(-22f, 580f) }
        } else text(m, if (r.mediaAccess) "-- --" else "NO INFO", -111f, 616f, 8.5f, Color(0xFF356E3A), family = FontFamily.Monospace)
        // The manual on the second shelf, and a "now reading" tag when an audiobook is on.
        rrect(Color(0xFFC0392B), -372f, 490f, 11f, 42f, 1f, 1.2f)
        rotate(-90f, Offset(-366.5f, 511f)) { text(m, "MANUAL", -366.5f, 514f, 6.5f, Color(0xFFFFF3C4), family = SANS, weight = FontWeight.Bold, center = true) }
        r.audiobook?.let { book ->
            val sw = if (DeskLayout.roomy) 106f else 70f
            rrect(Color(0xFFFFE27A), -392f, 402f, sw, 16f, 2f, 1.5f)
            text(m, "Now: " + book.title.take(if (DeskLayout.roomy) 15 else 9), -389f, 413f, 9f, INK)
            // The book being listened to glows on the top shelf.
            drawRoundRect(Color(0x80FFE27A), Offset(-396f, 430f), Size(sw, 52f), CornerRadius(3f))
        }
    }

    /** The big TV's screen (left, top, width, height). */
    private fun tvBox(): FloatArray = if (DeskLayout.roomy) floatArrayOf(-306f, 250f, 258f, 168f) else floatArrayOf(-270f, 338f, 186f, 114f)

    private fun DrawScope.playBadge(x: Float, y: Float, h: Float, c: Color) {
        drawRoundRect(c, Offset(x - h * 1.4f, y - h), Size(h * 2.8f, h * 2f), CornerRadius(h * .5f))
        drawPath(Path().apply { moveTo(x - h * .4f, y - h * .55f); lineTo(x + h * .6f, y); lineTo(x - h * .4f, y + h * .55f); close() }, Color.White)
    }

    private fun DrawScope.note(x: Float, y: Float) {
        drawCircle(Color(0xFFC6EB78), 3f, Offset(x, y)); line(Color(0xFFC6EB78), x + 2.6f, y, x + 2.6f, y - 12f, 1.6f)
        line(Color(0xFFC6EB78), x + 2.6f, y - 12f, x + 7f, y - 9f, 1.6f)
    }

    // ---- bedroom (right) -------------------------------------------------------------------------

    fun DrawScope.drawBedroom() {
        // Dresser with the old TV, the console on the floor in front
        rrect(Color(0xFF7A5536), 424f, 610f, 128f, 130f, 3f, 3f)
        for (d in 0..2) { rrect(Color(0xFF8E6440), 432f, 620f + d * 38f, 112f, 32f, 3f, 2f); rrect(INK, 480f, 634f + d * 38f, 16f, 5f, 2.5f) }
        // Old CRT TV with rabbit ears (the screen is dynamic)
        line(INK, 486f, 528f, 462f, 492f, 2f); line(INK, 486f, 528f, 512f, 490f, 2f)
        drawCircle(Color(0xFF9B9B9B), 2.5f, Offset(462f, 492f)); drawCircle(Color(0xFF9B9B9B), 2.5f, Offset(512f, 490f))
        rrect(Color(0xFF6B5B4A), 436f, 526f, 104f, 84f, 8f, 3f)
        rrect(Color(0xFF4E4236), 520f, 538f, 14f, 60f, 3f, 1.5f)
        for (k in 0..1) { drawCircle(Color(0xFFC9B38A), 3.5f, Offset(527f, 552f + k * 16f)); drawCircle(INK, 3.5f, Offset(527f, 552f + k * 16f), style = Stroke(1f)) }
        // Console + controller
        rrect(Color(0xFFBDB8AE), 430f, 718f, 52f, 20f, 3f, 2f)
        rrect(Color(0xFF6E6A62), 434f, 724f, 44f, 5f, 1f)
        drawCircle(Color(0xFFE5484D), 2f, Offset(472f, 733f))
        val cord = Path().apply { moveTo(482f, 728f); quadraticTo(490f, 740f, 496f, 732f) }
        drawPath(cord, INK, style = Stroke(1.5f))
        rrect(Color(0xFF8D8A84), 494f, 726f, 18f, 9f, 2f, 1.5f)
        val roomy = DeskLayout.roomy
        // Big window frame and curtains (sky and city are dynamic); wider when the phone is sideways.
        val (wl, ww) = bigWindow()
        line(INK, wl - 22f, 120f, wl + ww + 24f, 120f, 4f)
        for (cx in listOf(wl - 16f, wl + ww - 10f)) {
            val c = Path().apply { moveTo(cx, 120f); lineTo(cx + 28f, 120f); quadraticTo(cx + 18f, 250f, cx + 26f, 388f); lineTo(cx + 2f, 388f); quadraticTo(cx - 4f, 250f, cx, 120f); close() }
            drawPath(c, Color(0xFF8A2E5C)); drawPath(c, INK, style = Stroke(2f, join = StrokeJoin.Round))
        }
        // Cat tower by the window: tall sisal post, three platforms, a dangling mouse toy.
        rrect(Color(0xFF8E7A5A), 568f, 726f, 68f, 14f, 3f, 2f)
        sisal(596f, 380f, 12f, 346f)
        rrect(Color(0xFF9C6B8A), 574f, 632f, 56f, 10f, 4f, 2f)
        rrect(Color(0xFF9C6B8A), 580f, 516f, 50f, 10f, 4f, 2f)
        rrect(Color(0xFF9C6B8A), 564f, 374f, 72f, 14f, 6f, 2.5f)
        line(INK, 624f, 526f, 624f, 566f, 1f)
        drawOval(Color(0xFF9B9B9B), Offset(618f, 564f), Size(12f, 8f)); line(Color(0xFFF2B8C8), 618f, 568f, 612f, 572f, 1.2f)
        // Food tray: kibble and water on a mat (in front of the tower base when the bed is longer)
        val fx = if (roomy) 576f else 630f
        rrect(Color(0xFFE08C84), fx, 734f, 56f, 6f, 2f, 1.5f)
        drawBowl(fx + 4f, Color(0xFFC6EB78)); drawBowl(fx + 30f, Color(0xFF81D8CE))
        for (k in 0..3) drawCircle(Color(0xFF8E5E33), 1.6f, Offset(fx + 9f + k * 4f, 723f))
        // Bed: frame, mattress, quilt, pillow, headboard (longer when the phone is sideways)
        val bx = if (roomy) 640f else 684f
        rrect(Color(WOOD), 812f, 588f, 14f, 152f, 3f, 2.5f)
        rrect(Color(WOOD), bx, 686f, 824f - bx, 34f, 3f, 2.5f)
        line(INK, bx + 6f, 720f, bx + 6f, 738f, 5f); line(INK, 816f, 720f, 816f, 738f, 5f)
        rrect(Color(0xFFEDE6D6), bx + 4f, 656f, 812f - bx - 4f, 30f, 6f, 2f)
        val qx = bx + 4f
        val quilt = Path().apply { moveTo(qx, 668f); lineTo(778f, 664f); quadraticTo(786f, 690f, 780f, 712f); lineTo(qx + 2f, 712f); quadraticTo(qx - 6f, 690f, qx, 668f); close() }
        drawPath(quilt, Color(0xFF1F4FBF)); drawPath(quilt, INK, style = Stroke(2f, join = StrokeJoin.Round))
        var stripe = qx + 12f
        while (stripe < 770f) { line(Color(0xFF3F6FDF), stripe, 668f, stripe - 2f, 710f, 1.2f); stripe += 22f }
        rrect(Color(0xFFF4EEDB), 780f, 640f, 34f, 20f, 8f, 2f)
        // Litter box (its front lip is also drawn over the cat when it's inside)
        rrect(Color(0xFF5E7A76), 506f, 692f, 58f, 48f, 5f, 2.5f)
        drawRect(Color(0xFFE3D3A8), Offset(510f, 694f), Size(50f, 6f))
    }

    /** The bedroom's big window (left, width); it grows wider when the phone is sideways. */
    private fun bigWindow(): Pair<Float, Float> = if (DeskLayout.roomy) 486f to 302f else 522f to 220f

    private fun DrawScope.cloudPuff(x: Float, y: Float, w: Float, c: Color) {
        drawOval(c, Offset(x, y), Size(w, w * .3f))
        drawOval(c, Offset(x + w * .22f, y - w * .16f), Size(w * .5f, w * .36f))
        drawOval(c, Offset(x + w * .5f, y - w * .08f), Size(w * .36f, w * .28f))
    }

    private fun DrawScope.drawBowl(x: Float, c: Color) {
        val b = Path().apply { moveTo(x, 724f); lineTo(x + 22f, 724f); lineTo(x + 18f, 736f); lineTo(x + 4f, 736f); close() }
        drawPath(b, c); drawPath(b, INK, style = Stroke(1.8f, join = StrokeJoin.Round))
    }

    /** Drawn after the cat so it sits inside the litter box. */
    fun DrawScope.drawLitterFront() {
        rrect(Color(0xFF5E7A76), 504f, 704f, 62f, 36f, 4f, 2.5f)
        line(Color(0xFF7FA09A), 512f, 712f, 558f, 712f, 1.5f)
    }

    fun DrawScope.drawBedroomObjects(s: DeskState, m: TextMeasurer) {
        // Big window: sky, drifting clouds (the moon lives in the office window), a little city
        val (wl, ww) = bigWindow()
        val sx = ww / 220f
        fun wx(x: Float) = wl + (x - 522f) * sx
        val sky = if (s.night) listOf(Color(0xFF0B1A2A), Color(0xFF26405A)) else listOf(Color(0xFF6FA7C4), Color(0xFFCFE4EC))
        drawRoundRect(Brush.verticalGradient(sky, 130f, 380f), Offset(wl, 130f), Size(ww, 250f), CornerRadius(6f))
        val cloud = if (s.night) Color(0x8C6F86A0) else Color(0xE6FFFFFF)
        cloudPuff(wx(548f), 176f, 64f * sx, cloud); cloudPuff(wx(662f), 154f, 72f * sx, cloud)
        if (s.night) listOf(Offset(wx(596f), 214f), Offset(wx(640f), 200f), Offset(wx(566f), 146f), Offset(wx(718f), 206f))
            .forEach { drawCircle(Color(0xFFE9EFD9), 1.6f, it) }
        val city = if (s.night) Color(0xFF0E1C2C) else Color(0xFF6E8FA6)
        val towers = listOf(Triple(526f, 300f, 34f), Triple(562f, 270f, 28f), Triple(592f, 316f, 40f), Triple(634f, 256f, 30f), Triple(666f, 292f, 36f), Triple(704f, 312f, 38f))
        towers.forEach { (tx, top, tw) ->
            val x = wx(tx); val w = tw * sx
            drawRect(city, Offset(x, top), Size(w, 380f - top))
            if (s.night) for (wy in 0..3) for (col in 0 until (w / 14f).toInt().coerceAtLeast(2)) if ((wy + col + tx.toInt()) % 3 != 0)
                drawRect(Color(0xFFFFE27A), Offset(x + 6f + col * 12f, top + 10f + wy * 16f), Size(5f, 6f))
        }
        drawRoundRect(INK, Offset(wl, 130f), Size(ww, 250f), CornerRadius(6f), style = Stroke(3.5f))
        line(INK, wl + ww / 2, 130f, wl + ww / 2, 380f, 3.5f); line(INK, wl, 256f, wl + ww, 256f, 3.5f)
        rrect(Color(WOOD), wl - 10f, 380f, ww + 20f, 12f, 3f, 2.5f)
        s.weatherLine?.let { text(m, it, 690f, 414f, 16f, Color(0xFFB8C4D6), center = true) } // right of the tower post
        // The old TV's screen: an arcade attract screen, inviting a game
        drawRoundRect(Color(0xFF10161A), Offset(446f, 538f), Size(70f, 60f), CornerRadius(10f))
        val px = Color(0xFF7BE06A)
        val invader = listOf("0010000100", "0001111000", "0011011100", "0111111110", "0101111010", "0100000010")
        invader.forEachIndexed { row, cols -> cols.forEachIndexed { col, c -> if (c == '1') drawRect(px, Offset(461f + col * 4f, 546f + row * 4f), Size(3.6f, 3.6f)) } }
        text(m, "INSERT COIN", 481f, 590f, 7f, Color(0xFFFFE27A), family = FontFamily.Monospace, weight = FontWeight.Bold, center = true)
    }

    private fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (w in text.split(' ')) {
            if (cur.isEmpty()) cur = w.take(width) else if (cur.length + 1 + w.length <= width) cur += " $w" else { out += cur; cur = w.take(width) }
        }
        if (cur.isNotEmpty()) out += cur
        return out.mapIndexed { i, l -> if (i == 1 && out.size > 2) l.take(width - 1) + "…" else l }
    }
}
