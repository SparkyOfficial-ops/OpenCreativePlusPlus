package com.opencreativeplus.api.node

/
 * Interface for event nodes that trigger script execution.
 * Events are placed at the start of code lines on blue glass blocks.
 */
interface IEvent : INode {
    /
     * The type of event this node represents (e.g., "player_join", "player_interact")
     */
    val eventType: String
}
