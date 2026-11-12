package com.meowmba.shared.platform

/*
 * Native capabilities implemented by the host app (Swift on iOS) and registered at launch, the same
 * way [com.meowmba.shared.agent.OnDeviceModel.driver] is. Shared code only sees these interfaces, so
 * JVM tests run without them and every caller must handle "not registered".
 */

/** Speech-to-text. iOS: SFSpeechRecognizer + AVAudioEngine, on-device when the locale supports it. */
interface SpeechInputDriver {
    /** Asks for microphone + speech-recognition permission; reports a readable reason on refusal. */
    fun requestPermission(onResult: (granted: Boolean, reason: String?) -> Unit)
    /** Streams partial transcripts, then one final transcript (possibly empty), then stops itself. */
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    /** Ends capture early; a final transcript for what was heard still arrives. */
    fun stop()
}

/** Text-to-speech. iOS: AVSpeechSynthesizer. */
interface SpeechOutputDriver {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
    /** Play a complete WAV produced by Toto's on-phone or Lion voice. */
    fun playWav(bytes: ByteArray, onDone: () -> Unit) { onDone() }
}

/**
 * Fully on-device Kokoro TTS (sherpa-onnx). The host registers this when the
 * int8 pack and native runtime are present. If [ready] is false, callers use
 * the system voice as an emergency fallback.
 */
interface OnDeviceTtsDriver {
    val ready: Boolean
    fun render(text: String, voiceId: String, speaker: Int, speed: Float, onResult: (ByteArray?) -> Unit)
}

/** Receives face positions in normalized view coordinates (0..1, origin top-left). */
interface FaceSink {
    fun onFace(x: Float, y: Float)
    fun onNoFace()
    fun onStatus(label: String)
}

/** Front-camera face tracking for avatar attention. iOS: AVCaptureSession + Vision. No preview, no storage. */
interface FaceTrackingDriver {
    fun requestPermission(onResult: (granted: Boolean, reason: String?) -> Unit)
    fun start(sink: FaceSink)
    fun stop()
}

/** The system share sheet (Android: ACTION_SEND chooser; iOS: UIActivityViewController). */
interface ShareDriver {
    fun shareText(text: String, subject: String)
}

/**
 * The system photo picker (iOS: PHPickerViewController). The chosen image is copied into app
 * storage and its path returned, so a background survives restarts; null = cancelled.
 */
interface PhotoPickerDriver {
    fun pickImage(onPicked: (path: String?) -> Unit)
}

/** One location fix for weather (iOS: CLLocationManager, when-in-use, city-level accuracy). */
interface LocationDriver {
    fun requestFix(onFix: (latitude: Double, longitude: Double) -> Unit, onError: (String) -> Unit)
}

/**
 * The chat/Flow "+" (Android AttachSheet): [kind] is PHOTO (camera), IMAGE (photo library) or FILE.
 * The host reads the pick on the phone and returns its name, type and any text it found.
 */
interface AttachmentDriver {
    fun pick(kind: String, onRead: (name: String, mime: String, text: String) -> Unit, onCancel: () -> Unit, onError: (String) -> Unit)
}

/** Native iOS Toto bridge driver: pinned HTTPS GET/POST + CoreMotion IMU + proximity + Vision camera face tracking. */

/**
 * LOOI Bluetooth LE GATT (the transport the base actually uses).
 * Connection, scan, notify and sensor reads are allowed. Actuator writes
 * stay off until a verified stop protocol exists.
 */
interface LooiBleDriver {
    val connected: Boolean
    fun scan(
        onDevice: (name: String, id: String) -> Unit,
        onStatus: (String) -> Unit,
        onDone: (ok: Boolean, status: String) -> Unit,
    )
    fun stopScan()
    /** [id] empty = scan and attach the first device whose name contains LOOI. */
    fun connect(
        id: String,
        onStatus: (String) -> Unit,
        onResult: (ok: Boolean, status: String) -> Unit,
    )
    fun disconnect()
    /** Merch-only write gate. Default off each launch. Stop packets are still allowed. */
    fun setWritesEnabled(enabled: Boolean) {}
    fun writeCommand(bytes: ByteArray): Boolean = false
    fun writeMotorBoost(bytes: ByteArray): Boolean = false
    fun writeMotorSlow(bytes: ByteArray): Boolean = false
    fun writeNeck(value: Int): Boolean = false
    fun writeHeadlight(on: Boolean): Boolean = false
    /** Zero ff02/fed0 plus a neutral neck. Does not require writes to be enabled. */
    fun sendStop(): Boolean = false
}

/**
 * Line-protocol LOOI body over TCP (serial-over-TCP dock).
 * Classic Bluetooth SPP is not available on iOS (no MFi accessory protocol).
 */
interface LooiTcpDriver {
    val connected: Boolean
    fun connect(host: String, port: Int, onResult: (ok: Boolean, status: String) -> Unit)
    fun disconnect()
    fun sendLine(line: String)
}

interface TotoSensorDriver {
    fun request(
        method: String,
        host: String,
        port: Int,
        path: String,
        body: ByteArray?,
        certSha256: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        onResult: (status: Int, body: ByteArray?, error: String?) -> Unit,
    )
    fun setSensorsEnabled(enabled: Boolean)
    fun setCameraEnabled(enabled: Boolean)
    fun snapshotJson(mounted: Boolean): String
    fun popLatestCameraJpeg(): ByteArray?
    /**
     * Continuous always-on listening for the mounted phone (iOS: on-device SFSpeechRecognizer).
     * Final utterances and status changes are delivered to [sink] on the main thread; pass
     * enabled=false (sink may be null) to stop and release the microphone.
     */
    fun setSpeechListening(enabled: Boolean, sink: SpeechTranscriptSink?)
}

/** Receives continuous-listening results from [TotoSensorDriver.setSpeechListening]. */
interface SpeechTranscriptSink {
    /**
     * One finished utterance. [confidence] is 0..1, or a negative value when unavailable.
     * [startedMs]/[endedMs] are wall-clock epoch millis of the first/last partial change.
     * [faceDetected] is the camera's latest fresh face detection at the time of the utterance.
     */
    fun onFinalTranscript(text: String, confidence: Double, startedMs: Long, endedMs: Long, faceDetected: Boolean)
    /** Listening state for the UI, e.g. listening=true "listening" or listening=false "paused (dictation)". */
    fun onListeningStatus(listening: Boolean, status: String)
}

object PlatformDrivers {
    var speechIn: SpeechInputDriver? = null
    var speechOut: SpeechOutputDriver? = null
    var onDeviceTts: OnDeviceTtsDriver? = null
    var face: FaceTrackingDriver? = null
    var share: ShareDriver? = null
    var photos: PhotoPickerDriver? = null
    var location: LocationDriver? = null
    var attach: AttachmentDriver? = null
    var apps: AppLauncherDriver? = null
    var calendar: CalendarDriver? = null
    var status: DeviceStatusDriver? = null
    var scanner: CodeScannerDriver? = null
    var music: MusicDriver? = null
    var clipboard: ClipboardDriver? = null
    var places: com.meowmba.shared.core.shopping.PlacesDriver? = null
    var toto: TotoSensorDriver? = null
    var looi: LooiTcpDriver? = null
    var looiBle: LooiBleDriver? = null
}
