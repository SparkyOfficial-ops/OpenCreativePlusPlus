package com.opencreativeplus.plugin.node.random

import com.opencreativeplus.api.execution.ExecutionContext
import com.opencreativeplus.api.node.IAction
import java.util.logging.Logger
import kotlin.random.Random

/**
 * A single candidate branch for [RandomActionNode].
 *
 * @param action  The [IAction] to execute if this branch is selected.
 * @param weight  Relative probability weight. Must be positive; zero/negative branches are excluded.
 */
data class WeightedAction(val action: IAction, val weight: Double)

private const val MAX_BRANCHES = 64
private val logger: Logger = Logger.getLogger("RandomActionNode")

/**
 * Selects and executes exactly one child [IAction] per invocation, chosen by weighted random
 * selection. Weights are relative and normalised internally at construction time.
 *
 * Params:
 *   - "branches": List<WeightedAction> — candidate branches with relative weights
 *
 * Requirements: 1.1–1.6, 2.1–2.4, 3.1–3.5, 4.1–4.3, 6.1–6.3
 */
class RandomActionNode(
    params: Map<String, Any>,
    private val random: Random = Random.Default
) : IAction {

    override val nodeId = "random_action"
    override val displayName = "Случайное действие"

    // Branches filtered to positive weights only, capped at MAX_BRANCHES
    internal val branches: List<WeightedAction>

    // Cumulative normalised weights (CDF), same length as branches.
    // cdf[i] = sum of normalised weights for branches[0..i]
    private val cdf: DoubleArray

    init {
        @Suppress("UNCHECKED_CAST")
        val rawList = params["branches"] as? List<*> ?: emptyList<Any>()

        // Convert each item to WeightedAction, normalising Int weights to Double
        val converted: List<WeightedAction> = rawList.mapNotNull { item ->
            when (item) {
                is WeightedAction -> {
                    val w = item.weight
                    if (w <= 0.0) null else item
                }
                is Map<*, *> -> {
                    val action = item["action"] as? IAction ?: return@mapNotNull null
                    val rawWeight = item["weight"]
                    val w: Double = when (rawWeight) {
                        is Double -> rawWeight
                        is Int -> rawWeight.toDouble()
                        is Number -> rawWeight.toDouble()
                        else -> 1.0
                    }
                    if (w <= 0.0) null else WeightedAction(action, w)
                }
                else -> null
            }
        }

        // Cap at MAX_BRANCHES, logging a warning if truncation occurs
        val capped: List<WeightedAction> = if (converted.size > MAX_BRANCHES) {
            logger.warning(
                "RandomActionNode: branch list has ${converted.size} entries; " +
                    "only the first $MAX_BRANCHES will be used."
            )
            converted.take(MAX_BRANCHES)
        } else {
            converted
        }

        branches = capped

        // Build cumulative CDF
        if (branches.isEmpty()) {
            cdf = DoubleArray(0)
        } else {
            val totalWeight = branches.sumOf { it.weight }
            cdf = DoubleArray(branches.size)
            var cumulative = 0.0
            for (i in branches.indices) {
                cumulative += branches[i].weight
                cdf[i] = cumulative / totalWeight
            }
        }
    }

    /**
     * Selects exactly one branch via weighted random selection and executes it.
     * If [branches] is empty, returns immediately without executing anything.
     * All exceptions from the selected child action are propagated unchanged.
     *
     * Requirements: 3.1, 3.2, 3.3, 4.1, 6.2, 6.3
     */
    override suspend fun execute(context: ExecutionContext) {
        if (branches.isEmpty()) return

        val r = random.nextDouble()

        // Linear scan for first CDF bucket that exceeds r; fallback to last branch
        var selectedIndex = branches.size - 1
        for (i in cdf.indices) {
            if (cdf[i] > r) {
                selectedIndex = i
                break
            }
        }

        branches[selectedIndex].action.execute(context)
    }

    /**
     * Returns the node's parameters in the format expected by [ASTSerializer]:
     * branches as a [List] of [Map] entries with keys "action" ([IAction]) and "weight" ([Double]).
     *
     * Requirements: 7.1
     */
    override fun getParams(): Map<String, Any> =
        mapOf(
            "branches" to branches.map { mapOf("action" to it.action, "weight" to it.weight) }
        )
}
