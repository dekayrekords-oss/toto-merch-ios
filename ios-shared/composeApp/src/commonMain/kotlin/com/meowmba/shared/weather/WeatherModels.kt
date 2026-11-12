package com.meowmba.shared.weather

/**
 * What the sky is doing, in the few buckets that actually change what the companion shows
 * or how it reacts.
 *
 * Deliberately coarser than the 28 WMO codes Open-Meteo returns: the card needs one icon
 * and the cat needs one mood, so drizzle and heavy rain are the same decision.
 */
enum class WeatherCondition(val label: String, val emoji: String) {
    CLEAR("Clear", "☀️"),
    PARTLY_CLOUDY("Partly cloudy", "🌤️"),
    CLOUDY("Cloudy", "☁️"),
    FOG("Foggy", "🌫️"),
    DRIZZLE("Drizzle", "🌦️"),
    RAIN("Rain", "🌧️"),
    SNOW("Snow", "❄️"),
    THUNDERSTORM("Thunderstorm", "⛈️"),
    DUST("Dust", "🌪️"),
    UNKNOWN("Unknown", "🌡️");

    companion object {
        /**
         * Maps a WMO weather code (what Open-Meteo speaks) to a bucket.
         *
         * Codes per the WMO 4677 table Open-Meteo documents. Anything unrecognised becomes
         * [UNKNOWN] rather than silently pretending to be clear.
         */
        fun fromWmoCode(code: Int): WeatherCondition = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDERSTORM
            // WMO dust/sand: whirls (6-9) and duststorms (30-35). Open-Meteo rarely emits
            // these, but they are real here and used to land in UNKNOWN.
            6, 7, 8, 9, 30, 31, 32, 33, 34, 35 -> DUST
            else -> UNKNOWN
        }
    }
}

/**
 * A single weather reading, already in display units.
 *
 * Temperatures are whole degrees because that is how people say them out loud and how the
 * card shows them; keeping fractions would only invite inconsistent rounding between the
 * card and the spoken reply.
 */
data class WeatherNow(
    val condition: WeatherCondition,
    val temperatureF: Int,
    val feelsLikeF: Int,
    val highF: Int,
    val lowF: Int,
    val humidityPct: Int,
    val windMph: Int,
    val isDay: Boolean,
    /** Place name for the card subtitle; null when we only had coordinates. */
    val place: String? = null,
) {
    /**
     * How the cat should react. Reuses the existing emotion vocabulary rather than adding a
     * parallel weather-mood axis — one expression channel is easier to reason about, and
     * these map naturally:
     *   sun -> pleased, storm -> alert, snow/fog -> sleepy and hunkered down.
     */
    fun catEmotionName(): String = when (condition) {
        WeatherCondition.CLEAR -> if (isDay) "HAPPY" else "SLEEPY"
        WeatherCondition.PARTLY_CLOUDY -> "HAPPY"
        WeatherCondition.CLOUDY -> "IDLE"
        WeatherCondition.FOG -> "CURIOUS"
        WeatherCondition.DRIZZLE -> "CURIOUS"
        WeatherCondition.RAIN -> "SLEEPY"
        WeatherCondition.SNOW -> "SLEEPY"
        WeatherCondition.THUNDERSTORM -> "ALERT"
        WeatherCondition.DUST -> "ALERT"
        WeatherCondition.UNKNOWN -> "CONFUSED"
    }

    /** One line suitable for speaking aloud. */
    fun spoken(): String = buildString {
        append("It's ${temperatureF}° and ${condition.label.lowercase()}")
        if (kotlin.math.abs(feelsLikeF - temperatureF) >= 3) {
            append(", feels like ${feelsLikeF}°")
        }
        append(". High of ${highF}, low of ${lowF}.")
    }
}
