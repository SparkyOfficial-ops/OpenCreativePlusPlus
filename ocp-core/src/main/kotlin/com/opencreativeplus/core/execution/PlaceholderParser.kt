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
 * - `%block_loc%` — location string of the interacted block (InteractEvent)
 * - `%item%`      — held item type name (InteractEvent)
 * - `%var(name)%` — value of variable `name` from local or plot scope; empty string if absent
 */
interface PlaceholderParser {
    /**
     * Replace all known placeholders in [template] with values from [context].
     *
     * @param template the raw string that may contain placeholders
     * @param context  the current execution context providing runtime values
     * @return the resolved string with all placeholders substituted
     */
    fun parse(template: String, context: ExecutionContext): String
}

/**
 * Default implementation of [PlaceholderParser].
 */
class PlaceholderParserImpl : PlaceholderParser {

    private val VAR_PATTERN = Regex("""%var\(([^)]+)\)%""")

    override fun parse(template: String, context: ExecutionContext): String {
        var result = template
        result = result.replace("%player%",    context.player?.name ?: "")
        result = result.replace("%victim%",    context.eventData["victim"]    as? String ?: "")
        result = result.replace("%damager%",   context.eventData["damager"]   as? String ?: "")
        result = result.replace("%killer%",    context.eventData["killer"]    as? String ?: "none")
        result = result.replace("%block_loc%", context.eventData["block_loc"] as? String ?: "")
        result = result.replace("%item%",      context.eventData["item"]      as? String ?: "")
        result = VAR_PATTERN.replace(result) { match ->
            val varName = match.groupValues[1]
            context.localScope.get(varName)?.toString()
                ?: context.plotScope.get(varName)?.toString()
                ?: ""
        }
        return result
    }
}
