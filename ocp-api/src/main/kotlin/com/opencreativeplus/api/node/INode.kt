package com.opencreativeplus.api.node

/**
 * Base interface for all node types in the OpenCreativePlus engine.
 * Nodes represent individual blocks in the visual scripting system.
 */
interface INode {
    /**
     * Unique identifier for this node type
     */
    val nodeId: String
    
    /**
     * Human-readable display name for this node
     */
    val displayName: String
}
