package com.opencreativeplus.core.execution

import com.opencreativeplus.api.execution.ExecutionContext

/**
 * Parses and replaces placeholders in template strings using values from an [ExecutionContext].
 *
 * Supported placeholders:
 * - `%player%`    — name of the player who triggered the execution
 * - `%victim%`    — name of the damaged entity (EntityDamageEvent)
 * - `%damager%`   — name of the damaging entity (EntityDamageEvent)
 * - `%killer%`    — name of the killer (DeathEvent), defaults to "none"
 * - `%online%`    — count of online players on the server
 * - `%loc_x%`     — X coordinate of the primary player's current location
 * - `%loc_y%`     — Y coordinate of the primary player's current location
 * - `%loc_z%`     — Z coordinate of the primary player's current location
 * - `%block_loc%` — location string of the interacted block (InteractEvent)
 * - `%item%`      — held item type name (InteractEvent)
 * - `%var(name)%` — value of variable `name` from local or plot scope; empty string if absent
 */
interface PlaceholderParser {
    /**
     * Replace all known placeholders in [template] with values from [context].
     *
     * All replacements are performed in a single regex pass (Req 6.1).
     *
     * @param template the raw string that may contain placeholders
     * @param context  the current execution context providing runtime values
     * @return the resolved string with all placeholders substituted
     */
    fun parse(template: String, context: ExecutionContext): String
}

/**
 * Default implementation of [PlaceholderParser].
 *
 * All known tokens are matched by a single compiled [Regex] and replaced in one pass,
 * satisfying Requirement 6.1. Missing variables and absent entities resolve to `""`.
 */
class PlaceholderParserImpl : PlaceholderParser {

    /**
     * Single regex that matches every supported placeholder token.
     *
     * Groups:
     * - `%player%`, `%victim%`, `%damager%`, `%killer%`, `%online%`
     * - `%loc_x%`, `%loc_y%`, `%loc_z%`
     * - `%block_loc%`, `%item%`
     * - `%var(<name>)%` — captured group 1 holds the variable name
     */
    private val TOKEN_PATTERN = Regex(
        """%player%|%victim%|%damager%|%killer%|%online%|%loc_x%|%loc_y%|%loc_z%|%block_loc%|%item%|%var\(([^)]+)\)%"""
    )

    override fun parse(template: String, context: ExecutionContext): String {
        return TOKEN_PATTERN.replace(template) { match ->
            when (match.value) {
                "%player%"    -> context.player?.name ?: ""
                "%victim%"    -> context.eventData["victim"]    as? String ?: ""
                "%damager%"   -> context.eventData["damager"]   as? String ?: ""
                "%killer%"    -> context.eventData["killer"]    as? String ?: "none"
                "%online%"    -> context.player?.server?.onlinePlayers?.size?.toString()
                                    ?: context.eventData["online"]?.toString()
                                    ?: ""
                "%loc_x%"     -> context.player?.location?.x?.toString() ?: ""
                "%loc_y%"     -> context.player?.location?.y?.toString() ?: ""
                "%loc_z%"     -> context.player?.location?.z?.toString() ?: ""
                "%block_loc%" -> context.eventData["block_loc"] as? String ?: ""
                "%item%"      -> context.eventData["item"]      as? String ?: ""
                else          -> {
                    // %var(<name>)% — group 1 holds the variable name
                    val varName = match.groupValues[1]
                    context.localScope.get(varName)?.toString()
                        ?: context.plotScope.get(varName)?.toString()
                        ?: context.savedScope.get(varName)?.toString()
                        ?: ""
                }
            }
        }
    }
}
