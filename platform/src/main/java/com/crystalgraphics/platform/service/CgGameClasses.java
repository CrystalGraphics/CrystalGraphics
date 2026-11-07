package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.io.IOException;

/**
 * The game's own classes as they run: its version, a class's bytes after every transformer and mixin, and what the
 * runtime calls a class its jar stores under another name. What a tool reading the game (a script editor resolving
 * Minecraft's types) asks.
 *
 * <pre>{@code
 * String version = CgGameClasses.minecraftVersion();                          // "1.20.1"; "" with no game
 * byte[] level = CgGameClasses.bytes("net/minecraft/world/level/Level");     // null where there is no such class
 * String name = CgGameClasses.runtimeName("ahb");                              // 1.7.10: "net/minecraft/world/World"
 * }</pre>
 *
 * <ul>
 *   <li>Names are internal ({@code a/b/C}), both ways.</li>
 *   <li>Both sides: a dedicated server answers too. Absent ({@link #present()} false: the harness, a test), the version
 *       is {@code ""}, no class has bytes, and every name is its own.</li>
 *   <li>Bytes are read, not loaded: asking never initialises a class.</li>
 * </ul>
 */
public final class CgGameClasses {

    /** What a host answers. One class per era: {@code GameClassesModern}, {@code GameClassesLegacy}, {@code GameClasses1710}. */
    public interface Host {
        /** The running Minecraft's version, as Mojang names it: {@code 1.7.10}, {@code 1.20.1}, {@code 26.2}. */
        String minecraftVersion();

        /** {@code internalName}'s bytes as the game runs it, or null when the game has no such class. */
        byte[] bytes(String internalName) throws IOException;

        /** What the runtime calls a class its jar stores as {@code onDiskName}: itself, except where a loader renames. */
        default String runtimeName(String onDiskName) { return onDiskName; }

        /** The class file as its jar stores it, before any transformer, or null where the loader exposes none. */
        default byte[] fileBytes(String internalName) throws IOException { return null; }

        Host NONE = new Host() {
            @Override public String minecraftVersion() { return ""; }
            @Override public byte[] bytes(String internalName) { return null; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:game_classes", Host.NONE);

    private CgGameClasses() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Whether a game answers: false in the harness and in tests. */
    public static boolean present() {
        return host() != Host.NONE;
    }

    /** The running Minecraft's version, or {@code ""} with no game. */
    public static String minecraftVersion() {
        return host().minecraftVersion();
    }

    /** A class's bytes as the game runs it, or null. */
    public static byte[] bytes(String internalName) throws IOException {
        return host().bytes(internalName);
    }

    /**
     * The class file before any transformer or mixin, or null: only LaunchWrapper (1.7.10, Forge 1.8.9-1.12.2) hands
     * these out. Diffed against {@link #bytes} it shows what the transformers added.
     */
    public static byte[] fileBytes(String internalName) throws IOException {
        return host().fileBytes(internalName);
    }

    /** What the runtime calls a class its jar stores as {@code onDiskName}. */
    public static String runtimeName(String onDiskName) {
        return host().runtimeName(onDiskName);
    }
}
