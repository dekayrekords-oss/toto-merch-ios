package com.meowmba.shared

import com.meowmba.shared.core.flow.FlowComments
import com.meowmba.shared.core.northstar.NorthStar
import com.meowmba.shared.core.northstar.NorthStarPolicy
import com.meowmba.shared.core.northstar.NorthStars
import com.meowmba.shared.core.northstar.StarEvidence
import com.meowmba.shared.core.screen.VisionRequest
import com.meowmba.shared.core.shopping.*
import com.meowmba.shared.platform.ChargerAvatar
import com.meowmba.shared.robot.*
import com.meowmba.shared.voice.SpeechQuiet
import com.meowmba.shared.voice.SpeechText
import com.meowmba.shared.work.WorkJournal
import com.meowmba.shared.work.WorkOutcome
import com.meowmba.shared.work.WorkStatus
import com.meowmba.shared.work.WorkTask
import kotlin.test.*

/** Behaviour ported from Android builds 172–183, checked with Android's own cases where they exist. */
class AndroidParity183Test {
    private class MemStorage : com.meowmba.shared.core.TextStorage {
        val map = mutableMapOf<String, String>()
        override fun readText(name: String) = map[name]
        override fun writeText(name: String, content: String) { map[name] = content }
        override fun writeTextAtomic(name: String, content: String) { map[name] = content }
        override fun delete(name: String) { map.remove(name) }
    }

    // ── North Stars (172) ──
    @Test fun northStarCapturesLastingGoalsOnly() {
        assertEquals("play in Europe with my band", NorthStarPolicy.capture("My dream is to play in Europe with my band"))
        assertEquals("run a marathon next spring", NorthStarPolicy.capture("I want to run a marathon next spring."))
        assertNull(NorthStarPolicy.capture("I want pizza"))
        assertNull(NorthStarPolicy.capture("My goal is to open Spotify"))
        assertNull(NorthStarPolicy.capture("what is my dream?"))
    }

    @Test fun northStarNeverTurnsIntentionsIntoProgress() {
        assertEquals("PROGRESS", NorthStarPolicy.kind(StarEvidence("journal:1", "We recorded the rehearsal", 0)))
        assertEquals("CONNECTION", NorthStarPolicy.kind(StarEvidence("journal:1", "We need to record the rehearsal", 0)))
        assertEquals("MISSED", NorthStarPolicy.kind(StarEvidence("journal:1", "I missed the deadline", 0)))
        assertEquals("OPPORTUNITY", NorthStarPolicy.kind(StarEvidence("flow:1", "Festival applications open for bands", 0)))
        assertEquals("CONNECTION", NorthStarPolicy.kind(StarEvidence("flow:1", "The festival deadline passed", 0)))
    }

    @Test fun northStarLinksEvidenceAndKeepsDismissals() {
        var now = 1_790_000_000_000L
        val stars = NorthStars(MemStorage()) { now }
        val id = stars.save(null, "Play in Europe with my band", "", "Someday", listOf("Record one rehearsal for the live set"))
        stars.ingest(listOf(StarEvidence("journal:1", "We recorded one rehearsal for the live set tonight", now)))
        val link = stars.views.value.single().links.single()
        assertEquals("PROGRESS", link.kind)
        assertTrue(stars.views.value.single().momentum.startsWith("Moving"))
        stars.dismiss(link.id)
        now += 1
        stars.ingest(listOf(StarEvidence("journal:1", "We recorded one rehearsal for the live set tonight!", now)))
        assertTrue(stars.views.value.single().links.isEmpty())
        assertEquals("Play in Europe with my band", stars.goalFor("journal:1") ?: "Play in Europe with my band")
        stars.remove(id)
        assertTrue(stars.views.value.isEmpty())
    }

    // ── Flow comments (178) ──
    @Test fun onlyExplicitCancellationCancels() {
        listOf("this event is canceled", "Cancel this meeting", "it got cancelled", "please cancel it").forEach { assertTrue(FlowComments.requestsCancellation(it), it) }
        listOf("can we cancel next week?", "the cancel button is broken", "this is fine").forEach { assertFalse(FlowComments.requestsCancellation(it), it) }
        assertEquals(FlowComments.EventTarget(1_790_000_000_000L, "Standup: team"), FlowComments.target("ctx:event:1790000000000:Standup: team"))
        assertNull(FlowComments.target("note-1"))
    }

    // ── Screen help / vision (172) ──
    @Test fun qrAndLookRequests() {
        assertTrue(VisionRequest.parse("scan this QR code")!!.isQrQuery)
        assertNull(VisionRequest.parse("what is a QR code")?.takeIf { it.isQrQuery })
        assertEquals("the plant", VisionRequest.parse("look at the plant")?.focus?.let { "the $it" })
        assertNotNull(VisionRequest.parse("what do you see?"))
    }

    // ── Quiet, speech (176) ──
    @Test fun quietRequestsAndNonSpeech() {
        assertTrue(SpeechQuiet.isQuietRequest("Be quiet"))
        assertTrue(SpeechQuiet.isQuietRequest("please stop talking"))
        assertFalse(SpeechQuiet.isQuietRequest("be quiet about the surprise when mom calls"))
        assertTrue(SpeechQuiet.isOnlyNonSpeech("[laughs]"))
        assertFalse(SpeechQuiet.isOnlyNonSpeech("(open maps)"))
        assertFalse(SpeechQuiet.isOnlyNonSpeech("hello [laughs]"))
        assertEquals("You're at your current location.", SpeechText.forSpeech("You're at 47.6062, -122.3321."))
    }

    // ── Work receipts (182) ──
    @Test fun interruptedWorkIsNeverReplayed() {
        val running = WorkTask("1", "Email draft", "email", createdAt = 0)
        val recovered = WorkJournal.recover(running, 10)
        assertEquals(WorkStatus.FAILED, recovered.status)
        assertEquals(WorkOutcome.UNCONFIRMED, recovered.outcome)
        assertEquals("Check outcome", WorkJournal.label(recovered))
        assertEquals("Continue in the opened app", WorkJournal.label(running.copy(status = WorkStatus.DONE, outcome = WorkOutcome.HANDOFF)))
        val journal = WorkJournal(MemStorage()) { 5 }
        val id = journal.start("Reminder: water plants", "reminder")
        journal.finish(id, true, WorkOutcome.VERIFIED, "Saved")
        assertEquals("Verified result", WorkJournal.label(journal.tasks.value.single()))
    }

    // ── Charger avatar (181): Android WirelessAvatarPolicyTest, any charger ──
    @Test fun chargerAvatarIsEdgeTriggered() {
        val p = ChargerAvatar.Policy()
        assertFalse(ChargerAvatar.Policy().update(true, false, 0))
        assertTrue(p.update(true, true, 0))
        repeat(20) { assertFalse(p.update(true, true, 1000L * it)) }
        assertFalse(p.update(false, true, 21_000)); assertFalse(p.update(true, true, 23_000))   // realigning
        assertFalse(p.update(false, true, 24_000)); assertTrue(p.update(true, true, 30_000))    // redock
        val q = ChargerAvatar.Policy()
        assertTrue(q.update(true, true, 0)); assertFalse(q.update(true, false, 1)); assertTrue(q.update(true, true, 2))
    }

    // ── Shopping (177): Android StoreShoppingTest cases ──
    @Test fun shoppingRequestsAreExplicit() {
        assertEquals(ShoppingRequest("Target", "coffee"), ShoppingRequest.parse("Best deals on coffee at Target"))
        assertEquals(ShoppingRequest("Target", "coffee"), ShoppingRequest.parse("show me deals at Target on coffee please"))
        assertEquals(ShoppingRequest("Fry's", null), ShoppingRequest.parse("what's on sale at Fry’s?"))
        assertEquals(ShoppingRequest(null, "coffee"), ShoppingRequest.parse("deals on coffee here"))
        assertEquals(ShoppingRequest(), ShoppingRequest.parse("what store am I in?"))
        listOf("I love deals", "deals at localhost", "deals at https://x.com", "deals at ${"x".repeat(100)}").forEach { assertNull(ShoppingRequest.parse(it), it) }
    }

    @Test fun dealEvidenceIsHonest() {
        val today = DealEvidence.Day.of(1_790_000_000_000L, 0)
        assertEquals(DealEvidence.Day(2026, 9, 21), today)
        assertEquals("site:target.com \"Target\" \"coffee\" deals coupons September 2026", DealEvidence.query("Target", "coffee", today))
        val hits = listOf(
            WebHit("Coffee deals this week", "https://www.target.com/c/coffee-deals", "Save 20% on coffee", "bing"),
            WebHit("Old coupon", "https://blog.example.com/target", "Target coffee coupon 2023 save 10%", "bing"),
            WebHit("Local", "http://target.com/deal", "Target coffee sale", "bing"),
            WebHit("Other store", "https://walmart.com/x", "coffee sale", "bing"))
        val leads = DealEvidence.select("Target", "coffee", hits, today)
        assertEquals(listOf("https://www.target.com/c/coffee-deals"), leads.map { it.url })
        assertTrue(leads.single().official)
        assertNull(DealEvidence.safeHost("https://user@evil.com/"))
        assertNull(DealEvidence.safeHost("https://printer.local/"))
        assertEquals("example.com", DealEvidence.safeHost("https://example.com:443/a"))
    }

    @Test fun storeEvidenceNeverClaimsInside() {
        val now = 1_000_000L
        val fix = LocationFix(33.0, -111.0, 10.0, now, 0.0)
        val target = Place("Target", 33.0001, -111.0)
        assertTrue(StoreEvidence.resolve(fix, listOf(target), now) is StoreEvidence.Likely)
        assertTrue(StoreEvidence.resolve(fix, listOf(target, Place("Walgreens", 33.00012, -111.0)), now) is StoreEvidence.Ambiguous)
        assertTrue(StoreEvidence.resolve(fix.copy(speedMps = 12.0), listOf(target), now) is StoreEvidence.Unknown)
        assertTrue(StoreEvidence.resolve(fix.copy(accuracyMeters = 500.0), listOf(target), now) is StoreEvidence.Unknown)
    }

    @Test fun keylessSearchDecodesRedirects() {
        assertEquals("https://example.com/a", KeylessWebSearch.realUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=x"))
        assertTrue(KeylessWebSearch.searchUrl("ddg_lite", "a b")!!.endsWith("q=a+b"))
    }

    // ── Cozmo (175/179): Android CozmoTest wire cases ──
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val p = CozmoProtocol

    @Test fun cozmoResetAndStopMatchReferenceBytes() {
        assertContentEquals(hex("434f5a0352450101010001000000"), p.encode(CozmoProtocol.Frame(1, 0, 0, p.OOB, emptyList())))
        assertContentEquals(hex("434f5a03524501070100010000000401003b"), p.encode(CozmoProtocol.Frame(7, 0, 0, p.OOB, listOf(p.command(0x3b)))))
    }

    @Test fun cozmoStrictParser() {
        val valid = p.encode(CozmoProtocol.Frame(9, 0, 0, p.OOB, listOf(CozmoProtocol.Packet(2))))
        assertNotNull(p.decode(valid))
        for (length in (0 until valid.size).filter { it != 14 }) assertNull(p.decode(valid.copyOf(length)), "length=$length")
        assertNull(p.decode(valid.copyOf().also { it[0] = 0 }))
        assertNull(p.decode(valid.copyOf().also { it[15] = 10 }))
    }

    @Test fun cozmoMotorsAreBounded() {
        assertEquals(60f, LeReader(p.wheels(999f, -999f).data).getFloat(0)); assertEquals(-60f, LeReader(p.wheels(999f, -999f).data).getFloat(4))
        assertEquals(92f, LeReader(p.lift(900f).data).getFloat(0))
        assertEquals((-25.0 * kotlin.math.PI / 180).toFloat(), LeReader(p.head(-900f).data).getFloat(0), 0.00001f)
        assertTrue(runCatching { p.head(Float.NaN) }.isFailure)
        assertTrue(runCatching { p.wheels(Float.NaN, 0f) }.isFailure)
        assertTrue(p.face(p.eyes("happy")).data.size < 1002)
    }

    @Test fun cozmoOutOfBandNeverAdvancesAck() {
        val wire = CozmoReliability()
        val second = CozmoProtocol.Frame(9, 1, 1, p.OOB, listOf(p.command(0xed)))
        assertTrue(wire.receive(second).isEmpty()); assertEquals(p.OOB, wire.ack)
        val event = CozmoProtocol.Frame(9, p.OOB, p.OOB, p.OOB, listOf(CozmoProtocol.Packet(5, 0xf0)))
        assertEquals(1, wire.receive(event).size); assertEquals(p.OOB, wire.ack)
        assertEquals(2, wire.receive(CozmoProtocol.Frame(9, 0, 0, p.OOB, listOf(CozmoProtocol.Packet(2)))).size)
        assertEquals(1, wire.ack)
        assertEquals(0, p.next(65533)); assertEquals(1, p.distance(65533, 0))
    }

    @Test fun cozmoGesturesAndVoiceRouting() {
        CozmoReactions.names.forEach { name ->
            val steps = CozmoReactions.steps(name)
            assertTrue(steps.isNotEmpty() && steps.last().atMs <= 3000)
            steps.forEach { s -> s.head?.let { assertTrue(it in -25f..44.5f) }; s.lift?.let { assertTrue(it in 32f..92f) } }
            assertEquals(name, Cozmo.parse("Cozmo $name"))
            assertNull(Cozmo.parse("LOOI $name"))
        }
        assertEquals("look_up", Cozmo.parse("Cozmo, look up"))
        assertEquals("light_blue", Cozmo.parse("cozmo lights blue"))
        assertEquals("stop", Cozmo.parse("Cozmo stop!"))
        assertEquals("status", Cozmo.parse("Cozmo status"))
        assertEquals("1.50", CozmoSession.fmt(1.5f, 2))
    }
}

class VendingAndDesktopTest {
    @Test fun androidExampleFormsRoundTrip() {
        val examples = listOf(com.meowmba.shared.ui.vending.GeneratedAvatarExamples.coffeeCup(),
            com.meowmba.shared.ui.vending.GeneratedAvatarExamples.spaceship(), com.meowmba.shared.ui.vending.GeneratedAvatarExamples.dragon())
        examples.forEach { f ->
            assertEquals(64, f.id.length)
            val again = com.meowmba.shared.ui.vending.GeneratedAvatarForms.parse(com.meowmba.shared.ui.vending.GeneratedAvatarForms.toJson(f))
            assertEquals(f, again)
        }
    }

    @Test fun formsRejectUnsafeOrUnboundedDrawings() {
        val P = com.meowmba.shared.ui.vending.GeneratedAvatarForms
        val ok = """{"version":1,"name":"Dot","description":"","parts":[{"shape":"ellipse","x":0,"y":0,"width":0.5,"height":0.5,"fill":"#FFAA00"}],"face":{"x":0,"y":-0.07,"eye_spacing":0.16,"eye_width":0.12,"eye_height":0.16,"mouth_y":0.15,"mouth_width":0.14,"iris":"#4A354A"}}"""
        assertEquals("Dot", P.parse(ok).name)
        assertFailsWith<IllegalArgumentException> { P.parse(ok.replace("\"x\":0,\"y\":0,\"width\":0.5", "\"x\":0.45,\"y\":0,\"width\":0.5")) }
        assertFailsWith<IllegalArgumentException> { P.parse(ok.replace("#FFAA00", "red")) }
        assertFailsWith<IllegalArgumentException> { P.parse(ok.replace("\"fill\"", "\"image_url\":\"http://x\",\"fill\"")) }
        assertFailsWith<IllegalArgumentException> { P.parse("$ok trailing") }
        // Motion aliases a model often invents are coerced, not rejected.
        assertEquals(com.meowmba.shared.ui.vending.GeneratedAvatarMotion.ARM,
            P.parse(ok.replace("\"fill\":\"#FFAA00\"", "\"fill\":\"#FFAA00\",\"motion\":\"hands\"")).parts.single().motion)
    }

    @Test fun propsParsePlaceAndRehash() {
        val raw = """{"version":1,"name":"Paper umbrella","description":"A tiny peach paper umbrella","category":"held","use_hint":"Hold above head","parts":[{"shape":"ellipse","x":0.0,"y":-0.05,"width":0.42,"height":0.22,"fill":"#F4C7A5"},{"shape":"round_rect","x":0.0,"y":0.12,"width":0.04,"height":0.28,"fill":"#8B6A4A"}],"anchor":{"x":0.0,"y":0.22}}"""
        val prop = com.meowmba.shared.ui.vending.PropInventor.parseResponse("```json\n$raw\n```")
        assertEquals(com.meowmba.shared.ui.vending.GeneratedPropCategory.HELD, prop.category)
        val moved = com.meowmba.shared.ui.vending.GeneratedPropForms.withPlacement(prop, .9f, .1f, 3f)
        assertEquals(.5f, moved.anchorX); assertEquals(2.5f, moved.attachScale)
        assertNotEquals(prop.id, moved.id)
        assertEquals(moved, com.meowmba.shared.ui.vending.GeneratedPropForms.parse(com.meowmba.shared.ui.vending.GeneratedPropForms.toJson(moved)).copy(id = moved.id))
        assertEquals(0f to -.12f, com.meowmba.shared.ui.vending.GeneratedPropForms.defaultAttach(com.meowmba.shared.ui.vending.GeneratedPropCategory.WEARABLE, "Sunglasses"))
    }

    @Test fun workshopPromptComposesParts() {
        assertEquals("fox barista, chibi style, peach fur, head round muzzle, tail fluffy",
            com.meowmba.shared.ui.vending.AvatarWorkshopPrompt.build("fox barista", "peach fur", "chibi", "round muzzle", "", "", "", "fluffy", ""))
        assertEquals("", com.meowmba.shared.ui.vending.AvatarWorkshopPrompt.build("", "", "cartoon", "", "", "", "", "", ""))
    }

    @Test fun desktopCommandsNeedAnExplicitComputer() {
        val D = com.meowmba.shared.bridge.DesktopCommand
        assertEquals(com.meowmba.shared.bridge.DesktopCommand.Action.OpenApp("Safari"), D.parse("On my computer, open Safari"))
        assertEquals(com.meowmba.shared.bridge.DesktopCommand.Action.Media("pause"), D.parse("pause the music on my Mac"))
        assertEquals(com.meowmba.shared.bridge.DesktopCommand.Action.OpenUrl("https://example.com"), D.parse("on the desktop open https://example.com"))
        assertEquals(com.meowmba.shared.bridge.DesktopCommand.Action.Build("tests"), D.parse("on my computer run build tests"))
        assertNull(D.parse("open Safari"))
        assertNull(D.parse("my computer is slow"))
    }

    @Test fun bridgeSigningMatchesTheDaemon() {
        // canonical = "POST\n/rpc\n{ts}\n{sha256(body)}" (bridge/meowmba-bridge.py)
        assertEquals("POST\n/rpc\n100\nabc", com.meowmba.shared.bridge.DesktopBridgeProtocol.canonicalMessage("POST", "/rpc", "100", "abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", com.meowmba.shared.bridge.DesktopBridgeProtocol.sha256Hex(ByteArray(0)))
    }
}
