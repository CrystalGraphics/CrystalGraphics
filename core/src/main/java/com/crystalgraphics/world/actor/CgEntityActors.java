package com.crystalgraphics.world.actor;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgEntityTags;
import com.crystalgraphics.world.CgEntityVariables;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Entity actors: which actor plays each entity type, and the actors of a side's entities: the client's, and the
 * server's. A mod registers an actor class by entity type once, on any era and loader; each host tells it which
 * entities enter and leave on each side, and draws an entity with an actor through it instead of its vanilla model.
 * Unreal's actor spawned for a game object on the server and on each client, with its tick.
 *
 * <pre>{@code
 * CgEntityActors.register("mymod:dragon", DragonActor::new);                    // at load
 * CgEntityActors.register("mymod:golem", GolemActor::new).keepVanillaModel();  // drawn as well as the vanilla model
 * CgEntityActors.register("mymod:wyvern", WyvernActor::new).prefab("mymod:wyvern");   // its components an asset
 * }</pre>
 *
 * <p>One entity can be bound alone, beating its type's actor, with no registration by type:</p>
 *
 * <pre>{@code
 * CgEntityActors.Registration me = CgEntityActors.bindLocalPlayer(GokuActor::new);   // the client's own player
 * CgEntityActors.bind(entity.getId(), GolemActor::new);                              // one entity by id
 * me.close();                                                                        // its actor goes, the host's model returns
 * }</pre>
 *
 * <ul>
 *   <li>A binding spawns its actor in the next {@code PLAYS} tick once its entity is present, so it also applies to
 *       an entity already in the level; {@link #bindLocalPlayer} follows the player across respawns and level changes.</li>
 *   <li>An id binding ends with its entity: a new id needs a new binding.</li>
 *   <li>An actor whose {@code onSpawn} throws is not retried: its binding stays and does nothing.</li>
 *   <li>A registration's prefab loads behind the loading screen, with the pipelines its parts draw with; one named
 *       while the game runs loads at its first spawn, held.</li>
 * </ul>
 *
 * <p>What a host does, from its entity events (a harness scene's fake entities do the same):</p>
 *
 * <pre>{@code
 * CgEntityActors.entered(entity.getId(), "mymod:dragon");    // joined the client's level
 * if (CgEntityActors.replacesModel(entity.getId())) ...      // the entity renderer: its actor draws it
 * CgEntityActors.left(entity.getId());                       // left the level, where the host has the event
 * CgEntityActors.enteredServer(entity, "mymod:dragon");     // joined a server level: the server's actor, the same class
 * CgEntityActors.leftServer(entity);
 * }</pre>
 *
 * <ul>
 *   <li>The server's actors tick in {@code GAMEPLAY}, their roots at their entities' poses this tick, and each tick
 *       in {@code NET_OUT}, after the tick's sequences, every actor naming a root-motion body moves its entity
 *       ({@link CgEntityActor#rootMotion}). A client's moves only its own player's entity, each frame once posed.</li>
 *   <li>Bindings ({@link #bind}, {@link #bindLocalPlayer}) are a client's; a server spawns by type alone.</li>
 * </ul>
 *
 * <ul>
 *   <li>Each frame in {@code PLAYS}: every actor's root placed at its entity's pose (interpolated to the frame's
 *       partial tick, read from {@link CgEntityQueries#client()}), then its {@code tick}. Before {@code POSE}, so its
 *       animation reads this frame's changes and everything riding the root follows in {@code ATTACH}.</li>
 *   <li>An actor whose entity the query no longer finds is removed in that tick: a host without a leave event (Forge
 *       before 1.13) loses none, and {@link #left} only removes it a frame sooner.</li>
 *   <li>An entity of a type no actor is registered for is left to its host, as are entities that entered before
 *       their type was registered.</li>
 *   <li>The host's model is suppressed only where its renderer reaches the entity: an inventory's player preview
 *       draws through the same renderer, so an actor's player shows there as its name tag alone.</li>
 *   <li>A new level removes every actor; an actor that throws is removed, logged, and its entity left to its host.</li>
 *   <li>The client world's thread; {@link #register} from any thread.</li>
 * </ul>
 */
public final class CgEntityActors extends CgWorldSubsystem {

    private static final Logger LOGGER = LogManager.getLogger(CgEntityActors.class);
    private static final int TICK = CgTrace.name("actors.tick");
    private static final Map<String, Registration> TYPES = new ConcurrentHashMap<>();
    private static final Live[] NONE = new Live[0];
    private static final int BY_TYPE = Integer.MIN_VALUE, LOCAL_PLAYER = -1;
    private static final List<Registration> BINDINGS = new CopyOnWriteArrayList<>();

    /** The client world's, while it has one: what the host's per-entity questions read without a lookup. */
    @Nullable
    private static CgEntityActors client;
    /** The server world's, while it has one. */
    @Nullable
    private static CgEntityActors server;

    /** An entity type's actor, as registered; closing it unregisters the type (a reload, a scene leaving). */
    public static final class Registration implements AutoCloseable {
        private final String type;
        /** The entity it binds alone: an id or {@link #LOCAL_PLAYER}; {@link #BY_TYPE} for a type's. */
        private final int entityId;
        private final Supplier<? extends CgEntityActor> factory;
        private volatile boolean keepVanillaModel;
        @Nullable
        private volatile String prefab;
        /** Its actor failed to spawn: not tried again. */
        private boolean failed;

        Registration(String type, int entityId, Supplier<? extends CgEntityActor> factory) {
            this.type = type;
            this.entityId = entityId;
            this.factory = factory;
        }

        /** Draws the host's own model as well as the actor: an actor adding to an entity rather than replacing it. */
        public Registration keepVanillaModel() {
            keepVanillaModel = true;
            return this;
        }

        /**
         * Its actors spawn {@code id}, a prefab asset ({@code mymod:wyvern}, the file {@code assets/mymod/prefabs/wyvern.yaml}), on their root before
         * {@code onSpawn}, which reads it as {@code prefab()}; removed with the actor. Unreal's actor Blueprint and its
         * components.
         */
        public Registration prefab(String id) {
            prefab = Objects.requireNonNull(id, "id");
            return this;
        }

        public String type() {
            return type;
        }

        /**
         * A type's registration: its type has no actor from now, and actors already living stay until their entities
         * leave. An entity's binding: its actor is removed now and the host's model is the entity's again.
         */
        @Override
        public void close() {
            if (entityId == BY_TYPE) {
                TYPES.remove(type, this);
                return;
            }
            BINDINGS.remove(this);
            CgEntityActors actors = client;
            if (actors != null) actors.drop(this);
        }
    }

    /** An entity's actor, as it lives. */
    private static final class Live {
        final CgEntity entity;
        final CgEntityActor actor;
        final boolean replacesModel;
        final Registration origin;
        /** The server's: the host's entity, whose level its pose is read from. */
        @Nullable
        final Object host;

        Live(CgEntity entity, CgEntityActor actor, boolean replacesModel, Registration origin, @Nullable Object host) {
            this.entity = entity;
            this.actor = actor;
            this.replacesModel = replacesModel;
            this.origin = origin;
            this.host = host;
        }
    }

    /** In spawn order, as they tick. */
    private final List<Live> order = new ArrayList<>();
    /** The same, by entity id ascending: the host asks once an entity a frame, so a lookup allocates nothing. */
    private int[] ids = new int[0];
    private Live[] byId = NONE;
    private final Quaternionf turn = new Quaternionf();
    private final double[] probe = new double[CgEntityQuery.POSE_LENGTH];
    private CgTickFunction tick, move;
    private int levelEpoch;

    /**
     * {@code factory}'s actor draws every entity of type {@code type} ({@code mymod:dragon}): from the next one to enter.
     *
     * @throws IllegalStateException where the type has an actor already
     */
    public static Registration register(String type, Supplier<? extends CgEntityActor> factory) {
        Registration r = new Registration(Objects.requireNonNull(type, "type"), BY_TYPE, Objects.requireNonNull(factory, "factory"));
        if (TYPES.putIfAbsent(type, r) != null) throw new IllegalStateException("entity type " + type + " has an actor already");
        return r;
    }

    /**
     * {@code factory}'s actor draws entity {@code entityId} alone, in place of its type's actor and the host's model.
     * Spawned in the next tick once the entity is present; closing the registration removes it.
     *
     * @throws IllegalStateException where that entity is bound already
     */
    public static Registration bind(int entityId, Supplier<? extends CgEntityActor> factory) {
        if (entityId < 0) throw new IllegalArgumentException("entity id " + entityId);
        return bindTo(entityId, factory);
    }

    /** {@link #bind} for the client's own player, whichever entity that is now: it follows respawns and level changes. */
    public static Registration bindLocalPlayer(Supplier<? extends CgEntityActor> factory) {
        return bindTo(LOCAL_PLAYER, factory);
    }

    private static synchronized Registration bindTo(int entityId, Supplier<? extends CgEntityActor> factory) {
        Registration r = new Registration("bound", entityId, Objects.requireNonNull(factory, "factory"));
        for (Registration b : BINDINGS) {
            if (b.entityId == entityId) throw new IllegalStateException("that entity has an actor bound already");
        }
        BINDINGS.add(r);
        if (client == null) CgWorld.client().add(new CgEntityActors());
        return r;
    }

    /**
     * Loads every registration's prefab as the load task {@code crystalgraphics:actor-prefabs}, behind the loading
     * screen: each asset to {@code READY} with the systems its effect nodes play, then the pipelines its parts and
     * skeletal meshes draw with, so an actor spawns drawn. The engine's client start names it, after every listener's
     * {@code onLoad}.
     */
    public static void preload(CgLoadPlan plan) {
        List<CgAssetRef<CgPrefab>> refs = new ArrayList<>();
        List<CgAssetRef<?>> systems = new ArrayList<>();
        CgPipelinePrecache.Request[] pipelines = {null};
        boolean[] started = {false, false};
        plan.task("crystalgraphics:actor-prefabs").render(task -> {
            if (!started[0]) {
                started[0] = true;
                for (Registration r : TYPES.values()) if (r.prefab != null) refs.add(prefabRef(r.prefab));
                for (Registration r : BINDINGS) if (r.prefab != null) refs.add(prefabRef(r.prefab));
                if (refs.isEmpty()) return true;
                CgVfxAssets.instance().load(refs, CgLoadPriority.MOVE);
            }
            if (!settled(refs, 0.25f, task)) return false;
            if (!started[1]) {
                started[1] = true;
                // The systems its effect nodes play: loaded behind the screen too, so a node's play waits on nothing
                for (CgAssetRef<CgPrefab> ref : refs) {
                    CgPrefab prefab = ref.loaded();
                    if (prefab == null) continue;
                    for (int n = 0; n < prefab.nodes(); n++) {
                        if (prefab.effect(n) != null) systems.add(CgVfxAssets.ref(CgParticleKinds.SYSTEM, prefab.effect(n).effect()));
                    }
                }
                if (!systems.isEmpty()) CgVfxAssets.instance().load(systems, CgLoadPriority.MOVE);
            }
            if (!settled(systems, 0.5f, task)) return false;
            if (pipelines[0] == null) {
                pipelines[0] = CgPipelinePrecache.request(p -> {
                    for (CgAssetRef<CgPrefab> ref : refs) {
                        CgPrefab prefab = ref.loaded();
                        if (prefab == null) continue;
                        for (int k = 0; k < prefab.firstPart(prefab.nodes()); k++) {
                            for (CgMaterialDefinition m : prefab.drawnMaterials(k)) p.add(CgMaterial.of(m), false);
                        }
                        for (int n = 0; n < prefab.nodes(); n++) {
                            CgPrefab.Skeletal skeletal = prefab.skeletal(n);
                            if (skeletal != null) for (CgMaterialDefinition m : skeletal.materials()) p.add(CgMaterial.of(m), false, true);
                        }
                    }
                });
            }
            if (pipelines[0].ready()) return true;
            task.progress(0.75f);
            task.nextFrame();
            return false;
        }).start();
    }

    /** Whether every one of {@code refs} is ready or failed; else the task waits a frame, {@code share} of its bar per ref. */
    private static boolean settled(List<? extends CgAssetRef<?>> refs, float share, CgLoadTask task) {
        int settled = 0;
        for (CgAssetRef<?> ref : refs) if (ref.ready() || ref.state() == CgAssetState.FAILED) settled++;
        if (settled == refs.size()) return true;
        task.progress(share * settled / refs.size());
        task.nextFrame();
        return false;
    }

    private static CgAssetRef<CgPrefab> prefabRef(String id) {
        return CgVfxAssets.ref(CgBodyKinds.PREFAB, id);
    }

    /** Whether {@code type} has an actor. */
    public static boolean registered(String type) {
        return TYPES.containsKey(type);
    }

    /**
     * The host's: entity {@code id} of {@code type} joined the client's level. Nothing where its type has no actor, or
     * is null (an entity class the host's registry does not hold).
     */
    public static void entered(int id, @Nullable String type) {
        Registration r = type == null ? null : TYPES.get(type);
        if (r == null || bound(id)) return;
        CgEntityActors actors = client;
        if (actors == null) actors = CgWorld.client().add(new CgEntityActors());
        actors.spawn(id, type, r, null);
    }

    /** The host's: entity {@code id} left the client's level. */
    public static void left(int id) {
        CgEntityActors actors = client;
        if (actors != null) actors.despawn(id);
    }

    /**
     * The host's: {@code entity} (its own object) of {@code type} joined one of the server's levels. Nothing where its
     * type has no actor. The server's game thread.
     */
    public static void enteredServer(Object entity, @Nullable String type) {
        Registration r = type == null ? null : TYPES.get(type);
        int id = CgEntityQueries.idOf(entity);
        if (r == null || id < 0) return;
        CgEntityActors actors = server;
        if (actors == null) actors = CgWorld.serverWorld().add(new CgEntityActors());
        actors.spawn(id, type, r, entity);
    }

    /** The host's: {@code entity} left the server's level. */
    public static void leftServer(Object entity) {
        CgEntityActors actors = server;
        int id = CgEntityQueries.idOf(entity);
        if (actors != null && id >= 0) actors.despawn(id);
    }

    /** Entity {@code id}'s actor on the server, or null. */
    @Nullable
    public static CgEntityActor serverActor(int id) {
        CgEntityActors actors = server;
        Live l = actors == null ? null : actors.find(id);
        return l == null ? null : l.actor;
    }

    /**
     * Whether entity {@code id}'s actor draws it in place of its vanilla model: what the host's renderer asks, once for
     * each entity it draws a frame. Stamps {@link CgEntity#hostFrame}.
     */
    public static boolean replacesModel(int id) {
        CgEntityActors actors = client;
        if (actors == null) return false;
        Live l = actors.find(id);
        if (l == null) return false;
        l.entity.hostFrame = actors.world().frames();
        return l.replacesModel;
    }

    /** Entity {@code id}'s actor, or null. */
    @Nullable
    public static CgEntityActor actor(int id) {
        CgEntityActors actors = client;
        Live l = actors == null ? null : actors.find(id);
        return l == null ? null : l.actor;
    }

    /** How many actors live on this client. */
    public static int size() {
        CgEntityActors actors = client;
        return actors == null ? 0 : actors.order.size();
    }

    @Override
    protected void initialize() {
        if (world().server()) {
            tick = world().schedule().add(CgTickGroup.GAMEPLAY, "crystalgraphics:actors", this::tick).onServer(true);
            // after the tick's sequences moved their montages, before the tick's changes go out
            move = world().schedule().add(CgTickGroup.NET_OUT, "crystalgraphics:actors.move", dt -> move(true)).onServer(true);
            if (world() == CgWorld.serverWorld()) server = this;
            return;
        }
        levelEpoch = CgWorldQueries.client().levelEpoch();
        tick = world().schedule().add(CgTickGroup.PLAYS, "crystalgraphics:actors", this::tick).whilePaused(true);
        move = world().schedule().add(CgTickGroup.POSE, "crystalgraphics:actors.move", dt -> move(false))
                .after(CgAnimation.of(world()).tickFunction());
        if (world() == CgWorld.client()) client = this;
    }

    @Override
    protected void deinitialize() {
        tick.close();
        move.close();
        clear();
        if (client == this) client = null;
        if (server == this) server = null;
    }

    /** Each actor's root motion taken; on a server every actor moves its entity, on a client only the local player's. */
    private void move(boolean onServer) {
        if (order.isEmpty()) return;
        int local = onServer ? -1 : CgEntityQueries.client().localPlayer();
        for (int i = 0; i < order.size(); i++) {
            Live l = order.get(i);
            boolean moves = onServer || local >= 0 && l.entity.id() == local;
            if (moves && !onServer && l.actor.host == null) l.actor.host = CgEntityQueries.client().entity(local);
            try {
                l.actor.move(moves);
            } catch (RuntimeException e) {
                LOGGER.error("the actor of {} failed to move its entity", l.entity, e);
            }
        }
    }

    /** Whether a binding names entity {@code id}: its actor comes from the tick, not from its type. */
    private static boolean bound(int id) {
        if (BINDINGS.isEmpty()) return false;
        int local = CgEntityQueries.client().localPlayer();
        for (Registration b : BINDINGS) {
            if (b.entityId == id || b.entityId == LOCAL_PLAYER && id == local) return true;
        }
        return false;
    }

    /** Spawns each binding's actor for its entity where it is present and not drawn by this binding yet. */
    private void bindings() {
        CgEntityQuery entities = CgEntityQueries.client();
        for (Registration b : BINDINGS) {
            if (b.failed) continue;
            int id = b.entityId == LOCAL_PLAYER ? entities.localPlayer() : b.entityId;
            if (id < 0) continue;
            Live l = find(id);
            if (l != null && l.origin == b) continue;
            if (!entities.pose(id, 1f, probe)) continue;
            String type = entities.type(id);
            if (!spawn(id, type == null ? "unknown:entity" : type, b, null)) b.failed = true;
        }
    }

    /** Removes the actors a closed binding made. */
    private void drop(Registration b) {
        for (int i = 0; i < order.size(); i++) {
            Live l = order.get(i);
            if (l.origin != b) continue;
            order.remove(i--);
            end(l);
        }
        index();
    }

    @Nullable
    private Live find(int id) {
        int i = Arrays.binarySearch(ids, id);
        return i < 0 ? null : byId[i];
    }

    private boolean spawn(int id, String type, Registration r, @Nullable Object host) {
        // a level's entities join before its first frame: the last level's actors go now, not over the new ones
        syncLevel();
        despawn(id);
        CgEntity entity = new CgEntity(id, type);
        CgEntityActor actor = r.factory.get();
        actor.entity = entity;
        actor.server = world().server();
        actor.host = host;
        entity.heldTags = !(actor.server && host != null);
        entity.tags = entity.heldTags ? CgEntityTags.hold(id) : CgEntityTags.of(host);
        if (entity.heldTags) entity.replicated = CgEntityVariables.hold(id);
        else entity.serverHost = host;
        CgScene scene = actor.scene();
        actor.root = scene.root().spawn();
        Live l = new Live(entity, actor, !r.keepVanillaModel, r, host);
        refresh(l, 0f);
        try {
            if (r.prefab != null) {
                CgPrefab prefab = prefabRef(r.prefab).get();
                if (prefab == null) throw new IllegalStateException("its prefab " + r.prefab + " did not load");
                actor.prefab = scene.spawn(prefab).attach(actor.root, null).spawn();
                actor.prefab.forEachSkeletal(actor::feed);
            }
            actor.onSpawn(entity);
        } catch (RuntimeException e) {
            LOGGER.error("the actor of {} failed to spawn: its entity is left to its host", entity, e);
            actor.removeAll();
            if (entity.heldTags) {
                CgEntityTags.release(id);
                CgEntityVariables.release(id);
            }
            return false;
        }
        order.add(l);
        index();
        return true;
    }

    private void despawn(int id) {
        Live l = find(id);
        if (l == null) return;
        order.remove(l);
        index();
        end(l);
    }

    /** Rebuilds the id index from {@link #order}: on a spawn or a removal, never a frame. */
    private void index() {
        int n = order.size();
        Live[] sorted = order.toArray(new Live[n]);
        Arrays.sort(sorted, (a, b) -> Integer.compare(a.entity.id(), b.entity.id()));
        int[] keys = new int[n];
        for (int i = 0; i < n; i++) keys[i] = sorted[i].entity.id();
        ids = keys;
        byId = sorted;
    }

    private static void end(Live l) {
        try {
            l.actor.onRemove(l.entity);
        } catch (RuntimeException e) {
            LOGGER.error("the actor of {} failed while removed", l.entity, e);
        }
        l.actor.removeAll();
        if (l.entity.heldTags) {
            CgEntityTags.release(l.entity.id());
            CgEntityVariables.release(l.entity.id());
        }
    }

    private void clear() {
        for (Live l : order) end(l);
        order.clear();
        index();
    }

    private void syncLevel() {
        if (world().server()) return;
        // the scene's clear first: left to its ATTACH, it would remove the roots spawned here for the new level
        CgScene.syncClientLevel();
        int now = CgWorldQueries.client().levelEpoch();
        if (now == levelEpoch) return;
        levelEpoch = now;
        clear();
    }

    private void tick(float dt) {
        syncLevel();
        if (!BINDINGS.isEmpty() && !world().server()) bindings();
        if (order.isEmpty()) return;
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, TICK)) {
            boolean removed = false;
            for (int i = 0; i < order.size(); i++) {
                Live l = order.get(i);
                refresh(l, dt);
                if (!l.entity.present) {
                    order.remove(i--);
                    end(l);
                    removed = true;
                    continue;
                }
                try {
                    l.actor.tick(l.entity, dt);
                } catch (RuntimeException e) {
                    LOGGER.error("the actor of {} failed: removed, its entity left to its host", l.entity, e);
                    order.remove(i--);
                    end(l);
                    removed = true;
                }
            }
            if (removed) index();
        }
    }

    /**
     * Reads its entity's pose and state (a client's at the frame's partial tick, the server's at its tick), {@code dt}
     * seconds on, and stands its root there.
     */
    private void refresh(Live l, float dt) {
        boolean onServer = l.host != null && world().server();
        CgEntityQuery entities = onServer ? CgEntityQueries.of(l.host) : CgEntityQueries.client();
        CgEntity e = l.entity;
        float partialTick = onServer ? 1f : CgStageHost.frame(CgStageHost.WORLD_OPAQUE).partialTick();
        e.present = entities.pose(e.id(), partialTick, e.pose);
        if (!e.present) return;
        e.flags = entities.flags(e.id());
        e.state.update(entities, onServer ? CgWorldQueries.of(l.host) : CgWorldQueries.client(), e.id(), partialTick, dt,
                e.pose);
        if (e.serverHost != null) CgEntityVariables.serve(e.serverHost, e.state);
        // The engine's meshes face +z (glTF), which Minecraft's yaw 0 faces: -bodyYaw turns +z to the body's facing.
        // Minecraft's own models face -z, hence its 180 - bodyYaw (LivingEntityRenderer.setupRotations)
        turn.rotationY((float) Math.toRadians(-e.bodyYaw()));
        l.actor.root.at(e.x(), e.y(), e.z()).rotation(turn);
    }
}
