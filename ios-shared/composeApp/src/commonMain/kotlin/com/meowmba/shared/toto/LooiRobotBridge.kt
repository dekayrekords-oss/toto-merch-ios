package com.meowmba.shared.toto

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.platform.FaceAttention
import com.meowmba.shared.platform.PlatformDrivers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class LooiScanDevice(val name: String, val id: String)

data class LooiRobotState(
    val connected: Boolean = false,
    val ready: Boolean = false,
    val connecting: Boolean = false,
    val scanning: Boolean = false,
    val motorsOn: Boolean = false,
    val headlightOn: Boolean = false,
    val devices: List<LooiScanDevice> = emptyList(),
    val transport: String = "ble",
    val host: String = LooiRobotBridge.DEFAULT_HOST,
    val port: String = LooiRobotBridge.DEFAULT_PORT.toString(),
    val status: String = "LOOI · not connected",
    val lastExpression: String = "",
)

/**
 * LOOI control for the merch table. The base talks Bluetooth LE GATT
 * (Memba 2 / meowmba-app 736f030): scan a name containing "LOOI", connect,
 * discover fe00/ff02/fed0/fed1/fed2/fed9. Status is Connecting, then
 * Connected as soon as the radio link is up, then Ready when characteristics
 * are found. Motor writes stay off until Motors is turned ON for this launch.
 */
object LooiRobotBridge {
    const val DEFAULT_HOST = ""
    const val DEFAULT_PORT = 9100
    private const val SERVO_HEAD_TILT = 0
    private const val SERVO_HEAD_PAN = 1
    private const val CENTER = 90f
    private const val Y_DEAD = 0.14f
    private const val PITCH_GAIN = 0.40f
    private const val SUSTAIN_MS = 120L
    private const val MIN_NECK_WRITE_MS = 240L
    private const val NECK_STEP_DEADBAND = 0.04f
    private const val MAX_PITCH_STEP = 0.14f
    private const val FACE_HOLD_MS = 450L
    private const val TOUCH_YIELD_MS = 2_500L
    // Base yaw follow: gentle in-place turn pulses well inside the 30% / 4-tick cap.
    private const val X_DEAD = 0.35f
    private const val YAW_SUSTAIN_MS = 500L
    private const val MIN_TURN_MS = 1_400L
    private const val FOLLOW_TURN_SPEED = 18
    private const val FOLLOW_TURN_TICKS = 1
    /** Flip to -1 if a live check shows LOOI turning away from the person. */
    private const val YAW_SIGN = 1
    const val MOTOR_WRITE_NOTE =
        "Motors stay OFF until you turn them on. STOP writes zeros to the motor characteristics and a neutral neck, then turns Motors off. Close the official LOOI app if it is already holding the robot — LOOI usually accepts only one Bluetooth connection."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<LooiRobotState> = _state.asStateFlow()
    private var followJob: Job? = null
    private var usingTcp = false
    private var cueJob: Job? = null
    private var followPausedUntilMs: Long = 0L
    private var lastNeckSentPitch: Float = 0f
    private var lastNeckWriteMs: Long = 0L
    private var lastFaceSeenMs: Long = 0L
    private var sustainSign: Int = 0
    private var sustainSinceMs: Long = 0L
    private var smoothedY: Float = 0f
    private var hasSmoothedY: Boolean = false
    private var smoothedX: Float = 0f
    private var yawSign: Int = 0
    private var yawSinceMs: Long = 0L
    private var lastTurnMs: Long = 0L

    val connected: Boolean get() = _state.value.connected
    val motorsOn: Boolean get() = _state.value.motorsOn

    private fun resetFollowFilter() {
        lastNeckSentPitch = 0f
        lastNeckWriteMs = 0L
        lastFaceSeenMs = 0L
        sustainSign = 0
        sustainSinceMs = 0L
        smoothedY = 0f
        hasSmoothedY = false
        smoothedX = 0f
        yawSign = 0
        yawSinceMs = 0L
        lastTurnMs = 0L
    }

    /** Yield neck tracking while someone is touching the mounted iPhone screen. */
    fun pauseFollow(durationMs: Long = TOUCH_YIELD_MS) {
        val until = PlatformTime.currentMillis() + durationMs.coerceAtLeast(0L)
        if (until > followPausedUntilMs) followPausedUntilMs = until
    }

    private fun log(msg: String) {
        println("[LOOI] $msg")
    }

    private fun loadInitial(): LooiRobotState = LooiRobotState(
        host = DefaultTextStorage.readText("looi_host")?.ifBlank { null } ?: DEFAULT_HOST,
        port = DefaultTextStorage.readText("looi_port")?.ifBlank { null } ?: DEFAULT_PORT.toString(),
    )

    fun setHost(host: String) {
        DefaultTextStorage.writeTextAtomic("looi_host", host)
        _state.value = _state.value.copy(host = host)
    }

    fun setPort(port: String) {
        DefaultTextStorage.writeTextAtomic("looi_port", port)
        _state.value = _state.value.copy(port = port)
    }

    fun scan() {
        val driver = PlatformDrivers.looiBle
        if (driver == null) {
            _state.value = _state.value.copy(scanning = false, status = "LOOI Bluetooth LE is not available on this host")
            return
        }
        log("scan() requested")
        _state.value = _state.value.copy(scanning = true, devices = emptyList(), status = "Scanning for LOOI…")
        driver.scan(
            onDevice = { name, id ->
                log("found device name=$name id=$id")
                val current = _state.value
                if (current.devices.none { it.id == id }) {
                    _state.value = current.copy(devices = current.devices + LooiScanDevice(name, id))
                }
            },
            onStatus = { text -> applyStatus(text) },
            onDone = { ok, status ->
                log("scan done ok=$ok status=$status")
                _state.value = _state.value.copy(scanning = false, status = status.ifBlank {
                    if (ok && _state.value.devices.isNotEmpty()) "Select a LOOI, then Connect."
                    else "No LOOI in range. Power the base, close the official LOOI app if it is open, then Scan again."
                })
            },
        )
    }

    fun stopScan() {
        PlatformDrivers.looiBle?.stopScan()
        _state.value = _state.value.copy(scanning = false)
    }

    /** Default path: Bluetooth LE, auto-connecting the first LOOI in range if [id] is blank. */
    fun connectBle(id: String = "") {
        val driver = PlatformDrivers.looiBle
        if (driver == null) {
            _state.value = _state.value.copy(connected = false, ready = false, connecting = false,
                status = "LOOI Bluetooth LE is not available on this host")
            return
        }
        PlatformDrivers.looi?.disconnect()
        usingTcp = false
        followJob?.cancel(); followJob = null
        log("connectBle(id=${id.ifBlank { "<auto>" }})")
        _state.value = _state.value.copy(
            connecting = true, connected = false, ready = false, motorsOn = false, transport = "ble",
            status = if (id.isBlank()) "Connecting…" else "Connecting over Bluetooth LE…",
        )
        driver.connect(id,
            onStatus = { text -> applyStatus(text) },
            onResult = { ok, status ->
                log("connect result ok=$ok status=$status")
                val ready = ok && (status.startsWith("Ready") || _state.value.ready)
                _state.value = _state.value.copy(
                    connected = ok || _state.value.connected,
                    ready = ready,
                    connecting = false,
                    scanning = false,
                    transport = "ble",
                    status = status,
                )
            },
        )
    }

    /** Advanced fallback only. This LOOI does not use Wi-Fi. */
    fun connectTcp() {
        val driver = PlatformDrivers.looi
        if (driver == null) {
            _state.value = _state.value.copy(connected = false, connecting = false,
                status = "LOOI TCP driver is not available on this host")
            return
        }
        PlatformDrivers.looiBle?.disconnect()
        usingTcp = true
        val host = _state.value.host.ifBlank { DEFAULT_HOST }
        val port = _state.value.port.toIntOrNull() ?: DEFAULT_PORT
        _state.value = _state.value.copy(connecting = true, connected = false, ready = false, transport = "tcp",
            status = "Connecting to $host:$port (advanced Wi-Fi/TCP)…")
        driver.connect(host, port) { ok, status ->
            _state.value = _state.value.copy(
                connected = ok,
                ready = ok,
                connecting = false,
                transport = "tcp",
                status = status,
            )
            if (ok && _state.value.motorsOn) startFollowing() else { followJob?.cancel(); followJob = null }
        }
    }

    fun connect() = connectBle()

    fun disconnect() {
        log("disconnect()")
        stopScan()
        followJob?.cancel(); followJob = null
        cueJob?.cancel(); cueJob = null
        resetFollowFilter()
        if (_state.value.motorsOn || _state.value.connected) {
            PlatformDrivers.looiBle?.sendStop()
        }
        PlatformDrivers.looiBle?.setWritesEnabled(false)
        PlatformDrivers.looiBle?.disconnect()
        PlatformDrivers.looi?.disconnect()
        usingTcp = false
        _state.value = _state.value.copy(
            connected = false, ready = false, connecting = false, scanning = false,
            motorsOn = false, headlightOn = false,
            status = "LOOI · disconnected",
        )
    }

    fun setMotorsOn(on: Boolean) {
        if (on) {
            val cur = _state.value
            if (!cur.connected) {
                _state.value = cur.copy(status = "Connect to LOOI before turning Motors on.")
                return
            }
            if (!usingTcp && !cur.ready) {
                _state.value = cur.copy(status = "Wait for Ready (LOOI BLE channels fe00/fed1) before turning Motors on, or tap Reconnect.")
                return
            }
            log("Motors ON")
            resetFollowFilter()
            PlatformDrivers.looiBle?.setWritesEnabled(true)
            _state.value = cur.copy(motorsOn = true, status = "Motors ON — motion writes are armed. Use STOP to halt.")
            startFollowing()
        } else {
            log("Motors OFF")
            emergencyStop("Motors OFF")
        }
    }

    /** Big red STOP: zero motors, neutral neck, then disable writes. */
    fun stopMotors() {
        log("STOP")
        emergencyStop("STOP sent — motors zeroed, neck centered, Motors OFF")
    }

    /** Called from the native BLE driver on background or disconnect. */
    fun onNativeStopped(reason: String) {
        log("native stopped: $reason")
        followJob?.cancel(); followJob = null
        cueJob?.cancel(); cueJob = null
        resetFollowFilter()
        PlatformDrivers.looiBle?.setWritesEnabled(false)
        val disconnecting = reason.contains("disconnect", ignoreCase = true)
        _state.value = _state.value.copy(
            motorsOn = false,
            headlightOn = false,
            connected = if (disconnecting) false else _state.value.connected,
            ready = if (disconnecting) false else _state.value.ready,
            connecting = if (disconnecting) false else _state.value.connecting,
            status = reason,
        )
    }

    fun emergencyStop(reason: String = "STOP sent — Motors OFF") {
        followJob?.cancel(); followJob = null
        cueJob?.cancel(); cueJob = null
        resetFollowFilter()
        if (_state.value.headlightOn) {
            PlatformDrivers.looiBle?.writeHeadlight(false)
        }
        PlatformDrivers.looiBle?.sendStop()
        PlatformDrivers.looiBle?.setWritesEnabled(false)
        _state.value = _state.value.copy(motorsOn = false, headlightOn = false, status = reason)
    }

    fun setExpression(face: String) {
        val name = face.trim()
        if (name.isEmpty() || !connected) return
        if (!usingTcp) {
            _state.value = _state.value.copy(lastExpression = name, status = "Expression $name (face only — body moves only when Motors is ON)")
            return
        }
        sendLine("E $name")
        _state.value = _state.value.copy(lastExpression = name, status = "Expression: $name")
    }

    fun setServo(id: Int, angleDeg: Float) {
        if (!connected || !usingTcp) return
        val angle = angleDeg.coerceIn(0f, 180f)
        val tenths = (angle * 10f).toInt()
        sendLine("S $id ${tenths / 10}.${abs(tenths % 10)}")
    }

    /** Bounded manual neck pose (-1f up .. +1f down, 0f center). Yields auto-follow briefly. */
    fun setNeckPitchManual(pitch: Float, label: String = "Neck adjusted") {
        if (!connected) return
        val clamped = if (pitch.isFinite()) pitch.coerceIn(-1f, 1f) else 0f
        if (usingTcp) {
            pauseFollow(3_500L)
            setServo(SERVO_HEAD_TILT, (CENTER + clamped * 35f).coerceIn(50f, 130f))
            _state.value = _state.value.copy(status = label)
            return
        }
        if (!motorsOn) {
            _state.value = _state.value.copy(status = "$label skipped — turn Motors ON first.")
            return
        }
        val driver = PlatformDrivers.looiBle ?: return
        cueJob?.cancel()
        pauseFollow(3_500L)
        val byte = LooiBleFrames.neckByte(clamped)
        if (driver.writeNeck(byte)) {
            lastNeckSentPitch = clamped
            lastNeckWriteMs = PlatformTime.currentMillis()
            _state.value = _state.value.copy(status = label)
        }
    }

    /**
     * Bounded rotation pulse (never sends speed 0 as a brake; capped to ±30% speed and <= 4 ticks).
     */
    fun turnPulse(signedSpeed: Int, ticks: Int = 2) {
        if (!connected || signedSpeed == 0) return
        if (usingTcp) return
        if (!motorsOn) {
            _state.value = _state.value.copy(status = "Turn pulse skipped — turn Motors ON first.")
            return
        }
        val driver = PlatformDrivers.looiBle ?: return
        pauseFollow(2_000L)
        val mag = abs(signedSpeed).coerceIn(1, LooiBleFrames.MAX_SPEED_PERCENT)
        val right = signedSpeed > 0
        val frame = LooiBleFrames.rotate(mag, ticks.coerceIn(1, LooiBleFrames.MAX_TICKS), right = right)
        if (driver.writeCommand(frame)) {
            _state.value = _state.value.copy(status = if (right) "Gentle turn right (${mag}%)" else "Gentle turn left (${mag}%)")
        }
    }

    fun setHeadlight(on: Boolean) {
        if (!connected || usingTcp) return
        if (!motorsOn) {
            _state.value = _state.value.copy(status = "Headlight skipped — turn Motors ON first.")
            return
        }
        val driver = PlatformDrivers.looiBle ?: return
        if (driver.writeHeadlight(on)) {
            _state.value = _state.value.copy(headlightOn = on, status = if (on) "Headlight ON" else "Headlight OFF")
        }
    }

    fun nod() {
        if (!connected) return
        if (!usingTcp) {
            if (!motorsOn) {
                _state.value = _state.value.copy(status = "Nod not sent — turn Motors ON first.")
                return
            }
            val driver = PlatformDrivers.looiBle ?: return
            cueJob?.cancel()
            pauseFollow(1_200L)
            cueJob = scope.launch {
                try {
                    driver.writeNeck(LooiBleFrames.NOD_DOWN)
                    delay(260)
                } finally {
                    if (_state.value.motorsOn) {
                        driver.writeNeck(LooiBleFrames.NECK_CENTER)
                        lastNeckSentPitch = 0f
                        lastNeckWriteMs = PlatformTime.currentMillis()
                    }
                }
            }
            _state.value = _state.value.copy(status = "Nod sent")
            return
        }
        setServo(SERVO_HEAD_TILT, 70f)
        scope.launch {
            delay(280)
            setServo(SERVO_HEAD_TILT, CENTER)
        }
    }

    fun cue(kind: String) {
        if (!connected) return
        if (!usingTcp && !motorsOn) {
            log("cue $kind skipped — Motors OFF")
            _state.value = _state.value.copy(status = "Cue $kind not sent — turn Motors ON first.")
            return
        }
        when (kind) {
            "greet", "pay" -> {
                setExpression("happy")
                if (!usingTcp) {
                    val driver = PlatformDrivers.looiBle ?: return
                    val restoreLight = _state.value.headlightOn
                    cueJob?.cancel()
                    pauseFollow(1_400L)
                    cueJob = scope.launch {
                        try {
                            driver.writeNeck(LooiBleFrames.NOD_DOWN)
                            driver.writeHeadlight(true)
                            delay(260)
                            if (_state.value.motorsOn) {
                                driver.writeNeck(LooiBleFrames.NECK_CENTER)
                                delay(140)
                            }
                        } finally {
                            if (_state.value.motorsOn) {
                                driver.writeHeadlight(restoreLight)
                                lastNeckSentPitch = 0f
                                lastNeckWriteMs = PlatformTime.currentMillis()
                            }
                        }
                    }
                    _state.value = _state.value.copy(status = "Cue $kind sent")
                } else {
                    nod()
                }
            }
            "select" -> {
                setExpression("curious")
                if (!usingTcp && motorsOn) {
                    val driver = PlatformDrivers.looiBle ?: return
                    cueJob?.cancel()
                    pauseFollow(1_200L)
                    cueJob = scope.launch {
                        try {
                            driver.writeNeck(LooiBleFrames.NOD_UP)
                            delay(260)
                        } finally {
                            if (_state.value.motorsOn) {
                                driver.writeNeck(LooiBleFrames.NECK_CENTER)
                                lastNeckSentPitch = 0f
                                lastNeckWriteMs = PlatformTime.currentMillis()
                            }
                        }
                    }
                }
            }
            else -> setExpression(kind)
        }
    }

    fun followFace(offsetX: Float?, offsetY: Float?) {
        if (!connected) return
        val now = PlatformTime.currentMillis()
        if (cueJob?.isActive == true || now < followPausedUntilMs) return
        if (usingTcp) {
            val pan = if (offsetX == null) CENTER else {
                val x = offsetX.coerceIn(-1f, 1f)
                if (abs(x) < 0.1f) CENTER else (CENTER + x * 35f)
            }
            val tilt = if (offsetY == null) CENTER else {
                val y = offsetY.coerceIn(-1f, 1f)
                if (abs(y) < 0.14f) CENTER else (CENTER + y * 35f).coerceIn(50f, 130f)
            }
            setServo(SERVO_HEAD_PAN, pan)
            setServo(SERVO_HEAD_TILT, tilt)
            return
        }
        if (!motorsOn) return

        // Camera is mounted on LOOI's tilting head: use deadband + 0.40x gain + 120ms sustain +
        // 240ms spacing + step slew limit so tilting the phone never creates a nod oscillation loop.
        if (offsetY == null || !offsetY.isFinite()) {
            if (lastFaceSeenMs > 0L && now - lastFaceSeenMs < FACE_HOLD_MS) return
            hasSmoothedY = false
            sustainSign = 0
            yawSign = 0
            if (abs(lastNeckSentPitch) >= NECK_STEP_DEADBAND && now - lastNeckWriteMs >= MIN_NECK_WRITE_MS) {
                val next = (lastNeckSentPitch * 0.5f).let { if (abs(it) < NECK_STEP_DEADBAND) 0f else it }
                if (PlatformDrivers.looiBle?.writeNeck(LooiBleFrames.neckByte(next)) == true) {
                    lastNeckSentPitch = next
                    lastNeckWriteMs = now
                }
            }
            return
        }

        lastFaceSeenMs = now
        val rawY = offsetY.coerceIn(-1f, 1f)
        val rawX = if (offsetX == null || !offsetX.isFinite()) 0f else offsetX.coerceIn(-1f, 1f)
        if (!hasSmoothedY) {
            hasSmoothedY = true
            smoothedY = rawY
            smoothedX = rawX
        } else {
            smoothedY += (rawY - smoothedY) * 0.35f
            smoothedX += (rawX - smoothedX) * 0.35f
        }
        followYaw(now)

        if (abs(smoothedY) < Y_DEAD) {
            sustainSign = 0
            return
        }

        val sign = if (smoothedY > 0f) 1 else -1
        if (sign != sustainSign) {
            sustainSign = sign
            sustainSinceMs = now
            return
        }
        if (now - sustainSinceMs < SUSTAIN_MS || now - lastNeckWriteMs < MIN_NECK_WRITE_MS) return

        // The camera rides on the tilting head, so offsetY is the error *after* the current pose:
        // step incrementally from the last sent pitch instead of treating it as an absolute target
        // (an absolute target pulls the head back to level as soon as the face nears center).
        val delta = (smoothedY * PITCH_GAIN).coerceIn(-MAX_PITCH_STEP, MAX_PITCH_STEP)
        if (abs(delta) < NECK_STEP_DEADBAND) return
        val nextPitch = (lastNeckSentPitch + delta).coerceIn(-0.65f, 0.65f)
        if (PlatformDrivers.looiBle?.writeNeck(LooiBleFrames.neckByte(nextPitch)) == true) {
            lastNeckSentPitch = nextPitch
            lastNeckWriteMs = now
        }
    }

    /** Sustained horizontal offset -> one gentle rotate pulse (never speed 0, never a brake). */
    private fun followYaw(now: Long) {
        if (abs(smoothedX) < X_DEAD) {
            yawSign = 0
            return
        }
        val sign = if (smoothedX > 0f) 1 else -1
        if (sign != yawSign) {
            yawSign = sign
            yawSinceMs = now
            return
        }
        if (now - yawSinceMs < YAW_SUSTAIN_MS || now - lastTurnMs < MIN_TURN_MS) return
        val right = sign * YAW_SIGN > 0
        val frame = LooiBleFrames.rotate(FOLLOW_TURN_SPEED, FOLLOW_TURN_TICKS, right = right)
        if (PlatformDrivers.looiBle?.writeCommand(frame) == true) {
            lastTurnMs = now
            // Let the image settle before judging the new offset.
            yawSign = 0
            hasSmoothedY = false
        }
    }

    private fun startFollowing() {
        followJob?.cancel()
        followJob = scope.launch {
            FaceAttention.target.collect { pair ->
                if (!connected || !_state.value.motorsOn) return@collect
                if (pair == null) followFace(null, null)
                else {
                    val gx = ((pair.first - 0.5f) * 2f).coerceIn(-1f, 1f)
                    val gy = ((pair.second - 0.5f) * 2f).coerceIn(-1f, 1f)
                    followFace(gx, gy)
                }
            }
        }
    }

    private fun applyStatus(text: String) {
        log("status: $text")
        val s = _state.value
        val ready = text.startsWith("Ready")
        val disconnected = text.startsWith("Disconnected") ||
            text.startsWith("LOOI · disconnected") ||
            text.startsWith("LOOI disconnected")
        val missingChannels = text.contains("not found", ignoreCase = true) ||
            text.contains("timed out before", ignoreCase = true)
        val linked = text.startsWith("Connected") || ready ||
            text.contains("Discovering LOOI") || text.contains("Discovering characteristics")
        val nextReady = when {
            disconnected || missingChannels -> false
            ready -> true
            else -> s.ready
        }
        _state.value = s.copy(
            status = text,
            connected = when {
                disconnected -> false
                linked || ready -> true
                else -> s.connected
            },
            ready = nextReady,
            connecting = when {
                disconnected || ready || linked -> false
                else -> s.connecting
            },
            scanning = if (disconnected) false else s.scanning,
            motorsOn = if (disconnected || (!usingTcp && !nextReady)) false else s.motorsOn,
            headlightOn = if (disconnected) false else s.headlightOn,
        )
    }

    private fun sendLine(line: String) {
        PlatformDrivers.looi?.sendLine(line)
    }
}
