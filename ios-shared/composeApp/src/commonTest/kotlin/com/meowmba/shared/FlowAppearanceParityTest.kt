package com.meowmba.shared

import com.meowmba.shared.core.flow.*
import kotlin.test.*

class FlowThemesTest {
    @Test fun everyThemeHasReadableTextAndAccentText() {
        for (t in FlowThemes.all) for (night in listOf(false, true)) {
            val p = FlowThemes.palette(t.key, night)
            val tag = "${t.key}/${if (night) "night" else "day"}"
            assertTrue(FlowThemes.contrast(p.ink, p.paper) >= 7.0, "$tag ink")
            assertTrue(FlowThemes.contrast(p.ink2, p.paper) >= 4.5, "$tag ink2")
            assertTrue(FlowThemes.contrast(p.accent, p.paper) >= 4.5, "$tag accent on paper")
            assertTrue(FlowThemes.contrast(p.onAccent, p.accent) >= 4.5, "$tag onAccent")
        }
    }

    @Test fun paperNightAccentGetsDarkText() {
        assertEquals(FlowThemes.DARK_ON_ACCENT, FlowThemes.palette("paper", night = true).onAccent)
        assertEquals(FlowThemes.LIGHT_ON_ACCENT, FlowThemes.palette("paper", night = false).onAccent)
    }

    @Test fun userAccentOverridesAndPicksReadableText() {
        val yellow = 0xFFFFE27A.toInt()
        val p = FlowThemes.palette("linen", night = false, accentOverride = yellow)
        assertEquals(yellow, p.accent)
        assertEquals(FlowThemes.DARK_ON_ACCENT, p.onAccent)
    }

    @Test fun eveningLookFollowsWorkOnAuto() {
        assertTrue(FlowThemes.isNight(EveningLook.AUTO, WorkState.OFF))
        assertFalse(FlowThemes.isNight(EveningLook.AUTO, WorkState.WRAPPING))
        assertTrue(FlowThemes.isNight(EveningLook.NIGHT, WorkState.WORKING))
        assertFalse(FlowThemes.isNight(EveningLook.DAY, WorkState.OFF))
    }
}

class FlowSettingsStoreTest {
    private class MapKv : FlowKeyValue {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putAll(values: Map<String, String?>) = values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }

    @Test fun defaultsMatchOwnerDecisions() {
        val s = FlowSettingsStore(MapKv()).load()
        assertEquals(FlowSettings(), s)
        assertTrue(s.voice && s.notifyOtherApps && s.detectWorkEnd)
        assertEquals(3, s.maxPerDay); assertEquals(60, s.minGapMinutes); assertEquals(Urgency.WORTH_IT, s.minUrgency)
        assertFalse(s.allowJev)
    }

    @Test fun roundTrips() {
        val kv = MapKv(); val store = FlowSettingsStore(kv)
        val s = FlowSettings(voice = false, maxPerDay = 5, minGapMinutes = 30, minUrgency = Urgency.URGENT,
            theme = "stone", accent = 0xFF2F7A3E.toInt(), eveningLook = EveningLook.NIGHT, allowJev = true)
        store.save(s)
        assertEquals(s, store.load())
        assertEquals("#2F7A3E", kv.map[FlowSettingsStore.K_ACCENT])
    }

    @Test fun garbageIsNormalized() {
        val kv = MapKv().apply {
            map[FlowSettingsStore.K_MAX] = "99"; map[FlowSettingsStore.K_GAP] = "50"
            map[FlowSettingsStore.K_URGENCY] = "NOT_NOW"; map[FlowSettingsStore.K_THEME] = "neon"
            map[FlowSettingsStore.K_ACCENT] = "#zzz"; map[FlowSettingsStore.K_VOICE] = "maybe"
        }
        val s = FlowSettingsStore(kv).load()
        assertEquals(10, s.maxPerDay); assertEquals(60, s.minGapMinutes); assertEquals(Urgency.USEFUL, s.minUrgency)
        assertEquals("paper", s.theme); assertNull(s.accent); assertTrue(s.voice)
    }
}

