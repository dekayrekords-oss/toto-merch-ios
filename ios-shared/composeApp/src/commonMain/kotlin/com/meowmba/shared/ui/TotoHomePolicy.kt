package com.meowmba.shared.ui

/** Routing policy used by the actual mounted-home navigation, not only its menu. */
internal object TotoHomePolicy {
    val menu = listOf("Toto Controls", "Merch table", "Chat", "Remote models", "LLM", "Voice & behavior", "Memory", "Avatar", "Background", "Privacy", "Settings")

    fun destination(target: String): String? = when (target.trim().lowercase()) {
        "flow", "desk", "robot", "cozmo", "auto" -> null
        "appearance" -> "Avatar"
        else -> target
    }

    fun page(page: Page): Page = when (page) {
        Page.APPEARANCE -> Page.AVATAR
        Page.COZMO -> Page.SETTINGS
        else -> page
    }
}
