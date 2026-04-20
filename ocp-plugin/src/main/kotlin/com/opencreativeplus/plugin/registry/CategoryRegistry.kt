package com.opencreativeplus.plugin.registry

import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

/**
 * Defines the six node categories, each mapped to a unique Category_Block material
 * and a human-readable Russian label.
 *
 * Requirements: 1.1, 1.2
 */
enum class NodeCategory(
    val material: Material,
    val russianLabel: String
) {
    PLAYER_EVENT(Material.DIAMOND_BLOCK,  "Событие игрока"),
    PLAYER_ACTION(Material.COBBLESTONE,   "Действие игрока"),
    CONDITION(Material.OAK_PLANKS,        "Условие"),
    GAME_ACTION(Material.STONE_BRICKS,    "Игровое действие"),
    SET_VARIABLE(Material.GOLD_BLOCK,     "Установить переменную"),
    CONTROL_FLOW(Material.IRON_BLOCK,     "Управление потоком")
}

/**
 * Describes a single action that can be placed in the Coding_Zone.
 *
 * @param id            Unique string identifier (e.g. "send_message"). Must not be blank.
 * @param displayName   Human-readable name shown in the Action_Selector_GUI and on the sign.
 * @param icon          Material used as the icon in the GUI inventory.
 * @param category      The NodeCategory this action belongs to.
 * @param expectedParams Ordered list of parameter names consumed from the parameter chest.
 *
 * Requirements: 1.3
 */
data class ActionDescriptor(
    val id: String,
    val displayName: String,
    val icon: Material,
    val category: NodeCategory,
    val expectedParams: List<String> = emptyList()
)

/**
 * Registry that stores [ActionDescriptor]s and provides lookups by category or id.
 *
 * Registration rules (Requirements 1.4, 1.5):
 * - Throws [IllegalArgumentException] if [ActionDescriptor.id] is blank.
 * - Throws [IllegalArgumentException] if an [ActionDescriptor] with the same id is already registered.
 */
class CategoryRegistry {

    private val byId = ConcurrentHashMap<String, ActionDescriptor>()

    /**
     * Registers an [ActionDescriptor].
     *
     * @throws IllegalArgumentException if [descriptor.id] is blank or already registered.
     */
    fun register(descriptor: ActionDescriptor) {
        require(descriptor.id.isNotBlank()) {
            "ActionDescriptor id must not be blank"
        }
        require(!byId.containsKey(descriptor.id)) {
            "ActionDescriptor with id '${descriptor.id}' is already registered"
        }
        byId[descriptor.id] = descriptor
    }

    /**
     * Returns all descriptors belonging to the given [category], in registration order.
     *
     * Requirements: 1.3
     */
    fun getDescriptors(category: NodeCategory): List<ActionDescriptor> =
        byId.values.filter { it.category == category }

    /**
     * Returns the descriptor with the given [id], or null if not registered.
     */
    fun getDescriptorById(id: String): ActionDescriptor? = byId[id]

    /**
     * Returns the [NodeCategory] whose [NodeCategory.material] equals [material], or null.
     *
     * Requirements: 1.2
     */
    fun getCategoryForMaterial(material: Material): NodeCategory? =
        NodeCategory.entries.firstOrNull { it.material == material }

    /**
     * Returns true if [material] is the Category_Block material of any [NodeCategory].
     *
     * Requirements: 1.2
     */
    fun isCategoryMaterial(material: Material): Boolean =
        NodeCategory.entries.any { it.material == material }

    /**
     * Returns the set of all Category_Block materials (one per [NodeCategory]).
     *
     * Requirements: 1.2
     */
    fun allCategoryMaterials(): Set<Material> =
        NodeCategory.entries.map { it.material }.toSet()
}
