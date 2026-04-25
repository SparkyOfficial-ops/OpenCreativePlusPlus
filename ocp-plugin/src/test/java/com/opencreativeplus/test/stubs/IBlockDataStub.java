package com.opencreativeplus.test.stubs;

import org.bukkit.Material;

/**
 * Minimal stub for IBlockData (NMS block state) used in unit tests.
 * Provides the static methods that WrappedBlockData$NewBlockData looks for.
 */
public class IBlockDataStub {
    // Static method: Material getType(Object iBlockData) - for MATERIAL_FROM_BLOCK
    public static Material getType(Object iBlockData) {
        return Material.AIR;
    }

    // Static method: Object fromMaterial(Material material) - for BLOCK_FROM_MATERIAL
    public static Object fromMaterial(Material material) {
        return new IBlockDataStub();
    }

    // Static method: Object fromMaterialAndData(Material material, byte data) - for FROM_LEGACY_DATA
    public static Object fromMaterialAndData(Material material, byte data) {
        return new IBlockDataStub();
    }

    // Static method: int toLegacyData(Object iBlockData) - for TO_LEGACY_DATA
    public static int toLegacyData(Object iBlockData) {
        return 0;
    }

    // Instance method: Object getBlock() - for GET_BLOCK (returns BLOCK type)
    public Object getBlock() {
        return new BlockStub();
    }

    // Instance method: IBlockDataStub getBlockData() - for GET_BLOCK_DATA
    public IBlockDataStub getBlockData() {
        return this;
    }

    // Instance method: IBlockDataStub getHandle() - for GET_HANDLE
    public IBlockDataStub getHandle() {
        return this;
    }

    public static class BlockStub {
        // Instance method returning IBlockDataStub - for GET_BLOCK_DATA in BLOCK
        public IBlockDataStub getBlockData() {
            return new IBlockDataStub();
        }
    }
}
