@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.gui

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.CategoryRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * Property 10: Sign displayName correctness and idempotence
 *
 * For any ActionDescriptor with displayName = D, after selecting that action on a
 * Category_Block, the first line of the adjacent OAK_SIGN must equal D.
 * If the action is changed to a descriptor with displayName = D2, the same sign
 * must be updated to D2 (no duplicate signs placed).
 *
 * **Validates: Requirements 6.2, 6.4**
 *
 * Feature: category-based-coding-ui, Property 10: Sign displayName correctness and idempotence
 */
class SignDisplayNamePropertyTest : FreeSpec({

    fun makeGui(): NodeSelectionGUI {
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("test")
        every { plugin.name } returns "ocp"
        return NodeSelectionGUI(
            categoryRegistry = CategoryRegistry(),
            modeManager = mockk<ModeManager>(relaxed = true),
            plotManager = mockk<PlotManagerImpl>(relaxed = true),
            scope = CoroutineScope(Dispatchers.Unconfined),
            plugin = plugin
        )
    }

    /**
     * Creates a mock Category_Block with all four horizontal faces as AIR.
     * The NORTH face block will have its state return the given signState.
     */
    fun mockCategoryBlockWithAirFaces(signState: Sign): Block {
        val categoryBlock = mockk<Block>(relaxed = true)

        // All four faces are AIR initially
        for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val airBlock = mockk<Block>(relaxed = true)
            every { airBlock.type } returns Material.AIR
            every { categoryBlock.getRelative(face) } returns airBlock
        }

        // NORTH face block — starts as AIR, state returns signState
        val northBlock = mockk<Block>(relaxed = true)
        every { northBlock.type } returns Material.AIR
        every { northBlock.state } returns signState
        every { categoryBlock.getRelative(BlockFace.NORTH) } returns northBlock

        return categoryBlock
    }

    /**
     * Creates a mock Category_Block that already has an OAK_WALL_SIGN on NORTH face.
     */
    fun mockCategoryBlockWithExistingSign(signState: Sign): Block {
        val categoryBlock = mockk<Block>(relaxed = true)
        val signBlock = mockk<Block>(relaxed = true)

        every { signBlock.type } returns Material.OAK_WALL_SIGN
        every { signBlock.state } returns signState
        every { categoryBlock.getRelative(BlockFace.NORTH) } returns signBlock

        // Other faces are AIR
        for (face in listOf(BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val airBlock = mockk<Block>(relaxed = true)
            every { airBlock.type } returns Material.AIR
            every { categoryBlock.getRelative(face) } returns airBlock
        }

        return categoryBlock
    }

    "Property 10a: placeOrUpdateSign writes displayName to first line of new sign" - {
        // Validates: Requirements 6.2
        "for any displayName D, the sign's first line equals D" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..15)
            ) { displayName ->
                val gui = makeGui()
                val signState = mockk<Sign>(relaxed = true)
                val block = mockCategoryBlockWithAirFaces(signState)

                val lineSlot = slot<Int>()
                val textSlot = slot<String>()
                @Suppress("DEPRECATION")
                every { signState.setLine(capture(lineSlot), capture(textSlot)) } returns Unit

                gui.placeOrUpdateSign(block, "test_action", displayName)

                lineSlot.captured shouldBe 0
                textSlot.captured shouldBe displayName
            }
        }
    }

    "Property 10b: placeOrUpdateSign updates existing sign instead of placing new one" - {
        // Validates: Requirements 6.4
        "when a sign already exists, setLine is called on the existing sign state" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..15),
                Arb.string(1..15)
            ) { firstName, secondName ->
                val gui = makeGui()
                val signState = mockk<Sign>(relaxed = true)
                val block = mockCategoryBlockWithExistingSign(signState)

                val textSlot = slot<String>()
                @Suppress("DEPRECATION")
                every { signState.setLine(any(), capture(textSlot)) } returns Unit

                // First selection
                gui.placeOrUpdateSign(block, "action_1", firstName)
                // Second selection — should update the same sign
                gui.placeOrUpdateSign(block, "action_2", secondName)

                // setLine should have been called twice (once per call)
                @Suppress("DEPRECATION")
                verify(exactly = 2) { signState.setLine(0, any()) }

                // Last call should have secondName
                textSlot.captured shouldBe secondName
            }
        }
    }

    "Property 10c: placeOrUpdateSign logs warning when all faces are occupied" - {
        // Validates: Requirements 6.3
        "when all four horizontal faces are non-air, a warning is logged" {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.string(1..15)
            ) { displayName ->
                val plugin = mockk<Plugin>(relaxed = true)
                val logger = mockk<java.util.logging.Logger>(relaxed = true)
                every { plugin.logger } returns logger
                every { plugin.name } returns "ocp"

                val gui = NodeSelectionGUI(
                    categoryRegistry = CategoryRegistry(),
                    modeManager = mockk<ModeManager>(relaxed = true),
                    plotManager = mockk<PlotManagerImpl>(relaxed = true),
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    plugin = plugin
                )

                val block = mockk<Block>(relaxed = true)
                // All four faces are occupied (non-air, non-sign)
                for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                    val occupiedBlock = mockk<Block>(relaxed = true)
                    every { occupiedBlock.type } returns Material.STONE
                    every { block.getRelative(face) } returns occupiedBlock
                }

                gui.placeOrUpdateSign(block, "test_action", displayName)

                verify { logger.warning(any<String>()) }
            }
        }
    }
})
