package com.crystalgraphics.net.object;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.platform.service.CgStorageLayout;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * The server's files per player, {@code <world>/<layout>/players/<uuid>.jsonc}: what a player's per-holder objects
 * save, each type under its name ({@link SaveFiles}: JSONC, a person's comments kept). One file per player and storage
 * layout ({@link CgNetType#storage}; {@code crystalgraphics} unless a namespace names its own), kept apart from the
 * game's {@code playerdata} as the game keeps its stats and advancements.
 *
 * <p>A file is read once while it is in use: as its player joins, or at the first {@code of(uuid)} while they are away.
 * Its contents stay in memory, types the server does not know included (a mod removed), and are written from there for a
 * file whose saved fields changed: as the player leaves, with the world's save, as the server stops. An object that
 * ends while its player goes on (they left, changed dimension, or respawned) leaves its values in the file; one ended
 * at their death without {@code copyOnDeath()} leaves it. Server thread.</p>
 */
final class PlayerFiles {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** One player's file in one layout. */
    record File(UUID player, CgStorageLayout layout) {
    }

    /** Files in use, as read and since changed: each type's fields by type name. */
    private static final Map<File, Map<String, Object>> OPEN = new HashMap<>();
    /** Files whose saved fields changed since they were written: none, and a save writes nothing. */
    private static final Set<File> DIRTY = new HashSet<>();
    /** Players who joined since the tick began: their saved objects are made at its start. */
    private static final List<UUID> JOINED = new ArrayList<>();
    /** Players who left since the tick began: their files are written and closed at its start. */
    private static final List<UUID> LEFT = new ArrayList<>();

    private PlayerFiles() {
    }

    /** Whether {@code obj} is saved in its player's file: a per-holder type's object on a player. */
    static boolean owns(CgNetObject obj) {
        return obj.kind == CgAnchor.Kind.PLAYER && obj.holderKey != null;
    }

    /** A persisted type's new object on {@code player}, its values from their file. Before it starts. */
    static void restore(CgNetObject obj, UUID player) {
        File file = new File(player, CgNetType.storageOf(obj.type.namespace));
        obj.saveFile = file;
        Object saved = open(file).get(obj.type.name);
        if (saved == null) return;
        try {
            CgNetObjects.decodeFields(CgPlainOps.TEXT, saved, obj);
        } catch (RuntimeException failed) {
            Path path = path(file);
            LOGGER.error("[cg-net] {} in {} does not read; it starts from its fallbacks, the file kept as .unreadable",
                    obj.type.name, path, failed);
            if (path != null) SaveFiles.keepCopy(path);
        }
    }

    /** Tick end: a player's object whose type saves a changed field marks its file to be written. */
    static void changed(CgNetObject obj) {
        boolean[] saves = obj.type.saves;
        for (int i = 0; i < saves.length; i++) {
            if (saves[i] && CgNetObject.has(obj.dirty, i)) {
                DIRTY.add(obj.saveFile);
                return;
            }
        }
    }

    /**
     * {@code obj} ends while its player goes on: they left, changed dimension, or their entity was replaced. What it
     * holds stays in their file's contents, written with the rest of their file.
     */
    static void leaving(CgNetObject obj) {
        obj.leftWithPlayer = true;
        File file = obj.saveFile;
        if (file != null && DIRTY.contains(file)) {
            open(file).put(obj.type.name, CgNetObjects.encodeFields(CgPlainOps.TEXT, obj));
        }
    }

    /** {@code player}'s connection closed: their files are written and closed at the next tick's start. */
    static void disconnected(UUID player) {
        LEFT.add(player);
    }

    /** {@code obj} ended: one that ended without its player leaving (a death) is dropped from their file. */
    static void dropped(CgNetObject obj) {
        if (!obj.type.persisted || obj.leftWithPlayer || obj.saveFile == null) return;
        open(obj.saveFile).remove(obj.type.name);
        DIRTY.add(obj.saveFile);
    }

    /** {@code player} connected: the objects their files hold are made at the next tick's start. */
    static void joined(UUID player) {
        JOINED.add(player);
    }

    /**
     * Tick start: each player who left has their files written and closed, and each who joined gets the objects their
     * files saved, of types made by asking.
     */
    static void tickStarted() {
        if (!LEFT.isEmpty()) {
            for (int i = 0; i < LEFT.size(); i++) close(LEFT.get(i));
            LEFT.clear();
        }
        if (JOINED.isEmpty()) return;
        for (int i = 0; i < JOINED.size(); i++) {
            UUID player = JOINED.get(i);
            for (CgStorageLayout layout : CgNetType.storageLayouts()) {
                for (String name : new ArrayList<>(open(new File(player, layout)).keySet())) {
                    CgNetType<?> type = CgNetType.named(name);
                    // a type on entities is made as the player's entity enters
                    if (type != null && type.persisted && type.perHolder && type.entityTypes == null) type.of(player);
                }
            }
        }
        JOINED.clear();
    }

    /** Writes {@code player}'s changed files and forgets them, unless they are back or a write failed. */
    private static void close(UUID player) {
        if (CgPlayers.player(player) != null) return;
        for (File file : new ArrayList<>(OPEN.keySet())) {
            if (!file.player().equals(player)) continue;
            if (DIRTY.contains(file)) write(file);
            if (DIRTY.contains(file) || hasLive(file)) continue;
            OPEN.remove(file);
        }
    }

    /** Whether an object still saves into {@code file}: one made by {@code of(uuid)} while its player is away. */
    private static boolean hasLive(File file) {
        List<CgNetType<?>> types = CgNetType.SAVED_ON_ENTITIES;
        for (int i = 0; i < types.size(); i++) {
            CgNetObject obj = types.get(i).holders.get(file.player());
            if (obj != null && !obj.isRemoved() && file.equals(obj.saveFile) && obj.offline) return true;
        }
        return false;
    }

    /** With the world's save and as the server stops: every file whose saved fields changed. */
    static void writeAll() {
        if (DIRTY.isEmpty()) return;
        for (File file : new ArrayList<>(DIRTY)) write(file);
    }

    /** Back to nothing open or pending: the server stopped, or a test ended. */
    static void reset() {
        OPEN.clear();
        DIRTY.clear();
        JOINED.clear();
        LEFT.clear();
    }

    /** Writes {@code file} now: its player's live objects of its layout over what it held. */
    static void write(File file) {
        Path path = path(file);
        if (path == null) return;
        Map<String, Object> saved = open(file);
        List<CgNetType<?>> types = CgNetType.SAVED_ON_ENTITIES;
        for (int i = 0; i < types.size(); i++) {
            CgNetType<?> type = types.get(i);
            CgNetObject obj = type.holders.get(file.player());
            if (obj != null && !obj.isRemoved() && file.equals(obj.saveFile)) {
                saved.put(type.name, CgNetObjects.encodeFields(CgPlainOps.TEXT, obj));
            }
        }
        if (SaveFiles.write(path, CgCodecs.PLAIN, saved)) DIRTY.remove(file);
    }

    /** {@code file}'s contents, read from disk the first time it is asked for while in use. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> open(File file) {
        Map<String, Object> saved = OPEN.get(file);
        if (saved != null) return saved;
        Path path = path(file);
        Object read = path == null ? null : SaveFiles.read(path, CgCodecs.PLAIN);
        saved = read instanceof Map ? new LinkedHashMap<>((Map<String, Object>) read) : new LinkedHashMap<>();
        OPEN.put(file, saved);
        return saved;
    }

    /** Where {@code file} is; null with no world. */
    @Nullable
    static Path path(File file) {
        Path world = CgWorldQueries.saveDirectory(CgNetObjects.server());
        return world == null ? null
                : file.layout().rootIn(world).resolve("players").resolve(file.player() + SaveFiles.EXTENSION);
    }
}
