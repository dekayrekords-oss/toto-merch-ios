package com.meowmba.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meowmba.shared.agent.AssistantEngine
import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.agent.SelfStatusParser
import com.meowmba.shared.companion.CatAvatarStateMachine
import com.meowmba.shared.companion.CompanionEmotion
import com.meowmba.shared.companion.CompanionStateCore
import com.meowmba.shared.companion.InteractionState
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.memory.ChatMessage
import com.meowmba.shared.memory.ConversationStore
import com.meowmba.shared.memory.MessageRole
import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import com.meowmba.shared.settings.ProviderPresets
import com.meowmba.shared.settings.SettingsStore
import com.meowmba.shared.voice.SpeechText
import com.meowmba.shared.voice.SpokenText
import com.meowmba.shared.voice.WakePhraseMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Shared companion screen: animated cat avatar + streaming chat workspace.
 *
 * This is a real pipeline now, not an echo harness. A turn goes to the
 * configured provider, streams back token by token, and is persisted; when
 * nothing is configured the app says so instead of inventing a reply.
 */
class CompanionViewModel(
    private val settings: SettingsStore = SettingsStore(),
    private val conversation: ConversationStore = ConversationStore(),
    private val engineFactory: (ProviderConfig) -> AssistantEngine = { AssistantEngine(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    val memory = com.meowmba.shared.memory.LongTermMemoryStore()
    /** Obsidian-style notes (Android Vault); the Notes tile opens it. */
    val vault by lazy { com.meowmba.shared.vault.VaultRepository() }
    val memoryNotice = MutableStateFlow<String?>(null)
    fun remember(text: String) { scope.launch {
        memoryNotice.value = try { if (memory.remember(text) != null) "Saved to memory" else "Memory is paused or empty" }
        catch (e: Exception) { "Could not save memory: " + e.message }
    } }
    private val flowStore = HomeFlowStore()
    internal val flowNotes: StateFlow<List<FlowNote>> = flowStore.notes
    internal val avatarMode = MutableStateFlow(
        runCatching { AvatarMode.valueOf(DefaultTextStorage.readText("avatar_mode.txt") ?: "LUMEN") }
            .getOrDefault(AvatarMode.LUMEN)
    )

    internal fun selectAvatarMode(mode: AvatarMode) {
        DefaultTextStorage.writeTextAtomic("avatar_mode.txt", mode.name)
        avatarMode.value = mode
    }

    internal fun keepFlowNote(text: String) = flowStore.keep(text)

    // ── North Stars (Android build 172): goals linked to local evidence, never auto-completed. ──
    val northStars = com.meowmba.shared.core.northstar.NorthStars().also { northStarsForSnapshot = it }

    /** Evidence iOS actually has: kept Flow items, phone context, and what you said to Meow. */
    fun feedNorthStars() {
        fun E(key: String, text: String, at: Long) = com.meowmba.shared.core.northstar.StarEvidence(key, text, at)
        northStars.ingest(
            flowNotes.value.filter { !it.done }.map { E("flow:${it.id}", it.text + " " + it.comment.orEmpty(), it.createdAt) } +
                com.meowmba.shared.core.flow.FlowContextState.items.value.map { E("flow:${it.intentionId}", it.headline + " " + it.because, PlatformTime.currentMillis()) } +
                conversation.messages.value.takeLast(100).filter { it.role == MessageRole.USER }
                    .map { E("journal:${it.timestamp}", it.content, it.timestamp) })
    }

    // ── Screen help and QR (Android build 172). ──
    val screenHelp = MutableStateFlow<com.meowmba.shared.core.screen.ScreenHelpState?>(null)
    private var screenHelpJob: kotlinx.coroutines.Job? = null

    fun openSharedScreen(shared: com.meowmba.shared.platform.SharedScreen) {
        screenHelpJob?.cancel()
        val now = PlatformTime.currentMillis()
        screenHelp.value = when {
            !com.meowmba.shared.core.screen.ScreenHelp.fresh(shared.observedAt, now) ->
                com.meowmba.shared.core.screen.ScreenHelpState(shared.observedAt, error = "That screen capture has expired. Share a fresh screen to get help.")
            shared.error != null -> com.meowmba.shared.core.screen.ScreenHelpState(shared.observedAt, error = shared.error)
            shared.text.isBlank() -> com.meowmba.shared.core.screen.ScreenHelpState(shared.observedAt,
                error = "I couldn't read text from this picture. Some apps hide their content; try a clearer screenshot.")
            else -> com.meowmba.shared.core.screen.ScreenHelpState(shared.observedAt, text = shared.text.take(6000))
        }
    }

    fun closeScreenHelp() { screenHelpJob?.cancel(); screenHelpJob = null; screenHelp.value = null }

    fun askAboutSharedScreen(question: String, useConfiguredAi: Boolean) {
        val captured = screenHelp.value ?: return
        if (captured.reading || captured.busy || captured.text.isBlank()) return
        if (!com.meowmba.shared.core.screen.ScreenHelp.fresh(captured.observedAt, PlatformTime.currentMillis())) {
            screenHelp.value = captured.copy(error = "This picture is over ten minutes old. Share a fresh screen first."); return
        }
        screenHelp.value = captured.copy(busy = true, error = null, answer = null)
        screenHelpJob = scope.launch {
            val prompt = com.meowmba.shared.core.screen.ScreenHelp.prompt(captured.text, question)
            val provider = if (useConfiguredAi) AssistantEngine.providerFor(settings.config.value) else com.meowmba.shared.agent.OnDeviceProvider()
            val result = kotlinx.coroutines.withTimeoutOrNull(60_000) { runCatching { provider.generate(prompt) }.getOrElse { com.meowmba.shared.agent.ModelResponse.Error(it.message ?: "") } }
            val wid = work.start("Screen help: " + question.take(80), "screen")
            screenHelp.value = when (result) {
                null -> captured.copy(error = "That answer took too long. Try a shorter question.")
                is com.meowmba.shared.agent.ModelResponse.Complete -> captured.copy(answer = result.text.take(12000).ifBlank { "The model returned no answer. Please try again." })
                is com.meowmba.shared.agent.ModelResponse.Error -> captured.copy(error = if (useConfiguredAi) result.message.take(400).ifBlank { "Your configured AI couldn't answer." }
                    else "The on-device model couldn't answer (${result.message.take(200)}). Download it in LLM, or tick ‘Use my configured AI’.")
                else -> captured.copy(error = "The model didn't finish its answer.")
            }
            screenHelp.value?.let { st -> work.finish(wid, st.answer != null, com.meowmba.shared.work.WorkOutcome.RESULT, st.answer ?: st.error.orEmpty()) }
        }
    }

    private fun scanCode() {
        val scanner = com.meowmba.shared.platform.PlatformDrivers.scanner ?: return reply("Code scanning isn't available on this device.")
        busy.value = true
        scanner.scan({ value ->
            reply(if (value.isNullOrBlank()) "Scan cancelled. No code was saved or opened." else "I scanned this code: ${value.take(8192)}\nNothing was opened automatically.")
        }, { reply(it) })
    }

    // ── Become anything (Android avatar.form + Vending Machine). ──
    private fun becomeAvatar(wanted: String) {
        val vending = com.meowmba.shared.ui.vending.Vending
        val builtIn = com.meowmba.shared.agent.runtime.AvatarAnimal.resolve(wanted)
        busy.value = true
        if (builtIn != null) {
            vending.wear(null)
            val current = AvatarAppearanceStore.state.value
            AvatarAppearanceStore.update(current.copy(animal = com.meowmba.shared.ui.lumen.AvatarAnimal.fromKey(builtIn.key)))
            return reply(if (builtIn == com.meowmba.shared.agent.runtime.AvatarAnimal.NONE) "Back to my usual look." else "Okay — I'm a ${builtIn.label.lowercase()} now.")
        }
        vending.createAvatar(wanted)
        val id = work.start("Become $wanted".take(80), "avatar")
        activeJob = scope.launch {
            kotlinx.coroutines.delay(200)
            vending.avatarBusy.first { !it }
            val (msg, error) = vending.avatarMessage.value ?: ("Done." to false)
            work.finish(id, !error, com.meowmba.shared.work.WorkOutcome.VERIFIED, msg)
            reply(msg)
        }
    }

    // ── Store and deals, on request only (Android build 177). ──
    private val shopping by lazy {
        val web = com.meowmba.shared.core.shopping.KeylessSearchClient()
        com.meowmba.shared.core.shopping.StoreShopping(
            search = web::search,
            nearby = {
                val driver = com.meowmba.shared.platform.PlatformDrivers.places ?: error("no places")
                kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        driver.nearbyStores { fix, places, _ -> if (cont.isActive) cont.resumeWith(Result.success(
                            com.meowmba.shared.core.shopping.StoreShopping.parseNearby(fix, places))) }
                    }
                }
            },
            nowMs = PlatformTime::currentMillis,
            today = { com.meowmba.shared.core.shopping.DealEvidence.Day.of(PlatformTime.currentMillis(), PlatformTime.utcOffsetMinutes()) },
        )
    }

    private fun runShopping(request: com.meowmba.shared.core.shopping.ShoppingRequest) {
        val id = work.start(request.store?.let { "Deals at $it" + (request.item?.let { i -> " · $i" } ?: "") } ?: "Which store am I in?", "shopping")
        busy.value = true
        activeJob = scope.launch {
            val text = kotlinx.coroutines.withTimeoutOrNull(50_000) { shopping.run(request) }
                ?: "That lookup took too long. I haven't verified any offers."
            work.finish(id, true, com.meowmba.shared.work.WorkOutcome.RESULT, text)
            reply(text)
        }
    }

    // ── Flow comments (Android build 178). ──
    internal fun commentFor(id: String): String? =
        if (id.startsWith("ctx:")) com.meowmba.shared.core.flow.FlowContextState.comments.value[id]
        else flowNotes.value.firstOrNull { it.id == id }?.comment

    internal fun commentOnFlow(item: com.meowmba.shared.core.flow.FlowItem, text: String, done: (String) -> Unit) {
        val target = com.meowmba.shared.core.flow.FlowComments.target(item.intentionId)
        if (com.meowmba.shared.core.flow.FlowComments.requestsCancellation(text)) {
            val cal = com.meowmba.shared.platform.PlatformDrivers.calendar
            if (target == null || cal == null) return done("Nothing was canceled: this Flow has no exact calendar event.")
            return cal.requestAccess { granted, reason ->
                if (!granted) return@requestAccess done(reason ?: "Calendar access is needed to cancel it.")
                cal.cancelEvent(target.startMs, target.title) { ok, message ->
                    if (ok) com.meowmba.shared.core.flow.FlowContextState.dismiss(item.intentionId)
                    done(message)
                }
            }
        }
        if (item.intentionId.startsWith("ctx:")) com.meowmba.shared.core.flow.FlowContextState.comment(item.intentionId, text)
        else flowStore.comment(item.intentionId, text)
        feedNorthStars()
        done("Saved on this Flow. It isn't sent to anyone.")
    }
    internal fun completeFlowNote(id: String) {
        val ctx = com.meowmba.shared.core.flow.FlowContextState
        when {
            id.startsWith("ctx:reminder:") -> {
                ctx.dismiss(id)
                com.meowmba.shared.platform.PlatformDrivers.calendar?.completeReminder(id.removePrefix("ctx:reminder:")) { }
            }
            id.startsWith("ctx:") -> ctx.dismiss(id)
            else -> flowStore.done(id)
        }
    }
    internal fun deferFlowNote(id: String) =
        if (id.startsWith("ctx:")) com.meowmba.shared.core.flow.FlowContextState.later(id, PlatformTime.currentMillis()) else flowStore.later(id)
    internal fun askFromFlow(text: String) { onInputChange(text); send() }

    val state = MutableStateFlow(
        CompanionStateCore(interactionState = InteractionState.IDLE)
    )

    val messages: StateFlow<List<ChatMessage>> = conversation.messages
    val input = MutableStateFlow("")
    val config: StateFlow<ProviderConfig> = settings.config
    val settingsOpen = MutableStateFlow(false)
    val busy = MutableStateFlow(false)

    // ── Chat sheet, microphone, speech and camera (Android: isChatExpanded, isMicEnabled,
    //    listening, callMode, isCameraEnabled). Native work goes through PlatformDrivers.
    val chatExpanded = MutableStateFlow(false)
    /** Flow's context line (Android signals: place, app, calendar); filled by platform context. */
    val flowSignals = MutableStateFlow<List<com.meowmba.shared.core.flow.Signal>>(emptyList())
    fun openChat() { chatExpanded.value = true }
    fun closeChat() { chatExpanded.value = false }
    fun deleteMessage(index: Int) = conversation.removeAt(index)

    /** App-wide mic privacy latch (Android MicKillButton); persisted, on by default like Android. */
    val micEnabled = MutableStateFlow(DefaultTextStorage.readText("mic_enabled") != "false")
    /** Spoken replies (Android Privacy → Companion Voice). */
    val companionVoice = MutableStateFlow(DefaultTextStorage.readText("companion_voice") != "false")
    val listening = MutableStateFlow(false)
    val listenPartial = MutableStateFlow<String?>(null)
    val voiceIssue = MutableStateFlow<String?>(null)
    val callActive = MutableStateFlow(false)
    val cameraEnabled = MutableStateFlow(false)
    val cameraStatus = MutableStateFlow("Camera off")
    private var voiceTurn = false
    /** Call turns within this long of the last message count as the same conversation. */
    private val VOICE_CONVERSATION_WINDOW_MS = 30_000L

    fun setMicEnabled(on: Boolean) {
        DefaultTextStorage.writeTextAtomic("mic_enabled", on.toString())
        micEnabled.value = on
        if (!on) { stopListening(); callActive.value = false }
        state.value = state.value.copy(isMicEnabled = on)
    }

    fun setCompanionVoice(on: Boolean) {
        DefaultTextStorage.writeTextAtomic("companion_voice", on.toString())
        companionVoice.value = on
        if (!on) com.meowmba.shared.platform.PlatformDrivers.speechOut?.stop()
    }

    /**
     * Hands-free call turns can pick up clicks, "Oh no", "Great" and background talk; those must not
     * cost a model call. A single mic tap is explicit, so only noise is dropped then.
     */
    private fun voiceTurnWanted(heard: String): Boolean {
        val now = com.meowmba.shared.core.PlatformTime.currentMillis()
        val last = conversation.messages.value.lastOrNull()
        val lastMeow = conversation.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }
        return com.meowmba.shared.voice.VoiceTurnGate.decide(
            heard,
            addressed = !callActive.value || WakePhraseMatcher.match(heard) != null,
            inConversation = last != null && now - last.timestamp in 0..VOICE_CONVERSATION_WINDOW_MS,
            meowAskedQuestion = lastMeow != null && now - lastMeow.timestamp in 0..60_000L &&
                lastMeow.content.trim().endsWith("?"),
        ) == com.meowmba.shared.voice.VoiceTurnGate.Decision.ACCEPT
    }

    /** Big mic: tap to talk, tap again to finish. */
    fun onMicClick() { if (listening.value) stopListening() else startListening() }

    /** Voice call: hands-free turns until ended. Each reply is spoken, then listening resumes. */
    fun toggleCall() {
        if (callActive.value) { callActive.value = false; stopListening()
            com.meowmba.shared.platform.PlatformDrivers.speechOut?.stop(); return }
        callActive.value = true
        startListening()
    }

    fun startListening() {
        voiceIssue.value = null
        if (!micEnabled.value) { voiceIssue.value = "The microphone is off. Tap MIC OFF to turn it on."; callActive.value = false; return }
        val driver = com.meowmba.shared.platform.PlatformDrivers.speechIn
        if (driver == null) { voiceIssue.value = "Voice input is not available on this device."; callActive.value = false; return }
        if (busy.value) cancel()
        com.meowmba.shared.platform.PlatformDrivers.speechOut?.stop()
        driver.requestPermission { granted, reason ->
            if (!granted) {
                voiceIssue.value = reason ?: "Microphone or speech permission was not granted."
                callActive.value = false
                return@requestPermission
            }
            listening.value = true
            listenPartial.value = null
            state.value = state.value.copy(interactionState = InteractionState.LISTENING, errorMessage = null)
            driver.start(
                onPartial = { listenPartial.value = it },
                onFinal = { text ->
                    listening.value = false
                    listenPartial.value = null
                    if (state.value.interactionState == InteractionState.LISTENING)
                        state.value = state.value.copy(interactionState = InteractionState.IDLE)
                    val heard = SpokenText.polish(text)
                    if (SpokenText.isUsable(heard) && voiceTurnWanted(heard)) { voiceTurn = true; onInputChange(heard); send() }
                    else if (callActive.value) startListening()
                },
                onError = { problem ->
                    listening.value = false
                    listenPartial.value = null
                    voiceIssue.value = problem
                    callActive.value = false
                    if (state.value.interactionState == InteractionState.LISTENING)
                        state.value = state.value.copy(interactionState = InteractionState.IDLE)
                },
            )
        }
    }

    fun stopListening() {
        if (listening.value) com.meowmba.shared.platform.PlatformDrivers.speechIn?.stop()
    }

    /** "Be quiet" stops speech until the person addresses Meow again (Android stayQuiet, 176). */
    val quietUntilAddressed = MutableStateFlow(DefaultTextStorage.readText("quiet_until_addressed") == "true")
    private fun setQuiet(on: Boolean) {
        if (quietUntilAddressed.value == on) return
        quietUntilAddressed.value = on
        DefaultTextStorage.writeTextAtomic("quiet_until_addressed", on.toString())
    }

    private fun speakReply(text: String) {
        val out = com.meowmba.shared.platform.PlatformDrivers.speechOut
        if (!companionVoice.value || out == null || text.isBlank() || quietUntilAddressed.value) { if (callActive.value) startListening(); return }
        state.value = state.value.copy(interactionState = InteractionState.SPEAKING)
        val spoken = SpeechText.forSpeech(text)
        fun finished() {
            if (state.value.interactionState == InteractionState.SPEAKING)
                state.value = state.value.copy(interactionState = InteractionState.IDLE)
            if (callActive.value) startListening()
        }
        scope.launch {
            val wav = runCatching {
                when (com.meowmba.shared.voice.TotoVoicePreferences.source) {
                    com.meowmba.shared.voice.TotoVoiceSource.ON_PHONE ->
                        com.meowmba.shared.voice.TotoOnDeviceVoice.render(spoken)
                    com.meowmba.shared.voice.TotoVoiceSource.LION ->
                        com.meowmba.shared.voice.TotoLionVoice.render(spoken)
                    com.meowmba.shared.voice.TotoVoiceSource.SYSTEM -> null
                }
            }.onFailure { println("[TotoSpeech] on-device voice failed; falling back to Apple voice: ${it.message}") }
                .getOrNull()
            // On-phone Kokoro when the runtime is ready; otherwise the iPhone's
            // built-in voice is the emergency fallback. Lion is opt-in only.
            if (wav != null) out.playWav(wav) { finished() } else out.speak(spoken) { finished() }
        }
    }

    // ── Weather (Android WeatherRepository: Open-Meteo, no key). The card appears whenever a
    //    reading is surfaced; ambient mode refreshes the sky every quarter hour without a card.
    val weatherRepo = com.meowmba.shared.weather.WeatherRepository()
    val weather: StateFlow<com.meowmba.shared.weather.WeatherNow?> = weatherRepo.latest
    val ambientWeather = MutableStateFlow<com.meowmba.shared.weather.WeatherNow?>(null)
    val ambientWeatherEnabled = MutableStateFlow(DefaultTextStorage.readText("weather_ambient") == "true")
    val weatherStatus = MutableStateFlow<String?>(null)
    val weatherPlace = MutableStateFlow(weatherRepo.selectedPlace)
    val previewTimeOfDay = MutableStateFlow<com.meowmba.shared.weather.TimeOfDay?>(null)
    private var ambientJob: Job? = null

    fun refreshWeather() { scope.launch {
        weatherStatus.value = "Checking the weather…"
        weatherStatus.value = when (val r = weatherRepo.fetch()) {
            is com.meowmba.shared.weather.WeatherRepository.Result.Ok -> { ambientWeather.value = r.weather; null }
            is com.meowmba.shared.weather.WeatherRepository.Result.Failed -> r.reason
        }
    } }
    fun setWeatherPlace(place: String) {
        weatherRepo.setSelectedPlace(place); weatherPlace.value = weatherRepo.selectedPlace
        if (place.isNotBlank()) refreshWeather()
    }
    fun dismissWeatherCard() = weatherRepo.clear()
    fun cyclePreviewTimeOfDay() {
        val all = com.meowmba.shared.weather.TimeOfDay.entries
        previewTimeOfDay.value = previewTimeOfDay.value?.let { all.getOrNull(it.ordinal + 1) } ?: all.first()
    }
    fun setAmbientWeather(on: Boolean) {
        ambientWeatherEnabled.value = on
        DefaultTextStorage.writeTextAtomic("weather_ambient", on.toString())
        ensureAmbientWeather()
    }
    /** Runs while ambient weather or the live-sky background needs a current reading. */
    fun ensureAmbientWeather() {
        val wanted = ambientWeatherEnabled.value || com.meowmba.shared.ui.background.backgroundStore.selection.value.kind ==
            com.meowmba.shared.ui.background.BackgroundKind.LIVE_WEATHER
        if (!wanted) { ambientJob?.cancel(); ambientJob = null; return }
        if (ambientJob?.isActive == true) return
        ambientJob = scope.launch {
            while (true) {
                (weatherRepo.fetch(surface = false) as? com.meowmba.shared.weather.WeatherRepository.Result.Ok)
                    ?.let { ambientWeather.value = it.weather }
                kotlinx.coroutines.delay(15 * 60_000L)
            }
        }
    }

    /** Android answers weather ahead of the model: an instant lookup, spoken and shown as a card. */
    private fun answerWeather(text: String, history: List<ChatMessage>) {
        val place = Regex("(?i)\\b(?:in|for|at)\\s+([A-Za-z][A-Za-z .,'-]{1,80})\\??\\s*$").find(text)
            ?.groupValues?.get(1)?.trim()?.trimEnd('.', '?', '!')?.takeIf { it.lowercase() !in setOf("today", "tomorrow", "here", "now") }
        busy.value = true
        activeJob = scope.launch {
            val reply = when (val r = if (place != null) weatherRepo.fetchPlace(place) else weatherRepo.fetch()) {
                is com.meowmba.shared.weather.WeatherRepository.Result.Ok -> {
                    ambientWeather.value = r.weather
                    (r.weather.place?.let { "In $it: " } ?: "") + r.weather.spoken()
                }
                is com.meowmba.shared.weather.WeatherRepository.Result.Failed -> r.reason
            }
            conversation.append(ChatMessage(MessageRole.ASSISTANT, reply, PlatformTime.currentMillis()))
            busy.value = false
            val spoken = voiceTurn || callActive.value
            voiceTurn = false
            if (spoken) speakReply(reply)
        }
    }

    // ── Idle avatar (Android IdleAvatar): after N minutes without a touch on Flow/Desk, Meow rests on screen.
    val idleAvatar = MutableStateFlow(DefaultTextStorage.readText(IdleAvatar.PREF_MODE)
        ?.takeIf { k -> com.meowmba.shared.ui.flow.IdleAvatarOptions.MODES.any { it.first == k } } ?: IdleAvatar.DEFAULT_KEY)
    val idleMinutes = MutableStateFlow(DefaultTextStorage.readText(IdleAvatar.PREF_MINUTES)?.toIntOrNull()
        ?.takeIf { it in com.meowmba.shared.ui.flow.IdleAvatarOptions.MINUTES } ?: com.meowmba.shared.ui.flow.IdleAvatarOptions.DEFAULT_MINUTES)
    fun setIdleAvatar(key: String, minutes: Int) {
        idleAvatar.value = key; idleMinutes.value = minutes
        DefaultTextStorage.writeTextAtomic(IdleAvatar.PREF_MODE, key)
        DefaultTextStorage.writeTextAtomic(IdleAvatar.PREF_MINUTES, minutes.toString())
    }

    // ── Acting on the phone (Settings › Phone & device › "Let Meow act on this phone") ──
    val deviceActions = MutableStateFlow(DefaultTextStorage.readText("device_actions") != "false")
    fun setDeviceActions(on: Boolean) { deviceActions.value = on; DefaultTextStorage.writeTextAtomic("device_actions", on.toString()) }

    private fun reply(text: String) {
        conversation.append(ChatMessage(MessageRole.ASSISTANT, text, PlatformTime.currentMillis()))
        busy.value = false
        val spoken = voiceTurn || callActive.value
        voiceTurn = false
        if (spoken) speakReply(text)
    }

    fun openExternal(url: String, done: (Boolean) -> Unit) = openUrl(url, done)

    private fun openUrl(url: String, done: (Boolean) -> Unit) {
        val apps = com.meowmba.shared.platform.PlatformDrivers.apps ?: return done(false)
        apps.open(url, done)
    }

    // ── Meow's work (Android build 182): every action leaves an honest, durable receipt. ──
    val work = com.meowmba.shared.work.WorkJournal()
    init {
        com.meowmba.shared.ui.vending.Vending.provider = { AssistantEngine.providerFor(settings.config.value) }
        com.meowmba.shared.ui.vending.Vending.providerIsRemote = { settings.config.value.kind != ProviderKind.ON_DEVICE }
        computerWork = work
    }
    val workOpen = MutableStateFlow<String?>(null)   // "" = list, else a task id
    fun openWork(id: String? = null) { workOpen.value = id ?: "" }
    fun closeWork() { workOpen.value = null }
    fun linkWorkToNorthStar(task: com.meowmba.shared.work.WorkTask, starId: String): String {
        northStars.linkResult(starId, "task:${task.id}", task.title, task.result.orEmpty(), task.completedAt ?: task.createdAt)
        return "Linked to your North Star. Goal progress is unchanged."
    }

    private fun runDeviceAction(action: com.meowmba.shared.platform.DeviceAction) {
        // mailto:/sms: bodies don't decode '+' as a space; a literal '+' is already %2B.
        val q = { v: String -> com.meowmba.shared.voice.UrlCodec.encodeQuery(v).replace("+", "%20") }
        val offset = PlatformTime.utcOffsetMinutes()
        val (title, kind) = when (action) {
            is com.meowmba.shared.platform.DeviceAction.OpenApp -> "Open ${action.app.name}" to "app"
            is com.meowmba.shared.platform.DeviceAction.RunShortcut -> "Run shortcut “${action.name}”" to "shortcut"
            is com.meowmba.shared.platform.DeviceAction.Email -> "Email draft to ${action.to}" to "email"
            is com.meowmba.shared.platform.DeviceAction.Text -> "Text draft to ${action.to}" to "message"
            is com.meowmba.shared.platform.DeviceAction.Remind -> "Reminder: ${action.title}" to "reminder"
            is com.meowmba.shared.platform.DeviceAction.AddEvent -> "Calendar: ${action.title}" to "calendar"
            is com.meowmba.shared.platform.DeviceAction.ReadCalendar -> "Read ${if (action.tomorrow) "tomorrow's" else "today's"} calendar" to "calendar"
        }
        val id = work.start(title, kind)
        busy.value = true
        // Receipts say what Meow knows: a draft is handed off, a saved reminder is verified (Android 176/182).
        fun receipt(ok: Boolean, outcome: com.meowmba.shared.work.WorkOutcome, text: String) {
            work.finish(id, ok, outcome, text); reply(text)
        }
        val HANDOFF = com.meowmba.shared.work.WorkOutcome.HANDOFF
        val VERIFIED = com.meowmba.shared.work.WorkOutcome.VERIFIED
        val UNCONFIRMED = com.meowmba.shared.work.WorkOutcome.UNCONFIRMED
        val RESULT = com.meowmba.shared.work.WorkOutcome.RESULT
        fun calendar(use: (com.meowmba.shared.platform.CalendarDriver) -> Unit) {
            val cal = com.meowmba.shared.platform.PlatformDrivers.calendar ?: return receipt(false, RESULT, "Calendar and Reminders aren't available on this device.")
            cal.requestAccess { granted, reason -> if (granted) use(cal) else receipt(false, RESULT, reason ?: "I need Calendar and Reminders access for that.") }
        }
        when (action) {
            is com.meowmba.shared.platform.DeviceAction.OpenApp -> openUrl(action.app.url) { ok ->
                receipt(ok, HANDOFF, if (ok) "Opened ${action.app.name}." else "I couldn't open ${action.app.name}.") }
            is com.meowmba.shared.platform.DeviceAction.RunShortcut -> openUrl("shortcuts://run-shortcut?name=" + q(action.name)) { ok ->
                receipt(ok, UNCONFIRMED, if (ok) "Asked Shortcuts to run “${action.name}”. Shortcuts shows whether it finished." else "I couldn't reach Shortcuts.") }
            is com.meowmba.shared.platform.DeviceAction.Email -> openUrl("mailto:${action.to}?subject=${q(action.subject)}&body=${q(action.body)}") { ok ->
                receipt(ok, HANDOFF, if (ok) "Email draft to ${action.to} is open in Mail. Review it and tap Send there; Meow has not sent it." else "I couldn't open Mail.") }
            is com.meowmba.shared.platform.DeviceAction.Text -> openUrl("sms:${action.to}&body=${q(action.body)}") { ok ->
                receipt(ok, HANDOFF, if (ok) "Text to ${action.to} is ready in Messages. Review it and tap Send there; Meow has not sent it." else "I couldn't open Messages.") }
            is com.meowmba.shared.platform.DeviceAction.Remind -> calendar { cal ->
                cal.addReminder(action.title, action.dueMs) { ok, msg ->
                    receipt(ok, VERIFIED, if (ok) "Reminder saved in Reminders: ${action.title}" + (if (action.dueMs > 0) ", " + com.meowmba.shared.platform.DeviceActions.describe(action.dueMs, offset) else "") + "." else msg)
                } }
            is com.meowmba.shared.platform.DeviceAction.AddEvent -> calendar { cal ->
                cal.addEvent(action.title, action.startMs, 60) { ok, msg ->
                    receipt(ok, VERIFIED, if (ok) "Saved “${action.title}” in your calendar, ${com.meowmba.shared.platform.DeviceActions.describe(action.startMs, offset)}." else msg)
                } }
            is com.meowmba.shared.platform.DeviceAction.ReadCalendar -> calendar { cal ->
                val day = com.meowmba.shared.platform.DeviceActions.startOfDay(PlatformTime.currentMillis(), offset) + if (action.tomorrow) 86_400_000L else 0L
                cal.events(day, day + 86_400_000L) { lines ->
                    val items = lines.mapNotNull { l -> l.split('|', limit = 2).takeIf { it.size == 2 } }
                    receipt(true, RESULT, if (items.isEmpty()) "Nothing on your calendar ${if (action.tomorrow) "tomorrow" else "today"}."
                        else "${if (action.tomorrow) "Tomorrow" else "Today"}: " + items.joinToString("; ") { (ms, title) ->
                            com.meowmba.shared.memory.DateText.timeLine(ms.toLong(), offset) + " " + title })
                } }
        }
    }

    /** Flow's calendar line (Android "cal" signal): the next event today, or "Nothing tonight". */
    fun refreshCalendarSignal() {
        val cal = com.meowmba.shared.platform.PlatformDrivers.calendar ?: return
        if (!cal.hasAccess()) return
        val now = PlatformTime.currentMillis(); val offset = PlatformTime.utcOffsetMinutes()
        val end = com.meowmba.shared.platform.DeviceActions.startOfDay(now, offset) + 86_400_000L
        cal.events(now, end) { lines ->
            // The header names the next meeting that hasn't started, not the one already underway.
            val next = lines.map { it.split('|', limit = 2) }.firstOrNull { it.size == 2 && (it[0].toLongOrNull() ?: 0) >= now }
            val label = if (next == null) (if (com.meowmba.shared.memory.DateText.hourOfDay(now, offset) >= 17) "Nothing tonight" else "Nothing else today")
                else com.meowmba.shared.memory.DateText.timeLine(next[0].toLong(), offset) + " " + next[1].take(18)
            flowSignals.value = flowSignals.value.filter { it.key != "cal" } + com.meowmba.shared.core.flow.Signal("cal", label)
        }
    }

    /** Flow's phone context: next meeting, due reminders, weather worth acting on, a low battery. */
    fun refreshFlowContext() {
        refreshCalendarSignal()
        val now = PlatformTime.currentMillis(); val offset = PlatformTime.utcOffsetMinutes()
        val status = com.meowmba.shared.platform.PlatformDrivers.status
        val cal = com.meowmba.shared.platform.PlatformDrivers.calendar?.takeIf { it.hasAccess() }
        fun publish(events: List<com.meowmba.shared.core.flow.FlowContext.Event>, reminders: List<com.meowmba.shared.core.flow.FlowContext.Reminder>) {
            com.meowmba.shared.core.flow.FlowContextState.items.value = com.meowmba.shared.core.flow.FlowContext.items(
                now, offset, events, reminders, weather.value ?: ambientWeather.value,
                status?.batteryPercent() ?: -1, status?.charging() ?: true,
                com.meowmba.shared.core.flow.FlowContextState.dismissed.value)
        }
        if (cal == null) return publish(emptyList(), emptyList())
        cal.events(now - 5 * 60_000L, now + 2 * 3_600_000L) { evLines ->
            val events = evLines.mapNotNull { l -> l.split('|', limit = 2).takeIf { it.size == 2 }?.let { com.meowmba.shared.core.flow.FlowContext.Event(it[0].toLong(), it[1]) } }
            cal.reminders(com.meowmba.shared.platform.DeviceActions.startOfDay(now, offset) + 86_400_000L) { remLines ->
                val reminders = remLines.mapNotNull { l -> l.split('|', limit = 3).takeIf { it.size == 3 }?.let { com.meowmba.shared.core.flow.FlowContext.Reminder(it[0].toLong(), it[1], it[2]) } }
                publish(events, reminders)
                feedNorthStars()
                flowSignals.value = flowSignals.value.filter { it.key != "rem" } +
                    listOfNotNull(reminders.size.takeIf { it > 0 }?.let { com.meowmba.shared.core.flow.Signal("rem", if (it == 1) "1 reminder" else "$it reminders") })
            }
        }
    }

    // ── Attachments (Android onAttachment): read on the phone, then handed to Meow as a turn. ──
    val attachStatus = MutableStateFlow<String?>(null)
    fun attach(kind: com.meowmba.shared.attach.Attachments.Kind, note: String? = null) {
        val driver = com.meowmba.shared.platform.PlatformDrivers.attach
            ?: run { voiceIssue.value = "Attachments aren't available on this device."; return }
        driver.pick(kind.name,
            onRead = { name, mime, text ->
                attachStatus.value = null
                val read = com.meowmba.shared.attach.Attachments.read(name, kind, mime, text)
                // A shipping label becomes something Flow keeps in mind, as on Android.
                read.tracking.forEach { t -> keepFlowNote("Package on its way (${t.carrier}) · ${t.number}") }
                openChat()
                onInputChange(com.meowmba.shared.attach.Attachments.describe(read, note ?: input.value.takeIf { it.isNotBlank() }))
                send()
            },
            onCancel = { attachStatus.value = null },
            onError = { attachStatus.value = null; voiceIssue.value = it })
        attachStatus.value = "Reading what you shared…"
    }

    // ── Care (Android PetCarePanel + PetCareState): gentle, optional needs; never dies. ──
    val petCare = MutableStateFlow(com.meowmba.shared.behavior.PetCareState.decode(DefaultTextStorage.readText("pet_care"))
        .advance(PlatformTime.currentMillis()))
    val habitatMode = MutableStateFlow(DefaultTextStorage.readText("habitat_mode") == "true")
    val petLearning = MutableStateFlow(DefaultTextStorage.readText("pet_learning") != "false")
    val petFeedbackNotice = MutableStateFlow<String?>(null)

    private fun commitCare(next: com.meowmba.shared.behavior.PetCareState) {
        petCare.value = next
        DefaultTextStorage.writeTextAtomic("pet_care", next.encode())
    }
    fun setPetCare(enabled: Boolean) = commitCare(petCare.value.withCare(enabled, PlatformTime.currentMillis()))
    fun setHabitatMode(on: Boolean) {
        habitatMode.value = on; DefaultTextStorage.writeTextAtomic("habitat_mode", on.toString())
        // Android swaps to the habitat room; returning to Backdrop restores a plain backdrop if a room was showing.
        val bg = com.meowmba.shared.ui.background.backgroundStore
        val current = bg.selection.value
        val inRoom = current.animation?.isPetEnvironment == true
        if (on && !inRoom) bg.select(current.copy(kind = com.meowmba.shared.ui.background.BackgroundKind.ANIMATION,
            value = com.meowmba.shared.ui.background.BackgroundAnimation.CARTOON_NOOK.id))
        if (!on && inRoom) bg.select(current.copy(kind = com.meowmba.shared.ui.background.BackgroundKind.NONE, value = ""))
    }
    fun setPetLearning(on: Boolean) { petLearning.value = on; DefaultTextStorage.writeTextAtomic("pet_learning", on.toString()) }

    /** Care buttons update the meters and have Lumen act it out, as Android's habitat does. */
    fun habitatAction(action: com.meowmba.shared.behavior.HabitatAction) {
        val now = PlatformTime.currentMillis()
        val care = petCare.value
        commitCare(when (action) {
            com.meowmba.shared.behavior.HabitatAction.FEED, com.meowmba.shared.behavior.HabitatAction.DRINK -> care.feed(now)
            com.meowmba.shared.behavior.HabitatAction.PLAY, com.meowmba.shared.behavior.HabitatAction.EXPLORE -> care.play(now)
            com.meowmba.shared.behavior.HabitatAction.REST -> care.rest(now)
            com.meowmba.shared.behavior.HabitatAction.CLEAN -> care.clean(now)
            com.meowmba.shared.behavior.HabitatAction.TALK -> care.talk(now)
            else -> care.advance(now)
        })
        val clip = when (action) {
            com.meowmba.shared.behavior.HabitatAction.FEED, com.meowmba.shared.behavior.HabitatAction.DRINK -> "fond_nuzzle"
            com.meowmba.shared.behavior.HabitatAction.PLAY, com.meowmba.shared.behavior.HabitatAction.EXPLORE -> "pocket_groove"
            com.meowmba.shared.behavior.HabitatAction.TALK -> "fond_nuzzle"
            else -> null
        }
        clip?.let(::playTrick)
    }

    fun playTrick(clip: String) {
        com.meowmba.shared.ui.lumen.AvatarPerformanceCatalog.resolve(clip)?.let(AvatarAppearanceStore::play)
    }

    /** Preference feedback: acknowledged and counted; the behavior layer reads the counts. */
    fun petFeedback(liked: Boolean) {
        val key = if (liked) "pet_likes" else "pet_dislikes"
        val count = (DefaultTextStorage.readText(key)?.toIntOrNull() ?: 0) + 1
        DefaultTextStorage.writeTextAtomic(key, count.toString())
        petFeedbackNotice.value = if (liked) "Noted — more like that." else "Noted — a little less of that."
    }
    fun resetPetLearning() {
        DefaultTextStorage.delete("pet_likes"); DefaultTextStorage.delete("pet_dislikes")
        petFeedbackNotice.value = "Learned preferences cleared."
    }

    /** Camera toggle (Android CameraTrackingControl): face position drives Lumen's gaze; nothing is stored. */
    fun setCameraEnabled(on: Boolean) {
        val face = com.meowmba.shared.platform.PlatformDrivers.face
        if (!on) {
            face?.stop(); com.meowmba.shared.platform.FaceAttention.clear()
            cameraEnabled.value = false; cameraStatus.value = "Camera off"; return
        }
        if (face == null) { cameraStatus.value = "Tracking unavailable"; cameraEnabled.value = true; return }
        cameraEnabled.value = true
        cameraStatus.value = "Starting…"
        face.requestPermission { granted, reason ->
            if (!granted) { cameraStatus.value = reason ?: "Camera not allowed"; return@requestPermission }
            face.start(object : com.meowmba.shared.platform.FaceSink {
                override fun onFace(x: Float, y: Float) {
                    com.meowmba.shared.platform.FaceAttention.update(x, y)
                    if (cameraStatus.value != "Tracking you") cameraStatus.value = "Tracking you"
                }
                override fun onNoFace() {
                    com.meowmba.shared.platform.FaceAttention.clear()
                    if (cameraStatus.value != "Looking for you") cameraStatus.value = "Looking for you"
                }
                override fun onStatus(label: String) { cameraStatus.value = label }
            })
        }
    }

    /** Result of the last Save/Test action, shown under the settings fields. */
    val statusLine = MutableStateFlow<String?>(null)

    /** Edits in progress; only written to the store on Save. */
    val draft = MutableStateFlow(settings.config.value)

    private var engine: AssistantEngine? = null
    private var activeJob: Job? = null

    init {
        if (com.meowmba.shared.agent.OnDeviceModel.driver != null && DefaultTextStorage.readText(SettingsStore.KEY) == null) {
            settings.update(ProviderConfig(kind = ProviderKind.ON_DEVICE, model = "Qwen3-0.6B"))
            draft.value = settings.config.value
        }
        // Android opens chat with no canned greeting; the first reply is the model's own.
        ensureAmbientWeather()
    }

    /** Small models often echo their speaker label ("Meow: …"); the bubble already says who spoke. */
    private fun withoutSpeakerLabel(text: String): String {
        val name = settings.config.value.companionName.ifBlank { "Meow" }
        return Regex("^\\s*(?:\\Q$name\\E|Meowmba|Assistant)\\s*:\\s*", RegexOption.IGNORE_CASE).replace(text, "")
    }

    fun onInputChange(value: String) { input.value = value }

    // ── Merch table (TEST fork): local till, cart, receipt-before-pay, helper ticket, PayPal stub.
    val merchTable = com.meowmba.shared.merch.MerchTableController(
        clock = { PlatformTime.currentMillis() },
        storage = object : com.meowmba.shared.merch.MerchStorage {
            override fun read(): String? = DefaultTextStorage.readText("merch_inventory")
            override fun write(text: String) = DefaultTextStorage.writeTextAtomic("merch_inventory", text)
        },
    )
    val merch = MutableStateFlow(merchTable.snapshot).also { flow ->
        merchTable.onChange = { flow.value = it }
        merchTable.onArmPreset = { preset -> com.meowmba.shared.toto.TotoRobotBridge.sendV14Preset(preset) }
        merchTable.onLooiGesture = { kind -> com.meowmba.shared.toto.LooiRobotBridge.cue(kind) }
        // Real sandbox payments go through the merch backend once simulate is off.
        merchTable.sandboxFactory = com.meowmba.shared.merch.PayPalSandboxFactory { s ->
            com.meowmba.shared.merch.PayPalBackendGateway(s)
        }
    }

    private val _paypalSettings = MutableStateFlow(loadPayPalSettings())
    val paypalSettings: StateFlow<com.meowmba.shared.merch.PayPalSettings> get() = _paypalSettings

    private fun loadPayPalSettings(): com.meowmba.shared.merch.PayPalSettings {
        val secrets = com.meowmba.shared.bridge.DesktopBridge.secrets
        return com.meowmba.shared.merch.PayPalSettings(
            env = DefaultTextStorage.readText("paypal_env")?.ifBlank { null } ?: "sandbox",
            clientId = secrets?.readSecret("paypal_client_id").orEmpty(),
            clientSecret = secrets?.readSecret("paypal_client_secret").orEmpty(),
            backendUrl = DefaultTextStorage.readText("paypal_backend_url").orEmpty(),
            simulate = DefaultTextStorage.readText("paypal_simulate") != "false",
            backendToken = secrets?.readSecret("paypal_backend_token").orEmpty(),
        )
    }

    fun setPaypalSettings(settings: com.meowmba.shared.merch.PayPalSettings) {
        _paypalSettings.value = settings
        DefaultTextStorage.writeTextAtomic("paypal_env", settings.env)
        DefaultTextStorage.writeTextAtomic("paypal_backend_url", settings.backendUrl)
        DefaultTextStorage.writeTextAtomic("paypal_simulate", if (settings.simulate) "true" else "false")
        val secrets = com.meowmba.shared.bridge.DesktopBridge.secrets
        if (settings.clientId.isNotBlank()) secrets?.writeSecret("paypal_client_id", settings.clientId)
        if (settings.clientSecret.isNotBlank()) secrets?.writeSecret("paypal_client_secret", settings.clientSecret)
        if (settings.backendToken.isNotBlank()) secrets?.writeSecret("paypal_backend_token", settings.backendToken)
        merchTable.applyPayPalSettings(settings)
    }

    /** Meow's merch reply: into the chat and out loud immediately (Lion voice path). */
    fun merchSay(reply: String) {
        conversation.append(ChatMessage(MessageRole.ASSISTANT, reply, PlatformTime.currentMillis()))
        speakReply(reply)
    }

    fun onMerchWalkUp() {
        merchTable.onPersonArrived()?.let { merchSay(it) }
    }

    init { merchTable.applyPayPalSettings(_paypalSettings.value) }

    fun openSettings() {
        draft.value = settings.config.value
        statusLine.value = null
        settingsOpen.value = true
    }

    fun closeSettings() {
        settingsOpen.value = false
        statusLine.value = null
    }

    fun onDraftChange(updated: ProviderConfig) { draft.value = updated }

    fun saveSettings() {
        settings.update(draft.value)
        // The cached provider holds an HttpClient; drop it so the new endpoint
        // and key take effect on the very next turn.
        engine?.close()
        engine = null
        statusLine.value = "Saved · ${draft.value.describe()}"
    }

    fun testConnection() {
        val probe = draft.value
        if (probe.baseUrl.isBlank()) {
            statusLine.value = "Enter an endpoint first"
            return
        }
        statusLine.value = "Testing…"
        scope.launch {
            val engine = AssistantEngine(probe)
            statusLine.value = try {
                engine.ping()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message ?: "network error"}"
            } finally {
                engine.close()
            }
        }
    }

    fun clearConversation() {
        conversation.clear()
        statusLine.value = "Conversation cleared"
    }

    /** Cancels an in-flight reply, keeping whatever text already arrived. */
    fun cancel() {
        activeJob?.cancel()
    }

    fun send() {
        val text = input.value.trim()
        if (text.isEmpty() || busy.value) return
        input.value = ""

        // Captured before the current turn is appended: PromptBuilder adds the
        // user input itself, so it must not appear in the history too.
        val history = conversation.recentForPrompt()

        if (com.meowmba.shared.voice.SpeechQuiet.isOnlyNonSpeech(text)) return
        if (com.meowmba.shared.voice.SpeechQuiet.isQuietRequest(text)) {
            conversation.append(ChatMessage(MessageRole.USER, text, PlatformTime.currentMillis()))
            activeJob?.cancel()
            com.meowmba.shared.platform.PlatformDrivers.speechOut?.stop()
            callActive.value = false
            setQuiet(true)
            voiceTurn = false
            conversation.append(ChatMessage(MessageRole.ASSISTANT,
                "I'll stay quiet. Tap the microphone or send a message when you want me again.", PlatformTime.currentMillis()))
            busy.value = false
            state.value = state.value.copy(interactionState = InteractionState.IDLE)
            return
        }
        setQuiet(false)
        conversation.append(
            ChatMessage(MessageRole.USER, text, PlatformTime.currentMillis())
        )
        // Only accepted human turns can create goals or evidence (Android captureFromChat).
        runCatching { northStars.capture(text); feedNorthStars() }

        // Merch table first: inventory/cart/receipt turns never go to a model or the web-shopping tools.
        merchTable.handle(text)?.let { reply -> voiceTurn = false; merchSay(reply); return }

        val rememberCommand = com.meowmba.shared.memory.MemoryCommandParser.parse(text)
        if (rememberCommand != null) {
            busy.value = true
            activeJob = scope.launch {
                val content = rememberCommand.content ?: history.lastOrNull()?.content.orEmpty()
                val reply = try {
                    if (memory.remember(content) != null) "I saved that to memory. You can review or delete it in Memory."
                    else if (memory.state.value.paused) "Memory is paused. Turn on Save new memories in Memory first."
                    else "Tell me what you want me to remember."
                } catch (e: Exception) { "I could not save that memory: " + e.message }
                conversation.append(ChatMessage(MessageRole.ASSISTANT, reply, PlatformTime.currentMillis()))
                busy.value = false
            }
            return
        }

        // "Scan this QR code" opens the scanner; "what do you see" takes a photo to look at (Android VisionRouter).
        com.meowmba.shared.core.screen.VisionRequest.parse(text)?.let { q ->
            if (q.isQrQuery) { scanCode(); return }
            if (com.meowmba.shared.platform.PlatformDrivers.attach != null) {
                conversation.removeAt(conversation.messages.value.lastIndex)
                attach(com.meowmba.shared.attach.Attachments.Kind.PHOTO, text); return
            }
        }

        com.meowmba.shared.core.shopping.ShoppingRequest.parse(text)?.let { runShopping(it); return }

        // "Turn into a purple dragon" → Vending's Become-anything (Android avatar.form); built-in forms switch directly.
        com.meowmba.shared.agent.runtime.AvatarFormRequest.parse(text)?.let { wanted -> becomeAvatar(wanted); return }

        // "On my computer, open Safari" → the paired desktop bridge; receipts say what it reported.
        com.meowmba.shared.bridge.DesktopCommand.parse(text)?.let { action ->
            val B = com.meowmba.shared.bridge.DesktopBridge
            if (B.paired.value == null) { busy.value = true; reply("No computer is paired yet. Open Menu › Computer to pair one."); return }
            val title = when (action) { is com.meowmba.shared.bridge.DesktopCommand.Action.OpenApp -> "Computer: open ${action.name}"
                is com.meowmba.shared.bridge.DesktopCommand.Action.OpenUrl -> "Computer: open link"; is com.meowmba.shared.bridge.DesktopCommand.Action.Media -> "Computer: ${action.action}"
                is com.meowmba.shared.bridge.DesktopCommand.Action.Build -> "Computer: build ${action.name}"; else -> "Computer: clipboard" }
            val id = work.start(title, "desktop")
            busy.value = true
            activeJob = scope.launch {
                val r = when (action) {
                    is com.meowmba.shared.bridge.DesktopCommand.Action.OpenApp -> B.openApp(action.name)
                    is com.meowmba.shared.bridge.DesktopCommand.Action.OpenUrl -> B.openUrl(action.url)
                    is com.meowmba.shared.bridge.DesktopCommand.Action.Media -> B.media(action.action)
                    is com.meowmba.shared.bridge.DesktopCommand.Action.Build -> B.runBuild(action.name).map { "${it.name}: exit ${it.exitCode}" + (if (it.timedOut) " (timed out)" else "") + "\n" + it.output.takeLast(1500) }
                    else -> B.clipboardGet().map { "The computer's clipboard says: ${it.take(2000)}" }
                }
                val msg = r.getOrElse { "The computer couldn't do that: ${it.message}" }
                work.finish(id, r.isSuccess, com.meowmba.shared.work.WorkOutcome.UNCONFIRMED, msg)
                reply(msg)
            }
            return
        }

        // "Cozmo look up" → the robot, never another device (Android CozmoControlTool).
        com.meowmba.shared.robot.Cozmo.parse(text)?.let { action ->
            val st = com.meowmba.shared.robot.Cozmo.state.value
            val result = if (!st.connected) "Cozmo is not connected. Open Menu › Robot to connect him." else com.meowmba.shared.robot.Cozmo.action(action)
            val id = work.start("Cozmo: ${action.replace('_', ' ')}", "robot")
            work.finish(id, st.connected, if (action == "status") com.meowmba.shared.work.WorkOutcome.RESULT else com.meowmba.shared.work.WorkOutcome.UNCONFIRMED, result)
            busy.value = true; reply(result); return
        }

        if (deviceActions.value) com.meowmba.shared.platform.DeviceActions.parse(text, PlatformTime.currentMillis(),
                PlatformTime.utcOffsetMinutes())?.let { runDeviceAction(it); return }

        // Needle 3 catches the commands the phrase patterns miss ("could you ping mom that I'm late").
        val needle = com.meowmba.shared.platform.Needle
        if (deviceActions.value && needle.ready() && needle.mightBeCommand(text)) {
            busy.value = true
            statusLine.value = "Needle is routing…"
            needle.driver!!.route(needle.SYSTEM_PROMPT, needle.toolsJson, text) { raw ->
                busy.value = false
                val action = needle.toAction(raw, PlatformTime.currentMillis(), PlatformTime.utcOffsetMinutes())
                if (action != null) runDeviceAction(action) else continueSend(text, history)
            }
            return
        }
        continueSend(text, history)
    }

    private fun continueSend(text: String, history: List<ChatMessage>) {

        if (com.meowmba.shared.agent.runtime.AgentRouter.isWeatherQuestion(
                com.meowmba.shared.voice.WakePhraseMatcher.normalize(text))) {
            answerWeather(text, history)
            return
        }

        // Questions about the app's own state are answered from runtime facts,
        // never by asking the model to guess.
        val instant = when (SelfStatusParser.parse(text)) {
            SelfStatusParser.Question.MODEL -> "I'm using ${settings.config.value.describe()}."
            SelfStatusParser.Question.WHERE ->
                "I'm running through ${settings.config.value.kind.name.lowercase().replace('_', '-')}."
            null -> null
        }
        if (instant != null) {
            conversation.append(
                ChatMessage(MessageRole.ASSISTANT, instant, PlatformTime.currentMillis())
            )
            state.value = state.value.copy(
                interactionState = InteractionState.IDLE,
                emotion = CompanionEmotion.HAPPY,
                errorMessage = null,
            )
            return
        }

        conversation.append(
            ChatMessage(MessageRole.ASSISTANT, "", PlatformTime.currentMillis())
        )
        state.value = state.value.copy(
            interactionState = InteractionState.THINKING,
            errorMessage = null,
        )
        busy.value = true

        activeJob = scope.launch {
            val current = settings.config.value
            val brain = engine ?: engineFactory(current).also { engine = it }
            val accumulated = StringBuilder()
            try {
                brain.stream(text, history, memory.candidates()).collect { response ->
                    when (response) {
                        is ModelResponse.Partial -> {
                            accumulated.append(response.text)
                            conversation.updateLast(withoutSpeakerLabel(accumulated.toString()))
                            state.value = state.value.copy(
                                interactionState = InteractionState.SPEAKING
                            )
                        }
                        is ModelResponse.Complete -> Unit
                        is ModelResponse.Error -> {
                            // Keep partial text if any arrived; otherwise surface
                            // the real error rather than an empty bubble.
                            conversation.updateLast(
                                accumulated.toString().ifBlank { response.message }
                            )
                            state.value = state.value.copy(
                                interactionState = InteractionState.OFFLINE,
                                emotion = CompanionEmotion.ALERT,
                                errorMessage = response.message,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                conversation.updateLast(
                    accumulated.toString().ifBlank { "(cancelled)" }
                )
                throw e
            } catch (e: Exception) {
                conversation.updateLast(
                    accumulated.toString().ifBlank {
                        "Something went wrong: ${e.message ?: "unknown error"}"
                    }
                )
                state.value = state.value.copy(
                    interactionState = InteractionState.OFFLINE,
                    emotion = CompanionEmotion.ALERT,
                    errorMessage = e.message,
                )
            } finally {
                conversation.flush()
                busy.value = false
                if (state.value.interactionState == InteractionState.SPEAKING) {
                    state.value = state.value.copy(
                        interactionState = InteractionState.IDLE,
                        emotion = CompanionEmotion.HAPPY,
                    )
                }
                val spoken = voiceTurn || callActive.value
                voiceTurn = false
                if (spoken && state.value.interactionState != InteractionState.OFFLINE) speakReply(withoutSpeakerLabel(accumulated.toString()))
                else if (callActive.value) callActive.value = false
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MeowTheme { content() }
}

/** Sub-pages opened from the menu, Settings or a home; the home itself comes from the app theme. */
/** Set true by the Swift host in DEBUG builds only; lets tooling script demo recordings. */
object DemoHooks {
    var enabled = false
    /** Demo takes keep the Desk cat from wandering off mid-sentence; it still reacts to taps and replies. */
    val stillCat = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Phrases the demo "hears" (the Simulator has no microphone); each start() consumes one, word by word. */
    val heard = ArrayDeque<String>()
    val speech = object : com.meowmba.shared.platform.SpeechInputDriver {
        private var job: kotlinx.coroutines.Job? = null
        override fun requestPermission(onResult: (Boolean, String?) -> Unit) = onResult(true, null)
        override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
            job?.cancel()
            job = kotlinx.coroutines.MainScope().launch {
                var waited = 0
                while (heard.isEmpty() && waited < 120) { kotlinx.coroutines.delay(250); waited++ }
                val phrase = heard.removeFirstOrNull() ?: return@launch onFinal("")
                val words = phrase.split(' ')
                for (i in words.indices) { kotlinx.coroutines.delay(260); onPartial(words.take(i + 1).joinToString(" ")) }
                kotlinx.coroutines.delay(500); onFinal(phrase)
            }
        }
        override fun stop() { }
    }
}

internal enum class Page { SETTINGS, MEMORY, LLM, REMOTE, APPEARANCE, VAULT, PRIVACY, BACKGROUND, AVATAR, VOICE, WEATHER, SETUP, DEVICE, COZMO, TOTO, VENDING, COMPUTER, FILES, TERMINAL, AGENTS, MERCH, UNAVAILABLE }

/** Menu tiles and Settings categories → pages. Anything not ported to iOS gets an honest unavailable page. */
internal fun pageFor(target: String): Page? = when (target) {
    "Toto Controls", "Toto", "Toto companion" -> Page.TOTO
    "Settings" -> Page.SETTINGS
    "Memory", "Connections & memory" -> Page.MEMORY
    "LLM", "AI & models" -> Page.LLM
    "Remote models" -> Page.REMOTE
    "Appearance" -> Page.APPEARANCE
    "Notes", "Vault" -> Page.VAULT
    "Privacy" -> Page.PRIVACY
    "Phone & device" -> Page.DEVICE
    "Agents", "Tools" -> Page.AGENTS
    "Background" -> Page.BACKGROUND
    "Avatar", "Neural / Lumen" -> Page.AVATAR
    "Voice & behavior" -> Page.VOICE
    "Merch table" -> Page.MERCH
    "Weather" -> Page.WEATHER
    "Robot", "Cozmo" -> Page.COZMO
    "Computer", "Projects", "Studio", "Vending", "Vending Machine" -> Page.UNAVAILABLE
    "Terminal" -> Page.TERMINAL
    "Files" -> Page.FILES
    "Setup" -> Page.SETUP
    else -> null
}

@Composable
fun CompanionScreen(viewModel: CompanionViewModel, avatarOnly: Boolean = false) {
    val savedTheme by appearanceStore.theme.collectAsState()
    val theme = if (avatarOnly) AppTheme.COMPANION else savedTheme
    // Android always opens on the theme's home. A debug-only file lets tooling open one page directly.
    val initial = remember {
        DefaultTextStorage.readText("debug_open_page")?.also { DefaultTextStorage.delete("debug_open_page") }
    }
    // Back stack of sub-pages over the home; empty = the home is showing.
    val stack = remember { androidx.compose.runtime.mutableStateListOf<Page>().apply {
        (initial?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.SETUP.takeIf { SetupState.needed() })?.let(::add) } }
    val page = stack.lastOrNull()
    var menuOpen by remember { mutableStateOf(initial == "MENU") }
    var aboutToto by remember { mutableStateOf(false) }
    var showLooi by remember { mutableStateOf(initial.equals("LOOI", ignoreCase = true)) }
    var unavailable by remember { mutableStateOf("") }
    var attachOpen by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }
    var avatarShown by remember { mutableStateOf(false) }
    val kiosk by com.meowmba.shared.platform.LauncherMode.kiosk.collectAsState()
    var kioskExit by remember { mutableStateOf(false) }
    // Widgets, Siri and the Action Button arrive as meowmba:// links.
    // The charger avatar (Android build 181): plugging in shows the chosen avatar full screen.
    val chargerShow by com.meowmba.shared.platform.ChargerAvatar.show.collectAsState()
    LaunchedEffect(Unit) {
        runCatching {
            if (!com.meowmba.shared.toto.TotoRobotBridge.state.value.connected &&
                !com.meowmba.shared.toto.TotoRobotBridge.state.value.connecting) {
                com.meowmba.shared.toto.TotoRobotBridge.connect()
            }
            viewModel.setCameraEnabled(true)
            com.meowmba.shared.platform.PlatformDrivers.status?.let { st ->
                com.meowmba.shared.platform.ChargerAvatar.onPower(st.charging(), PlatformTime.currentMillis())
                st.observeCharging { com.meowmba.shared.platform.ChargerAvatar.onPower(it, PlatformTime.currentMillis()) }
            }
        }.onFailure { println("[Merch] startup step failed (non-fatal): ${it.message}") }
    }
    LaunchedEffect(chargerShow) { if (chargerShow > 0) { stack.clear(); menuOpen = false; avatarShown = !avatarOnly } }
    val sharedScreen by com.meowmba.shared.platform.IncomingShares.pending.collectAsState()
    LaunchedEffect(sharedScreen) {
        val shared = sharedScreen ?: return@LaunchedEffect
        com.meowmba.shared.platform.IncomingShares.pending.value = null
        viewModel.openSharedScreen(shared)
    }
    val screenHelp by viewModel.screenHelp.collectAsState()
    val link by com.meowmba.shared.platform.IncomingLinks.pending.collectAsState()
    LaunchedEffect(link) {
        val url = link ?: return@LaunchedEffect
        com.meowmba.shared.platform.IncomingLinks.pending.value = null
        val route = url.removePrefix("meowmba://").substringBefore('?').trimEnd('/')
        val q = url.substringAfter("q=", "").substringBefore('&').let { com.meowmba.shared.voice.UrlCodec.decode(it) }
        menuOpen = false
        when (route) {
            "chat" -> { stack.clear(); viewModel.openChat() }
            "ask" -> { stack.clear(); viewModel.openChat(); if (q.isNotBlank()) { viewModel.onInputChange(q); viewModel.send() } }
            "talk" -> { stack.clear(); viewModel.startListening() }
            "flow", "desk" -> { /* intentionally unavailable in Toto avatar target */ }
            "home", "" -> stack.clear()
            "avatar" -> { stack.clear(); avatarShown = !avatarOnly }
            "pair" -> { com.meowmba.shared.bridge.DesktopBridge.pendingPayload.value = url.substringAfter("p=", "").substringBefore('&')
                .let { com.meowmba.shared.voice.UrlCodec.decode(it) }.takeIf { it.isNotBlank() }
                stack.clear() }
        }
    }
    val idleKey by viewModel.idleAvatar.collectAsState()
    val idleAfter by viewModel.idleMinutes.collectAsState()
    var lastTouch by remember { mutableStateOf(PlatformTime.currentMillis()) }
    var idle by remember { mutableStateOf(false) }
    val chatExpanded by viewModel.chatExpanded.collectAsState()
    LaunchedEffect(initial) { if (initial == "CHAT") viewModel.openChat() }
    // Debug builds only (the Swift host enables it): tooling feeds demo steps through a file, one command per line.
    LaunchedEffect(Unit) {
        if (!DemoHooks.enabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(400)
            val script = DefaultTextStorage.readText("debug_demo") ?: continue
            DefaultTextStorage.delete("debug_demo")
            for (line in script.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                val cmd = line.substringBefore(':'); val arg = line.substringAfter(':', "")
                val now = PlatformTime.currentMillis()
                when (cmd) {
                    "keep" -> viewModel.keepFlowNote(arg)
                    "event", "reminder" -> {
                        val mins = arg.substringBefore(':').toLongOrNull() ?: 30L; val title = arg.substringAfter(':')
                        val cal = com.meowmba.shared.platform.PlatformDrivers.calendar
                        val at = now + mins * 60_000L
                        if (cmd == "event") cal?.addEvent(title, at, 30) { _, _ -> viewModel.refreshFlowContext() }
                        else cal?.addReminder(title, at) { _, _ -> viewModel.refreshFlowContext() }
                    }
                    "star" -> {
                        val parts = arg.split('|')
                        val id = viewModel.northStars.save(null, parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "This year" },
                            parts.getOrElse(3) { "" }.split(';').filter { it.isNotBlank() })
                        viewModel.northStars.pin(id); viewModel.feedNorthStars()
                    }
                    "ask" -> { stack.clear(); viewModel.openChat(); viewModel.onInputChange(arg); viewModel.send() }
                    "theme" -> { stack.clear(); if (!avatarOnly) appearanceStore.setTheme(AppTheme.valueOf(arg)) }
                    "page" -> { menuOpen = false; stack.clear(); runCatching { Page.valueOf(arg) }.getOrNull()?.let {
                        stack.add(if (avatarOnly) TotoHomePolicy.page(it) else it)
                    } }
                    "home" -> { menuOpen = false; stack.clear(); viewModel.chatExpanded.value = false }
                    "menu" -> menuOpen = true
                    "avatar" -> { stack.clear(); avatarShown = !avatarOnly && arg != "off" }
                    "refresh" -> viewModel.refreshFlowContext()
                    // Clears this demo's own test events and reminders by title (Simulator tooling only).
                    "clear" -> com.meowmba.shared.platform.PlatformDrivers.calendar?.let { cal ->
                        val titles = arg.split('|').toSet()
                        cal.events(now - 86_400_000L, now + 2 * 86_400_000L) { lines -> lines.forEach { l ->
                            val p = l.split('|', limit = 2); if (p.size == 2 && p[1] in titles) cal.cancelEvent(p[0].toLong(), p[1]) { _, _ -> } } }
                        cal.reminders(now + 2 * 86_400_000L) { lines -> lines.forEach { l ->
                            val p = l.split('|', limit = 3); if (p.size == 3 && p[2] in titles) cal.completeReminder(p[1]) { } } }
                    }
                    "voice" -> com.meowmba.shared.platform.PlatformDrivers.speechIn = DemoHooks.speech
                    "hear" -> DemoHooks.heard.addLast(arg)
                    "listen" -> viewModel.startListening()
                    "still" -> DemoHooks.stillCat.value = arg != "off"
                }
            }
        }
    }

    /** Opens [target]; [fromHome] starts a fresh stack (menu, rail, home buttons). */
    fun open(target: String, fromHome: Boolean = false) {
        menuOpen = false
        val destination = if (avatarOnly) TotoHomePolicy.destination(target) ?: return else target
        when (destination) {
            "Companion" -> { appearanceStore.setTheme(AppTheme.COMPANION); stack.clear() }
            "Flow" -> { appearanceStore.setTheme(AppTheme.FLOW); stack.clear() }
            "Desk" -> { appearanceStore.setTheme(AppTheme.DESK); stack.clear() }
            "Chat" -> { stack.clear(); viewModel.openChat() }
            else -> {
                if (fromHome) stack.clear()
                val next = pageFor(destination) ?: Page.UNAVAILABLE.also { unavailable = destination }
                stack.add(if (avatarOnly) TotoHomePolicy.page(next) else next)
            }
        }
    }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun home() { stack.clear() }

    val avatarMode by viewModel.avatarMode.collectAsState()
    val flowNotes by viewModel.flowNotes.collectAsState()
    val state by viewModel.state.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val input by viewModel.input.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val config by viewModel.config.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val listening by viewModel.listening.collectAsState()
    val listenPartial by viewModel.listenPartial.collectAsState()
    val voiceIssue by viewModel.voiceIssue.collectAsState()
    val callActive by viewModel.callActive.collectAsState()
    val cameraEnabled by viewModel.cameraEnabled.collectAsState()
    val cameraStatus by viewModel.cameraStatus.collectAsState()
    val pose = CatAvatarStateMachine.resolvePose(state)
    LaunchedEffect(state.emotion) {
        com.meowmba.shared.toto.TotoRobotBridge.sendEmotion(state.emotion.name.lowercase())
    }
    val platformSignals by viewModel.flowSignals.collectAsState()
    val weatherNow by viewModel.weather.collectAsState()
    val ambient by viewModel.ambientWeather.collectAsState()
    val ambientOn by viewModel.ambientWeatherEnabled.collectAsState()
    val previewTod by viewModel.previewTimeOfDay.collectAsState()
    val weatherPlace by viewModel.weatherPlace.collectAsState()
    val weatherStatus by viewModel.weatherStatus.collectAsState()
    // Flow's dateline signals (Android: place, app, calendar…); iOS shows what it actually knows.
    val flowSignals = remember(platformSignals, weatherNow, ambient, flowNotes) {
        val open = flowNotes.filter { !it.done }
        val packages = open.count { it.text.startsWith("Package on its way") }
        buildList {
            addAll(platformSignals)
            (weatherNow ?: ambient)?.let { add(com.meowmba.shared.core.flow.Signal("wx", "${it.temperatureF}° ${it.condition.label}")) }
            if (packages > 0) add(com.meowmba.shared.core.flow.Signal("pkg", if (packages == 1) "1 package" else "$packages packages"))
            if (open.size > packages) add(com.meowmba.shared.core.flow.Signal("need", "${open.size - packages} kept"))
        }
    }
    LaunchedEffect(page) { if (page == Page.WEATHER && weatherNow == null && ambient == null) viewModel.refreshWeather() }
    val backgroundKind = com.meowmba.shared.ui.background.backgroundStore.selection.collectAsState().value.kind
    LaunchedEffect(avatarOnly) {
        if (avatarOnly) {
            appearanceStore.setTheme(AppTheme.COMPANION)
            stack.indices.forEach { i -> stack[i] = TotoHomePolicy.page(stack[i]) }
        }
    }
    LaunchedEffect(backgroundKind) { viewModel.ensureAmbientWeather() }
    LaunchedEffect(theme, page, weatherNow, ambient) {
        if (page == null && (theme == AppTheme.FLOW || theme == AppTheme.DESK)) while (true) {
            viewModel.refreshFlowContext(); kotlinx.coroutines.delay(60_000L) // countdowns like "in 9 min" stay current
        }
    }

    // Android: only Flow and Desk rest into the avatar (Companion already is the avatar).
    val idleAllowed = page == null && (theme == AppTheme.FLOW || theme == AppTheme.DESK) && idleKey != "off" &&
        !chatExpanded && !menuOpen && !appsOpen && !attachOpen
    LaunchedEffect(idleAllowed, idleAfter, lastTouch) {
        if (!idleAllowed) { idle = false; return@LaunchedEffect }
        val wait = lastTouch + idleAfter * 60_000L - PlatformTime.currentMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        idle = true
    }
    MeowTheme {
        // Swipes: right-to-left opens chat everywhere; on the homes, up = apps, down = menu;
        // Desk left-to-right returns to Flow, Companion left-to-right goes to Flow (Android 763d535).
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                lastTouch = PlatformTime.currentMillis()
                com.meowmba.shared.toto.LooiRobotBridge.pauseFollow()
            } }
            .homeSwipes(
                onLeft = { if (!chatExpanded && !kiosk) { stack.clear(); viewModel.openChat() } },
                onRight = { if (!avatarOnly && page == null && (theme == AppTheme.DESK || theme == AppTheme.COMPANION)) open("Flow") else if (page != null) back() },
                onUp = { if (page == null && !chatExpanded && !kiosk) {
                    if (avatarOnly) viewModel.openChat() else appsOpen = true
                } },
                onDown = { if (page == null && !chatExpanded && !kiosk) menuOpen = true },
            )) {
            when (page) {
                null -> if (avatarOnly) TotoMountedHome(
                    pose = pose, mode = avatarMode, busy = busy, listening = listening,
                    speaking = state.interactionState == InteractionState.SPEAKING,
                    offline = state.interactionState == InteractionState.OFFLINE,
                    micEnabled = micEnabled, cameraEnabled = cameraEnabled,
                    onMenu = { menuOpen = true }, onMic = viewModel::onMicClick,
                    onTotoControls = { open("Toto Controls", fromHome = true) },
                    showMenuButton = false,
                ) else when (theme) {
                    AppTheme.COMPANION -> AvatarHomeScene(pose, avatarMode,
                        CompanionHomeUi(micEnabled = micEnabled,
                            micVisual = when {
                                listening -> MicVisual.LISTENING
                                busy -> MicVisual.THINKING
                                else -> MicVisual.IDLE
                            },
                            cameraEnabled = cameraEnabled, cameraStatus = cameraStatus, chatExpanded = chatExpanded,
                            speaking = state.interactionState == com.meowmba.shared.companion.InteractionState.SPEAKING,
                            offline = state.interactionState == com.meowmba.shared.companion.InteractionState.OFFLINE,
                            emotion = state.emotion),
                        onMenu = { menuOpen = true }, onAvatar = { open("Avatar", fromHome = true) },
                        onChat = viewModel::openChat,
                        onMicEnabled = viewModel::setMicEnabled, onMic = viewModel::onMicClick,
                        onCameraToggle = { viewModel.setCameraEnabled(!cameraEnabled) },
                        onOpen = { open(it, fromHome = true) }, onFlow = { if (!avatarOnly) open("Flow") },
                        care = { modifier -> CarePanelHost(viewModel, modifier) },
                        sky = ambient, atmosphere = ambient?.takeIf { ambientOn }, previewTimeOfDay = previewTod,
                        weatherCard = { modifier -> weatherNow?.let { w ->
                            WeatherCard(w.copy(isDay = previewTod?.isLight ?: w.isDay), onDismiss = viewModel::dismissWeatherCard,
                                modifier = modifier, previewTimeOfDay = previewTod, onCycleTimeOfDay = viewModel::cyclePreviewTimeOfDay)
                        } })
                    AppTheme.FLOW -> AndroidFlowScene(flowNotes,
                        onKeep = viewModel::keepFlowNote, onDone = viewModel::completeFlowNote,
                        onLater = viewModel::deferFlowNote, onAsk = { viewModel.askFromFlow(it); viewModel.openChat() },
                        onMenu = { menuOpen = true }, onDesk = { open("Desk") }, onChat = viewModel::openChat,
                        onSettings = { open("Settings", fromHome = true) },
                        micOn = micEnabled, voice = when {
                            listening -> com.meowmba.shared.ui.flow.FlowVoice.Listening(listenPartial)
                            state.interactionState == com.meowmba.shared.companion.InteractionState.SPEAKING ->
                                com.meowmba.shared.ui.flow.FlowVoice.Speaking(messages.lastOrNull()?.content?.take(80))
                            busy -> com.meowmba.shared.ui.flow.FlowVoice.Thinking
                            voiceIssue != null -> com.meowmba.shared.ui.flow.FlowVoice.Note(voiceIssue!!)
                            else -> com.meowmba.shared.ui.flow.FlowVoice.Idle
                        },
                        onMic = viewModel::onMicClick, onMicToggle = { viewModel.setMicEnabled(!micEnabled) },
                        signals = flowSignals, onAttach = { attachOpen = true },
                        idleAvatar = idleKey, idleMinutes = idleAfter, onIdleAvatar = viewModel::setIdleAvatar,
                        northStars = viewModel.northStars, commentFor = viewModel::commentFor, onComment = viewModel::commentOnFlow,
                        activeWork = viewModel.work.tasks.collectAsState().value.count { !it.isTerminal }, onWork = { viewModel.openWork(it) })
                    AppTheme.DESK -> DeskScene(flowNotes, onKeep = viewModel::keepFlowNote,
                        onDone = viewModel::completeFlowNote, onLater = viewModel::deferFlowNote,
                        onMenu = { menuOpen = true }, onFlow = { open("Flow") },
                        onAvatar = { open("Avatar", fromHome = true) }, onChat = viewModel::openChat,
                        onSettings = { open("Settings", fromHome = true) },
                        micOn = micEnabled, onMicToggle = { viewModel.setMicEnabled(!micEnabled) },
                        // Only a non-empty assistant turn is an answer; a phone command's last turn is still yours.
                        thinking = busy && (messages.lastOrNull()?.role != MessageRole.ASSISTANT || messages.lastOrNull()?.content.isNullOrBlank()),
                        replying = (busy && messages.lastOrNull()?.role == MessageRole.ASSISTANT && !messages.lastOrNull()?.content.isNullOrBlank()) ||
                            state.interactionState == com.meowmba.shared.companion.InteractionState.SPEAKING,
                        reply = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content,
                        question = messages.lastOrNull { it.role == MessageRole.USER }?.content,
                        replyDoneAt = if (busy) 0L else messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.timestamp ?: 0L,
                        onBubbleTap = viewModel::openChat,
                        listening = listening,
                        weatherLine = (weatherNow ?: ambient)?.let { "${it.temperatureF}° ${it.condition.label.lowercase()}" },
                        night = (weatherNow ?: ambient)?.let { !it.isDay },
                        northStars = viewModel.northStars, onWork = { viewModel.openWork() },
                        onWeather = { open("Weather", fromHome = true) }, onNotes = { open("Vault", fromHome = true) },
                        onApps = { appsOpen = true }, onAsk = { viewModel.askFromFlow(it) },
                        onOpenUrl = viewModel::openExternal)
                    AppTheme.PROFESSIONAL -> ProfessionalScene(viewModel, pose, avatarMode, onOpen = { open(it, fromHome = true) },
                        onMenu = { menuOpen = true })
                }
                Page.SETTINGS -> SettingsHomeScene(onOpen = { open(it) }, onBack = ::back, onSetup = { open("Setup") })
                Page.MEMORY -> MemoryScene(viewModel, onBack = ::back)
                Page.LLM -> LocalModelScene(viewModel, onRemote = { open("Remote models") }, onVoice = { open("Voice & behavior") }, onBack = ::back)
                Page.REMOTE -> RemoteModelScene(viewModel, onBack = ::back)
                Page.APPEARANCE -> AppearanceScene(onBack = ::back,
                    onHome = ::home, onAvatar = { open("Avatar") },
                    avatarMode = avatarMode, onAvatarMode = viewModel::selectAvatarMode,
                    idleAvatar = idleKey, idleMinutes = idleAfter, onIdleAvatar = viewModel::setIdleAvatar)
                Page.VAULT -> VaultScreen(viewModel.vault, onClose = ::back)
                Page.PRIVACY -> PrivacyScene(viewModel, onBack = ::back)
                Page.DEVICE -> DeviceSettingsScene(viewModel, onBack = ::back, onPrivacy = { open("Privacy") })
                Page.VOICE -> VoiceSettingsScene(viewModel, onBack = ::back)
                Page.MERCH -> MerchStaffScene(viewModel, onBack = ::back)
                Page.BACKGROUND -> com.meowmba.shared.ui.background.BackgroundScene(onClose = ::back,
                    habitatMode = viewModel.habitatMode.collectAsState().value, onHabitatMode = viewModel::setHabitatMode)
                Page.AVATAR -> AvatarScene(pose, avatarMode, onMode = viewModel::selectAvatarMode,
                    onClose = ::back, onFlow = { open("Flow") }, onDesk = { open("Desk") }, onChat = { open("Chat") })
                Page.WEATHER -> WeatherScreen(weather = weatherNow ?: ambient, ambientEnabled = ambientOn,
                    onAmbientChange = viewModel::setAmbientWeather, previewTimeOfDay = previewTod,
                    onPreviewTimeOfDay = { viewModel.previewTimeOfDay.value = it }, onCycleCondition = {},
                    onRefresh = viewModel::refreshWeather, onClose = ::back, location = weatherPlace,
                    status = weatherStatus, onLocationChange = viewModel::setWeatherPlace)
                Page.SETUP -> SetupWizard(viewModel, avatarMode, pose, onFinished = ::home,
                    onOpenChat = { home(); viewModel.openChat() }, onRemoteModels = { open("Remote models") })
                Page.COMPUTER -> ComputerScreen(onBack = ::back)
                Page.FILES -> ComputerScreen(ComputerTab.FILES, onBack = ::back)
                Page.TERMINAL -> ComputerScreen(ComputerTab.TERMINAL, onBack = ::back)
                Page.AGENTS -> AgentsScreen(onBack = ::back, onOpen = { open(it) }, onWork = { viewModel.openWork() })
                Page.VENDING -> com.meowmba.shared.ui.vending.VendingMachineScreen(onClose = ::back)
                Page.COZMO -> CozmoScreen(onBack = ::back, onOpenSettings = { viewModel.openExternal("app-settings:") {} })
                Page.TOTO -> TotoRobotScreen(onBack = ::back)
                Page.UNAVAILABLE -> UnavailableScene(unavailable, onBack = ::back)
            }

            // Full-screen merch menu over the face-only home; the avatar stays underneath.
            if (page == null && avatarOnly) {
                val merchSnap by viewModel.merch.collectAsState()
                LaunchedEffect(Unit) {
                    viewModel.onMerchWalkUp()
                    var sawFace = com.meowmba.shared.platform.FaceAttention.target.value != null
                    com.meowmba.shared.platform.FaceAttention.target.collect { target ->
                        val inView = target != null
                        if (inView && !sawFace) viewModel.onMerchWalkUp()
                        sawFace = inView
                    }
                }
                com.meowmba.shared.merch.MerchTablePanel(merchSnap, viewModel.merchTable, onSay = viewModel::merchSay,
                    modifier = Modifier.fillMaxSize())
                // Above the merch menu so staff can always open Toto settings / Merch table.
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                ) {
                    androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.Settings,
                            contentDescription = "Toto controls and settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Chat rises over whichever home is showing (Android ChatOverlay).
            if (page == null) ChatOverlay(
                isExpanded = chatExpanded, onClose = viewModel::closeChat,
                draft = input, onDraftChange = viewModel::onInputChange, onSend = viewModel::send,
                onDeleteMessage = viewModel::deleteMessage, messages = messages,
                companionName = config.companionName.ifBlank { "Meow" },
                thinking = busy && messages.lastOrNull()?.content.isNullOrBlank(),
                listening = listening, listenPartial = listenPartial, onMicClick = viewModel::onMicClick,
                callActive = callActive, onCallClick = viewModel::toggleCall,
                recognitionIssue = voiceIssue, errorMessage = state.errorMessage,
                onStop = if (busy) viewModel::cancel else null, onAttach = { attachOpen = true },
                terminal = { TerminalUnavailable(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            if (!avatarOnly && (idle || avatarShown)) IdleAvatarLayer(
                avatar = { LumenAvatar(pose, Modifier.fillMaxSize().safeDrawingPadding(),
                    mode = if (avatarShown) avatarMode else if (idleKey == "orb") AvatarMode.ORB else AvatarMode.LUMEN,
                    magnification = AvatarAppearanceStore.state.collectAsState().value.scale,
                    // The avatar handles its own taps, so waking has to be its tap action too.
                    onTap = { idle = false; avatarShown = false; lastTouch = PlatformTime.currentMillis() }) },
                snapshot = flowSnapshotFor(flowNotes, PlatformTime.currentMillis(), flowSignals),
                onWake = { idle = false; avatarShown = false; lastTouch = PlatformTime.currentMillis() },
                onFlow = { idle = false; avatarShown = false; lastTouch = PlatformTime.currentMillis(); open("Flow") },
                onChat = { idle = false; avatarShown = false; lastTouch = PlatformTime.currentMillis(); viewModel.openChat() },
                onApps = { idle = false; avatarShown = false; lastTouch = PlatformTime.currentMillis(); appsOpen = true },
                onMenu = { idle = false; avatarShown = false; lastTouch = PlatformTime.currentMillis(); menuOpen = true },
                backLabel = if (avatarShown) "Back to " + when (theme) {
                    AppTheme.FLOW -> "Flow"; AppTheme.DESK -> "Desk"; AppTheme.PROFESSIONAL -> "workspace"; else -> "home"
                } else null,
            )
            if (appsOpen && !avatarOnly) AppDrawerSheet(onDismiss = { appsOpen = false }, onTool = { appsOpen = false; open(it, fromHome = true) })
            if (attachOpen) AttachSheet(onPick = { attachOpen = false; viewModel.attach(it) }, onDismiss = { attachOpen = false })

            // The tile menu floats over the dimmed current screen (Android BackgroundTiles).
            // Kiosk lock: swipes are off (the Menu button still works); hold the top-left corner for 3 s to leave.
            val workOpen by viewModel.workOpen.collectAsState()
            workOpen?.let { id ->
                WorkHistorySheet(viewModel.work.tasks.collectAsState().value, id.ifBlank { null },
                    viewModel.northStars.views.collectAsState().value, onClose = viewModel::closeWork,
                    onLink = viewModel::linkWorkToNorthStar, onRemove = viewModel.work::remove)
            }
            screenHelp?.let { ScreenHelpSheet(it, onAsk = viewModel::askAboutSharedScreen, onClose = viewModel::closeScreenHelp) }
            if (kiosk) Box(Modifier.align(Alignment.TopStart).size(72.dp).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val held = withTimeoutOrNull(3_000L) { waitForUpOrCancellation() }
                    if (held == null) kioskExit = true
                }
            })
            if (kioskExit) com.meowmba.shared.ui.theme.AlertDialog(onDismissRequest = { kioskExit = false },
                title = { Text("Leave kiosk lock?") },
                text = { Text("Menus and swipes come back. If Guided Access is on, triple-click the side button to end it.") },
                confirmButton = { TextButton(onClick = { kioskExit = false; com.meowmba.shared.platform.LauncherMode.setKiosk(false)
                    com.meowmba.shared.platform.LauncherMode.display?.requestGuidedAccess(false) {} }) { Text("Leave") } },
                dismissButton = { TextButton(onClick = { kioskExit = false }) { Text("Stay") } })
            if (avatarOnly && menuOpen) TotoControlsSheet(onClose = { menuOpen = false },
                onOpen = { open(it, fromHome = true) }, micEnabled = micEnabled,
                onMicEnabled = viewModel::setMicEnabled, cameraEnabled = cameraEnabled,
                onCameraEnabled = viewModel::setCameraEnabled,
                onAbout = { menuOpen = false; aboutToto = true },
                onOpenLooi = { menuOpen = false; showLooi = true })
            if (showLooi) LooiControlScreen(onClose = { showLooi = false })
            if (aboutToto) TotoPersonalityPanel(onDismiss = { aboutToto = false })
            if (!avatarOnly) MeowMenuScene(visible = menuOpen, onClose = { menuOpen = false }, onSelect = { open(it, fromHome = true) },
                avatarLabel = avatarMode.title, onAvatar = {
                    menuOpen = false; stack.clear(); viewModel.closeChat()
                    // Companion's home already is the avatar; elsewhere show it full-screen until tapped.
                    if (theme != AppTheme.COMPANION) { avatarShown = true }
                })

        }
    }
}

/** A mounted display is an avatar, not the idle snapshot of another workspace. */
@Composable
private fun TotoMountedHome(
    pose: com.meowmba.shared.companion.AvatarPose, mode: AvatarMode,
    busy: Boolean, listening: Boolean, speaking: Boolean, offline: Boolean,
    micEnabled: Boolean, cameraEnabled: Boolean, onMenu: () -> Unit, onMic: () -> Unit,
    onTotoControls: () -> Unit = {},
    showMenuButton: Boolean = true,
) {
    val appearance by AvatarAppearanceStore.state.collectAsState()
    val bridgeState by com.meowmba.shared.toto.TotoRobotBridge.state.collectAsState()
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        LumenAvatar(pose, Modifier.fillMaxSize(), mode = mode, magnification = appearance.scale,
            thinking = busy, speaking = speaking, offline = offline, listening = listening)
        TextButton(
            onClick = onTotoControls,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Text(if (bridgeState.connected) "Toto Connected · Controls" else "Toto Controls")
        }
        if (showMenuButton) androidx.compose.material3.IconButton(onClick = onMenu,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Settings,
                contentDescription = "Toto controls and settings", tint = MaterialTheme.colorScheme.onBackground)
        }
        TextButton(onClick = onMic, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            Text(when { listening -> "Listening · stop"; !micEnabled -> "Enable voice"; else -> "Talk to Toto" })
        }
        if (bridgeState.connected) {
            Button(
                onClick = { com.meowmba.shared.toto.TotoRobotBridge.stopAll() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Text("STOP")
            }
        }
        if (cameraEnabled || bridgeState.cameraSharing) Text("Camera on", modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TotoControlsSheet(onClose: () -> Unit, onOpen: (String) -> Unit,
    micEnabled: Boolean, onMicEnabled: (Boolean) -> Unit,
    cameraEnabled: Boolean, onCameraEnabled: (Boolean) -> Unit,
    onAbout: () -> Unit, onOpenLooi: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        // iOS Dialogs measure with infinite max height; heightIn must precede verticalScroll.
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().heightIn(max = dialogScrollMaxHeight())) {
            Column(Modifier.fillMaxWidth().heightIn(max = dialogScrollMaxHeight()).padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Toto settings", style = MaterialTheme.typography.headlineSmall)
                val bridge by com.meowmba.shared.toto.TotoRobotBridge.state.collectAsState()
                TotoTetherControl(
                    tethered = bridge.motionBlockers.any { it.contains("Tether", ignoreCase = true) },
                    connected = bridge.connected,
                    onTether = { com.meowmba.shared.toto.TotoRobotBridge.setTetherLock(true) },
                    onUntether = { com.meowmba.shared.toto.TotoRobotBridge.setTetherLock(false) },
                )
                Spacer(Modifier.height(16.dp))
                val looi by com.meowmba.shared.toto.LooiRobotBridge.state.collectAsState()
                Text("LOOI desktop robot", style = MaterialTheme.typography.titleMedium)
                Text(looi.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onOpenLooi,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp),
                ) { Text("LOOI control") }
                Button(
                    onClick = { onOpen("Merch table") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Merch table settings") }
                TotoHomePolicy.menu.filter { it != "Merch table" }.forEach { title ->
                    TextButton(onClick = { onOpen(title) }, modifier = Modifier.fillMaxWidth()) { Text(title) }
                }
                TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("About Toto") }
                TextButton(onClick = { onMicEnabled(!micEnabled) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (micEnabled) "Mute microphone" else "Enable microphone")
                }
                TextButton(onClick = { onCameraEnabled(!cameraEnabled) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (cameraEnabled) "Turn camera off" else "Turn camera on")
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

@Composable
private fun CarePanelHost(viewModel: CompanionViewModel, modifier: Modifier) {
    val care by viewModel.petCare.collectAsState()
    val habitat by viewModel.habitatMode.collectAsState()
    val learning by viewModel.petLearning.collectAsState()
    val notice by viewModel.petFeedbackNotice.collectAsState()
    val appearance by AvatarAppearanceStore.state.collectAsState()
    PetCarePanel(care = care, onCareChanged = viewModel::setPetCare, onAction = viewModel::habitatAction,
        onFeedback = viewModel::petFeedback, onLearningChanged = viewModel::setPetLearning, learningEnabled = learning,
        feedbackNotice = notice, onResetLearning = viewModel::resetPetLearning, modifier = modifier,
        habitatMode = habitat, onTrick = viewModel::playTrick, formKey = appearance.animal.key,
        onHabitatMode = viewModel::setHabitatMode)
}
