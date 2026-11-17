package com.corepack.pet.agent

import org.json.JSONException
import org.json.JSONObject

/** The words of the question the app asks before a tool that is not read-only runs on the PC. */
object ToolConfirmText {
    private const val MAX_VALUE = 120
    private const val MAX_LINES = 8

    fun title(tool: String): String = "Let the pet run \"$tool\"?"

    /**
     * [argumentsJson] is the arguments as JSON text (it may have been cut short, so it may not parse).
     * Shows each argument on its own line, so you can see what the tool is about to get.
     */
    fun body(server: String, tool: String, argumentsJson: String): String {
        val head = "\"$tool\" (from \"$server\") is about to run.\n\n"
        val args = try {
            JSONObject(argumentsJson)
        } catch (e: JSONException) {
            val raw = argumentsJson.trim()
            return head + if (raw.isEmpty()) "It needs no arguments." else "It will get: " + raw.take(200) + if (raw.length > 200) "..." else ""
        }
        // Alphabetical, because JSONObject does not promise to keep the order the arguments came in.
        val keys = args.keys().asSequence().toList().sorted()
        if (keys.isEmpty()) return head + "It needs no arguments."
        val sb = StringBuilder(head).append("It will get:")
        for (k in keys.take(MAX_LINES)) {
            val v = (args.opt(k)?.toString() ?: "null").replace('\n', ' ')
            sb.append("\n  ").append(k).append(": ").append(v.take(MAX_VALUE)).append(if (v.length > MAX_VALUE) "..." else "")
        }
        if (keys.size > MAX_LINES) sb.append("\n  and ").append(keys.size - MAX_LINES).append(" more")
        return sb.toString()
    }
}
