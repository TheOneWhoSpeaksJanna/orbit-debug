package com.orbitai.core.commands

/**
 * Slash-command registry for the chat input.
 *
 * Mirrors how OpenClaude/Claude expose "/" commands, but rendered as a
 * filterable palette in the Compose input bar (type "/" -> popup; "/he" ->
 * filters to "/help"). Selecting a command either (a) inserts it into the
 * input, or (b) executes it immediately via [SlashCommandHandler].
 *
 * Keep this list small and genuinely useful — these are the "original
 * commands" the user asked to surface (alongside skills/MCP in the + menu).
 */
data class SlashCommand(
    val name: String,            // without the leading slash, e.g. "help"
    val description: String,
    val usage: String = "/$name",
    val category: String = "Commands",
    /** If true, selecting the command runs it immediately; otherwise it is
     * inserted into the input box for the user to edit/send. */
    val immediate: Boolean = true
)

/**
 * Context the command handlers need. Supplied by ChatViewModel so commands
 * can post messages, trigger the update flow, clear the session, etc.
 */
interface SlashCommandHandler {
    fun postSystemMessage(text: String)
    fun triggerUpdate()
    fun clearSession()
    fun openSkills()
}

object ChatSlashCommands {

    val ALL: List<SlashCommand> = listOf(
        SlashCommand(
            name = "help",
            description = "List every available slash command",
            immediate = true
        ),
        SlashCommand(
            name = "update",
            description = "Check for an app update and install if available",
            immediate = true
        ),
        SlashCommand(
            name = "btw",
            description = "Attach a note to the agent (e.g. \"/btw use the project's style guide\")",
            immediate = false
        ),
        SlashCommand(
            name = "skills",
            description = "Open the Skills & Capabilities screen",
            immediate = true
        ),
        SlashCommand(
            name = "clear",
            description = "Clear the current chat session",
            immediate = true
        ),
        SlashCommand(
            name = "model",
            description = "Show the currently selected model",
            immediate = true
        )
    )

    fun filter(prefix: String): List<SlashCommand> {
        val p = prefix.lowercase().removePrefix("/").trim()
        if (p.isEmpty()) return ALL
        return ALL.filter { it.name.startsWith(p) || it.description.lowercase().contains(p) }
    }

    /** Execute an immediate command. Returns true if handled. */
    fun execute(cmd: SlashCommand, arg: String, handler: SlashCommandHandler): Boolean {
        return when (cmd.name) {
            "help" -> {
                val lines = ALL.joinToString("\n") { "/${it.name} — ${it.description}" }
                handler.postSystemMessage("Available commands:\n$lines")
                true
            }
            "update" -> {
                handler.postSystemMessage("Checking for updates…")
                handler.triggerUpdate()
                true
            }
            "skills" -> {
                handler.openSkills()
                true
            }
            "clear" -> {
                handler.clearSession()
                true
            }
            "model" -> {
                // The actual model is filled in by the view model wrapper.
                handler.postSystemMessage("Use the model chip in the top bar to change models.")
                true
            }
            else -> false
        }
    }
}
