package com.meowmba.shared.ui.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.meowmba.shared.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ported from Android `IllustratedEnvironmentBackground.kt` (build 171). Original painted room plus
 * bounded native atmosphere; an absent illustration shows its complete vector room. The Android
 * PNG assets ship as quality-90 JPEGs, with pre-scaled 320px thumbnails for the picker so a grid
 * of rooms never decodes full-size art.
 */
@Composable
internal fun IllustratedEnvironmentBackground(
    animation: BackgroundAnimation,
    modifier: Modifier = Modifier,
    clock: () -> Float,
    slow: () -> Float,
    thumbnail: Boolean = false,
) {
    var image by remember(animation, thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    val fallback = remember { PetEnvironmentBackgroundPainter() }
    val objects = remember { HabitatObjectAnimations() }
    LaunchedEffect(animation, thumbnail) { image = IllustratedRoomCache.load(animation, thumbnail) }
    val loaded = image
    if (loaded == null) {
        Canvas(modifier) { fallback.draw(this, animation, roomPhase(clock()), roomPhase(slow())) }
    } else {
        IllustratedRoomLayers(loaded, animation, objects, clock, modifier)
    }
}

/** Retain the stationary image's display list independently of the moving details. */
@Composable
internal fun IllustratedRoomLayers(image: ImageBitmap, animation: BackgroundAnimation,
    objects: HabitatObjectAnimations, clock: () -> Float, modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            drawIllustratedRoomImage(image, roomPlacement(image, animation))
        }
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            drawIllustratedRoomObjects(animation, objects, roomPlacement(image, animation), roomPhase(clock()))
        }
    }
}

private fun DrawScope.roomPlacement(image: ImageBitmap, animation: BackgroundAnimation) =
    illustratedRoomPlacement(image.width, image.height, size.width, size.height,
        illustratedSourceTableAnchor(animation), illustratedSourceFocusX(animation))

/** Shared native paint path used by the screen and pixel-level stationary-room checks. */
internal fun DrawScope.drawIllustratedRoom(image: ImageBitmap, animation: BackgroundAnimation,
    objects: HabitatObjectAnimations, phase: Float) {
    val placement = roomPlacement(image, animation)
    drawIllustratedRoomImage(image, placement)
    drawIllustratedRoomObjects(animation, objects, placement, phase)
}

private fun DrawScope.drawIllustratedRoomImage(image: ImageBitmap, placement: IllustratedRoomPlacement) {
    // Anchored cover maps each measured image ledge to the world's .78 plane.
    // Center-crop would silently move the contact plane on a landscape Fold.
    clipRect {
        drawImage(
            image,
            dstOffset = IntOffset(placement.x.roundToInt(), placement.y.roundToInt()),
            dstSize = IntSize(placement.width.roundToInt().coerceAtLeast(1), placement.height.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.High,
        )
    }
}

private fun DrawScope.drawIllustratedRoomObjects(animation: BackgroundAnimation,
    objects: HabitatObjectAnimations, placement: IllustratedRoomPlacement, phase: Float) {
    clipRect {
        clipRect(bottom = size.height * PET_ENVIRONMENT_TABLE_Y) {
            objects.draw(this, animation, placement, phase)
        }
    }
}

internal data class IllustratedRoomPlacement(val x: Float, val y: Float, val width: Float, val height: Float)

/** Source contact anchors are measured from the final assets, never inferred from prompts. */
internal fun illustratedSourceTableAnchor(animation: BackgroundAnimation): Float = when (animation) {
    BackgroundAnimation.KITCHEN, BackgroundAnimation.LIBRARY -> .689f
    BackgroundAnimation.GREENHOUSE -> 994f / 1536f
    BackgroundAnimation.ORCHARD -> 941f / 1536f
    BackgroundAnimation.WORKSHOP -> 908f / 1536f
    BackgroundAnimation.POND -> 974f / 1536f
    BackgroundAnimation.CAFE -> 945f / 1536f
    BackgroundAnimation.OBSERVATORY -> 905f / 1536f
    BackgroundAnimation.BEACH -> 982f / 1536f
    BackgroundAnimation.BEDROOM -> 887f / 1536f
    // New landscape art carries a deliberately broad, nearly level counter.
    BackgroundAnimation.CARTOON_NOOK -> 612f / 941f
    BackgroundAnimation.CARTOON_GALLEY -> 604f / 941f
    BackgroundAnimation.CARTOON_SNACK_LAB -> 679f / 1024f
    BackgroundAnimation.CARTOON_LAUNDROMAT -> 685f / 1024f
    BackgroundAnimation.CARTOON_DINER -> 664f / 1024f
    // Build 117 source artwork is square; the final measured contact anchors are
    // verified against the shipped PNGs by the integration build.
    BackgroundAnimation.CARTOON_PORTAL_ARCADE -> 758f / 1254f
    BackgroundAnimation.CARTOON_MOON_GARDEN -> 833f / 1254f
    BackgroundAnimation.CARTOON_ALIEN_AQUARIUM -> 753f / 1254f
    BackgroundAnimation.CARTOON_METEOR_GARAGE -> 850f / 1254f
    else -> .69f
}

/** Keep the illustrated joke in portrait's narrow crop; other rooms retain center framing. */
internal fun illustratedSourceFocusX(animation: BackgroundAnimation): Float = when (animation) {
    BackgroundAnimation.CARTOON_SNACK_LAB -> .23f
    BackgroundAnimation.CARTOON_DINER -> .18f
    else -> .5f
}

/** Cover without stretching; align the ledge vertically and bound horizontal focal framing. */
internal fun illustratedRoomPlacement(imageWidth: Int, imageHeight: Int, width: Float, height: Float,
    sourceAnchorFraction: Float = .69f, sourceFocusX: Float = .5f): IllustratedRoomPlacement {
    if (imageWidth <= 0 || imageHeight <= 0 || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f)
        return IllustratedRoomPlacement(0f, 0f, 1f, 1f)
    val sourceAnchor = if (sourceAnchorFraction.isFinite()) sourceAnchorFraction.coerceIn(.05f, .95f) else .69f
    val sourceY = imageHeight * sourceAnchor
    val targetY = height * PET_ENVIRONMENT_TABLE_Y
    // Anchor each image to the shared interaction plane while still covering
    // every edge. Landscape rooms now have dedicated wide source art; reducing
    // cover below this value would expose blank bands on an unfolded screen.
    val scale = maxOf(width / imageWidth, targetY / sourceY, (height - targetY) / (imageHeight - sourceY))
    val drawnWidth = imageWidth * scale
    val drawnHeight = imageHeight * scale
    val focus = if (sourceFocusX.isFinite()) sourceFocusX.coerceIn(0f, 1f) else .5f
    val left = (width * .5f - drawnWidth * focus).coerceIn(minOf(0f, width - drawnWidth), 0f)
    return IllustratedRoomPlacement(left,
        targetY - sourceY * scale, drawnWidth, drawnHeight)
}

/** Bound both dimensions before allocation, and never enlarge a small image. */
internal fun illustratedDecodeSize(width: Int, height: Int, requestedEdge: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val edge = requestedEdge.coerceIn(64, 1536)
    val ratio = minOf(1.0, edge.toDouble() / max(width, height))
    return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
}

private fun roomPhase(value: Float) = if (value.isFinite()) value - kotlin.math.floor(value) else 0f


/** One full-size room and a handful of thumbnails stay decoded; the rest are dropped by age. */
private object IllustratedRoomCache {
    private val lock = Mutex()
    private val rooms = LinkedHashMap<String, ImageBitmap>()

    suspend fun load(animation: BackgroundAnimation, thumbnail: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!animation.isPetEnvironment) return@withContext null
        lock.withLock {
            val key = (if (thumbnail) "thumbs/" else "painted/") + animation.id
            rooms.remove(key)?.let { rooms[key] = it; return@withLock it }
            val decoded = runCatching { Res.readBytes("files/backgrounds/$key.jpg").decodeToImageBitmap() }.getOrNull()
                ?: return@withLock null
            rooms[key] = decoded
            val full = rooms.keys.filter { it.startsWith("painted/") }
            if (full.size > 2) rooms.remove(full.first())
            val thumbs = rooms.keys.filter { it.startsWith("thumbs/") }
            if (thumbs.size > 24) rooms.remove(thumbs.first())
            decoded
        }
    }
}
