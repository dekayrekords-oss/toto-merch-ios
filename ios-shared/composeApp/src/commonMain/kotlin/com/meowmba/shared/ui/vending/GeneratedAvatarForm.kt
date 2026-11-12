package com.meowmba.shared.ui.vending

import com.meowmba.shared.orgjson.JSONArray
import com.meowmba.shared.orgjson.JSONObject
import com.meowmba.shared.orgjson.JSONTokener
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A small drawing language, not model-supplied code, paths, images or shaders. */
enum class GeneratedAvatarShape { ELLIPSE, ROUND_RECT, POLYGON }
enum class GeneratedAvatarMotion { STILL, BODY, WING, ARM, LEG, ANTENNA, TAIL, FLOAT }
data class GeneratedAvatarPoint(val x: Float, val y: Float)
data class GeneratedAvatarPart(
    val shape: GeneratedAvatarShape, val x: Float, val y: Float,
    val width: Float, val height: Float, val fill: Long,
    val outline: Long = 0xFF182032, val stroke: Float = .012f,
    val rotation: Float = 0f, val motion: GeneratedAvatarMotion = GeneratedAvatarMotion.STILL,
    val phase: Float = 0f, val points: List<GeneratedAvatarPoint> = emptyList(),
)
data class GeneratedAvatarFace(
    val x: Float = 0f, val y: Float = -.07f, val eyeSpacing: Float = .16f,
    val eyeWidth: Float = .12f, val eyeHeight: Float = .16f,
    val mouthY: Float = .18f, val mouthWidth: Float = .14f, val iris: Long = 0xFF58CBA9,
)
data class GeneratedAvatarForm(
    val id: String, val name: String, val description: String,
    val parts: List<GeneratedAvatarPart>, val face: GeneratedAvatarFace, val version: Int = 1,
)

object GeneratedAvatarForms {
    const val MAX_JSON = 32_768
    const val MAX_PARTS = 32
    const val MAX_REQUEST = 720
    const val MAX_SAVED = 24
    class SpecException(message: String) : IllegalArgumentException(message)

    /** Rejects malformed or unbounded output instead of silently drawing a different request. */
    fun parse(raw: String): GeneratedAvatarForm {
        if (raw.length > MAX_JSON) fail("Avatar drawing is too large (maximum $MAX_JSON characters)")
        val root = try {
            val tokenizer = JSONTokener(raw)
            val value = tokenizer.nextValue() as? JSONObject ?: fail("Avatar drawing must be a JSON object")
            if (tokenizer.nextClean() != '\u0000') fail("Avatar drawing contains trailing content")
            value
        } catch (e: SpecException) { throw e }
        catch (_: Exception) { fail("Avatar drawing is not valid JSON") }
        keys(root, setOf("version", "name", "description", "parts", "face"), "avatar")
        if (number(root, "version", 1f, 1f) != 1f) fail("Unsupported avatar version")
        val name = string(root, "name", 60)
        val description = string(root, "description", MAX_REQUEST, allowEmpty = true)
        val array = root.optJSONArray("parts") ?: fail("Avatar needs a parts array")
        if (array.length() !in 1..MAX_PARTS) fail("Avatar needs 1 to $MAX_PARTS parts")
        val parts = (0 until array.length()).map { index ->
            val p = array.optJSONObject(index) ?: fail("Part $index must be an object")
            keys(p, setOf("shape", "x", "y", "width", "height", "fill", "outline", "stroke", "rotation", "motion", "phase", "points"), "part $index")
            val shape = GeneratedAvatarShape.entries.firstOrNull { it.name.lowercase() == p.opt("shape") }
                ?: fail("Part $index shape must be ellipse, round_rect or polygon")
            val motion = if (!p.has("motion")) GeneratedAvatarMotion.STILL else
                parseMotion(p.opt("motion"), index)
            val x = number(p, "x", -.5f, .5f); val y = number(p, "y", -.5f, .5f)
            val width = number(p, "width", .01f, 1f); val height = number(p, "height", .01f, 1f)
            val rotation = number(p, "rotation", -180f, 180f, 0f)
            val stroke = number(p, "stroke", .002f, .025f, .012f)
            val angle = rotation * kotlin.math.PI.toFloat() / 180f
            val extentX = (abs(cos(angle)) * width + abs(sin(angle)) * height) * .5f + stroke * .5f
            val extentY = (abs(sin(angle)) * width + abs(cos(angle)) * height) * .5f + stroke * .5f
            if (abs(x) + extentX > .57f || abs(y) + extentY > .57f)
                fail("Part $index extends beyond the drawing boundary; move or shrink it within -0.57 to 0.57")
            val points = if (shape == GeneratedAvatarShape.POLYGON) {
                val values = p.optJSONArray("points") ?: fail("Polygon $index needs points")
                if (values.length() !in 3..24) fail("Polygon $index needs 3 to 24 points")
                (0 until values.length()).map { j ->
                    val point = values.optJSONArray(j) ?: fail("Polygon point must be an [x,y] pair")
                    if (point.length() != 2) fail("Polygon point must have exactly two coordinates")
                    fun coordinate(i: Int): Float {
                        val n = (point.opt(i) as? Number)?.toFloat() ?: fail("Polygon coordinates must be numbers")
                        if (!n.isFinite() || n !in -.5f.. .5f) fail("Polygon local coordinates must be -0.5 to 0.5")
                        return n
                    }
                    GeneratedAvatarPoint(coordinate(0), coordinate(1))
                }.also { pts ->
                    var area = 0f
                    pts.indices.forEach { j -> val a = pts[j]; val b = pts[(j + 1) % pts.size]; area += a.x * b.y - b.x * a.y }
                    if (abs(area) < .001f) fail("Polygon $index must enclose a visible area")
                }
            } else {
                if (p.has("points")) fail("Only polygon parts may contain points")
                emptyList()
            }
            GeneratedAvatarPart(shape, x, y, width, height, color(p, "fill"), color(p, "outline", 0xFF182032),
                stroke, rotation, motion, number(p, "phase", 0f, 6.284f, 0f), points)
        }
        val f = root.optJSONObject("face") ?: fail("Avatar needs a live face layout")
        keys(f, setOf("x", "y", "eye_spacing", "eye_width", "eye_height", "mouth_y", "mouth_width", "iris"), "face")
        val face = GeneratedAvatarFace(number(f, "x", -.35f, .35f), number(f, "y", -.35f, .35f),
            number(f, "eye_spacing", .06f, .4f), number(f, "eye_width", .04f, .25f), number(f, "eye_height", .04f, .3f),
            number(f, "mouth_y", .04f, .35f), number(f, "mouth_width", .04f, .35f), color(f, "iris", 0xFF58CBA9))
        if (abs(face.x) + (face.eyeSpacing + face.eyeWidth) * .5f > .53f ||
            abs(face.y) + face.eyeHeight * .5f > .5f || abs(face.y + face.mouthY) + face.mouthWidth * .35f > .53f ||
            abs(face.x) + face.mouthWidth * .5f > .53f) fail("Live eyes and mouth must fit within the drawing")
        val form = GeneratedAvatarForm("", name, description, parts, face)
        val id = com.meowmba.shared.bridge.BridgeCrypto.sha256Hex(toJson(form).encodeToByteArray())
        return form.copy(id = id)
    }

    fun toJson(form: GeneratedAvatarForm): String = JSONObject().apply {
        put("version", form.version); put("name", form.name); put("description", form.description)
        put("parts", JSONArray().apply { form.parts.forEach { part -> put(JSONObject().apply {
            put("shape", part.shape.name.lowercase()); put("x", part.x.toDouble()); put("y", part.y.toDouble())
            put("width", part.width.toDouble()); put("height", part.height.toDouble()); put("fill", hex(part.fill))
            put("outline", hex(part.outline)); put("stroke", part.stroke.toDouble()); put("rotation", part.rotation.toDouble())
            put("motion", part.motion.name.lowercase()); put("phase", part.phase.toDouble())
            if (part.shape == GeneratedAvatarShape.POLYGON) put("points", JSONArray().apply {
                part.points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
            })
        }) } })
        val f = form.face
        put("face", JSONObject().put("x", f.x.toDouble()).put("y", f.y.toDouble()).put("eye_spacing", f.eyeSpacing.toDouble())
            .put("eye_width", f.eyeWidth.toDouble()).put("eye_height", f.eyeHeight.toDouble()).put("mouth_y", f.mouthY.toDouble())
            .put("mouth_width", f.mouthWidth.toDouble()).put("iris", hex(f.iris)))
    }.let(::canonicalJson)

    // JSONObject ordering differs between Android and the JVM test library.
    // Stable IDs must survive both implementations and future JSON upgrades.
    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        // Drawing coordinates are Floats. Avoid expanding 0.1f into twenty
        // decimal digits and exhausting the bounded file budget on polygons.
        is Double -> value.toFloat().toString()
        is Number -> value.toString()
        else -> throw SpecException("Unsupported avatar JSON value")
    }

    private fun hex(value: Long): String = "#" + (value and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
    private fun keys(value: JSONObject, allowed: Set<String>, at: String) {
        val unknown = value.keys().asSequence().firstOrNull { it !in allowed }
        if (unknown != null) fail("Unsupported $at field: ${unknown.take(40)}")
    }
    private fun number(value: JSONObject, key: String, low: Float, high: Float, default: Float? = null): Float {
        if (!value.has(key) && default != null) return default
        val result = (value.opt(key) as? Number)?.toFloat() ?: fail("$key must be a number")
        if (!result.isFinite() || result !in low..high) fail("$key must be $low to $high")
        return result
    }
    private fun string(value: JSONObject, key: String, max: Int, allowEmpty: Boolean = false): String {
        val result = (value.opt(key) as? String)?.trim() ?: fail("$key must be text")
        if (result.length > max || (!allowEmpty && result.isEmpty()) || result.any { it.let { c -> c.code < 32 || c.code in 127..159 } }) fail("$key must be readable text, up to $max characters")
        return result
    }
    private fun color(value: JSONObject, key: String, default: Long? = null): Long {
        if (!value.has(key) && default != null) return default
        val raw = value.opt(key) as? String ?: fail("$key must be a #RRGGBB color")
        if (!Regex("#[0-9a-fA-F]{6}").matches(raw)) fail("$key must be a #RRGGBB color")
        return 0xFF000000 or raw.drop(1).toLong(16)
    }

    /**
     * Models prompted about "head/body/arms/legs" often emit motion "head",
     * which is not in the schema. Coerce known aliases so invent does not enter
     * repair loops that multiply LLM payloads and OOM the process.
     */
    private fun parseMotion(raw: Any?, index: Int): GeneratedAvatarMotion {
        val key = (raw as? String)?.trim()?.lowercase()
            ?: fail("Part $index has an unsupported motion")
        GeneratedAvatarMotion.entries.firstOrNull { it.name.lowercase() == key }?.let { return it }
        return when (key) {
            "head", "face", "neck" -> GeneratedAvatarMotion.STILL
            "torso", "chest", "trunk" -> GeneratedAvatarMotion.BODY
            "hand", "hands", "limb", "paw", "handle", "fin" -> GeneratedAvatarMotion.ARM
            "foot", "feet", "leg_pair" -> GeneratedAvatarMotion.LEG
            "wings" -> GeneratedAvatarMotion.WING
            "steam", "smoke", "hover", "bob" -> GeneratedAvatarMotion.FLOAT
            "ears", "horn", "horns" -> GeneratedAvatarMotion.ANTENNA
            else -> fail("Part $index has an unsupported motion")
        }
    }

    private fun fail(message: String): Nothing = throw SpecException(message)
}

/** Original QA examples are also useful prompt demonstrations; no preset limits the schema. */
object GeneratedAvatarExamples {
    fun coffeeCup(): GeneratedAvatarForm = make("Coffee captain", "An animated cream coffee cup with a coral sleeve and a looping handle", listOf(
        part(GeneratedAvatarShape.ELLIPSE, .30f, .02f, .27f, .32f, 0xFFFFDFB0),
        part(GeneratedAvatarShape.ELLIPSE, .32f, .02f, .12f, .18f, 0xFF344351),
        part(GeneratedAvatarShape.ROUND_RECT, 0f, .035f, .52f, .60f, 0xFFFFE8B9, GeneratedAvatarMotion.BODY),
        part(GeneratedAvatarShape.ROUND_RECT, 0f, .12f, .51f, .24f, 0xFFF87878, GeneratedAvatarMotion.BODY),
        part(GeneratedAvatarShape.ELLIPSE, 0f, -.25f, .54f, .14f, 0xFFE6BD85),
        part(GeneratedAvatarShape.ELLIPSE, 0f, -.258f, .44f, .085f, 0xFF624437),
        part(GeneratedAvatarShape.ELLIPSE, -.10f, -.41f, .055f, .14f, 0xFFDCE5D8, GeneratedAvatarMotion.FLOAT),
        part(GeneratedAvatarShape.ELLIPSE, .11f, -.43f, .05f, .15f, 0xFFDCE5D8, GeneratedAvatarMotion.FLOAT),
    ), GeneratedAvatarFace(y = -.065f, mouthY = .18f, iris = 0xFF28A896))

    fun spaceship(): GeneratedAvatarForm = make("Pocket starship", "A mint flying saucer with expressive cockpit eyes and tiny rocket feet", listOf(
        polygon(0f, .32f, .34f, .25f, 0xFFFFB75C, listOf(-.5f to -.5f, -.22f to .5f, 0f to .12f, .28f to .5f, .5f to -.5f), GeneratedAvatarMotion.TAIL),
        part(GeneratedAvatarShape.ELLIPSE, 0f, -.11f, .53f, .47f, 0xFF93DBD6, GeneratedAvatarMotion.BODY),
        part(GeneratedAvatarShape.ELLIPSE, 0f, .12f, .94f, .28f, 0xFF768DE7, GeneratedAvatarMotion.BODY),
        part(GeneratedAvatarShape.ELLIPSE, 0f, .06f, .92f, .23f, 0xFFB2E999, GeneratedAvatarMotion.BODY),
        part(GeneratedAvatarShape.ELLIPSE, -.32f, .08f, .09f, .05f, 0xFFFFD779),
        part(GeneratedAvatarShape.ELLIPSE, .32f, .08f, .09f, .05f, 0xFFFFD779),
        part(GeneratedAvatarShape.ELLIPSE, -.13f, .16f, .08f, .04f, 0xFFF48296),
        part(GeneratedAvatarShape.ELLIPSE, .13f, .16f, .08f, .04f, 0xFFF48296),
    ), GeneratedAvatarFace(y = -.14f, eyeSpacing = .19f, mouthY = .19f, iris = 0xFF7684E8))

    fun dragon(): GeneratedAvatarForm = make("Peach dragon", "A friendly turquoise dragon with peach wing membranes, little horns and a swishing tail", listOf(
        polygon(-.33f, -.05f, .35f, .48f, 0xFFF4A789, listOf(.5f to .5f, -.5f to .25f, -.3f to -.1f, -.5f to -.5f, .5f to -.08f), GeneratedAvatarMotion.WING),
        polygon(.33f, -.05f, .35f, .48f, 0xFFF4A789, listOf(-.5f to .5f, .5f to .25f, .3f to -.1f, .5f to -.5f, -.5f to -.08f), GeneratedAvatarMotion.WING),
        polygon(.27f, .24f, .45f, .25f, 0xFF479D9C, listOf(-.5f to -.5f, .1f to .1f, .5f to -.3f, .35f to .5f, -.5f to .5f), GeneratedAvatarMotion.TAIL),
        part(GeneratedAvatarShape.ELLIPSE, -.15f, .36f, .20f, .15f, 0xFF65B8AE, GeneratedAvatarMotion.LEG),
        part(GeneratedAvatarShape.ELLIPSE, .15f, .36f, .20f, .15f, 0xFF65B8AE, GeneratedAvatarMotion.LEG),
        part(GeneratedAvatarShape.ELLIPSE, 0f, .15f, .43f, .48f, 0xFF65B8AE, GeneratedAvatarMotion.BODY),
        part(GeneratedAvatarShape.ELLIPSE, 0f, .20f, .27f, .31f, 0xFFF9CE9A, GeneratedAvatarMotion.BODY),
        polygon(-.16f, -.35f, .13f, .20f, 0xFFFFDEAE, listOf(-.5f to -.5f, .5f to .5f, -.5f to .5f), GeneratedAvatarMotion.ANTENNA),
        polygon(.16f, -.35f, .13f, .20f, 0xFFFFDEAE, listOf(.5f to -.5f, .5f to .5f, -.5f to .5f), GeneratedAvatarMotion.ANTENNA),
        part(GeneratedAvatarShape.ELLIPSE, 0f, -.15f, .52f, .41f, 0xFF74C7B9, GeneratedAvatarMotion.BODY),
        part(GeneratedAvatarShape.ELLIPSE, 0f, -.035f, .37f, .18f, 0xFFB3DFB5, GeneratedAvatarMotion.BODY),
    ), GeneratedAvatarFace(y = -.19f, eyeSpacing = .20f, eyeWidth = .14f, eyeHeight = .17f, mouthY = .20f, iris = 0xFFFFAD64))

    private fun part(shape: GeneratedAvatarShape, x: Float, y: Float, w: Float, h: Float, fill: Long,
        motion: GeneratedAvatarMotion = GeneratedAvatarMotion.STILL) = GeneratedAvatarPart(shape, x, y, w, h, fill, motion = motion)
    private fun polygon(x: Float, y: Float, w: Float, h: Float, fill: Long, pts: List<Pair<Float, Float>>, motion: GeneratedAvatarMotion) =
        GeneratedAvatarPart(GeneratedAvatarShape.POLYGON, x, y, w, h, fill, motion = motion, points = pts.map { GeneratedAvatarPoint(it.first, it.second) })
    private fun make(name: String, description: String, parts: List<GeneratedAvatarPart>, face: GeneratedAvatarFace): GeneratedAvatarForm =
        GeneratedAvatarForms.parse(GeneratedAvatarForms.toJson(GeneratedAvatarForm("", name, description, parts, face)))
}
