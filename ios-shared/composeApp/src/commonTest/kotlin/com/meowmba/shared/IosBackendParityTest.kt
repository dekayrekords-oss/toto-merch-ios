package com.meowmba.shared

import com.meowmba.shared.core.*
import com.meowmba.shared.memory.*
import com.meowmba.shared.agent.*
import com.meowmba.shared.settings.*
import com.meowmba.shared.ui.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IosBackendParityTest {
    @Test fun backgroundRestoresSelectionAndBoundsDimming() {
        val disk = MemoryTextStorage()
        val bg = com.meowmba.shared.ui.background.BackgroundStore(disk)
        val aurora = com.meowmba.shared.ui.background.BackgroundSelection(
            com.meowmba.shared.ui.background.BackgroundKind.ANIMATION, "aurora", 110)
        bg.select(aurora)
        val restored = com.meowmba.shared.ui.background.BackgroundStore(disk)
        assertEquals(com.meowmba.shared.ui.background.BackgroundAnimation.AURORA, restored.selection.value.animation)
        // Android's dimming slider stops at 90%: a fully black scrim would hide the scene entirely.
        assertEquals(90, restored.selection.value.dimPercent)
        restored.select(com.meowmba.shared.ui.background.BackgroundSelection())
        assertNull(com.meowmba.shared.ui.background.BackgroundStore(disk).selection.value.animation)
    }

    @Test fun backgroundCarriesForwardTheOlderAnimationOnlySetting() {
        val disk = MemoryTextStorage()
        disk.writeTextAtomic("background_animation", "woodland")
        disk.writeTextAtomic("background_dim", "20")
        val restored = com.meowmba.shared.ui.background.BackgroundStore(disk).selection.value
        assertEquals(com.meowmba.shared.ui.background.BackgroundAnimation.WOODLAND, restored.animation)
        assertEquals(20, restored.dimPercent)
    }

    @Test fun memorySurvivesRestartAndIsActuallyRecalled() = runTest {
        val disk = MemoryTextStorage()
        val store = LongTermMemoryStore(disk, { 1000L })
        store.remember("My cat is named Pixel")
        val restored = LongTermMemoryStore(disk, { 1001L }).candidates()
        val results = RecallEngine.rank(LocalHashEmbedder().getEmbedding("What is my cat named?"), "What is my cat named?", restored)
        assertEquals("My cat is named Pixel", results.first().memory.content)
        assertTrue(results.first().reason.isNotBlank())
    }
    @Test fun pauseBlocksSavingButKeepsExistingMemory() = runTest {
        val disk = MemoryTextStorage(); val store = LongTermMemoryStore(disk)
        store.remember("Keep this"); store.setPaused(true)
        assertNull(store.remember("Do not save this"))
        val restored = LongTermMemoryStore(disk)
        assertTrue(restored.state.value.paused)
        assertEquals(listOf("Keep this"), restored.candidates().map { it.content })
    }
    @Test fun retentionAndDeletionSurviveRestart() = runTest {
        var now = 1000L; val disk = MemoryTextStorage()
        val store = LongTermMemoryStore(disk, { now })
        store.setRetention(30); store.remember("Old fact")
        now += 31L * 86_400_000
        assertTrue(store.candidates().isEmpty())
        val new = store.remember("New fact")!!; store.delete(new.id)
        assertTrue(LongTermMemoryStore(disk, { now }).candidates().isEmpty())
    }
    @Test fun exportContainsFactsAndPauseControlsWithoutChatHistory() = runTest {
        val store = LongTermMemoryStore(MemoryTextStorage()); store.remember("Likes tea")
        store.setPaused(true)
        val exported = store.export()
        assertTrue(exported.contains("Likes tea")); assertTrue(exported.contains("\"paused\":true"))
    }
    @Test fun themeAndBrightnessAreIndependentAndDurable() {
        val disk = MemoryTextStorage(); val store = AppearanceStore(disk)
        store.setMode(AppearanceMode.LIGHT); store.setTheme(AppTheme.DESK)
        val restored = AppearanceStore(disk)
        assertEquals(AppearanceMode.LIGHT, restored.mode.value)
        assertEquals(AppTheme.DESK, restored.theme.value)
        assertFalse(AppearanceMode.LIGHT.isDark(true)); assertTrue(AppearanceMode.SYSTEM.isDark(true))
    }
    @Test fun nativeProviderRequiresNoNetworkConfigAndStreamsViaDriver() = runTest {
        val previous = OnDeviceModel.driver
        try {
            OnDeviceModel.driver = object : OnDeviceDriver {
                override fun isInstalled() = true
                override fun install(onProgress: (Float) -> Unit, onComplete: () -> Unit, onError: (String) -> Unit) = onComplete()
                override fun generate(prompt: String, onToken: (String) -> Unit, onComplete: () -> Unit, onError: (String) -> Unit) { onToken("Pixel"); onComplete() }
                override fun cancel() {}
                override fun unload() {}
            }
            val config = ProviderConfig(kind = ProviderKind.ON_DEVICE)
            assertTrue(config.isConfigured); assertFalse(config.kind.needsApiKey)
            val events = AssistantEngine(config).stream("hello").toList()
            assertEquals("Pixel", events.filterIsInstance<ModelResponse.Partial>().single().text)
            assertEquals(1, events.filterIsInstance<ModelResponse.Complete>().size)
        } finally { OnDeviceModel.driver = previous }
    }
    @Test fun missingNativeModelIsAnHonestError() = runTest {
        val previous = OnDeviceModel.driver
        try {
            OnDeviceModel.driver = null
            assertTrue(OnDeviceProvider().generate("hello") is ModelResponse.Error)
        } finally { OnDeviceModel.driver = previous }
    }
    @Test fun damagedMemoryFileIsPreservedInsteadOfCrashingStartup() {
        val disk = MemoryTextStorage(mapOf(LongTermMemoryStore.KEY to "{broken"))
        val store = LongTermMemoryStore(disk)
        assertTrue(store.candidates().isEmpty())
        assertEquals("{broken", disk.readText("long_term_memory.corrupt.json"))
    }
    @Test fun menuContainsTheAndroidDestinationsInOrder() {
        assertEquals(16, meowMenuItems.size)
        assertEquals(listOf("Settings", "Flow", "Desk", "Memory", "Files"), meowMenuItems.take(5))
        assertTrue(meowMenuItems.none { it in setOf("Computer", "Projects", "Vending", "Studio") })
        assertEquals(meowMenuItems.size, meowMenuItems.distinct().size)
    }
}
