package com.crystalgraphics.vfx.particle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.layout.CgLayout;
import com.crystalgraphics.object.layout.CgLayoutValues;
import com.crystalgraphics.object.layout.CgParam;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;

/**
 * Each built-in module's words, laid out from its declaration, equal what its hand-written {@code writeParams} wrote
 * before O6 deleted it: {@link #GOLDEN}, captured from {@code writeParams} for {@link #examples()}, word for word.
 */
public class CgVfxModuleWordsTest {

    /** {@code writeParams}' words for each of {@link #examples()}, in order. */
    private static final float[][] GOLDEN = {
            {0.0f, -9.8f, 0.0f, 0.0f},   // gravity
            {0.0f, 1.5f, 0.0f, 0.0f},   // gravity
            {4.0f, 1.0f, 0.0f, 0.0f},   // drag
            {0.25f, 1.0f, 1.0f, 0.06f},   // drag
            {1.0f, 0.0f, 0.0f, 0.0f},   // wind
            {8.0f, 0.12f, 0.6f, 0.0f},   // turbulence
            {16.0f, 0.9f, 0.0f, 0.0f},   // buoyancy
            {3.0f, 2.0f, 6.0f, 1.5f},   // updraft
            {0.3f, 0.5f, 0.6f, 1.0f},   // ground
            {0.0f, 0.02f, 0.0f, 0.3f},   // ground
            {2.0f, 0.3f, 2.0f, 2.5f},   // current
            {0.8f, 0.0f, 0.0f, 0.0f},   // spin
            {6.0f, 6.0f, 6.0f, 0.0f, 30.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f},   // attract
            {3.0f, 3.0f, 3.0f, 1.0f, 12.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f},   // attract
            {4.0f, 4.0f, 4.0f, 0.0f, 10.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f},   // attract
            {0.0f, 1.0f, 0.0f, 0.6f},   // orbit
            {0.0f, 0.70710677f, 0.70710677f, 3.0f, -1.0f, 0.0f, 0.0f, 0.0f},   // vortex
            {1.6f, 3.0f, 1.5f, 0.8f, 0.5f, 24.0f, 0.0f, 0.0f},   // flock
            {2.0f, 1.0f, 1.0f, 1.0f, 0.3f, 7.0f, 0.0f, 0.0f},   // flock
            {2.5f, 0.0f, 0.0f, 0.0f},   // flow
            {0.2f, 3.0f, 9.0f, 0.0f},   // gas_feed
            {1.0f, 0.5f, 0.0f, 2.0f},   // grid_splat
            {2.0f, 1.5f, 0.4f, 3.0f, 0.9f, 0.0f, 0.0f, 0.0f},   // grid_follow
            {1.0f, -2.0f, 0.0f, 0.0f},   // grid_gradient
            {1.0f, -2.0f, 3.0f, 0.0f},   // force
            {2.0f, 3.0f, 4.0f, 7.0f, 5.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},   // vector_field, tiling on every axis
            {2.0f, 0.0f, 0.0f, 0.0f},   // damping
            {4.0f, 0.0f, 0.0f, 0.0f},   // limit_speed
            {2.0f, 1.0f, 3.0f, 2.0f, 0.1f, 2.0f, 0.0f, 0.0f},   // conform
            {0.37139067f, 0.9284767f, 0.0f, 2.0f, 0.3f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f},   // collide
            {2.0f, 2.0f, 2.0f, 0.0f, 0.5f, 0.1f, 0.2f, 1.0f, 0.5f, 1.0f, 0.0f, 0.0f},   // collide
            {1.0f, 2.0f, 3.0f, 1.0f, 0.1f, 0.2f, 0.3f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f},   // collide
            {0.35f, 0.4f, 0.4f, 0.0f},   // collide_world
            {0.0f, 0.0f, 0.0f, 1.0f},   // collide_world
            {0.2f, 0.3f, 0.4f, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f},   // collide_depth
            {0.2f, 0.3f, 0.4f, 1.0f, 1.2f, 0.0f, 0.0f, 0.0f},   // collide_entities
            {0.3f, 0.3f, 0.3f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f},   // kill
            {4.0f, 1.0f, 4.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f},   // kill
            {0.0f, 1.0f, 0.0f, 2.0f, 1.0f, 0.0f, 0.0f, 0.0f},   // kill
    };

    /** Example configurations: every kind at least once, every shape on the shape kinds. */
    static List<CgVfxModule> examples() {
        CgVfxField field = CgVfxField.of(2, 2, 2, true, (x, y, z, out) -> out[0] = x + y * 0.5f + z);
        return new ArrayList<>(Arrays.asList(
                new CgVfxModule.Gravity(9.8f), new CgVfxModule.Gravity(-1.5f),
                new CgVfxModule.Drag(4f, 0f), new CgVfxModule.Drag(0.25f, 0.06f),
                new CgVfxModule.Wind(1f),
                new CgVfxModule.Turbulence(8f, 0.12f, 0.6f),
                new CgVfxModule.Buoyancy(16f, 0.9f),
                new CgVfxModule.Updraft(3f, 2f, 6f, 1.5f),
                new CgVfxModule.Ground(0.3f, 0.5f, 0.6f), new CgVfxModule.Ground(0f, 0.02f, 0f, 0.3f),
                new CgVfxModule.Current(2f, 0.3f, 2f, 2.5f),
                new CgVfxModule.Spin(0.8f),
                new CgVfxModule.Attract(CgSphere.of(6), 30f).at(0f, 1.5f, 0f),
                new CgVfxModule.Attract(CgBox.of(3, 3, 3), 12f).attenuation(0f),
                new CgVfxModule.Attract(CgSphere.of(4), 10f).directed(1f, 0f, 1f, 0f),
                new CgVfxModule.Orbit(0.6f, 0f, 1f, 0f, 0f, 1.5f, 0f),
                new CgVfxModule.Vortex(0f, 1f, 1f, 3f, -1f, 0f, 0f, 0f),
                new CgVfxModule.Flock(1.6f, 0.5f, 3f, 1.5f, 0.8f), new CgVfxModule.Flock(2f, 0.3f, 1f, 1f, 1f, 7),
                new CgVfxModule.Flow(2.5f),
                new CgVfxModule.Feed(0.2f, 3f, 9f),
                new CgVfxModule.Splat(1f, 0.5f, 0f, 2f),
                new CgVfxModule.Follow(2, 1.5f, 0.4f, 3f, 0.9f),
                new CgVfxModule.Gradient(1, -2f),
                new CgVfxModule.Force(1f, -2f, 3f),
                new CgVfxModule.VectorField(field, CgBox.of(2, 3, 4), 5f, 0.5f).at(1f, 0f, 0f),
                new CgVfxModule.Damping(2f),
                new CgVfxModule.LimitSpeed(4f),
                new CgVfxModule.Conform(CgSphere.of(2), 1f, 3f, 2f),
                new CgVfxModule.Collide(CgPlane.of(0.4, 1, 0, 5), 0.3f, 0f, 0f),
                new CgVfxModule.Collide(CgSphere.of(2), 0.5f, 0.1f, 0.2f).container().killing().radius(0.5f),
                new CgVfxModule.Collide(CgBox.of(1, 2, 3), 0.1f, 0.2f, 0.3f).at(1f, 2f, 3f),
                new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f), new CgVfxModule.CollideWorld(0f, 0f, 0f, true),
                new CgVfxModule.CollideDepth(0.2f, 0.3f, 0.4f, 0.5f, false),
                new CgVfxModule.CollideEntities(0.2f, 0.3f, 0.4f, 1.2f, true),
                new CgVfxModule.Kill(CgSphere.of(0.3), true).at(0f, 1.5f, 0f),
                new CgVfxModule.Kill(CgBox.of(4, 1, 4), false),
                new CgVfxModule.Kill(CgPlane.of(0, 1, 0), true)));
    }

    @Test
    public void declaredWordsEqualTheHandWrittenOnes() {
        List<CgVfxModule> examples = examples();
        assertEquals(GOLDEN.length, examples.size());
        Set<String> seen = new HashSet<>();
        for (int e = 0; e < GOLDEN.length; e++) {
            CgVfxModule m = examples.get(e);
            CgVfxModuleTypes.Kind<CgVfxModule> kind = CgVfxModuleTypes.of(m);
            assertEquals(m.gpuKind(), kind.id());
            int[] expected = new int[GOLDEN[e].length];
            for (int w = 0; w < expected.length; w++) expected[w] = Float.floatToRawIntBits(GOLDEN[e][w]);
            assertArrayEquals(m.toString(), expected, CgVfxModuleTypes.words(m));
            seen.add(kind.id());
        }
        for (CgVfxModuleTypes.Kind<?> k : CgVfxModuleTypes.all()) {
            if (k.type().getEnclosingClass() == CgVfxModule.class) assertTrue(k.id() + " has no example", seen.contains(k.id()));
        }
    }

    /** A kind of a mod's own: one scalar, then a vec3 on a new vec4. */
    private record Swirl(float strength, float x, float y, float z) implements CgVfxGpuModule {
        static {
            CgVfxModuleTypes.declare("test_words_swirl", Swirl.class,
                    t -> t.property("strength", CgValueType.FLOAT).property("axis", CgValueType.VEC3),
                    (m, in) -> in.f("strength", m.strength()).v3("axis", m.x(), m.y(), m.z()));
        }

        public String gpuKind() {
            return "test_words_swirl";
        }

        public boolean afterSolve() {
            return false;
        }
    }

    /** Another class under the same id. */
    private record Imposter() implements CgVfxGpuModule {
        public String gpuKind() {
            return "test_words_swirl";
        }

        public boolean afterSolve() {
            return false;
        }
    }

    @Test
    public void aModsKindIsLaidOutFromItsDeclarationAndBindsByName() {
        Swirl s = new Swirl(2f, 0f, 1f, 0f);
        assertArrayEquals(new int[] {Float.floatToRawIntBits(2f), 0, 0, 0, 0, Float.floatToRawIntBits(1f), 0, 0},
                CgVfxModuleTypes.words(s));
        assertEquals(2, CgVfxModuleTypes.vectors(s));
        assertEquals(5, CgVfxModuleTypes.of(s).words().lane("axis", 1));
        try {
            CgVfxModuleTypes.of(s).compile(CgVfxModuleTypes.inputs(s));
            fail("a kind declared for code reads no inputs");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("test_words_swirl"));
        }
        try {
            CgVfxModuleTypes.declare("test_words_swirl", Imposter.class, t -> { }, (m, in) -> { });
            fail("an id is one class's");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Swirl"));
        }
        try {
            CgVfxModuleTypes.words(new Imposter());
            fail("an undeclared kind");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("declare"));
        }
    }

    @Test
    public void compiledModulesWriteTheSameWords() {
        for (CgVfxModule m : examples()) {
            CgVfxModuleTypes.Kind<CgVfxModule> kind = CgVfxModuleTypes.of(m);
            Map<String, Object> inputs = kind.inputs(m);
            CgVfxModule back = kind.compile(inputs);
            // a record that normalises an axis may move it by an ulp when it normalises it again
            float[] a = floats(kind, inputs), b = floats(kind, kind.inputs(back));
            assertArrayEquals(m.toString(), a, b, 1e-6f);
        }
    }

    private static float[] floats(CgVfxModuleTypes.Kind<?> kind, Map<String, Object> inputs) {
        CgLayoutValues v = kind.values(inputs);
        float[] out = new float[kind.layout().floats() + kind.layout().ints()];
        for (int i = 0; i < kind.layout().floats(); i++) out[i] = v.floatAt(i);
        for (int i = 0; i < kind.layout().ints(); i++) out[kind.layout().floats() + i] = v.intAt(i);
        return out;
    }

    @Test
    public void paramsBindBySlotAndFailNamingBoth() {
        CgType t = CgTypes.create().declare("test:ring", CgTypeKind.STRUCT)
                .property("radius", CgValueType.FLOAT).defaultValue(2.0)
                .property("axis", CgValueType.VEC3)
                .property("count", CgValueType.INT).defaultValue(3)
                .register();
        CgLayout layout = CgLayout.of(t);
        assertEquals(4, layout.floats());
        assertEquals(1, layout.ints());
        CgLayout.Slot radius = layout.bind(CgParam.ofFloat("radius"));
        CgLayoutValues values = layout.values();
        assertEquals(2f, values.getFloat(radius), 0f);
        assertEquals(3, values.getInt(layout.bind(CgParam.ofInt("count"))));
        assertEquals(1, layout.bind(CgParam.ofVec3("axis")).offset());
        try {
            layout.bind(CgParam.ofFloat("axis"));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("axis") && e.getMessage().contains("float"));
        }
        try {
            layout.bind(CgParam.ofFloat("speed"));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("test:ring") && e.getMessage().contains("speed"));
        }
    }
}
