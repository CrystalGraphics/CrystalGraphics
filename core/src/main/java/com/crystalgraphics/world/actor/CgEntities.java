package com.crystalgraphics.world.actor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgEntityTags;

/**
 * Where gameplay reaches an entity: {@link #of} answers the one {@link CgEntity} of a host entity on this side, made
 * here and owned here; an actor, where the entity has one, attaches to it and is never asked for it. Carries gameplay's
 * three verbs. Unreal's ability system component, on any entity.
 *
 * <pre>{@code
 * static final CgGameplayTag STUNNED = CgTagRegistry.global().register("mymod:state.stunned");
 *
 * // the server
 * CgEntities.of(zombie).addTag(STUNNED).executeCue(STUN_CUE);
 *
 * // the owner's client: one input, one prediction window, taken or refused together
 * CgEntities.predicted(() -> CgEntities.local().addTag(CHARGING).addCue(CHARGE_CUE));
 * }</pre>
 *
 * <ul>
 *   <li>The server's calls are the truth. The owner's client may change its own entity's tags and add its cues,
 *       shown at once and taken by the server's rule ({@link CgEntityTags#acceptTags}); any other client's call throws,
 *       naming the verb.</li>
 *   <li>A handle lives until its entity leaves. A held reference to one outlives it: check {@link CgEntity#present()}.</li>
 *   <li>Server thread on a server, the client thread on a client.</li>
 * </ul>
 */
public final class CgEntities {

    /** The server's bare handles (an entity with no actor), by the host's object. */
    private static final Map<Object, CgEntity> SERVER = new WeakHashMap<>();
    /** A client's bare handles, by network id. */
    private static final Map<Integer, CgEntity> CLIENT = new HashMap<>();
    /** The prediction window open on this thread, or null. */
    @Nullable
    private static CgPredictionKey window;

    static {
        CgNetEntities.listen(new CgNetEntities.Listener() {
            @Override
            public void left(int id) {
                CgEntity gone = CLIENT.remove(id);
                if (gone != null) CgEntityTags.release(id);
            }

            @Override
            public void leftServer(Object entity) {
                SERVER.remove(entity);
            }
        });
    }

    private CgEntities() {
    }

    /**
     * Server: the handle of {@code entity}, the host's own object.
     *
     * @throws IllegalArgumentException for an object that is no entity
     */
    public static CgEntity of(Object entity) {
        Objects.requireNonNull(entity, "entity");
        int id = CgEntityQueries.idOf(entity);
        if (id < 0) throw new IllegalArgumentException(entity + " is no entity");
        return SERVER.computeIfAbsent(entity, e -> {
            CgEntity bare = new CgEntity(id, "entity");
            bare.serverHost = e;
            bare.tags = CgEntityTags.of(e);
            return bare;
        });
    }

    /** Client: the handle of the entity with network id {@code id}, which the client's level need not have loaded yet. */
    public static CgEntity of(int id) {
        if (id < 0) throw new IllegalArgumentException("entity id " + id);
        return CLIENT.computeIfAbsent(id, i -> {
            CgEntity bare = new CgEntity(i, "entity");
            bare.heldTags = true;
            bare.tags = CgEntityTags.hold(i);
            return bare;
        });
    }

    /**
     * Client: the handle of this client's own player, which follows its respawns and level changes.
     *
     * @throws IllegalStateException with no player in a level
     */
    public static CgEntity local() {
        int id = CgEntityQueries.client().localPlayer();
        if (id < 0) throw new IllegalStateException("no local player: this client is not in a level");
        return of(id);
    }

    /**
     * Runs {@code calls} in one prediction window: every predicted call inside shares one {@link CgPredictionKey}, so
     * the server taking or refusing it takes or refuses them all. Unreal's {@code FScopedPredictionWindow}. Nested, it
     * joins the window around it. Where nothing inside predicts (the server), it just runs.
     *
     * @return the window's key: {@code onRefused} adds what else it should undo
     */
    public static CgPredictionKey predicted(Runnable calls) {
        Objects.requireNonNull(calls, "calls");
        CgPredictionKey outer = window;
        CgPredictionKey key = outer != null ? outer : CgPredictionKey.open();
        window = key;
        try {
            calls.run();
        } finally {
            window = outer;
        }
        return key;
    }

    /** Drops every bare handle and the open window. Tests only. */
    public static void resetForTesting() {
        SERVER.clear();
        CLIENT.clear();
        window = null;
    }

    /** The window's key, or a new one for a call outside any. */
    static CgPredictionKey key() {
        return window != null ? window : CgPredictionKey.open();
    }
}
