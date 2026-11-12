package com.meowmba.shared.toto

import com.meowmba.shared.bridge.DesktopBridge
import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.platform.PlatformDrivers
import com.meowmba.shared.platform.SpeechTranscriptSink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

data class TotoRobotState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val endpoint: String = TotoRobotBridge.DEFAULT_ENDPOINT,
    val pin: String = TotoRobotBridge.DEFAULT_PIN,
    val token: String = TotoRobotBridge.DEFAULT_TOKEN,
    val message: String = "Toto Orin pairing ready · tap Connect",
    val mounted: Boolean = false,
    val sensorSharing: Boolean = false,
    val cameraSharing: Boolean = false,
    val trackingEnabled: Boolean = true,
    val sensorSummary: String = "Sensors off · distance unavailable",
    val visionSummary: String = "Camera vision off",
    val speechSummary: String = "🎙 Mic off",
    val robotBatterySummary: String = "Robot Battery: connect to view",
    val robotImuSummary: String = "SPRacingF3 IMU: connect to view",
    val robotTofSummary: String = "VL53L5CX ToF 8x8: connect to view",
    val robotArmSummary: String = "V14 Imperial Arm: live_neutral",
    val lastIntentStatus: String = "Ready",
    val baseReady: Boolean = false,
    val tiltReady: Boolean = false,
    val motionPhase: String = "idle",
    val motionReason: String = "Connect to Toto to use movement controls",
    val motionBlockers: List<String> = emptyList(),
    val activeMotionAction: String? = null,
    val stopMessage: String? = null,
)

object TotoRobotBridge {
    const val DEFAULT_HOST = ""
    const val DEFAULT_PORT = 8448
    const val DEFAULT_ENDPOINT = ""
    const val DEFAULT_PIN = ""
    const val DEFAULT_TOKEN = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<TotoRobotState> = _state.asStateFlow()

    private var pumpJob: Job? = null
    private var auxPumpJob: Job? = null
    private var leaseWatchJob: Job? = null
    private var autoConnectJob: Job? = null
    private var userDisconnected: Boolean = false
    private var telemetrySequence: Long = PlatformTime.currentMillis()
    private var motionSequence: Long = 0L
    private var motionGeneration: Long = 0L
    private var leaseExpiresAtMs: Long = 0L
    private var sessionId: String = randomUuid()
    private var activeBehaviorId: String? = null
    private var pendingEmotion: String? = null
    private var lastCameraUploadMs: Long = 0L
    private var lastV14Preset: String? = null
    private var lastV14PresetAtMs: Long = 0L
    private var speechSequence: Long = PlatformTime.currentMillis()
    private var speechStatus: String = "Mic off"
    private var speechListening: Boolean = false
    private var lastHeard: String? = null

    /** Final utterances from the phone's on-device recognizer -> POST /api/meowmba/speech. */
    private val speechSink = object : SpeechTranscriptSink {
        override fun onFinalTranscript(text: String, confidence: Double, startedMs: Long, endedMs: Long, faceDetected: Boolean) {
            val clean = text.trim().take(500)
            if (clean.isEmpty()) return
            lastHeard = clean
            refreshSpeechSummary()
            if (!_state.value.connected) return
            val body = buildJsonObject {
                put("session_id", sessionId)
                put("sequence", ++speechSequence)
                put("text", clean)
                put("is_final", true)
                put("confidence", if (confidence in 0.0..1.0 && confidence > 0.0) JsonPrimitive(confidence) else JsonNull)
                put("started_ms", startedMs)
                put("ended_ms", endedMs)
                put("face_detected", faceDetected)
                put("source", "ios_sfspeech")
            }
            scope.launch {
                postJson("/api/meowmba/speech", body).fold(
                    onSuccess = { reply -> println("[TotoSpeech] sent \"$clean\" -> $reply") },
                    onFailure = { err -> println("[TotoSpeech] POST /api/meowmba/speech failed (non-fatal): ${err.message}") },
                )
            }
        }

        override fun onListeningStatus(listening: Boolean, status: String) {
            speechListening = listening
            speechStatus = status
            refreshSpeechSummary()
        }
    }

    private fun refreshSpeechSummary() {
        val heard = lastHeard?.let { h -> if (h.length > 48) h.take(47) + "…" else h }
        val head = if (speechListening) "🎙 listening" else "🎙 $speechStatus"
        _state.update { it.copy(speechSummary = if (heard != null) "$head · heard: “$heard”" else head) }
    }
    private fun randomUuid(): String {
        val chars = "0123456789abcdef"
        return buildString(36) {
            for (i in 0 until 36) {
                when (i) {
                    8, 13, 18, 23 -> append('-')
                    14 -> append('4')
                    19 -> append(chars[(Random.nextInt(16) and 0x3) or 0x8])
                    else -> append(chars[Random.nextInt(16)])
                }
            }
        }
    }

    private fun loadInitialState(): TotoRobotState {
        val ep = DefaultTextStorage.readText("toto_bridge_endpoint")?.trim().orEmpty()
        val pin = DefaultTextStorage.readText("toto_bridge_pin")?.trim().orEmpty()
        val tok = DesktopBridge.secrets?.readSecret("toto_bridge_token")?.trim()?.takeIf { it.length >= 16 }
            ?: DefaultTextStorage.readText("toto_bridge_token")?.trim()?.takeIf { it.length >= 16 }
            .orEmpty()
        return TotoRobotState(endpoint = ep, pin = pin, token = tok)
    }

    private fun saveCredentials(endpoint: String, pin: String, token: String) {
        DefaultTextStorage.writeText("toto_bridge_endpoint", endpoint)
        DefaultTextStorage.writeText("toto_bridge_pin", pin)
        DefaultTextStorage.writeText("toto_bridge_token", token)
        DesktopBridge.secrets?.writeSecret("toto_bridge_token", token)
    }

    private fun parseHostPort(endpoint: String): Pair<String, Int>? {
        val clean = endpoint.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        if (clean.isEmpty()) return null
        val host = clean.substringBefore(":")
        val port = clean.substringAfter(":", "8448").toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    fun startAutoConnect() {
        userDisconnected = false
        if (!_state.value.connected && !_state.value.connecting) {
            connect()
        }
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = scope.launch {
            while (isActive) {
                delay(5000L)
                if (!userDisconnected && !_state.value.connected && !_state.value.connecting) {
                    connect()
                }
            }
        }
    }

    fun clearPairingSettings() {
        disconnect()
        saveCredentials("", "", "")
        _state.update {
            it.copy(
                endpoint = DEFAULT_ENDPOINT,
                pin = DEFAULT_PIN,
                token = DEFAULT_TOKEN,
                message = "Robot settings cleared",
            )
        }
    }

    fun connect(endpoint: String = _state.value.endpoint, pin: String = _state.value.pin, token: String = _state.value.token) {
        userDisconnected = false
        val hp = parseHostPort(endpoint)
        val normPin = pin.trim().lowercase()
        val normTok = token.trim()
        if (hp == null || normPin.length != 64 || normTok.length < 16) {
            _state.update { it.copy(message = "Enter a valid HTTPS endpoint, 64-hex pin, and bearer token") }
            return
        }
        disconnect(silent = true)
        sessionId = randomUuid()
        telemetrySequence = PlatformTime.currentMillis()
        motionSequence = 0L
        _state.update {
            it.copy(
                endpoint = "https://${hp.first}:${hp.second}",
                pin = normPin,
                token = normTok,
                connecting = true,
                message = "Checking pinned HTTPS connection to ${hp.first}:${hp.second}…",
            )
        }
        scope.launch {
            val statusRes = getJson("/api/meowmba/status")
            statusRes.fold(
                onSuccess = { obj ->
                    val proto = obj["preview_protocol"]?.jsonPrimitive?.intOrNull ?: 0
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (!ok || proto != 1) {
                        _state.update { it.copy(connected = false, connecting = false, message = "Endpoint did not report Toto bridge protocol v1") }
                        return@fold
                    }
                    saveCredentials("https://${hp.first}:${hp.second}", normPin, normTok)
                    val mounted = obj["mounted"]?.jsonPrimitive?.booleanOrNull ?: true
                    PlatformDrivers.toto?.setSensorsEnabled(true)
                    PlatformDrivers.toto?.setCameraEnabled(true)
                    speechSequence = maxOf(speechSequence, PlatformTime.currentMillis())
                    PlatformDrivers.toto?.setSpeechListening(true, speechSink)
                    _state.update {
                        it.copy(
                            connected = true,
                            connecting = false,
                            mounted = mounted,
                            sensorSharing = true,
                            cameraSharing = true,
                            trackingEnabled = true,
                            sensorSummary = "CoreMotion IMU & Proximity active…",
                            visionSummary = "iOS TrueDepth + Vision face tracking & wall clearance active…",
                            message = "Connected to Toto Orin · full sensors, camera & face tracking active",
                        )
                    }
                    updateRobotSensorsFromStatus(obj)
                    refreshControlStatus()
                    startPump()
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            connected = false,
                            connecting = false,
                            message = "Connection failed: ${err.message ?: "check endpoint, pin and token"}",
                        )
                    }
                },
            )
        }
    }

    fun disconnect(silent: Boolean = false) {
        if (!silent) userDisconnected = true
        motionGeneration++
        leaseExpiresAtMs = 0L
        pumpJob?.cancel(); pumpJob = null
        auxPumpJob?.cancel(); auxPumpJob = null
        leaseWatchJob?.cancel(); leaseWatchJob = null
        activeBehaviorId = null
        lastV14Preset = null
        lastV14PresetAtMs = 0L
        PlatformDrivers.toto?.setSensorsEnabled(false)
        PlatformDrivers.toto?.setCameraEnabled(false)
        PlatformDrivers.toto?.setSpeechListening(false, null)
        if (!silent) {
            _state.update {
                it.copy(
                    connected = false,
                    connecting = false,
                    sensorSharing = false,
                    cameraSharing = false,
                    activeMotionAction = null,
                    motionPhase = "idle",
                    message = "Disconnected · sharing off",
                    sensorSummary = "Sensors off · distance unavailable",
                    visionSummary = "Camera vision off",
                )
            }
        }
    }

    fun setMounted(mounted: Boolean) {
        _state.update { it.copy(mounted = mounted) }
        if (_state.value.connected) {
            val payload = buildJsonObject {
                put("intent", if (mounted) "dock_phone" else "undock_phone")
                put("owner_opt_in", true)
            }
            dispatchIntent(payload, if (mounted) "Phone mounted -> V14 Live Neutral" else "Phone unmounted -> V14 Parked")
        }
    }

    fun setSensorSharing(enabled: Boolean) {
        if (enabled && !_state.value.connected) return
        PlatformDrivers.toto?.setSensorsEnabled(enabled)
        _state.update {
            it.copy(
                sensorSharing = enabled,
                sensorSummary = if (enabled) "CoreMotion IMU & Proximity active…" else "Sensors off · distance unavailable",
            )
        }
    }

    fun setCameraSharing(enabled: Boolean) {
        if (enabled && !_state.value.connected) return
        PlatformDrivers.toto?.setCameraEnabled(enabled)
        _state.update {
            it.copy(
                cameraSharing = enabled,
                visionSummary = if (enabled) "iOS Vision face tracking & optical clearance active…" else "Camera vision off",
            )
        }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        _state.update { it.copy(trackingEnabled = enabled) }
        val payload = buildJsonObject {
            put("intent", "tracking_mode")
            put("enabled", enabled)
            put("owner_opt_in", true)
        }
        dispatchIntent(payload, if (enabled) "Face & gaze tracking enabled" else "Face & gaze tracking paused")
    }

    fun sendEmotion(emotion: String) {
        val clean = emotion.trim().lowercase()
        pendingEmotion = clean
        if (_state.value.connected) {
            scope.launch {
                val e = pendingEmotion ?: return@launch
                pendingEmotion = null
                val body = buildJsonObject {
                    put("emotion", e)
                    put("intensity", 0.85)
                    put("head_accent", true)
                    put("play_sound", true)
                }
                postJson("/api/meowmba/emotion", body)
            }
        }
    }

    fun sendV14Preset(preset: String, force: Boolean = false) {
        val clean = preset.trim()
        if (clean.isEmpty()) return
        val now = PlatformTime.currentMillis()
        if (!force && clean == lastV14Preset && now - lastV14PresetAtMs < 4_000L) return
        lastV14Preset = clean
        lastV14PresetAtMs = now
        val payload = buildJsonObject {
            put("intent", "v14_preset")
            put("preset", clean)
            put("owner_opt_in", true)
        }
        dispatchIntent(payload, "V14 Arm pose -> $clean")
    }

    fun sendV14Motif(motif: String) {
        val payload = buildJsonObject {
            put("intent", "v14_motif")
            put("motif", motif)
            put("owner_opt_in", true)
        }
        dispatchIntent(payload, "V14 K-2SO Motif -> $motif")
    }

    fun jogV14Arm(deltaPitchDeg: Double = 0.0, deltaRollDeg: Double = 0.0, deltaElbowDeg: Double = 0.0, reset: Boolean = false) {
        val payload = buildJsonObject {
            put("intent", "v14_jog")
            put("delta_pitch_deg", deltaPitchDeg)
            put("delta_roll_deg", deltaRollDeg)
            put("delta_elbow_deg", deltaElbowDeg)
            put("reset", reset)
            put("owner_opt_in", true)
        }
        val desc = if (reset) "V14 wrist centered" else "V14 jog pitch ${deltaPitchDeg.roundToInt()}° roll ${deltaRollDeg.roundToInt()}°"
        dispatchIntent(payload, desc)
    }

    fun recordPuppetGesture(name: String = "mobile_gesture", durationS: Double = 4.5) {
        val payload = buildJsonObject {
            put("intent", "puppet_record")
            put("name", name)
            put("duration_s", durationS)
            put("owner_opt_in", true)
        }
        dispatchIntent(payload, "Recording LeRobot gesture '$name' (${durationS}s)…")
    }

    fun playPuppetGesture(name: String = "mobile_gesture", speed: Double = 1.0) {
        val payload = buildJsonObject {
            put("intent", "puppet_play")
            put("name", name)
            put("speed", speed)
            put("owner_opt_in", true)
        }
        dispatchIntent(payload, "Playing LeRobot gesture '$name'")
    }

    fun sendNavigationIntent(intent: String, place: String? = null) {
        val payload = buildJsonObject {
            put("intent", intent)
            put("owner_opt_in", true)
            put("speak_english", true)
            if (!place.isNullOrBlank()) put("place", place)
        }
        val label = if (!place.isNullOrBlank()) "Navigate -> $place" else "Intent -> $intent"
        dispatchIntent(payload, label)
    }

    fun setTetherLock(tethered: Boolean) {
        val payload = buildJsonObject {
            put("intent", "tether_mode")
            put("tethered", tethered)
            put("owner_opt_in", true)
        }
        dispatchIntent(payload, if (tethered) "Wheel tether lock enabled" else "Wheel tether lock cleared")
    }

    private fun dispatchIntent(payload: JsonObject, description: String) {
        if (!_state.value.connected) return
        _state.update { it.copy(lastIntentStatus = "Sending: $description…") }
        scope.launch {
            postJson("/api/meowmba/intent", payload).fold(
                onSuccess = { obj ->
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    _state.update {
                        it.copy(
                            lastIntentStatus = if (ok) "Sent: $description" else "Blocked: ${obj["blocked"]?.jsonPrimitive?.contentOrNull ?: "error"}",
                        )
                    }
                    if (payload["intent"]?.jsonPrimitive?.contentOrNull == "tether_mode") {
                        refreshControlStatus()
                    }
                },
                onFailure = { err ->
                    _state.update { it.copy(lastIntentStatus = "Failed: ${err.message ?: description}") }
                },
            )
        }
    }

    private fun isValidAck(obj: JsonObject, expectedSession: String, expectedBid: String, expectedSeq: Long, expectedAction: String): Boolean {
        if (obj["accepted"]?.jsonPrimitive?.booleanOrNull != true || obj["started"]?.jsonPrimitive?.booleanOrNull != true) {
            return false
        }
        obj["session_id"]?.jsonPrimitive?.contentOrNull?.let { if (it != expectedSession) return false }
        obj["behavior_id"]?.jsonPrimitive?.contentOrNull?.let { if (it != expectedBid) return false }
        obj["sequence"]?.jsonPrimitive?.longOrNull?.let { if (it != expectedSeq) return false }
        obj["action"]?.jsonPrimitive?.contentOrNull?.let { if (it != expectedAction) return false }
        return true
    }

    suspend fun startMotion(action: String): Boolean {
        if (!_state.value.connected) return false
        val ticket = ++motionGeneration
        val bid = randomUuid()
        val seq = ++motionSequence
        val sess = sessionId
        activeBehaviorId = bid
        leaseExpiresAtMs = PlatformTime.currentMillis() + 500L
        val body = buildJsonObject {
            put("session_id", sess)
            put("behavior_id", bid)
            put("sequence", seq)
            put("stage", "start")
            put("action", action)
            put("lease_ms", 500)
        }
        return postJson("/api/meowmba/control", body).fold(
            onSuccess = { obj ->
                if (ticket != motionGeneration || activeBehaviorId != bid) {
                    // Start arrived after release/stop/supersession — immediately fence with STOP.
                    scope.launch { postJson("/api/meowmba/control/stop", buildJsonObject {}) }
                    return@fold false
                }
                val accepted = isValidAck(obj, sess, bid, seq, action) && PlatformTime.currentMillis() < leaseExpiresAtMs
                if (!accepted) {
                    activeBehaviorId = null
                    leaseExpiresAtMs = 0L
                    scope.launch { postJson("/api/meowmba/control/stop", buildJsonObject {}) }
                } else {
                    leaseExpiresAtMs = PlatformTime.currentMillis() + 500L
                }
                _state.update {
                    it.copy(
                        activeMotionAction = if (accepted) action else null,
                        motionPhase = if (accepted) "active" else "rejected",
                        motionReason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: if (accepted) "Moving: $action" else "Command rejected",
                    )
                }
                accepted
            },
            onFailure = { err ->
                if (ticket == motionGeneration) {
                    activeBehaviorId = null
                    leaseExpiresAtMs = 0L
                    _state.update { it.copy(activeMotionAction = null, motionPhase = "error", motionReason = err.message ?: "Motion failed") }
                }
                false
            },
        )
    }

    suspend fun heartbeatMotion(): Boolean {
        val ticket = motionGeneration
        val bid = activeBehaviorId ?: return false
        val action = _state.value.activeMotionAction ?: return false
        if (!action.startsWith("base_")) return false
        if (PlatformTime.currentMillis() >= leaseExpiresAtMs) {
            releaseMotion()
            return false
        }
        val seq = ++motionSequence
        val sess = sessionId
        val body = buildJsonObject {
            put("session_id", sess)
            put("behavior_id", bid)
            put("sequence", seq)
            put("stage", "heartbeat")
            put("action", action)
            put("lease_ms", 500)
        }
        return postJson("/api/meowmba/control", body).fold(
            onSuccess = { obj ->
                if (ticket != motionGeneration || activeBehaviorId != bid) return@fold false
                val ok = isValidAck(obj, sess, bid, seq, action)
                if (ok) {
                    leaseExpiresAtMs = PlatformTime.currentMillis() + 500L
                } else {
                    activeBehaviorId = null
                    leaseExpiresAtMs = 0L
                    _state.update {
                        it.copy(
                            activeMotionAction = null,
                            motionPhase = "stopped",
                            motionReason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "Lease ended",
                        )
                    }
                }
                ok
            },
            onFailure = {
                if (ticket == motionGeneration) {
                    activeBehaviorId = null
                    leaseExpiresAtMs = 0L
                    _state.update { it.copy(activeMotionAction = null, motionPhase = "error") }
                }
                false
            },
        )
    }

    /** Single-step Kinect head tilt without opening a wheel lease or sending cancel/stop on release. */
    fun stepTilt(action: String) {
        if (!_state.value.connected || !action.startsWith("tilt_")) return
        val bid = randomUuid()
        val seq = ++motionSequence
        val sess = sessionId
        val body = buildJsonObject {
            put("session_id", sess)
            put("behavior_id", bid)
            put("sequence", seq)
            put("stage", "start")
            put("action", action)
            put("lease_ms", 500)
        }
        scope.launch {
            postJson("/api/meowmba/control", body).fold(
                onSuccess = { obj ->
                    val ok = isValidAck(obj, sess, bid, seq, action)
                    _state.update {
                        it.copy(
                            motionReason = obj["reason"]?.jsonPrimitive?.contentOrNull
                                ?: if (ok) "Tilt: $action" else "Tilt rejected",
                        )
                    }
                },
                onFailure = { err ->
                    _state.update { it.copy(motionReason = err.message ?: "Tilt failed") }
                },
            )
        }
    }

    fun releaseMotion() {
        motionGeneration++
        val bid = activeBehaviorId
        val action = _state.value.activeMotionAction
        val sess = sessionId
        activeBehaviorId = null
        leaseExpiresAtMs = 0L
        _state.update { it.copy(activeMotionAction = null, motionPhase = "released", motionReason = "Hold a direction; release to stop") }
        scope.launch {
            if (bid != null && action != null) {
                val cancelBody = buildJsonObject {
                    put("session_id", sess)
                    put("behavior_id", bid)
                    put("sequence", ++motionSequence)
                    put("stage", "cancel")
                    put("action", action)
                    put("lease_ms", 500)
                }
                postJson("/api/meowmba/control", cancelBody)
            }
            postJson("/api/meowmba/control/stop", buildJsonObject {})
        }
    }

    fun stopAll() {
        motionGeneration++
        activeBehaviorId = null
        leaseExpiresAtMs = 0L
        _state.update {
            it.copy(
                activeMotionAction = null,
                motionPhase = "ui_stop",
                stopMessage = "Sending STOP to Toto…",
            )
        }
        scope.launch {
            val stopCtrl = postJson("/api/meowmba/control/stop", buildJsonObject {})
            val stopIntent = postJson(
                "/api/meowmba/intent",
                buildJsonObject {
                    put("intent", "stop")
                    put("speak_english", false)
                },
            )
            val ok = stopCtrl.isSuccess || stopIntent.isSuccess
            _state.update {
                it.copy(
                    stopMessage = if (ok) "STOP accepted by Toto controller" else "STOP request failed",
                )
            }
        }
    }

    /** Lifecycle safety hook: immediately fence leases and stop Toto when the iOS app leaves active foreground. */
    fun emergencyStop(reason: String = "App left the foreground") {
        val hadActiveMotion = activeBehaviorId != null || _state.value.activeMotionAction != null
        motionGeneration++
        activeBehaviorId = null
        leaseExpiresAtMs = 0L
        if (!_state.value.connected && !hadActiveMotion) return
        _state.update {
            it.copy(
                activeMotionAction = null,
                motionPhase = "background_stop",
                stopMessage = reason,
            )
        }
        scope.launch {
            postJson("/api/meowmba/control/stop", buildJsonObject {})
        }
    }

    private fun startPump() {
        pumpJob?.cancel()
        auxPumpJob?.cancel()
        leaseWatchJob?.cancel()

        // Fast 200ms telemetry + emotion lane — never blocked by 350 KB JPEG uploads or status polls.
        pumpJob = scope.launch {
            while (isActive && _state.value.connected) {
                val st = _state.value
                pendingEmotion?.let { emo ->
                    pendingEmotion = null
                    postJson(
                        "/api/meowmba/emotion",
                        buildJsonObject {
                            put("emotion", emo)
                            put("intensity", 0.85)
                            put("head_accent", true)
                            put("play_sound", true)
                        },
                    )
                }
                if (st.sensorSharing || st.cameraSharing) {
                    val snapRaw = PlatformDrivers.toto?.snapshotJson(st.mounted)
                    val snapObj = snapRaw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    val payload = buildJsonObject {
                        put("sequence", ++telemetrySequence)
                        put("session_id", sessionId)
                        put("mounted", st.mounted)
                        put("imu", snapObj?.get("imu") ?: buildJsonObject {})
                        put("screen_guard", snapObj?.get("screen_guard") ?: buildJsonObject {})
                        put("vision", snapObj?.get("vision") ?: buildJsonObject {})
                    }
                    postJson("/api/meowmba/telemetry", payload).onSuccess { reply ->
                        val brake = reply["screen_guard"]?.jsonObject?.get("brake_active")?.jsonPrimitive?.booleanOrNull == true
                        val sSum = snapObj?.get("sensor_summary")?.jsonPrimitive?.contentOrNull ?: st.sensorSummary
                        val vSum = snapObj?.get("vision_summary")?.jsonPrimitive?.contentOrNull ?: st.visionSummary
                        _state.update {
                            it.copy(
                                sensorSummary = if (st.sensorSharing) sSum + (if (brake) " · brake active" else "") else it.sensorSummary,
                                visionSummary = if (st.cameraSharing) vSum else it.visionSummary,
                            )
                        }
                    }
                }
                delay(200)
            }
        }

        // Separate camera JPEG (~950ms) and robot/control status (~1.2s) lane so slow frames never stall telemetry.
        auxPumpJob = scope.launch {
            var auxTick = 0L
            while (isActive && _state.value.connected) {
                auxTick++
                val st = _state.value
                if (st.cameraSharing && PlatformTime.currentMillis() - lastCameraUploadMs >= 950L) {
                    val jpeg = PlatformDrivers.toto?.popLatestCameraJpeg()
                    if (jpeg != null && jpeg.size in 16..450_000) {
                        lastCameraUploadMs = PlatformTime.currentMillis()
                        postRaw("/api/meowmba/camera", jpeg, "image/jpeg")
                    }
                }
                if (auxTick % 3L == 0L) {
                    getJson("/api/meowmba/status").onSuccess { updateRobotSensorsFromStatus(it) }
                    refreshControlStatus()
                }
                delay(400)
            }
        }

        // Local 50ms deadman lease watchdog matching Android TotoBehaviorCoordinator.tick().
        leaseWatchJob = scope.launch {
            while (isActive && _state.value.connected) {
                val exp = leaseExpiresAtMs
                if (exp > 0L && activeBehaviorId != null && PlatformTime.currentMillis() >= exp) {
                    motionGeneration++
                    activeBehaviorId = null
                    leaseExpiresAtMs = 0L
                    _state.update {
                        it.copy(
                            activeMotionAction = null,
                            motionPhase = "lease_expired",
                            motionReason = "Motion lease expired — stopped",
                        )
                    }
                    postJson("/api/meowmba/control/stop", buildJsonObject {})
                }
                delay(50)
            }
        }
    }

    private suspend fun refreshControlStatus() {
        getJson("/api/meowmba/control/status").onSuccess { obj ->
            val baseReady = obj["controller_ready"]?.jsonPrimitive?.booleanOrNull == true &&
                    obj["physical_output_enabled"]?.jsonPrimitive?.booleanOrNull == true
            val tiltReady = obj["kinect_tilt_available"]?.jsonPrimitive?.booleanOrNull == true
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "Ready"
            val blockers = obj["blockers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            _state.update {
                it.copy(
                    baseReady = baseReady,
                    tiltReady = tiltReady,
                    motionReason = if (it.activeMotionAction != null) it.motionReason else reason,
                    motionBlockers = blockers,
                )
            }
        }
    }

    private fun updateRobotSensorsFromStatus(status: JsonObject) {
        val bat = status["battery"]?.jsonObject
        val v24 = bat?.get("main_24v_v")?.jsonPrimitive?.doubleOrNull
        val rPct = bat?.get("roomba_pct")?.jsonPrimitive?.doubleOrNull
        val rV = bat?.get("roomba_v")?.jsonPrimitive?.doubleOrNull
        val onBase = bat?.get("on_home_base")?.jsonPrimitive?.booleanOrNull == true
        val batStr = if (v24 != null || rPct != null) {
            val v24s = ((v24 ?: 0.0) * 100.0).roundToInt() / 100.0
            val rPs = (rPct ?: 0.0).roundToInt()
            val rVs = ((rV ?: 0.0) * 10.0).roundToInt() / 10.0
            "24V Rail: ${v24s}V · Roomba: ${rPs}% (${rVs}V)${if (onBase) " [Docked]" else ""}"
        } else "Robot Battery: unavailable"

        val rs = status["robot_sensors"]?.jsonObject
        val f3 = rs?.get("f3_imu")?.jsonObject
        val imuStr = if (f3 != null && f3["connected"]?.jsonPrimitive?.booleanOrNull == true) {
            val r = f3["roll_deg"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val p = f3["pitch_deg"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val y = f3["yaw_deg"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val errs = f3["errors"]?.jsonPrimitive?.intOrNull ?: 0
            "F3 IMU: roll ${r}° · pitch ${p}° · yaw ${y.roundToInt()}° · errs $errs"
        } else "F3 IMU: offline"

        val tof = rs?.get("tof")?.jsonObject
        val tofStr = if (tof != null) {
            val t1 = tof["tof_1_min_mm"]?.jsonPrimitive?.intOrNull
            val t2 = tof["tof_2_min_mm"]?.jsonPrimitive?.intOrNull
            val t3 = tof["tof_3_min_mm"]?.jsonPrimitive?.intOrNull
            "ToF 8x8 min: T1=${t1?.let { "${it}mm" } ?: "--"} · T2=${t2?.let { "${it}mm" } ?: "--"} · T3=${t3?.let { "${it}mm" } ?: "--"}"
        } else "ToF 8x8: unavailable"

        val arm = rs?.get("v14_arm")?.jsonObject
        val trk = rs?.get("tracking")?.jsonObject
        val target = arm?.get("target")?.jsonPrimitive?.contentOrNull ?: "live_neutral"
        val pOff = trk?.get("pitch_offset_deg")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val rOff = trk?.get("roll_offset_deg")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val armStr = "V14 Arm: $target (wrist pitch ${pOff}° · roll ${rOff}°)"

        _state.update {
            it.copy(
                robotBatterySummary = batStr,
                robotImuSummary = imuStr,
                robotTofSummary = tofStr,
                robotArmSummary = armStr,
            )
        }
    }

    private suspend fun getJson(path: String): Result<JsonObject> {
        val st = _state.value
        val hp = parseHostPort(st.endpoint) ?: return Result.failure(IllegalArgumentException("Invalid endpoint"))
        val headers = mapOf("Authorization" to "Bearer ${st.token}")
        val driver = PlatformDrivers.toto
        if (driver == null) return Result.failure(IllegalStateException("Native iOS Toto driver unavailable"))
        return suspendCancellableCoroutine { cont ->
            driver.request("GET", hp.first, hp.second, path, null, st.pin, headers, 2500) { status, body, err ->
                if (!cont.isActive) return@request
                if (err != null || body == null) {
                    cont.resume(Result.failure(RuntimeException(err ?: "Empty response")))
                } else if (status !in 200..299) {
                    cont.resume(Result.failure(RuntimeException("HTTP $status")))
                } else {
                    val parsed = runCatching { json.parseToJsonElement(body.decodeToString()).jsonObject }
                    cont.resume(parsed)
                }
            }
        }
    }

    private suspend fun postJson(path: String, payload: JsonObject): Result<JsonObject> {
        val raw = payload.toString().encodeToByteArray()
        return postRaw(path, raw, "application/json")
    }

    private suspend fun postRaw(path: String, raw: ByteArray, contentType: String): Result<JsonObject> {
        val st = _state.value
        val hp = parseHostPort(st.endpoint) ?: return Result.failure(IllegalArgumentException("Invalid endpoint"))
        val headers = mapOf(
            "Authorization" to "Bearer ${st.token}",
            "Content-Type" to contentType,
        )
        val totoDriver = PlatformDrivers.toto
        if (totoDriver != null) {
            return suspendCancellableCoroutine { cont ->
                totoDriver.request("POST", hp.first, hp.second, path, raw, st.pin, headers, 2500) { status, body, err ->
                    if (!cont.isActive) return@request
                    if (err != null || body == null) {
                        cont.resume(Result.failure(RuntimeException(err ?: "Empty response")))
                    } else if (status !in 200..299) {
                        cont.resume(Result.failure(RuntimeException("HTTP $status")))
                    } else {
                        val parsed = runCatching { json.parseToJsonElement(body.decodeToString()).jsonObject }
                        cont.resume(parsed)
                    }
                }
            }
        }
        val fallbackHttp = DesktopBridge.http ?: return Result.failure(IllegalStateException("Pinned HTTP driver unavailable"))
        return suspendCancellableCoroutine { cont ->
            fallbackHttp.post(hp.first, hp.second, path, raw, st.pin, headers, 2500) { status, body, err ->
                if (!cont.isActive) return@post
                if (err != null || body == null) {
                    cont.resume(Result.failure(RuntimeException(err ?: "Empty response")))
                } else if (status !in 200..299) {
                    cont.resume(Result.failure(RuntimeException("HTTP $status")))
                } else {
                    val parsed = runCatching { json.parseToJsonElement(body.decodeToString()).jsonObject }
                    cont.resume(parsed)
                }
            }
        }
    }
}
