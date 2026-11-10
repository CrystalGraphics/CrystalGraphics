package com.crystalgraphics.platform.command;

/**
 * Whether Brigadier is on this process's classpath: Minecraft 1.13.2 and later and the harness have it, 1.7.10 and
 * Forge 1.8.9-1.12.2 do not. Asked before any class that names a Brigadier type is touched, which is why it names none.
 */
public final class CgBrigadier {

    private static final boolean PRESENT = probe();

    private CgBrigadier() {
    }

    public static boolean present() {
        return PRESENT;
    }

    private static boolean probe() {
        try {
            Class.forName("com.mojang.brigadier.CommandDispatcher", false, CgBrigadier.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
