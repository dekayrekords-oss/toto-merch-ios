package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.attach.Attachments
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.memory.DateText
import com.meowmba.shared.vault.VaultRepository
import com.meowmba.shared.voice.SpeechText
import com.meowmba.shared.weather.WeatherCondition
import com.meowmba.shared.weather.WeatherRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android backends ported for iOS: attachments, weather parsing, spoken text, vault, dates. */
class IosBackendPortTest {

    @Test fun attachmentFindsAUpsLabelAndTellsMeowWhatWasShared() {
        val read = Attachments.read("label.jpg", Attachments.Kind.IMAGE, "image/jpeg",
            "UPS GROUND\nTRACKING #: 1Z 999 AA1 01 2345 6784\n1Z999AA10123456784")
        assertEquals(listOf(Attachments.Tracking("UPS", "1Z999AA10123456784")), read.tracking)
        val turn = Attachments.describe(read, "where is this?")
        assertTrue(turn.startsWith("where is this?\n\n[I shared a picture: label.jpg]"))
        assertTrue("Tracking number: 1Z999AA10123456784 (UPS)" in turn)
    }

    @Test fun attachmentNeverTreatsAPhoneNumberAsFedExWithoutContext() {
        assertTrue(Attachments.findTracking("Call 555123456789 for help").isEmpty())
        assertEquals("FedEx", Attachments.findTracking("FedEx tracking 123456789012").single().carrier)
    }

    @Test fun attachmentWithoutTextSaysSoForPicturesOnly() {
        assertTrue("No readable text" in Attachments.describe(Attachments.read("p.jpg", Attachments.Kind.PHOTO, "image/jpeg", ""), null))
        assertFalse("No readable text" in Attachments.describe(Attachments.read("a.zip", Attachments.Kind.FILE, "application/zip", ""), null))
    }

    @Test fun weatherParsesOpenMeteoAndRoundsLikeAndroid() {
        val body = """{"timezone":"America/Los_Angeles","current":{"temperature_2m":64.6,"relative_humidity_2m":58,
            "apparent_temperature":63.9,"weather_code":2,"wind_speed_10m":5.6,"is_day":1},
            "daily":{"temperature_2m_max":[83.2],"temperature_2m_min":[59.4]}}"""
        val now = assertNotNull(WeatherRepository(com.meowmba.shared.core.MemoryTextStorage()).parse(body))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, now.condition)
        assertEquals(65, now.temperatureF); assertEquals(83, now.highF); assertEquals(59, now.lowF)
        assertEquals(6, now.windMph); assertEquals("Los Angeles", now.place)
        assertEquals("It's 65° and partly cloudy. High of 83, low of 59.", now.spoken())
    }

    @Test fun weatherRejectsAResponseWithoutATemperature() {
        assertNull(WeatherRepository(com.meowmba.shared.core.MemoryTextStorage()).parse("""{"current":{},"daily":{}}"""))
    }

    @Test fun spokenRepliesSkipMarkdownCodeAndLinks() {
        val spoken = SpeechText.forSpeech("## Plan\n- **Buy** milk\n- see [the list](https://x.test/a)\n```kotlin\nval a = 1\n```\nDone 🎉")
        assertEquals("Plan\nBuy milk\nsee the list\n(code shown on screen)\nDone", spoken)
    }

    @Test fun localStampsRoundTripAcrossOffsets() {
        val millis = 1_790_000_000_000L
        for (offset in listOf(-420, 0, 330)) {
            val stamp = DateText.localStamp(millis, offset)
            assertEquals(millis, DateText.parseLocalStamp(stamp, offset))
        }
        assertNull(DateText.parseLocalStamp("yesterday"))
    }

    @Test fun vaultSavesSearchesAndReadsBackObsidianNotes() {
        val vault = VaultRepository(ModelFile(PlatformTemp.createDirectory("vault-test")))
        vault.save("Groceries", "Milk and [[Bread]]", listOf("home"))
        vault.save("Work/Standup", "Ship build 172")
        assertEquals(setOf("Groceries", "Work/Standup"), vault.list().map { it.title }.toSet())
        assertEquals(listOf("Groceries"), vault.search("milk").map { it.title })
        assertEquals(listOf("home"), vault.get("Groceries")?.tags)
        assertEquals(listOf("Bread"), vault.missingLinks(vault.get("Groceries")!!))
        assertEquals("Milk and [[Bread]]\n\nand eggs", vault.append("Groceries", "and eggs").body)
        assertTrue(vault.delete("Groceries"))
        assertNull(vault.get("Groceries"))
    }
}
