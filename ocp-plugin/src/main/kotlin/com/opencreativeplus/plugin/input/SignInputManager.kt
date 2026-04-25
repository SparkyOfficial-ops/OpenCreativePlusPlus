package com.opencreativeplus.plugin.input

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolManager
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.events.PacketEvent
import kotlinx.coroutines.CompletableDeferred
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the state for an active sign input session.
 *
 * @property playerId       UUID of the player in the session
 * @property blockLocation  Location of the temporary sign block placed for input
 * @property prefill        The initial text pre-filled on the sign's first line
 * @property deferred       Completes with the entered text, or null on cancel/disconnect
 */
data class SignInputSession(
    val playerId: UUID,
    val blockLocation: Location,
    val prefill: String,
    val deferred: CompletableDeferred<String?>
)

/**
 * Manages sign-based text input sessions for players.
 *
 * When [awaitSignInput] is called, a temporary sign block is placed at a location
 * near the player, the sign editor is opened via ProtocolLib packets, and the
 * coroutine suspends until the player submits or cancels.
 *
 * The UPDATE_SIGN packet is intercepted to capture the first line of the sign.
 * On session end (submit, cancel, or disconnect), the temporary block is removed.
 *
 * Requirements: 1.1, 1.2, 1.3, 1.6, 1.8
 */
class SignInputManager(
    private val plugin: Plugin,
    private val protocolManager: ProtocolManager
) {

    private val sessions = ConcurrentHashMap<UUID, SignInputSession>()

    init {
        // Intercept UPDATE_SIGN packets from clients to capture sign input
        protocolManager.addPacketListener(object : PacketAdapter(
            plugin,
            ListenerPriority.HIGHEST,
            PacketType.Play.Client.UPDATE_SIGN
        ) {
            override fun onPacketReceiving(event: PacketEvent) {
                val player = event.player
                val session = sessions[player.uniqueId] ?: return

                // Cancel the packet so the sign change doesn't persist in the world
                event.isCancelled = true

                // Read the first line from the sign update packet
                val strings = event.packet.stringArrays.read(0)
                val firstLine = strings?.getOrNull(0) ?: ""

                // Complete the deferred and clean up
                session.deferred.complete(firstLine)
                sessions.remove(player.uniqueId)
                removeTemporaryBlock(session.blockLocation, player)
            }
        })
    }

    /**
     * Opens a sign editor for [player] pre-filled with [prefill], suspends until
     * the player submits or the session is cancelled.
     *
     * @param player  The player to prompt
     * @param prefill The text to pre-fill on the first line of the sign
     * @return The text entered by the player, or null if cancelled/disconnected
     */
    suspend fun awaitSignInput(player: Player, prefill: String): String? {
        val deferred = CompletableDeferred<String?>()
        val location = computeSignLocation(player)

        val session = SignInputSession(
            playerId = player.uniqueId,
            blockLocation = location,
            prefill = prefill,
            deferred = deferred
        )
        sessions[player.uniqueId] = session

        // Place a temporary sign block at the computed location (client-side only)
        placeTemporarySign(player, location, prefill)

        // Send OPEN_SIGN_EDITOR packet to open the sign editor UI
        sendOpenSignEditor(player, location)

        return try {
            deferred.await()
        } finally {
            sessions.remove(player.uniqueId)
            removeTemporaryBlock(location, player)
        }
    }

    /**
     * Cancels the active session for [player], completing the deferred with null
     * and removing the temporary sign block.
     */
    fun cancelSession(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        session.deferred.complete(null)
        removeTemporaryBlock(session.blockLocation, player)
    }

    /**
     * Called from PlayerQuitEvent to clean up any active session for the disconnecting player.
     */
    fun onPlayerQuit(playerId: UUID) {
        val session = sessions.remove(playerId) ?: return
        session.deferred.complete(null)
        // Player is offline, fall back to world block removal
        removeTemporaryBlock(session.blockLocation, null)
    }

    /**
     * Returns true if [playerId] currently has an active sign input session.
     */
    fun hasActiveSession(playerId: UUID): Boolean = sessions.containsKey(playerId)

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes a suitable location for the temporary sign block — one block in
     * front of the player at eye level, clamped to integer coordinates.
     */
    private fun computeSignLocation(player: Player): Location {
        val loc = player.location.clone()
        // Place the sign 2 blocks in front of the player at their feet level
        val dir = loc.direction.normalize()
        return Location(
            loc.world,
            Math.floor(loc.x + dir.x * 2).toInt().toDouble(),
            Math.floor(loc.y).toInt().toDouble(),
            Math.floor(loc.z + dir.z * 2).toInt().toDouble()
        )
    }

    /**
     * Places a temporary OAK_SIGN block at [location] and sets the first line
     * to [prefill] via a BLOCK_CHANGE packet (client-side only).
     */
    private fun placeTemporarySign(player: Player, location: Location, prefill: String) {
        val blockChangePacket = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE)

        // Set the block position
        blockChangePacket.blockPositionModifier.write(
            0,
            com.comphenix.protocol.wrappers.BlockPosition(
                location.blockX,
                location.blockY,
                location.blockZ
            )
        )

        // Set the block data to OAK_SIGN
        val signData: BlockData = plugin.server.createBlockData(Material.OAK_SIGN)
        blockChangePacket.blockData.write(0, com.comphenix.protocol.wrappers.WrappedBlockData.createData(signData))

        protocolManager.sendServerPacket(player, blockChangePacket)

        // Send sign tile entity data with prefill text
        sendSignTileEntityData(player, location, prefill)
    }

    /**
     * Sends a tile entity update packet to set the sign text (prefill) on the client.
     */
    private fun sendSignTileEntityData(player: Player, location: Location, prefill: String) {
        // Use UPDATE_SIGN server packet to set the sign content client-side
        val tilePacket = protocolManager.createPacket(PacketType.Play.Server.UPDATE_SIGN)
        tilePacket.blockPositionModifier.write(
            0,
            com.comphenix.protocol.wrappers.BlockPosition(
                location.blockX,
                location.blockY,
                location.blockZ
            )
        )
        // Set the four lines: first line = prefill, rest empty
        tilePacket.stringArrays.write(0, arrayOf(prefill, "", "", ""))
        protocolManager.sendServerPacket(player, tilePacket)
    }

    /**
     * Sends an OPEN_SIGN_EDITOR packet to open the sign editing UI for [player].
     */
    private fun sendOpenSignEditor(player: Player, location: Location) {
        val packet = protocolManager.createPacket(PacketType.Play.Server.OPEN_SIGN_EDITOR)
        packet.blockPositionModifier.write(
            0,
            com.comphenix.protocol.wrappers.BlockPosition(
                location.blockX,
                location.blockY,
                location.blockZ
            )
        )
        protocolManager.sendServerPacket(player, packet)
    }

    /**
     * Removes the temporary sign block by sending a BLOCK_CHANGE packet restoring
     * AIR at [location] to the [player], or falling back to a world block change.
     */
    private fun removeTemporaryBlock(location: Location, player: Player? = null) {
        val onlinePlayer = player ?: plugin.server.onlinePlayers
            .firstOrNull { sessions[it.uniqueId]?.blockLocation == location }

        if (onlinePlayer != null) {
            val restorePacket = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE)
            restorePacket.blockPositionModifier.write(
                0,
                com.comphenix.protocol.wrappers.BlockPosition(
                    location.blockX,
                    location.blockY,
                    location.blockZ
                )
            )
            val airData: BlockData = plugin.server.createBlockData(Material.AIR)
            restorePacket.blockData.write(0, com.comphenix.protocol.wrappers.WrappedBlockData.createData(airData))
            runCatching { protocolManager.sendServerPacket(onlinePlayer, restorePacket) }
        } else {
            // Fallback: restore the actual world block if player is gone
            runCatching {
                location.world?.getBlockAt(location)?.type = Material.AIR
            }
        }
    }
}
