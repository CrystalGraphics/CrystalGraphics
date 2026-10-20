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
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

/** Each built-in module's words, laid out from its declaration, equal its hand-written {@code writeParams}. */
public class CgVfxModuleWordsTest {

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
    public void generatedWordsEqualWriteParams() {
        Set<String> seen = new HashSet<>();
        for (CgVfxModule m : examples()) {
            CgVfxModuleTypes.Kind<CgVfxModule> kind = CgVfxModuleTypes.of(m);
            assertEquals(m.gpuKind(), kind.id());
            assertEquals(m.gpuKind() + "'s vec4s", m.paramVectors(), kind.words().vectors());
            int[] expected = new int[m.paramVectors() * 4], actual = new int[expected.length];
            CgVfxWords out = CgVfxWords.into(expected, 0, m.paramVectors(), m.gpuKind());
            m.writeParams(out);
            out.finish("writeParams");
            out = CgVfxWords.into(actual, 0, m.paramVectors(), m.gpuKind());
            kind.words().write(kind.values(kind.inputs(m)), out);
            out.finish("the declaration");
            assertArrayEquals(m.toString(), expected, actual);
            seen.add(kind.id());
        }
        for (CgVfxModuleTypes.Kind<?> k : CgVfxModuleTypes.all()) assertTrue(k.id() + " has no example", seen.contains(k.id()));
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
