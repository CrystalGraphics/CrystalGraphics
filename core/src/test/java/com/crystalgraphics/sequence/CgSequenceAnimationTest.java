package com.crystalgraphics.sequence;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;

/** A sequence's {@code animation} track on a role's instance, and the instance's montage slot under it. */
public class CgSequenceAnimationTest {

    /** A root and its elbow, the elbow rising from 1 to 5 over the one-second clip {@code bend}. */
    private static CgSkeletalMesh arm() {
        CgSkeleton skeleton = CgSkeleton.builder().bone("root", null, new Vector3f()).bone("Elbow", "root", new Vector3f(0, 1, 0)).build();
        float[] rotations = new float[5 * 4], translations = new float[5 * 3];
        for (int k = 0; k < 5; k++) {
            rotations[k * 4 + 3] = 1f;
            translations[k * 3 + 1] = 1f + k;
        }
        CgAnimSequence bend = CgAnimSequence.builder("bend", 4, 5).track("elbow", translations, rotations, new float[]{1, 1, 1}).build();
        CgMesh triangle = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int a = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int b = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int c = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(a, b, c);
        });
        return CgSkeletalMesh.builder(triangle, skeleton).sequence(bend).build();
    }

    /** Where the elbow is as last posed. */
    private static double elbow(CgAnimInstance arm) {
        double[] point = new double[3];
        arm.socket(arm.socket("elbow"), point, null);
        return point[1];
    }

    private static final class Fighter implements CgSequenceSkeletal {
        final CgAnimInstance body;

        Fighter(CgAnimInstance body) {
            this.body = body;
        }

        @Override
        public CgAnimInstance instance() {
            return body;
        }

        @Override
        public CgAnimSequence clip(String id) {
            return body.mesh().sequence(id.substring(id.lastIndexOf('/') + 1));
        }

        @Override
        public int slot(String name) {
            return -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
        }

        @Override
        public void get(int slot, float[] out) {
        }
    }

    @Test
    public void eachSectionPlaysItsClipOnItsTicksAndLeavingFreesThePose() {
        CgSequenceDefinition d = CgSequenceCompiler.compile(CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(
                "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 800]\n"
                        + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
                        + "tracks:\n  - id: t1\n    kind: animation\n    binding: { role: caster }\n    sections:\n"
                        + "      - { id: s1, range: [0, 400], clip: mymod:anim/bend, start_offset: 250, slot: DefaultSlot }\n"
                        + "      - { id: s2, range: [400, 800], clip: mymod:anim/bend, play_rate: 2, slot: DefaultSlot }\n"), null),
                (kind, id) -> null);
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        CgAnimInstance arm = new CgAnimInstance(arm());
        sequencer.play(d, Map.of("caster", new Fighter(arm)));

        sequencer.evaluate(0.1f);                                        // tick 100: 0.1 s in, plus 0.25 s offset
        assertEquals(1 + 0.35 * 4, elbow(arm.evaluate()), 1e-4);
        sequencer.evaluate(0.4f);                                        // tick 500: the second, 0.1 s at twice the rate
        assertEquals(1 + 0.2 * 4, elbow(arm.evaluate()), 1e-4);
        sequencer.evaluate(0.2f);                                        // tick 700: 0.3 s at twice the rate
        assertEquals(1 + 0.6 * 4, elbow(arm.evaluate()), 1e-4);
        sequencer.evaluate(0.2f);                                        // finished at 800, past both: its rest pose
        assertEquals(1, elbow(arm.evaluate()), 1e-4);
    }

    @Test
    public void aSlotKeepsItsSectionsTheRestOfItsWeightAndNormalisesPastOne() {
        CgSkeletalMesh mesh = arm();
        CgAnimSequence bend = mesh.sequence("bend");
        CgAnimInstance arm = new CgAnimInstance(mesh).play(CgAnimSection.of(bend).until(Double.POSITIVE_INFINITY).build());
        // Its section at 0 s (1), a quarter of the slot at 1 s (5): Unreal's source weight, 1 - the slot's.
        assertEquals(0.75 * 1 + 0.25 * 5, elbow(arm.slot(bend, 1f, 0.25f).evaluate()), 1e-4);
        // Two entries of weight 1 each: normalised to a half each, the section gone.
        assertEquals((2 + 4) / 2.0, elbow(arm.slot(bend, 0.25f, 1f).slot(bend, 0.75f, 1f).evaluate()), 1e-4);
        assertEquals(1, elbow(arm.evaluate()), 1e-4);                    // entries last one pose
    }
}
