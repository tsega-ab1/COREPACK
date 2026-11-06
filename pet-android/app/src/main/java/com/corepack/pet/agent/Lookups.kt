package com.corepack.pet.agent

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** Fetches a web page as text. A fake is used in tests. */
fun interface Http {
    @Throws(IOException::class)
    fun get(url: String): String
}

class UrlHttp(private val timeoutMs: Int = 8000, private val maxBytes: Int = 300_000) : Http {
    override fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "CorePackPet/0.1 (personal assistant app)")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.use { s ->
                val buf = ByteArray(maxBytes)
                var n = 0
                while (n < maxBytes) {
                    val r = s.read(buf, n, maxBytes - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** WMO weather codes as used by Open-Meteo. */
object Wmo {
    fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81 -> "rain showers"
        82 -> "violent rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unclear weather"
    }
}

class WeatherTool(private val http: Http) : Tool {
    override val spec = ToolSpec(
        "get_weather",
        "Get the current weather and a 3-day outlook for a city. The city name is sent to Open-Meteo.",
        """{"type":"object","properties":{"city":{"type":"string","description":"City name, like Addis Ababa."}},"required":["city"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val city = stringArg(arguments, "city")
        if (city.isEmpty()) return "error: missing 'city'"
        try {
            val geo = JSONObject(http.get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1&language=en&format=json"))
            val place = geo.optJSONArray("results")?.optJSONObject(0) ?: return "No place found called '$city'."
            val lat = place.optDouble("latitude")
            val lon = place.optDouble("longitude")
            val where = listOf(place.optString("name"), place.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3",
                lat, lon,
            )
            return format(where, JSONObject(http.get(url)))
        } catch (e: IOException) {
            return "error: couldn't reach the weather service (${e.message})"
        } catch (e: org.json.JSONException) {
            return "error: the weather service sent an unexpected answer"
        }
    }

    internal fun format(where: String, fc: JSONObject): String {
        val cur = fc.getJSONObject("current")
        val sb = StringBuilder()
        sb.append("$where: now ${cur.getDouble("temperature_2m").roundToInt()}°C")
        if (cur.has("apparent_temperature")) sb.append(" (feels like ${cur.getDouble("apparent_temperature").roundToInt()}°C)")
        sb.append(", ${Wmo.describe(cur.optInt("weather_code", -1))}")
        if (cur.has("relative_humidity_2m")) sb.append(", humidity ${cur.getInt("relative_humidity_2m")}%")
        if (cur.has("wind_speed_10m")) sb.append(", wind ${cur.getDouble("wind_speed_10m").roundToInt()} km/h")
        sb.append(".")
        val d = fc.optJSONObject("daily") ?: return sb.toString()
        val days = d.optJSONArray("time") ?: return sb.toString()
        val names = listOf("Today", "Tomorrow")
        for (i in 0 until days.length()) {
            val max = d.optJSONArray("temperature_2m_max")?.optDouble(i)
            val min = d.optJSONArray("temperature_2m_min")?.optDouble(i)
            if (max == null || min == null || max.isNaN() || min.isNaN()) continue
            val label = names.getOrNull(i) ?: days.getString(i)
            sb.append("\n$label: ${Wmo.describe(d.optJSONArray("weather_code")?.optInt(i, -1) ?: -1)}, ${min.roundToInt()}-${max.roundToInt()}°C")
            val rain = d.optJSONArray("precipitation_probability_max")
            if (rain != null && !rain.isNull(i)) sb.append(", rain chance ${rain.getInt(i)}%")
            sb.append(".")
        }
        return sb.toString()
    }
}

class WikiTool(private val http: Http) : Tool {
    override val spec = ToolSpec(
        "wiki_summary",
        "Look up a short factual summary of a topic on Wikipedia. The topic is sent to Wikipedia.",
        """{"type":"object","properties":{"topic":{"type":"string","description":"What to look up."}},"required":["topic"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val topic = stringArg(arguments, "topic")
        if (topic.isEmpty()) return "error: missing 'topic'"
        try {
            val search = JSONObject(http.get("https://en.wikipedia.org/w/rest.php/v1/search/title?q=${enc(topic)}&limit=1"))
            val title = search.optJSONArray("pages")?.optJSONObject(0)?.optString("title").orEmpty()
            if (title.isEmpty()) return "Nothing found on Wikipedia for '$topic'."
            val page = JSONObject(http.get("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_')).replace("+", "%20")}"))
            val extract = page.optString("extract").trim()
            if (extract.isEmpty()) return "Wikipedia has a page '$title' but no summary."
            val desc = page.optString("description").trim()
            return "$title" + (if (desc.isNotEmpty()) " ($desc)" else "") + ": " + extract.take(700) + " (source: Wikipedia)"
        } catch (e: IOException) {
            return "error: couldn't reach Wikipedia (${e.message})"
        } catch (e: org.json.JSONException) {
            return "error: Wikipedia sent an unexpected answer"
        }
    }
}
