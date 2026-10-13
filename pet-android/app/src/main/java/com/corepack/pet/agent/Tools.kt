package com.corepack.pet.agent

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimeTool(private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val spec = ToolSpec("get_time", "Get the current local date, time and time zone.")

    override fun run(arguments: Map<String, Any?>): String =
        now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm (VV)", Locale.ENGLISH))
}

class RememberTool(private val store: FactStore) : Tool {
    override val spec = ToolSpec(
        "remember",
        "Save one short, lasting fact about the user so it is remembered in future conversations.",
        """{"type":"object","properties":{"fact":{"type":"string","description":"The fact, as one short sentence."}},"required":["fact"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val fact = arguments["fact"] as? String ?: return "error: missing 'fact'"
        return if (store.add(fact)) "saved" else "already known, or empty"
    }
}

class ForgetTool(private val store: FactStore) : Tool {
    override val spec = ToolSpec(
        "forget",
        "Delete saved facts that contain the given text.",
        """{"type":"object","properties":{"text":{"type":"string","description":"Part of the fact to delete (at least 3 characters)."}},"required":["text"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val text = arguments["text"] as? String ?: return "error: missing 'text'"
        val n = store.remove(text)
        return if (n > 0) "removed $n" else "nothing matched"
    }
}

class MoodTool(private val onMood: (PetMood) -> Unit) : Tool {
    override val spec = ToolSpec(
        "set_pet_mood",
        "Change the pet's facial expression to show how it feels.",
        """{"type":"object","properties":{"mood":{"type":"string","enum":["neutral","happy","sad","curious","sleepy","excited"]}},"required":["mood"]}""",
    )

    override val needsFollowUp = false

    override fun run(arguments: Map<String, Any?>): String {
        val mood = PetMood.parse(arguments["mood"] as? String) ?: return "error: unknown mood"
        onMood(mood)
        return "ok"
    }
}
