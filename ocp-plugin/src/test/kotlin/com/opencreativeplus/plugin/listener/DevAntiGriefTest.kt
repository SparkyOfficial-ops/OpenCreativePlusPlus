package com.opencreativeplus.plugin.listener

import com.opencreativeplus.api.plot.ModeManager
import com.opencreativeplus.api.plot.Plot
import com.opencreativeplus.api.plot.PlotMetadata
import com.opencreativeplus.api.plot.PlotMode
import com.opencreativeplus.api.plot.PlotSettings
import com.opencreativeplus.plugin.inventory.InventoryManager
import com.opencreativeplus.plugin.plot.PlotManagerImpl
import com.opencreativeplus.plugin.registry.CategoryRegistry
import io.mockk.*
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for DEV-mode anti-grief guards:
 * - [DevInventoryGuardListener]: drops, DROP clicks, shift-click into containers, drags
 * - [PlotProtectionListener.onBucketEmpty]: bucket fluids bypass BlockPlaceEvent
 */
class DevAntiGriefTest {

    private lateinit var inventoryManager: InventoryManager
    private lateinit var guard: DevInventoryGuardListener
    private lateinit var modeManager: ModeManager
    private lateinit var plotManager: PlotManagerImpl
    private lateinit var protection: PlotProtectionListener
    private lateinit var player: Player

    @BeforeEach
    fun setup() {
        mockkStatic(Bukkit::class)
        val plugin = mockk<Plugin>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>(relaxed = true)
        val stubbedTask = mockk<BukkitTask>(relaxed = true)
        every { Bukkit.getScheduler() } returns scheduler
        every { scheduler.runTaskLater(any<Plugin>(), any<Runnable>(), any<Long>()) } answers {
            stubbedTask
        }

        player = mockk(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()

        inventoryManager = mockk(relaxed = true)
        guard = DevInventoryGuardListener(inventoryManager, plugin)

        modeManager = mockk(relaxed = true)
        plotManager = mockk(relaxed = true)
        protection = PlotProtectionListener(modeManager, CategoryRegistry(), plotManager, plugin)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun cancelledTracker(event: org.bukkit.event.Cancellable): () -> Boolean {
        var cancelled = false
        every { event.isCancelled } answers { cancelled }
        every { event.isCancelled = any() } answers { cancelled = firstArg() }
        return { cancelled }
    }

    private fun makeClickEvent(click: ClickType, clickedType: InventoryType?, viewType: InventoryType): InventoryClickEvent {
        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.whoClicked } returns player
        every { event.click } returns click
        val clicked = clickedType?.let { mockk<Inventory>(relaxed = true) { every { type } returns it } }
        every { event.clickedInventory } returns clicked
        val view = mockk<Inventory>(relaxed = true)
        every { view.type } returns viewType
        every { event.inventory } returns view
        return event
    }

    private fun makePlot(owner: UUID): Plot = Plot(
        id = UUID.randomUUID(),
        owner = owner,
        name = "Test Plot",
        description = "",
        mainWorldName = "main",
        devWorldName = "dev",
        createdAt = 0L,
        updatedAt = 0L,
        settings = PlotSettings(),
        metadata = PlotMetadata()
    )

    // -------------------------------------------------------------------------
    // 1. Drops — Q key out of hand, pickup, DROP clicks inside inventory
    // -------------------------------------------------------------------------

    @Test
    fun `world item drop is cancelled in DEV mode`() {
        every { inventoryManager.isPlayerInDev(player) } returns true
        val event = mockk<PlayerDropItemEvent>(relaxed = true)
        every { event.player } returns player
        val isCancelled = cancelledTracker(event)

        guard.onDrop(event)

        assertTrue(isCancelled(), "Drops must be blocked in DEV mode")
    }

    @Test
    fun `world item drop is allowed outside DEV mode`() {
        every { inventoryManager.isPlayerInDev(player) } returns false
        val event = mockk<PlayerDropItemEvent>(relaxed = true)
        every { event.player } returns player
        val isCancelled = cancelledTracker(event)

        guard.onDrop(event)

        assertFalse(isCancelled())
    }

    @Test
    fun `DROP and CONTROL_DROP clicks inside inventory are cancelled in DEV mode`() {
        every { inventoryManager.isPlayerInDev(player) } returns true
        for (click in listOf(ClickType.DROP, ClickType.CONTROL_DROP)) {
            val event = makeClickEvent(click, InventoryType.PLAYER, InventoryType.PLAYER)
            val isCancelled = cancelledTracker(event)

            guard.onInventoryClick(event)

            assertTrue(isCancelled(), "$click must be blocked in DEV mode")
        }
    }

    @Test
    fun `item pickup is cancelled in DEV mode`() {
        every { inventoryManager.isPlayerInDev(player) } returns true
        val event = mockk<EntityPickupItemEvent>(relaxed = true)
        every { event.entity } returns player
        val isCancelled = cancelledTracker(event)

        guard.onPickup(event)

        assertTrue(isCancelled(), "Pickup must be blocked in DEV mode")
    }

    // -------------------------------------------------------------------------
    // 2. Shift-click / number-key: player inventory → opened container
    // -------------------------------------------------------------------------

    @Test
    fun `shift-click from player inventory into a chest is cancelled in DEV mode`() {
        every { inventoryManager.isPlayerInDev(player) } returns true
        val event = makeClickEvent(ClickType.SHIFT_LEFT, InventoryType.PLAYER, InventoryType.CHEST)
        val isCancelled = cancelledTracker(event)

        guard.onInventoryClick(event)

        assertTrue(isCancelled(), "Moving items into a container must be blocked in DEV mode")
    }

    @Test
    fun `clicks inside the player inventory alone are not cancelled`() {
        every { inventoryManager.isPlayerInDev(player) } returns true
        val event = makeClickEvent(ClickType.LEFT, InventoryType.PLAYER, InventoryType.PLAYER)
        val isCancelled = cancelledTracker(event)

        guard.onInventoryClick(event)

        assertFalse(isCancelled())
    }

    // -------------------------------------------------------------------------
    // 3. Drags — InventoryDragEvent bypass of the click guard
    // -------------------------------------------------------------------------

    @Test
    fun `drag touching container slots is cancelled in DEV mode`() {
        every { inventoryManager.isPlayerInDev(player) } returns true
        val event = mockk<InventoryDragEvent>(relaxed = true)
        every { event.whoClicked } returns player
        val top = mockk<Inventory>(relaxed = true)
        every { top.type } returns InventoryType.CHEST
        every { top.size } returns 27
        every { event.inventory } returns top
        // raw slots 0..3 = chest slots, 30..31 = player slots
        every { event.rawSlots } returns setOf(0, 1, 2, 3, 30, 31)
        val isCancelled = cancelledTracker(event)

        guard.onInventoryDrag(event)

        assertTrue(isCancelled(), "Cross-inventory drags must be blocked in DEV mode")
    }

    @Test
    fun `drag limited to player slots is not cancelled`() {
        every { inventoryManager.isPlayerInDev(player) } returns true
        val event = mockk<InventoryDragEvent>(relaxed = true)
        every { event.whoClicked } returns player
        val top = mockk<Inventory>(relaxed = true)
        every { top.type } returns InventoryType.CHEST
        every { top.size } returns 27
        every { event.inventory } returns top
        // raw slots >= top size belong to the player inventory
        every { event.rawSlots } returns setOf(30, 31, 32)
        val isCancelled = cancelledTracker(event)

        guard.onInventoryDrag(event)

        assertFalse(isCancelled())
    }

    // -------------------------------------------------------------------------
    // 4. Buckets — fluids bypass BlockPlaceEvent (Req 12.7)
    // -------------------------------------------------------------------------

    private fun makeBucketEvent(bucket: Material): PlayerBucketEmptyEvent {
        val event = mockk<PlayerBucketEmptyEvent>(relaxed = true)
        every { event.player } returns player
        every { event.bucket } returns bucket
        return event
    }

    @Test
    fun `water bucket is always blocked regardless of mode`() {
        val event = makeBucketEvent(Material.WATER_BUCKET)
        val isCancelled = cancelledTracker(event)

        protection.onBucketEmpty(event)

        assertTrue(isCancelled(), "Water buckets must be blocked everywhere (Req 12.7)")
    }

    @Test
    fun `lava bucket is always blocked regardless of mode`() {
        val event = makeBucketEvent(Material.LAVA_BUCKET)
        val isCancelled = cancelledTracker(event)

        protection.onBucketEmpty(event)

        assertTrue(isCancelled(), "Lava buckets must be blocked everywhere (Req 12.7)")
    }

    @Test
    fun `other buckets are blocked in DEV mode`() {
        val plot = makePlot(player.uniqueId)
        every { plotManager.getPlayerPlotSync(player.uniqueId) } returns plot
        every { modeManager.getCurrentMode(player, plot) } returns PlotMode.DEV

        val event = makeBucketEvent(Material.POWDER_SNOW_BUCKET)
        val isCancelled = cancelledTracker(event)

        protection.onBucketEmpty(event)

        assertTrue(isCancelled(), "Buckets must be blocked in DEV mode")
    }

    @Test
    fun `other buckets are allowed outside DEV mode`() {
        val plot = makePlot(player.uniqueId)
        every { plotManager.getPlayerPlotSync(player.uniqueId) } returns plot
        every { modeManager.getCurrentMode(player, plot) } returns PlotMode.BUILD

        val event = makeBucketEvent(Material.POWDER_SNOW_BUCKET)
        val isCancelled = cancelledTracker(event)

        protection.onBucketEmpty(event)

        assertFalse(isCancelled())
    }
}
