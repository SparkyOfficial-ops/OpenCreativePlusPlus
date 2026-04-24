package com.opencreativeplus.plugin.node

import com.opencreativeplus.plugin.node.action.WaitAction
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.negativeInt
import io.kotest.property.forAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask

/**
 * Property 18: WaitAction negative ticks
 *
 * For any negative integer t, calling delayTicks(t) must throw IllegalArgumentException.
 *
 * Validates: Requirements 11.4
 */
class WaitActionNegativeTicksPropertyTest : StringSpec({

    "Property 18: delayTicks throws IllegalArgumentException for any negative tick value" {
        val task = mockk<BukkitTask>(relaxed = true)
        val scheduler = mockk<BukkitScheduler>()
        val server = mockk<Server>()
        val plugin = mockk<Plugin>()
        every { plugin.server } returns server
        every { server.scheduler } returns scheduler

        forAll(25, Arb.negativeInt()) { ticks ->
            val action = WaitAction(emptyMap(), plugin)
            var threw = false
            try {
                runBlocking { action.delayTicks(ticks) }
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            threw
        }
    }
})
