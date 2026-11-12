package com.meowmba.shared.ui.desk

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.ui.desk.DeskPainter.INK
import com.meowmba.shared.ui.desk.DeskPainter.line
import com.meowmba.shared.ui.desk.DeskPainter.rrect
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The four hanging pictures (two in the living room, two in the bedroom). Each shows one of the
 * built-in drawings or a photo the user picked; the choice is kept as "art:N" or a file path.
 */
object DeskPictures {
    val ART_NAMES = listOf("Hills", "Meow", "Flowers", "Sunset", "Fish")
    private const val FILE = "desk_pictures"
    private val defaults = mapOf(
        DeskObject.PICTURE_LIVING_A to "art:0", DeskObject.PICTURE_LIVING_B to "art:3",
        DeskObject.PICTURE_BED_A to "art:2", DeskObject.PICTURE_BED_B to "art:1",
    )

    val choices = MutableStateFlow(load())

    fun choice(o: DeskObject, all: Map<String, String> = choices.value): String = all[o.name] ?: defaults[o] ?: "art:0"

    fun set(o: DeskObject, value: String) {
        choices.value = choices.value + (o.name to value)
        DefaultTextStorage.writeTextAtomic(FILE, choices.value.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun load(): Map<String, String> = DefaultTextStorage.readText(FILE).orEmpty().lines()
        .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    /** Frames and pictures in scene units; [photos] holds decoded photos by path. */
    fun DrawScope.drawPictures(all: Map<String, String>, photos: Map<String, ImageBitmap>) {
        DeskObject.entries.filter { it.isPicture }.forEach { o ->
            val (x, y, w, h) = o.hit.let { listOf(it[0], it[1], it[2], it[3]) }
            // Hanging wire and nail
            line(INK, x + w / 2, y - 16f, x + 10f, y + 4f, 1.2f); line(INK, x + w / 2, y - 16f, x + w - 10f, y + 4f, 1.2f)
            drawCircle(Color(0xFFBDBDBD), 2.5f, Offset(x + w / 2, y - 16f))
            rrect(Color(0xFF8C6A48), x, y, w, h, 3f, 2.5f)
            val ix = x + 7f; val iy = y + 7f; val iw = w - 14f; val ih = h - 14f
            val value = choice(o, all)
            val photo = photos[value]
            clipRect(ix, iy, ix + iw, iy + ih) {
                if (photo != null) {
                    // Crop to fill, like a print cut to the frame.
                    val scale = maxOf(iw / photo.width, ih / photo.height)
                    val sw = (iw / scale).toInt(); val sh = (ih / scale).toInt()
                    drawImage(photo, IntOffset((photo.width - sw) / 2, (photo.height - sh) / 2), IntSize(sw, sh),
                        IntOffset(ix.toInt(), iy.toInt()), IntSize(iw.toInt(), ih.toInt()))
                } else art(value.removePrefix("art:").toIntOrNull() ?: 0, ix, iy, iw, ih)
            }
            drawRect(INK, Offset(ix, iy), Size(iw, ih), style = Stroke(1.5f))
        }
    }

    private fun DrawScope.art(n: Int, x: Float, y: Float, w: Float, h: Float) {
        when (n % ART_NAMES.size) {
            0 -> { // Hills under a sky
                drawRect(Color(0xFFBFDCE6), Offset(x, y), Size(w, h))
                drawCircle(Color(0xFFFFE27A), w * .12f, Offset(x + w * .75f, y + h * .28f))
                drawPath(Path().apply { moveTo(x, y + h * .7f); quadraticTo(x + w * .3f, y + h * .4f, x + w * .6f, y + h * .66f)
                    quadraticTo(x + w * .8f, y + h * .5f, x + w, y + h * .62f); lineTo(x + w, y + h); lineTo(x, y + h); close() }, Color(0xFF7FA58A))
            }
            1 -> { // Meow's portrait
                drawRect(Color(0xFFF2B8C8), Offset(x, y), Size(w, h))
                val cx = x + w / 2; val cy = y + h * .58f; val r = minOf(w, h) * .3f
                drawPath(Path().apply { moveTo(cx - r, cy - r * .3f); lineTo(cx - r * .8f, cy - r * 1.3f); lineTo(cx - r * .2f, cy - r * .8f); close() }, Color(0xFF3A3A3E))
                drawPath(Path().apply { moveTo(cx + r, cy - r * .3f); lineTo(cx + r * .8f, cy - r * 1.3f); lineTo(cx + r * .2f, cy - r * .8f); close() }, Color(0xFF3A3A3E))
                drawCircle(Color(0xFF3A3A3E), r, Offset(cx, cy))
                drawCircle(Color(0xFFC6EB78), r * .16f, Offset(cx - r * .38f, cy - r * .1f)); drawCircle(Color(0xFFC6EB78), r * .16f, Offset(cx + r * .38f, cy - r * .1f))
                drawCircle(Color(0xFFF2B8C8), r * .1f, Offset(cx, cy + r * .25f))
            }
            2 -> { // Flowers in a vase
                drawRect(Color(0xFFFFF3C4), Offset(x, y), Size(w, h))
                val base = y + h * .62f
                listOf(-.22f to Color(0xFFE5484D), 0f to Color(0xFF1F4FBF), .22f to Color(0xFFE0B66A)).forEach { (dx, c) ->
                    val fx = x + w * (.5f + dx); val fy = y + h * (.3f + kotlin.math.abs(dx))
                    line(Color(0xFF2F7A3E), x + w / 2, base, fx, fy, 1.5f); drawCircle(c, w * .1f, Offset(fx, fy))
                }
                rrect(Color(0xFF5E7A76), x + w * .36f, base, w * .28f, h * .3f, 3f)
            }
            3 -> { // Sunset over the sea
                drawRect(Color(0xFFF2A06B), Offset(x, y), Size(w, h * .6f))
                drawCircle(Color(0xFFFFE27A), w * .16f, Offset(x + w / 2, y + h * .6f))
                drawRect(Color(0xFF26405A), Offset(x, y + h * .6f), Size(w, h * .4f))
                for (k in 1..3) line(Color(0xFFF2A06B), x + w * (.2f + k * .1f), y + h * (.6f + k * .1f), x + w * (.8f - k * .1f), y + h * (.6f + k * .1f), 1.2f)
            }
            else -> { // A fish
                drawRect(Color(0xFF81D8CE), Offset(x, y), Size(w, h))
                val cx = x + w * .45f; val cy = y + h / 2
                drawOval(Color(0xFFE0B66A), Offset(cx - w * .25f, cy - h * .14f), Size(w * .5f, h * .28f))
                drawPath(Path().apply { moveTo(cx + w * .22f, cy); lineTo(cx + w * .42f, cy - h * .14f); lineTo(cx + w * .42f, cy + h * .14f); close() }, Color(0xFFE0B66A))
                drawCircle(INK, 2f, Offset(cx - w * .14f, cy - h * .03f))
                drawCircle(Color(0x99FFFFFF), 2.5f, Offset(cx - w * .3f, cy - h * .25f)); drawCircle(Color(0x99FFFFFF), 1.8f, Offset(cx - w * .34f, cy - h * .36f))
            }
        }
    }
}
