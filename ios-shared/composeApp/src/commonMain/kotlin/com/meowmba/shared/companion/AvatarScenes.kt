package com.meowmba.shared.companion

import com.meowmba.shared.companion.MouthMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random

/**
 * Authored avatar performances, invented clips, and their sampling logic.
 * Ported from `ui/companion/AvatarPerformanceCatalog.kt` + `CustomAnimation.kt`
 * with `org.json` rewritten as kotlinx.serialization (org.json is JVM-only).
 * Behavior is preserved; the File-backed CustomAnimationStore stays a
 * platform seam (persistence comes from the host app).
 */

enum class PerformanceAccent {
    NONE, HEARTS, TEAR, SPARKLES, QUESTION, STAR, SLEEP, MUSIC, BUBBLE,
    SNOW, WIND, EMBER, LIGHTNING, WARMTH,
    BUTTERFLY, DETECTIVE, YARN, BALL, ACORN, SNACK, NEST, RAIN,
    MOUSE, BOOK, HAT_COWBOY, HAT_WIZARD, HAT_BERET,
}

/** A prop an invented clip may reference (bounded; full props port later). */
data class InventedProp(val id: String, val params: Map<String, String> = emptyMap())

/** Sparse per-frame pose overrides. Null = "this field is not animated". */
data class AnimFrame(
    val atMs: Int,
    val eyeOpen: Float? = null,
    val pupilSize: Float? = null,
    val pupilGazeUp: Float? = null,
    val headTiltDeg: Float? = null,
    val earPerk: Float? = null,
    val earDroopDeg: Float? = null,
    val happyEyes: Boolean? = null,
    val whiskerFlutter: Boolean? = null,
    val mouth: MouthMode? = null,
    val glowColorHex: Long? = null,
    val tongueOut: Float? = null,
    val bodyBounce: Float? = null,
    val tailWag: Float? = null,
    val sparkle: Float? = null,
)

/** A named invented clip: sorted frames, last frame's time is the duration. */
data class CustomAnimationSpec(
    val name: String,
    val frames: List<AnimFrame>,
    val props: List<InventedProp> = emptyList(),
) {
    val durationMs: Int get() = frames.last().atMs
}

data class AvatarPerformance(
    val id: String,
    val title: String,
    val category: String,
    val family: String,
    val accent: PerformanceAccent,
    val spec: CustomAnimationSpec,
    val aliases: Set<String>,
    val ambient: Boolean = false,
)

/**
 * Original authored performances for Memba, not an inventory of another robot.
 * Each phrase has preparation, action, a readable hold and neutral recovery.
 * Accents are illustrative; a star or celebration never establishes task success.
 * The caller owns eligibility, speech interruption and all privacy/body gates.
 */
object AvatarPerformanceCatalog {

    /** Custom clips may reuse a name; only these exact authored specs get extra prop channels. */
    fun fromSpec(spec: CustomAnimationSpec?): AvatarPerformance? = spec?.let { candidate ->
        resolve(candidate.name)?.takeIf { it.spec == candidate }
    }

    val entries: List<AvatarPerformance> = listOf(
        clip("warm_hello", "Warm Hello", "Social", "greeting", 2600,
            aliases = setOf("hello", "hi", "greet", "greeting"), beats = listOf(
                f(240, eye = 0.9f, tilt = -2f, perk = 0.94f),
                f(620, eye = 1.12f, pupil = 0.4f, perk = 1.3f, bounce = 0.18f),
                f(1040, eye = 0.82f, happy = true, tilt = 6f, mouth = MouthMode.HAPPY, wag = 0.3f),
                f(1430, eye = 0.82f, happy = true, tilt = 6f, mouth = MouthMode.HAPPY, wag = 0.3f),
                f(2050, eye = 0.96f, pupil = 0.32f, tilt = 1f, perk = 1.08f),
            )),
        clip("gentle_goodbye", "Gentle Goodbye", "Social", "farewell", 3000,
            aliases = setOf("bye", "goodbye", "farewell", "wave goodbye"), beats = listOf(
                f(320, eye = 0.86f, pupil = 0.34f, tilt = 3f),
                f(780, eye = 0.7f, happy = true, tilt = -7f, perk = 1.08f, wag = -0.28f),
                f(1170, eye = 0.7f, happy = true, tilt = 7f, perk = 1.08f, wag = 0.28f),
                f(1530, eye = 0.7f, happy = true, tilt = -4f, wag = -0.15f),
                f(1930, eye = 0.7f, happy = true, tilt = -4f, wag = -0.15f),
                f(2500, eye = 0.88f, tilt = -1f, droop = 3f),
            )),
        clip("sure_nod", "Sure Nod", "Conversation", "nod", 2200,
            aliases = setOf("yes", "nod", "acknowledge", "okay"), beats = listOf(
                f(220, eye = 1.06f, pupil = 0.34f, gaze = 0.12f, perk = 1.12f),
                f(560, eye = 0.82f, gaze = 0.02f, bounce = -0.14f),
                f(880, eye = 1.02f, gaze = 0.18f, perk = 1.12f, bounce = 0.08f),
                f(1210, eye = 1.02f, gaze = 0.18f, perk = 1.12f, bounce = 0.08f),
                f(1690, eye = 0.94f, gaze = 0.04f, mouth = MouthMode.HAPPY),
            )),
        clip("firm_no", "Firm No", "Conversation", "shake", 2700,
            aliases = setOf("no", "refuse", "head shake", "shake no"), beats = listOf(
                f(300, eye = 0.84f, pupil = 0.24f, perk = 0.9f),
                f(650, eye = 0.72f, tilt = -11f, droop = 5f),
                f(1000, eye = 0.72f, tilt = 11f, droop = 5f),
                f(1310, eye = 0.76f, tilt = -6f, droop = 3f),
                f(1690, eye = 0.76f, tilt = -6f, droop = 3f),
                f(2190, eye = 0.9f, tilt = -1f, perk = 0.98f),
            )),
        clip("listening_lean", "Listening Lean", "Conversation", "attentive", 2600,
            ambient = true, aliases = setOf("listen", "listening", "attentive"), beats = listOf(
                f(250, eye = 0.92f, perk = 1.1f),
                f(650, pupil = 0.38f, gaze = 0.08f, tilt = 2f, perk = 1.16f),
                f(1300, pupil = 0.38f, gaze = 0.08f, tilt = 2f, perk = 1.16f),
                f(2100, eye = 0.95f, pupil = 0.3f, gaze = 0.02f, tilt = 0.7f),
            )),
        clip("thoughtful_pause", "Thoughtful Pause", "Conversation", "ponder", 3000,
            ambient = true, aliases = setOf("consider", "consideration", "thinking", "think"), beats = listOf(
                f(300, pupil = 0.32f, perk = 0.95f),
                f(850, eye = 0.9f, pupil = 0.34f, gaze = 0.22f, tilt = -3f),
                f(1600, eye = 0.9f, pupil = 0.34f, gaze = 0.22f, tilt = -3f),
                f(2400, eye = 0.96f, gaze = 0.06f, tilt = -1f),
            )),
        clip("little_insight", "Little Insight", "Conversation", "discovery", 2800,
            accent = PerformanceAccent.STAR, aliases = setOf("insight", "aha", "idea"), beats = listOf(
                f(370, eye = 0.78f, pupil = 0.3f, gaze = 0.17f, tilt = -5f, perk = 0.94f),
                f(890, eye = 1.25f, pupil = 0.48f, perk = 1.4f, bounce = 0.12f),
                f(1210, eye = 1.08f, pupil = 0.4f, perk = 1.2f, mouth = MouthMode.HAPPY),
                f(1720, eye = 1.08f, pupil = 0.4f, perk = 1.2f, mouth = MouthMode.HAPPY),
                f(2320, eye = 0.96f, pupil = 0.32f, perk = 1.04f),
            )),
        clip("puzzled_tilt", "Puzzled Tilt", "Conversation", "ponder", 3300,
            accent = PerformanceAccent.QUESTION, aliases = setOf("confused", "puzzled", "confusion"), beats = listOf(
                f(330, eye = 0.92f, pupil = 0.36f, perk = 1.12f),
                f(860, eye = 0.83f, pupil = 0.39f, gaze = 0.15f, tilt = 13f, perk = 0.92f),
                f(1320, eye = 0.83f, pupil = 0.39f, gaze = 0.15f, tilt = 13f, perk = 0.92f),
                f(1840, eye = 1.05f, pupil = 0.35f, tilt = -7f, droop = 4f),
                f(2280, eye = 1.05f, pupil = 0.35f, tilt = -7f, droop = 4f),
                f(2830, eye = 0.95f, tilt = -2f),
            )),
        clip("content_smile", "Contented Smile", "Joy", "content", 2500,
            ambient = true, aliases = setOf("pleased", "content", "smile"), beats = listOf(
                f(280, eye = 0.95f, perk = 0.98f),
                f(700, eye = 0.72f, happy = true, perk = 1.02f, mouth = MouthMode.HAPPY),
                f(1450, eye = 0.72f, happy = true, perk = 1.02f, mouth = MouthMode.HAPPY),
                f(2000, eye = 0.9f, pupil = 0.3f),
            )),
        clip("eager_bounce", "Eager Bounce", "Joy", "bounce", 2800,
            accent = PerformanceAccent.SPARKLES, aliases = setOf("excited", "excitement", "eager"), beats = listOf(
                f(240, eye = 0.88f, perk = 0.86f, bounce = -0.22f),
                f(580, eye = 1.3f, pupil = 0.5f, perk = 1.4f, bounce = 0.5f, wag = 0.4f),
                f(900, eye = 0.92f, happy = true, bounce = -0.1f, wag = -0.35f),
                f(1240, eye = 1.15f, pupil = 0.42f, bounce = 0.28f, wag = 0.2f),
                f(1560, eye = 0.85f, happy = true, mouth = MouthMode.HAPPY, perk = 1.15f),
                f(1920, eye = 0.85f, happy = true, mouth = MouthMode.HAPPY, perk = 1.15f),
                f(2350, eye = 0.95f, perk = 1.05f),
            )),
        clip("tiny_triumph", "Tiny Triumph", "Joy", "flourish", 3400,
            accent = PerformanceAccent.STAR, aliases = setOf("celebrate", "celebration", "triumph"), beats = listOf(
                f(350, eye = 0.83f, gaze = 0.05f, perk = 0.9f, bounce = -0.16f),
                f(830, eye = 1.18f, pupil = 0.44f, gaze = 0.3f, perk = 1.36f, bounce = 0.34f),
                f(1320, eye = 0.7f, happy = true, tilt = -9f, mouth = MouthMode.HAPPY, wag = -0.3f),
                f(1790, eye = 0.7f, happy = true, tilt = 9f, mouth = MouthMode.HAPPY, wag = 0.3f),
                f(2220, eye = 0.7f, happy = true, tilt = 9f, mouth = MouthMode.HAPPY, wag = 0.3f),
                f(2820, eye = 0.9f, gaze = 0.05f, tilt = 2f, perk = 1.08f),
            )),
        clip("proud_pose", "Proud Pose", "Joy", "stance", 3100,
            accent = PerformanceAccent.SPARKLES, aliases = setOf("proud", "pride", "take a bow"), beats = listOf(
                f(300, eye = 0.87f, gaze = 0.06f, tilt = -3f, perk = 0.96f),
                f(780, eye = 0.72f, pupil = 0.3f, gaze = 0.26f, tilt = 7f, perk = 1.24f, bounce = 0.12f),
                f(1510, eye = 0.72f, pupil = 0.3f, gaze = 0.26f, tilt = 7f, perk = 1.24f, bounce = 0.12f),
                f(2010, eye = 0.88f, happy = true, gaze = 0.08f, tilt = 3f, mouth = MouthMode.HAPPY),
                f(2630, eye = 0.96f, tilt = 1f),
            )),
        clip("bashful_glance", "Bashful Glance", "Affection", "bashful", 3100,
            aliases = setOf("shy", "bashful", "blush"), beats = listOf(
                f(300, eye = 1.04f, pupil = 0.43f, perk = 1.08f),
                f(760, eye = 0.7f, pupil = 0.36f, tilt = -10f, droop = 9f, bounce = -0.08f),
                f(1240, eye = 0.7f, pupil = 0.36f, tilt = -10f, droop = 9f, bounce = -0.08f),
                f(1700, eye = 0.94f, pupil = 0.4f, tilt = -5f, droop = 4f),
                f(2110, eye = 0.78f, happy = true, tilt = -3f, mouth = MouthMode.HAPPY),
                f(2600, eye = 0.94f, tilt = -1f),
            )),
        clip("fond_nuzzle", "Fond Nuzzle", "Affection", "nuzzle", 3300,
            aliases = setOf("affection", "affectionate", "nuzzle", "cuddle"), beats = listOf(
                f(360, eye = 0.85f, pupil = 0.38f, tilt = 3f),
                f(930, eye = 0.36f, happy = true, tilt = 12f, perk = 0.86f, droop = 5f, bounce = 0.08f),
                f(1510, eye = 0.36f, happy = true, tilt = 12f, perk = 0.86f, droop = 5f, bounce = 0.08f),
                f(2060, eye = 0.65f, happy = true, tilt = 5f, flutter = true, mouth = MouthMode.HAPPY),
                f(2750, eye = 0.9f, pupil = 0.31f, tilt = 1f),
            )),
        clip("heart_flutter", "Heart Flutter", "Affection", "nuzzle", 3600,
            accent = PerformanceAccent.HEARTS, aliases = setOf("hearts", "heart", "love"), beats = listOf(
                f(320, eye = 0.86f, pupil = 0.38f, bounce = -0.08f),
                f(860, eye = 1.04f, pupil = 0.58f, tilt = -5f, perk = 1.2f, bounce = 0.12f),
                f(1340, eye = 0.62f, happy = true, tilt = 5f, mouth = MouthMode.HAPPY, wag = 0.18f),
                f(1830, eye = 0.62f, happy = true, tilt = 5f, mouth = MouthMode.HAPPY, wag = 0.18f),
                f(2430, eye = 0.8f, pupil = 0.4f, tilt = -2f, perk = 1.08f),
                f(3030, eye = 0.95f, pupil = 0.32f),
            )),
        clip("wonder_widen", "Wonder Widen", "Feelings", "wonder", 2400,
            accent = PerformanceAccent.SPARKLES, aliases = setOf("surprised", "surprise", "wonder"), beats = listOf(
                f(280, eye = 0.92f, gaze = 0.1f, perk = 1.08f),
                f(680, eye = 1.42f, pupil = 0.6f, gaze = 0.2f, perk = 1.4f),
                f(1210, eye = 1.42f, pupil = 0.6f, gaze = 0.2f, perk = 1.4f),
                f(1690, eye = 1.12f, pupil = 0.43f, gaze = 0.07f, tilt = 3f, perk = 1.17f),
                f(2050, eye = 1.03f, pupil = 0.33f, tilt = 1f),
            )),
        clip("startled_peek", "Startled Peek", "Feelings", "recoil", 2300,
            aliases = setOf("startled", "startle", "frightened"), beats = listOf(
                f(210, eye = 0.87f, pupil = 0.33f, tilt = -2f),
                f(440, eye = 1.5f, pupil = 0.63f, perk = 1.5f, bounce = 0.22f),
                f(750, eye = 0.34f, pupil = 0.45f, perk = 0.6f, droop = 16f, bounce = -0.2f),
                f(1050, eye = 0.34f, pupil = 0.45f, perk = 0.6f, droop = 16f, bounce = -0.2f),
                f(1500, eye = 0.83f, pupil = 0.4f, tilt = 4f, perk = 0.9f, droop = 4f),
                f(1930, eye = 0.95f, pupil = 0.32f, tilt = 1f),
            )),
        clip("patient_huff", "Patient Huff", "Feelings", "squint", 2900,
            aliases = setOf("annoyed", "annoyance", "huff", "grumpy"), beats = listOf(
                f(360, eye = 0.88f, pupil = 0.23f, perk = 0.9f),
                f(870, eye = 0.48f, pupil = 0.2f, tilt = -6f, perk = 0.64f, droop = 14f),
                f(1440, eye = 0.48f, pupil = 0.2f, tilt = -6f, perk = 0.64f, droop = 14f),
                f(1800, eye = 0.58f, tilt = 2f, perk = 0.76f, droop = 8f, bounce = -0.12f),
                f(2390, eye = 0.86f, tilt = 1f, perk = 0.94f, droop = 2f),
            )),
        clip("quiet_sadness", "Quiet Sadness", "Feelings", "downcast", 3700,
            accent = PerformanceAccent.TEAR, aliases = setOf("sad", "sadness", "tear"), beats = listOf(
                f(450, eye = 0.88f, pupil = 0.35f, perk = 0.9f, droop = 6f),
                f(1160, eye = 0.57f, pupil = 0.43f, tilt = -5f, perk = 0.62f, droop = 25f, bounce = -0.18f),
                f(1980, eye = 0.57f, pupil = 0.43f, tilt = -5f, perk = 0.62f, droop = 25f, bounce = -0.18f),
                f(2710, eye = 0.76f, pupil = 0.36f, tilt = -2f, perk = 0.82f, droop = 11f, bounce = -0.06f),
                f(3250, eye = 0.94f, pupil = 0.3f, droop = 3f),
            )),
        clip("sorry_dip", "Sorry Dip", "Feelings", "nod", 3100,
            aliases = setOf("sorry", "apology", "apologize"), beats = listOf(
                f(330, eye = 0.9f, pupil = 0.36f, tilt = 2f, perk = 0.92f),
                f(890, eye = 0.62f, pupil = 0.38f, tilt = -4f, perk = 0.72f, droop = 16f, bounce = -0.2f),
                f(1550, eye = 0.62f, pupil = 0.38f, tilt = -4f, perk = 0.72f, droop = 16f, bounce = -0.2f),
                f(2160, eye = 0.89f, pupil = 0.41f, gaze = 0.08f, perk = 0.94f, droop = 5f),
                f(2620, eye = 0.96f, pupil = 0.32f, gaze = 0.02f),
            )),
        clip("drowsy_drift", "Drowsy Drift", "Rest", "drift", 4100,
            accent = PerformanceAccent.SLEEP, aliases = setOf("sleep", "sleepy", "drowsy"), beats = listOf(
                f(480, eye = 0.78f, perk = 0.94f, droop = 5f),
                f(1150, eye = 0.38f, tilt = 4f, perk = 0.8f, droop = 16f, bounce = -0.08f),
                f(1870, eye = 0.07f, tilt = 9f, perk = 0.68f, droop = 25f, bounce = -0.15f),
                f(2480, eye = 0.07f, tilt = 9f, perk = 0.68f, droop = 25f, bounce = -0.15f),
                f(3140, eye = 0.62f, tilt = 4f, perk = 0.9f, droop = 10f),
                f(3670, eye = 0.9f, tilt = 1f, droop = 2f),
            )),
        clip("roomy_yawn", "Roomy Yawn", "Rest", "stretch", 3900,
            accent = PerformanceAccent.SLEEP, aliases = setOf("yawn", "yawning"), beats = listOf(
                f(430, eye = 0.78f, perk = 0.92f, droop = 6f, bounce = -0.04f),
                f(990, eye = 0.36f, gaze = 0.18f, perk = 0.76f, droop = 16f, mouth = MouthMode.SPEAKING),
                f(1500, eye = 0.06f, gaze = 0.28f, perk = 0.63f, droop = 26f, mouth = MouthMode.SPEAKING, tongue = 0.24f),
                f(2110, eye = 0.06f, gaze = 0.28f, perk = 0.63f, droop = 26f, mouth = MouthMode.SPEAKING, tongue = 0.24f),
                f(2740, eye = 0.48f, gaze = 0.06f, perk = 0.84f, droop = 12f),
                f(3370, eye = 0.86f, perk = 0.96f, droop = 3f),
            )),
        clip("morning_wake", "Morning Wake", "Rest", "stretch", 4000,
            accent = PerformanceAccent.SPARKLES, aliases = setOf("wake", "wake up", "good morning"), beats = listOf(
                f(500, eye = 0.36f, perk = 0.84f, droop = 12f, bounce = -0.08f),
                f(1060, eye = 0.09f, gaze = 0.12f, perk = 0.74f, droop = 18f),
                f(1690, eye = 0.82f, pupil = 0.42f, gaze = 0.3f, perk = 1.35f, bounce = 0.22f),
                f(2280, eye = 0.82f, pupil = 0.42f, gaze = 0.3f, perk = 1.35f, bounce = 0.22f),
                f(2870, eye = 1.14f, pupil = 0.38f, gaze = 0.1f, perk = 1.16f, mouth = MouthMode.HAPPY),
                f(3490, eye = 1.02f, pupil = 0.3f, perk = 1.04f),
            )),
        clip("tiny_sneeze", "Tiny Sneeze", "Quirks", "sneeze", 2600,
            aliases = setOf("sneeze", "achoo"), beats = listOf(
                f(260, eye = 0.87f, gaze = 0.1f, perk = 1.08f, flutter = true),
                f(620, eye = 0.64f, gaze = 0.27f, perk = 1.23f, bounce = 0.12f),
                f(960, eye = 0.64f, gaze = 0.27f, perk = 1.23f, bounce = 0.12f),
                f(1140, eye = 0.04f, tilt = -8f, perk = 0.68f, mouth = MouthMode.SPEAKING, bounce = -0.32f),
                f(1470, eye = 1.1f, pupil = 0.4f, tilt = 3f, perk = 1.15f, bounce = 0.1f),
                f(2040, eye = 0.91f, pupil = 0.31f, tilt = 1f),
            )),
        clip("hiccup_hop", "Hiccup Hop", "Quirks", "bounce", 2800,
            accent = PerformanceAccent.BUBBLE, aliases = setOf("hiccup", "hiccups"), beats = listOf(
                f(340, eye = 0.93f, tilt = 2f, perk = 0.95f),
                f(700, eye = 1.28f, pupil = 0.44f, perk = 1.3f, mouth = MouthMode.SPEAKING, bounce = 0.27f),
                f(950, eye = 0.86f, tilt = -3f, bounce = -0.08f),
                f(1350, eye = 0.86f, tilt = -3f, bounce = -0.08f),
                f(1580, eye = 1.15f, pupil = 0.37f, perk = 1.18f, bounce = 0.15f),
                f(2130, eye = 0.94f, tilt = 1f, perk = 1.04f),
            )),
        clip("curious_sniff", "Curious Sniff", "Quirks", "inspect", 2300,
            ambient = true, aliases = setOf("sniff", "sniffing"), beats = listOf(
                f(200, pupil = 0.34f, perk = 1.08f),
                f(520, pupil = 0.36f, gaze = 0.08f, perk = 1.18f, flutter = true),
                f(780, pupil = 0.36f, gaze = 0.08f, perk = 1.18f, flutter = true),
                f(1080, pupil = 0.34f, gaze = 0.06f, tilt = 1.5f, perk = 1.08f),
                f(1320, pupil = 0.34f, gaze = 0.1f, perk = 1.12f, flutter = true),
                f(1510, pupil = 0.34f, gaze = 0.1f, perk = 1.12f, flutter = true),
                f(1900, pupil = 0.3f, gaze = 0.03f),
            )),
        clip("slow_blink", "Slow Blink", "Play", "blink", 2800,
            ambient = true, aliases = setOf("slowblink", "blink slowly", "trust blink"), beats = listOf(
                f(350, eye = 0.9f, pupil = 0.3f),
                f(900, eye = 0.08f, pupil = 0.3f),
                f(1450, eye = 0.08f, pupil = 0.3f),
                f(2150, eye = 0.68f, pupil = 0.29f),
            )),
        clip("playful_wink", "Playful Wink", "Play", "wink", 2100,
            aliases = setOf("wink", "winking"), beats = listOf(
                f(240, eye = 1.08f, pupil = 0.34f, tilt = -3f),
                f(640, eye = 0.12f, tilt = 8f, perk = 1.12f, mouth = MouthMode.HAPPY),
                f(940, eye = 0.12f, tilt = 8f, perk = 1.12f, mouth = MouthMode.HAPPY),
                f(1350, eye = 0.93f, pupil = 0.36f, tilt = 5f, mouth = MouthMode.HAPPY),
                f(1720, eye = 1.01f, pupil = 0.3f, tilt = 1f),
            )),
        clip("ear_curiosity", "Ear Curiosity", "Play", "ear", 2500,
            ambient = true, aliases = setOf("earplay", "ear play", "play with your ears"), beats = listOf(
                f(260, tilt = -0.7f, perk = 0.94f),
                f(620, pupil = 0.32f, tilt = 1.5f, perk = 1.18f),
                f(990, pupil = 0.32f, tilt = 1.5f, perk = 1.18f),
                f(1320, pupil = 0.31f, tilt = -1.7f, perk = 0.9f),
                f(1600, pupil = 0.31f, tilt = -1.7f, perk = 0.9f),
                f(2100, tilt = 0.5f, perk = 1.05f),
            )),
        clip("pocket_groove", "Pocket Groove", "Play", "rhythm", 4600,
            accent = PerformanceAccent.MUSIC, aliases = setOf("dance", "music", "groove", "dance to music"), beats = listOf(
                f(340, eye = 0.86f, tilt = -3f, perk = 0.9f, bounce = -0.15f),
                f(760, eye = 0.82f, happy = true, tilt = 12f, perk = 1.18f, bounce = 0.27f, wag = 0.45f),
                f(1190, eye = 0.82f, happy = true, tilt = -12f, perk = 1.08f, bounce = -0.12f, wag = -0.45f),
                f(1630, eye = 1.12f, pupil = 0.38f, tilt = 7f, perk = 1.3f, bounce = 0.36f, wag = 0.3f),
                f(2080, eye = 0.8f, happy = true, tilt = -8f, bounce = -0.08f, wag = -0.25f),
                f(2530, eye = 0.8f, happy = true, tilt = 5f, perk = 1.12f, bounce = 0.18f, wag = 0.2f),
                f(2990, eye = 0.7f, happy = true, tilt = -5f, mouth = MouthMode.HAPPY, bounce = 0.05f),
                f(3440, eye = 0.7f, happy = true, tilt = -5f, mouth = MouthMode.HAPPY, bounce = 0.05f),
                f(4030, eye = 0.94f, tilt = -1f, perk = 1.04f),
            )),
        clip("peekaboo", "Peekaboo", "Play", "peek", 3500,
            accent = PerformanceAccent.BUBBLE, aliases = setOf("peek a boo", "peek"), beats = listOf(
                f(310, eye = 0.82f, pupil = 0.35f, tilt = -5f, bounce = -0.08f),
                f(850, eye = 0.06f, tilt = -10f, perk = 0.7f, droop = 15f, bounce = -0.55f),
                f(1370, eye = 0.06f, tilt = -10f, perk = 0.7f, droop = 15f, bounce = -0.55f),
                f(1700, eye = 1.35f, pupil = 0.55f, tilt = 5f, perk = 1.38f, bounce = 0.28f),
                f(2200, eye = 0.74f, happy = true, tilt = 3f, mouth = MouthMode.HAPPY),
                f(2630, eye = 0.74f, happy = true, tilt = 3f, mouth = MouthMode.HAPPY),
                f(3070, eye = 0.95f, tilt = 1f),
            )),
        clip("little_hide", "Little Hide", "Play", "hide", 3700,
            aliases = setOf("hide", "hiding", "shy hide"), beats = listOf(
                f(380, eye = 0.88f, pupil = 0.4f, tilt = 6f, perk = 0.9f),
                f(970, eye = 0.28f, tilt = 12f, perk = 0.62f, droop = 22f, bounce = -0.8f),
                f(1730, eye = 0.28f, tilt = 12f, perk = 0.62f, droop = 22f, bounce = -0.8f),
                f(2220, eye = 0.68f, pupil = 0.42f, tilt = 7f, perk = 0.86f, droop = 8f, bounce = -0.3f),
                f(2740, eye = 0.88f, pupil = 0.34f, tilt = 3f, perk = 0.96f, bounce = -0.08f),
                f(3260, eye = 0.96f, tilt = 1f),
            )),
        clip("snow_wonder", "Snow Wonder", "Weather", "snowfall", 3800,
            accent = PerformanceAccent.SNOW, aliases = setOf("snow", "snowfall"), beats = listOf(
                f(370, eye = 0.96f, pupil = 0.34f, gaze = 0.08f, perk = 1.1f),
                f(1020, eye = 1.18f, pupil = 0.48f, gaze = 0.48f, tilt = -6f, perk = 1.26f),
                f(1680, eye = 1.18f, pupil = 0.48f, gaze = 0.48f, tilt = -6f, perk = 1.26f),
                f(2250, eye = 0.78f, happy = true, gaze = 0.22f, tilt = 5f, mouth = MouthMode.HAPPY),
                f(2820, eye = 1.04f, pupil = 0.37f, gaze = 0.1f, tilt = 2f, perk = 1.1f),
                f(3380, eye = 0.97f, pupil = 0.3f, gaze = 0.02f),
            )),
        clip("breezy_whiskers", "Breezy Whiskers", "Weather", "breeze", 3500,
            accent = PerformanceAccent.WIND, aliases = setOf("wind", "breeze", "windy"), beats = listOf(
                f(280, eye = 0.88f, tilt = 3f, perk = 0.9f, flutter = true),
                f(810, eye = 0.54f, tilt = -12f, perk = 0.66f, droop = 10f, flutter = true, wag = -0.3f),
                f(1300, eye = 0.54f, tilt = -12f, perk = 0.66f, droop = 10f, flutter = true, wag = -0.3f),
                f(1770, eye = 0.76f, tilt = 7f, perk = 1.08f, flutter = true, wag = 0.15f),
                f(2190, eye = 0.87f, tilt = -4f, perk = 0.93f, flutter = true),
                f(2910, eye = 0.95f, tilt = -1f, perk = 1.04f),
            )),
        clip("warm_embers", "Warm Embers", "Weather", "firelight", 4200,
            accent = PerformanceAccent.EMBER, aliases = setOf("fire", "embers", "firelight"), beats = listOf(
                f(460, eye = 0.91f, pupil = 0.34f, tilt = -2f, perk = 1.08f),
                f(1130, eye = 0.72f, pupil = 0.38f, tilt = 5f, perk = 0.9f, droop = 4f, bounce = 0.06f),
                f(1880, eye = 0.72f, pupil = 0.38f, tilt = 5f, perk = 0.9f, droop = 4f, bounce = 0.06f),
                f(2510, eye = 0.5f, happy = true, tilt = 2f, perk = 0.88f, mouth = MouthMode.HAPPY),
                f(3160, eye = 0.79f, pupil = 0.33f, tilt = -2f, perk = 0.96f),
                f(3720, eye = 0.96f, tilt = -0.5f),
            )),
        clip("storm_startle", "Storm Startle", "Weather", "recoil", 3000,
            accent = PerformanceAccent.LIGHTNING, aliases = setOf("lightning", "thunder", "storm"), beats = listOf(
                f(360, eye = 0.85f, pupil = 0.35f, gaze = 0.22f, tilt = 4f, perk = 1.16f),
                f(750, eye = 1.46f, pupil = 0.68f, tilt = -7f, perk = 1.48f, bounce = 0.24f),
                f(1090, eye = 0.26f, pupil = 0.48f, tilt = -4f, perk = 0.56f, droop = 23f, bounce = -0.27f),
                f(1480, eye = 0.26f, pupil = 0.48f, tilt = -4f, perk = 0.56f, droop = 23f, bounce = -0.27f),
                f(1980, eye = 0.94f, pupil = 0.44f, gaze = 0.08f, tilt = 5f, perk = 0.92f, droop = 6f),
                f(2530, eye = 0.97f, pupil = 0.32f, tilt = 1f),
            )),
        clip("sloth_stretch", "Sloth Stretch", "Character", "sloth", 5700,
            aliases = setOf("sloth", "act sloth", "act like a sloth"), beats = listOf(
                f(690, eye = 0.81f, pupil = 0.32f, tilt = -3f, perk = 0.92f, droop = 4f),
                f(1580, eye = 0.53f, gaze = 0.1f, tilt = -8f, perk = 0.82f, droop = 10f, bounce = -0.1f),
                f(2640, eye = 0.38f, gaze = 0.26f, tilt = 9f, perk = 0.77f, droop = 14f, bounce = 0.18f),
                f(3490, eye = 0.38f, gaze = 0.26f, tilt = 9f, perk = 0.77f, droop = 14f, bounce = 0.18f),
                f(4280, eye = 0.69f, gaze = 0.09f, tilt = 4f, perk = 0.9f, droop = 5f, bounce = 0.06f),
                f(5110, eye = 0.93f, tilt = 1f, perk = 0.98f),
            )),
        clip("warm_day", "Warm Day", "Weather", "heat", 4000,
            accent = PerformanceAccent.WARMTH, aliases = setOf("high temperature", "hot day", "warm weather", "too hot"), beats = listOf(
                f(420, eye = 0.87f, perk = 0.92f, droop = 5f),
                f(1100, eye = 0.58f, pupil = 0.25f, tilt = -6f, perk = 0.69f, droop = 18f, tongue = 0.28f),
                f(1690, eye = 0.58f, pupil = 0.25f, tilt = -6f, perk = 0.69f, droop = 18f, tongue = 0.28f),
                f(2210, eye = 0.65f, tilt = 5f, perk = 0.78f, droop = 12f, mouth = MouthMode.SPEAKING, bounce = -0.08f),
                f(2830, eye = 0.83f, tilt = 2f, perk = 0.9f, droop = 5f),
                f(3490, eye = 0.96f, tilt = 0.5f, perk = 0.98f),
            )),
        clip("cheeky_puff", "Cheeky Puff", "Quirks", "puff", 2700,
            accent = PerformanceAccent.BUBBLE, aliases = setOf("fart", "puff", "cheeky"), beats = listOf(
                f(320, eye = 0.85f, pupil = 0.3f, tilt = 4f, perk = 0.9f, bounce = -0.12f),
                f(760, eye = 0.35f, tilt = -5f, perk = 0.8f, bounce = -0.23f),
                f(1080, eye = 1.17f, pupil = 0.44f, tilt = 3f, perk = 1.24f, bounce = 0.15f),
                f(1490, eye = 0.72f, happy = true, tilt = -7f, mouth = MouthMode.HAPPY),
                f(1870, eye = 0.72f, happy = true, tilt = -7f, mouth = MouthMode.HAPPY),
                f(2260, eye = 0.96f, tilt = -2f, perk = 1.03f),
            )),
        clip("butterfly_chase", "Butterfly Chase", "Scenes", "pursuit", 5800,
            accent = PerformanceAccent.BUTTERFLY,
            aliases = setOf("chase a butterfly", "chase the butterfly", "follow a butterfly"), beats = listOf(
                f(420, eye = 0.88f, pupil = 0.34f, gaze = 0.14f, tilt = -3f, perk = 1.12f),
                f(1050, eye = 1.24f, pupil = 0.5f, gaze = 0.62f, tilt = -7f, perk = 1.35f, bounce = 0.1f),
                f(1650, eye = 1.08f, pupil = 0.44f, gaze = 0.35f, tilt = 10f, perk = 1.22f),
                f(2150, eye = 0.67f, pupil = 0.48f, gaze = 0.18f, tilt = 7f, perk = 0.8f, bounce = -0.22f),
                f(2580, eye = 1.28f, pupil = 0.55f, gaze = 0.58f, tilt = -10f, perk = 1.4f, bounce = 0.38f),
                f(3050, eye = 1.12f, pupil = 0.46f, gaze = 0.3f, tilt = -3f, perk = 1.22f, bounce = 0.08f),
                f(3750, eye = 1.12f, pupil = 0.46f, gaze = 0.3f, tilt = -3f, perk = 1.22f, bounce = 0.08f),
                f(4550, eye = 0.72f, happy = true, gaze = 0.32f, tilt = 5f, flutter = true, mouth = MouthMode.HAPPY),
                f(5270, eye = 0.96f, pupil = 0.31f, gaze = 0.06f, tilt = 1f, perk = 1.06f),
            )),
        clip("detective", "Detective", "Scenes", "investigation", 5600,
            accent = PerformanceAccent.DETECTIVE,
            aliases = setOf("be a detective", "play detective", "investigate a clue", "inspect a clue"), beats = listOf(
                f(460, eye = 0.88f, pupil = 0.34f, tilt = -4f, perk = 1.12f),
                f(1120, eye = 0.55f, pupil = 0.42f, tilt = -9f, perk = 1.25f, bounce = -0.06f),
                f(1860, eye = 0.62f, pupil = 0.46f, gaze = 0.08f, tilt = 8f, perk = 1.3f, flutter = true),
                f(2490, eye = 0.45f, pupil = 0.38f, tilt = 3f, perk = 1.1f, droop = 4f),
                f(2920, eye = 1.18f, pupil = 0.51f, gaze = 0.16f, tilt = -2f, perk = 1.42f, bounce = 0.1f),
                f(3550, eye = 1.18f, pupil = 0.51f, gaze = 0.16f, tilt = -2f, perk = 1.42f, bounce = 0.1f),
                f(4230, eye = 0.76f, happy = true, tilt = 5f, perk = 1.16f, mouth = MouthMode.HAPPY),
                f(5030, eye = 0.96f, pupil = 0.31f, tilt = 1f, perk = 1.04f),
            )),
        clip("yarn_play", "Yarn Play", "Scenes", "animal_play", 5400,
            accent = PerformanceAccent.YARN,
            aliases = setOf("play with yarn", "play with a yarn ball", "bat the yarn", "yarn pounce"), beats = listOf(
                f(360, eye = 0.86f, pupil = 0.4f, tilt = -5f, perk = 1.14f, bounce = -0.08f),
                f(1030, eye = 0.54f, pupil = 0.54f, tilt = -8f, perk = 0.82f, bounce = -0.27f, wag = -0.18f),
                f(1580, eye = 1.2f, pupil = 0.58f, tilt = 9f, perk = 1.36f, bounce = 0.33f, wag = 0.3f),
                f(2110, eye = 0.83f, pupil = 0.43f, tilt = -11f, perk = 1.13f, bounce = -0.06f, wag = -0.22f),
                f(2690, eye = 1.08f, pupil = 0.49f, tilt = 6f, perk = 1.27f, bounce = 0.18f, wag = 0.25f),
                f(3210, eye = 0.64f, happy = true, tilt = 4f, flutter = true, mouth = MouthMode.HAPPY, wag = 0.1f),
                f(3860, eye = 0.64f, happy = true, tilt = 4f, flutter = true, mouth = MouthMode.HAPPY, wag = 0.1f),
                f(4770, eye = 0.94f, pupil = 0.32f, tilt = 1f, perk = 1.04f),
            )),
        clip("ball_play", "Ball Play", "Scenes", "animal_play", 5100,
            accent = PerformanceAccent.BALL,
            aliases = setOf("play with a ball", "play with the ball", "catch a ball", "ball bounce"), beats = listOf(
                f(390, eye = 1.06f, pupil = 0.39f, gaze = 0.14f, perk = 1.18f, wag = 0.2f),
                f(980, eye = 1.22f, pupil = 0.52f, gaze = 0.55f, tilt = 5f, perk = 1.4f, bounce = 0.12f, wag = -0.35f),
                f(1430, eye = 0.84f, pupil = 0.48f, gaze = 0.25f, tilt = -4f, perk = 0.94f, bounce = -0.19f, wag = 0.3f),
                f(1810, eye = 1.3f, pupil = 0.55f, gaze = 0.65f, perk = 1.5f, mouth = MouthMode.SPEAKING, bounce = 0.42f),
                f(2300, eye = 0.76f, happy = true, tilt = 3f, perk = 1.15f, mouth = MouthMode.HAPPY, bounce = -0.1f, wag = -0.25f),
                f(2860, eye = 0.76f, happy = true, tilt = 3f, perk = 1.15f, mouth = MouthMode.HAPPY, bounce = -0.1f, wag = -0.25f),
                f(3520, eye = 0.88f, happy = true, tilt = -3f, perk = 1.23f, mouth = MouthMode.HAPPY, tongue = 0.16f, wag = 0.38f),
                f(4470, eye = 0.97f, pupil = 0.31f, tilt = -0.7f, perk = 1.06f, wag = 0.06f),
            )),
        clip("acorn_play", "Acorn Play", "Scenes", "animal_play", 5500,
            accent = PerformanceAccent.ACORN,
            aliases = setOf("play with an acorn", "play with the acorn", "inspect an acorn", "roll an acorn"), beats = listOf(
                f(470, eye = 0.92f, pupil = 0.36f, tilt = 6f, perk = 1.12f),
                f(1180, eye = 0.7f, pupil = 0.47f, tilt = 11f, perk = 1.32f, flutter = true, bounce = -0.08f),
                f(1860, eye = 1.16f, pupil = 0.52f, tilt = -8f, perk = 1.42f, bounce = 0.11f),
                f(2480, eye = 0.81f, pupil = 0.43f, tilt = 4f, perk = 1.15f, bounce = -0.05f, wag = 0.14f),
                f(3020, eye = 0.63f, happy = true, tilt = -4f, perk = 1.08f, mouth = MouthMode.HAPPY, bounce = 0.05f),
                f(3770, eye = 0.63f, happy = true, tilt = -4f, perk = 1.08f, mouth = MouthMode.HAPPY, bounce = 0.05f),
                f(4350, eye = 0.9f, pupil = 0.37f, tilt = 2f, perk = 1.13f, flutter = true),
                f(4970, eye = 0.97f, pupil = 0.3f, tilt = 0.5f, perk = 1.02f),
            )),
        clip("mischievous_raccoon", "Mischievous Raccoon", "Scenes", "sneak", 5800,
            accent = PerformanceAccent.SNACK,
            aliases = setOf("hide a snack", "hide the snack", "sneak a snack", "mischievous racoon"), beats = listOf(
                f(420, eye = 0.81f, pupil = 0.35f, tilt = -7f, perk = 1.14f),
                f(1110, eye = 0.66f, pupil = 0.42f, tilt = 10f, perk = 1.2f, bounce = -0.12f),
                f(1830, eye = 0.5f, pupil = 0.45f, tilt = -6f, perk = 0.8f, droop = 6f, bounce = -0.24f),
                f(2450, eye = 0.73f, happy = true, tilt = -3f, perk = 1.08f, mouth = MouthMode.HAPPY, bounce = -0.08f),
                f(3130, eye = 0.73f, happy = true, tilt = -3f, perk = 1.08f, mouth = MouthMode.HAPPY, bounce = -0.08f),
                f(3680, eye = 1.22f, pupil = 0.56f, tilt = 5f, perk = 1.38f, bounce = 0.14f),
                f(4330, eye = 0.67f, happy = true, tilt = 7f, perk = 0.92f, droop = 4f, mouth = MouthMode.HAPPY, wag = 0.12f),
                f(5180, eye = 0.95f, pupil = 0.32f, tilt = 1f, perk = 1.02f),
            )),
        clip("sleepy_nest", "Sleepy Nest", "Scenes", "nest", 6000,
            accent = PerformanceAccent.NEST,
            aliases = setOf("curl up in a nest", "curl up in your nest", "nest nap", "snuggle in a nest"), beats = listOf(
                f(540, eye = 0.84f, pupil = 0.33f, tilt = -4f, perk = 0.94f, droop = 5f),
                f(1270, eye = 0.63f, pupil = 0.35f, tilt = 8f, perk = 0.85f, droop = 10f, bounce = -0.11f),
                f(2110, eye = 0.35f, happy = true, tilt = 13f, perk = 0.72f, droop = 22f, bounce = -0.25f, wag = -0.16f),
                f(2840, eye = 0.07f, happy = true, tilt = 9f, perk = 0.66f, droop = 27f, bounce = -0.3f),
                f(3880, eye = 0.07f, happy = true, tilt = 9f, perk = 0.66f, droop = 27f, bounce = -0.3f),
                f(4490, eye = 0.4f, happy = true, tilt = 6f, perk = 0.82f, droop = 14f, bounce = -0.12f),
                f(5360, eye = 0.86f, pupil = 0.3f, tilt = 2f, perk = 0.96f, droop = 3f, bounce = -0.03f),
            )),
        clip("mouse_chase", "Mouse Chase", "Scenes", "pursuit", 5800,
            accent = PerformanceAccent.MOUSE,
            aliases = setOf("chase a mouse", "chase the mouse", "follow a mouse", "play with a mouse"), beats = listOf(
                f(380, eye = 0.86f, pupil = 0.36f, tilt = -4f, perk = 1.14f),
                f(1080, eye = 1.16f, pupil = 0.51f, tilt = -8f, perk = 1.3f, flutter = true),
                f(1810, eye = 0.79f, pupil = 0.55f, tilt = 7f, perk = 1.18f, wag = -0.18f),
                f(2390, eye = 0.49f, pupil = 0.58f, tilt = 3f, perk = 0.73f, bounce = -0.34f, wag = 0.23f),
                f(2670, eye = 0.49f, pupil = 0.58f, tilt = 3f, perk = 0.73f, bounce = -0.34f, wag = 0.23f),
                f(3080, eye = 1.31f, pupil = 0.6f, tilt = -5f, perk = 1.45f, bounce = 0.43f, wag = -0.27f),
                f(3640, eye = 1.18f, pupil = 0.49f, tilt = 9f, perk = 1.22f, bounce = -0.09f),
                f(4330, eye = 0.73f, happy = true, tilt = 5f, perk = 1.05f, mouth = MouthMode.HAPPY, wag = 0.14f),
                f(4770, eye = 0.73f, happy = true, tilt = 5f, perk = 1.05f, mouth = MouthMode.HAPPY, wag = 0.14f),
                f(5340, eye = 0.97f, pupil = 0.31f, tilt = 1f, perk = 1.03f),
            )),
        clip("book_reading", "Book Reading", "Scenes", "reading", 6000,
            accent = PerformanceAccent.BOOK,
            aliases = setOf("read a book", "read your book", "open a book", "turn a page"), beats = listOf(
                f(410, eye = 0.91f, pupil = 0.32f, tilt = -2f, perk = 1.02f),
                f(1160, eye = 0.76f, pupil = 0.37f, tilt = 3f, perk = 1.12f, bounce = -0.04f),
                f(1890, eye = 0.83f, pupil = 0.39f, tilt = -3f, perk = 1.09f),
                f(2470, eye = 0.83f, pupil = 0.39f, tilt = -3f, perk = 1.09f),
                f(3010, eye = 1.03f, pupil = 0.42f, tilt = 4f, perk = 1.19f, flutter = true),
                f(3730, eye = 0.88f, pupil = 0.36f, tilt = -4f, perk = 1.06f),
                f(4490, eye = 0.68f, happy = true, pupil = 0.34f, tilt = 2f, perk = 0.97f, mouth = MouthMode.HAPPY),
                f(4930, eye = 0.68f, happy = true, pupil = 0.34f, tilt = 2f, perk = 0.97f, mouth = MouthMode.HAPPY),
                f(5510, eye = 0.96f, pupil = 0.30f, tilt = 0.5f, perk = 1.02f),
            )),
        clip("cowboy_hat", "Cowboy Hat", "Scenes", "wardrobe", 5200,
            accent = PerformanceAccent.HAT_COWBOY,
            aliases = setOf("wear a hat", "put on a hat", "wear a cowboy hat", "put on a cowboy hat", "tip your hat"), beats = listOf(
                f(370, eye = 1.06f, pupil = 0.35f, tilt = -3f, perk = 1.11f),
                f(1040, eye = 0.80f, pupil = 0.33f, tilt = 6f, perk = 1.04f, bounce = 0.07f),
                f(1700, eye = 0.64f, happy = true, tilt = -9f, perk = 0.90f, bounce = -0.15f, mouth = MouthMode.HAPPY),
                f(2260, eye = 0.64f, happy = true, tilt = -9f, perk = 0.90f, bounce = -0.15f, mouth = MouthMode.HAPPY),
                f(2940, eye = 1.03f, pupil = 0.37f, tilt = 5f, perk = 1.15f, bounce = 0.10f),
                f(3530, eye = 0.78f, happy = true, tilt = 3f, mouth = MouthMode.HAPPY),
                f(4080, eye = 0.78f, happy = true, tilt = 3f, mouth = MouthMode.HAPPY),
                f(4780, eye = 0.97f, pupil = 0.3f, tilt = 0.5f, perk = 1.02f),
            )),
        clip("wizard_hat", "Wizard Hat", "Scenes", "wardrobe", 5800,
            accent = PerformanceAccent.HAT_WIZARD,
            aliases = setOf("wear a wizard hat", "put on a wizard hat", "wear a magic hat", "stargaze in a wizard hat"), beats = listOf(
                f(430, eye = 0.9f, pupil = 0.37f, tilt = 2f, perk = 1.08f),
                f(1210, eye = 1.18f, pupil = 0.51f, gaze = 0.34f, tilt = -5f, perk = 1.26f),
                f(1980, eye = 1.3f, pupil = 0.56f, gaze = 0.57f, tilt = -8f, perk = 1.35f, bounce = 0.08f),
                f(2510, eye = 1.3f, pupil = 0.56f, gaze = 0.57f, tilt = -8f, perk = 1.35f, bounce = 0.08f),
                f(3260, eye = 0.89f, pupil = 0.43f, gaze = 0.4f, tilt = 5f, perk = 1.19f, flutter = true),
                f(4030, eye = 0.72f, happy = true, pupil = 0.4f, tilt = 3f, mouth = MouthMode.HAPPY),
                f(4590, eye = 0.72f, happy = true, pupil = 0.4f, tilt = 3f, mouth = MouthMode.HAPPY),
                f(5340, eye = 0.96f, pupil = 0.31f, gaze = 0.04f, tilt = 0.6f, perk = 1.02f),
            )),
        clip("painter_beret", "Painter Beret", "Scenes", "wardrobe", 5600,
            accent = PerformanceAccent.HAT_BERET,
            aliases = setOf("wear a beret", "put on a beret", "wear a painter hat", "put on a painter hat", "pose like a painter"), beats = listOf(
                f(410, eye = 0.85f, pupil = 0.33f, tilt = 4f, perk = 1.07f),
                f(1070, eye = 0.66f, pupil = 0.36f, tilt = 8f, perk = 1.16f, flutter = true),
                f(1790, eye = 0.75f, pupil = 0.42f, tilt = -6f, perk = 1.22f, bounce = -0.07f),
                f(2350, eye = 0.75f, pupil = 0.42f, tilt = -6f, perk = 1.22f, bounce = -0.07f),
                f(3010, eye = 1.12f, pupil = 0.47f, tilt = 5f, perk = 1.3f, bounce = 0.10f),
                f(3710, eye = 0.64f, happy = true, tilt = 9f, perk = 1.09f, mouth = MouthMode.HAPPY),
                f(4360, eye = 0.64f, happy = true, tilt = 9f, perk = 1.09f, mouth = MouthMode.HAPPY),
                f(5090, eye = 0.96f, pupil = 0.3f, tilt = 1f, perk = 1.02f),
            )),
        clip("lost_mouse", "Lost Mouse", "Scenes", "search_story", 8600,
            accent = PerformanceAccent.MOUSE,
            aliases = setOf("find the lost mouse", "look for the lost mouse", "aussie lost mouse", "shepherd lost mouse"), beats = listOf(
                f(690, eye = 1.08f, pupil = .43f, tilt = -3f, perk = 1.16f),
                f(1720, eye = 1.14f, pupil = .47f, tilt = 4f, perk = 1.22f),
                f(2236, eye = .84f, pupil = .41f, tilt = -6f, perk = 1.04f),
                f(2924, eye = .84f, pupil = .41f, tilt = -6f, perk = 1.04f),
                f(3440, eye = .87f, pupil = .42f, tilt = 6f, perk = 1.08f),
                f(4214, eye = .87f, pupil = .42f, tilt = 6f, perk = 1.08f),
                f(4816, eye = 1.24f, pupil = .53f, tilt = -2f, perk = 1.38f, flutter = true),
                f(5332, eye = .64f, pupil = .52f, tilt = 2f, perk = .90f, bounce = -.20f),
                f(5762, eye = .64f, pupil = .52f, tilt = 2f, perk = .90f, bounce = -.20f),
                f(6278, eye = 1.16f, pupil = .49f, tilt = -3f, perk = 1.25f, bounce = .23f, wag = .13f),
                f(6794, eye = .82f, happy = true, pupil = .40f, tilt = 5f, perk = 1.08f, mouth = MouthMode.HAPPY),
                f(7482, eye = .82f, happy = true, pupil = .40f, tilt = 5f, perk = 1.08f, mouth = MouthMode.HAPPY),
                f(8084, eye = .97f, pupil = .30f, tilt = 1f, perk = 1.02f),
            )),
        clip("reading_realization", "Reading Realization", "Scenes", "realization_story", 9000,
            accent = PerformanceAccent.BOOK,
            aliases = setOf("owl reading realization", "reread the book", "reread a book", "read and have an idea"), beats = listOf(
                f(720, eye = .90f, pupil = .34f, tilt = -2f, perk = 1.03f),
                f(1710, eye = .79f, pupil = .39f, tilt = 2f, perk = 1.08f),
                f(2700, eye = .76f, pupil = .41f, tilt = -3f, perk = 1.12f),
                f(3330, eye = .66f, pupil = .43f, tilt = 5f, perk = 1.16f),
                f(4140, eye = .70f, pupil = .45f, tilt = 5f, perk = 1.16f),
                f(4590, eye = .70f, pupil = .45f, tilt = 5f, perk = 1.16f),
                f(5130, eye = 1.30f, pupil = .54f, tilt = -2f, perk = 1.40f, bounce = .12f),
                f(5580, eye = 1.30f, pupil = .54f, tilt = -2f, perk = 1.40f, bounce = .12f),
                f(6210, eye = .85f, happy = true, pupil = .39f, tilt = 2f, perk = 1.13f, mouth = MouthMode.HAPPY),
                f(6840, eye = .85f, happy = true, pupil = .39f, tilt = 2f, perk = 1.13f, mouth = MouthMode.HAPPY),
                f(7470, eye = .71f, happy = true, pupil = .35f, tilt = -2f, perk = 1.02f, bounce = -.05f, mouth = MouthMode.HAPPY),
                f(8370, eye = .97f, pupil = .30f, tilt = .5f, perk = 1.01f),
            )),
        clip("hat_mishap", "Hat Mishap", "Scenes", "mishap_story", 8000,
            accent = PerformanceAccent.HAT_COWBOY,
            aliases = setOf("duck hat mishap", "hat slips over your eyes", "lose your hat", "fix your slipping hat"), beats = listOf(
                f(640, eye = .94f, pupil = .35f, tilt = -3f, perk = 1.08f),
                f(1520, eye = .78f, happy = true, tilt = -4f, mouth = MouthMode.HAPPY, bounce = .05f),
                f(2080, eye = 1.16f, pupil = .47f, tilt = 3f, perk = 1.20f),
                f(2800, eye = .53f, pupil = .43f, tilt = 7f, perk = .94f, bounce = -.08f),
                f(3440, eye = .53f, pupil = .43f, tilt = 7f, perk = .94f, bounce = -.08f),
                f(4000, eye = .66f, pupil = .43f, tilt = -8f, perk = 1.12f),
                f(4640, eye = 1.14f, pupil = .44f, tilt = 6f, perk = 1.22f, bounce = .10f),
                f(5280, eye = .84f, pupil = .37f, tilt = -3f, perk = 1.01f),
                f(5760, eye = .69f, happy = true, tilt = 5f, perk = .94f, mouth = MouthMode.HAPPY),
                f(6480, eye = .69f, happy = true, tilt = 5f, perk = .94f, mouth = MouthMode.HAPPY),
                f(7440, eye = .96f, pupil = .30f, tilt = .5f, perk = 1.01f),
            )),
        clip("rainy_window", "Rainy Window", "Scenes", "rain_watch", 6000,
            accent = PerformanceAccent.RAIN,
            aliases = setOf("watch the rain", "watch rain", "look out at the rain", "rain watching"), beats = listOf(
                f(510, eye = 0.94f, pupil = 0.35f, gaze = 0.17f, tilt = -3f, perk = 1.08f),
                f(1290, eye = 1.08f, pupil = 0.44f, gaze = 0.48f, tilt = -7f, perk = 1.18f),
                f(2090, eye = 0.83f, pupil = 0.4f, gaze = 0.14f, tilt = 5f, perk = 1.02f, droop = 3f),
                f(2880, eye = 0.65f, happy = true, gaze = 0.26f, tilt = 3f, perk = 0.91f, droop = 5f, mouth = MouthMode.HAPPY),
                f(3730, eye = 0.65f, happy = true, gaze = 0.26f, tilt = 3f, perk = 0.91f, droop = 5f, mouth = MouthMode.HAPPY),
                f(4350, eye = 0.1f, happy = true, pupil = 0.33f, tilt = 2f, perk = 0.9f, droop = 6f),
                f(5270, eye = 0.89f, pupil = 0.31f, gaze = 0.05f, tilt = 0.7f, perk = 0.98f, droop = 1f),
            )),
    )

    private val byName: Map<String, AvatarPerformance> = buildMap {
        AvatarPerformanceCatalog.entries.forEach { entry ->
            (entry.aliases + entry.id + entry.title).forEach { alias ->
                val key = normalized(alias)
                val previous = put(key, entry)
                require(previous == null || previous.id == entry.id) { "Conflicting performance alias: $alias" }
            }
        }
    }

    /** Exact whole-name lookup; callers must establish performance intent separately. */
    fun resolve(name: String): AvatarPerformance? = byName[normalized(name)]
    fun names(): List<String> = entries.map { it.title }

    private fun normalized(name: String): String = name.trim().lowercase()
        .replace(Regex("[\\s_-]+"), " ")

    private fun clip(
        id: String, title: String, category: String, family: String, duration: Int,
        accent: PerformanceAccent = PerformanceAccent.NONE,
        ambient: Boolean = false,
        aliases: Set<String>,
        beats: List<AnimFrame>,
    ) = AvatarPerformance(id, title, category, family, accent,
        CustomAnimationSpec(title, listOf(f(0)) + beats + f(duration)), aliases, ambient)

    /** Full neutral defaults make every keyed channel's recovery explicit. Colors stay inherited. */
    private fun f(
        at: Int, eye: Float = 1f, pupil: Float = 0.28f, gaze: Float = 0f,
        tilt: Float = 0f, perk: Float = 1f, droop: Float = 0f,
        happy: Boolean = false, flutter: Boolean = false, mouth: MouthMode = MouthMode.IDLE,
        tongue: Float = 0f, bounce: Float = 0f, wag: Float = 0f,
    ) = AnimFrame(atMs = at, eyeOpen = eye, pupilSize = pupil, pupilGazeUp = gaze,
        headTiltDeg = tilt, earPerk = perk, earDroopDeg = droop, happyEyes = happy,
        whiskerFlutter = flutter, mouth = mouth, tongueOut = tongue,
        bodyBounce = bounce, tailWag = wag, sparkle = 0f)
}

/** Keeps a bounded history across changing pools; it never starts a timer or plays a clip. */
class PerformanceRotation(seed: Int = 73) {
    private val random = Random(seed)
    private val recentIds = ArrayDeque<String>()
    private val recentFamilies = ArrayDeque<String>()

    fun pick(pool: List<AvatarPerformance>): AvatarPerformance? {
        val unique = pool.distinctBy { it.id }
        if (unique.isEmpty()) return null
        val freshIds = unique.filter { it.id !in recentIds }
        val freshBoth = freshIds.filter { it.family !in recentFamilies }
        val candidates = freshBoth.ifEmpty { freshIds }.ifEmpty {
            unique.filter { it.id != recentIds.lastOrNull() && it.family !in recentFamilies }
        }.ifEmpty {
            unique.filter { it.id != recentIds.lastOrNull() }
        }.ifEmpty { unique }
        val chosen = candidates[random.nextInt(candidates.size)]
        recentIds.addLast(chosen.id)
        if (recentIds.size > 3) recentIds.removeFirst()
        recentFamilies.addLast(chosen.family)
        if (recentFamilies.size > 2) recentFamilies.removeFirst()
        return chosen
    }
}

/**
 * Invented-clip engine (ported from `ui/companion/CustomAnimation.kt` with
 * org.json rewritten as kotlinx.serialization). The model PROPOSES, this
 * CONSTRAINS: out-of-range numbers are clamped (and reported), broken specs
 * are rejected with a reason, and privacy marks are not animatable.
 */
object CustomAnimations {

    /** Why a spec was refused. Always a plain, actionable sentence. */
    class SpecException(message: String) : Exception(message)

    private val EYE_OPEN_RANGE = 0f..1.6f
    private val PUPIL_RANGE = 0.05f..0.9f
    private val GAZE_RANGE = 0f..1f
    private val TILT_RANGE = -25f..25f
    private val PERK_RANGE = 0.3f..1.6f
    private val DROOP_RANGE = 0f..40f
    private val TONGUE_RANGE = 0f..1f
    private val BOUNCE_RANGE = -1f..1f
    private val WAG_RANGE = -1f..1f
    private val SPARKLE_RANGE = 0f..1f
    private const val MAX_FRAMES = 16
    private const val MAX_DURATION_MS = 10_000
    private const val MIN_VISIBLE_MS = 1200
    private val NAME_PATTERN = Regex("[A-Za-z0-9 _\\-]{1,40}")
    private val ALLOWED_MOUTHS = setOf(MouthMode.IDLE, MouthMode.HAPPY, MouthMode.SPEAKING)

    /**
     * Parses a spec from JSON: `{"name": "wiggle", "frames": [{"at": 0, "ear_perk": 1.5}, …]}`.
     * Numeric keys accept snake_case or camelCase ("eye_open"/"eyeOpen").
     * Returns the validated spec plus human-readable notes for every clamp.
     * Throws [SpecException] on anything structurally wrong.
     */
    fun parse(json: String): Pair<CustomAnimationSpec, List<String>> {
        val root = try {
            (kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject)
                ?: throw SpecException("not an object")
        } catch (e: SpecException) {
            throw e
        } catch (e: Exception) {
            throw SpecException("not JSON: ${e.message?.take(80)}")
        }
        val name = (root["name"] as? JsonPrimitive)?.content?.trim() ?: ""
        if (!NAME_PATTERN.matches(name)) {
            throw SpecException("bad name \"$name\" — use 1-40 letters, numbers, spaces, '-' or '_'")
        }
        val rawFrames = root["frames"] as? JsonArray
            ?: throw SpecException("missing \"frames\" array")
        if (rawFrames.isEmpty()) throw SpecException("frames is empty — at least one keyframe")
        if (rawFrames.size > MAX_FRAMES) {
            throw SpecException("${rawFrames.size} frames — the limit is $MAX_FRAMES")
        }

        val notes = mutableListOf<String>()
        val frames = rawFrames.mapIndexed { i, el ->
            val f = (el as? JsonObject) ?: throw SpecException("frame $i is not an object")
            parseFrame(i, f, notes)
        }.sortedBy { it.atMs }

        if (frames.last().atMs == 0) {
            throw SpecException("every keyframe is at 0ms — the clip never plays; add a later keyframe")
        }
        val stretched = if (frames.last().atMs < MIN_VISIBLE_MS) {
            val factor = MIN_VISIBLE_MS.toFloat() / frames.last().atMs
            notes.add("clip was ${frames.last().atMs}ms — stretched to ${MIN_VISIBLE_MS}ms so it can be seen")
            frames.map { f -> f.copy(atMs = (f.atMs * factor).toInt()) }
        } else {
            frames
        }
        return CustomAnimationSpec(name, stretched, InventedScenes.parse(root, stretched.last().atMs)) to notes
    }

    private fun parseFrame(index: Int, f: JsonObject, notes: MutableList<String>): AnimFrame {
        val at = clampInt(f.optLongOf("at"), 0, MAX_DURATION_MS, notes, "frame $index 'at'")
        if (at < 0) throw SpecException("frame $index has no \"at\" (milliseconds)")
        return AnimFrame(
            atMs = at,
            eyeOpen = f.optNum("eye_open", "eyeOpen")?.let { clampF(it, EYE_OPEN_RANGE, notes, "eye_open") },
            pupilSize = f.optNum("pupil_size", "pupilSize")?.let { clampF(it, PUPIL_RANGE, notes, "pupil_size") },
            pupilGazeUp = f.optNum("gaze_up", "pupilGazeUp")?.let { clampF(it, GAZE_RANGE, notes, "gaze_up") },
            headTiltDeg = f.optNum("head_tilt", "headTiltDeg")?.let { clampF(it, TILT_RANGE, notes, "head_tilt") },
            earPerk = f.optNum("ear_perk", "earPerk")?.let { clampF(it, PERK_RANGE, notes, "ear_perk") },
            earDroopDeg = f.optNum("ear_droop", "earDroopDeg")?.let { clampF(it, DROOP_RANGE, notes, "ear_droop") },
            happyEyes = f.optBool("happy_eyes", "happyEyes"),
            whiskerFlutter = f.optBool("whisker_flutter", "whiskerFlutter"),
            mouth = mouth(f),
            glowColorHex = glow(f),
            tongueOut = f.optNum("tongue_out", "tongueOut")?.let { clampF(it, TONGUE_RANGE, notes, "tongue_out") },
            bodyBounce = f.optNum("body_bounce", "bodyBounce")?.let { clampF(it, BOUNCE_RANGE, notes, "body_bounce") },
            tailWag = f.optNum("tail_wag", "tailWag")?.let { clampF(it, WAG_RANGE, notes, "tail_wag") },
            sparkle = f.optNum("sparkle")?.let { clampF(it, SPARKLE_RANGE, notes, "sparkle") },
        )
    }

    private fun mouth(f: JsonObject): MouthMode? {
        val raw = (f["mouth"] as? JsonPrimitive)?.content ?: return null
        val mode = runCatching { MouthMode.valueOf(raw.uppercase()) }.getOrNull()
            ?: throw SpecException("unknown mouth \"$raw\" — use IDLE, HAPPY or SPEAKING")
        if (mode !in ALLOWED_MOUTHS) {
            throw SpecException("mouth $mode is system-owned — the mute mark is not animatable")
        }
        return mode
    }

    private fun glow(f: JsonObject): Long? {
        val v = f["glow"] ?: return null
        return when (v) {
            is JsonPrimitive -> {
                val raw = v.content
                if (raw.startsWith("#")) {
                    val hex = raw.removePrefix("#")
                    when (hex.length) {
                        6 -> ("FF" + hex).toLongOrNull(16)
                            ?: throw SpecException("glow \"$raw\" — use #RRGGBB or #AARRGGBB")
                        8 -> hex.toLongOrNull(16)
                            ?: throw SpecException("glow \"$raw\" — use #RRGGBB or #AARRGGBB")
                        else -> throw SpecException("glow \"$raw\" — use #RRGGBB or #AARRGGBB")
                    }
                } else {
                    v.floatOrNull?.toLong() ?: throw SpecException("glow must be a color string or ARGB number")
                }
            }
            else -> throw SpecException("glow must be a color string or ARGB number")
        }
    }

    private fun clampF(v: Float, range: ClosedFloatingPointRange<Float>, notes: MutableList<String>, field: String): Float {
        val c = v.coerceIn(range.start, range.endInclusive)
        if (c != v) notes.add("$field $v clamped to $c")
        return c
    }

    private fun clampInt(v: Long, min: Int, max: Int, notes: MutableList<String>, field: String): Int {
        if (v < 0) return -1
        val c = v.coerceIn(min.toLong(), max.toLong()).toInt()
        if (c.toLong() != v) notes.add("$field ${v}ms clamped to ${c}ms")
        return c
    }

    /** Serializes to the same JSON shape [parse] accepts (round-trip stable). */
    fun toJson(spec: CustomAnimationSpec): String = buildJsonObject {
        put("name", spec.name)
        putJsonArray("frames") {
            spec.frames.forEach { f ->
                add(buildJsonObject {
                    put("at", f.atMs)
                    f.eyeOpen?.let { put("eye_open", it.toDouble()) }
                    f.pupilSize?.let { put("pupil_size", it.toDouble()) }
                    f.pupilGazeUp?.let { put("gaze_up", it.toDouble()) }
                    f.headTiltDeg?.let { put("head_tilt", it.toDouble()) }
                    f.earPerk?.let { put("ear_perk", it.toDouble()) }
                    f.earDroopDeg?.let { put("ear_droop", it.toDouble()) }
                    f.happyEyes?.let { put("happy_eyes", it) }
                    f.whiskerFlutter?.let { put("whisker_flutter", it) }
                    f.mouth?.let { put("mouth", it.name) }
                    f.glowColorHex?.let { put("glow", it) }
                    f.tongueOut?.let { put("tongue_out", it.toDouble()) }
                    f.bodyBounce?.let { put("body_bounce", it.toDouble()) }
                    f.tailWag?.let { put("tail_wag", it.toDouble()) }
                    f.sparkle?.let { put("sparkle", it.toDouble()) }
                })
            }
        }
    }.toString()

    /**
     * Samples a clip at [tMs]. Returns null when the clip is over (t > duration),
     * which is how the renderer knows to stop applying it. Each field tracks its
     * own surrounding keyframes independently — that is what makes frames sparse.
     * Numbers ease (smoothstep); booleans, mouth and color step at their keyframe.
     */
    fun sample(spec: CustomAnimationSpec, tMs: Long): AnimFrame? {
        if (tMs < 0 || tMs > spec.durationMs) return null
        val t = tMs.toFloat()
        return AnimFrame(
            atMs = tMs.toInt(),
            eyeOpen = lerp(spec.frames, t) { it.eyeOpen },
            pupilSize = lerp(spec.frames, t) { it.pupilSize },
            pupilGazeUp = lerp(spec.frames, t) { it.pupilGazeUp },
            headTiltDeg = lerp(spec.frames, t) { it.headTiltDeg },
            earPerk = lerp(spec.frames, t) { it.earPerk },
            earDroopDeg = lerp(spec.frames, t) { it.earDroopDeg },
            happyEyes = step(spec.frames, t) { it.happyEyes },
            whiskerFlutter = step(spec.frames, t) { it.whiskerFlutter },
            mouth = step(spec.frames, t) { it.mouth },
            glowColorHex = step(spec.frames, t) { it.glowColorHex },
            tongueOut = lerp(spec.frames, t) { it.tongueOut },
            bodyBounce = lerp(spec.frames, t) { it.bodyBounce },
            tailWag = lerp(spec.frames, t) { it.tailWag },
            sparkle = lerp(spec.frames, t) { it.sparkle },
        )
    }

    private fun lerp(frames: List<AnimFrame>, t: Float, pick: (AnimFrame) -> Float?): Float? {
        val keyed = frames.mapNotNull { f -> pick(f)?.let { f.atMs to it } }
        if (keyed.isEmpty()) return null
        val after = keyed.firstOrNull { it.first >= t } ?: return keyed.last().second
        val before = keyed.lastOrNull { it.first <= t } ?: return after.second
        if (after.first == before.first) return before.second
        val u = (t - before.first) / (after.first - before.first)
        val eased = u * u * (3f - 2f * u)
        return before.second + (after.second - before.second) * eased
    }

    private fun <T> step(frames: List<AnimFrame>, t: Float, pick: (AnimFrame) -> T?): T? {
        val keyed = frames.mapNotNull { f -> pick(f)?.let { f.atMs to it } }
        if (keyed.isEmpty()) return null
        return (keyed.lastOrNull { it.first <= t } ?: keyed.first()).second
    }

    /** Applies a sampled frame over a base pose; null fields keep the base value. */
    fun AvatarPose.overlay(frame: AnimFrame): AvatarPose = copy(
        eyeOpen = frame.eyeOpen ?: eyeOpen,
        pupilSize = frame.pupilSize ?: pupilSize,
        pupilGazeUp = frame.pupilGazeUp ?: pupilGazeUp,
        headTiltDeg = frame.headTiltDeg ?: headTiltDeg,
        earPerk = frame.earPerk ?: earPerk,
        earDroopDeg = frame.earDroopDeg ?: earDroopDeg,
        happyEyes = frame.happyEyes ?: happyEyes,
        whiskerFlutter = frame.whiskerFlutter ?: whiskerFlutter,
        mouth = frame.mouth ?: mouth,
        glowColorHex = frame.glowColorHex ?: glowColorHex,
        tongueOut = frame.tongueOut ?: tongueOut,
        bodyBounce = frame.bodyBounce ?: bodyBounce,
        tailWag = frame.tailWag ?: tailWag,
        sparkle = frame.sparkle ?: sparkle,
    )

    /**
     * Cross-fade a sampled frame over an already-eased pose (ported unchanged:
     * the renderer's hard-switch caused a visible twitch at clip boundaries).
     * [weight] is the fade: 0 = fully the eased pose, 1 = fully the clip.
     * Fields the frame does NOT name stay at their eased value. Discrete fields
     * flip once, at the halfway point of the fade.
     */
    fun AvatarPose.blendFrame(frame: AnimFrame?, weight: Float): AvatarPose {
        if (frame == null || weight <= 0f) return this
        val w = weight.coerceIn(0f, 1f)
        fun num(base: Float, over: Float?): Float =
            over?.let { base + (it - base) * w } ?: base
        val discrete = w >= 0.5f
        return copy(
            eyeOpen = num(eyeOpen, frame.eyeOpen),
            pupilSize = num(pupilSize, frame.pupilSize),
            pupilGazeUp = num(pupilGazeUp, frame.pupilGazeUp),
            headTiltDeg = num(headTiltDeg, frame.headTiltDeg),
            earPerk = num(earPerk, frame.earPerk),
            earDroopDeg = num(earDroopDeg, frame.earDroopDeg),
            tongueOut = num(tongueOut, frame.tongueOut),
            bodyBounce = num(bodyBounce, frame.bodyBounce),
            tailWag = num(tailWag, frame.tailWag),
            sparkle = num(sparkle, frame.sparkle),
            happyEyes = if (discrete) frame.happyEyes ?: happyEyes else happyEyes,
            whiskerFlutter = if (discrete) frame.whiskerFlutter ?: whiskerFlutter else whiskerFlutter,
            mouth = if (discrete) frame.mouth ?: mouth else mouth,
            glowColorHex = if (discrete) frame.glowColorHex ?: glowColorHex else glowColorHex,
        )
    }

    /**
     * Cross-fade toward a whole target pose — the composed-expression path.
     */
    fun AvatarPose.blendToward(target: AvatarPose, weight: Float): AvatarPose {
        if (weight <= 0f) return this
        val w = weight.coerceIn(0f, 1f)
        fun num(base: Float, over: Float): Float = base + (over - base) * w
        return copy(
            eyeOpen = num(eyeOpen, target.eyeOpen),
            pupilSize = num(pupilSize, target.pupilSize),
            pupilGazeUp = num(pupilGazeUp, target.pupilGazeUp),
            headTiltDeg = num(headTiltDeg, target.headTiltDeg),
            earPerk = num(earPerk, target.earPerk),
            earDroopDeg = num(earDroopDeg, target.earDroopDeg),
            tongueOut = num(tongueOut, target.tongueOut),
            bodyBounce = num(bodyBounce, target.bodyBounce),
            tailWag = num(tailWag, target.tailWag),
            sparkle = num(sparkle, target.sparkle),
            leanX = num(leanX, target.leanX),
            happyEyes = if (w >= 0.5f) target.happyEyes else happyEyes,
            whiskerFlutter = if (w >= 0.5f) target.whiskerFlutter else whiskerFlutter,
            mouth = if (w >= 0.5f) target.mouth else mouth,
        )
    }

    /** Exponential approach to a target, framerate-independent. */
    fun approach(current: Float, target: Float, dtSec: Float, tauSec: Float): Float {
        if (tauSec <= 0f) return target
        val k = 1f - kotlin.math.exp(-dtSec / tauSec)
        return current + (target - current) * k
    }

    // -- kotlinx JSON helpers (org.json's opt* semantics) -------------------

    private fun JsonObject.optNum(vararg keys: String): Float? {
        for (k in keys) {
            val e = this[k]
            if (e is JsonPrimitive && e !is JsonNull) return e.floatOrNull
        }
        return null
    }

    private fun JsonObject.optBool(vararg keys: String): Boolean? {
        for (k in keys) {
            val e = this[k]
            if (e is JsonPrimitive) return e.booleanOrNull
        }
        return null
    }

    private fun JsonObject.optLongOf(key: String): Long {
        val e = this[key] as? JsonPrimitive ?: return -1
        return e.content.toLongOrNull() ?: -1
    }
}

/** Bounded prop channels for invented clips. Full props port: later tranche. */
object InventedScenes {
    fun parse(root: JsonObject, durationMs: Int): List<InventedProp> = emptyList()
    fun toJson(props: List<InventedProp>): JsonElement? = null
}
