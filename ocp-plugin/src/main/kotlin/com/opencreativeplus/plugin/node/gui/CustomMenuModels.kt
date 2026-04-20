package com.opencreativeplus.plugin.node.gui

import org.bukkit.inventory.ItemStack
import java.io.Serializable

data class CustomMenuDefinition(
    val name: String,
    val slots: Map<Int, MenuSlotDefinition>
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class MenuSlotDefinition(
    val item: ItemStack,
    val displayName: String,
    val clickScriptName: String?
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
