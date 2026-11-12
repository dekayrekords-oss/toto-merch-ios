package com.meowmba.shared.ui
import androidx.compose.runtime.collectAsState

/** App layout and brightness are independent; changing one never resets the other. */
enum class AppTheme(val key: String, val label: String) {
    COMPANION("companion", "Companion"),
    PROFESSIONAL("professional", "Professional"),
    FLOW("flow", "Meow Flow"),
    /** Meow's Desk: the illustrated office with the micro cat, a mode like Professional and Flow. */
    DESK("desk", "Meow's Desk");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: COMPANION
    }
}

enum class AppearanceMode(val key: String, val label: String) {
    SYSTEM("system", "System"), LIGHT("light", "Light"), DARK("dark", "Dark");

    fun isDark(systemDark: Boolean) = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DARK
    }
}


internal class AppearanceStore(private val storage: com.meowmba.shared.core.TextStorage = com.meowmba.shared.core.DefaultTextStorage) {
    val mode = kotlinx.coroutines.flow.MutableStateFlow(AppearanceMode.fromKey(storage.readText("appearance_mode")))
    val theme = kotlinx.coroutines.flow.MutableStateFlow(AppTheme.fromKey(storage.readText("app_theme")))
    fun setMode(value: AppearanceMode) { storage.writeTextAtomic("appearance_mode", value.key); mode.value = value }
    fun setTheme(value: AppTheme) { storage.writeTextAtomic("app_theme", value.key); theme.value = value }
}
internal val appearanceStore = AppearanceStore()
@androidx.compose.runtime.Composable
internal fun MeowTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val mode = appearanceStore.mode.collectAsState().value
    val theme = appearanceStore.theme.collectAsState().value
    val dark = mode.isDark(androidx.compose.foundation.isSystemInDarkTheme())
    val colors = if (theme == AppTheme.PROFESSIONAL) {
        if (dark) com.meowmba.shared.ui.theme.ProfessionalDark else com.meowmba.shared.ui.theme.ProfessionalLight
    } else if (dark) com.meowmba.shared.ui.theme.DarkColorScheme else com.meowmba.shared.ui.theme.LightColorScheme
    androidx.compose.material3.MaterialTheme(colorScheme = colors,
        shapes = com.meowmba.shared.ui.theme.WorkbenchShapes,
        typography = com.meowmba.shared.ui.theme.rememberAppTypography(theme == AppTheme.PROFESSIONAL),
        content = content)
}
