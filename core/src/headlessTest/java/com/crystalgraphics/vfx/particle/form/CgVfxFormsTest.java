package com.crystalgraphics.vfx.particle.form;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.layout.CgFormClass;
import com.crystalgraphics.object.value.CgValueForm;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

/** Every form of a module input compiles to its form class and evaluates as the format says it does. */
public class CgVfxFormsTest {

    private static final String MODULE = "test:sparks/drift";
    private static final CgType INPUTS = CgTypes.create().declare("test:drift", CgTypeKind.STRUCT)
            .property("speed", CgValueType.FLOAT)
            .property("axis", CgValueType.VEC3)
            .property("count", CgValueType.FLOAT).forms(CgValueForm.CONSTANT)
            .property("per_loop", CgValueType.FLOAT).rolls(CgPropertyMeta.Rolls.LOOP)
            .register();

    private final CgVfxFormCompiler compiler = new CgVfxFormCompiler(new CgVfxCurveBlock(), id -> null);
    private final CgVfxFormContext ctx = new CgVfxFormContext().seed(7).particle(3);

    private CgVfxFormInput compile(String input, Object value) {
        return compiler.compile(MODULE, INPUTS.property(input), value);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void constantsAreWords() {
        CgVfxFormInput c = compile("axis", Arrays.asList(0, 1, 2));
        assertEquals(CgFormClass.ROW, c.formClass());
        float[] out = new float[3];
        c.eval(ctx, out);
        assertEquals(2f, out[2], 0f);
        assertEquals(0.5f, compile("speed", 0.5).channel(ctx, 0), 0f);
    }

    @Test
    public void rangesRollPerParticleKeyedByModuleAndName() {
        CgVfxFormInput r = compile("speed", map("range", Arrays.asList(2, 4)));
        assertEquals(CgFormClass.ROW, r.formClass());
        float u = CgVfxEmitterInstance.rand(7 ^ (MODULE + "/speed").hashCode(), 3, 0);
        assertEquals(2f + 2f * u, r.channel(ctx, 0), 1e-6f);
        Set<Float> seen = new HashSet<>();
        for (int p = 0; p < 20; p++) {
            float v = r.channel(ctx.particle(p), 0);
            assertTrue(v >= 2f && v <= 4f);
            seen.add(v);
        }
        assertTrue(seen.size() > 10);
        CgVfxFormInput locked = compile("axis", map("range", Arrays.asList(Arrays.asList(0, 0, 0), Arrays.asList(1, 1, 1)),
                "per_channel", true, "locked", "yz"));
        assertEquals(locked.channel(ctx, 1), locked.channel(ctx, 2), 0f);
        assertNotEquals(locked.channel(ctx, 0), locked.channel(ctx, 1), 0f);
        assertEquals(CgFormClass.INSTANCE_LANE, compile("per_loop", map("range", Arrays.asList(0, 1))).formClass());
    }

    @Test
    public void picksTakeOneEnd() {
        CgVfxFormInput p = compile("speed", map("pick", Arrays.asList(-1, 1)));
        Set<Float> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) seen.add(p.channel(ctx.particle(i), 0));
        assertEquals(new HashSet<>(Arrays.asList(-1f, 1f)), seen);
    }

    @Test
    public void curvesSampleTheirBlockAsTheCurveEvaluates() {
        Map<String, Object> keys = map("keys", Arrays.asList(Arrays.asList(0, 0), Arrays.asList(0.5, 1), Arrays.asList(1, 0.25)));
        CgVfxFormInput c = compile("speed", map("curve", keys, "by", "age"));
        assertEquals(CgFormClass.CURVE_BLOCK, c.formClass());
        CgRichCurve direct = CgRichCurve.decode(keys);
        for (float a = 0f; a <= 1f; a += 0.05f) assertEquals("age " + a, direct.eval(a), c.channel(ctx.age(a), 0), 2e-3f);
        CgVfxFormInput per = compile("axis", map("curves", Arrays.asList(keys, map("keys", Arrays.asList(Arrays.asList(0, 3))), keys),
                "by", "particles.age"));
        assertEquals(3f, per.channel(ctx.particleAge(0.3f), 1), 1e-6f);
    }

    @Test
    public void arraysIndexByTheirLookup() {
        CgVfxFormInput random = compile("speed", map("array", Arrays.asList(1, 2, 3), "by", "random"));
        Set<Float> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) seen.add(random.channel(ctx.particle(i), 0));
        assertEquals(new HashSet<>(Arrays.asList(1f, 2f, 3f)), seen);
        CgVfxFormInput byAge = compile("speed", map("array", Arrays.asList(0, 10), "by", "age"));
        assertEquals(2.5f, byAge.channel(ctx.age(0.25f), 0), 1e-6f);
        CgVfxFormInput wrapped = compile("speed", map("array", Arrays.asList(5, 6, 7), "by", "id", "address", "wrap"));
        assertEquals(6f, wrapped.channel(ctx.particle(4), 0), 0f);
    }

    @Test
    public void bindingsScaleTheirRemappedParameter() {
        CgVfxFormInput b = compile("speed", map("bind", "user.radius", "scale", 2,
                "remap", map("in", Arrays.asList(0, 20), "out", Arrays.asList(0, 1)), "default", 5));
        assertEquals(CgFormClass.INSTANCE_LANE, b.formClass());
        assertEquals(0.5f, b.channel(ctx, 0), 1e-6f);                         // unbound: its default, remapped
        assertEquals(1f, b.channel(ctx.parameter("user.radius", 10f), 0), 1e-6f);
        assertEquals(2f, b.channel(ctx.parameter("user.radius", 99f), 0), 1e-6f);   // clamped to in
        CgVfxFormInput direct = compile("speed", map("bind", "user.radius", "mode", "direct", "scale", 0.5));
        assertEquals(49.5f, direct.channel(ctx, 0), 1e-6f);
        assertEquals(CgFormClass.ATTRIBUTE, compile("speed", map("bind", "particles.age")).formClass());
    }

    @Test
    public void functionsExpandInline() {
        CgVfxFormInput add = compile("speed", map("fn", "crystalgraphics:add", "inputs", map("a", 1, "b", map("range", Arrays.asList(2, 3)))));
        float v = add.channel(ctx, 0);
        assertTrue(v >= 3f && v <= 4f);
        assertEquals(CgFormClass.ROW, add.formClass());
        CgVfxFormInput rr = compile("speed", map("fn", "crystalgraphics:random_range", "inputs", map("min", 1,
                "max", map("curve", map("keys", Arrays.asList(Arrays.asList(0, 2), Arrays.asList(1, 2)))))));
        assertEquals(CgFormClass.CURVE_BLOCK, rr.formClass());
        float r = rr.channel(ctx.age(0.5f), 0);
        assertTrue(r >= 1f && r <= 2f);
    }

    @Test
    public void dataIsOutsideTheWordsAndNarrowedFormsAreRefused() {
        assertEquals(CgFormClass.WORLD, compile("speed", map("texture", "test:noise")).formClass());
        try {
            compile("count", map("range", Arrays.asList(1, 2)));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("count") && e.getMessage().contains("range"));
        }
    }

    @Test
    public void theShapeKeyTakesFormClasses() {
        List<CgVfxFormInput> constant = List.of(compile("speed", 1));
        List<CgVfxFormInput> curve = List.of(compile("speed", map("curve", map("keys", Arrays.asList(Arrays.asList(0, 1))))));
        assertEquals("drift(speed:ROW)", CgVfxFormCompiler.shapeKey("drift", constant));
        assertNotEquals(CgVfxFormCompiler.shapeKey("drift", constant), CgVfxFormCompiler.shapeKey("drift", curve));
        assertEquals(CgVfxFormCompiler.shapeKey("drift", constant), CgVfxFormCompiler.shapeKey("drift", List.of(compile("speed", 9))));
    }
}
