package org.breezyweather.oacp

import android.content.Context
import android.util.Log
import breezyweather.data.location.LocationRepository
import breezyweather.data.weather.WeatherRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.oacp.android.OacpParams
import org.oacp.android.OacpReceiver
import org.oacp.android.OacpResult
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "OacpBreezy"

/**
 * Handles background OACP actions for Breezy Weather.
 *
 * Uses OACP SDK for plumbing (goAsync, result broadcast, requestId).
 * Uses Hilt EntryPoint for manual DI (can't use @AndroidEntryPoint
 * because OacpReceiver.onReceive is final).
 */
class OacpActionReceiver : OacpReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OacpEntryPoint {
        fun locationRepository(): LocationRepository
        fun weatherRepository(): WeatherRepository
    }

    override fun onAction(
        context: Context,
        action: String,
        params: OacpParams,
        requestId: String?
    ): OacpResult? {
        Log.i(TAG, "onAction: action=$action, requestId=$requestId")

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, OacpEntryPoint::class.java
        )
        val locationRepo = entryPoint.locationRepository()
        val weatherRepo = entryPoint.weatherRepository()
        val locationName = params.getString("location")

        return try {
            when {
                action.endsWith(".oacp.ACTION_CHECK_WEATHER") ->
                    runBlocking { getCurrentWeather(locationRepo, weatherRepo, locationName) }
                action.endsWith(".oacp.ACTION_CHECK_FORECAST") -> {
                    val days = params.getInt("days")
                    runBlocking { getForecast(locationRepo, weatherRepo, locationName, days?.takeIf { it > 0 }) }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling action", e)
            OacpResult.error("internal_error", e.message ?: "Unknown error")
        }
    }

    private suspend fun getCurrentWeather(
        locationRepo: LocationRepository,
        weatherRepo: WeatherRepository,
        locationName: String?,
    ): OacpResult {
        val location = findLocation(locationRepo, locationName)
            ?: return OacpResult.error(
                "not_found",
                if (locationName != null) "Location '$locationName' not found."
                else "No locations configured in Breezy Weather."
            )

        val weather = weatherRepo.getWeatherByLocationId(
            location.formattedId,
            withDaily = false,
            withHourly = false,
            withMinutely = false,
            withAlerts = false,
        )

        val current = weather?.current
            ?: return OacpResult.error(
                "not_found",
                "No weather data for ${location.city}. Refresh the app first."
            )

        val cityName = location.customName
            ?: location.cityAndDistrict.ifBlank { location.country.ifBlank { "Current location" } }
        val parts = mutableListOf<String>()
        current.weatherText?.let { parts.add(it) }
        current.temperature?.temperature?.let { parts.add("${it.inCelsius.toInt()}°C") }
        current.relativeHumidity?.let { parts.add("Humidity: ${it.inPercent.toInt()}%") }
        current.wind?.speed?.let { parts.add("Wind: ${it.inKilometersPerHour.toInt()} km/h") }

        // Show when data was last refreshed
        weather.base?.refreshTime?.let { refreshTime ->
            val ago = (System.currentTimeMillis() - refreshTime.time) / 60_000
            parts.add("(updated ${ago}min ago)")
        }

        val message = "$cityName: ${parts.joinToString(", ")}"
        Log.i(TAG, "Weather result: $message")
        return OacpResult.success(message)
    }

    private suspend fun getForecast(
        locationRepo: LocationRepository,
        weatherRepo: WeatherRepository,
        locationName: String?,
        days: Int?,
    ): OacpResult {
        val location = findLocation(locationRepo, locationName)
            ?: return OacpResult.error(
                "not_found",
                if (locationName != null) "Location '$locationName' not found."
                else "No locations configured in Breezy Weather."
            )

        val weather = weatherRepo.getWeatherByLocationId(
            location.formattedId,
            withDaily = true,
            withHourly = false,
            withMinutely = false,
            withAlerts = false,
        )

        val dailyList = weather?.dailyForecastStartingToday
        if (dailyList.isNullOrEmpty()) {
            return OacpResult.error(
                "not_found",
                "No forecast data for ${location.city}. Refresh the app first."
            )
        }

        val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val limit = (days ?: dailyList.size).coerceAtMost(dailyList.size)
        val lines = dailyList.take(limit).map { day ->
            val dateName = dateFormat.format(day.date)
            val high = day.day?.temperature?.temperature?.inCelsius?.toInt()?.let { "${it}°" } ?: "?"
            val low = day.night?.temperature?.temperature?.inCelsius?.toInt()?.let { "${it}°" } ?: "?"
            val desc = day.day?.weatherText ?: ""
            "$dateName: $high/$low $desc"
        }

        val message = "${location.city} forecast:\n${lines.joinToString("\n")}"
        Log.i(TAG, "Forecast result: $message")
        return OacpResult.success(message)
    }

    private suspend fun findLocation(
        locationRepo: LocationRepository,
        name: String?,
    ): breezyweather.domain.location.model.Location? {
        if (name == null) {
            return locationRepo.getFirstLocation(withParameters = false)
        }
        val all = locationRepo.getAllLocations(withParameters = false)
        return all.firstOrNull { it.city.contains(name, ignoreCase = true) }
            ?: locationRepo.getFirstLocation(withParameters = false)
    }
}
