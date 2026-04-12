package com.opencreativeplus.core.input

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import org.bukkit.entity.Player

/
 * Manages chat-based input sessions for players.
 *
 * When a player is in a [Chat_Input_Session], their next chat message is intercepted
 * and delivered to the waiting coroutine rather than being broadcast to the server.
 * Sessions are keyed by player UUID and are thread-safe via [ConcurrentHashMap].
 *
 * Typical usage:
 * ```kotlin
 * val value = chatInputManager.awaitChatInput(player, "Enter a value (or 'cancel'):")
 * if (value != null) { /* use value */ }
 * ```
 *
 * Requirements: 1.4, 1.5, 1.6, 1.7, 1.8, 3.1, 3.2, 3.3, 3.4, 3.5
 */
class ChatInputManager {

    private val sessions = ConcurrentHashMap<UUID, CompletableDeferred<String?>>()

    /
     * Registers a [Chat_Input_Session] for [player], sends [prompt] to them, and suspends
     * until the player replies in chat or the session is cancelled.
     *
     * - If the player types `"cancel"` (case-insensitive), returns `null`.
     * - If the player disconnects, throws [ChatInputCancelledException].
     * - Otherwise returns the player's message text.
     *
     * The session is always cleaned up in the `finally` block, even on cancellation.
     *
     * @param player The player to prompt.
     * @param prompt The message sent to the player before suspending.
     * @return The player's response, or `null` if they cancelled.
     */
    suspend fun awaitChatInput(player: Player, prompt: String): String? {
        val deferred = CompletableDeferred<String?>()
        sessions[player.uniqueId] = deferred
        player.sendMessage(prompt)
        return try {
            deferred.await()
        } finally {
            sessions.remove(player.uniqueId)
        }
    }

    /
     * Executes a sequential chain of labeled prompts, collecting each response into a map.
     *
     * Prompts are sent one at a time; the next prompt is only sent after the previous
     * response is received. If the player cancels any step (types `"cancel"` or disconnects),
     * a [ChatInputCancelledException] is thrown and no further prompts are sent.
     *
     * @param player The player to prompt.
     * @param prompts A list of `(label, promptText)` pairs. Labels become the map keys.
     * @return A map of `label -> response` for all completed prompts.
     * @throws ChatInputCancelledException if the player cancels or disconnects mid-chain.
     */
    suspend fun inputChain(player: Player, prompts: List<Pair<String, String>>): Map<String, String> {
        val results = mutableMapOf<String, String>()
        for ((label, prompt) in prompts) {
            val response = awaitChatInput(player, prompt)
                ?: throw ChatInputCancelledException(player.uniqueId)
            results[label] = response
        }
        return results
    }

    /
     * Called from the `AsyncChatEvent` listener to deliver a chat message to an active session.
     *
     * If the message equals `"cancel"` (case-insensitive), the session is completed with `null`.
     * Otherwise the session is completed with the message text.
     *
     * @param playerId The UUID of the player who sent the message.
     * @param message The raw chat message text.
     * @return `true` if the message was consumed by an active session; `false` otherwise.
     *         Callers should cancel the chat event when this returns `true`.
     */
    fun onChatMessage(playerId: UUID, message: String): Boolean {
        val deferred = sessions[playerId] ?: return false
        if (message.equals("cancel", ignoreCase = true)) {
            deferred.complete(null)
        } else {
            deferred.complete(message)
        }
        return true
    }

    /
     * Called when a player disconnects to clean up any active session.
     *
     * Completes the deferred exceptionally with [ChatInputCancelledException] so that
     * any coroutine suspended in [awaitChatInput] or [inputChain] is unblocked and can
     * handle the disconnection gracefully.
     *
     * @param playerId The UUID of the disconnecting player.
     */
    fun onPlayerDisconnect(playerId: UUID) {
        sessions.remove(playerId)?.completeExceptionally(
            ChatInputCancelledException(playerId)
        )
    }

    /
     * Returns `true` if [playerId] currently has an active [Chat_Input_Session].
     *
     * @param playerId The UUID to check.
     */
    fun hasActiveSession(playerId: UUID): Boolean = sessions.containsKey(playerId)
}

/
 * Thrown when a [Chat_Input_Session] is cancelled, either because the player typed `"cancel"`,
 * disconnected, or the session was otherwise terminated before a response was received.
 *
 * @property playerId The UUID of the player whose session was cancelled.
 */
class ChatInputCancelledException(val playerId: UUID) : Exception("Chat input cancelled for $playerId")
