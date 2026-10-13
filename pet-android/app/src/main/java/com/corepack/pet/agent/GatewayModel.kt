package com.corepack.pet.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the COREPACK gateway: OpenAI-style chat completions over SSE, with tool calling.
 * Use one instance per user turn. No Android classes, so it can run in plain JVM tests.
 */
class GatewayModel(
    baseUrl: String,
    private val token: String,
    private val readTimeoutMs: Int = 180_000,
) : Model {
    private val base = baseUrl.trim().trimEnd('/')

    @Volatile
    private var conn: HttpURLConnection? = null

    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
        Thread {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // already closed
            }
        }.start()
    }

    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn {
        if (cancelled) throw ModelException("cancelled", cancelled = true)
        var connected = false
        try {
            val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = readTimeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "text/event-stream")
            c.outputStream.use { it.write(buildBody(messages, tools)) }

            val code = c.responseCode
            connected = true
            if (code != 200) throw ModelException(errorText(c, code))

            val text = StringBuilder()
            val calls = ToolAccumulator()
            var pendingExtra: String? = null
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith(": corepack ")) {
                        try {
                            hooks.onGatewayEvent(JSONObject(line.substring(11)).optString("event"))
                        } catch (e: Exception) {
                            // ignore a malformed status line
                        }
                    } else if (line.startsWith("data:")) {
                        val data = line.substring(5).trim()
                        if (data == "[DONE]") break
                        val json = try {
                            JSONObject(data)
                        } catch (e: Exception) {
                            continue
                        }
                        val err = json.optJSONObject("error")
                        if (err != null) {
                            throw ModelException(err.optString("message", "The engine returned an error"))
                        }
                        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                        if (!delta.isNull("content")) {
                            val t = delta.optString("content", "")
                            if (t.isNotEmpty()) {
                                text.append(t)
                                hooks.onText(t)
                            }
                        }
                        // Gemini may attach its thought signature to any delta, not only to the tool call.
                        delta.optJSONObject("extra_content")?.let { pendingExtra = it.toString() }
                        delta.optJSONArray("tool_calls")?.let { calls.add(it) }
                    }
                }
            }
            return ModelTurn(text.toString(), calls.build(pendingExtra))
        } catch (e: ModelException) {
            throw e
        } catch (e: IOException) {
            if (cancelled) throw ModelException("cancelled", cancelled = true)
            throw ModelException(e.message ?: "Connection problem", unreachable = !connected, cause = e)
        }
    }

    // ------------------------------------------------------------------ request

    private fun buildBody(messages: List<AgentMessage>, tools: List<ToolSpec>): ByteArray {
        val msgs = JSONArray()
        for (m in messages) msgs.put(messageJson(m))
        val body = JSONObject().put("stream", true).put("messages", msgs)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) arr.put(toolJson(t))
            body.put("tools", arr)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    private fun toolJson(t: ToolSpec): JSONObject {
        val fn = JSONObject().put("name", t.name).put("description", t.description)
        // Gemini rejects an object schema with no properties, so no-argument tools omit "parameters".
        if (t.parametersJson.isNotBlank()) fn.put("parameters", JSONObject(t.parametersJson))
        return JSONObject().put("type", "function").put("function", fn)
    }

    private fun messageJson(m: AgentMessage): JSONObject {
        val o = JSONObject().put("role", m.role)
        if (m.role == "tool") {
            o.put("tool_call_id", m.toolCallId ?: "")
            o.put("content", m.content ?: "")
            return o
        }
        if (m.toolCalls.isEmpty()) {
            o.put("content", m.content ?: "")
            return o
        }
        if (!m.content.isNullOrEmpty()) o.put("content", m.content)
        val arr = JSONArray()
        for (c in m.toolCalls) {
            val fn = JSONObject().put("name", c.name).put("arguments", c.argumentsJson)
            val tc = JSONObject().put("id", c.id).put("type", "function").put("function", fn)
            c.extraContentJson?.let { tc.put("extra_content", JSONObject(it)) }
            arr.put(tc)
        }
        o.put("tool_calls", arr)
        return o
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

    // ------------------------------------------------------------------ streamed tool calls

    private class ToolAccumulator {
        private class Part {
            var id = ""
            var name = ""
            val args = StringBuilder()
            var extra: String? = null
        }

        private val parts = ArrayList<Part>()
        private val byIndex = HashMap<Int, Part>()

        fun add(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val tc = arr.optJSONObject(i) ?: continue
                val id = if (tc.isNull("id")) "" else tc.optString("id", "")
                val part: Part = when {
                    tc.has("index") -> {
                        val idx = tc.optInt("index")
                        val existing = byIndex[idx]
                        // Some servers reuse index 0 for several complete calls: a new id means a new call.
                        if (existing != null && id.isNotEmpty() && existing.id.isNotEmpty() && existing.id != id) {
                            Part().also { parts.add(it); byIndex[idx] = it }
                        } else {
                            existing ?: Part().also { parts.add(it); byIndex[idx] = it }
                        }
                    }
                    id.isNotEmpty() -> parts.firstOrNull { it.id == id } ?: Part().also { parts.add(it) }
                    else -> parts.lastOrNull() ?: Part().also { parts.add(it) }
                }
                if (id.isNotEmpty()) part.id = id
                val fn = tc.optJSONObject("function")
                if (fn != null) {
                    if (!fn.isNull("name")) {
                        val n = fn.optString("name", "")
                        if (n.isNotEmpty()) part.name = n
                    }
                    if (!fn.isNull("arguments")) part.args.append(fn.optString("arguments", ""))
                }
                tc.optJSONObject("extra_content")?.let { part.extra = it.toString() }
            }
        }

        fun build(pendingExtra: String?): List<ToolCall> {
            val out = ArrayList<ToolCall>()
            for (p in parts) {
                if (p.name.isEmpty()) continue
                val raw = p.args.toString().ifBlank { "{}" }
                val args = try {
                    toMap(JSONObject(raw))
                } catch (e: Exception) {
                    emptyMap()
                }
                val extra = p.extra ?: if (out.isEmpty()) pendingExtra else null
                out.add(ToolCall(p.id.ifEmpty { "call_${out.size + 1}" }, p.name, args, raw, extra))
            }
            return out
        }

        private fun toMap(o: JSONObject): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            for (k in o.keys()) m[k] = unwrap(o.get(k))
            return m
        }

        private fun unwrap(v: Any?): Any? = when {
            v == null || v == JSONObject.NULL -> null
            v is JSONObject -> toMap(v)
            v is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            else -> v
        }
    }
}
