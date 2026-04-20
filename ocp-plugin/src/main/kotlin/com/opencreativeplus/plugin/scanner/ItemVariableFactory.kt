package com.opencreativeplus.plugin.scanner

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Factory for creating and reading item-variable ItemStacks.
 *
 * PDC keys:
 *  - ocp:item_var_type — STRING — "variable" or "location"
 *  - ocp:item_var_name — STRING — the variable/location name
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4
 */
object ItemVariableFactory {

    private val KEY_VAR_TYPE = NamespacedKey("ocp", "item_var_type")
    private val KEY_VAR_NAME = NamespacedKey("ocp", "item_var_name")

    /**
     * Creates an ItemStack representing a variable.
     * PDC: ocp:item_var_type = "variable", ocp:item_var_name = [name]
     * Display name: "Переменная: [name]"
     * Req 9.1, 9.3
     */
    fun createVariable(name: String, icon: Material = Material.PAPER): ItemStack {
        val item = ItemStack(icon)
        val meta = item.itemMeta ?: return item
        meta.displayName(Component.text("Переменная: $name"))
        meta.persistentDataContainer.set(KEY_VAR_TYPE, PersistentDataType.STRING, "variable")
        meta.persistentDataContainer.set(KEY_VAR_NAME, PersistentDataType.STRING, name)
        item.itemMeta = meta
        return item
    }

    /**
     * Creates an ItemStack representing a location.
     * PDC: ocp:item_var_type = "location", ocp:item_var_name = [name]
     * Display name: "Локация: [name]"
     * Req 9.2, 9.4
     */
    fun createLocation(name: String, icon: Material = Material.COMPASS): ItemStack {
        val item = ItemStack(icon)
        val meta = item.itemMeta ?: return item
        meta.displayName(Component.text("Локация: $name"))
        meta.persistentDataContainer.set(KEY_VAR_TYPE, PersistentDataType.STRING, "location")
        meta.persistentDataContainer.set(KEY_VAR_NAME, PersistentDataType.STRING, name)
        item.itemMeta = meta
        return item
    }

    /**
     * Reads the ocp:item_var_type PDC value from the given item.
     * Returns null if the item has no meta or the key is absent.
     */
    fun readVarType(item: ItemStack): String? =
        item.itemMeta?.persistentDataContainer?.get(KEY_VAR_TYPE, PersistentDataType.STRING)

    /**
     * Reads the ocp:item_var_name PDC value from the given item.
     * Returns null if the item has no meta or the key is absent.
     */
    fun readVarName(item: ItemStack): String? =
        item.itemMeta?.persistentDataContainer?.get(KEY_VAR_NAME, PersistentDataType.STRING)
}
