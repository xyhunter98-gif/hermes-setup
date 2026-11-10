package com.hermes.assistant

/**
 * Turns a raw speech transcript into a structured Command.
 * Examples:
 *   "Hermes call Dad"
 *   "Hermes message Mom I'm on my way"
 *   "Hermes text Dad running late"
 *   "Hermes open Spotify"
 *   "Hermes set a timer for 5 minutes"
 *   "Hermes what's the time"
 *   "Hermes battery"
 *   "Hermes search nearest pizza place"
 *   "Hermes flashlight on" / "Hermes flashlight off"
 */
sealed class Command {
    data class Call(val contactName: String) : Command()
    data class Message(val contactName: String, val body: String) : Command()
    data class OpenApp(val appName: String) : Command()
    data class Timer(val seconds: Int, val label: String) : Command()
    data class Search(val query: String) : Command()
    data class Flashlight(val on: Boolean) : Command()
    object Time : Command()
    object Battery : Command()
    data class Unknown(val raw: String) : Command()
}

object CommandParser {

    private val wakeWords = listOf("hermes", "hermis", "hermes,", "hey hermes")

    /**
     * @param requireWakeWord when true (background/continuous listening), input that
     *   doesn't start with a wake word is ignored entirely — returns null, not Unknown,
     *   so the caller knows this wasn't meant for Hermes at all. Tap-to-talk mode passes
     *   false since the user already explicitly triggered listening.
     */
    fun parse(rawInput: String, requireWakeWord: Boolean = false): Command? {
        var text = rawInput.trim().lowercase()

        val matchedWake = wakeWords.firstOrNull { text.startsWith(it) }
        if (matchedWake != null) {
            text = text.removePrefix(matchedWake).trim().removePrefix(",").trim()
        } else if (requireWakeWord) {
            return null
        }

        return when {
            text.startsWith("call ") -> Command.Call(text.removePrefix("call ").trim())
            text.startsWith("phone ") -> Command.Call(text.removePrefix("phone ").trim())

            text.startsWith("message ") || text.startsWith("text ") -> {
                val afterVerb = if (text.startsWith("message ")) text.removePrefix("message ")
                                 else text.removePrefix("text ")
                val parts = afterVerb.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) Command.Message(parts[0], parts[1]) else Command.Unknown(rawInput)
            }

            text.startsWith("open ") -> Command.OpenApp(text.removePrefix("open ").trim())
            text.startsWith("launch ") -> Command.OpenApp(text.removePrefix("launch ").trim())

            text.startsWith("set a timer") || text.startsWith("timer") || text.startsWith("set timer") ->
                parseTimer(text)

            text.startsWith("search ") -> Command.Search(text.removePrefix("search ").trim())
            text.startsWith("google ") -> Command.Search(text.removePrefix("google ").trim())
            text.startsWith("look up ") -> Command.Search(text.removePrefix("look up ").trim())

            text.contains("flashlight") || text.contains("torch") -> {
                val on = !text.contains("off")
                Command.Flashlight(on)
            }

            text.contains("what's the time") || text.contains("what time") || text == "time" ->
                Command.Time

            text.contains("battery") -> Command.Battery

            else -> Command.Unknown(rawInput)
        }
    }

    private fun parseTimer(text: String): Command {
        val numberMatch = Regex("(\\d+)").find(text)
        val amount = numberMatch?.value?.toIntOrNull() ?: 5
        val unitIsSeconds = text.contains("second")
        val seconds = if (unitIsSeconds) amount else amount * 60

        // "set a timer for 5 minutes for pasta" -> label = "pasta"
        val label = Regex("for .*?(?:minutes?|seconds?)\\s*(?:for\\s+)?(.*)")
            .find(text)?.groupValues?.get(1)?.trim().orEmpty()

        return Command.Timer(seconds, label.ifBlank { "Hermes timer" })
    }
}
