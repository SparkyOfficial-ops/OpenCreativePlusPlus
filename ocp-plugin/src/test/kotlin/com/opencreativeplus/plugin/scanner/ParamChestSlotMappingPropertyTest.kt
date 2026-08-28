// Feature: ocp-visual-programming-platform, Property 8: Маппинг слотов ParamChest на аргументы
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.opencreativeplus.plugin.scanner

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Barrel
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Property 8: Маппинг слотов ParamChest на аргументы
 *
 * For any chest with N items in slots 0..N-1, [BlockScanner.readParamChestContainers]
 * must map slot i to argument index i of the node, without skipping or reordering arguments.
 * Empty slots must produce null entries (Requirement 3.7).
 *
 * **Validates: Requirements 3.6**
 */
class ParamChestSlotMappingPropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------

    val world = mockk<World>(relaxed = true)
    val nodeRegistry = mockk<com.opencreativeplus.api.registry.NodeRegistry>(relaxed = true)
    val scanner = BlockScanner(world, nodeRegistry)

    // PDC keys used by readDataContainer
    val KEY_DC_VAR_NAME   = NamespacedKey("ocp", "var_name")
    val KEY_DC_LOC_X      = NamespacedKey("ocp", "loc_x")
    val KEY_DC_LOC_Y      = NamespacedKey("ocp", "loc_y")
    val KEY_DC_LOC_Z      = NamespacedKey("ocp", "loc_z")
    val KEY_DC_LOC_WORLD  = NamespacedKey("ocp", "loc_world")
    val KEY_DC_LOC_YAW    = NamespacedKey("ocp", "loc_yaw")
    val KEY_DC_LOC_PITCH  = NamespacedKey("ocp", "loc_pitch")

    // -----------------------------------------------------------------------
    // Item mock helpers
    // -----------------------------------------------------------------------

    /** Build a mock Book item whose display name is [text]. → DataContainer.Text */
    fun mockBookItem(text: String): ItemStack {
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        every { meta.persistentDataContainer } returns pdc
        every { meta.displayName() } returns Component.text(text)
        val item = mockk<ItemStack>(relaxed = true)
        every { item.type } returns Material.BOOK
        every { item.itemMeta } returns meta
        return item
    }

    /** Build a mock Magma Cream item whose display name is [number.toString()]. → DataContainer.Number */
    fun mockMagmaCreamItem(number: Double): ItemStack {
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        val meta = mockk<ItemMeta>(relaxed = true)
        every { meta.persistentDataContainer } returns pdc
        every { meta.displayName() } returns Component.text(number.toString())
        val item = mockk<ItemStack>(relaxed = true)
        every { item.type } returns Material.MAGMA_CREAM
        every { item.itemMeta } returns meta
        return item
    }

    /** Build a mock Iron Ingot item with PDC ocp:var_name = [varName]. → DataContainer.Variable */
    fun mockIronIngotItem(varName: String): ItemStack {
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { pdc.get(KEY_DC_VAR_NAME, PersistentDataType.STRING) } returns varName
        val meta = mockk<ItemMeta>(relaxed = true)
        every { meta.persistentDataContainer } returns pdc
        every { meta.displayName() } returns Component.text(varName)
        val item = mockk<ItemStack>(relaxed = true)
        every { item.type } returns Material.IRON_INGOT
        every { item.itemMeta } returns meta
        return item
    }

    /** Build a mock Compass item with PDC location coordinates. → DataContainer.Location */
    fun mockCompassItem(x: Double, y: Double, z: Double, worldName: String, yaw: Float, pitch: Float): ItemStack {
        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { pdc.get(KEY_DC_LOC_X,     PersistentDataType.DOUBLE) } returns x
        every { pdc.get(KEY_DC_LOC_Y,     PersistentDataType.DOUBLE) } returns y
        every { pdc.get(KEY_DC_LOC_Z,     PersistentDataType.DOUBLE) } returns z
        every { pdc.get(KEY_DC_LOC_WORLD, PersistentDataType.STRING) } returns worldName
        every { pdc.get(KEY_DC_LOC_YAW,   PersistentDataType.DOUBLE) } returns yaw.toDouble()
        every { pdc.get(KEY_DC_LOC_PITCH, PersistentDataType.DOUBLE) } returns pitch.toDouble()
        val meta = mockk<ItemMeta>(relaxed = true)
        every { meta.persistentDataContainer } returns pdc
        val item = mockk<ItemStack>(relaxed = true)
        every { item.type } returns Material.COMPASS
        every { item.itemMeta } returns meta
        return item
    }

    /**
     * Build a mock code block with a Chest directly above it.
     * [contents] is the full inventory array (null = empty slot).
     */
    fun makeBlockWithChest(contents: Array<ItemStack?>): Block {
        val inventory = mockk<Inventory>(relaxed = true)
        every { inventory.contents } returns contents

        val chestState = mockk<Barrel>(relaxed = true)
        every { chestState.inventory } returns inventory

        val chestBlock = mockk<Block>(relaxed = true)
        every { chestBlock.state } returns chestState

        val codeBlock = mockk<Block>(relaxed = true)
        val location = Location(world, 0.0, 64.0, 0.0)
        every { codeBlock.location } returns location
        every { codeBlock.getRelative(BlockFace.UP) } returns chestBlock

        return codeBlock
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    val arbFiniteDouble = Arb.double().filter { !it.isNaN() && !it.isInfinite() }
    val arbFiniteFloat  = Arb.float().filter  { !it.isNaN() && !it.isInfinite() }
    val arbText         = Arb.string(0..40)
    val arbVarName      = Arb.string(1..30)
    val arbWorldName    = Arb.string(1..20)

    /** Arbitrary DataContainer (all four types). */
    val arbDataContainer: Arb<DataContainer> = arbitrary {
        when (Arb.int(0..3).bind()) {
            0 -> DataContainer.Text(arbText.bind())
            1 -> DataContainer.Number(arbFiniteDouble.bind())
            2 -> DataContainer.Variable(arbVarName.bind())
            else -> DataContainer.Location(
                x     = arbFiniteDouble.bind(),
                y     = arbFiniteDouble.bind(),
                z     = arbFiniteDouble.bind(),
                world = arbWorldName.bind(),
                yaw   = arbFiniteFloat.bind(),
                pitch = arbFiniteFloat.bind()
            )
        }
    }

    /** Convert a DataContainer to the corresponding mock ItemStack. */
    fun DataContainer.toMockItem(): ItemStack = when (this) {
        is DataContainer.Text     -> mockBookItem(value)
        is DataContainer.Number   -> mockMagmaCreamItem(value)
        is DataContainer.Variable -> mockIronIngotItem(name)
        is DataContainer.Location -> mockCompassItem(x, y, z, this.world, yaw, pitch)
    }

    // -----------------------------------------------------------------------
    // Property 8a: slot index i maps to argument index i (no reordering)
    // -----------------------------------------------------------------------

    "Property 8a: slot i maps to argument index i — no reordering" - {
        /**
         * Validates: Requirements 3.6
         *
         * For any list of N DataContainers placed in slots 0..N-1,
         * readParamChestContainers must return a list where index i holds
         * the DataContainer corresponding to slot i.
         */
        "for any N items in slots 0..N-1, result[i] corresponds to slot i" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(arbDataContainer, 1..10)
            ) { containers ->
                val items: Array<ItemStack?> = containers.map { it.toMockItem() }.toTypedArray()
                val block = makeBlockWithChest(items)

                val result = scanner.readParamChestContainers(block)

                // Result must have at least as many entries as the chest contents
                result.size shouldBe items.size

                // Each slot i must map to argument index i
                containers.forEachIndexed { i, expected ->
                    val actual = result[i]
                    actual shouldNotBe null
                    when (expected) {
                        is DataContainer.Text     -> actual shouldBe DataContainer.Text(expected.value)
                        is DataContainer.Number   -> actual shouldBe DataContainer.Number(expected.value)
                        is DataContainer.Variable -> actual shouldBe DataContainer.Variable(expected.name)
                        is DataContainer.Location -> actual shouldBe DataContainer.Location(
                            expected.x, expected.y, expected.z,
                            expected.world, expected.yaw, expected.pitch
                        )
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8b: empty slots produce null entries (Requirement 3.7)
    // -----------------------------------------------------------------------

    "Property 8b: empty slots produce null entries at the corresponding argument index" - {
        /**
         * Validates: Requirements 3.6, 3.7
         *
         * For any chest where some slots are null (empty), the result list must
         * contain null at those exact indices — no shifting or compaction.
         */
        "null slots in the chest inventory map to null in the result list" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(arbDataContainer.orNull(nullProbability = 0.4), 2..10)
            ) { containers ->
                val items: Array<ItemStack?> = containers.map { dc ->
                    dc?.toMockItem()
                }.toTypedArray()
                val block = makeBlockWithChest(items)

                val result = scanner.readParamChestContainers(block)

                result.size shouldBe items.size

                containers.forEachIndexed { i, expected ->
                    if (expected == null) {
                        result[i] shouldBe null
                    } else {
                        result[i] shouldNotBe null
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8c: no arguments are skipped — result size equals chest size
    // -----------------------------------------------------------------------

    "Property 8c: result size equals chest inventory size — no arguments skipped" - {
        /**
         * Validates: Requirements 3.6
         *
         * The result list must have exactly as many entries as the chest inventory,
         * ensuring no slot is silently dropped.
         */
        "result list length equals the number of chest slots" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..27)
            ) { n ->
                // Build a chest with n slots: alternating Book items and empty slots
                val items: Array<ItemStack?> = Array(n) { i ->
                    if (i % 2 == 0) mockBookItem("text_$i") else null
                }
                val block = makeBlockWithChest(items)

                val result = scanner.readParamChestContainers(block)

                result shouldHaveSize n
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8d: single-item chest — item always at index 0
    // -----------------------------------------------------------------------

    "Property 8d: single-item chest — item is always at argument index 0" - {
        /**
         * Validates: Requirements 3.6
         *
         * For any single DataContainer placed in slot 0, the result must have
         * exactly one entry at index 0 with the correct value.
         */
        "any single item in slot 0 maps to argument index 0" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbDataContainer
            ) { container ->
                val items: Array<ItemStack?> = arrayOf(container.toMockItem())
                val block = makeBlockWithChest(items)

                val result = scanner.readParamChestContainers(block)

                result shouldHaveSize 1
                result[0] shouldNotBe null
                when (container) {
                    is DataContainer.Text     -> result[0] shouldBe DataContainer.Text(container.value)
                    is DataContainer.Number   -> result[0] shouldBe DataContainer.Number(container.value)
                    is DataContainer.Variable -> result[0] shouldBe DataContainer.Variable(container.name)
                    is DataContainer.Location -> result[0] shouldBe DataContainer.Location(
                        container.x, container.y, container.z,
                        container.world, container.yaw, container.pitch
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8e: all-empty chest returns all-null list
    // -----------------------------------------------------------------------

    "Property 8e: all-empty chest returns a list of all nulls" - {
        /**
         * Validates: Requirements 3.6, 3.7
         *
         * A chest where every slot is empty must produce a result list of the same
         * length where every entry is null.
         */
        "chest with N empty slots produces N null entries" {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.int(1..27)
            ) { n ->
                val items: Array<ItemStack?> = arrayOfNulls(n)
                val block = makeBlockWithChest(items)

                val result = scanner.readParamChestContainers(block)

                result shouldHaveSize n
                result.forEach { entry -> entry shouldBe null }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 8f: mixed types preserve order across all four DataContainer types
    // -----------------------------------------------------------------------

    "Property 8f: mixed DataContainer types in a single chest preserve slot order" - {
        /**
         * Validates: Requirements 3.6
         *
         * A chest containing one of each DataContainer type (Text, Number, Variable, Location)
         * in a specific order must produce results in the same order at the same indices.
         */
        "Text at 0, Number at 1, Variable at 2, Location at 3 — order preserved" {
            checkAll(
                PropTestConfig(iterations = 100),
                arbText,
                arbFiniteDouble,
                arbVarName,
                arbFiniteDouble, arbFiniteDouble, arbFiniteDouble, arbWorldName,
                arbFiniteFloat, arbFiniteFloat
            ) { text, number, varName, lx, ly, lz, lWorld, lYaw, lPitch ->
                val items: Array<ItemStack?> = arrayOf(
                    mockBookItem(text),
                    mockMagmaCreamItem(number),
                    mockIronIngotItem(varName),
                    mockCompassItem(lx, ly, lz, lWorld, lYaw, lPitch)
                )
                val block = makeBlockWithChest(items)

                val result = scanner.readParamChestContainers(block)

                result shouldHaveSize 4
                result[0] shouldBe DataContainer.Text(text)
                result[1] shouldBe DataContainer.Number(number)
                result[2] shouldBe DataContainer.Variable(varName)
                result[3] shouldBe DataContainer.Location(lx, ly, lz, lWorld, lYaw, lPitch)
            }
        }
    }
})
