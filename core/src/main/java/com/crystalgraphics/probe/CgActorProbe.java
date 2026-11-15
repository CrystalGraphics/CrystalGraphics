package com.crystalgraphics.probe;

import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gauntlet.CgGauntletCapture;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.actor.CgEntity;
import com.crystalgraphics.world.actor.CgEntityActor;
import com.crystalgraphics.world.actor.CgEntityActors;
import org.joml.Matrix4dc;

import java.util.List;

/**
 * Proves, in a running single-player game, that the host binds entity actors: an actor spawned for an entity of its
 * type entering the client's level, its root standing where the host has the entity, the host's renderer leaving that
 * entity's model to it, and the actor gone when its entity is. The gauntlet's test {@code crystalgraphics:actors}; it
 * enters a world itself.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:actors
 * // what it logs, and writes in the run's report:
 * crystalgraphics:actors actors.spawned: true (minecraft:armor_stand#412 has an actor, #411 entered before its type did)
 * crystalgraphics:actors actors.replaced: true (the host's renderer reached #412 over 57 frames)
 * crystalgraphics:actors actors.prefab: true (crystalgraphics:probe_actor's head 0.0 blocks from 1 above the root)
 * }</pre>
 *
 * <ul>
 *   <li>It summons two targets ({@link CgWorldStimulus#stand}: armor stands, pigs on 1.7.10) three blocks in front of
 *       the player, registers an actor for their type between the two, and clears every entity but players within
 *       {@value #CLEAR} blocks at the end. Never point it at a world that matters.</li>
 *   <li>The actor draws nothing: the second target shows as its name tag and shadow alone, which the shot
 *       {@code crystalgraphics-actors-replaced.png} shows. Its registration names a prefab of two nodes and no mesh,
 *       requested as the test starts, its head checked a block above the root.</li>
 *   <li>Skipped, with the reason, where there is no single-player server.</li>
 * </ul>
 */
public final class CgActorProbe {

    /** Client ticks: in a world before summoning; the most for a summon to arrive or leave; a draw to be seen over. */
    private static final int SETTLE = 60, ARRIVE = 100, WATCH = 40;
    private static final double AHEAD = 3, FIND = 1.5, CLEAR = 8, FOLLOW = 1e-3;
    private static final String PREFAB = "crystalgraphics:probe_actor";

    private enum Phase { WAITING, SETTLING, FIRST, SECOND, WATCHING, SHOT, LEAVING, DONE }

    private static Phase phase = Phase.WAITING;
    private static int ticks;
    private static double ax, ay, az;
    private static int first = -1, second = -1;
    private static long firstHostFrame = -1;
    private static int hostFrames;
    private static CgEntityActors.Registration registration;
    private static CgGauntletCapture.Shot shot;
    private static boolean installed;
    private static final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private static final CgAABB box = new CgAABB();
    /** The test playing it; null outside a run's turn. */
    private static volatile Test active;

    private CgActorProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:actors}. */
    public static CgGauntletController test() {
        return new Test();
    }

    /** What the probe registers: an actor drawing nothing, so the host's model is the only thing it could have shown. */
    private static final class ProbeActor extends CgEntityActor {
        @Override
        protected void onSpawn(CgEntity entity) {
        }

        CgPrimitiveComponent<?> head() {
            return prefab() == null ? null : prefab().node("head");
        }
    }

    private static final class Test extends CgGauntletController {
        @Override
        protected void onInit() {
            active = this;
            phase = Phase.WAITING;
            first = second = -1;
            CgVfxAssets.instance().prefetch(List.of(CgVfxAssets.ref(CgBodyKinds.PREFAB, PREFAB)));   // ready by the spawn
            install();
        }

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected void onEnd() {
            active = null;
            if (registration != null) registration.close();
            registration = null;
        }

        void stated(String name, boolean ok, String detail) {
            check(name, ok, detail);
        }

        void skipped(String name, String reason) {
            skip(name, reason);
        }

        void noted(String name, String detail) {
            info(name, detail);
        }

        CgGauntletCapture.Shot shoot(String name) {
            return capture(name);
        }

        void done() {
            endTest();
        }
    }

    private static synchronized void install() {
        if (installed) return;
        installed = true;
        CgEngineLifecycle.onClientTick(CgActorProbe::tick);
    }

    private static void tick() {
        Test test = active;
        if (test == null) return;
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        CgEntityQuery entities = CgEntityQueries.client();
        int me = entities.localPlayer();
        ticks++;
        switch (phase) {
            case WAITING:
                if (me < 0) return;
                stimulus.keepRunning();
                next(Phase.SETTLING);
                return;
            case SETTLING:
                if (ticks < SETTLE || !entities.pose(me, 1f, pose)) return;
                // ahead of the player, where the host draws it: Minecraft's yaw 0 faces +z
                double yaw = Math.toRadians(pose[CgEntityQuery.YAW]);
                ax = pose[CgEntityQuery.X] - Math.sin(yaw) * AHEAD;
                ay = pose[CgEntityQuery.Y];
                az = pose[CgEntityQuery.Z] + Math.cos(yaw) * AHEAD;
                if (!stimulus.stand(ax, ay, az)) {
                    test.skipped("everything", "no single-player server to summon on");
                    finish(test);
                    return;
                }
                next(Phase.FIRST);
                return;
            case FIRST: {
                first = near(entities, me, -1);
                if (first < 0) {
                    if (ticks > ARRIVE) fail(test, "actors.type", "no target arrived at " + at());
                    return;
                }
                String type = entities.type(first);
                test.stated("actors.type", type != null && type.indexOf(':') > 0, "#" + first + " is " + type);
                if (type == null) {
                    finish(test);
                    return;
                }
                registration = CgEntityActors.register(type, ProbeActor::new).prefab(PREFAB);
                stimulus.stand(ax, ay, az);
                next(Phase.SECOND);
                return;
            }
            case SECOND:
                second = near(entities, me, first);
                if (second < 0 || CgEntityActors.actor(second) == null) {
                    if (ticks > ARRIVE) {
                        fail(test, "actors.spawned", second < 0 ? "no second target arrived at " + at()
                                : "#" + second + " of " + registration.type() + " entered with no actor");
                    }
                    return;
                }
                test.stated("actors.spawned", CgEntityActors.actor(first) == null,
                        registration.type() + "#" + second + " has an actor, #" + first + " entered before its type did");
                next(Phase.WATCHING);
                return;
            case WATCHING: {
                CgEntityActor actor = CgEntityActors.actor(second);
                long frame = actor == null ? -1 : actor.entity().hostFrame();
                if (frame >= 0 && firstHostFrame < 0) firstHostFrame = frame;
                if (frame > firstHostFrame && firstHostFrame >= 0) hostFrames = (int) (frame - firstHostFrame);
                if (ticks < WATCH) return;
                // after frames have placed it: Forge announces an entity before its level holds it
                follows(test, entities);
                test.stated("actors.replaced", hostFrames > 0, hostFrames > 0
                        ? "the host's renderer reached #" + second + " over " + hostFrames + " frames"
                        : "the host's renderer never reached #" + second + " (frame " + frame + ", now "
                                + CgWorld.client().frames() + "; pitch " + pitch(entities, me) + ")");
                shot = test.shoot("replaced");
                next(Phase.SHOT);
                return;
            }
            case SHOT:
                if (!shot.done()) return;
                stimulus.clear(ax, ay, az, CLEAR);
                next(Phase.LEAVING);
                return;
            case LEAVING:
                if (CgEntityActors.actor(second) != null) {
                    if (ticks > ARRIVE) fail(test, "actors.left", "#" + second + "'s actor outlived its entity");
                    return;
                }
                test.stated("actors.left", true, "#" + second + "'s actor went with it, " + ticks + " ticks after the clear");
                finish(test);
                return;
            default:
        }
    }

    /** Its root against the host's own box for the entity: the feet at the box's bottom centre. */
    private static void follows(Test test, CgEntityQuery entities) {
        CgEntityActor actor = CgEntityActors.actor(second);
        if (actor == null || !entities.box(second, 1f, box)) {
            test.skipped("actors.follows", "#" + second + " gone before it could be read");
            return;
        }
        // the root stood at last frame's partial tick; a still target is the same at any
        double dx = actor.root().x() - (box.minX + box.maxX) * 0.5, dy = actor.root().y() - box.minY;
        double dz = actor.root().z() - (box.minZ + box.maxZ) * 0.5;
        double off = Math.sqrt(dx * dx + dy * dy + dz * dz);
        test.stated("actors.follows", off < FOLLOW, "root " + off + " blocks from the box's bottom centre");
        // its prefab's head, a block up from its body, placed off the root as of the last frame's ATTACH
        CgPrimitiveComponent<?> head = ((ProbeActor) actor).head();
        if (head == null) {
            test.stated("actors.prefab", false, PREFAB + " was not spawned on the actor");
            return;
        }
        Matrix4dc m = head.worldTransform();
        double hx = m.m30() - actor.root().x(), hy = m.m31() - actor.root().y() - 1, hz = m.m32() - actor.root().z();
        double headOff = Math.sqrt(hx * hx + hy * hy + hz * hz);
        test.stated("actors.prefab", headOff < FOLLOW, PREFAB + "'s head " + headOff + " blocks from 1 above the root");
    }

    /** The nearest entity but the player and {@code not} within {@value #FIND} of the targets' point; -1 for none. */
    private static int near(CgEntityQuery entities, int me, int not) {
        final int[] best = {-1};
        final double[] bestD = {FIND * FIND};
        entities.within(ax - FIND, ay - FIND, az - FIND, ax + FIND, ay + FIND + 2, az + FIND, id -> {
            if (id == me || id == not || !entities.pose(id, 1f, pose)) return;
            double dx = pose[CgEntityQuery.X] - ax, dz = pose[CgEntityQuery.Z] - az;
            double d = dx * dx + dz * dz;
            if (d < bestD[0]) {
                bestD[0] = d;
                best[0] = id;
            }
        });
        return best[0];
    }

    private static String at() {
        return String.format("%.1f %.1f %.1f", ax, ay, az);
    }

    private static String pitch(CgEntityQuery entities, int me) {
        return entities.pose(me, 1f, pose) ? String.format("%.0f", pose[CgEntityQuery.PITCH]) : "?";
    }

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private static void fail(Test test, String name, String reason) {
        test.stated(name, false, reason);
        finish(test);
    }

    private static void finish(Test test) {
        if (registration != null) registration.close();
        registration = null;
        CgPlatform.get(CgWorldStimulus.SERVICE).clear(ax, ay, az, CLEAR);
        phase = Phase.DONE;
        test.done();
    }
}
