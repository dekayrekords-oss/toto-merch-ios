package com.meowmba.shared.robot

import com.meowmba.shared.core.PlatformTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** iOS: a UDP link bound to the robot's Wi-Fi (Network framework). Callbacks may come on any thread. */
interface UdpDriver {
    fun open(host: String, port: Int, onReceive: (ByteArray) -> Unit, onFailed: (String) -> Unit)
    fun send(bytes: ByteArray)
    fun close()
}

data class CozmoState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val message: String = "Not connected",
    val firmware: String = "",
    val serial: String = "",
    val telemetry: CozmoTelemetry? = null,
    val wheelsEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val expression: String = "neutral",
    val companionEyes: Boolean = true,
    val cubes: List<CozmoCube> = emptyList(),
    val lastEvent: String = "",
)
data class CozmoCube(val factoryId: Int, val type: Int, val rssi: Int, val objectId: Int? = null)

/** Reliable ordered packets; telemetry/image events are out of band and never advance the ACK. */
internal class CozmoReliability {
    class Pending(var packet: CozmoProtocol.Packet, var sentAt: Long, val createdAt: Long = sentAt)
    val pending = linkedMapOf<Int, Pending>()
    private val received = mutableMapOf<Int, CozmoProtocol.Packet>()
    private var nextSend = 0
    private var nextReceive = 0
    var ack = CozmoProtocol.OOB; private set
    fun add(packet: CozmoProtocol.Packet, now: Long): Pair<Int, Pending> {
        check(pending.size < 16) { "Cozmo command queue is full" }
        val seq = nextSend
        nextSend = CozmoProtocol.next(nextSend)
        return seq to Pending(packet, now).also { pending[seq] = it }
    }
    fun receive(frame: CozmoProtocol.Frame): List<CozmoProtocol.Packet> {
        val first = pending.keys.firstOrNull()
        if (first != null && frame.ack != CozmoProtocol.OOB && CozmoProtocol.distance(first, frame.ack) < pending.size) {
            val count = CozmoProtocol.distance(first, frame.ack) + 1
            pending.keys.take(count).forEach { pending.remove(it) }
        }
        val delivered = mutableListOf<CozmoProtocol.Packet>()
        var seq = frame.first
        frame.packets.forEach { p ->
            if (p.type >= 5) delivered += p
            else {
                if (seq in 0 until CozmoProtocol.MOD && CozmoProtocol.distance(nextReceive, seq) < 62 && seq !in received) received[seq] = p
                seq = CozmoProtocol.next(seq)
            }
        }
        while (true) {
            val p = received.remove(nextReceive) ?: break
            ack = nextReceive
            nextReceive = CozmoProtocol.next(nextReceive)
            delivered += p
        }
        return delivered
    }
    fun revokeMotion() { pending.values.filter { it.packet.id in MOTORS }.forEach { it.packet = CozmoProtocol.command(0x3b) } }
    companion object { val MOTORS = setOf(0x32, 0x34, 0x35, 0x36, 0x37, 0x58) }
}

/**
 * Android CozmoSession on one coroutine instead of a thread. One explicit connection; no
 * reconnect or motion replay after link loss; every move is bounded and Stop wins.
 */
class CozmoSession(private val udp: UdpDriver, private val onImage: (ByteArray?) -> Unit = {}) {
    private sealed class Request {
        data class Command(val packets: List<CozmoProtocol.Packet>, val motor: Boolean = false, val wheels: Boolean = false, val created: Long, val duration: Long = 0) : Request()
        data class Wheels(val enabled: Boolean) : Request()
        data class Camera(val enabled: Boolean) : Request()
        data class Expression(val name: String) : Request()
        data class Companion(val enabled: Boolean) : Request()
        data class Reaction(val name: String, val created: Long) : Request()
    }
    private val _state = MutableStateFlow(CozmoState())
    val state: StateFlow<CozmoState> = _state.asStateFlow()
    private var st: CozmoState get() = _state.value; set(v) { _state.value = v }
    private val queue = Channel<Request>(32)
    private val inbox = Channel<ByteArray>(256)
    @kotlin.concurrent.Volatile private var stopping = false
    @kotlin.concurrent.Volatile private var halt = false
    private val wire = CozmoReliability()
    private val camera = CozmoCamera()
    private var job: Job? = null
    private var wheelUntil = 0L
    private var lastStateAt = 0L
    private var lastRobotTimestamp: Long? = null
    private var firmwareSupported = false
    private var bodySeen = false
    private var lastFace = 0L
    private var faceTicks = 0
    private var blinkUntil = 0L
    private var lastCameraRequest = 0L
    private var pingCounter = 0
    private var initializedAt = 0L
    private var lastAnimationTick = 0L
    private val reactionSteps = ArrayDeque<Pair<Long, CozmoReactionStep>>()
    private fun clock() = PlatformTime.currentMillis()

    fun start(scope: CoroutineScope) {
        udp.open(CozmoProtocol.HOST, CozmoProtocol.PORT, { inbox.trySend(it) }, { error -> st = st.copy(message = error); stopping = true })
        job = scope.launch(Dispatchers.Default) { run() }
    }

    private fun enqueue(r: Request): String {
        if (!st.connected || stopping) return "Cozmo is not connected"
        return if (queue.trySend(r).isSuccess) "Command queued for Cozmo" else "Cozmo is busy; try again"
    }
    fun setWheels(enabled: Boolean): String = enqueue(Request.Wheels(enabled))
    fun setCamera(enabled: Boolean): String = enqueue(Request.Camera(enabled))
    fun setCompanion(enabled: Boolean): String = enqueue(Request.Companion(enabled))
    fun stop() { halt = true }
    fun close() { stopping = true; halt = true }
    fun expression(name: String): String = if (name in CozmoProtocol.EXPRESSIONS) enqueue(Request.Expression(name)) else "Unknown Cozmo expression"

    fun action(action: String): String {
        if (action == "stop") { stop(); return "Stop requested; check that Cozmo stops" }
        if (action == "status") return statusText()
        val p = CozmoProtocol
        if (action in CozmoReactions.names) return enqueue(Request.Reaction(action, clock()))
        if (action in p.EXPRESSIONS) return expression(action)
        if (action.startsWith("light_")) {
            val color = p.COLORS[action.removePrefix("light_")] ?: return "Unknown backpack color"
            return enqueue(Request.Command(listOf(p.light(color), p.light(color, side = true)), created = clock()))
        }
        val wheels = when (action) {
            "forward" -> 45f to 45f; "back" -> -40f to -40f; "left" -> -35f to 35f; "right" -> 35f to -35f
            "arc_left" -> 20f to 45f; "arc_right" -> 45f to 20f; else -> null
        }
        if (wheels != null) {
            if (!st.wheelsEnabled) return "Enable short floor moves in Robot → Cozmo first"
            st.telemetry?.motionBlock(clock())?.let { return it }
            if (st.telemetry == null) return "Waiting for Cozmo's sensors"
            return enqueue(Request.Command(listOf(p.wheels(wheels.first, wheels.second)), true, true, clock(), 350))
        }
        val packet = when (action) {
            "look_up" -> p.head(44.5f); "look_down" -> p.head(-25f); "look_center" -> p.head(0f)
            "lift_up" -> p.lift(92f); "lift_middle" -> p.lift(62f); "lift_down" -> p.lift(32f)
            "calibrate" -> {
                val block = st.telemetry?.motionBlock(clock()) ?: if (st.telemetry == null) "Waiting for sensors" else null
                if (block != null) return block
                p.command(0x58, byteArrayOf(1, 1))
            }
            "headlight_on" -> p.command(0x0b, byteArrayOf(1)); "headlight_off" -> p.command(0x0b, byteArrayOf(0))
            else -> return "Unsupported Cozmo action: $action"
        }
        return enqueue(Request.Command(listOf(packet), packet.id in CozmoReliability.MOTORS, created = clock()))
    }

    fun connectCube(factoryId: Int): String {
        if (st.cubes.none { it.factoryId == factoryId }) return "That cube has not been discovered"
        return enqueue(Request.Command(listOf(CozmoProtocol.command(5, LeWriter(5).putInt(factoryId).put(1).array())), created = clock()))
    }

    fun statusText(): String = buildString {
        append(st.message)
        st.telemetry?.let { t ->
            append("; battery ${fmt(t.batteryVolts, 2)} V; head ${t.headDegrees.toInt()}°; lift ${t.liftMm.toInt()} mm")
            if (t.pickedUp) append("; picked up")
            if (t.onCharger) append("; on charger")
            if (t.cliff) append("; edge detected")
        }
        append("; wheels ${if (st.wheelsEnabled) "enabled" else "disabled"}; ${st.cubes.size} cube(s)")
    }

    private fun sendRaw(frame: CozmoProtocol.Frame) = udp.send(CozmoProtocol.encode(frame))
    private fun send(p: CozmoProtocol.Packet) { val (seq, pending) = wire.add(p, clock()); sendPending(seq, pending) }
    private fun sendPending(seq: Int, pending: CozmoReliability.Pending) {
        sendRaw(CozmoProtocol.Frame(7, seq, seq, wire.ack, listOf(pending.packet)))
        pending.sentAt = clock()
    }
    private fun sendFace(expression: String, blink: Boolean = false) = send(CozmoProtocol.face(CozmoProtocol.eyes(expression, blink)))

    private fun drainMotion() {
        val keep = mutableListOf<Request>()
        while (true) { val r = queue.tryReceive().getOrNull() ?: break; if (!(r is Request.Command && r.motor || r is Request.Wheels || r is Request.Reaction)) keep += r }
        keep.forEach { queue.trySend(it) }
    }

    private fun stopNow(disarm: Boolean = true) {
        drainMotion()
        reactionSteps.clear()
        wire.revokeMotion()
        wheelUntil = 0
        if (firmwareSupported) {
            if (wire.pending.size < 16) send(CozmoProtocol.command(0x3b))
            wire.pending.filterValues { it.packet.id == 0x3b }.forEach { (seq, p) -> sendPending(seq, p) }
        }
        st = st.copy(wheelsEnabled = st.wheelsEnabled && !disarm, lastEvent = "Stop requested; verify Cozmo has stopped")
    }

    private suspend fun run() {
        val started = clock()
        var lastPing = 0L
        var lastReset = 0L
        var handshake = false
        try {
            st = st.copy(connecting = true, message = "Contacting Cozmo…")
            while (!stopping && currentCoroutineContext().isActive) {
                val now = clock()
                if (!handshake && now - lastReset >= 1000) { sendRaw(CozmoProtocol.Frame(1, 0, 0, CozmoProtocol.OOB, emptyList())); lastReset = now }
                if (!st.connected && now - started > 10_000) error("No compatible Cozmo response. Join Cozmo's Wi-Fi in Settings and close the official app.")
                if (handshake && now - lastPing >= 33) {
                    val ping = LeWriter(17).putDouble(now / 1000.0).putInt(pingCounter++).putInt(0).put(0).array()
                    sendRaw(CozmoProtocol.Frame(11, CozmoProtocol.OOB, CozmoProtocol.OOB, wire.ack, listOf(CozmoProtocol.Packet(11, data = ping))))
                    lastPing = now
                }
                if (halt) { halt = false; stopNow() }
                if (wheelUntil > 0 && (now >= wheelUntil || st.telemetry?.motionBlock(now) != null)) stopNow(disarm = st.telemetry?.motionBlock(now) != null)
                if (st.connected && now - lastStateAt > 1500) error("Cozmo connection lost; reconnect manually")
                if (wire.pending.values.any { now - it.createdAt > 2000 }) error("Cozmo is not acknowledging commands; reconnect manually")
                if (wire.pending.size < 8) queue.tryReceive().getOrNull()?.let { process(it, now) }
                if (st.connected && wire.pending.size < 8 && now - lastAnimationTick >= 33) { send(CozmoProtocol.command(0x8f)); lastAnimationTick = now }
                if (reactionSteps.isNotEmpty() && st.telemetry?.motionBlock(now) != null) stopNow()
                if (wire.pending.size < 8 && reactionSteps.firstOrNull()?.first?.let { now >= it } == true) {
                    val (due, step) = reactionSteps.removeFirst()
                    if (now - due > 500) { reactionSteps.clear(); st = st.copy(lastEvent = "Reaction canceled: robot link delayed") }
                    else {
                        step.head?.let { send(CozmoProtocol.head(it)) }
                        step.lift?.let { send(CozmoProtocol.lift(it)) }
                        step.expression?.let { sendFace(it); st = st.copy(expression = it) }
                    }
                }
                if (st.connected && wire.pending.size < 8 && blinkUntil > 0 && now >= blinkUntil) { sendFace(st.expression); blinkUntil = 0 }
                if (st.connected && wire.pending.size < 8 && now - lastFace > 1500) {
                    val blink = st.companionEyes && ++faceTicks % 4 == 0
                    sendFace(st.expression, blink)
                    if (blink) blinkUntil = now + 140
                    lastFace = now
                }
                if (st.cameraEnabled && wire.pending.size < 8 && now - lastCameraRequest > 300) {
                    send(CozmoProtocol.command(0x4c, byteArrayOf(2, 4))); lastCameraRequest = now
                }
                wire.pending.toList().filter { now - it.second.sentAt >= 120 }.forEach { (seq, p) -> sendPending(seq, p) }
                val datagram = withTimeoutOrNull(10) { inbox.receive() } ?: continue
                val frame = CozmoProtocol.decode(datagram) ?: continue
                if (frame.type == 3) error("Cozmo ended the connection")
                wire.receive(frame).forEach { p ->
                    if (p.type == 2) handshake = true
                    if (p.type == 3) error("Cozmo disconnected")
                    receive(p, now)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            st = st.copy(message = e.message ?: "Cozmo connection failed")
        } finally {
            runCatching { stopNow() }
            // Do not send protocol Disconnect: old firmware can reboot if packets follow it.
            udp.close()
            camera.reset(); onImage(null)
            st = st.copy(connecting = false, connected = false, wheelsEnabled = false, cameraEnabled = false,
                telemetry = null, cubes = emptyList(), message = if (stopping && st.message.startsWith("Cozmo connected")) "Disconnected" else st.message)
        }
    }

    private fun process(request: Request, now: Long) {
        when (request) {
            is Request.Wheels -> st = st.copy(wheelsEnabled = request.enabled, lastEvent = "Short floor moves ${if (request.enabled) "enabled" else "disabled"}")
            is Request.Companion -> st = st.copy(companionEyes = request.enabled)
            is Request.Camera -> {
                if (!request.enabled) { send(CozmoProtocol.camera(false)); camera.reset(); onImage(null) }
                else send(CozmoProtocol.command(0x66, byteArrayOf(0)))
                st = st.copy(cameraEnabled = request.enabled)
            }
            is Request.Expression -> { sendFace(request.name); st = st.copy(expression = request.name) }
            is Request.Reaction -> {
                val block = st.telemetry?.motionBlock(now) ?: if (st.telemetry == null) "Waiting for sensors" else null
                if (block != null || now - request.created > 500) st = st.copy(lastEvent = block ?: "Reaction expired; try again")
                else {
                    reactionSteps.clear()
                    CozmoReactions.steps(request.name).forEach { reactionSteps.addLast(now + it.atMs to it) }
                    st = st.copy(lastEvent = "${request.name.replace('_', ' ')} requested; Stop cancels it")
                }
            }
            is Request.Command -> {
                val block = when {
                    now - request.created > 500 -> "Command expired; try again"
                    request.motor && (st.telemetry == null || now - lastStateAt > 500) -> "Waiting for fresh robot sensors"
                    request.motor && st.telemetry?.falling == true -> "Motor command blocked: Cozmo is falling"
                    request.packets.any { it.id == 0x58 } -> st.telemetry?.motionBlock(now)
                    request.wheels && !st.wheelsEnabled -> "Enable short floor moves first"
                    request.wheels -> st.telemetry?.motionBlock(now)
                    else -> null
                }
                if (block != null) { st = st.copy(lastEvent = block); return }
                if (request.motor) reactionSteps.clear()
                request.packets.forEach(::send)
                st = st.copy(lastEvent = "Command sent to Cozmo; check the sensor values for movement")
                if (request.wheels) wheelUntil = now + request.duration
            }
        }
    }

    private fun receive(p: CozmoProtocol.Packet, now: Long) {
        val b = LeReader(p.data)
        when (p.id) {
            0xee -> {
                if (p.data.size < 4) return
                val length = b.getShort(2) and 65535
                if (length > p.data.size - 4) return
                val json = p.data.copyOfRange(4, 4 + length).decodeToString()
                val version = Regex("\"version\"\\s*:\\s*\"?(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
                check(version == 2381 && !json.contains("FACTORY")) { "Unsupported Cozmo firmware ($version). This adapter uses the documented 2381 protocol." }
                if (!firmwareSupported) {
                    firmwareSupported = true
                    st = st.copy(firmware = version.toString())
                    send(CozmoProtocol.command(0x25)); send(CozmoProtocol.command(0x25))
                }
            }
            0xed -> if (firmwareSupported && !bodySeen && p.data.size >= 12) {
                bodySeen = true
                st = st.copy(serial = (b.getInt(0).toLong() and 0xffffffffL).toString(16))
                send(CozmoProtocol.command(0x45, LeWriter(24).putInt(0).putInt(0).putInt(1).putFloat(0f).putFloat(0f).putInt(Int.MIN_VALUE).array()))
                send(CozmoProtocol.command(0x4b, ByteArray(8)))
                send(CozmoProtocol.command(0x9f))
                send(CozmoProtocol.command(0x60, byteArrayOf(1)))
                send(CozmoProtocol.command(0x0a, byteArrayOf(1)))
                initializedAt = now
            }
            0xf0 -> if (bodySeen) {
                val telemetry = CozmoTelemetry.decode(p.data, now) ?: return
                val old = st.telemetry
                val timestamp = b.getInt(0).toLong() and 0xffffffffL
                val previous = lastRobotTimestamp
                if (previous != null && ((timestamp - previous) and 0xffffffffL) !in 1..0x7fffffffL) return
                lastRobotTimestamp = timestamp
                lastStateAt = now
                val ready = now - initializedAt >= 500
                st = st.copy(telemetry = telemetry, connecting = !ready, connected = ready,
                    message = if (ready) "Cozmo connected" else "Waiting for motor initialization…")
                // Sensor reactions change the face only; never start unsolicited motors.
                if (ready && st.companionEyes && old != null) {
                    val reaction = when {
                        telemetry.falling && !old.falling -> "surprised"
                        telemetry.pickedUp && !old.pickedUp -> "curious"
                        telemetry.onCharger && !old.onCharger -> "sleepy"
                        !telemetry.pickedUp && old.pickedUp -> "happy"
                        else -> null
                    }
                    reaction?.let { queue.trySend(Request.Expression(it)) }
                }
            }
            0xf2 -> if (st.cameraEnabled) camera.accept(p.data)?.let(onImage)
            0xf3 -> if (p.data.size >= 9) {
                val id = b.getInt(0); val type = b.getInt(4); val signal = p.data[8].toInt()
                if (type in 1..3) {
                    val existing = st.cubes.firstOrNull { it.factoryId == id }
                    st = st.copy(cubes = (st.cubes.filterNot { it.factoryId == id } + CozmoCube(id, type, signal, existing?.objectId)).takeLast(3))
                }
            }
            0xd0 -> if (p.data.size >= 13) {
                val objectId = b.getInt(0); val factory = b.getInt(4); val connected = p.data[12].toInt() != 0
                st = st.copy(cubes = st.cubes.map { if (it.factoryId == factory) it.copy(objectId = objectId.takeIf { connected }) else it },
                    lastEvent = "Cube ${if (connected) "connected" else "disconnected"}")
            }
            0xb6 -> if (p.data.size >= 9) {
                st = st.copy(lastEvent = "Cube tapped ${p.data[8].toInt() and 255} time(s)")
                if (st.companionEyes) queue.trySend(Request.Expression("happy"))
            }
            0xd1 -> if (p.data.size >= 3) st = st.copy(lastEvent =
                "${if (p.data[0].toInt() == 3) "Head" else "Lift"} calibration: ${if (p.data[1].toInt() != 0) "started" else "finished"}")
        }
    }

    companion object {
        fun fmt(v: Float, digits: Int): String {
            var m = 1; repeat(digits) { m *= 10 }
            val r = kotlin.math.round(v * m).toLong()
            val whole = r / m; val frac = kotlin.math.abs(r % m).toString().padStart(digits, '0')
            return "$whole.$frac"
        }
    }
}

/** Android CozmoController: one session at a time; its state and camera frames for the screen. */
object Cozmo {
    var udp: (() -> UdpDriver)? = null
    private var session: CozmoSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = MutableStateFlow(CozmoState())
    val image = MutableStateFlow<ByteArray?>(null)
    private var mirror: Job? = null

    fun connect() {
        val factory = udp ?: run { state.value = CozmoState(message = "Robot networking isn't available in this build."); return }
        session?.close()
        image.value = null
        val next = CozmoSession(factory()) { image.value = it }
        session = next
        mirror?.cancel()
        mirror = scope.launch { next.state.collect { state.value = it } }
        next.start(scope)
    }
    fun disconnect() { session?.close(); session = null; image.value = null }
    fun stop() { session?.stop() }
    fun action(name: String): String = session?.action(name) ?: "Cozmo is not connected"
    fun setWheels(on: Boolean) { if (!on) session?.stop() else session?.setWheels(true) }
    fun setCamera(on: Boolean) { session?.setCamera(on); if (!on) image.value = null }
    fun setCompanion(on: Boolean) { session?.setCompanion(on) }
    fun connectCube(id: Int): String = session?.connectCube(id) ?: "Cozmo is not connected"
    fun expression(name: String) { if (state.value.connected && state.value.companionEyes) session?.expression(name) }

    /** Android AgentRouter's Cozmo routing: "Cozmo look up", "Cozmo lights blue", "Cozmo stop". */
    fun parse(text: String): String? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        if (!Regex("^(?:hey )?cozmo\\b").containsMatchIn(t)) return null
        val rest = t.replace(Regex("^(?:hey )?cozmo[,:]?\\s*"), "").replace(Regex("^(?:please |can you |could you )"), "")
        val light = Regex("^(?:lights?|backpack(?: lights?)?)\\s+(\\w+)").find(rest)?.groupValues?.get(1)
        return when {
            light != null -> if (light in CozmoProtocol.COLORS) "light_$light" else null
            rest.isEmpty() || rest == "status" || rest.startsWith("how are") -> "status"
            rest.startsWith("stop") -> "stop"
            rest.startsWith("look up") -> "look_up"; rest.startsWith("look down") -> "look_down"
            rest.startsWith("look") && ("center" in rest || "ahead" in rest || "straight" in rest) -> "look_center"
            rest.startsWith("lift up") || rest.startsWith("raise") -> "lift_up"
            rest.startsWith("lift down") || rest.startsWith("lower") -> "lift_down"
            rest.startsWith("half lift") || rest.startsWith("lift half") -> "lift_middle"
            rest.startsWith("go forward") || rest == "forward" -> "forward"
            rest.startsWith("go back") || rest.startsWith("back up") || rest == "back" -> "back"
            rest.startsWith("turn left") -> "left"; rest.startsWith("turn right") -> "right"
            rest.startsWith("headlight on") || rest.startsWith("light on") -> "headlight_on"
            rest.startsWith("headlight off") || rest.startsWith("light off") -> "headlight_off"
            else -> (CozmoReactions.names + CozmoProtocol.EXPRESSIONS).firstOrNull { rest.startsWith(it) || rest == "be $it" || rest == "look $it" }
        }
    }
}
