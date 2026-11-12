package com.meowmba.shared.weather

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.TextStorage
import com.meowmba.shared.platform.PlatformDrivers
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Weather from Open-Meteo, ported from Android `core/weather/WeatherRepository` (build 171).
 * Chosen there because it needs no API key and no account. Differences: HTTP through Ktor,
 * JSON through kotlinx.serialization, the device fix through [PlatformDrivers.location], and
 * named places through Open-Meteo's own geocoder instead of `android.location.Geocoder`.
 */
class WeatherRepository(
    private val storage: TextStorage = DefaultTextStorage,
    private val client: HttpClient = HttpClient(),
) {
    private var locationRevision = 0L
    val selectedPlace: String get() = storage.readText("weather_place").orEmpty()
    fun setSelectedPlace(place: String) {
        locationRevision++
        storage.writeTextAtomic("weather_place", place.trim().take(160))
        clear()
    }

    private val _latest = MutableStateFlow<WeatherNow?>(null)
    val latest: StateFlow<WeatherNow?> = _latest.asStateFlow()

    /** Cleared when the card is dismissed. */
    fun clear() { _latest.value = null }

    sealed interface Result {
        data class Ok(val weather: WeatherNow) : Result
        data class Failed(val reason: String) : Result
    }

    /** A named city needs no device-location grant. */
    suspend fun fetchPlace(place: String, surface: Boolean = true): Result {
        val revision = locationRevision
        if (place.trim().length !in 2..160) return Result.Failed("Please give a city and state.")
        return try {
            val response = client.get("https://geocoding-api.open-meteo.com/v1/search") {
                parameter("name", place.substringBefore(',').trim())
                parameter("count", 5)
                parameter("language", "en")
            }
            if (!response.status.isSuccess()) return Result.Failed("I couldn't look up that city.")
            val results = json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
            // "Portland, Oregon" → prefer the match whose region/country mentions the qualifier.
            val qualifier = place.substringAfter(',', "").trim().lowercase()
            val match = results.firstOrNull { r ->
                qualifier.isNotBlank() && listOf("admin1", "country", "country_code").any {
                    r[it]?.jsonPrimitive?.content?.lowercase()?.startsWith(qualifier) == true
                }
            } ?: results.singleOrNull() ?: results.firstOrNull().takeIf { qualifier.isBlank() }
                ?: return Result.Failed("I couldn't identify one location for $place.")
            val lat = match["latitude"]?.jsonPrimitive?.doubleOrNull
            val lon = match["longitude"]?.jsonPrimitive?.doubleOrNull
            if (lat == null || lon == null) return Result.Failed("That place has no coordinates.")
            when (val result = fetch(lat, lon, surface = false)) {
                is Result.Ok -> {
                    if (revision != locationRevision) return Result.Failed("Weather location changed; refresh again.")
                    val named = result.weather.copy(place = place.trim())
                    if (surface) _latest.value = named
                    Result.Ok(named)
                }
                is Result.Failed -> result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.Failed("I couldn't look up that city.")
        }
    }

    /**
     * Current conditions for the chosen place, the device's location, or [lat]/[lon]. Never throws;
     * failures come back as something worth saying out loud. [surface] publishes to [latest].
     */
    suspend fun fetch(lat: Double? = null, lon: Double? = null, surface: Boolean = true): Result {
        val revision = locationRevision
        if (lat == null && lon == null && selectedPlace.isNotBlank()) return fetchPlace(selectedPlace, surface)
        val fix = if (lat != null && lon != null) lat to lon else deviceFix()
            ?: return Result.Failed(locationProblem ?: "I need location access to check the weather where you are.")
        return try {
            val response = client.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", fix.first)
                parameter("longitude", fix.second)
                parameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day")
                parameter("daily", "temperature_2m_max,temperature_2m_min")
                parameter("temperature_unit", "fahrenheit")
                parameter("wind_speed_unit", "mph")
                // timezone=auto: the daily high/low must be for the user's day, not UTC's.
                parameter("timezone", "auto")
                parameter("forecast_days", 1)
            }
            if (!response.status.isSuccess()) return Result.Failed("The weather service returned ${response.status.value}.")
            val parsed = parse(response.bodyAsText()) ?: return Result.Failed("I couldn't read the weather response.")
            if (revision != locationRevision) return Result.Failed("Weather location changed; refresh again.")
            if (surface) _latest.value = parsed
            Result.Ok(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.Failed("I couldn't reach the weather service.")
        }
    }

    private var locationProblem: String? = null

    private suspend fun deviceFix(): Pair<Double, Double>? {
        val driver = PlatformDrivers.location ?: return null.also { locationProblem = "Location isn't available on this device." }
        return suspendCancellableCoroutine { cont ->
            driver.requestFix(
                onFix = { la, lo -> locationProblem = null; if (cont.isActive) cont.resume(la to lo) },
                onError = { problem -> locationProblem = problem; if (cont.isActive) cont.resume(null) },
            )
        }
    }

    internal fun parse(body: String): WeatherNow? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val cur = root["current"]!!.jsonObject
        val daily = root["daily"]?.jsonObject
        fun JsonObject.num(name: String) = this[name]?.jsonPrimitive?.doubleOrNull
        fun firstOf(name: String) = daily?.get(name)?.jsonArray?.firstOrNull()?.jsonPrimitive?.doubleOrNull
        val temp = cur.num("temperature_2m") ?: return null
        WeatherNow(
            condition = WeatherCondition.fromWmoCode(cur["weather_code"]?.jsonPrimitive?.intOrNull ?: -1),
            temperatureF = temp.roundToInt(),
            feelsLikeF = (cur.num("apparent_temperature") ?: temp).roundToInt(),
            // Fall back to the current reading rather than showing a nonsense 0° high.
            highF = (firstOf("temperature_2m_max") ?: temp).roundToInt(),
            lowF = (firstOf("temperature_2m_min") ?: temp).roundToInt(),
            humidityPct = cur["relative_humidity_2m"]?.jsonPrimitive?.intOrNull ?: 0,
            windMph = (cur.num("wind_speed_10m") ?: 0.0).roundToInt(),
            isDay = (cur["is_day"]?.jsonPrimitive?.intOrNull ?: 1) == 1,
            place = root["timezone"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?.substringAfterLast('/')?.replace('_', ' '),
        )
    }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
