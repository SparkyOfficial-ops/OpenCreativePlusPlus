package com.opencreativeplus.plugin.node.visual

import org.bukkit.Particle
import org.bukkit.Sound

/**
 * Human-readable sound names mapped to Bukkit Sound values.
 * Covers ambient, block, entity, item, music, and ui categories.
 * s: 6.4
 */
enum class OcpSound(val bukkitSound: Sound) {
    AMBIENT_CAVE(Sound.AMBIENT_CAVE),
    BLOCK_ANVIL_HIT(Sound.BLOCK_ANVIL_HIT),
    BLOCK_CHEST_OPEN(Sound.BLOCK_CHEST_OPEN),
    BLOCK_CHEST_CLOSE(Sound.BLOCK_CHEST_CLOSE),
    BLOCK_NOTE_BLOCK_PLING(Sound.BLOCK_NOTE_BLOCK_PLING),
    ENTITY_PLAYER_LEVELUP(Sound.ENTITY_PLAYER_LEVELUP),
    ENTITY_PLAYER_DEATH(Sound.ENTITY_PLAYER_DEATH),
    ENTITY_GENERIC_EXPLODE(Sound.ENTITY_GENERIC_EXPLODE),
    ENTITY_ITEM_PICKUP(Sound.ENTITY_ITEM_PICKUP),
    ENTITY_VILLAGER_YES(Sound.ENTITY_VILLAGER_YES),
    ENTITY_VILLAGER_NO(Sound.ENTITY_VILLAGER_NO),
    ENTITY_ARROW_HIT_PLAYER(Sound.ENTITY_ARROW_HIT_PLAYER),
    ENTITY_EXPERIENCE_ORB_PICKUP(Sound.ENTITY_EXPERIENCE_ORB_PICKUP),
    ITEM_ARMOR_EQUIP_DIAMOND(Sound.ITEM_ARMOR_EQUIP_DIAMOND),
    MUSIC_GAME(Sound.MUSIC_GAME),
    UI_BUTTON_CLICK(Sound.UI_BUTTON_CLICK),
    UI_TOAST_CHALLENGE_COMPLETE(Sound.UI_TOAST_CHALLENGE_COMPLETE);

    companion object {
        fun fromName(name: String): OcpSound? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Human-readable particle names mapped to Bukkit Particle values.
 * s: 6.5
 */
enum class OcpParticle(val bukkitParticle: Particle) {
    FLAME(Particle.FLAME),
    HEART(Particle.HEART),
    EXPLOSION_LARGE(Particle.EXPLOSION_LARGE),
    EXPLOSION_NORMAL(Particle.EXPLOSION_NORMAL),
    REDSTONE(Particle.REDSTONE),
    ELECTRIC_SPARK(Particle.ELECTRIC_SPARK),
    SMOKE_NORMAL(Particle.SMOKE_NORMAL),
    SMOKE_LARGE(Particle.SMOKE_LARGE),
    WATER_SPLASH(Particle.WATER_SPLASH),
    WATER_BUBBLE(Particle.WATER_BUBBLE),
    CRIT(Particle.CRIT),
    MAGIC_CRIT(Particle.CRIT_MAGIC),
    ENCHANTMENT_TABLE(Particle.ENCHANTMENT_TABLE),
    PORTAL(Particle.PORTAL),
    END_ROD(Particle.END_ROD),
    SNOWBALL(Particle.SNOWBALL),
    FIREWORKS_SPARK(Particle.FIREWORKS_SPARK),
    VILLAGER_HAPPY(Particle.VILLAGER_HAPPY),
    VILLAGER_ANGRY(Particle.VILLAGER_ANGRY),
    TOTEM(Particle.TOTEM);

    companion object {
        fun fromName(name: String): OcpParticle? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
