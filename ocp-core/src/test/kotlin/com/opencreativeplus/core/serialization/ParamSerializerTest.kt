package com.opencreativeplus.core.serialization

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Unit tests for [ParamSerializer] — fix checks and preservation.
 *
 * 9.4 save(Int 42) → load returns Int 42
 * 9.5 save(Boolean true) → load returns Boolean true
 * 9.6 save(String "hello") → load returns String "hello"
 */
class ParamSerializerTest : FreeSpec({

    fun makePdcEnv(): Pair<ParamSerializer, Block> {
        val store = mutableMapOf<NamespacedKey, Any>()
        val keyCache = mutableMapOf<String, NamespacedKey>()

        fun keyFor(name: String): NamespacedKey =
            keyCache.getOrPut(name) { mockk<NamespacedKey>() }

        val pdc = mockk<PersistentDataContainer>()

        every { pdc.set(any(), any<PersistentDataType<Any, Any>>(), any()) } answers {
            store[firstArg<NamespacedKey>()] = thirdArg()
        }
        every { pdc.get(any(), PersistentDataType.STRING) } answers {
            store[firstArg<NamespacedKey>()] as? String
        }
        every { pdc.get(any(), PersistentDataType.INTEGER) } answers {
            store[firstArg<NamespacedKey>()] as? Int
        }
        every { pdc.get(any(), PersistentDataType.DOUBLE) } answers {
            store[firstArg<NamespacedKey>()] as? Double
        }
        every { pdc.get(any(), PersistentDataType.BYTE) } answers {
            store[firstArg<NamespacedKey>()] as? Byte
        }

        val tileState = mockk<TileState>()
        every { tileState.persistentDataContainer } returns pdc
        every { tileState.update() } returns true
        every { tileState.update(any(), any()) } returns true

        val block = mockk<Block>()
        every { block.state } returns tileState

        val serializer = ParamSerializer(::keyFor)
        return serializer to block
    }

    // 9.4 — fix check: Int round-trip preserves type
    "save(Int 42) then load returns Int 42" {
        val (serializer, block) = makePdcEnv()
        serializer.save(block, "count", 42)
        val result = serializer.load(block, "count")
        result shouldBe 42
        (result is Int) shouldBe true
    }

    // 9.5 — fix check: Boolean round-trip preserves type
    "save(Boolean true) then load returns Boolean true" {
        val (serializer, block) = makePdcEnv()
        serializer.save(block, "flag", true)
        val result = serializer.load(block, "flag")
        result shouldBe true
        (result is Boolean) shouldBe true
    }

    // 9.6 — preservation: String round-trip unchanged
    "save(String \"hello\") then load returns String \"hello\"" {
        val (serializer, block) = makePdcEnv()
        serializer.save(block, "msg", "hello")
        val result = serializer.load(block, "msg")
        result shouldBe "hello"
        (result is String) shouldBe true
    }
})
