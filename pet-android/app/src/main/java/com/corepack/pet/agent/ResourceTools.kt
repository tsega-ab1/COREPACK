package com.corepack.pet.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A document or piece of data a tool server on the PC offers to read. */
data class ResourceInfo(val server: String, val uri: String, val name: String, val description: String, val mimeType: String)

/** Lets the model see what the tool servers on the PC offer to read. */
class ListResourcesTool(private val resources: List<ResourceInfo>, private val max: Int = 40) : Tool {
    override val spec: ToolSpec = ToolSpec(
        "list_resources",
        "List the documents and data that tool servers on the PC offer to read. Read one with read_resource.",
        "",
    )

    override fun run(arguments: Map<String, Any?>): String {
        if (resources.isEmpty()) return "There is nothing to read."
        val lines = resources.take(max).joinToString("\n") {
            "${it.server} | ${it.uri} | ${it.name}" + if (it.description.isBlank()) "" else " - ${it.description}"
        }
        return lines + if (resources.size > max) "\n[${resources.size - max} more not shown]" else ""
    }
}

/** Reads one of them. The gateway only reads what the server itself listed. */
class ReadResourceTool(private val gateway: ToolGateway) : Tool {
    override val spec: ToolSpec = ToolSpec(
        "read_resource",
        "Read one document or piece of data found with list_resources. Give its server and uri exactly as listed.",
        JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("server", JSONObject().put("type", "string").put("description", "The server, as listed"))
                .put("uri", JSONObject().put("type", "string").put("description", "The uri, as listed")))
            .put("required", JSONArray(listOf("server", "uri")))
            .toString(),
    )

    override fun run(arguments: Map<String, Any?>): String {
        val server = arguments["server"]?.toString()?.trim().orEmpty()
        val uri = arguments["uri"]?.toString()?.trim().orEmpty()
        if (server.isEmpty() || uri.isEmpty()) return "error: give both the server and the uri, as listed"
        return try {
            val r = gateway.readResource(server, uri)
            if (r.ok) r.text else "error: ${r.text}"
        } catch (e: RemoteToolGone) {
            "error: that resource is not available"
        } catch (e: IOException) {
            "error: ${e.message ?: "could not reach the gateway"}"
        }
    }
}
