package com.crystalgraphics.animation.warping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipDefinition;
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
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.animation.CgSequenceSkeletal;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.sun.management.ThreadMXBean;

/**
 * Motion warping: a rush authored to cover 4 m along +x, its whole second a window toward "opponent", lands on targets
 * nearer, further and off its line, and ends turned as its target is. Frames are 1/16 s.
 */
public class CgMotionWarpingTest {

    static final float DT = 1f / 16f;

    static final CgSkeleton SKELETON = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("pelvis", "root", new Vector3f(0, 1, 0)).build();

    /** 4 m along +x in its first second, eased in and out as a rush is, then a quarter second held. */
    static CgAnimSequence rush(CgWarpWindow window) {
        int keys = 21;
        float[] t = new float[keys * 3], r = new float[keys * 4];
        for (int k = 0; k < keys; k++) {
            float a = Math.min(1f, k / 16f);
            t[k * 3] = 4f * (a * a * (3 - 2 * a));
            r[k * 4 + 3] = 1f;
        }
        return CgAnimSequence.builder("rush", 16, keys).track("root", t, r, new float[] {1, 1, 1}).build()
                .with(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)).withWarpWindows(List.of(window)));
    }

    static CgSkeletalMesh mesh(CgAnimSequence rush) {
        CgMesh tri = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int s = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(p, q, s);
        });
        return CgSkeletalMesh.builder(tri, SKELETON).sequence(rush).build();
    }

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    /** A character on flat ground: where it stands and its yaw, moved by its montage's root motion each frame. */
    static final class Mover implements CgSequenceSkeletal {
        final CgAnimInstance body;
        final Vector3d at = new Vector3d();
        float yaw;

        Mover(CgSkeletalMesh mesh) {
            body = new CgAnimInstance(mesh).placedBy(out -> out.translation(at).rotateY(yaw));
        }

        @Override public CgAnimInstance instance() {
            return body;
        }

        @Override public CgAnimSequence clip(String id) {
            return body.mesh().sequence("rush");
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    /** Plays the rush to its end from the origin facing +z, the opponent at ({@code x}, 0, {@code z}) turned {@code yaw}. */
    static Mover play(CgWarpWindow window, double x, double z, float yaw) {
        Mover mover = new Mover(mesh(rush(window)));
        mover.body.motionWarping().target("opponent", x, 0, z, yaw);
        return run(mover);
    }

    /** Plays its rush to the end as a sequence's montage, moving it by the root motion each frame. */
    static Mover run(Mover mover) {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgSequenceDefinition d = CgSequenceDefinition.compile(CgAssetData.of("test:rush", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read("_v: 1\ntick_resolution: 1000\ndisplay_rate: 16\nrange: [0, 1250]\n"
                        + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                        + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: "
                        + "[ { id: s1, range: [0, 1250], clip: test:rush } ] }\n"), null));
        sequencer.play(d, Map.of("caster", mover));
        Matrix4f step = new Matrix4f();
        Vector3f t = new Vector3f();
        Quaternionf q = new Quaternionf();
        for (int f = 0; f < 24; f++) {
            sequencer.evaluate(DT);
            mover.body.tick(DT);
            if (mover.body.montages().consumeRootMotion(1f, step)) {
                step.getTranslation(t).rotateY(mover.yaw);
                mover.at.add(t.x, t.y, t.z);
                step.getNormalizedRotation(q);
                mover.yaw += (float) (2 * Math.atan2(q.y, q.w));
            }
        }
        return mover;
    }

    @Test
    public void itsOwnMotionWithNoTarget() {
        Mover m = play(CgWarpWindow.of("nobody", 0f, 1f), 9, 0, 0);
        assertEquals("the clip's 4 m", 4.0, m.at.x, 1e-3);
        assertEquals(0.0, m.at.z, 1e-3);
    }

    @Test
    public void aRushLandsOnTargetsNearerAndFurtherThanItsClip() {
        CgWarpWindow w = CgWarpWindow.of("opponent", 0f, 1f).noRotation();
        for (double[] target : new double[][] {{2, 0}, {8, 0}, {13, 0}, {5, 3}, {4, -2.5}, {1, 1}}) {
            Mover m = play(w, target[0], target[1], 0);
            String at = "target " + target[0] + ", " + target[1] + ": at " + m.at;
            assertEquals(at, target[0], m.at.x, 0.01);
            assertEquals(at, target[1], m.at.z, 0.01);
            assertEquals(at, 0.0, m.at.y, 1e-4);
        }
    }

    @Test
    public void itStopsItsStandOffShortOfItsTarget() {
        Mover m = new Mover(mesh(rush(CgWarpWindow.of("opponent", 0f, 1f).noRotation())));
        m.body.motionWarping().target("opponent", 6, 0, 3, 0f).standOff("opponent", 0.9f);
        run(m);
        double gap = Math.hypot(6 - m.at.x, 3 - m.at.z);
        assertEquals("at " + m.at, 0.9, gap, 0.01);
        // on the line it came in on: from the origin toward the target
        assertEquals("at " + m.at, Math.atan2(3, 6), Math.atan2(m.at.z, m.at.x), 0.01);
    }

    @Test
    public void itEndsTurnedAsItsTarget() {
        Mover m = play(CgWarpWindow.of("opponent", 0f, 1f), 6, 2, (float) Math.toRadians(60));
        String at = "at " + m.at + " yaw " + Math.toDegrees(m.yaw);
        assertEquals(at, 6.0, m.at.x, 0.01);
        assertEquals(at, 2.0, m.at.z, 0.01);
        assertEquals(at, 60.0, Math.toDegrees(m.yaw), 0.5);
    }

    @Test
    public void aWindowFromTheFile() {
        CgClipDefinition d = CgClipDefinition.compile("test:rush", (Map<?, ?>) CgAssetYaml.read("_v: 1\nwarp_windows: [ { target: opponent, "
                + "start: 0.2, end: 0.9, facing: true, method: constant_rate, max_rotation_rate: 720 } ]\n"));
        CgWarpWindow w = d.settings(id -> null).warpWindows().get(0);
        assertEquals("opponent", w.target());
        assertEquals(0.2f, w.start(), 0f);
        assertEquals(0.9f, w.end(), 0f);
        assertTrue(w.facing() && w.translation() && w.ignoreHeight());
        assertEquals(CgWarpWindow.Rotation.CONSTANT_RATE, w.method());
        assertEquals(720f, w.maxRotationRate(), 0f);
    }

    @Test
    public void warmItAllocatesNothing() {
        CgAnimSequence rush = rush(CgWarpWindow.of("opponent", 0f, 1f).facing(true));
        CgMotionWarping warping = new CgMotionWarping().target("opponent", 7, 0, 3, 0f);
        Matrix4f step = new Matrix4f();
        int[] frame = {0};
        warping.placedBy(out -> out.translation(frame[0] * 0.4, 0, frame[0] * 0.1));
        Runnable lap = () -> {
            for (int f = 0; f < 16; f++) {
                frame[0] = f;
                rush.extractRootMotion(SKELETON, f * DT, DT, false, step);
                warping.process(rush, SKELETON, f * DT, (f + 1) * DT, DT, step);
            }
        };
        for (int i = 0; i < 200; i++) lap.run();
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 1000; i++) lap.run();
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over 16,000 warm steps", bytes < 1600);
    }
}
