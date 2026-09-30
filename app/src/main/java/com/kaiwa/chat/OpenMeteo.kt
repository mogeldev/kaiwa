package com.kaiwa.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The Open-Meteo tools: the weather, and the air.
 *
 * They share a provider and a geocoder and nothing else, so they are two tools rather
 * than one: a question about the weather should not pay for an air-quality request, and
 * "is the air bad today?" is a question in its own right.
 *
 * Open-Meteo is what makes this need no configuration of its own: no account, no key, no
 * sign-up, and plain GETs returning JSON. It is free for non-commercial use. A keyed
 * weather service would have meant another secret in every config file for no more
 * information.
 *
 * Everything here blocks. Callers are already on a worker thread.
 */
object Weather : Tool {

    override val name = "weather"

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    /**
     * The tool as the model sees it.
     *
     * A place name rather than coordinates, because a place name is what a person types
     * and [geocode] turns it into the numbers the forecast wants. Asking the model for
     * latitude and longitude would be asking it to guess.
     */
    override fun declaration(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put(
                    "description",
                    "Current conditions and a three-day outlook for a place, including " +
                        "sunrise and sunset. Call this whenever the reply depends on the " +
                        "weather there."
                )
                .put("parameters", parameters())
        )

    override fun run(arguments: JSONObject): String {
        val location = arguments.optString("location").trim()
        if (location.isEmpty()) return "No location was given."

        val place = try {
            geocode(location)
        } catch (e: Exception) {
            return lookupFailed(e)
        } ?: return "No place called \"$location\" was found."

        return try {
            forecast(place)
        } catch (e: Exception) {
            lookupFailed(e)
        }
    }

    private fun forecast(place: Place): String {
        val url = "$FORECAST?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
            "sunrise,sunset,uv_index_max" +
            "&forecast_days=3&timezone=${urlEncode(place.timezone)}"

        val json = JSONObject(httpGet(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val days = daily.getJSONArray("time")

        val out = StringBuilder()
        out.append("Weather for ").append(place.label).append('\n')
        out.append("Now: ").append(oneDecimal(current.getDouble("temperature_2m")))
            .append("°C (feels like ").append(oneDecimal(current.getDouble("apparent_temperature")))
            .append("°C), humidity ").append(current.getInt("relative_humidity_2m"))
            .append("%, wind ").append(oneDecimal(current.getDouble("wind_speed_10m")))
            .append(" km/h, ").append(describe(current.getInt("weather_code")))

        for (i in 0 until days.length()) {
            out.append('\n')
            out.append(if (i == 0) "Today" else days.getString(i)).append(": ")
                .append(oneDecimal(daily.getJSONArray("temperature_2m_min").getDouble(i)))
                .append(" to ")
                .append(oneDecimal(daily.getJSONArray("temperature_2m_max").getDouble(i)))
                .append("°C, ").append(describe(daily.getJSONArray("weather_code").getInt(i)))

            // Today only: three days of sunrise is three lines nobody asked for, and the
            // model is composing a sentence, not a table.
            if (i == 0) {
                val rise = daily.optJSONArray("sunrise")?.optString(0).orEmpty().substringAfter('T')
                val set = daily.optJSONArray("sunset")?.optString(0).orEmpty().substringAfter('T')
                if (rise.isNotEmpty() && set.isNotEmpty()) {
                    out.append(", sun ").append(rise).append('-').append(set)
                }
            }

            val chance = daily.optJSONArray("precipitation_probability_max")
            if (chance != null && !chance.isNull(i)) {
                out.append(", ").append(chance.getInt(i)).append("% chance of precipitation")
            }

            val uv = daily.optJSONArray("uv_index_max")?.optDouble(i, Double.NaN) ?: Double.NaN
            if (!uv.isNaN()) out.append(", UV ").append(oneDecimal(uv))
        }

        return out.toString()
    }
}

/** Air quality and pollen, which is a different question from what the sky is doing. */
object AirQuality : Tool {

    override val name = "air_quality"

    private const val AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"

    override fun declaration(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put(
                    "description",
                    "Air quality and pollen counts for a place, for questions about smog, " +
                        "hay fever, or whether it is a good day to be outside."
                )
                .put("parameters", parameters())
        )

    override fun run(arguments: JSONObject): String {
        val location = arguments.optString("location").trim()
        if (location.isEmpty()) return "No location was given."

        val place = try {
            geocode(location)
        } catch (e: Exception) {
            return lookupFailed(e)
        } ?: return "No place called \"$location\" was found."

        return try {
            report(place)
        } catch (e: Exception) {
            lookupFailed(e)
        }
    }

    private fun report(place: Place): String {
        val url = "$AIR?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=european_aqi,pm2_5,pm10," +
            "alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,ragweed_pollen" +
            "&timezone=${urlEncode(place.timezone)}"

        val current = JSONObject(httpGet(url)).getJSONObject("current")

        val out = StringBuilder()
        out.append("Air for ").append(place.label).append('\n')

        val aqi = current.optInt("european_aqi", -1)
        if (aqi >= 0) {
            out.append("European AQI: ").append(aqi).append(" (").append(aqiWord(aqi)).append(")")
        } else {
            out.append("European AQI: not reported here")
        }
        append(current, out, "pm2_5", ", PM2.5 ")
        append(current, out, "pm10", ", PM10 ")

        // Pollen is null outside its season and outside the model's coverage, which is
        // most of the year: report what came back rather than a row of nulls.
        val pollen = listOf(
            "alder_pollen" to "alder",
            "birch_pollen" to "birch",
            "grass_pollen" to "grass",
            "mugwort_pollen" to "mugwort",
            "ragweed_pollen" to "ragweed"
        ).mapNotNull { (key, label) ->
            val value = current.optDouble(key, Double.NaN)
            if (value.isNaN()) null else "$label ${oneDecimal(value)}"
        }
        if (pollen.isNotEmpty()) {
            out.append("\nPollen (grains/m³): ").append(pollen.joinToString(", "))
        }

        return out.toString()
    }

    /** The bands the European AQI is read in, which the model would otherwise guess at. */
    private fun aqiWord(aqi: Int): String = when {
        aqi <= 20 -> "good"
        aqi <= 40 -> "fair"
        aqi <= 60 -> "moderate"
        aqi <= 80 -> "poor"
        aqi <= 100 -> "very poor"
        else -> "extremely poor"
    }
}

/** The parameter block both Open-Meteo tools declare, since both take a place name. */
private fun parameters(): JSONObject = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject().put(
            "location",
            JSONObject()
                .put("type", "string")
                .put(
                    "description",
                    "City or place name, for example \"München\" or \"Kyoto, Japan\"."
                )
        )
    )
    .put("required", JSONArray().put("location"))

private fun append(current: JSONObject, out: StringBuilder, key: String, prefix: String) {
    val value = current.optDouble(key, Double.NaN)
    if (!value.isNaN()) out.append(prefix).append(oneDecimal(value)).append("µg/m³")
}

/** A place name resolved to the coordinates and timezone the forecasts need. */
private data class Place(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String
)

/**
 * Name to coordinates, which is the step that decides whether the rest is any use.
 *
 * Worth knowing: this matches names, not intentions. "Muenchen" finds a village in
 * Austria while "München" and "Munich" both find the city. The app passes along whatever
 * the model pulled out of the question, so the wording is the model's problem - and
 * wrong-place answers are why the place is named in the result it reads back.
 */
private fun geocode(location: String): Place? {
    val url = "https://geocoding-api.open-meteo.com/v1/search" +
        "?name=${urlEncode(location)}&count=1&language=en&format=json"
    val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return null
    if (results.length() == 0) return null

    val first = results.getJSONObject(0)
    val label = listOf("name", "admin1", "country")
        .map { first.optString(it).trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(", ")

    return Place(
        label = label,
        latitude = first.getDouble("latitude"),
        longitude = first.getDouble("longitude"),
        timezone = first.optString("timezone")
    )
}

/** Both tools here fail the same way, in words the model can repeat. */
private fun lookupFailed(e: Exception): String = lookupFailed(e, "the service")

/**
 * One decimal place, forced to a full stop rather than the device's locale. A comma
 * would be right for a German reader and wrong for the JSON-minded model reading it, and
 * the model is the only reader these strings ever have.
 */
private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

/** WMO codes, which is what Open-Meteo reports the sky as. */
private fun describe(code: Int): String = when (code) {
    0 -> "clear sky"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61, 63, 65 -> "rain"
    66, 67 -> "freezing rain"
    71, 73, 75 -> "snow"
    77 -> "snow grains"
    80, 81, 82 -> "rain showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunderstorm with hail"
    else -> "weather code $code"
}
