package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * The client's game session: whether the game can be driven, whether a world is loaded, getting into one, and ending
 * the game. What an unattended run (the engine's gauntlet) needs of a host that a person would do with the mouse.
 *
 * <pre>{@code
 * if (CgGameSession.ready() && !CgGameSession.inWorld()) CgGameSession.enterWorld(null);   // the first save, or a made one
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
         * save on disk, else a new flat creative world ({@link #makeSave} where the era's own API cannot make one).
         *
         * @return whether a world is on its way; false while the game cannot take the request yet
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

    /** 1.13.2's data version: the oldest a modern host loads, which every later one upgrades through its own fixers. */
    private static final int DATA_VERSION = 1631;

    /**
     * Makes a save under {@code saves} for a host whose era cannot make one through its own API: {@link #MADE_SAVE}, a
     * creative superflat world with commands, written at 1.13.2's format. Every modern version upgrades it as it loads,
     * and its level makes its spawn on first load. An older release opens without a prompt, except where the version's
     * file fixer has work for it (26.3): Minecraft then asks twice, so the host runs the fixer on this save itself first.
     *
     * <pre>{@code
     * String name = CgGameSession.saveToEnter(saves, save);
     * if (name == null) name = CgGameSession.makeSave(saves);
     * }</pre>
     *
     * @return the save's folder name
     * @throws IllegalStateException when the folder cannot be written
     */
    public static String makeSave(Path saves) {
        Path dir = saves.resolve(MADE_SAVE);
        try {
            Files.createDirectories(dir);
            try (OutputStream file = Files.newOutputStream(dir.resolve("level.dat"));
                 DataOutputStream nbt = new DataOutputStream(new GZIPOutputStream(file))) {
                compound(nbt, "");
                compound(nbt, "Data");
                integer(nbt, "DataVersion", DATA_VERSION);
                integer(nbt, "version", 19133);                   // Anvil
                string(nbt, "LevelName", MADE_SAVE);
                string(nbt, "generatorName", "flat");
                integer(nbt, "generatorVersion", 0);
                compound(nbt, "generatorOptions");                // empty: the classic flat layers
                end(nbt);
                bool(nbt, "MapFeatures", false);
                longValue(nbt, "RandomSeed", 0L);
                integer(nbt, "GameType", 1);                      // creative
                bool(nbt, "allowCommands", true);
                bool(nbt, "hardcore", false);
                bool(nbt, "initialized", false);                  // the level places its spawn on first load
                longValue(nbt, "Time", 0L);
                longValue(nbt, "DayTime", 6000L);                 // noon
                compound(nbt, "Version");
                integer(nbt, "Id", DATA_VERSION);
                string(nbt, "Name", "1.13.2");
                bool(nbt, "Snapshot", false);
                end(nbt);
                end(nbt);
                end(nbt);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not make a save under " + saves, e);
        }
        return MADE_SAVE;
    }

    // NBT, as level.dat holds it: a tag's type, its name in modified UTF-8, its payload
    private static void compound(DataOutputStream out, String name) throws IOException {
        out.writeByte(10);
        out.writeUTF(name);
    }

    private static void end(DataOutputStream out) throws IOException {
        out.writeByte(0);
    }

    private static void bool(DataOutputStream out, String name, boolean value) throws IOException {
        out.writeByte(1);
        out.writeUTF(name);
        out.writeByte(value ? 1 : 0);
    }

    private static void integer(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void longValue(DataOutputStream out, String name, long value) throws IOException {
        out.writeByte(4);
        out.writeUTF(name);
        out.writeLong(value);
    }

    private static void string(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        out.writeUTF(name);
        out.writeUTF(value);
    }
}
