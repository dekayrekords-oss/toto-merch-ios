package com.meowmba.shared

import com.meowmba.shared.agent.runtime.AvatarSceneGateway
import com.meowmba.shared.companion.AvatarPerformanceCatalog
import com.meowmba.shared.companion.CustomAnimations
import com.meowmba.shared.companion.PerformanceRotation
import com.meowmba.shared.companion.PerformanceAccent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Authored performance catalog + scene routing + invented-clip engine. */
class CatalogAndSceneTest {

    // -- catalog resolution -------------------------------------------------

    @Test
    fun resolveFindsAliasesAndTitles() {
        assertEquals("Warm Hello", AvatarPerformanceCatalog.resolve("hello")?.title)
        assertEquals("Warm Hello", AvatarPerformanceCatalog.resolve("Hi")?.title)
        assertEquals("Sure Nod", AvatarPerformanceCatalog.resolve("nod")?.title)
        assertEquals("Playful Wink", AvatarPerformanceCatalog.resolve("winking")?.title)
        assertEquals("Pocket Groove", AvatarPerformanceCatalog.resolve("dance to music")?.title)
        assertNull(AvatarPerformanceCatalog.resolve("do the hokey pokey"))
    }

    @Test
    fun catalogHasScenesAndAccents() {
        val scenes = AvatarPerformanceCatalog.entries.filter { it.category == "Scenes" }
        assertTrue(scenes.size >= 12, "expected authored scenes, got ${scenes.size}")
        assertTrue(scenes.any { it.accent == PerformanceAccent.BUTTERFLY })
        assertTrue(scenes.any { it.accent == PerformanceAccent.DETECTIVE })
        // Every clip starts and ends on the same neutral defaults.
        scenes.forEach { scene ->
            val first = scene.spec.frames.first()
            val last = scene.spec.frames.last()
            assertEquals(1f, first.eyeOpen)
            assertEquals(1f, last.eyeOpen)
            assertEquals(0f, last.bodyBounce)
            assertEquals(0f, last.tailWag)
        }
    }

    // -- scene routing ------------------------------------------------------

    @Test
    fun sceneRequestResolvesExplicitScenesOnly() {
        assertEquals("Butterfly Chase", AvatarSceneGateway.parse("can you chase a butterfly"))
        assertEquals("Butterfly Chase", AvatarSceneGateway.parse("play the butterfly chase scene"))
        assertEquals("Detective", AvatarSceneGateway.parse("please play detective"))
        assertEquals("Rainy Window", AvatarSceneGateway.parse("watch the rain"))
        assertEquals("Yarn Play", AvatarSceneGateway.parse("meow play with yarn"))
        // Casual mentions and plain greetings are not scenes.
        assertNull(AvatarSceneGateway.parse("hello"))
        assertNull(AvatarSceneGateway.parse("what's the weather"))
        assertNull(AvatarSceneGateway.parse("chase"))
        assertNull(AvatarSceneGateway.parse("hide the snack in the pantry"))
    }

    // -- rotation -----------------------------------------------------------

    @Test
    fun rotationVariesAcrossPicks() {
        val pool = AvatarPerformanceCatalog.entries.filter { it.category == "Scenes" }
        val rotation = PerformanceRotation(seed = 7)
        val picked = (0 until 6).mapNotNull { rotation.pick(pool) }
        assertEquals(6, picked.size)
        assertTrue(picked.distinctBy { it.id }.size >= 3, "rotation should vary, got ${picked.map { it.id }}")
    }

    // -- invented clips -----------------------------------------------------

    @Test
    fun parseValidSpecAndStretchShortClip() {
        val json = """{"name":"wiggle","frames":[
            {"at":0,"ear_perk":1.0},{"at":300,"ear_perk":1.5},{"at":600,"ear_perk":1.0}]}"""
        val (spec, notes) = CustomAnimations.parse(json)
        assertEquals("wiggle", spec.name)
        // 600ms is under the 1200ms visibility floor -> stretched x2.
        assertEquals(1200, spec.durationMs)
        assertEquals(1.5f, spec.frames[1].earPerk)
        assertTrue(notes.any { it.contains("stretched") })
    }

    @Test
    fun parseToJsonRoundTrips() {
        val json = """{"name":"twitch","frames":[{"at":0,"eye_open":1.0},{"at":400,"eye_open":0.2,"happy_eyes":true},{"at":800,"eye_open":1.0}]}"""
        val (spec, _) = CustomAnimations.parse(json)
        val rep = CustomAnimations.toJson(spec)
        val (again, _) = CustomAnimations.parse(rep)
        assertEquals(spec, again)
    }

    @Test
    fun parseClampsOutOfRangeValuesWithNotes() {
        val json = """{"name":"spin","frames":[{"at":0,"ear_perk":0.1},{"at":20000,"head_tilt":99}]}"""
        val (spec, notes) = CustomAnimations.parse(json)
        assertEquals(0.3f, spec.frames[0].earPerk) // clamped to floor
        assertEquals(10000, spec.durationMs)       // clamped to max
        assertEquals(25f, spec.frames[1].headTiltDeg)
        assertTrue(notes.any { it.contains("ear_perk") && it.contains("clamped") })
        assertTrue(notes.any { it.contains("head_tilt") && it.contains("clamped") })
    }

    @Test
    fun parseRejectsBrokenSpecs() {
        assertFailsWith<CustomAnimations.SpecException> { CustomAnimations.parse("not json") }
        assertFailsWith<CustomAnimations.SpecException> {
            CustomAnimations.parse("""{"name":"x"}""") // no frames
        }
        assertFailsWith<CustomAnimations.SpecException> {
            CustomAnimations.parse("""{"name":"x","frames":[{"at":0},{"at":0}]}""") // never plays
        }
        assertFailsWith<CustomAnimations.SpecException> {
            CustomAnimations.parse("""{"name":"x","frames":[{"at":0,"mouth":"MUTED"}]}""") // system-owned
        }
        assertFailsWith<CustomAnimations.SpecException> {
            CustomAnimations.parse("""{"name":"","frames":[{"at":0},{"at":100}]}""") // bad name
        }
    }

    @Test
    fun sampleEasesBetweenKeyframes() {
        val (spec, _) = CustomAnimations.parse(
            """{"name":"ease","frames":[{"at":0,"ear_perk":1.0},{"at":400,"ear_perk":1.5}]}"""
        )
        // duration 400 < 1200 -> stretched x3 (1200ms). Midpoint at 600ms.
        assertEquals(1200, spec.durationMs)
        val mid = assertNotNull(CustomAnimations.sample(spec, 600))
        // u = 0.5 -> smoothstep(0.5) = 0.5 -> 1.0 + 0.5*0.5 = 1.25
        assertEquals(1.25f, mid.earPerk!!, absoluteTolerance = 0.001f)
        assertEquals(1.0f, CustomAnimations.sample(spec, 0)!!.earPerk)
        assertEquals(1.5f, CustomAnimations.sample(spec, 1200)!!.earPerk)
        assertNull(CustomAnimations.sample(spec, 1201))
        assertNull(CustomAnimations.sample(spec, -1))
    }
}
