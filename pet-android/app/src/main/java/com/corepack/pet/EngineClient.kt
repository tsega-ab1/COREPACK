package com.corepack.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the COREPACK gateway (OpenAI-compatible chat completions over SSE). No libraries needed. */
object EngineClient {
    const val PERSONA =
        "You are a small, friendly companion that lives on the user's phone. " +
            "Keep replies short: one to three sentences unless the user asks for more."

    class Handle {
        @Volatile
        var cancelled = false

        @Volatile
        var conn: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            Thread {
                try {
                    conn?.disconnect()
                } catch (e: Exception) {
                }
            }.start()
        }
    }

    interface Listener {
        fun onEvent(name: String)
        fun onToken(text: String)
        fun onDone()
        fun onError(message: String, unreachable: Boolean)
    }

    private fun clean(base: String) = base.trim().trimEnd('/')

    /** Base is like http://127.0.0.1:8787/v1 ; the health check lives at the server root. */
    private fun root(base: String) = clean(base).removeSuffix("/v1")

    fun health(base: String): Boolean {
        return try {
            val c = URL(root(base) + "/health").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 2000
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
            return "Can't reach the gateway. On the PC run: corepack gateway start (phone plugged in)."
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
                401 -> "Gateway found, but it rejected the token. Show it with: corepack gateway token"
                else -> "Gateway answered HTTP ${c.responseCode}"
            }
        } catch (e: Exception) {
            "Gateway found, but the check failed: ${e.message}"
        }
    }

    private fun errorText(c: HttpURLConnection, code: Int): String {
        val raw = try {
            c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }
        val msg = try {
            JSONObject(raw).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            ""
        }
        return when {
            code == 401 -> "The gateway rejected the token. Check it in the app's main screen."
            msg.isNotBlank() -> msg
            else -> "The gateway answered HTTP $code"
        }
    }

    private fun handleEvent(json: String, l: Listener) {
        try {
            l.onEvent(JSONObject(json).optString("event"))
        } catch (e: Exception) {
        }
    }

    /** Returns false if an error was reported and reading should stop. */
    private fun handleData(d: String, l: Listener): Boolean {
        val j = try {
            JSONObject(d)
        } catch (e: Exception) {
            return true
        }
        val err = j.optJSONObject("error")
        if (err != null) {
            l.onError(err.optString("message", "The engine returned an error"), false)
            return false
        }
        val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return true
        if (delta.isNull("content")) return true
        val t = delta.optString("content", "")
        if (t.isNotEmpty()) l.onToken(t)
        return true
    }

    fun stream(context: Context, history: List<Pair<String, String>>, l: Listener): Handle {
        val base = clean(Prefs.baseUrl(context))
        val token = Prefs.token(context)
        val h = Handle()
        Thread {
            var connected = false
            try {
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", PERSONA))
                for ((role, text) in history) {
                    msgs.put(JSONObject().put("role", role).put("content", text))
                }
                val body = JSONObject().put("stream", true).put("messages", msgs)
                    .toString().toByteArray(Charsets.UTF_8)

                val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
                h.conn = c
                c.requestMethod = "POST"
                c.connectTimeout = 5000
                c.readTimeout = 180000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "text/event-stream")
                c.outputStream.use { it.write(body) }

                val code = c.responseCode
                connected = true
                if (code != 200) {
                    l.onError(errorText(c, code), false)
                    return@Thread
                }
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.startsWith(": corepack ")) {
                            handleEvent(line.substring(11), l)
                        } else if (line.startsWith("data:")) {
                            val d = line.substring(5).trim()
                            if (d == "[DONE]") break
                            if (!handleData(d, l)) return@Thread
                        }
                    }
                }
                l.onDone()
            } catch (e: IOException) {
                if (h.cancelled) l.onDone() else l.onError(e.message ?: "Connection problem", !connected)
            } catch (e: Exception) {
                l.onError(e.message ?: "Something went wrong", false)
            }
        }.start()
        return h
    }
}
