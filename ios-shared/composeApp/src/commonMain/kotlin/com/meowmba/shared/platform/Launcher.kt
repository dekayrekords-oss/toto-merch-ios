package com.meowmba.shared.platform

import com.meowmba.shared.core.DefaultTextStorage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Links that open Meowmba from outside: widgets, Siri / Shortcuts ("Ask Meow"), the Action Button.
 * `meowmba://chat`, `meowmba://ask?q=…`, `meowmba://talk`, `meowmba://flow`, `meowmba://desk`, `meowmba://home`.
 */
/** A screen picture handed over by the "Ask Meow about this screen" shortcut, already read on the phone. */
data class SharedScreen(val text: String, val observedAt: Long, val error: String? = null)
object IncomingShares { val pending = MutableStateFlow<SharedScreen?>(null) ; fun screen(text: String, observedAt: Long, error: String?) { pending.value = SharedScreen(text, observedAt, error) } }

/** iOS: UIPasteboard (the system asks before sharing another app's copy). */
interface ClipboardDriver { fun readText(): String?; fun writeText(text: String) }

/** VisionKit's live QR / barcode scanner. [onResult] gets the payload, or null when cancelled. */
interface CodeScannerDriver { fun scan(onResult: (String?) -> Unit, onError: (String) -> Unit) }

object IncomingLinks {
    val pending = MutableStateFlow<String?>(null)
    /** Called by the host with the full URL; consumed once by the app. */
    fun open(url: String) { pending.value = url }
}

/** Screen-level switches the host applies (screen awake, status bar, kiosk lock). */
interface DisplayDriver {
    fun keepAwake(on: Boolean)
    fun hideStatusBar(hidden: Boolean)
    /** Asks iOS to lock the phone into Meowmba (Autonomous Single App Mode; works only on supervised iPhones). */
    fun requestGuidedAccess(on: Boolean, onResult: (locked: Boolean) -> Unit)
}

/** Launcher mode: Meowmba behaves as the phone's home, as far as iOS allows. */
object LauncherMode {
    val enabled = MutableStateFlow(DefaultTextStorage.readText("launcher_mode") == "true")
    val kiosk = MutableStateFlow(DefaultTextStorage.readText("kiosk_lock") == "true")
    var display: DisplayDriver? = null
        set(value) { field = value; apply() }

    fun setEnabled(on: Boolean) { enabled.value = on; DefaultTextStorage.writeTextAtomic("launcher_mode", on.toString()); apply() }
    fun setKiosk(on: Boolean) { kiosk.value = on; DefaultTextStorage.writeTextAtomic("kiosk_lock", on.toString()); apply() }
    private fun apply() {
        display?.keepAwake(enabled.value)
        display?.hideStatusBar(enabled.value)
    }
}

/**
 * Android WirelessAvatarMonitor/Policy (build 181). iOS can't tell wireless from wired power,
 * so this triggers on any charger; edge-triggered once per charging session, with a 5 s
 * dropout grace so realigning a MagSafe puck doesn't retrigger.
 */
object ChargerAvatar {
    val enabled = MutableStateFlow(DefaultTextStorage.readText("charger_avatar") == "true")
    /** Bumped when the avatar should take the screen. */
    val show = MutableStateFlow(0)
    private val policy = Policy()

    fun setEnabled(on: Boolean) { enabled.value = on; DefaultTextStorage.writeTextAtomic("charger_avatar", on.toString()) }

    fun onPower(charging: Boolean, now: Long) { if (policy.update(charging, enabled.value, now)) show.value++ }

    class Policy {
        private var handled = false
        private var disconnectedAt: Long? = null
        fun update(charging: Boolean, enabled: Boolean, now: Long): Boolean {
            if (!enabled) { handled = false; disconnectedAt = null; return false }
            if (!charging) { if (disconnectedAt == null) disconnectedAt = now; return false }
            if (disconnectedAt?.let { now - it >= 5_000 } == true) handled = false
            disconnectedAt = null
            if (handled) return false
            handled = true
            return true
        }
    }
}
