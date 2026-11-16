package com.crystalgraphics.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

/**
 * What CrystalGraphics saves with an entity: one compound kept in the entity's own saved data, so it travels with the
 * entity through chunk unloads and dies with it. A persisted per-holder net type on entities saves here
 * ({@code CgNetType.Builder#persisted}), each type under its name. Unreal's {@code CPF_SaveGame} properties, saved with
 * their actor. Server.
 *
 * <pre>{@code
 * CgNbtCompound saved = CgEntitySaves.read(zombie);       // empty where it has none
 * saved.put("mymod:rage", rage);
 * CgEntitySaves.write(zombie, saved);                     // saved when the game saves the entity
 * }</pre>
 *
 * <p>A persisted type's change costs a flag: it is encoded once, as the game saves the entity (a chunk unloading, an
 * autosave), every type of that entity in one read and one write.</p>
 *
 * <ul>
 *   <li>Each era keeps it where its loader keeps a mod's entity data: Forge's and NeoForge's persistent data, legacy
 *       Forge's and 1.7.10's entity data, a field of the entity's on Fabric, saved and loaded with it.</li>
 *   <li>A player's is in their own file, {@code playerdata/<uuid>.dat}, there for every player who ever joined: while
 *       they are away {@link #readOffline} and {@link #writeOffline} reach it.</li>
 *   <li>Without a game (a test, a tool) nothing is kept: {@link #read} answers empty and {@link #write} does nothing.</li>
 * </ul>
 */
public final class CgEntitySaves {

    /** What a host does: hand over and take back an entity's compound, its game's tag converted at the seam. */
    public interface Host {
        /** {@code entity}'s compound, or null where it has none. */
        @Nullable
        CgNbtCompound read(Object entity);

        /** Replaces {@code entity}'s compound: what the game saves with it from now. */
        void write(Object entity, CgNbtCompound data);

        /**
         * Whether the host calls {@link CgEntitySaves#saving} as the game saves each entity, before its own data is
         * written: a change is then written once, at the save. Without, each change is written at the tick it happens.
         */
        boolean savesWhenSaved();

        /**
         * The keys from an entity's saved root to its compound, as the game writes the entity's file
         * ({@code {"ForgeData", "crystalgraphics"}}): how an offline player's is found in {@code playerdata/<uuid>.dat}.
         * Null where the host saves none.
         */
        @Nullable
        String[] savedPath();

        Host NONE = new Host() {
            @Override public CgNbtCompound read(Object entity) { return null; }
            @Override public void write(Object entity, CgNbtCompound data) { }
            @Override public boolean savesWhenSaved() { return false; }
            @Override public String[] savedPath() { return null; }
        };
    }

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:entity_saves", Host.NONE);

    private CgEntitySaves() {
    }

    /** {@code entity}'s compound, a copy to change and {@link #write}; empty where it has none. */
    public static CgNbtCompound read(Object entity) {
        CgNbtCompound saved = CgPlatform.get(HOST).read(entity);
        return saved != null ? saved : new CgNbtCompound();
    }

    /** {@code data} becomes what is saved with {@code entity}. */
    public static void write(Object entity, CgNbtCompound data) {
        CgPlatform.get(HOST).write(entity, data);
    }

    /**
     * A host, as the game starts saving {@code entity} and before its own data is written: what changed on it since its
     * last save is written now. A check and nothing more where nothing changed. Server thread.
     */
    public static void saving(Object entity) {
        CgNetEntities.saving(entity);
    }

    /**
     * An offline player's compound, read from their file in the world's {@code playerdata}; null where they have none
     * (never joined) or the host saves none. Server thread.
     */
    @Nullable
    public static CgNbtCompound readOffline(@Nullable Object server, UUID player) {
        String[] path = CgPlatform.get(HOST).savedPath();
        Path file = playerFile(server, player);
        if (path == null || file == null || !Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            CgNbtTag at = CgNbt.readBinary(in);
            for (String key : path) at = at instanceof CgNbtCompound c && c.has(key) ? c.get(key) : null;
            return at instanceof CgNbtCompound c ? c : null;
        } catch (IOException | RuntimeException failed) {
            LOGGER.error("[crystalgraphics] {} does not read: the player's saved values start from their fallbacks", file, failed);
            return null;
        }
    }

    /**
     * Writes an offline player's compound into their file, the rest of it as it was: through a file beside it, moved
     * over it, as the game writes it. False where they have none (never joined: a file the game would load as the
     * player is never made) or the write failed. Server thread.
     */
    public static boolean writeOffline(@Nullable Object server, UUID player, CgNbtCompound data) {
        String[] path = CgPlatform.get(HOST).savedPath();
        Path file = playerFile(server, player);
        if (path == null || file == null || !Files.isRegularFile(file)) return false;
        try {
            CgNbtCompound root;
            try (InputStream in = Files.newInputStream(file)) {
                root = CgNbt.readBinary(in);
            }
            CgNbtCompound at = root;
            for (int i = 0; i < path.length - 1; i++) {
                CgNbtCompound next = at.has(path[i]) && at.get(path[i]) instanceof CgNbtCompound c ? c : null;
                if (next == null) {
                    next = new CgNbtCompound();
                    at.put(path[i], next);
                }
                at = next;
            }
            at.put(path[path.length - 1], data);
            Path written = file.resolveSibling(file.getFileName() + ".cg-tmp");
            try (OutputStream out = Files.newOutputStream(written)) {
                CgNbt.writeBinary(out, root);
            }
            Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException failed) {
            LOGGER.error("[crystalgraphics] could not write {}: the player's values stay in memory until they join", file, failed);
            return false;
        }
    }

    @Nullable
    private static Path playerFile(@Nullable Object server, UUID player) {
        Path world = CgWorldQueries.saveDirectory(server);
        return world == null ? null : world.resolve("playerdata").resolve(player + ".dat");
    }

    /** Whether the host writes at each entity's save ({@link Host#savesWhenSaved}). */
    public static boolean hooked() {
        return CgPlatform.get(HOST).savesWhenSaved();
    }
}
