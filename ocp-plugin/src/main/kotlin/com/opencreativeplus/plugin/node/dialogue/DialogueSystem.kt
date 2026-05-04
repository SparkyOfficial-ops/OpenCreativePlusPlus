package com.opencreativeplus.plugin.node.dialogue

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a single dialogue option with a display label and a list of actions to execute when chosen.
 * s: 13.1, 13.6
 */
data class DialogueOption(val label: String, val body: List<IAction>)

/**
 * Manages pending dialogue interactions, tracking which player owns each dialogue
 * and providing cleanup on player quit.
 * s: 13.2, 13.3, 13.5
 */
object DialogueManager {
    // dialogueId -> deferred result (option index)
    private val pending = ConcurrentHashMap<UUID, CompletableDeferred<Int>>()

    // dialogueId -> playerId (for cleanup on quit)
    private val dialogueOwner = ConcurrentHashMap<UUID, UUID>()

    // playerId -> set of dialogueIds (for cleanup on quit)
    private val playerDialogues = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /**
     * Suspends until the player clicks an option for the given dialogue, or until cancelled.
     * s: 13.3
     */
    suspend fun awaitClick(dialogueId: UUID, playerId: UUID): Int {
        val deferred = CompletableDeferred<Int>()
        pending[dialogueId] = deferred
        playerDialogues.getOrPut(playerId) { ConcurrentHashMap.newKeySet() }.add(dialogueId)
        dialogueOwner[dialogueId] = playerId
        return try {
            deferred.await()
        } finally {
            pending.remove(dialogueId)
            dialogueOwner.remove(dialogueId)
            playerDialogues[playerId]?.remove(dialogueId)
        }
    }

    /**
     * Called when a player clicks a dialogue option (via /ocp_dialogue command).
     * Completes the deferred with the chosen option index.
     * s: 13.3
     */
    fun onOptionClick(dialogueId: UUID, optionIndex: Int) {
        pending[dialogueId]?.complete(optionIndex)
    }

    /**
     * Cancels all pending dialogues for a player who has quit.
     * s: 13.5
     */
    fun onPlayerQuit(playerId: UUID) {
        val dialogueIds = playerDialogues.remove(playerId) ?: return
        for (dialogueId in dialogueIds) {
            pending.remove(dialogueId)?.cancel()
            dialogueOwner.remove(dialogueId)
        }
    }
}

/**
 * Sends a formatted dialogue message to a player with up to 4 clickable response options.
 * Uses Adventure Component API for clickable chat text.
 * Waits up to 60 seconds for a response; executes timeoutBody if no option is clicked.
 * s: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6
 */
class SendDialogueNode(rawParams: Map<String, Any>) : IAction {
    override val nodeId = "send_dialogue"
    override val displayName = "Send Dialogue"

    private val text: String = rawParams["text"] as? String ?: ""

    @Suppress("UNCHECKED_CAST")
    private val options: List<DialogueOption> = rawParams["options"] as? List<DialogueOption> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private val timeoutBody: List<IAction> = rawParams["timeout_body"] as? List<IAction> ?: emptyList()

    private val playerParam: Player? = rawParams["player"] as? Player

    override suspend fun execute(context: ExecutionContext) {
        val player: Player = playerParam ?: context.player ?: return

        val dialogueId = UUID.randomUUID()
        val component: Component = buildComponent(text, options, dialogueId)
        // sendMessage must run on main thread (Adventure/Bukkit API)
        context.syncContext {
            (player as net.kyori.adventure.audience.Audience).sendMessage(component)
        }

        val result = withTimeoutOrNull(60_000L) {
            DialogueManager.awaitClick(dialogueId, player.uniqueId)
        }

        if (result != null) {
            options.getOrNull(result)?.body?.forEach { it.execute(context) }
        } else {
            timeoutBody.forEach { it.execute(context) }
        }
    }

    private fun buildComponent(text: String, options: List<DialogueOption>, id: UUID): Component {
        var comp: Component = Component.text(text).appendNewline()
        options.forEachIndexed { i, opt ->
            comp = comp.append(
                Component.text("[${opt.label}]")
                    .clickEvent(ClickEvent.runCommand("/ocp_dialogue $id $i"))
                    .color(NamedTextColor.YELLOW)
            ).appendSpace()
        }
        return comp
    }
}
