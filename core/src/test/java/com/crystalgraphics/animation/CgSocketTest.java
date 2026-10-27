package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B4's gate: a socket is where Unreal puts one (placement × bone × relative), found as Unreal finds one (a socket, then
 * a bone, ignoring case, then the instance itself); and an effect on a posed hand never lags it, ticking against this
 * frame's pose, its drawn origin re-read late.
 */
public class CgSocketTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:socket")).build();

    /** A root and its elbow; the elbow's rotation keyed from 0 to 90 degrees about z over a second, rising a block a key. */
    private static CgSkeletalMesh arm() {
        CgSkeleton skeleton = CgSkeleton.builder().bone("root", null, new Vector3f()).bone("Elbow", "root", new Vector3f(0, 1, 0)).build();
        float[] rotations = new float[5 * 4], translations = new float[5 * 3];
        for (int k = 0; k < 5; k++) {
            Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(22.5 * k));
            rotations[k * 4] = q.x;
            rotations[k * 4 + 1] = q.y;
            rotations[k * 4 + 2] = q.z;
            rotations[k * 4 + 3] = q.w;
            translations[k * 3 + 1] = 1f + k;
        }
        CgAnimSequence bend = CgAnimSequence.builder("bend", 4, 5).track("elbow", translations, rotations, new float[]{1, 1, 1}).build();
        CgMesh triangle = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int a = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int b = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int c = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(a, b, c);
        });
        return CgSkeletalMesh.builder(triangle, skeleton).sequence(bend)
                .socket(CgSocket.of("Palm", "elbow", new Vector3f(0, 0.5f, 0))).build();
    }

    private static CgAnimInstance playing(CgSkeletalMesh mesh) {
        return new CgAnimInstance(mesh).place(10, 64, -3, new Matrix4f().scaling(2f))
                .play(CgAnimSection.of(mesh.sequence("bend")).until(Double.POSITIVE_INFINITY).build());
    }

    @Test
    public void aSocketIsPlaceTimesBoneTimesRelative() {
        CgAnimInstance arm = playing(arm()).seek(0.5).evaluate();       // key 2: up 3, turned 45 degrees
        double[] point = new double[3];
        Quaternionf turn = new Quaternionf();
        arm.socket(arm.socket("palm"), point, turn);
        Matrix4f expected = new Matrix4f().scaling(2f).translate(0, 3, 0).rotateZ((float) Math.toRadians(45)).translate(0, 0.5f, 0);
        assertEquals(10 + expected.m30(), point[0], 1e-5);
        assertEquals(64 + expected.m31(), point[1], 1e-5);
        assertEquals(-3 + expected.m32(), point[2], 1e-5);
        Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(45));
        assertEquals(Math.abs(q.dot(turn)), 1f, 1e-5f);
    }

    @Test
    public void aSocketIsFoundAsUnrealFindsOne() {
        CgAnimInstance arm = playing(arm()).seek(0.5).evaluate();
        assertTrue("the socket, ignoring case", (arm.socket("PALM") & (1 << 30)) != 0);
        assertEquals("else a bone, ignoring case", 1, arm.socket("elbow"));
        assertEquals(CgAnimInstance.NO_SOCKET, arm.socket("tail"));
        double[] point = new double[3];
        arm.socket(CgAnimInstance.NO_SOCKET, point, null);                // neither: where it stands
        assertEquals(10, point[0], 0);
        assertEquals(64, point[1], 0);
        assertEquals(-3, point[2], 0);
    }

    /** Records where it stood each tick: its logic's view of the socket. */
    static final class OnHand extends CgVfxEffect<OnHand> {
        double x, y, z;

        OnHand() {
            super(LOOK, 0, 0, 0);
        }

        @Override
        protected void tick(float dt) {
            x = originX;
            y = originY;
            z = originZ;
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @Test
    public void anEffectOnAPosedHandNeverLagsIt() {
        CgSkeletalMesh mesh = arm();
        try (CgWorld world = new CgWorld()) {
            CgAnimation animation = world.add(new CgAnimation());
            CgAnimInstance arm = animation.add(playing(mesh));
            CgVfxSystem vfx = new CgVfxSystem();
            OnHand hand = vfx.play(new OnHand().follow(arm, "palm"));
            double[] clock = {0};
            world.schedule().add(CgTickGroup.EFFECTS, "test:vfx", dt -> vfx.update(clock[0]));
            CgAnimInstance reference = playing(mesh);
            int palm = reference.socket("palm");
            double[] expected = new double[3];
            int checked = 0;
            for (int f = 0; f < 90; f++) {
                clock[0] = f / 60.0;
                CgFrameClock.set(clock[0]);
                world.frame(null);
                if (f < 2) continue;                                   // the system's first update only sets its clock
                // This frame's pose, worked out apart: the effect ticked against exactly it, not the frame before's
                reference.seek(arm.time()).evaluate().socket(palm, expected, null);
                assertEquals("frame " + f, expected[0], hand.x, 1e-9);
                assertEquals("frame " + f, expected[1], hand.y, 1e-9);
                assertEquals("frame " + f, expected[2], hand.z, 1e-9);
                checked++;
            }
            assertTrue(checked > 80);

            // Moved after the effects ticked: the late re-read puts what draws on it
            arm.place(20, 70, 5, new Matrix4f().scaling(2f));
            vfx.lateAttach();
            arm.socket(palm, expected, null);
            assertEquals(expected[0], hand.originX(), 1e-9);
            assertEquals(expected[1], hand.originY(), 1e-9);

            // No longer posed: what follows it stops
            animation.remove(arm);
            vfx.lateAttach();
            assertFalse(hand.state() == CgVfxEffect.State.PLAYING);
        }
    }

    @Test
    public void socketsOnBonesTheSkeletonLacksAreRefused() {
        CgSkeletalMesh mesh = arm();
        try {
            mesh.withSockets(List.of(CgSocket.of("x", "tail", new Vector3f())));
            throw new AssertionError("a socket on a missing bone was taken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tail"));
        }
    }
}
