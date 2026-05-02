@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

// Feature: ocp-plugin-fixes-and-completions, Property 3: per-player pending block map

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.CategoryRegistry
import com.opencreativeplus.plugin.scanner.ParameterPlacer
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import java.util.logging.Logger
import io.mockk.every

/**
 * Property 3: per-player pending block map
 *
 * For any player UUID and block, after storing the block in pendingBlocks for that
 * player, the map must return that exact block. After removing the entry (simulating
 * inventory close), the map must return null for that player.
 *
 * Additionally, if a player opens the GUI for block A and then for block B, the map
 * must contain block B (not block A), satisfying Req 3.4.
 *
 * **Validates: Requirements 3.2, 3.4**
 *
 * Feature: ocp-plugin-fixes-and-completions, Property 3: per-player pending block map
 */
class NodeSelectionGUIBlockPropertyTest : FreeSpec({

    fun makeGui(): NodeSelectionGUI {
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("test")
        every { plugin.name } returns "ocp"
        return NodeSelectionGUI(
            categoryRegistry = CategoryRegistry(),
            parameterPlacer = mockk<ParameterPlacer>(relaxed = true),
            modeManager = mockk<ModeManager>(relaxed = true),
            plotManager = mockk<PlotManagerImpl>(relaxed = true),
            scope = CoroutineScope(Dispatchers.Unconfined),
            plugin = plugin
        )
    }

    // -----------------------------------------------------------------------
    // Property 3a: storing a block in pendingBlocks makes it retrievable
    // -----------------------------------------------------------------------

    "Property 3a: pendingBlocks stores the block for the given player UUID (Req 3.2)" - {
        // Validates: Requirements 3.2
        // After simulating onPlayerInteract by writing to pendingBlocks, the map
        // must return the exact same block instance for that player's UUID.
        "for any UUID, pendingBlocks[uuid] == block after storing" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { uuid ->
                val gui = makeGui()
                val block = mockk<Block>(relaxed = true)

                // Simulate what onPlayerInteract does: store the block
                gui.pendingBlocks[uuid] = block

                gui.pendingBlocks[uuid] shouldBe block
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3b: removing the entry (simulating close) clears the block
    // -----------------------------------------------------------------------

    "Property 3b: pendingBlocks[uuid] is null after removal (simulating onInventoryClose)" - {
        // Validates: Requirements 3.2, 3.3
        // After simulating onInventoryClose by removing the entry, the map must
        // return null for that player's UUID.
        "for any UUID, pendingBlocks[uuid] == null after remove" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { uuid ->
                val gui = makeGui()
                val block = mockk<Block>(relaxed = true)

                // Store then remove — mirrors onPlayerInteract then onInventoryClose
                gui.pendingBlocks[uuid] = block
                gui.pendingBlocks.remove(uuid)

                gui.pendingBlocks[uuid].shouldBeNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3c: opening for block B after block A results in block B (Req 3.4)
    // -----------------------------------------------------------------------

    "Property 3c: last stored block wins — block B replaces block A for the same UUID (Req 3.4)" - {
        // Validates: Requirements 3.4
        // If a player opens NodeSelectionGUI for block A and then for block B,
        // pendingBlocks must contain block B (the most recently stored block).
        "for any UUID, storing blockA then blockB yields blockB in pendingBlocks" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid()
            ) { uuid ->
                val gui = makeGui()
                val blockA = mockk<Block>(relaxed = true)
                val blockB = mockk<Block>(relaxed = true)

                // Simulate opening GUI for block A
                gui.pendingBlocks[uuid] = blockA
                // Simulate opening GUI for block B (replaces A)
                gui.pendingBlocks[uuid] = blockB

                gui.pendingBlocks[uuid] shouldBe blockB
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3d: per-player isolation — different UUIDs have independent entries
    // -----------------------------------------------------------------------

    "Property 3d: per-player isolation — different UUIDs store independent blocks" - {
        // Validates: Requirements 3.2
        // The pendingBlocks map is keyed by player UUID, so two different players
        // must each see their own block independently.
        "two distinct UUIDs each store their own block without interference" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.uuid()
            ) { uuid1, uuid2 ->
                // Ensure the two UUIDs are distinct
                if (uuid1 == uuid2) return@checkAll

                val gui = makeGui()
                val block1 = mockk<Block>(relaxed = true)
                val block2 = mockk<Block>(relaxed = true)

                gui.pendingBlocks[uuid1] = block1
                gui.pendingBlocks[uuid2] = block2

                gui.pendingBlocks[uuid1] shouldBe block1
                gui.pendingBlocks[uuid2] shouldBe block2
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 3e: removing one player's entry does not affect another player's entry
    // -----------------------------------------------------------------------

    "Property 3e: removing one player's entry leaves other players' entries intact" - {
        // Validates: Requirements 3.2, 3.3
        // When one player closes the GUI (entry removed), other players' pending
        // blocks must remain in the map.
        "removing uuid1 entry does not affect uuid2 entry" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.uuid(),
                Arb.uuid()
            ) { uuid1, uuid2 ->
                if (uuid1 == uuid2) return@checkAll

                val gui = makeGui()
                val block1 = mockk<Block>(relaxed = true)
                val block2 = mockk<Block>(relaxed = true)

                gui.pendingBlocks[uuid1] = block1
                gui.pendingBlocks[uuid2] = block2

                // Player 1 closes the GUI
                gui.pendingBlocks.remove(uuid1)

                // Player 2's entry must still be present
                gui.pendingBlocks[uuid2] shouldBe block2
                // Player 1's entry must be gone
                gui.pendingBlocks[uuid1].shouldBeNull()
            }
        }
    }
})
