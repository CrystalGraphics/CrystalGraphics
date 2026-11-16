package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * The objects on entities ({@link CgNetType.Builder#onEntities}), on each side, and the world subsystem that runs
 * their behaviours: on the server each tick in {@code NET_IN}, on a client each frame in {@code BEGIN}, so a property is
 * current before the frame's actors and plays read it.
 *
 * <ul>
 *   <li>A local type's objects never touch the wire: each side makes and keeps its own, made as the entity enters
 *       ({@link CgNetObjects#entityEntered}) and ended as it leaves, or when its host no longer has it (a host with no
 *       leave event).</li>
 *   <li>A sent type's object is the server's, made as the entity enters; a client's copy arrives over the wire, and
 *       its frame is run here like a local one's.</li>
 *   <li>Added to its world by the first entity that gets an object; a client's local objects end when its level
 *       changes. Allocates nothing per tick or frame.</li>
 * </ul>
 */
public final class CgNetEntities extends CgWorldSubsystem {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

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

    private CgTickFunction run;
    private int levelEpoch;

    private CgNetEntities() {
    }

    // ── The store ───────────────────────────────────────────────────────────────────────────────

    private static synchronized Side side(CgNetType type, boolean onServer) {
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
    static CgNetObject server(CgNetType type, Object entity, boolean make) {
        Side s = side(type, true);
        CgNetObject obj = s.byEntity.get(entity);
        if (obj != null || !make) return obj;
        obj = new CgNetObject(type, true);
        obj.startLocal(entity, CgEntityQueries.idOf(entity));
        s.byEntity.put(entity, obj);
        s.all.add(obj);
        begin(obj, type.server);
        return obj;
    }

    /** A client: a local type's object on entity {@code id}, made if {@code make} and absent. */
    @Nullable
    static CgNetObject client(CgNetType type, int id, boolean make) {
        if (id < 0) return null;
        Side s = side(type, false);
        CgNetObject obj = s.byId.get(id + 1);
        if (obj != null || !make) return obj;
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
    static void entered(boolean onServer, @Nullable Object entity, int id, String entityType) {
        for (CgNetType type : CgNetType.REGISTERED) {
            if (type.entityTypes == null || !type.entityTypes.test(entityType)) continue;
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
    static void left(boolean onServer, @Nullable Object entity, int id) {
        for (Side s : onServer ? serverSides : clientSides) {
            if (s == null) continue;
            CgNetObject obj = onServer ? s.byEntity.remove(entity) : id < 0 ? null : s.byId.remove(id + 1);
            if (obj == null) continue;
            s.all.remove(obj);
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
