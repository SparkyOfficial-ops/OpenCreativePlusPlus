package com.opencreativeplus.plugin.scanner

import com.opencreativeplus.api.registry.NodeRegistry
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BlockScanner.readDataContainer].
 *
 * Covers one concrete example per DataContainer subtype and the ParseError
 * path for a non-numeric Magma Cream display name.
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5
 */
class DataContainerTest {

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    private val world = mockk<World>(relaxed = true)
    private val nodeRegistry = mockk<NodeRegistry>(relaxed = true)
    private val scanner = BlockScanner(world, nodeRegistry)

    /**
     * Build a mock [ItemStack] with the given [material] and [displayName].
     * The item's PDC is backed by an in-memory map so that [PersistentDataType.STRING]
     * and [PersistentDataType.DOUBLE] reads work correctly.
     */
    private fun mockItem(
        material: Material,
        displayName: String = "",
        pdcEntries: Map<NamespacedKey, Any> = emptyMap()
    ): ItemStack {
        val store = pdcEntries.toMutableMap<NamespacedKey, Any>()

        val pdc = mockk<PersistentDataContainer>(relaxed = true)
        every { pdc.get(any(), PersistentDataType.STRING) } answers {
            store[firstArg<NamespacedKey>()] as? String
        }
        every { pdc.get(any(), PersistentDataType.DOUBLE) } answers {
            store[firstArg<NamespacedKey>()] as? Double
        }

        val component: Component = Component.text(displayName)
        val meta = mockk<ItemMeta>(relaxed = true)
        every { meta.displayName() } returns component
        every { meta.persistentDataContainer } returns pdc

        val item = mockk<ItemStack>(relaxed = true)
        every { item.type } returns material
        every { item.itemMeta } returns meta

        return item
    }

    // -----------------------------------------------------------------------
    // Requirement 3.1 — Book → DataContainer.Text
    // -----------------------------------------------------------------------

    /**
     * WHEN a Book item is placed in a ParamChest slot,
     * THEN readDataContainer SHALL return DataContainer.Text whose value
     * equals the item's plain-text display name.
     *
     * Requirements: 3.1
     */
    @Test
    fun `Book item returns DataContainer Text with display name as value`() {
        val item = mockItem(Material.BOOK, displayName = "Hello World")

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Text>(result)
        assertEquals("Hello World", result.value)
    }

    /**
     * Book with an empty display name returns DataContainer.Text with an empty string.
     *
     * Requirements: 3.1
     */
    @Test
    fun `Book item with empty display name returns DataContainer Text with empty string`() {
        val item = mockItem(Material.BOOK, displayName = "")

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Text>(result)
        assertEquals("", result.value)
    }

    // -----------------------------------------------------------------------
    // Requirement 3.2 — Magma Cream → DataContainer.Number
    // -----------------------------------------------------------------------

    /**
     * WHEN a Magma Cream item's display name is a valid number,
     * THEN readDataContainer SHALL return DataContainer.Number with that value.
     *
     * Requirements: 3.2
     */
    @Test
    fun `Magma Cream with numeric display name returns DataContainer Number`() {
        val item = mockItem(Material.MAGMA_CREAM, displayName = "42.5")

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Number>(result)
        assertEquals(42.5, result.value)
    }

    /**
     * Magma Cream with an integer display name is parsed as a Double.
     *
     * Requirements: 3.2
     */
    @Test
    fun `Magma Cream with integer display name returns DataContainer Number as Double`() {
        val item = mockItem(Material.MAGMA_CREAM, displayName = "100")

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Number>(result)
        assertEquals(100.0, result.value)
    }

    // -----------------------------------------------------------------------
    // Requirement 3.5 — Magma Cream with non-numeric display name → ParseError
    // -----------------------------------------------------------------------

    /**
     * IF a Magma Cream item's display name cannot be parsed as a number,
     * THEN readDataContainer SHALL add a ParseError identifying the slot
     * and return null.
     *
     * Requirements: 3.5
     */
    @Test
    fun `Magma Cream with non-numeric display name adds ParseError and returns null`() {
        val item = mockItem(Material.MAGMA_CREAM, displayName = "not-a-number")
        scanner.parseErrors.clear()

        val result = scanner.readDataContainer(item, slot = 2)

        assertNull(result, "Expected null when display name is not a valid number")
        assertEquals(1, scanner.parseErrors.size, "Expected exactly one ParseError")

        val error = scanner.parseErrors[0]
        assertTrue(
            error.message.contains("2"),
            "ParseError message should identify slot 2, was: '${error.message}'"
        )
        assertTrue(
            error.message.contains("not-a-number"),
            "ParseError message should contain the invalid value, was: '${error.message}'"
        )
    }

    /**
     * ParseError for a non-numeric Magma Cream includes the block location when provided.
     *
     * Requirements: 3.5
     */
    @Test
    fun `Magma Cream ParseError carries block location when provided`() {
        val item = mockItem(Material.MAGMA_CREAM, displayName = "abc")
        val blockLocation = org.bukkit.Location(world, 10.0, 64.0, -5.0)
        scanner.parseErrors.clear()

        scanner.readDataContainer(item, slot = 0, blockLocation = blockLocation)

        assertEquals(1, scanner.parseErrors.size)
        assertEquals(blockLocation, scanner.parseErrors[0].location)
    }

    // -----------------------------------------------------------------------
    // Requirement 3.3 — Iron Ingot → DataContainer.Variable
    // -----------------------------------------------------------------------

    /**
     * WHEN an Iron Ingot item has the PDC tag ocp:var_name,
     * THEN readDataContainer SHALL return DataContainer.Variable with that name.
     *
     * Requirements: 3.3
     */
    @Test
    fun `Iron Ingot with var_name PDC tag returns DataContainer Variable`() {
        val varNameKey = NamespacedKey("ocp", "var_name")
        val item = mockItem(
            material = Material.IRON_INGOT,
            pdcEntries = mapOf(varNameKey to "myVariable")
        )

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Variable>(result)
        assertEquals("myVariable", result.name)
    }

    /**
     * Iron Ingot without a PDC tag falls back to the display name as the variable name.
     *
     * Requirements: 3.3
     */
    @Test
    fun `Iron Ingot without PDC tag falls back to display name as variable name`() {
        val item = mockItem(Material.IRON_INGOT, displayName = "fallbackVar")

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Variable>(result)
        assertEquals("fallbackVar", result.name)
    }

    // -----------------------------------------------------------------------
    // Requirement 3.4 — Compass → DataContainer.Location
    // -----------------------------------------------------------------------

    /**
     * WHEN a Compass item has PDC tags ocp:loc_x/y/z/world/yaw/pitch,
     * THEN readDataContainer SHALL return DataContainer.Location with those values.
     *
     * Requirements: 3.4
     */
    @Test
    fun `Compass with location PDC tags returns DataContainer Location`() {
        val item = mockItem(
            material = Material.COMPASS,
            pdcEntries = mapOf(
                NamespacedKey("ocp", "loc_x")     to 100.0,
                NamespacedKey("ocp", "loc_y")     to 64.0,
                NamespacedKey("ocp", "loc_z")     to -200.0,
                NamespacedKey("ocp", "loc_world") to "world_nether",
                NamespacedKey("ocp", "loc_yaw")   to 90.0,
                NamespacedKey("ocp", "loc_pitch") to -45.0
            )
        )

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Location>(result)
        assertEquals(100.0, result.x)
        assertEquals(64.0, result.y)
        assertEquals(-200.0, result.z)
        assertEquals("world_nether", result.world)
        assertEquals(90f, result.yaw)
        assertEquals(-45f, result.pitch)
    }

    /**
     * Compass with no PDC tags returns DataContainer.Location with all-zero defaults.
     *
     * Requirements: 3.4
     */
    @Test
    fun `Compass with no PDC tags returns DataContainer Location with default zeros`() {
        val item = mockItem(Material.COMPASS)

        val result = scanner.readDataContainer(item, slot = 0)

        assertIs<DataContainer.Location>(result)
        assertEquals(0.0, result.x)
        assertEquals(0.0, result.y)
        assertEquals(0.0, result.z)
        assertEquals("", result.world)
        assertEquals(0f, result.yaw)
        assertEquals(0f, result.pitch)
    }

    // -----------------------------------------------------------------------
    // Null / unrecognised material
    // -----------------------------------------------------------------------

    /**
     * A null item (empty slot) returns null.
     *
     * Requirements: 3.7
     */
    @Test
    fun `null item returns null`() {
        val result = scanner.readDataContainer(null, slot = 0)
        assertNull(result)
    }

    /**
     * An unrecognised material returns null without adding a ParseError.
     *
     * Requirements: 3.7
     */
    @Test
    fun `unrecognised material returns null without ParseError`() {
        val item = mockItem(Material.DIAMOND, displayName = "irrelevant")
        scanner.parseErrors.clear()

        val result = scanner.readDataContainer(item, slot = 0)

        assertNull(result)
        assertTrue(scanner.parseErrors.isEmpty(), "No ParseError expected for unrecognised material")
    }
}
