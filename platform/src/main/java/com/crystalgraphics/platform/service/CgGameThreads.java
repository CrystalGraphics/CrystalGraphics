package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * How to reach the game's own threads: the client thread, and the integrated server's. Work for them goes through
 * {@code CgHostThread}, which asks this.
 *
 * <pre>{@code
 * CgHostThread.SERVER.run(() -> level.setBlock(pos, state));         // core; the host's thread does the rest
 *
 * // a host, once, on a client
 * CgPlatform.provide(CgGameThreads.HOST, new GameThreadsModern());
 * }</pre>
 *
 * <ul>
 *   <li>A host with no queue of its own for a thread answers null for it: a client thread that is the one frames are
 *       drawn on, or a version without an integrated server's task queue.</li>
 *   <li>Without a host (the harness, a test) both are null.</li>
 * </ul>
 */
public final class CgGameThreads {

    /** How a host reaches one of its threads. */
    public interface Binding {

        /** Runs {@code work} on the thread, later; never on the caller's. */
        void execute(Runnable work);

        /** Whether the calling thread is this one. */
        boolean isCurrent();

        /** Whether the thread exists now: a server is running, a client is up. */
        default boolean isAvailable() {
            return true;
        }
    }

    /** What a host answers. One class per era, {@code GameThreads<Era>}. */
    public interface Host {

        /** The game's client thread, or null where it is the one frames are drawn on. */
        Binding client();

        /** The integrated server's thread, or null where the host cannot reach it. */
        Binding server();

        Host NONE = new Host() {
            @Override public Binding client() { return null; }
            @Override public Binding server() { return null; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:game_threads", Host.NONE);

    private CgGameThreads() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** The client thread's binding, or null. */
    public static Binding client() {
        return host().client();
    }

    /** The integrated server's binding, or null. */
    public static Binding server() {
        return host().server();
    }
}
