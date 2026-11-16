package com.crystalgraphics.net.object;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.serialization.format.CgFiles;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtOps;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * The server's file per player, {@code <world>/crystalgraphics/players/<uuid>.dat}: what a player's per-holder objects
 * save, each type under its name, in CrystalGraphics' binary format. Kept apart from the game's {@code playerdata}, as
 * the game keeps its stats and advancements. Read as an object is made (the player online or not), written for a
 * player whose saved fields changed: as they leave, with the world's save, and as the server stops. Server thread.
 */
final class PlayerFiles {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Players whose saved fields changed since their file was written: none, and a save writes nothing. */
    private static final Set<UUID> DIRTY = new HashSet<>();
    /** Types a player's file drops at its next write: their objects ended (a death), not with the player leaving. */
    private static final Map<UUID, Set<String>> ENDED = new HashMap<>();
    /** Players who joined since the tick began: their saved objects are made at its start. */
    private static final List<UUID> JOINED = new ArrayList<>();

    /** The player {@link #restore} last read the file of, and its contents; dropped at the tick's end. */
    @Nullable
    private static UUID restoring;
    @Nullable
    private static CgNbtCompound restored;

    private PlayerFiles() {
    }

    /** Whether {@code obj} is saved in its player's file: a per-holder type's object on a player. */
    static boolean owns(CgNetObject obj) {
        return obj.kind == CgAnchor.Kind.PLAYER && obj.holderKey != null;
    }

    /** A persisted type's new object on {@code player}, its values from their file. Before it starts. */
    static void restore(CgNetObject obj, UUID player) {
        // a player's types are made together as they join: their file is read once for them
        if (!player.equals(restoring)) {
            restored = read(player);
            restoring = player;
        }
        CgNbtCompound saved = restored;
        if (saved == null || !saved.has(obj.type.name)) return;
        try {
            CgNetObjects.decodeFields(CgNbtOps.INSTANCE, saved.get(obj.type.name), obj);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] {}'s saved {} does not read; it starts from its fallbacks", player, obj.type.name, failed);
        }
    }

    /** Tick end: a player's object whose type saves a changed field marks their file to be written. */
    static void changed(CgNetObject obj) {
        boolean[] saves = obj.type.saves;
        for (int i = 0; i < saves.length; i++) {
            if (saves[i] && CgNetObject.has(obj.dirty, i)) {
                DIRTY.add(obj.player);
                return;
            }
        }
    }

    /** {@code obj}'s player has left: their file is written now, and the object ends with them. */
    static void leaving(CgNetObject obj) {
        obj.leftWithPlayer = true;
        if (DIRTY.contains(obj.player)) write(obj.player);
    }

    /** {@code obj} ended: one that ended without its player leaving (a death) is dropped from their file. */
    static void dropped(CgNetObject obj) {
        if (!obj.type.persisted || obj.leftWithPlayer) return;
        ENDED.computeIfAbsent(obj.player, p -> new HashSet<>()).add(obj.type.name);
        DIRTY.add(obj.player);
    }

    /** {@code player} connected: the objects their file holds are made at the next tick's start. */
    static void joined(UUID player) {
        JOINED.add(player);
    }

    /** Tick start: each player who joined gets the objects their file saved, of types made by asking. */
    static void tickStarted() {
        for (int i = 0; i < JOINED.size(); i++) {
            UUID player = JOINED.get(i);
            CgNbtCompound saved = read(player);
            if (saved == null) continue;
            for (String name : saved.keys()) {
                CgNetType type = CgNetType.named(name);
                // a type on entities is made as the player's entity enters
                if (type != null && type.persisted && type.perHolder && type.entityTypes == null) type.of(player);
            }
        }
        JOINED.clear();
    }

    /** With the world's save and as the server stops: every player's file whose saved fields changed. */
    static void writeAll() {
        if (DIRTY.isEmpty()) return;
        for (UUID player : new ArrayList<>(DIRTY)) write(player);
    }

    /** Tick end: the file kept for restoring is dropped. */
    static void tickEnded() {
        restoring = null;
        restored = null;
    }

    /** Back to nothing pending: the server stopped, or a test ended. */
    static void reset() {
        DIRTY.clear();
        ENDED.clear();
        JOINED.clear();
        tickEnded();
    }

    /** Writes {@code player}'s file now, from their live objects and what it held. */
    static void write(UUID player) {
        Path file = file(player);
        if (file == null) return;
        CgNbtCompound saved = read(player);
        if (saved == null) saved = new CgNbtCompound();
        Set<String> ended = ENDED.remove(player);
        if (ended != null) for (String name : ended) saved.remove(name);
        List<CgNetType> types = CgNetType.SAVED_ON_ENTITIES;
        for (int i = 0; i < types.size(); i++) {
            CgNetType type = types.get(i);
            CgNetObject obj = type.holders.get(player);
            if (obj != null && !obj.isRemoved()) saved.put(type.name, CgNetObjects.encodeFields(CgNbtOps.INSTANCE, obj));
        }
        try {
            CgFiles.write(file, CgNbtCompound.CODEC, saved);
            DIRTY.remove(player);
            if (player.equals(restoring)) restoring = null;
        } catch (IOException failed) {
            LOGGER.error("[cg-net] could not write {}: the player's changes stay pending", file, failed);
        }
    }

    /** {@code player}'s file as it is on disk; null for none. */
    @Nullable
    static CgNbtCompound read(UUID player) {
        Path file = file(player);
        if (file == null) return null;
        try {
            return CgFiles.read(file, CgNbtCompound.CODEC);
        } catch (IOException | RuntimeException failed) {
            LOGGER.error("[cg-net] {} does not read: the player's saved values start from their fallbacks", file, failed);
            return null;
        }
    }

    /** Where {@code player}'s file is; null with no world. */
    @Nullable
    static Path file(UUID player) {
        Path world = CgWorldQueries.saveDirectory(CgNetObjects.server());
        return world == null ? null : world.resolve("crystalgraphics").resolve("players").resolve(player + ".dat");
    }
}
