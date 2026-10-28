package com.crystalgraphics.sequence.state;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;

/**
 * Sections blend as Unreal's blenders combine them ({@code BlendResultsWithInitial}): the expected values below are
 * worked by hand from its formula, a slot starting at 10.
 */
public class CgSequenceBlendTest {

    private static final String HEAD = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 800]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";

    /** One slot, fov, starting at 10. */
    private static final class Camera implements CgSequenceTarget {
        float fov = 10f;

        @Override
        public int slot(String name) {
            return name.equals("fov") ? 0 : -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            fov = values[0];
        }

        @Override
        public void get(int slot, float[] out) {
            out[0] = fov;
        }
    }

    private static CgSequenceDefinition compile(String sections, Map<String, Object> deps) {
        String yaml = HEAD + "tracks:\n  - id: t1\n    kind: property\n    binding: { role: caster }\n    property: fov\n    sections:\n" + sections;
        CgAssetCompiler.Dependencies d = (CgType kind, String id) -> deps.get(id);
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), d);
    }

    /** {@code sections} played on a fresh camera to tick {@code tick}: the camera's fov then. */
    private static float at(String sections, int tick) {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        Camera camera = new Camera();
        sequencer.play(compile(sections, Map.of()), Map.of("caster", camera));
        sequencer.evaluate(tick / 1000f);
        return camera.fov;
    }

    @Test
    public void absolutesAverageByWeight() {
        assertEquals(4f, at("      - { id: s1, range: [0, 800], value: [[0, 2]] }\n"
                + "      - { id: s2, range: [0, 800], row: 1, value: [[0, 6]] }\n", 100), 1e-5f);
        // Weighted half: (2·1 + 6·0.5) / 1.5
        assertEquals(10f / 3f, at("      - { id: s1, range: [0, 800], value: [[0, 2]] }\n"
                + "      - { id: s2, range: [0, 800], row: 1, value: [[0, 6]], weight: [[0, 0.5]] }\n", 100), 1e-5f);
    }

    @Test
    public void aPartialAbsoluteRampsFromTheInitialValue() {
        // 10·0.75 + 2·0.25, over 0.25 + 0.75
        assertEquals(8f, at("      - { id: s1, range: [0, 800], value: [[0, 2]], weight: [[0, 0.25]] }\n", 100), 1e-5f);
        // A linear ease in over 100 ticks, halfway: 10·0.5 + 2·0.5
        assertEquals(6f, at("      - { id: s1, range: [0, 800], value: [[0, 2]], ease: { in: 100, out: 0, in_fn: linear } }\n", 50), 1e-5f);
        // Unreal's default ease, cubic in-out, a quarter of the way: 0.5·(0.5)³ = 0.0625
        assertEquals(10f * 0.9375f + 2f * 0.0625f, at("      - { id: s1, range: [0, 800], value: [[0, 2]], ease: { in: 100, out: 0 } }\n", 25), 1e-4f);
    }

    @Test
    public void automaticEaseTakesItsOverlapsAsUnrealsUpdateEasingDoes() {
        CgSequenceDefinition d = compile("      - { id: s1, range: [0, 400], value: [[0, 2]], ease: { auto: true } }\n"
                + "      - { id: s2, range: [300, 800], value: [[0, 6]], ease: { auto: true } }\n"
                + "      - { id: s3, range: [500, 600], value: [[0, 9]], ease: { auto: true } }\n", Map.of());
        List<CgSequenceSection> s = d.tracks().get(0).sections();
        assertEquals(0, s.get(0).ease().in());
        assertEquals(100, s.get(0).ease().out());          // s2 starts 100 before s1 ends
        assertEquals(100, s.get(1).ease().in());           // s1 ends 100 after s2 starts
        assertEquals(0, s.get(1).ease().out());
        assertEquals(25, s.get(2).ease().in());            // wholly inside s2: a quarter of its 100 each way
        assertEquals(25, s.get(2).ease().out());
    }

    @Test
    public void additiveRelativeAndOverrideCombineInUnrealsOrder() {
        assertEquals(5f, at("      - { id: s1, range: [0, 800], value: [[0, 2]] }\n"
                + "      - { id: s2, range: [0, 800], row: 1, blend: additive, value: [[0, 3]] }\n", 100), 1e-5f);
        assertEquals(13f, at("      - { id: s1, range: [0, 800], blend: additive, value: [[0, 3]] }\n", 100), 1e-5f);   // on the initial value
        assertEquals(11f, at("      - { id: s1, range: [0, 800], blend: relative, value: [[0, 1]] }\n", 100), 1e-5f);   // initial + 1
        // An override at half weight lerps what came before toward it.
        assertEquals(4f, at("      - { id: s1, range: [0, 800], value: [[0, 2]] }\n"
                + "      - { id: s2, range: [0, 800], row: 1, blend: override, value: [[0, 6]], weight: [[0, 0.5]] }\n", 100), 1e-5f);
        // Additive from base: what the channel moved since its section began, 7 − 3, on top of the absolute 2.
        assertEquals(6f, at("      - { id: s1, range: [0, 800], value: [[0, 2]] }\n"
                + "      - { id: s2, range: [0, 800], row: 1, blend: additive_from_base, value: [[0, 3, linear], [200, 7]] }\n", 200), 1e-5f);
    }

    @Test
    public void twoSequencesKeyingOneGlobalRestoreItOnlyWhenBothEnd() {
        CgParameterCollection grade = CgParameterCollection.of("sqblend");
        CgParameterCollection.Scalar dark = grade.scalar("dark", 0.25f);
        Map<String, Object> deps = Map.of("mymod:parameters/grade", grade);
        String global = HEAD + "tracks:\n  - id: t1\n    kind: global\n    collection: mymod:parameters/grade\n    sections:\n";
        CgSequenceDefinition a = compile2(global + "      - { id: s1, range: [0, 400], completion: restore_state, scalars: { dark: [[0, 1]] } }\n", deps);
        CgSequenceDefinition b = compile2(global + "      - { id: s1, range: [0, 800], completion: restore_state, scalars: { dark: [[0, 3]] } }\n", deps);
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        sequencer.play(a, Map.of("caster", new Camera()));
        sequencer.play(b, Map.of("caster", new Camera()));
        sequencer.evaluate(0.1f);
        assertEquals(2f, dark.get(), 1e-5f);                 // both: (1 + 3) / 2
        sequencer.evaluate(0.4f);
        assertEquals(3f, dark.get(), 1e-5f);                 // a ended; b still holds it: no restore yet
        sequencer.evaluate(0.4f);
        sequencer.evaluate(0.1f);
        assertEquals(0.25f, dark.get(), 1e-5f);              // both ended: what it was before either
    }

    @Test
    public void keepStateLeavesTheLastValue() {
        CgParameterCollection grade = CgParameterCollection.of("sqkeep");
        CgParameterCollection.Scalar dark = grade.scalar("dark", 0.25f);
        CgSequenceDefinition a = compile2(HEAD + "tracks:\n  - id: t1\n    kind: global\n    collection: mymod:parameters/grade\n"
                + "    sections:\n      - { id: s1, range: [0, 400], completion: keep_state, scalars: { dark: [[0, 1]] } }\n",
                Map.of("mymod:parameters/grade", grade));
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        sequencer.play(a, Map.of("caster", new Camera()));
        sequencer.evaluate(0.1f);
        sequencer.evaluate(0.5f);
        sequencer.evaluate(0.1f);
        assertEquals(1f, dark.get(), 1e-5f);
    }

    private static CgSequenceDefinition compile2(String yaml, Map<String, Object> deps) {
        CgAssetCompiler.Dependencies d = (CgType kind, String id) -> deps.get(id);
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), d);
    }
}
