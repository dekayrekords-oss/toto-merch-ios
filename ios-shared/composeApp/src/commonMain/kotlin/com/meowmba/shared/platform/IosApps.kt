package com.meowmba.shared.platform

/** An iPhone app Meow can open by its URL scheme. [check] is the scheme iOS is asked about. */
data class IosApp(val name: String, val url: String, val check: String? = null, val aliases: List<String> = emptyList())

/**
 * iOS never lets an app list what is installed; it can only ask about specific apps whose schemes it
 * declares (Info.plist LSApplicationQueriesSchemes, at most 50). Built-in apps are always present.
 */
object IosApps {
    val builtIn = listOf(
        IosApp("Phone", "tel://"), IosApp("Messages", "sms://", aliases = listOf("text", "imessage")),
        IosApp("Mail", "message://", aliases = listOf("email")), IosApp("Safari", "x-web-search://", aliases = listOf("browser", "web")),
        IosApp("Maps", "maps://", aliases = listOf("apple maps")), IosApp("Calendar", "calshow://"),
        IosApp("Photos", "photos-redirect://", aliases = listOf("pictures", "gallery")), IosApp("Music", "music://", aliases = listOf("apple music")),
        IosApp("Notes", "mobilenotes://"), IosApp("Reminders", "x-apple-reminderkit://"),
        IosApp("FaceTime", "facetime://"), IosApp("App Store", "itms-apps://"), IosApp("Podcasts", "podcasts://"),
        IosApp("Books", "ibooks://"), IosApp("Files", "shareddocuments://"), IosApp("Shortcuts", "shortcuts://"),
        IosApp("Settings", "app-settings:", aliases = listOf("my settings")),
    )
    val thirdParty = listOf(
        IosApp("WhatsApp", "whatsapp://", "whatsapp"), IosApp("Spotify", "spotify://", "spotify"),
        IosApp("YouTube", "youtube://", "youtube"), IosApp("Instagram", "instagram://", "instagram"),
        IosApp("Facebook", "fb://", "fb"), IosApp("Messenger", "fb-messenger://", "fb-messenger"),
        IosApp("X", "twitter://", "twitter", listOf("twitter")), IosApp("TikTok", "tiktok://", "tiktok"),
        IosApp("Snapchat", "snapchat://", "snapchat"), IosApp("Reddit", "reddit://", "reddit"),
        IosApp("Gmail", "googlegmail://", "googlegmail"), IosApp("Google Maps", "comgooglemaps://", "comgooglemaps"),
        IosApp("Chrome", "googlechrome://", "googlechrome"), IosApp("Google Drive", "googledrive://", "googledrive"),
        IosApp("Google Photos", "googlephotos://", "googlephotos"), IosApp("Slack", "slack://", "slack"),
        IosApp("Zoom", "zoomus://", "zoomus"), IosApp("Teams", "msteams://", "msteams", listOf("microsoft teams")),
        IosApp("Outlook", "ms-outlook://", "ms-outlook"), IosApp("Discord", "discord://", "discord"),
        IosApp("Telegram", "tg://", "tg"), IosApp("Signal", "sgnl://", "sgnl"),
        IosApp("Netflix", "nflx://", "nflx"), IosApp("Twitch", "twitch://", "twitch"),
        IosApp("Uber", "uber://", "uber"), IosApp("Lyft", "lyft://", "lyft"), IosApp("Waze", "waze://", "waze"),
        IosApp("Venmo", "venmo://", "venmo"), IosApp("PayPal", "paypal://", "paypal"),
        IosApp("Amazon", "com.amazon.mobile.shopping://", "com.amazon.mobile.shopping"),
        IosApp("LinkedIn", "linkedin://", "linkedin"), IosApp("Pinterest", "pinterest://", "pinterest"),
        IosApp("Notion", "notion://", "notion"), IosApp("Duolingo", "duolingo://", "duolingo"),
        IosApp("ChatGPT", "chatgpt://", "chatgpt"),
    )

    /** The apps on this phone: built-in, plus third-party apps iOS confirms are installed. */
    /** "open spo" → "open Spotify" chips for installed apps (Android OpenAppCompletion). */
    fun completions(draft: String): List<String> {
        val query = Regex("^\\s*open\\s+(.*)$", RegexOption.IGNORE_CASE).matchEntire(draft)?.groupValues?.get(1)?.trim() ?: return emptyList()
        if (query.isEmpty() || query.length > 60) return emptyList()
        val q = query.lowercase()
        return available().mapNotNull { app ->
            val names = listOf(app.name) + app.aliases
            val score = names.maxOf { n -> val l = n.lowercase(); when { l == q -> 3; l.startsWith(q) -> 2; q in l -> 1; else -> 0 } }
            if (score == 0 || app.name.equals(query, true)) null else app to score
        }.sortedWith(compareByDescending<Pair<IosApp, Int>> { it.second }.thenBy { it.first.name })
            .map { "open ${it.first.name}" }.distinct().take(4)
    }

    fun available(): List<IosApp> {
        val driver = PlatformDrivers.apps ?: return builtIn
        val present = driver.installedSchemes(thirdParty.mapNotNull { it.check }).toSet()
        return builtIn + thirdParty.filter { it.check in present }
    }

    /** "open spotify", "launch the maps app" → the app, if one matches. */
    fun match(request: String): IosApp? {
        val m = Regex("^(?:please\\s+)?(?:open|launch|start|go to|switch to)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?[.!?]*$", RegexOption.IGNORE_CASE)
            .find(request.trim()) ?: return null
        val wanted = m.groupValues[1].trim().lowercase()
        return available().firstOrNull { it.name.lowercase() == wanted || wanted in it.aliases }
    }
}

/** Asks iOS which of the given schemes have an app, and opens URLs in other apps. */
interface AppLauncherDriver {
    fun installedSchemes(schemes: List<String>): List<String>
    fun open(url: String, onResult: (opened: Boolean) -> Unit)
}
