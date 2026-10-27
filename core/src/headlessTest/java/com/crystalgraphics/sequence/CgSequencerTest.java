package com.crystalgraphics.sequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;

public class CgSequencerTest {

    /** A thousand ticks a second: tick t is t milliseconds in. */
    private static final String HEAD = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 800]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";

    /** Every key form: a constant, a linear, a cubic on stored tangents, a broken one, auto tangents, weighted both. */
    private static final String KEYS = "[[0, 1, constant], [100, 2, linear], [200, 4, cubic, user, 0.01, 0.01],"
            + " [400, 0, cubic, break, -0.02, 0.0], [500, 2], [600, 3, cubic, user, 0.0, 0.01, { arrive_weight: 0, leave_weight: 40, weight_mode: leave }],"
            + " [700, 1, cubic, user, 0.0, 0.0, { arrive_weight: 30, leave_weight: 0, weight_mode: arrive }]]";

    /** A role with a few named slots, keeping what was last written. */
    private static final class Role implements CgSequenceSpawned {
        final List<String> names;
        final float[][] values;
        int writes;
        boolean destroyed;

        @Override
        public void destroy(boolean graceful) {
            destroyed = true;
        }

        Role(String... names) {
            this.names = List.of(names);
            this.values = new float[names.length][4];
        }

        @Override
        public int slot(String name) {
            return names.indexOf(name);
        }

        @Override
        public void set(int slot, float[] v, int mask) {
            writes++;
            for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) values[slot][i] = v[i];
        }

        @Override
        public void get(int slot, float[] out) {
            System.arraycopy(values[slot], 0, out, 0, 4);
        }

        float get(String name) {
            return values[names.indexOf(name)][0];
        }

        @Override
        public String toString() {
            return "role " + names;
        }
    }

    private static CgSequenceDefinition compile(String yaml, Map<String, Object> deps) {
        CgAssetCompiler.Dependencies d = (CgType kind, String id) -> deps.get(id);
        return CgSequenceCompiler.compile(CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), d);
    }

    private static CgSequencer sequencer() {
        CgWorld world = new CgWorld();
        return world.add(new CgSequencer());
    }

    @Test
    public void aPropertyTrackSamplesEveryKeyFormAsItsCurve() {
        CgSequenceDefinition d = compile(HEAD + "tracks:\n  - id: t1\n    kind: property\n    binding: { role: caster }\n"
                + "    property: fov\n    sections:\n      - { id: s1, range: [0, 800], value: { keys: " + KEYS + " } }\n", Map.of());
        CgRichCurve curve = CgRichCurve.decode(Map.of("keys", CgAssetYaml.read(KEYS)));
        CgSequencer sequencer = sequencer();
        Role caster = new Role("fov");
        CgSequencePlayer player = sequencer.play(d, Map.of("caster", caster));

        // Hand-worked on Unreal's formulas: a constant holds, a linear interpolates, a cubic is the Bezier on its tangents.
        sequencer.evaluate(0.05f);
        assertEquals(1f, caster.get("fov"), 1e-4f);
        sequencer.evaluate(0.1f);
        assertEquals(3f, caster.get("fov"), 1e-4f);                     // tick 150: halfway from 2 to 4
        sequencer.evaluate(0.15f);
        // tick 300: (4 + 3·(4 + 0.01·200/3) + 3·(0 + 0.02·200/3) + 0) / 8
        assertEquals(2.75f, caster.get("fov"), 1e-4f);

        // Then at every frame, at a rate that is no divisor of the keys, the player writes what the curve evaluates.
        while (true) {
            sequencer.evaluate(1f / 37f);
            if (player.finished()) break;
            assertEquals("at tick " + player.tick(), curve.eval(player.tick()), caster.get("fov"), 1e-4f);
        }
        // At 800 its section has ended: restored to what it was before, as a completion it leaves to the project does.
        assertEquals(0f, caster.get("fov"), 0f);
    }

    @Test
    public void aTransformMovesItsLocatorAndASegmentBoundarySwitchesSections() {
        CgSequenceDefinition d = compile(HEAD + "locators:\n  - { id: k1, name: ball }\ntracks:\n"
                + "  - id: t1\n    kind: transform\n    binding: { locator: k1 }\n    sections:\n"
                + "      - { id: s1, range: [0, 800], translation: [[[0, 0, linear], [800, 8]], [[0, 1]], [[0, 2]]] }\n"
                + "  - id: t2\n    kind: visibility\n    binding: { role: caster }\n    sections:\n"
                + "      - { id: s2, range: [0, 400], visible: [[0, true]] }\n"
                + "      - { id: s3, range: [400, 800], visible: [[400, false]] }\n", Map.of());
        CgSequencer sequencer = sequencer();
        Role caster = new Role("visible");
        CgSequencePlayer player = sequencer.play(d, Map.of("caster", caster));
        sequencer.evaluate(0.2f);
        assertEquals(1f, caster.get("visible"), 0f);
        assertEquals(2f, player.locator(0).translation()[0], 1e-5f);
        assertEquals(1f, player.locator(0).translation()[1], 0f);
        sequencer.evaluate(0.3f);
        assertEquals(0f, caster.get("visible"), 0f);                     // tick 500: the second section's
        assertEquals(5f, player.locator(0).translation()[0], 1e-5f);
    }

    @Test
    public void aGlobalTrackWritesItsCollection() {
        CgParameterCollection skill = CgParameterCollection.of("sqtest");
        CgParameterCollection.Scalar darken = skill.scalar("darken", 0f);
        CgSequenceDefinition d = compile(HEAD + "tracks:\n  - id: t1\n    kind: global\n    collection: mymod:parameters/skill\n"
                + "    sections:\n      - { id: s1, range: [0, 800], scalars: { darken: [[0, 0, linear], [800, 1]] } }\n",
                Map.of("mymod:parameters/skill", skill));
        CgSequencer sequencer = sequencer();
        sequencer.play(d, Map.of("caster", new Role()));
        sequencer.evaluate(0.4f);
        assertEquals(0.5f, darken.get(), 1e-5f);
    }

    @Test
    public void aSlotItsTargetLacksFailsAtPlayAndASpawnableBindsWhenMade() {
        CgSequenceDefinition d = compile(HEAD + "spawnables:\n  - { id: c1, camera: { cine: { fov: 40 } } }\ntracks:\n"
                + "  - id: t1\n    kind: property\n    binding: { role: caster }\n    property: fov\n    sections:\n"
                + "      - { id: s1, range: [0, 800], value: [[0, 50]] }\n"
                + "  - id: t2\n    kind: property\n    binding: { camera: c1 }\n    property: fov\n    sections:\n"
                + "      - { id: s2, range: [0, 800], value: [[0, 60]] }\n", Map.of());
        Role camera = new Role("fov");
        try {
            sequencer().spawner("camera", (p, s, at) -> camera).play(d, Map.of("caster", new Role("visible")));
            fail("played with a slot its role lacks");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("mymod:s: tracks.t1.sections.s1.value: role [visible] has no fov"));
        }
        CgSequencer sequencer = sequencer().spawner("camera", (p, s, at) -> camera);
        Role caster = new Role("fov");
        CgSequencePlayer player = sequencer.play(d, Map.of("caster", caster));
        assertEquals(0, camera.writes);                                  // made in the first frame's spawn phase
        sequencer.evaluate(0.1f);
        assertEquals(50f, caster.get("fov"), 0f);
        assertEquals(60f, camera.get("fov"), 0f);
        player.stop();
        sequencer.evaluate(0.1f);
        assertEquals(0, sequencer.players().size());
        assertTrue(camera.destroyed);
    }

    @Test
    public void aRoleNotGivenFailsUnlessItMaySkip() {
        CgSequenceDefinition d = compile(HEAD.replace("kind: caster }", "kind: caster }\n  - { id: b2, name: target, kind: target, absent: skip }"), new HashMap<>());
        sequencer().play(d, Map.of("caster", new Role()));
        try {
            sequencer().play(d, Map.of("target", new Role()));
            fail("played without its caster");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("caster"));
        }
    }
}
