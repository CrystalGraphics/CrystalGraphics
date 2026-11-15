package com.crystalgraphics.animation.warping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
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
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.api.animation.CgWarpWindow;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.animation.CgSequenceSkeletal;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * Kakarot's Goku rushing an opponent wherever they stand: his back attack, its travel moved from his pelvis onto his
 * root, its rush a warp window toward "opponent", played as a sequence's montage at 60 Hz. He lands on targets a
 * quarter, half and one and a half times his clip's reach, and off its line, and ends turned as his target is, as
 * the window closes; the clip's settle after it is its own.
 */
public class CgMotionWarpingKakarotTest {

    private static final Path KK = Paths.get("src/main/resources/assets/kk");
    private static final float DT = 1f / 60f;
    /** Where the rush ends in the clip: its travel is over by then. */
    private static final float RUSH_END = 1.0f;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    static final class Goku implements CgSequenceSkeletal {
        final CgAnimInstance body;
        final CgAnimSequence rush;
        final Vector3d at = new Vector3d();
        float yaw;
        /** Where it stood, and its turn, as its window closed. */
        Vector3d landed;
        float landedYaw;

        Goku(CgSkeletalMesh mesh, CgAnimSequence rush) {
            this.rush = rush;
            body = new CgAnimInstance(mesh).placedBy(out -> out.translation(at).rotateY(yaw));
        }

        @Override public CgAnimInstance instance() {
            return body;
        }

        @Override public CgAnimSequence clip(String id) {
            return rush;
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    @Test
    public void gokusRushLandsOnHisOpponentNearerAndFurtherThanHisClip() {
        CgSkeletalMesh mesh = CgMeshLoader.skeletal(KK.resolve("models/Art/MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb").toAbsolutePath().toString());
        CgAnimSequence raw = CgMeshLoader.sequences(KK.resolve("models/Art/ANM/cpl/C001A/BTL/C001A_BTL_BackAtk01_Atk.glb")
                .toAbsolutePath().toString()).get(0);
        CgAnimSequence encoded = CgClipModifiers.encodeRootBone(raw, mesh.skeleton(), "root", "pelvis");
        Vector3f own = encoded.extractRootMotion(mesh.skeleton(), 0f, RUSH_END, false, new Matrix4f()).getTranslation(new Vector3f());
        double reach = Math.hypot(own.x, own.z);
        System.out.printf("BackAtk01's rush: %.2f m in %.1f s%n", reach, RUSH_END);

        CgAnimSequence plain = encoded.with(encoded.settings().withWarpWindows(List.of(CgWarpWindow.of("opponent", 0f, RUSH_END).noRotation())));
        double[] factors = {0.25, 0.5, 1.5};
        double[] turns = {0, 40, -70};
        for (double factor : factors) {
            for (double turn : turns) {
                double a = Math.toRadians(turn), x = (own.x * Math.cos(a) + own.z * Math.sin(a)) * factor,
                        z = (-own.x * Math.sin(a) + own.z * Math.cos(a)) * factor;
                Goku g = play(mesh, plain, x, z, 0f);
                double off = Math.hypot(g.landed.x - x, g.landed.z - z);
                System.out.printf("  target x%.2f turned %+.0f (%.1f, %.1f): lands %.1f cm off%n", factor, turn, x, z, off * 100);
                assertTrue("x" + factor + " turned " + turn + ": " + off * 100 + " cm off", off < 0.02);
            }
        }
        // turned as the opponent is, while closing on them
        CgAnimSequence turning = encoded.with(encoded.settings().withWarpWindows(List.of(CgWarpWindow.of("opponent", 0f, RUSH_END))));
        Goku g = play(mesh, turning, own.x * 0.6, own.z * 0.6, (float) Math.toRadians(150));
        assertEquals("his turn", 150.0, Math.toDegrees(g.landedYaw), 1.0);
        assertTrue("on his opponent: " + g.landed, Math.hypot(g.landed.x - own.x * 0.6, g.landed.z - own.z * 0.6) < 0.02);
    }

    private static Goku play(CgSkeletalMesh mesh, CgAnimSequence rush, double x, double z, float yaw) {
        Goku g = new Goku(mesh, rush);
        g.body.motionWarping().target("opponent", x, 0, z, yaw);
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        int ms = Math.round(rush.duration() * 1000);
        CgSequenceDefinition d = CgSequenceDefinition.compile(CgAssetData.of("test:rush", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read("_v: 1\ntick_resolution: 1000\ndisplay_rate: 60\nrange: [0, " + ms + "]\n"
                        + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                        + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: "
                        + "[ { id: s1, range: [0, " + ms + "], clip: kk:rush } ] }\n"), null));
        sequencer.play(d, Map.of("caster", g));
        Matrix4f step = new Matrix4f();
        Vector3f t = new Vector3f();
        Quaternionf q = new Quaternionf();
        boolean wasOpen = false;
        Vector3d before = new Vector3d();
        for (int f = 0, frames = (int) (rush.duration() / DT) + 10; f < frames; f++) {
            sequencer.evaluate(DT);
            g.body.tick(DT);
            before.set(g.at);
            float yawBefore = g.yaw;
            if (g.body.montages().consumeRootMotion(1f, step)) {
                step.getTranslation(t).rotateY(g.yaw);
                g.at.add(t.x, t.y, t.z);
                step.getNormalizedRotation(q);
                g.yaw += (float) (2 * Math.atan2(q.y, q.w));
            }
            boolean open = g.body.motionWarping().open() != null;
            if (wasOpen && !open && g.landed == null) {
                // this frame's step was already past the window
                g.landed = new Vector3d(before);
                g.landedYaw = yawBefore;
            }
            wasOpen = open;
        }
        return g;
    }
}
