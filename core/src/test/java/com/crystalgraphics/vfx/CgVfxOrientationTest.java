package com.crystalgraphics.vfx;

import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.world.CgEntityAttachments;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * An emitter's orientation at run time (aaa-design §2.16): an effect's orientation is what it follows times its own,
 * every instance it steps spawns turned by it, and a local instance's particles turn by its change.
 */
public class CgVfxOrientationTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:orientation")).build();
    private static final CgVfxEmitter WORLD = CgVfxEmitter.builder("orient_world").capacity(400).rate(60f).life(1f, 1f).build();
    private static final CgVfxEmitter LOCAL = CgVfxEmitter.builder("orient_local").local().capacity(400).rate(60f)
            .life(1f, 1f).build();

    static final class Turned extends CgVfxEffect<Turned> {
        final CgVfxEmitterInstance world = new CgVfxEmitterInstance(WORLD, 0.2f), local = new CgVfxEmitterInstance(LOCAL, 0.3f);

        Turned() {
            super(LOOK, 0, 64, 0);
            world.start(0f, 0f, 0f);
            local.start(0f, 0f, 0f);
        }

        @Override
        protected void tick(float dt) {
            tick(world, dt);
            tick(local, dt);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    private static void assertQuaternion(Quaternionf expected, float x, float y, float z, float w) {
        // q and -q are one rotation.
        float sign = Math.signum(expected.w * w + expected.x * x + expected.y * y + expected.z * z);
        assertEquals(expected.x, x * sign, 1e-5f);
        assertEquals(expected.y, y * sign, 1e-5f);
        assertEquals(expected.z, z * sign, 1e-5f);
        assertEquals(expected.w, w * sign, 1e-5f);
    }

    @Test
    public void anEffectsOrientationIsWhatItFollowsTimesItsOwn() {
        Turned effect = new Turned().orient(new Quaternionf().rotationX(0.4f));
        assertQuaternion(new Quaternionf().rotationX(0.4f), effect.orientation().x(), effect.orientation().y(),
                effect.orientation().z(), effect.orientation().w());
        effect.turnToFollowed(new Quaternionf().rotationY(1.1f));
        Quaternionf expected = new Quaternionf().rotationY(1.1f).mul(new Quaternionf().rotationX(0.4f));
        assertQuaternion(expected, effect.orientation().x(), effect.orientation().y(), effect.orientation().z(),
                effect.orientation().w());
    }

    @Test
    public void everyInstanceSpawnsTurnedAndALocalOneTurnsByTheChange() {
        CgVfxSystem system = new CgVfxSystem();
        Turned effect = system.play(new Turned().orient(new Quaternionf().rotationY(0.3f)));
        for (int f = 0; f <= 6; f++) system.update(f / 60.0);
        CgVfxEmitterInstance.Books world = effect.world.books(), local = effect.local.books();
        Quaternionf first = new Quaternionf().rotationY(0.3f);
        assertQuaternion(first, world.rotationX(), world.rotationY(), world.rotationZ(), world.rotationW());
        assertQuaternion(first, local.rotationX(), local.rotationY(), local.rotationZ(), local.rotationW());
        assertQuaternion(new Quaternionf(), local.turnX(), local.turnY(), local.turnZ(), local.turnW());

        // Turned further between two particle steps: the local instance turns by the difference, the world one never.
        effect.orient(new Quaternionf().rotationY(0.8f));
        for (int f = 7; f <= 10; f++) system.update(f / 60.0);
        assertQuaternion(new Quaternionf().rotationY(0.8f), world.rotationX(), world.rotationY(), world.rotationZ(),
                world.rotationW());
        assertQuaternion(new Quaternionf(), world.turnX(), world.turnY(), world.turnZ(), world.turnW());
        // The change is shown once, in the step that followed it; the last step saw none.
        Quaternionf seen = new Quaternionf(local.turnX(), local.turnY(), local.turnZ(), local.turnW());
        assertQuaternion(new Quaternionf(), seen.x, seen.y, seen.z, seen.w);
    }

    @Test
    public void aLocalInstancesTurnIsTheRotationsChangeOverAStep() {
        CgVfxEmitterInstance local = new CgVfxEmitterInstance(LOCAL, 0.3f);
        local.start(0f, 0f, 0f);
        Quaternionf a = new Quaternionf().rotationZ(0.2f).rotateY(0.5f), b = new Quaternionf().rotationX(-0.7f).rotateZ(1.3f);
        local.books().orientation(a.x, a.y, a.z, a.w);
        local.books().schedule(1f / 60f, 0.0, 0.0, 0.0);
        local.books().orientation(b.x, b.y, b.z, b.w);
        local.books().schedule(1f / 60f, 0.0, 0.0, 0.0);
        CgVfxEmitterInstance.Books books = local.books();
        Quaternionf turn = new Quaternionf(books.turnX(), books.turnY(), books.turnZ(), books.turnW());
        // Turning a's frame by the change gives b's.
        Vector3f v = new Vector3f(0.3f, -1f, 2f), viaA = a.transform(new Vector3f(v)), viaB = b.transform(new Vector3f(v));
        turn.transform(viaA);
        assertEquals(viaB.x, viaA.x, 1e-5f);
        assertEquals(viaB.y, viaA.y, 1e-5f);
        assertEquals(viaB.z, viaA.z, 1e-5f);
        local.books().coast(1f / 60f);
        assertQuaternion(new Quaternionf(), books.turnX(), books.turnY(), books.turnZ(), books.turnW());
    }

    @Test
    public void anAttachmentsProportionsTurnItsPlusZToTheWayItFaces() {
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        pose[CgEntityQuery.HEIGHT] = 1.8;
        pose[CgEntityQuery.YAW] = 37.0;
        pose[CgEntityQuery.PITCH] = -20.0;
        pose[CgEntityQuery.BODY_YAW] = 110.0;
        double[] look = CgEntityAttachments.forward(pose, new double[3]);
        Vector3f z = CgEntityAttachments.rotation(CgEntityAttachments.EYES, pose, new Quaternionf())
                .transform(new Vector3f(0f, 0f, 1f));
        assertEquals(look[0], z.x, 1e-5);
        assertEquals(look[1], z.y, 1e-5);
        assertEquals(look[2], z.z, 1e-5);
        // The chest turns with the body, level.
        double body = Math.toRadians(110.0);
        Vector3f chest = CgEntityAttachments.rotation(CgEntityAttachments.CHEST, pose, new Quaternionf())
                .transform(new Vector3f(0f, 0f, 1f));
        assertEquals(-Math.sin(body), chest.x, 1e-5);
        assertEquals(0.0, chest.y, 1e-5);
        assertEquals(Math.cos(body), chest.z, 1e-5);
    }
}
