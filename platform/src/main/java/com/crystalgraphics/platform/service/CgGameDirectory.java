package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The host's directories: the game directory, where its {@code config/} folder lives, and the directory of the world
 * that is loaded.
 *
 * <pre>{@code
 * Path config = CgGameDirectory.game().resolve("config");
 * Path save = CgGameDirectory.world();                       // null with no world loaded; never cached
 *
 * // a host, once, on a client
 * CgPlatform.provide(CgGameDirectory.HOST, new GameDirectoryModern());
 * }</pre>
 *
 * <ul>
 *   <li>Without a host (a dedicated server, the harness, a test) {@link #game()} is the working directory, which
 *       every launcher sets to the game directory and a server runs in, and {@link #world()} is null.</li>
 *   <li>{@link #world()} is the integrated server's world: null on a remote server and until the world has loaded.</li>
 *   <li>Asked when a file is opened, never cached by the caller, so the answer may only exist after registration.</li>
 * </ul>
 */
public final class CgGameDirectory {

    /** What a host answers. One class per era, {@code GameDirectory<Era>}. */
    public interface Host {

        /** The game directory. */
        Path game();

        /** The loaded world's save directory while this process runs its server, else null. */
        Path world();

        Host NONE = new Host() {
            @Override public Path game() { return Paths.get("").toAbsolutePath(); }
            @Override public Path world() { return null; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:game_directory", Host.NONE);

    // Kept once a real host answers: one asked before the host provides it gets NONE's answer, uncached
    private static volatile Host host;

    private CgGameDirectory() {
    }

    private static Host host() {
        Host h = host;
        if (h != null) return h;
        h = CgPlatform.get(HOST);
        if (h != Host.NONE) host = h;
        return h;
    }

    /** Drops the cached host, for a test that provides the slot and clears it again. */
    public static void forgetForTesting() {
        host = null;
    }

    /** The game directory: the host's, or the working directory. */
    public static Path game() {
        return host().game();
    }

    /** The loaded world's save directory, or null. */
    public static Path world() {
        return host().world();
    }
}
