package com.meowmba.shared.ui.vending

import com.meowmba.shared.orgjson.JSONArray
import com.meowmba.shared.orgjson.JSONObject
import com.meowmba.shared.orgjson.JSONTokener
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** How the companion might use a dispensed prop. */
enum class GeneratedPropCategory {
    HELD, WEARABLE, SCENE, TOY;

    companion object {
        fun fromKey(raw: String?): GeneratedPropCategory =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HELD
    }
}

/**
 * A bounded vector prop the companion can hold or place — same shape language
 * as generated avatars, without a live face. Invented through the Vending Machine.
 */
data class GeneratedPropForm(
    val id: String,
    val name: String,
    val description: String,
    val category: GeneratedPropCategory,
    val useHint: String,
    val parts: List<GeneratedAvatarPart>,
    /** Attach point on the avatar in normalized companion space (y down). */
    val anchorX: Float = 0f,
    val anchorY: Float = 0.22f,
    /** Extra placement controls for the prop-on-avatar editor. */
    val attachScale: Float = 1f,
    val attachRotation: Float = 0f,
    val version: Int = 1,
)

object GeneratedPropForms {
    const val MAX_JSON = 24_576
    const val MAX_PARTS = 16
    const val MAX_REQUEST = 720
    const val MAX_SAVED = 32
    class SpecException(message: String) : IllegalArgumentException(message)

    fun parse(raw: String): GeneratedPropForm {
        if (raw.length > MAX_JSON) fail("Prop drawing is too large (maximum $MAX_JSON characters)")
        val root = try {
            val tokenizer = JSONTokener(raw)
            val value = tokenizer.nextValue() as? JSONObject ?: fail("Prop drawing must be a JSON object")
            if (tokenizer.nextClean() != '\u0000') fail("Prop drawing contains trailing content")
            value
        } catch (e: SpecException) { throw e }
        catch (_: Exception) { fail("Prop drawing is not valid JSON") }
        keys(root, setOf("version", "name", "description", "category", "use_hint", "parts", "anchor", "attach_scale", "attach_rotation"), "prop")
        if (number(root, "version", 1f, 1f) != 1f) fail("Unsupported prop version")
        val name = string(root, "name", 60)
        val description = string(root, "description", MAX_REQUEST, allowEmpty = true)
        val catRaw = root.opt("category") as? String ?: fail("Prop needs a category string")
        val category = GeneratedPropCategory.entries.firstOrNull { it.name.equals(catRaw, ignoreCase = true) }
            ?: fail("Prop category must be held, wearable, scene or toy")
        val useHint = string(root, "use_hint", 240, allowEmpty = true)
        val array = root.optJSONArray("parts") ?: fail("Prop needs a parts array")
        if (array.length() !in 1..MAX_PARTS) fail("Prop needs 1 to $MAX_PARTS parts")
        val parts = (0 until array.length()).map { index -> parsePart(array.optJSONObject(index), index) }
        val anchor = root.optJSONObject("anchor") ?: fail("Prop needs an anchor")
        keys(anchor, setOf("x", "y"), "anchor")
        val form = GeneratedPropForm(
            "", name, description, category, useHint, parts,
            number(anchor, "x", -.5f, .5f, 0f),
            number(anchor, "y", -.5f, .5f, .22f),
            number(root, "attach_scale", .3f, 2.5f, 1f),
            number(root, "attach_rotation", -180f, 180f, 0f),
        )
        val id = com.meowmba.shared.bridge.BridgeCrypto.sha256Hex(toJson(form).encodeToByteArray())
        return form.copy(id = id)
    }

    fun toJson(form: GeneratedPropForm): String = JSONObject().apply {
        put("version", 1)
        put("name", form.name)
        put("description", form.description)
        put("category", form.category.name.lowercase())
        put("use_hint", form.useHint)
        put("parts", JSONArray().apply {
            form.parts.forEach { part ->
                put(JSONObject().apply {
                    put("shape", part.shape.name.lowercase())
                    put("x", part.x); put("y", part.y)
                    put("width", part.width); put("height", part.height)
                    put("fill", colorHex(part.fill))
                    put("outline", colorHex(part.outline))
                    put("stroke", part.stroke)
                    put("rotation", part.rotation)
                    put("motion", part.motion.name.lowercase())
                    put("phase", part.phase)
                    if (part.points.isNotEmpty()) {
                        put("points", JSONArray().apply {
                            part.points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
                        })
                    }
                })
            }
        })
        put("anchor", JSONObject().put("x", form.anchorX).put("y", form.anchorY))
        put("attach_scale", form.attachScale)
        put("attach_rotation", form.attachRotation)
    }.toString()


    /** Smarter default attach for known wearable types; user may still drag-override. */
    fun withSmartDefaults(form: GeneratedPropForm): GeneratedPropForm {
        val (x, y) = defaultAttach(form.category, form.name, form.description)
        val scale = when (form.category) {
            GeneratedPropCategory.WEARABLE -> .85f
            GeneratedPropCategory.HELD -> 1f
            GeneratedPropCategory.TOY -> .9f
            GeneratedPropCategory.SCENE -> 1.1f
        }
        return rehash(form.copy(anchorX = x, anchorY = y, attachScale = scale, attachRotation = 0f))
    }

    fun withPlacement(
        form: GeneratedPropForm,
        anchorX: Float,
        anchorY: Float,
        attachScale: Float = form.attachScale,
        attachRotation: Float = form.attachRotation,
    ): GeneratedPropForm = rehash(
        form.copy(
            anchorX = anchorX.coerceIn(-.5f, .5f),
            anchorY = anchorY.coerceIn(-.5f, .5f),
            attachScale = attachScale.coerceIn(.3f, 2.5f),
            attachRotation = attachRotation.coerceIn(-180f, 180f),
        ),
    )

    fun defaultAttach(
        category: GeneratedPropCategory,
        name: String,
        description: String = "",
    ): Pair<Float, Float> {
        val blob = "$name $description".lowercase()
        return when (category) {
            GeneratedPropCategory.WEARABLE -> when {
                looksLike(blob, "glass", "sunglass", "spectacles", "goggle", "monocle", "visor") ->
                    0f to -.12f
                looksLike(blob, "hat", "cap", "crown", "helmet", "beanie", "hood", "tiara") ->
                    0f to -.28f
                looksLike(blob, "mask", "scarf", "bandana") ->
                    0f to -.02f
                looksLike(blob, "earring", "ear", "antenna") ->
                    .18f to -.16f
                looksLike(blob, "bow", "ribbon", "flower") ->
                    -.16f to -.22f
                else -> 0f to -.1f
            }
            GeneratedPropCategory.HELD -> .22f to .22f
            GeneratedPropCategory.TOY -> 0f to .18f
            GeneratedPropCategory.SCENE -> 0f to .28f
        }
    }

    private fun looksLike(blob: String, vararg needles: String): Boolean =
        needles.any { it in blob }

    private fun rehash(form: GeneratedPropForm): GeneratedPropForm {
        val id = com.meowmba.shared.bridge.BridgeCrypto.sha256Hex(toJson(form.copy(id = "")).encodeToByteArray())
        return form.copy(id = id)
    }

    private fun parsePart(p: JSONObject?, index: Int): GeneratedAvatarPart {
        val part = p ?: fail("Part $index must be an object")
        keys(part, setOf("shape", "x", "y", "width", "height", "fill", "outline", "stroke", "rotation", "motion", "phase", "points"), "part $index")
        val shape = GeneratedAvatarShape.entries.firstOrNull { it.name.lowercase() == part.opt("shape") }
            ?: fail("Part $index shape must be ellipse, round_rect or polygon")
        val motion = if (!part.has("motion")) GeneratedAvatarMotion.STILL else {
            val key = (part.opt("motion") as? String)?.trim()?.lowercase()
                ?: fail("Part $index has an unsupported motion")
            GeneratedAvatarMotion.entries.firstOrNull { it.name.lowercase() == key }
                ?: when (key) {
                    "head", "face", "neck" -> GeneratedAvatarMotion.STILL
                    "torso", "chest" -> GeneratedAvatarMotion.BODY
                    "hand", "hands", "limb", "paw", "handle" -> GeneratedAvatarMotion.ARM
                    "foot", "feet" -> GeneratedAvatarMotion.LEG
                    "wings" -> GeneratedAvatarMotion.WING
                    "steam", "smoke", "hover", "bob" -> GeneratedAvatarMotion.FLOAT
                    "ears", "horn", "horns" -> GeneratedAvatarMotion.ANTENNA
                    else -> fail("Part $index has an unsupported motion")
                }
        }
        val x = number(part, "x", -.5f, .5f); val y = number(part, "y", -.5f, .5f)
        val width = number(part, "width", .01f, 1f); val height = number(part, "height", .01f, 1f)
        val rotation = number(part, "rotation", -180f, 180f, 0f)
        val stroke = number(part, "stroke", .002f, .025f, .012f)
        val angle = rotation * kotlin.math.PI.toFloat() / 180f
        val extentX = (abs(cos(angle)) * width + abs(sin(angle)) * height) * .5f + stroke * .5f
        val extentY = (abs(sin(angle)) * width + abs(cos(angle)) * height) * .5f + stroke * .5f
        if (abs(x) + extentX > .57f || abs(y) + extentY > .57f)
            fail("Part $index extends beyond the drawing boundary; move or shrink it within -0.57 to 0.57")
        val points = if (shape == GeneratedAvatarShape.POLYGON) {
            val values = part.optJSONArray("points") ?: fail("Polygon $index needs points")
            if (values.length() !in 3..16) fail("Polygon $index needs 3 to 16 points")
            (0 until values.length()).map { j ->
                val point = values.optJSONArray(j) ?: fail("Polygon point must be an [x,y] pair")
                if (point.length() != 2) fail("Polygon point must have exactly two coordinates")
                fun coordinate(i: Int): Float {
                    val n = (point.opt(i) as? Number)?.toFloat() ?: fail("Polygon coordinates must be numbers")
                    if (!n.isFinite() || n !in -.5f.. .5f) fail("Polygon local coordinates must be -0.5 to 0.5")
                    return n
                }
                GeneratedAvatarPoint(coordinate(0), coordinate(1))
            }
        } else {
            if (part.has("points")) fail("Only polygon parts may contain points")
            emptyList()
        }
        return GeneratedAvatarPart(
            shape, x, y, width, height, color(part, "fill"), color(part, "outline", 0xFF182032),
            stroke, rotation, motion, number(part, "phase", 0f, 6.284f, 0f), points,
        )
    }

    private fun keys(obj: JSONObject, allowed: Set<String>, label: String) {
        obj.keys().asSequence().forEach { key ->
            if (key !in allowed) fail("$label has unsupported field \"$key\"")
        }
    }

    private fun string(obj: JSONObject, key: String, max: Int, allowEmpty: Boolean = false): String {
        val value = obj.opt(key) as? String ?: fail("$key must be a string")
        if (!allowEmpty && value.isBlank()) fail("$key cannot be blank")
        if (value.length > max) fail("$key is too long")
        if (value.any { it.let { c -> c.code < 32 || c.code in 127..159 } && it != '\n' && it != '\t' }) fail("$key contains control characters")
        return value.trim()
    }

    private fun number(obj: JSONObject, key: String, min: Float, max: Float, default: Float? = null): Float {
        if (!obj.has(key)) {
            if (default != null) return default
            fail("$key is required")
        }
        val n = (obj.opt(key) as? Number)?.toFloat() ?: fail("$key must be a number")
        if (!n.isFinite() || n !in min..max) fail("$key must be $min to $max")
        return n
    }

    private fun color(obj: JSONObject, key: String, default: Long? = null): Long {
        if (!obj.has(key)) {
            if (default != null) return default
            fail("$key is required")
        }
        val raw = obj.opt(key) as? String ?: fail("$key must be a hex color string")
        val match = Regex("^#([0-9A-Fa-f]{6})$").matchEntire(raw)
            ?: fail("$key must be opaque #RRGGBB")
        return 0xFF000000 or match.groupValues[1].toLong(16)
    }

    private fun colorHex(value: Long): String = "#" + (value and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    private fun fail(message: String): Nothing = throw SpecException(message)
}
