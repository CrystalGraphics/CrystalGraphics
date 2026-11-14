package com.crystalgraphics.world.actor;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
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
        boolean here = true;

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
        CgEntityActors.left(7);
        if (registration != null) registration.close();
        CgPlatform.provide(CgLevels.HOST, null);
    }

    @Test
    public void anActorFollowsItsEntityUntilItLeaves() {
        Counting actor = new Counting();
        registration = CgEntityActors.register("test:walker", () -> actor);
        CgEntityActors.entered(8, "test:nobody");                     // a type with no actor: the host's
        assertNull(CgEntityActors.actor(8));
        CgEntityActors.entered(7, "test:walker");
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

        CgEntityActors.left(7);
        assertEquals(1, actor.removed);
        assertTrue("its root removed with it", root.removed());
        assertNull(CgEntityActors.actor(7));
        assertFalse(CgEntityActors.replacesModel(7));
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
        CgEntityActors.entered(7, "test:walker");
        assertFalse("kept: drawn beside its actor", CgEntityActors.replacesModel(7));
    }
}
