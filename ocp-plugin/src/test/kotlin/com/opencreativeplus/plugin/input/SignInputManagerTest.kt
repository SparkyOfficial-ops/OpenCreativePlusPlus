package com.opencreativeplus.plugin.input

import com.comphenix.protocol.AsynchronousManager
import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolManager
import com.comphenix.protocol.events.ListeningWhitelist
import com.comphenix.protocol.events.NetworkMarker
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.events.PacketListener
import com.comphenix.protocol.injector.PacketConstructor
import com.comphenix.protocol.injector.netty.WirePacket
import com.comphenix.protocol.utility.MinecraftReflection
import com.comphenix.protocol.utility.MinecraftVersion
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Injects stub classes into MinecraftReflection's CachedPackage so that
 * BukkitConverters.<clinit> can find CraftWorld and related classes without
 * a real CraftBukkit server on the classpath.
 *
 * Must be called AFTER MinecraftReflection is initialized (i.e., after PacketType is first
 * referenced) but BEFORE PacketContainer is first loaded.
 */
private fun injectCraftBukkitStubs() {
    val mrClass = MinecraftReflection::class.java

    // Force MinecraftReflection static init to run (sets CRAFTBUKKIT_PACKAGE)
    runCatching { MinecraftReflection.getCraftBukkitPackage() }

    // Get CRAFTBUKKIT_PACKAGE (may be null or a MockK proxy package)
    val craftBukkitPkgField = mrClass.getDeclaredField("CRAFTBUKKIT_PACKAGE")
    craftBukkitPkgField.isAccessible = true
    val craftBukkitPkg = (craftBukkitPkgField.get(null) as? String) ?: "org.bukkit.craftbukkit.v1_20_R3"

    val classSourceClass = Class.forName("com.comphenix.protocol.utility.ClassSource")
    val cachedPackageClass = Class.forName("com.comphenix.protocol.utility.CachedPackage")
    val ctor = cachedPackageClass.getDeclaredConstructor(String::class.java, classSourceClass)
    ctor.isAccessible = true

    // Create a ClassSource that returns Optional.of(Any::class.java) for any class name,
    // except inventory.CraftItemStack which returns CraftItemStackStub
    val classSource = java.lang.reflect.Proxy.newProxyInstance(
        classSourceClass.classLoader,
        arrayOf(classSourceClass)
    ) { _, _, args ->
        val className = args?.getOrNull(0) as? String ?: ""
        when {
            className.contains("CraftItemStack") ->
                java.util.Optional.of(com.opencreativeplus.test.stubs.CraftItemStackStub::class.java)
            else ->
                java.util.Optional.of(Any::class.java)
        }
    }

    // Replace craftbukkitPackage with our stub-backed one
    val craftbukkitPackageField = mrClass.getDeclaredField("craftbukkitPackage")
    craftbukkitPackageField.isAccessible = true
    val stubCraftPackage = ctor.newInstance(craftBukkitPkg, classSource)
    craftbukkitPackageField.set(null, stubCraftPackage)
    println("DEBUG: craftbukkitPackage set to $stubCraftPackage (class=${stubCraftPackage?.javaClass})")
    println("DEBUG: craftbukkitPackage field value = ${craftbukkitPackageField.get(null)?.javaClass}")

    // Also replace minecraftPackage so getMinecraftClass("server.level.WorldServer") succeeds
    val minecraftPackageField = mrClass.getDeclaredField("minecraftPackage")
    minecraftPackageField.isAccessible = true
    val minecraftPkg = runCatching { MinecraftReflection.getMinecraftPackage() }.getOrElse { "net.minecraft" }
    val stubMinecraftPackage = ctor.newInstance(minecraftPkg, classSource)
    minecraftPackageField.set(null, stubMinecraftPackage)

    // Pre-set asNMSCopy, asCraftMirror, isEmpty in MinecraftReflection to no-op accessors
    // so getMinecraftItemStack() doesn't try to find them via reflection on Any::class.java
    val methodAccessorClass = Class.forName("com.comphenix.protocol.reflect.accessors.MethodAccessor")
    val noOpMethodAccessor = java.lang.reflect.Proxy.newProxyInstance(
        methodAccessorClass.classLoader,
        arrayOf(methodAccessorClass)
    ) { _, method, args ->
        println("DEBUG noOpMethodAccessor.${method.name}(${args?.toList()}) called")
        when (method.name) {
            "invoke" -> null
            "getMethod" -> null
            else -> null
        }
    }
    println("DEBUG: noOpMethodAccessor = $noOpMethodAccessor (class=${noOpMethodAccessor?.javaClass})")
    println("DEBUG: MinecraftReflection classloader = ${mrClass.classLoader}")
    for (fieldName in listOf("asNMSCopy", "asCraftMirror", "isEmpty")) {
        try {
            val field = mrClass.getDeclaredField(fieldName)
            field.isAccessible = true
            println("DEBUG: before set $fieldName = ${field.get(null)}")
            field.set(null, noOpMethodAccessor)
            val afterValue = field.get(null)
            println("DEBUG: after set $fieldName = $afterValue (class=${afterValue?.javaClass})")
        } catch (e: Exception) {
            println("DEBUG: EXCEPTION setting $fieldName: $e")
        }
    }

    // Force WrappedBlockData to load (triggers its static init which sets IBLOCK_DATA = Any::class.java),
    // then override IBLOCK_DATA and BLOCK with stub classes that have the methods NewBlockData needs.
    runCatching {
        // Force WrappedBlockData.<clinit> to run
        Class.forName("com.comphenix.protocol.wrappers.WrappedBlockData")
    }
    runCatching {
        val wrappedBlockDataClass = Class.forName("com.comphenix.protocol.wrappers.WrappedBlockData")
        val iBlockDataField = wrappedBlockDataClass.getDeclaredField("IBLOCK_DATA")
        iBlockDataField.isAccessible = true
        iBlockDataField.set(null, com.opencreativeplus.test.stubs.IBlockDataStub::class.java)

        val blockField = wrappedBlockDataClass.getDeclaredField("BLOCK")
        blockField.isAccessible = true
        blockField.set(null, com.opencreativeplus.test.stubs.IBlockDataStub.BlockStub::class.java)
    }

    // Set FLATTENED = false so NewBlockData.<clinit> returns immediately (skips fuzzy reflection setup).
    // FLATTENED is static final, so we use Unsafe to set it.
    try {
        val wrappedBlockDataClass = Class.forName("com.comphenix.protocol.wrappers.WrappedBlockData")
        val flattenedField = wrappedBlockDataClass.getDeclaredField("FLATTENED")
        flattenedField.isAccessible = true

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val offset = unsafe.staticFieldOffset(flattenedField)
        val base = unsafe.staticFieldBase(flattenedField)
        val before = unsafe.getBoolean(base, offset)
        unsafe.putBoolean(base, offset, false)
        val after = unsafe.getBoolean(base, offset)
        println("DEBUG: FLATTENED changed from $before to $after via Unsafe")
    } catch (e: Exception) {
        println("DEBUG: FLATTENED set FAILED: $e")
    }
    // Force NewBlockData to load (with FLATTENED=false, its static init returns immediately)
    try {
        Class.forName("com.comphenix.protocol.wrappers.WrappedBlockData\$NewBlockData")
        println("DEBUG: NewBlockData loaded successfully")
    } catch (e: Exception) {
        println("DEBUG: NewBlockData load FAILED: $e")
    }
    // Set NewBlockData's MethodAccessor fields to no-op so createNewData(Object) doesn't NPE
    runCatching {
        val newBlockDataClass = Class.forName("com.comphenix.protocol.wrappers.WrappedBlockData\$NewBlockData")
        val methodAccessorClass = Class.forName("com.comphenix.protocol.reflect.accessors.MethodAccessor")
        val noOpAccessor = java.lang.reflect.Proxy.newProxyInstance(
            methodAccessorClass.classLoader,
            arrayOf(methodAccessorClass)
        ) { _, _, _ -> null }
        for (fieldName in listOf("GET_BLOCK_DATA", "GET_BLOCK", "MATERIAL_FROM_BLOCK", "BLOCK_FROM_MATERIAL",
                                  "TO_LEGACY_DATA", "FROM_LEGACY_DATA", "GET_HANDLE")) {
            runCatching {
                val f = newBlockDataClass.getDeclaredField(fieldName)
                f.isAccessible = true
                if (f.get(null) == null) f.set(null, noOpAccessor)
            }
        }
    }
}

/**
 * Minimal hand-written stub for ProtocolManager.
 * Avoids triggering PacketContainer's static initializer (which requires CraftBukkit).
 */
private class FakeProtocolManager : ProtocolManager {
    var createPacketCallCount = 0
    var sendServerPacketCallCount = 0
    var lastSendServerPacketPlayer: Player? = null

    private val packetStub: PacketContainer by lazy {
        mockkClass(PacketContainer::class, relaxed = true)
    }

    override fun addPacketListener(listener: PacketListener) {}
    override fun removePacketListener(listener: PacketListener) {}
    override fun removePacketListeners(plugin: Plugin) {}

    override fun createPacket(type: PacketType): PacketContainer {
        createPacketCallCount++
        // Debug: check asNMSCopy before triggering PacketContainer static init
        val asNMSCopyField = com.comphenix.protocol.utility.MinecraftReflection::class.java.getDeclaredField("asNMSCopy")
        asNMSCopyField.isAccessible = true
        println("DEBUG createPacket: asNMSCopy = ${asNMSCopyField.get(null)?.javaClass}")
        return packetStub
    }

    override fun createPacket(type: PacketType, forceDefaults: Boolean): PacketContainer {
        createPacketCallCount++
        return packetStub
    }

    override fun sendServerPacket(receiver: Player, packet: PacketContainer) {
        sendServerPacketCallCount++
        lastSendServerPacketPlayer = receiver
    }

    override fun sendServerPacket(receiver: Player, packet: PacketContainer, filters: Boolean) {
        sendServerPacketCallCount++
        lastSendServerPacketPlayer = receiver
    }

    override fun sendServerPacket(receiver: Player, packet: PacketContainer, marker: NetworkMarker, filters: Boolean) {
        sendServerPacketCallCount++
        lastSendServerPacketPlayer = receiver
    }

    override fun sendWirePacket(receiver: Player, id: Int, bytes: ByteArray) {}
    override fun sendWirePacket(receiver: Player, packet: WirePacket) {}
    override fun receiveClientPacket(sender: Player, packet: PacketContainer) {}
    override fun receiveClientPacket(sender: Player, packet: PacketContainer, filters: Boolean) {}
    override fun receiveClientPacket(sender: Player, packet: PacketContainer, marker: NetworkMarker, filters: Boolean) {}
    override fun broadcastServerPacket(packet: PacketContainer) {}
    override fun broadcastServerPacket(packet: PacketContainer, entity: Entity, includeTracker: Boolean) {}
    override fun broadcastServerPacket(packet: PacketContainer, origin: Location, maxObservers: Int) {}
    override fun broadcastServerPacket(packet: PacketContainer, players: Collection<Player>) {}
    override fun getPacketListeners(): com.google.common.collect.ImmutableSet<PacketListener> =
        com.google.common.collect.ImmutableSet.of()
    override fun createPacketConstructor(type: PacketType, vararg arguments: Any): PacketConstructor =
        throw UnsupportedOperationException()
    override fun updateEntity(entity: Entity, observers: List<Player>) {}
    override fun getEntityFromID(world: org.bukkit.World, entityId: Int): Entity? = null
    override fun getEntityTrackers(entity: Entity): List<Player> = emptyList()
    override fun getSendingFilterTypes(): Set<PacketType> = emptySet()
    override fun getReceivingFilterTypes(): Set<PacketType> = emptySet()
    override fun getMinecraftVersion(): MinecraftVersion = MinecraftVersion.WILD_UPDATE
    override fun isClosed(): Boolean = false
    override fun getAsynchronousManager(): AsynchronousManager = throw UnsupportedOperationException()
    override fun verifyWhitelist(listener: PacketListener, whitelist: ListeningWhitelist) {}
    override fun getProtocolVersion(player: Player): Int = 0
}

class SignInputManagerTest {

    private lateinit var plugin: Plugin
    private lateinit var fakeProtocolManager: FakeProtocolManager
    private lateinit var player: Player
    private lateinit var manager: SignInputManager
    private val playerId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Set Bukkit.server so PacketType's static init (MinecraftVersion.getCurrentVersion) works.
        // The version string must match the pattern: .*\(.*MC.\s*([a-zA-z0-9\-.]+).*
        val server = mockk<Server>(relaxed = true)
        every { server.version } returns "git-Paper-1.20.1 (MC: 1.20.1)"
        every { server.createBlockData(any<org.bukkit.Material>()) } returns mockk(relaxed = true)
        val serverField = Bukkit::class.java.getDeclaredField("server")
        serverField.isAccessible = true
        serverField.set(null, server)

        plugin = mockk(relaxed = true)
        every { plugin.server } returns server
        every { server.onlinePlayers } returns emptyList()

        fakeProtocolManager = FakeProtocolManager()
        player = mockk(relaxed = true)

        val world = mockk<World>(relaxed = true)
        val location = mockk<Location>(relaxed = true)
        val direction = Vector(0.0, 0.0, 1.0)

        every { player.uniqueId } returns playerId
        every { player.location } returns location
        every { location.clone() } returns location
        every { location.world } returns world
        every { location.direction } returns direction
        every { location.x } returns 0.0
        every { location.y } returns 64.0
        every { location.z } returns 0.0

        // Create SignInputManager first — this triggers PacketType static init.
        manager = SignInputManager(plugin, fakeProtocolManager)

        // Inject CraftBukkit stubs into MinecraftReflection so BukkitConverters.<clinit>
        // can find CraftWorld when PacketContainer is first loaded.
        injectCraftBukkitStubs()
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `session is created when awaitSignInput is called`() = runTest {
        // Launch awaitSignInput — it will suspend waiting for the deferred
        val job = launch(Dispatchers.Unconfined) {
            manager.awaitSignInput(player, "hello")
        }

        // Give the coroutine a chance to reach the suspension point
        yield()

        // Session must exist now
        assertTrue(manager.hasActiveSession(playerId), "Session should be active after awaitSignInput is called")

        // Clean up: cancel via onPlayerQuit
        manager.onPlayerQuit(playerId)
        job.join()
    }

    @Test
    fun `prefill is stored in the session`() = runTest {
        val prefill = "my_prefill_value"

        val job = launch(Dispatchers.Unconfined) {
            manager.awaitSignInput(player, prefill)
        }
        yield()

        // Session exists — prefill was accepted into the session
        assertTrue(manager.hasActiveSession(playerId))

        // Verify that createPacket was called (sign placement + open editor)
        assertTrue(fakeProtocolManager.createPacketCallCount >= 1, "createPacket should be called at least once")
        // Verify that sendServerPacket was called with the correct player
        assertTrue(fakeProtocolManager.sendServerPacketCallCount >= 1, "sendServerPacket should be called at least once")
        assertEquals(player, fakeProtocolManager.lastSendServerPacketPlayer)

        manager.onPlayerQuit(playerId)
        job.join()
    }

    @Test
    fun `onPlayerQuit clears the session and completes deferred with null`() = runTest {
        var result: String? = "not-null-sentinel"

        val job = launch(Dispatchers.Unconfined) {
            result = manager.awaitSignInput(player, "test")
        }
        yield()

        assertTrue(manager.hasActiveSession(playerId), "Session should exist before quit")

        // Simulate disconnect
        manager.onPlayerQuit(playerId)
        job.join()

        assertFalse(manager.hasActiveSession(playerId), "Session should be cleared after quit")
        assertNull(result, "awaitSignInput should return null when player disconnects")
    }
}
