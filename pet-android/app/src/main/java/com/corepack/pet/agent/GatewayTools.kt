package com.corepack.pet.agent

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the gateway's tool endpoints with the phone's token. [baseUrl] is the same address chat uses,
 * for example http://192.168.1.20:8787/v1. Both are read on every call, so changed settings apply at once.
 */
class HttpToolGateway(
    private val baseUrl: () -> String,
    private val token: () -> String,
    private val listTimeoutMs: Int = 8_000,
    private val callTimeoutMs: Int = 70_000,
) : ToolGateway {

    override fun list(): List<RemoteToolInfo> {
        val (code, text) = send("GET", "/tools", null, listTimeoutMs)
        if (code != 200) throw IOException(describe(code, text))
        val root = parse(text)
        val tools = root.optJSONArray("tools") ?: return emptyList()
        return (0 until tools.length()).mapNotNull { parseTool(tools.optJSONObject(it)) }
    }

    override fun call(name: String, arguments: Map<String, Any?>): RemoteResult {
        val body = JSONObject().put("name", name).put("arguments", JSONObject(arguments)).toString()
        val (code, text) = send("POST", "/tools/call", body, callTimeoutMs)
        if (code == 404) throw RemoteToolGone(describe(code, text))
        if (code != 200) throw IOException(describe(code, text))
        val o = parse(text)
        return RemoteResult(o.optBoolean("ok", false), o.optString("text", ""))
    }

    private fun parseTool(o: JSONObject?): RemoteToolInfo? {
        if (o == null) return null
        val name = o.optString("name", "")
        if (name.isBlank()) return null
        val params = o.optJSONObject("parameters")
        val hasProperties = params != null && (params.optJSONObject("properties")?.length() ?: 0) > 0
        return RemoteToolInfo(
            name = name,
            server = o.optString("server", ""),
            description = o.optString("description", name),
            // Gemini rejects an object schema with no properties, so a tool without arguments carries no schema.
            parametersJson = if (hasProperties && params != null) params.toString() else "",
            needsConfirm = o.optBoolean("needs_confirm", true),
        )
    }

    private fun parse(text: String): JSONObject =
        try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("The gateway answered with something that is not JSON")
        }

    private fun describe(code: Int, text: String): String {
        val message = try {
            JSONObject(text).optJSONObject("error")?.optString("message", "")
        } catch (e: JSONException) {
            null
        }
        return if (!message.isNullOrBlank()) message else "The gateway answered HTTP $code"
    }

    private fun send(method: String, path: String, body: String?, timeoutMs: Int): Pair<Int, String> {
        val c = URL(baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer " + token())
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            return code to (stream?.use { readLimited(it) } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(s: InputStream, max: Int = 400_000): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
