package com.corepack.pet

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Connection checks for the COREPACK gateway. Chat itself goes through the agent (see PetAgent). */
object EngineClient {
    private fun clean(base: String) = base.trim().trimEnd('/')

    /** Base is like http://127.0.0.1:8787/v1 ; the health check lives at the server root. */
    private fun root(base: String) = clean(base).removeSuffix("/v1")

    private fun hosted(base: String) = clean(base).startsWith("https://")

    fun health(base: String): Boolean {
        return try {
            val c = URL(root(base) + "/health").openConnection() as HttpURLConnection
            // A hosted server on a free plan can take several seconds to wake up.
            val wait = if (hosted(base)) 10_000 else 2000
            c.connectTimeout = wait
            c.readTimeout = wait
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Blocking. Call from a background thread. Returns a message a person can act on. */
    fun probe(base: String, token: String): String {
        if (!health(base)) {
            return if (hosted(base)) {
                "Can't reach the server. Check your internet connection and try again in a minute."
            } else {
                "Can't reach the gateway. On the PC run: corepack gateway start (phone plugged in)."
            }
        }
        return try {
            val c = URL(clean(base) + "/models").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.setRequestProperty("Authorization", "Bearer $token")
            when (c.responseCode) {
                200 -> {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val id = j.optJSONArray("data")?.optJSONObject(0)?.optString("id") ?: "unknown"
                    "Connected. Model: $id"
                }
                401 -> if (hosted(base)) {
                    "The server didn't accept that invite code. Check it and try again."
                } else {
                    "Gateway found, but it rejected the token. Show it with: corepack gateway token"
                }
                else -> "Gateway answered HTTP ${c.responseCode}"
            }
        } catch (e: Exception) {
            "Gateway found, but the check failed: ${e.message}"
        }
    }
}
