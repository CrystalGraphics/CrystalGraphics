package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtOps;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgEntitySaves;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * Entities on each side as the engine keeps them: where a host says an entity came or went, the objects on entities
 * ({@link CgNetType.Builder#onEntities}: an entity's properties) made and ended, and the world subsystem that runs their
 * behaviours, on the server each tick in {@code NET_IN}, on a client each frame in {@code BEGIN}, so a property is
 * current before the frame's actors and plays read it.
 *
 * <pre>{@code
 * // a host's client, from its level's entity join and leave events
 * CgNetEntities.entered(entity.getId(), "minecraft:zombie");
 * CgNetEntities.left(entity.getId());
 *
 * // its server
 * CgNetEntities.enteredServer(entity, "minecraft:zombie");
 * CgNetEntities.leftServer(entity);
 *
 * // anything else that keeps something per entity hears them, after the entity's properties are made
 * CgNetEntities.listen(new CgNetEntities.Listener() { ... });
 * }</pre>
 *
 * <ul>
 *   <li>A local type's objects never touch the wire: each side makes and keeps its own, made as the entity enters
 *       and ended as it leaves, or when its host no longer has it (Forge before 1.13 has no leave event, and its host
 *       leaves {@code left} uncalled).</li>
 *   <li>A sent type's object is the server's, made as the entity enters; a client's copy arrives over the wire, and
 *       its frame is run here like a local one's.</li>
 *   <li>Added to its world by the first entity that gets an object; a client's local objects end when its level
 *       changes. Allocates nothing per tick or frame.</li>
 * </ul>
 */
public final class CgNetEntities extends CgWorldSubsystem {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** A player's entity type id, on every version, as the hosts name it: what a net type's {@code entities: players} accepts. */
    public static final String PLAYER = "minecraft:player";

    /** One side's objects of one type. */
    private static final class Side {
        /** Local objects: by entity on the server, by network id on a client. */
        final Map<Object, CgNetObject> byEntity = new HashMap<>();
        final IdTable byId = new IdTable();
        /** Every object of the type on an entity on this side, local or sent: what the frame and tick walk. */
        final List<CgNetObject> all = new ArrayList<>();
    }

    private static Side[] serverSides = new Side[0], clientSides = new Side[0];
    /** Each side's scratch for asking whether an entity is still there. */
    private static final double[] SERVER_POSE = new double[CgEntityQuery.POSE_LENGTH],
            CLIENT_POSE = new double[CgEntityQuery.POSE_LENGTH];

    @Nullable
    private static volatile CgNetEntities clientWorld, serverWorld;

    /**
     * What else keeps something per entity: told after the entity's properties are made, before they end. Called on the
     * side's thread.
     */
    public interface Listener {
        /** Entity {@code id} of {@code type} joined the client's level; a null type is one the host's registry lacks. */
        default void entered(int id, @Nullable String type) {
        }

        default void left(int id) {
        }

        /** {@code entity}, the host's own object, joined one of the server's levels. */
        default void enteredServer(Object entity, @Nullable String type) {
        }

        default void leftServer(Object entity) {
        }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private CgTickFunction run;
    private int levelEpoch;

    private CgNetEntities() {
    }

    // ── Entities coming and going: the host's ───────────────────────────────────────────────────

    /** Has {@code listener} hear every entity come and go from now, on both sides. At init. */
    public static void listen(Listener listener) {
        LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    /** The host's: entity {@code id} of {@code type} joined the client's level. The client thread. */
    public static void entered(int id, @Nullable String type) {
        if (type != null) attach(false, null, id, type);
        for (Listener l : LISTENERS) l.entered(id, type);
    }

    /** The host's: entity {@code id} left the client's level. */
    public static void left(int id) {
        for (Listener l : LISTENERS) l.left(id);
        detach(false, null, id);
    }

    /** The host's: {@code entity}, its own object, of {@code type} joined one of the server's levels. The server thread. */
    public static void enteredServer(Object entity, @Nullable String type) {
        if (type != null) attach(true, entity, -1, type);
        for (Listener l : LISTENERS) l.enteredServer(entity, type);
    }

    /** The host's: {@code entity} left the server's level. */
    public static void leftServer(Object entity) {
        for (Listener l : LISTENERS) l.leftServer(entity);
        detach(true, entity, -1);
    }

    // ── The store ───────────────────────────────────────────────────────────────────────────────

    private static synchronized Side side(CgNetType<?> type, boolean onServer) {
        Side[] sides = onServer ? serverSides : clientSides;
        if (type.index >= sides.length) {
            sides = Arrays.copyOf(sides, type.index + 8);
            if (onServer) serverSides = sides;
            else clientSides = sides;
        }
        Side s = sides[type.index];
        if (s == null) sides[type.index] = s = new Side();
        return s;
    }

    /** Server: a local type's object on {@code entity}, made if {@code make} and absent. */
    @Nullable
    static CgNetObject server(CgNetType<?> type, Object entity, boolean make) {
        Side s = side(type, true);
        CgNetObject obj = s.byEntity.get(entity);
        if (obj != null || !make) return obj;
        ensure(true);
        obj = new CgNetObject(type, true);
        obj.startLocal(entity, CgEntityQueries.idOf(entity));
        s.byEntity.put(entity, obj);
        s.all.add(obj);
        begin(obj, type.server);
        return obj;
    }

    /** A client: a local type's object on entity {@code id}, made if {@code make} and absent. */
    @Nullable
    static CgNetObject client(CgNetType<?> type, int id, boolean make) {
        if (id < 0) return null;
        Side s = side(type, false);
        CgNetObject obj = s.byId.get(id + 1);
        if (obj != null || !make) return obj;
        ensure(false);
        obj = new CgNetObject(type, false);
        obj.startLocal(null, id);
        s.byId.put(id + 1, obj);
        s.all.add(obj);
        begin(obj, type.client);
        return obj;
    }

    private static void begin(CgNetObject obj, @Nullable Function<CgNetObject, CgNetBehaviour> factory) {
        if (factory == null) return;
        try {
            obj.behaviour = factory.apply(obj);
            if (obj.behaviour != null) obj.behaviour.spawned(obj, 0f);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the behaviour of {} failed to start", obj, failed);
            obj.behaviour = null;
        }
    }

    private static void end(CgNetObject obj) {
        obj.endLocal();
        if (obj.behaviour == null) return;
        try {
            obj.behaviour.despawned(obj, true);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the behaviour of {} failed to end", obj, failed);
        }
    }

    /** A client: an object sent on an entity arrived; its frame is run from now. */
    static void clientHeld(CgNetObject obj) {
        if (obj.type.entityTypes != null) side(obj.type, false).all.add(obj);
    }

    /** A client: an object sent on an entity ended. */
    static void clientUnheld(CgNetObject obj) {
        if (obj.type.entityTypes != null) side(obj.type, false).all.remove(obj);
    }

    /** {@code entity} (server) or entity {@code id} (client) of {@code entityType} appeared: its types' objects made. */
    private static void attach(boolean onServer, @Nullable Object entity, int id, String entityType) {
        for (CgNetType<?> type : CgNetType.ON_ENTITIES) {
            if (!type.entityTypes.test(entityType)) continue;
            ensure(onServer);
            if (type.localOnly) {
                if (onServer) server(type, entity, true);
                else client(type, id, true);
            } else if (onServer && CgNetwork.isInstalled()) {
                type.of(entity);
            }
        }
    }

    /** {@code entity} or entity {@code id} left this side: its local objects end; a sent one ends with its anchor. */
    private static void detach(boolean onServer, @Nullable Object entity, int id) {
        for (Side s : onServer ? serverSides : clientSides) {
            if (s == null) continue;
            CgNetObject obj = onServer ? s.byEntity.remove(entity) : id < 0 ? null : s.byId.remove(id + 1);
            if (obj == null) continue;
            s.all.remove(obj);
            // a player's entity leaving (logging out, a dimension, a respawn) keeps what its object saved
            if (onServer && obj.type.persisted && PlayerFiles.owns(obj)) PlayerFiles.leaving(obj);
            end(obj);
        }
    }

    /**
     * Server, each tick: every local object's behaviour ticks (a sent one ticks with the server's objects); one whose
     * entity its host no longer answers for ends.
     */
    static void tickServer() {
        for (Side s : serverSides) {
            if (s == null) continue;
            for (int i = s.all.size() - 1; i >= 0; i--) {
                CgNetObject obj = s.all.get(i);
                if (!obj.localObject) continue;
                if (obj.holder == null || !CgEntityQueries.of(obj.holder).pose(obj.entityId, 1f, SERVER_POSE)) {
                    s.byEntity.remove(obj.holder);
                    s.all.remove(i);
                    end(obj);
                } else if (obj.behaviour != null) {
                    try {
                        obj.behaviour.tick(obj);
                    } catch (RuntimeException failed) {
                        LOGGER.error("[cg-net] the behaviour of {} failed its tick", obj, failed);
                    }
                }
            }
        }
    }

    /**
     * A client, each frame: every object on an entity hears {@code frame}, local or sent; a local one whose entity the
     * client no longer has ends.
     */
    static void frame(float seconds) {
        for (Side s : clientSides) {
            if (s == null) continue;
            for (int i = s.all.size() - 1; i >= 0; i--) {
                CgNetObject obj = s.all.get(i);
                if (obj.localObject && !CgEntityQueries.client().pose(obj.entityId, 1f, CLIENT_POSE)) {
                    s.byId.remove(obj.entityId + 1);
                    s.all.remove(i);
                    end(obj);
                    continue;
                }
                if (obj.behaviour == null) continue;
                try {
                    obj.behaviour.frame(obj, seconds);
                } catch (RuntimeException failed) {
                    LOGGER.error("[cg-net] the behaviour of {} failed its frame", obj, failed);
                }
            }
        }
    }

    // ── Saved with the entity ───────────────────────────────────────────────────────────────

    /**
     * A persisted type's new object on {@code entity}, its values set from what the entity saved; a value that no longer
     * decodes is logged and left at its fallback. Before it starts.
     */
    static void restore(CgNetObject obj, Object entity) {
        // an entity's types are made together as it enters: its compound is read once for them
        if (entity != restoring) {
            restored = CgEntitySaves.read(entity);
            restoring = entity;
        }
        CgNbtCompound saved = restored;
        if (!saved.has(obj.type.name)) return;
        try {
            CgNetObjects.decodeFields(CgNbtOps.INSTANCE, saved.get(obj.type.name), obj);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] {}'s saved {} does not read; it starts from its fallbacks", entity, obj.type.name, failed);
        }
    }

    /** The entity {@link #restore} last read the compound of, and that compound; dropped at the tick's end. */
    @Nullable
    private static Object restoring;
    @Nullable
    private static CgNbtCompound restored;
    /** Entity objects changed since their entity was last saved: none, and a save asks nothing. */
    private static int unsavedOnEntities;

    /** Whether a field its type saves changed this tick: a change to one it does not save costs its entity nothing. */
    private static boolean savedChanged(CgNetObject obj) {
        boolean[] saves = obj.type.saves;
        for (int i = 0; i < saves.length; i++) if (saves[i] && CgNetObject.has(obj.dirty, i)) return true;
        return false;
    }

    /**
     * Server, at the tick's end: an object that changed. One on an entity whose type saves a changed field is written as
     * the entity is next saved, or now where the host has no hook; any other costs nothing here.
     */
    static void changed(CgNetObject obj) {
        if (!obj.type.persisted || obj.kind != CgAnchor.Kind.ENTITY || !savedChanged(obj)) return;
        unsaved(obj);
    }

    private static void unsaved(CgNetObject obj) {
        if (!obj.unsavedOnEntity) {
            obj.unsavedOnEntity = true;
            unsavedOnEntities++;
        }
        if (CgEntitySaves.hooked()) return;
        Object entity = obj.entity();
        if (entity != null) saving(entity);
    }

    /**
     * The game is saving {@code entity}: its persisted objects changed since it was last saved are written into its
     * compound, every type in one read and one write. Server thread; a host calls it through
     * {@code CgEntitySaves.saving}. Costs a check where nothing changed, and allocates nothing where this entity has
     * nothing to write.
     */
    public static void saving(Object entity) {
        // a client saves entities too (a creative pick): the server's objects are the server thread's alone
        if (unsavedOnEntities == 0 || CgNetwork.side() != CgNetwork.Side.SERVER) return;
        CgNbtCompound saved = null;
        List<CgNetType<?>> types = CgNetType.SAVED_ON_ENTITIES;
        for (int i = 0; i < types.size(); i++) {
            CgNetType<?> type = types.get(i);
            CgNetObject obj = type.holders.get(entity);
            if (obj == null || !obj.unsavedOnEntity) continue;
            if (saved == null) saved = CgEntitySaves.read(entity);
            saved.put(type.name, CgNetObjects.encodeFields(CgNbtOps.INSTANCE, obj));
            obj.unsavedOnEntity = false;
            unsavedOnEntities--;
        }
        if (saved == null) return;
        CgEntitySaves.write(entity, saved);
        entitiesWritten++;
        if (restoring == entity) restoring = null;
    }

    /** Entities written into since the server started: what the gauntlet's check reads. */
    static int entitiesWritten;
    /** Objects that ended with a change their entity's save never took. */
    static int endedUnsaved;

    /** Server: an ended object's change no longer waits on its entity's save. */
    static void dropped(CgNetObject obj) {
        if (!obj.unsavedOnEntity) return;
        endedUnsaved++;
        obj.unsavedOnEntity = false;
        unsavedOnEntities--;
    }

    /**
     * Server, as it stops: the game saves every entity after its stopping event, by when the objects are gone, so what
     * changed on entities goes into their compounds now.
     */
    static void stopping(List<CgNetObject> live) {
        for (int i = 0; i < live.size() && unsavedOnEntities > 0; i++) {
            CgNetObject obj = live.get(i);
            Object entity = obj.unsavedOnEntity ? obj.entity() : null;
            if (entity != null) saving(entity);
        }
        unsavedOnEntities = 0;
    }

    /** Server, each tick's end: the compound kept for restoring is dropped. */
    static void tickEnded() {
        restoring = null;
        restored = null;
    }

    /** Back to nothing pending. Tests only, through {@code CgNetObjects.resetForTesting}. */
    static void resetSaves() {
        unsavedOnEntities = 0;
        restoring = null;
        restored = null;
    }

    /** Ends one side's local objects and forgets its list: a server stopping, a client leaving its level. */
    static void clear(boolean onServer) {
        for (Side s : onServer ? serverSides : clientSides) {
            if (s == null) continue;
            for (int i = 0; i < s.all.size(); i++) if (s.all.get(i).localObject) end(s.all.get(i));
            s.byEntity.clear();
            s.byId.clear();
            s.all.clear();
        }
    }

    // ── The subsystem ───────────────────────────────────────────────────────────────────────────

    /** Makes sure the side's world runs them: from the first entity that gets an object. */
    static void ensure(boolean onServer) {
        if (onServer) {
            if (serverWorld == null && CgWorld.serverWorld() != null) CgWorld.serverWorld().add(new CgNetEntities());
        } else if (clientWorld == null) {
            CgWorld.client().add(new CgNetEntities());
        }
    }

    @Override
    protected void initialize() {
        if (world().server()) {
            run = world().schedule().add(CgTickGroup.NET_IN, "crystalgraphics:net.entities", dt -> tickServer()).onServer(true);
            serverWorld = this;
            return;
        }
        levelEpoch = CgWorldQueries.client().levelEpoch();
        run = world().schedule().add(CgTickGroup.BEGIN, "crystalgraphics:net.entities", this::onFrame).whilePaused(true);
        clientWorld = this;
    }

    private void onFrame(float seconds) {
        int now = CgWorldQueries.client().levelEpoch();
        if (now != levelEpoch) {
            levelEpoch = now;
            clear(false);
        }
        frame(seconds);
    }

    @Override
    protected void deinitialize() {
        run.close();
        if (serverWorld == this) {
            serverWorld = null;
            clear(true);
        }
        if (clientWorld == this) {
            clientWorld = null;
            clear(false);
        }
    }
}
