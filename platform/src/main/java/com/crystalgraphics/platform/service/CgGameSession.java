package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The client's game session: whether the game can be driven, whether a world is loaded, getting into one, and ending
 * the game. What an unattended run (the engine's gauntlet) needs of a host that a person would do with the mouse.
 *
 * <pre>{@code
 * if (CgGameSession.ready() && !CgGameSession.inWorld()) CgGameSession.enterWorld(null);   // the first save, or a new one
 * CgGameSession.quit();                                                                      // the game's own shutdown
 * }</pre>
 *
 * A host, once on a client, as it registers the platform:
 *
 * <pre>{@code
 * CgPlatform.provide(CgGameSession.HOST, GameSessionModern.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>Without a host (a dedicated server, the harness, a test) nothing is ready and no world is entered.</li>
 *   <li>{@link #enterWorld} asks; the world loads over the frames after. Ask again until it answers true.</li>
 *   <li>{@link #quit} runs the game's own shutdown, so the engine's teardown runs with it; never {@code System.exit}.</li>
 * </ul>
 */
public final class CgGameSession {

    /** What a host answers. One class per era, {@code GameSession<Era>}. */
    public interface Host {

        /** Whether the game can be driven: past its own splash and loading overlay, on its title screen or in a world. */
        boolean ready();

        /** Whether a world is loaded on this client. */
        boolean inWorld();

        /**
         * Gets into a world: joining a server the game was pointed at, else the save named {@code save}, else the first
         * save on disk, else (where the era can make one) a new flat creative world.
         *
         * @return whether a world is on its way; false while the game cannot take the request yet
         * @throws IllegalStateException when this era has no save to load and cannot make one
         */
        boolean enterWorld(String save);

        /** Ends the game through its own shutdown. */
        void quit();

        Host NONE = new Host() {
            @Override public boolean ready() { return false; }
            @Override public boolean inWorld() { return false; }
            @Override public boolean enterWorld(String save) { return false; }
            @Override public void quit() { }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:game_session", Host.NONE);

    private CgGameSession() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Whether the game can be driven: past its splash and loading overlay. */
    public static boolean ready() {
        return host().ready();
    }

    /** Whether a world is loaded on this client. */
    public static boolean inWorld() {
        return host().inWorld();
    }

    /** Asks to get into a world; {@code save} null for the first on disk. @see Host#enterWorld */
    public static boolean enterWorld(String save) {
        return host().enterWorld(save);
    }

    /** Ends the game through its own shutdown. */
    public static void quit() {
        host().quit();
    }

    /**
     * The save a host enters: {@code wanted} when given, else the first folder of {@code saves} holding a
     * {@code level.dat}; null when there is none, for a host to make one or refuse. Every era's host asks this, so they
     * agree on which save a run plays.
     */
    public static String saveToEnter(Path saves, String wanted) {
        if (wanted != null) return wanted;
        if (!Files.isDirectory(saves)) return null;
        try (Stream<Path> listed = Files.list(saves)) {
            return listed.filter(save -> Files.isRegularFile(save.resolve("level.dat"))).sorted()
                    .map(save -> save.getFileName().toString()).findFirst().orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The folder name a host gives the world it makes when there is no save. */
    public static final String MADE_SAVE = "crystalgraphics-gauntlet";
}
