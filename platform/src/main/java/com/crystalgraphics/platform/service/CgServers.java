package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.nio.file.Path;

/**
 * The host's own server: whether one is running, whether it is dedicated, where it keeps its files, and how to stop
 * it. Single-player's integrated server is a server too, so one code path serves both. Players are
 * {@code CgPlayers}'.
 *
 * <pre>{@code
 * if (CgServers.running() && !CgServers.dedicated()) {
 *     Path world = CgServers.directory();      // the save; null until its world has loaded
 * }
 * CgServers.halt();                            // asks; the server stops on its own thread
 * }</pre>
 *
 * A host, once on both sides, as it registers the platform:
 *
 * <pre>{@code
 * CgPlatform.provide(CgServers.HOST, ServersModern.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>Without a host (the harness, a test) no server runs.</li>
 *   <li>{@link #directory()} is null until the world has loaded: ask when needed, never cache it.</li>
 * </ul>
 */
public final class CgServers {

    /** What a host answers. One class per era, {@code Servers<Era>}. */
    public interface Host {

        /** Whether a server, integrated or dedicated, is up in this process. */
        boolean running();

        /** Whether the running server is a dedicated one; false for none and for an integrated one. */
        boolean dedicated();

        /**
         * Where this server's own files live: the server directory on a dedicated one, the world's save directory on
         * an integrated one. Null with no server, or before a world has loaded.
         */
        Path directory();

        /** The game version this server runs, for a report; {@code "?"} with none. */
        String version();

        /** Asks the running server to stop; nothing with none. */
        void halt();

        Host NONE = new Host() {
            @Override public boolean running() { return false; }
            @Override public boolean dedicated() { return false; }
            @Override public Path directory() { return null; }
            @Override public String version() { return "?"; }
            @Override public void halt() { }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:servers", Host.NONE);

    private CgServers() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Whether a server is up in this process. */
    public static boolean running() {
        return host().running();
    }

    /** Whether the running server is dedicated. */
    public static boolean dedicated() {
        return host().dedicated();
    }

    /** The server's files: its directory when dedicated, the world's save when integrated; null with none. */
    public static Path directory() {
        return host().directory();
    }

    /** The running server's game version, for a report. */
    public static String version() {
        return host().version();
    }

    /** Asks the running server to stop. */
    public static void halt() {
        host().halt();
    }
}
