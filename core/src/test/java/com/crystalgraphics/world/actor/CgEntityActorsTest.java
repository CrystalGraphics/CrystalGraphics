package com.crystalgraphics.world.actor;

import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.world.CgEntityProperties;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** An entity of a registered type is drawn by its actor, whose root follows the entity each frame until it leaves. */
public class CgEntityActorsTest {

    /** One entity, id 7, walking along +x a block a frame. */
    private static final class Walker implements CgEntityQuery {
        double x;
        int hurtTime;
        boolean here = true;
        /** Whether it answers facts: a frame it does not keeps the last state. */
        boolean facts = true;

        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }

        @Override
        public boolean pose(int id, float partialTick, double[] out) {
            if (id != 7 || !here) return false;
            Arrays.fill(out, 0, POSE_LENGTH, 0.0);
            out[X] = x;
            out[Y] = 64;
            out[BODY_YAW] = 180;
            return true;
        }

        @Override
        public boolean facts(int id, float partialTick, CgEntityFacts out) {
            if (id != 7 || !here || !facts) return false;
            out.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
            out.hurtTime = hurtTime;
            return true;
        }

        @Override public int flags(int id) { return id == 7 && here ? ALIVE | SPRINTING : 0; }
        @Override public int kind(int id) { return KIND_OTHER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }
        @Override public void within(double a, double b, double c, double d, double e, double f, IntConsumer visitor) { }
    }

    static final class Counting extends CgEntityActor {
        int spawned, ticks, removed;
        boolean sprinting;

        @Override
        protected void onSpawn(CgEntity entity) {
            spawned++;
        }

        @Override
        protected void tick(CgEntity entity, float dt) {
            ticks++;
            sprinting = entity.flag(CgEntityQuery.SPRINTING);
        }

        @Override
        protected void onRemove(CgEntity entity) {
            removed++;
        }
    }

    private final Walker walker = new Walker();
    private CgEntityActors.Registration registration;

    @Before
    public void level() {
        CgPlatform.provide(CgLevels.HOST, CgLevels.single(CgWorldQuery.NONE, walker));
    }

    @After
    public void leave() {
        CgNetEntities.left(7);
        if (registration != null) registration.close();
        CgPlatform.provide(CgLevels.HOST, null);
    }

    @Test
    public void anActorFollowsItsEntityUntilItLeaves() {
        Counting actor = new Counting();
        registration = CgEntityActors.register("test:walker", () -> actor);
        CgNetEntities.entered(8, "test:nobody");                     // a type with no actor: the host's
        assertNull(CgEntityActors.actor(8));
        CgNetEntities.entered(7, "test:walker");
        assertSame(actor, CgEntityActors.actor(7));
        assertTrue("it replaces the vanilla model", CgEntityActors.replacesModel(7));
        assertEquals(1, actor.spawned);
        CgSceneRoot root = actor.root();
        assertEquals("placed as it spawned", 64.0, root.y(), 0);

        for (int f = 1; f <= 3; f++) {
            walker.x = f;
            CgWorld.client().frame(null);
            assertEquals("frame " + f + ": the root where the entity is", f, root.x(), 1e-9);
        }
        assertEquals(3, actor.ticks);
        assertTrue("its flags read", actor.sprinting);

        CgNetEntities.left(7);
        assertEquals(1, actor.removed);
        assertTrue("its root removed with it", root.removed());
        assertNull(CgEntityActors.actor(7));
        assertFalse(CgEntityActors.replacesModel(7));
    }

    @Test
    public void anEntitysMotionIsWorkedOutEachFrameItsEventsTrueForTheirFrame() {
        Counting actor = new Counting();
        registration = CgEntityActors.register("test:walker", () -> actor);
        CgNetEntities.entered(7, "test:walker");
        CgNetObject motion = actor.entity().properties(CgEntityProperties.MOTION);
        assertSame("made once asked for, the actor's entity's own", motion, CgEntityProperties.MOTION.of(7));
        CgWorld.client().frame(null);
        assertFalse(motion.get(CgEntityProperties.HURT));
        assertTrue("worked out by the frame, before the actor reads it", motion.get(CgEntityProperties.GROUNDED));
        walker.hurtTime = 10;
        CgWorld.client().frame(null);
        assertTrue(motion.get(CgEntityProperties.HURT));
        assertEquals(0f, motion.get(CgEntityProperties.HURT_TIME), 1e-6);
        walker.facts = false;
        CgWorld.client().frame(null);
        assertFalse("a frame with no facts keeps the values, and nothing happened in it", motion.get(CgEntityProperties.HURT));
        assertTrue(motion.get(CgEntityProperties.GROUNDED));
        walker.facts = true;
        walker.hurtTime = 9;
        CgWorld.client().frame(null);
        assertFalse(motion.get(CgEntityProperties.HURT));

        walker.here = false;
        CgWorld.client().frame(null);
        assertTrue("ended with its entity", motion.isRemoved());
    }

    @Test
    public void anEntityTheHostNoLongerAnswersTakesItsActorWithItAndTheHostsDrawsAreStamped() {
        Counting actor = new Counting();
        registration = CgEntityActors.register("test:walker", () -> actor);
        CgNetEntities.entered(7, "test:walker");
        assertEquals("never drawn by the host yet", -1, actor.entity().hostFrame());
        CgWorld.client().frame(null);
        CgEntityActors.replacesModel(7);
        assertEquals("the host's draw, stamped", CgWorld.client().frames(), actor.entity().hostFrame());

        walker.here = false;                                           // gone with no leave event (Forge before 1.13)
        CgWorld.client().frame(null);
        assertEquals(1, actor.removed);
        assertNull(CgEntityActors.actor(7));
        assertTrue(actor.root().removed());
    }

    @Test
    public void anEntityBindingBeatsItsTypeAndClosingItGivesTheEntityBack() {
        Counting byType = new Counting(), bound = new Counting();
        registration = CgEntityActors.register("test:walker", () -> byType);
        CgNetEntities.entered(7, "test:walker");
        assertSame(byType, CgEntityActors.actor(7));

        CgEntityActors.Registration binding = CgEntityActors.bind(7, () -> bound);
        try {
            CgEntityActors.bind(7, Counting::new);
            throw new AssertionError("a second binding of one entity was taken");
        } catch (IllegalStateException expected) {
            // one actor per entity
        }
        CgWorld.client().frame(null);
        assertSame("the binding replaced the type's actor", bound, CgEntityActors.actor(7));
        assertEquals(1, byType.removed);
        assertTrue(CgEntityActors.replacesModel(7));

        CgNetEntities.entered(7, "test:walker");                      // the host announces it again: still the binding's
        assertSame(bound, CgEntityActors.actor(7));

        binding.close();
        assertEquals(1, bound.removed);
        assertNull(CgEntityActors.actor(7));
        assertFalse("the host's model again", CgEntityActors.replacesModel(7));
    }

    /** A body whose right hand is a bone 0.3 to the side, 1.2 up and 0.1 ahead, and whose left hand has a grip socket. */
    static final class Holding extends CgEntityActor {
        @Override
        protected void onSpawn(CgEntity entity) {
            CgSkeleton skeleton = CgSkeleton.builder().bone("root", null, new Vector3f())
                    .bone("right_hand", "root", new Vector3f(0.3f, 1.2f, 0.1f))
                    .bone("left_hand", "root", new Vector3f(-0.3f, 1.2f, 0.1f)).build();
            CgSkeletalMesh mesh = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
            }), skeleton).socket(CgSocket.of("left_hand_item", "left_hand", new Vector3f(0f, -0.1f, 0f))).build();
            add(scene().skeletal(mesh));
        }
    }

    @Test
    public void anActorsBodyHoldsEachHandsItemAtItsGrip() {
        registration = CgEntityActors.register("test:walker", Holding::new);
        CgNetEntities.entered(7, "test:walker");
        walker.x = 5;
        CgWorld.client().frame(null);
        Matrix4f grip = new Matrix4f();
        Vector3f at = new Vector3f();
        assertEquals("the main hand in the right arm", CgEntityActors.RIGHT, CgEntityActors.grip(7, CgEntityProperties.MAIN, grip));
        grip.getTranslation(at);
        // body yaw 180: the root turned half round, so the hand's +x and +z land at -x and -z of the feet
        assertEquals(-0.3f, at.x, 1e-4f);
        assertEquals(1.2f, at.y, 1e-4f);
        assertEquals(-0.1f, at.z, 1e-4f);
        assertEquals(CgEntityActors.LEFT, CgEntityActors.grip(7, CgEntityProperties.OFF, grip));
        grip.getTranslation(at);
        assertEquals("the off hand at its grip socket, under its hand", 1.1f, at.y, 1e-4f);
        assertEquals(CgEntityActors.NO_GRIP, CgEntityActors.grip(8, CgEntityProperties.MAIN, grip));
    }

    @Test
    public void aTypeTakesOneActorAndKeepingTheVanillaModelIsAsked() {
        registration = CgEntityActors.register("test:walker", Counting::new).keepVanillaModel();
        try {
            CgEntityActors.register("test:walker", Counting::new);
            throw new AssertionError("a second actor for one type was taken");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("test:walker"));
        }
        CgNetEntities.entered(7, "test:walker");
        assertFalse("kept: drawn beside its actor", CgEntityActors.replacesModel(7));
    }
}
