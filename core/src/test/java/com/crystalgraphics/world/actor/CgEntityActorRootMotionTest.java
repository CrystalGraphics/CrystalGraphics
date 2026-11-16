package com.crystalgraphics.world.actor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.joml.Vector3f;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipSettings;
import com.crystalgraphics.api.animation.CgRootMotion;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgWarpWindow;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.world.CgEntityProperties;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityMovement;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.animation.CgSequenceSkeletal;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * A mob's lunge on the server: its type's actor spawns there too, plays its sequence's montage headless, and its root
 * motion moves the entity each tick through the host's own movement: onto its target, stopped by a wall, turned.
 */
public class CgEntityActorRootMotionTest {

    static final CgSkeleton SKELETON = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("pelvis", "root", new Vector3f(0, 1, 0)).build();

    /** 4 m along +z (its facing) in its first second, eased, then a quarter second held. */
    static CgAnimSequence lunge(CgWarpWindow window) {
        int keys = 21;
        float[] t = new float[keys * 3], r = new float[keys * 4];
        for (int k = 0; k < keys; k++) {
            float a = Math.min(1f, k / 16f);
            t[k * 3 + 2] = 4f * (a * a * (3 - 2 * a));
            r[k * 4 + 3] = 1f;
        }
        CgClipSettings settings = CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE));
        if (window != null) settings = settings.withWarpWindows(List.of(window));
        return CgAnimSequence.builder("lunge", 16, keys).track("root", t, r, new float[] {1, 1, 1}).build().with(settings);
    }

    static CgSkeletalMesh mesh(CgAnimSequence clip) {
        CgMesh tri = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int s = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(p, q, s);
        });
        return CgSkeletalMesh.builder(tri, SKELETON).sequence(clip).build();
    }

    static CgSequenceDefinition LUNGE;

    /** The host's entity: a position, a yaw in Minecraft's degrees (0 faces +z), and a wall it cannot pass. */
    static final class Mob {
        final int id;
        double x, y, z;
        float yaw;
        int hurtTime;
        boolean grounded = true;
        double wallZ = Double.POSITIVE_INFINITY;
        int moves;

        Mob(int id) {
            this.id = id;
        }
    }

    /** The one mob the level holds. */
    static Mob mob;

    static final CgEntityQuery ENTITIES = new CgEntityQuery() {
        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }

        @Override public boolean pose(int id, float partialTick, double[] out) {
            if (mob == null || id != mob.id) return false;
            Arrays.fill(out, 0, POSE_LENGTH, 0.0);
            out[X] = mob.x;
            out[Y] = mob.y;
            out[Z] = mob.z;
            out[YAW] = out[BODY_YAW] = out[HEAD_YAW] = mob.yaw;
            return true;
        }

        @Override public boolean facts(int id, float partialTick, CgEntityFacts out) {
            if (mob == null || id != mob.id) return false;
            out.bits = CgEntityFacts.LIVING | (mob.grounded ? CgEntityFacts.GROUNDED : 0);
            out.hurtTime = mob.hurtTime;
            return true;
        }

        @Override public int flags(int id) { return mob != null && id == mob.id ? ALIVE | LIVING : 0; }
        @Override public int kind(int id) { return KIND_MONSTER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }
        @Override public void within(double a, double b, double c, double d, double e, double f, IntConsumer visitor) { }
    };

    static final CgLevels.Host LEVELS = new CgLevels.Host() {
        @Override public CgWorldQuery world(Object level) { return CgWorldQuery.NONE; }
        @Override public CgEntityQuery entities(Object level) { return level instanceof Mob ? ENTITIES : CgEntityQuery.NONE; }
        @Override public CgWorldQuery clientWorld() { return CgWorldQuery.NONE; }
        @Override public CgEntityQuery clientEntities() { return CgEntityQuery.NONE; }
        @Override public Object clientLevel() { return null; }
        @Override public String dimension(Object handle) { return null; }
        @Override public Object level(Object handle, String dimension) { return null; }
        @Override public boolean chunkLoaded(Object level, int chunkX, int chunkZ) { return false; }
        @Override public Path saveDirectory(Object handle) { return null; }
        @Override public int entityId(Object entity) { return entity instanceof Mob m ? m.id : -1; }

        @Override public boolean position(Object entity, double[] out) {
            if (!(entity instanceof Mob m)) return false;
            out[0] = m.x;
            out[1] = m.y;
            out[2] = m.z;
            return true;
        }

        @Override public boolean alive(Object entity) { return entity instanceof Mob; }
    };

    static final CgEntityMovement.Host MOVEMENT = new CgEntityMovement.Host() {
        @Override public boolean move(Object entity, double dx, double dy, double dz) {
            if (!(entity instanceof Mob m)) return false;
            m.x += dx;
            m.y += dy;
            // swept against a wall as Entity.move is: stopped at it, never through
            m.z = Math.min(m.z + dz, m.wallZ);
            m.moves++;
            return true;
        }

        @Override public boolean teleport(Object entity, double x, double y, double z) {
            if (!(entity instanceof Mob m)) return false;
            m.x = x;
            m.y = y;
            m.z = z;
            return true;
        }

        @Override public boolean rotate(Object entity, float yaw, float pitch) {
            if (!(entity instanceof Mob m)) return false;
            m.yaw = yaw;
            return true;
        }

        @Override public float yaw(Object entity) {
            return entity instanceof Mob m ? m.yaw : Float.NaN;
        }

        @Override public float pitch(Object entity) {
            return entity instanceof Mob ? 0f : Float.NaN;
        }

        @Override public boolean velocity(Object entity, double vx, double vy, double vz) { return false; }
        @Override public boolean push(Object entity, double vx, double vy, double vz) { return false; }
        @Override public boolean gravity(Object entity, boolean on) { return false; }
        @Override public boolean resetFall(Object entity) { return false; }
    };

    /** The type's actor: one skeletal body whose root motion moves its entity, on either side. */
    static final class Fighter extends CgEntityActor {
        static CgSkeletalMesh body;
        CgSkeletalMeshComponent component;

        @Override protected void onSpawn(CgEntity entity) {
            component = rootMotion(add(scene().skeletal(body)));
        }
    }

    /** Its role: the body its clips play on. */
    record Role(CgAnimInstance body) implements CgSequenceSkeletal {
        @Override public CgAnimInstance instance() {
            return body;
        }

        @Override public CgAnimSequence clip(String id) {
            return body.mesh().sequence("lunge");
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    private static int nextId = 100;
    private CgEntityActors.Registration registration;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
        LUNGE = CgSequenceDefinition.compile(CgAssetData.of("test:lunge", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read("_v: 1\ntick_resolution: 1000\ndisplay_rate: 20\nrange: [0, 1250]\n"
                        + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                        + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: "
                        + "[ { id: s1, range: [0, 1250], clip: test:lunge } ] }\n"), null));
    }

    @Before
    public void setUp() {
        CgPlatform.provide(CgLevels.HOST, LEVELS);
        CgPlatform.provide(CgEntityMovement.HOST, MOVEMENT);
        registration = CgEntityActors.register("test:fighter", Fighter::new);
    }

    @After
    public void tearDown() {
        if (mob != null) CgNetEntities.leftServer(mob);
        mob = null;
        registration.close();
        CgPlatform.provide(CgLevels.HOST, CgLevels.Host.NONE);
        CgPlatform.provide(CgEntityMovement.HOST, CgEntityMovement.Host.NONE);
    }

    /** {@code m} joins the server's level, its actor spawns there, its lunge plays, then 40 game ticks. */
    private Mob lunge(Mob m, CgAnimSequence clip, Consumer<CgAnimInstance> before) {
        mob = m;
        Fighter.body = mesh(clip);
        CgWorld server = CgWorld.serverWorld();
        CgSequencer sequencer = CgSequencer.of(server);
        if (sequencer == null) sequencer = server.add(new CgSequencer());
        CgNetEntities.enteredServer(m, "test:fighter");
        Fighter actor = (Fighter) CgEntityActors.serverActor(m.id);
        assertNotNull("the server's actor", actor);
        assertTrue("built for the server", actor.server());
        CgAnimInstance body = actor.component.anim();
        before.accept(body);
        sequencer.play(LUNGE, Map.of("caster", new Role(body)));
        for (int i = 0; i < 40; i++) server.tick();
        return m;
    }

    @Test
    public void itsLungeCarriesTheEntityItsClipsDistanceOnTheServer() {
        Mob m = lunge(new Mob(nextId++), lunge(null), b -> { });
        assertEquals("4 m along its facing, +z", 4.0, m.z, 0.01);
        assertEquals(0.0, m.x, 1e-4);
        assertTrue("moved through the host, tick by tick: " + m.moves, m.moves >= 15);
    }

    @Test
    public void beingHurtPlaysItsReactionWhereItsRuleHolds() {
        CgVfxAssets.instance().registry().registerCode("test:lunge", CgSequenceKinds.SEQUENCE, () -> LUNGE);
        try {
            registration.react(CgReaction.of(CgEntityProperties.HURT, "test:lunge", CgAnimGraph.DEFAULT_SLOT,
                    CgReaction.when(Map.of("variable", "entity.grounded", "op", "==", "value", true))));
            Mob grounded = hurt(new Mob(nextId++));
            assertEquals("hurt on the ground: its reaction's lunge carried it 4 m", 4.0, grounded.z, 0.01);

            CgNetEntities.leftServer(grounded);
            Mob falling = new Mob(nextId++);
            falling.grounded = false;
            hurt(falling);
            assertEquals("hurt in the air: its rule did not hold, nothing played", 0.0, falling.z, 1e-9);
        } finally {
            CgVfxAssets.instance().registry().remove(CgSequenceKinds.SEQUENCE, "test:lunge");
        }
    }

    /** {@code m} joins the server, is seen a few ticks, then hurt, then 40 ticks. */
    private Mob hurt(Mob m) {
        mob = m;
        Fighter.body = mesh(lunge(null));
        CgWorld server = CgWorld.serverWorld();
        if (CgSequencer.of(server) == null) server.add(new CgSequencer());
        CgNetEntities.enteredServer(m, "test:fighter");
        for (int i = 0; i < 3; i++) server.tick();
        assertEquals("not hurt yet: nothing played", 0.0, m.z, 1e-9);
        m.hurtTime = 10;
        for (int i = 0; i < 40; i++) server.tick();
        return m;
    }

    @Test
    public void facingWestItLungesWest() {
        Mob m = new Mob(nextId++);
        m.yaw = 90f;                                       // Minecraft's: yaw 90 faces -x
        lunge(m, lunge(null), b -> { });
        assertEquals(-4.0, m.x, 0.01);
        assertEquals(0.0, m.z, 0.01);
    }

    @Test
    public void aWallStopsIt() {
        Mob m = new Mob(nextId++);
        m.wallZ = 2.5;
        lunge(m, lunge(null), b -> { });
        assertEquals("stopped at the wall, never through", 2.5, m.z, 1e-9);
    }

    @Test
    public void warpedItStopsAtArmsLengthOfItsTargetTurnedToIt() {
        Mob m = lunge(new Mob(nextId++), lunge(CgWarpWindow.of("opponent", 0f, 1f).facing(true)),
                b -> b.motionWarping().target("opponent", 5, 0, 3, 0f).standOff("opponent", 0.9f));
        double gap = Math.hypot(5 - m.x, 3 - m.z);
        assertEquals("at " + m.x + ", " + m.z, 0.9, gap, 0.02);
        // facing the target: Minecraft's yaw toward (dx, dz) is atan2(-dx, dz)
        double toward = Math.toDegrees(Math.atan2(-(5 - m.x), 3 - m.z));
        assertEquals("yaw " + m.yaw, toward, m.yaw, 1.0);
    }
}
