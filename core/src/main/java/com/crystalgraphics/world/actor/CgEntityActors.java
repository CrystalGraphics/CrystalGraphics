package com.crystalgraphics.world.actor;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Entity actors: which actor draws each entity type, and the actors of the entities on this client. A mod registers an
 * actor class by entity type once, on any era and loader; each host tells it which entities enter and leave, and draws
 * an entity with an actor through it instead of its vanilla model. Unreal's actor spawned for a game object, with its
 * tick.
 *
 * <pre>{@code
 * CgEntityActors.register("mymod:dragon", DragonActor::new);                    // at load
 * CgEntityActors.register("mymod:golem", GolemActor::new).keepVanillaModel();  // drawn as well as the vanilla model
 * }</pre>
 *
 * <p>What a host does, from its entity events (a harness scene's fake entities do the same):</p>
 *
 * <pre>{@code
 * CgEntityActors.entered(entity.getId(), "mymod:dragon");    // joined the client's level
 * if (CgEntityActors.replacesModel(entity.getId())) return;  // the entity renderer: its actor draws it
 * CgEntityActors.left(entity.getId());                       // left the level, unloaded or killed
 * }</pre>
 *
 * <ul>
 *   <li>Each frame in {@code PLAYS}: every actor's root placed at its entity's pose (interpolated to the frame's
 *       partial tick, read from {@link CgEntityQueries#client()}), then its {@code tick}. Before {@code POSE}, so its
 *       animation reads this frame's changes and everything riding the root follows in {@code ATTACH}.</li>
 *   <li>An entity of a type no actor is registered for is left to its host.</li>
 *   <li>A new level removes every actor; an actor that throws is removed, logged, and its entity left to its host.</li>
 *   <li>The client world's thread; {@link #register} from any thread.</li>
 * </ul>
 */
public final class CgEntityActors extends CgWorldSubsystem {

    private static final Logger LOGGER = LogManager.getLogger(CgEntityActors.class);
    private static final int TICK = CgTrace.name("actors.tick");
    private static final Map<String, Registration> TYPES = new ConcurrentHashMap<>();

    /** An entity type's actor, as registered; closing it unregisters the type (a reload, a scene leaving). */
    public static final class Registration implements AutoCloseable {
        private final String type;
        private final Supplier<? extends CgEntityActor> factory;
        private volatile boolean keepVanillaModel;

        Registration(String type, Supplier<? extends CgEntityActor> factory) {
            this.type = type;
            this.factory = factory;
        }

        /** Draws the host's own model as well as the actor: an actor adding to an entity rather than replacing it. */
        public Registration keepVanillaModel() {
            keepVanillaModel = true;
            return this;
        }

        public String type() {
            return type;
        }

        /** Its type has no actor from now; actors already living stay until their entities leave. */
        @Override
        public void close() {
            TYPES.remove(type, this);
        }
    }

    /** An entity's actor, as it lives. */
    private static final class Live {
        final CgEntity entity;
        final CgEntityActor actor;
        final boolean replacesModel;

        Live(CgEntity entity, CgEntityActor actor, boolean replacesModel) {
            this.entity = entity;
            this.actor = actor;
            this.replacesModel = replacesModel;
        }
    }

    private final Map<Integer, Live> live = new HashMap<>();
    private final List<Live> order = new ArrayList<>();
    private final Quaternionf turn = new Quaternionf();
    private CgTickFunction tick;
    private int levelEpoch;

    /**
     * {@code factory}'s actor draws every entity of type {@code type} ({@code mymod:dragon}): from the next one to enter.
     *
     * @throws IllegalStateException where the type has an actor already
     */
    public static Registration register(String type, Supplier<? extends CgEntityActor> factory) {
        Registration r = new Registration(Objects.requireNonNull(type, "type"), Objects.requireNonNull(factory, "factory"));
        if (TYPES.putIfAbsent(type, r) != null) throw new IllegalStateException("entity type " + type + " has an actor already");
        return r;
    }

    /** Whether {@code type} has an actor. */
    public static boolean registered(String type) {
        return TYPES.containsKey(type);
    }

    /** The host's: entity {@code id} of {@code type} joined the client's level. Nothing where its type has no actor. */
    public static void entered(int id, String type) {
        Registration r = TYPES.get(type);
        if (r == null) return;
        client().spawn(id, type, r);
    }

    /** The host's: entity {@code id} left the client's level. */
    public static void left(int id) {
        CgEntityActors actors = CgWorld.client().subsystem(CgEntityActors.class);
        if (actors != null) actors.despawn(id);
    }

    /** Whether entity {@code id}'s actor draws it in place of its vanilla model: what the host's renderer asks. */
    public static boolean replacesModel(int id) {
        CgEntityActors actors = CgWorld.client().subsystem(CgEntityActors.class);
        if (actors == null) return false;
        Live l = actors.live.get(id);
        return l != null && l.replacesModel;
    }

    /** Entity {@code id}'s actor, or null. */
    @Nullable
    public static CgEntityActor actor(int id) {
        CgEntityActors actors = CgWorld.client().subsystem(CgEntityActors.class);
        if (actors == null) return null;
        Live l = actors.live.get(id);
        return l == null ? null : l.actor;
    }

    /** How many actors live on this client. */
    public static int size() {
        CgEntityActors actors = CgWorld.client().subsystem(CgEntityActors.class);
        return actors == null ? 0 : actors.order.size();
    }

    private static CgEntityActors client() {
        CgWorld world = CgWorld.client();
        CgEntityActors existing = world.subsystem(CgEntityActors.class);
        return existing != null ? existing : world.add(new CgEntityActors());
    }

    @Override
    protected void initialize() {
        levelEpoch = CgWorldQueries.client().levelEpoch();
        tick = world().schedule().add(CgTickGroup.PLAYS, "crystalgraphics:actors", this::tick).whilePaused(true);
    }

    @Override
    protected void deinitialize() {
        tick.close();
        clear();
    }

    private void spawn(int id, String type, Registration r) {
        if (live.containsKey(id)) despawn(id);
        CgEntity entity = new CgEntity(id, type);
        CgEntityActor actor = r.factory.get();
        actor.entity = entity;
        actor.root = CgScene.client().root().spawn();
        Live l = new Live(entity, actor, !r.keepVanillaModel);
        refresh(l);
        try {
            actor.onSpawn(entity);
        } catch (RuntimeException e) {
            LOGGER.error("the actor of {} failed to spawn: its entity is left to its host", entity, e);
            actor.removeAll();
            return;
        }
        live.put(id, l);
        order.add(l);
    }

    private void despawn(int id) {
        Live l = live.remove(id);
        if (l == null) return;
        order.remove(l);
        end(l);
    }

    private static void end(Live l) {
        try {
            l.actor.onRemove(l.entity);
        } catch (RuntimeException e) {
            LOGGER.error("the actor of {} failed while removed", l.entity, e);
        }
        l.actor.removeAll();
    }

    private void clear() {
        for (Live l : order) end(l);
        order.clear();
        live.clear();
    }

    private void tick(float dt) {
        int now = CgWorldQueries.client().levelEpoch();
        if (now != levelEpoch) {
            levelEpoch = now;
            clear();
        }
        if (order.isEmpty()) return;
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, TICK)) {
            for (int i = 0; i < order.size(); i++) {
                Live l = order.get(i);
                refresh(l);
                try {
                    l.actor.tick(l.entity, dt);
                } catch (RuntimeException e) {
                    LOGGER.error("the actor of {} failed: removed, its entity left to its host", l.entity, e);
                    live.remove(l.entity.id());
                    order.remove(i--);
                    end(l);
                }
            }
        }
    }

    /** Reads its entity's pose at the frame's partial tick and stands its root there. */
    private void refresh(Live l) {
        CgEntityQuery entities = CgEntityQueries.client();
        CgEntity e = l.entity;
        e.present = entities.pose(e.id(), CgRenderStage.WORLD_OPAQUE.host().partialTick(), e.pose);
        if (!e.present) return;
        e.flags = entities.flags(e.id());
        // Minecraft turns a living model by 180 - bodyYaw about up (LivingEntityRenderer.setupRotations)
        turn.rotationY((float) Math.toRadians(180.0 - e.bodyYaw()));
        l.actor.root.at(e.x(), e.y(), e.z()).rotation(turn);
    }
}
