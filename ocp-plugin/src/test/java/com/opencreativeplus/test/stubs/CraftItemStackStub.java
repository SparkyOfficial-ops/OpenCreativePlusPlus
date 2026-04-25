package com.opencreativeplus.test.stubs;

import org.bukkit.inventory.ItemStack;

/**
 * Minimal stub for CraftItemStack used in unit tests.
 * Provides the asNMSCopy method that MinecraftReflection.getMinecraftItemStack() looks for.
 */
public class CraftItemStackStub {
    public static Object asNMSCopy(ItemStack itemStack) {
        return null;
    }

    public static CraftItemStackStub asCraftMirror(Object nmsItemStack) {
        return new CraftItemStackStub();
    }

    public boolean isEmpty() {
        return true;
    }
}
