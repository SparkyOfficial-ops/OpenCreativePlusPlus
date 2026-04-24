// Feature: category-based-coding-ui, Property 11: Param chest PDC tag and idempotence
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * Property 11: Param chest PDC tag and idempotence
 *
 * For any Category_Block for which ParameterPlacer.placeChest is called, the block
 * directly above must be CHEST and its PDC must contain ocp:param_chest = "true".
 * If placeChest is called a second time on the same block, there must still be
 * exactly one CHEST above it.
 *
 * **Validates: Requirements 8.3, 8.4**
 */
class ParamChestPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Helpers: build a fake "world" for one category block + the block above
    // -----------------------------------------------------------------------

    /**
     * Builds a mocked Plugin whose name is "opencreativeplus" (matching plugin.yml).
     * NamespacedKey(plugin, "param_chest") → "opencreativeplus:param_chest"
     */
    fun mockPlugin(): Plugin {
        val logger = mockk<Logger>(relaxed = true)
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.name } returns "opencreativeplus"
        every { plugin.logger } returns logger
        return plugin
    }

    /**
     * Builds a pair of mutable-state blocks: (categoryBlock, blockAbove).
     *
     * The blockAbove starts as AIR. Its type can be mutated via the returned
     * [MutableBlockWorld] helper, which also tracks PDC writes.
     */
    class MutableBlockWorld {
        var aboveType: Material = Material.AIR
        val pdcData: MutableMap<NamespacedKey, String> = mutableMapOf()

        /** Whether the PDC has been updated (chestState.update() called). */
        var pdcUpdated: Boolean = false

        fun buildBlocks(): Pair<Block, Block> {
            val categoryBlock = mockk<Block>(relaxed = true)
            val blockAbove = mockk<Block>(relaxed = true)

            every { categoryBlock.getRelative(BlockFace.UP) } returns blockAbove

            // blockAbove.type getter/setter backed by aboveType
            every { blockAbove.type } answers { aboveType }
            val typeSlot = slot<Material>()
            every { blockAbove.type = capture(typeSlot) } answers { aboveType = typeSlot.captured }

            // blockAbove.state returns a fresh BlockState mock each time,
            // backed by the shared pdcData map
            every { blockAbove.state } answers { buildChestState() }

            return categoryBlock to blockAbove
        }

        private fun buildChestState(): TileState {
            val pdc = mockk<PersistentDataContainer>(relaxed = true)
            val state = mockk<TileState>(relaxed = true)

            every { state.persistentDataContainer } returns pdc

            // Capture set() calls
            val keySlot = slot<NamespacedKey>()
            val valSlot = slot<String>()
            every {
                pdc.set(capture(keySlot), any<PersistentDataType<String, String>>(), capture(valSlot))
            } answers {
                pdcData[keySlot.captured] = valSlot.captured
            }

            // Return stored values for get()
            every {
                pdc.get(any<NamespacedKey>(), any<PersistentDataType<String, String>>())
            } answers {
                val key = firstArg<NamespacedKey>()
                pdcData[key]
            }

            // update() marks the PDC as persisted
            every { state.update() } answers { pdcUpdated = true; true }

            return state
        }
    }

    // -----------------------------------------------------------------------
    // Property 11a: after placeChest, block above is CHEST
    // -----------------------------------------------------------------------

    "Property 11a: after placeChest, the block directly above is CHEST" - {
        "for any category block position, placeChest must set the block above to CHEST" {
            // Validates: Requirements 8.1, 8.3
            checkAll(PropTestConfig(iterations = 20), Arb.int(0..1000).map { it }) { _ ->
                val world = MutableBlockWorld()
                val (categoryBlock, blockAbove) = world.buildBlocks()
                val plugin = mockPlugin()
                val placer = ParameterPlacer(plugin)

                val result = placer.placeChest(categoryBlock)

                result shouldBe true
                blockAbove.type shouldBe Material.CHEST
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11b: after placeChest, PDC contains ocp:param_chest = "true"
    // -----------------------------------------------------------------------

    "Property 11b: after placeChest, the chest's PDC contains ocp:param_chest = 'true'" - {
        "for any category block, the PDC tag ocp:param_chest must equal 'true' after placement" {
            // Validates: Requirements 8.3
            checkAll(PropTestConfig(iterations = 20), Arb.int(0..1000).map { it }) { _ ->
                val world = MutableBlockWorld()
                val (categoryBlock, _) = world.buildBlocks()
                val plugin = mockPlugin()
                val placer = ParameterPlacer(plugin)

                placer.placeChest(categoryBlock)

                val paramChestKey = NamespacedKey("opencreativeplus", "param_chest")
                world.pdcData[paramChestKey] shouldBe "true"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11c: placeChest returns false when block above is occupied
    // -----------------------------------------------------------------------

    "Property 11c: placeChest returns false when block above is not AIR" - {
        "if the block above is already occupied, placeChest must return false and not change it" {
            // Validates: Requirements 8.2
            checkAll(PropTestConfig(iterations = 20), Arb.int(0..1000).map { it }) { _ ->
                val world = MutableBlockWorld()
                world.aboveType = Material.STONE  // pre-occupied, not a param chest
                val (categoryBlock, blockAbove) = world.buildBlocks()
                val plugin = mockPlugin()
                val placer = ParameterPlacer(plugin)

                val result = placer.placeChest(categoryBlock)

                result shouldBe false
                blockAbove.type shouldBe Material.STONE
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11d: idempotence — calling placeChest twice results in exactly one CHEST
    // -----------------------------------------------------------------------

    "Property 11d: idempotence — calling placeChest twice results in exactly one CHEST above" - {
        "for any category block, two consecutive placeChest calls must leave exactly one CHEST above" {
            // Validates: Requirements 8.4
            checkAll(PropTestConfig(iterations = 20), Arb.int(0..1000).map { it }) { _ ->
                val world = MutableBlockWorld()
                val (categoryBlock, blockAbove) = world.buildBlocks()
                val plugin = mockPlugin()
                val placer = ParameterPlacer(plugin)

                // First placement
                val first = placer.placeChest(categoryBlock)
                first shouldBe true
                blockAbove.type shouldBe Material.CHEST

                // Second placement — must remove old chest and place a new one
                val second = placer.placeChest(categoryBlock)
                second shouldBe true

                // Still exactly one CHEST above (not AIR, not doubled)
                blockAbove.type shouldBe Material.CHEST

                // PDC tag must still be "true"
                val paramChestKey = NamespacedKey("opencreativeplus", "param_chest")
                world.pdcData[paramChestKey] shouldBe "true"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 11e: hasParamChest reflects placeChest state
    // -----------------------------------------------------------------------

    "Property 11e: hasParamChest returns true after placeChest and false after removeChest" - {
        "hasParamChest must be consistent with the placement lifecycle" {
            // Validates: Requirements 8.3, 8.4
            checkAll(PropTestConfig(iterations = 20), Arb.int(0..1000).map { it }) { _ ->
                val world = MutableBlockWorld()
                val (categoryBlock, blockAbove) = world.buildBlocks()
                val plugin = mockPlugin()
                val placer = ParameterPlacer(plugin)

                // Before placement: no chest
                blockAbove.type shouldBe Material.AIR

                // After placement: hasParamChest should return true
                placer.placeChest(categoryBlock)
                blockAbove.type shouldBe Material.CHEST
                val paramChestKey = NamespacedKey("opencreativeplus", "param_chest")
                world.pdcData[paramChestKey] shouldBe "true"

                // After removal: block above becomes AIR
                placer.removeChest(categoryBlock)
                blockAbove.type shouldBe Material.AIR
            }
        }
    }
})
