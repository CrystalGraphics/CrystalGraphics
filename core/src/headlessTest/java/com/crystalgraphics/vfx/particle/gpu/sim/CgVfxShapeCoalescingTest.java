package com.crystalgraphics.vfx.particle.gpu.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;
import com.crystalgraphics.vfx.particle.form.CgVfxFormContext;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;

/** Shape coalescing: definitions raised into one shape, each drawing what its own forms draw. */
public class CgVfxShapeCoalescingTest {

    private static final Map<String, Object> RISING = Map.of("keys", List.of(List.of(0.0, 1.0), List.of(1.0, 4.0, "linear")));

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Test
    public void aConstantAndARangeOfOneInputShareAShapeOnceRaised_eachDrawingItsOwnValues() {
        CgVfxEmitter still = pushed("still", List.of(1.0, 2.0, 3.0));
        CgVfxEmitter ranged = pushed("ranged", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(2.0, 4.0, 6.0))));
        assertNotEquals(CgVfxShape.of(still).key(), CgVfxShape.of(ranged).key());

        CgVfxShapeCoalescing table = new CgVfxShapeCoalescing(0);
        table.include(List.of(still, ranged));
        CgVfxShape a = table.shape(still, 0), b = table.shape(ranged, 0);
        assertEquals(a.key(), b.key());
        assertEquals(1, table.shapes());
        // The constant, now a range with equal ends, gives its value for every particle.
        for (int p = 0; p < 50; p++) {
            CgVfxFormContext ctx = context(p);
            for (int k = 0; k < 3; k++) assertEquals(k + 1f, a.form(0).compiled().channel(ctx, k), 0f);
        }
        assertSameValues(ranged.moduleForms().get(0).compiled(), b.form(0).compiled());
    }

    @Test
    public void aRangeAndACurveRaiseToRandomRangeOfCurves_drawingWhatEachDrewBefore() {
        CgVfxEmitter ranged = pushed("ranged", Map.of("range", List.of(List.of(-1.0, 0.0, 1.0), List.of(2.0, 4.0, 6.0))));
        CgVfxEmitter curved = pushed("curved", Map.of("curves", List.of(RISING, RISING, RISING), "by", "age"));
        CgVfxShapeCoalescing table = new CgVfxShapeCoalescing(0);
        table.include(List.of(ranged, curved));
        CgVfxShape a = table.shape(ranged, 0), b = table.shape(curved, 0);
        assertEquals(a.key(), b.key());
        assertTrue(a.key(), a.key().contains("random_range"));
        assertSameValues(ranged.moduleForms().get(0).compiled(), a.form(0).compiled());
        assertSameValues(curved.moduleForms().get(0).compiled(), b.form(0).compiled());
    }

    @Test
    public void aSpawnValueALookLacksIsRaisedToWhatItsStepReadsWithoutOne() {
        CgVfxEmitter still = spawning("still", List.of(init()));
        CgVfxEmitter moving = spawning("moving", List.of(init(), Map.of("id", "velocity", "module", "crystalgraphics:add_velocity",
                "inputs", Map.of("linear", Map.of("curves", List.of(RISING, RISING, RISING), "by", "loop")))));
        CgVfxShapeCoalescing table = new CgVfxShapeCoalescing(0);
        table.include(List.of(still, moving));
        CgVfxShape a = table.shape(still, 0);
        assertEquals(a.key(), table.shape(moving, 0).key());
        CgVfxSpawnForm velocity = a.spawnForm(a.spawnFormOf(CgVfxSpawnInput.VELOCITY));
        for (int p = 0; p < 20; p++) {
            for (int k = 0; k < 3; k++) assertEquals(0f, velocity.compiled().channel(context(p), k), 0f);
        }
    }

    @Test
    public void underItsBudgetNothingIsRaised() {
        CgVfxEmitter still = pushed("still", List.of(1.0, 2.0, 3.0));
        CgVfxEmitter ranged = pushed("ranged", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(2.0, 4.0, 6.0))));
        CgVfxShapeCoalescing table = new CgVfxShapeCoalescing(2);
        table.include(List.of(still, ranged));
        assertNotEquals(table.shape(still, 0).key(), table.shape(ranged, 0).key());
        assertEquals(2, table.shapes());
    }

    @Test
    public void aLaterIncludeMovesADefinitionItRaises_andSaysSo() {
        CgVfxEmitter still = pushed("still", List.of(1.0, 2.0, 3.0));
        CgVfxEmitter ranged = pushed("ranged", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(2.0, 4.0, 6.0))));
        CgVfxShapeCoalescing table = new CgVfxShapeCoalescing(0);
        assertEquals(List.of(), table.include(List.of(still)));
        String alone = table.shape(still, 0).key();
        assertEquals(List.of(still), table.include(List.of(ranged)));
        assertNotEquals(alone, table.shape(still, 0).key());
        assertEquals(table.shape(ranged, 0).key(), table.shape(still, 0).key());
    }

    /** {@code raised} gives every particle what {@code given} does. */
    private static void assertSameValues(CgVfxFormInput given, CgVfxFormInput raised) {
        assertEquals(given.components(), raised.components());
        for (int p = 0; p < 200; p++) {
            CgVfxFormContext ctx = context(p);
            for (int k = 0; k < given.components(); k++) {
                float want = given.channel(ctx, k);
                assertEquals("particle " + p + " channel " + k, want, raised.channel(ctx, k), 1e-5f * (1f + Math.abs(want)));
            }
        }
    }

    private static CgVfxFormContext context(int p) {
        return new CgVfxFormContext().seed(0x5EED).particle(p).age((p % 17) / 16f).particleAge(p * 0.01f).loop((p % 11) / 10f, 0);
    }

    /** An emitter pushed by {@code acceleration}, given in any form. */
    private static CgVfxEmitter pushed(String name, Object acceleration) {
        Map<String, Object> tree = emitter();
        tree.put("particle_update", List.of(Map.of("id", "push", "module", "crystalgraphics:acceleration",
                "inputs", Map.of("acceleration", acceleration))));
        return CgVfxEmitterAssets.compile(name, tree, null);
    }

    private static CgVfxEmitter spawning(String name, List<Object> spawn) {
        Map<String, Object> tree = emitter();
        tree.put("particle_spawn", spawn);
        return CgVfxEmitterAssets.compile(name, tree, null);
    }

    private static Map<String, Object> emitter() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("properties", Map.of("capacity", 64));
        tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        return tree;
    }

    private static Map<String, Object> init() {
        return Map.of("id", "init", "module", "crystalgraphics:initialize_particle", "inputs", Map.of("lifetime", 3.0));
    }
}
