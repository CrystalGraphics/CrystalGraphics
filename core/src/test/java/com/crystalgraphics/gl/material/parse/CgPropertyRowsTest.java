package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperties;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** {@code #pragma cg_use rows} and array properties: what the parser adds and what the compiler emits for them. */
public class CgPropertyRowsTest {

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();

    private static final String ROWS = "#type spatial\n"
            + "#pragma cg_use rows\n"
            + "Properties {\n"
            + "    _Radius (\"Radius\", float) = 0.3\n"
            + "    _Core (\"Core\", color) = (1, 1, 1, 1)\n"
            + "    _Bands (\"Bands\", color[3]) = (1, 1, 1, 1), (0, 1, 1, 1), (0, 0.5, 0.9, 1)\n"
            + "    _Steps (\"Steps\", int) = 3\n"
            + "    _Mask (\"Mask\", sampler2D) = \"white\"\n"
            + "}\n"
            + "struct v2f { vec2 uv; };\n"
            + "Pass {\n"
            + "    void vertex(out v2f o) { o.uv = vec2(_Radius); }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = _Core * _Bands(1); }\n"
            + "}\n";

    @BeforeClass
    public static void injectTboCapabilities() throws Exception {
        Constructor<CgCapabilities> ctor = CgCapabilities.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CgCapabilities stub = ctor.newInstance();
        Field pathField = CgCapabilities.class.getDeclaredField("shaderBufferPath");
        pathField.setAccessible(true);
        pathField.set(stub, CgCapabilities.ShaderBufferPath.TBO);
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, stub);
    }

    @AfterClass
    public static void clearCapabilitiesCache() throws Exception {
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, null);
    }

    @Test
    public void everyNumberGetsAHiddenRowSlot() {
        CgParsedShader parsed = CgShaderParser.parse(ROWS, "test:rows.shader");
        CgMaterialProperties props = new CgMaterialProperties(parsed.properties());
        assertTrue(props.property("_Radius").isRows());
        assertTrue(props.property("_Bands").isRows());
        assertFalse("an int is never row-bound", props.property("_Steps").isRows());
        assertFalse(props.property("_Mask").isRows());
        CgMaterialProperty slot = props.property(CgMaterialProperty.rowSlotName("_Radius"));
        assertNotNull(slot);
        assertTrue(slot.isHidden());
        assertEquals("unbound until a binder says", -1, slot.getIntValue());
        assertNull(props.property(CgMaterialProperty.rowSlotName("_Steps")));
    }

    @Test
    public void anArrayParsesEachTuple() {
        CgMaterialProperty bands = new CgMaterialProperties(CgShaderParser.parse(ROWS, "test:rows.shader").properties())
                .property("_Bands");
        assertTrue(bands.isArray());
        assertEquals(3, bands.getCount());
        float[] v = bands.getFloatValue();
        assertEquals(12, v.length);
        assertEquals(0f, v[4], 0f);
        assertEquals(0.5f, v[9], 0f);
    }

    @Test
    public void theBlockHoldsRenamedFields() {
        CgParsedShader parsed = CgShaderParser.parse(ROWS, "test:rows.shader");
        CgBufferFormat block = new CgMaterialProperties(parsed.properties()).buildUboFormat();
        assertTrue(block.toString().contains("_cgm_Radius"));
        assertTrue(block.toString().contains("_cgm_Bands_2"));
        assertTrue(block.toString().contains("_cg_at_Radius"));
        assertTrue("an int keeps its name", block.toString().contains("_Steps"));
    }

    @Test
    public void eachNumberIsReadThroughItsAccessor() {
        CgParsedShader parsed = CgShaderParser.parse(ROWS, "test:rows.shader");
        CgMaterialShaderCompiler.CompiledSource source = CgMaterialShaderCompiler.compile(parsed, NO_BUFFERS);
        for (String stage : new String[] {source.vertexSource(), source.fragmentSource()}) {
            assertTrue(stage.contains("#include \"crystalgraphics:shaders/env/buffer/rows.glsl\""));
            assertTrue(stage.contains(
                    "#define _Radius (_cg_at_Radius < 0 ? _cgm_Radius : CG_ROW(CG_PROPERTY_ROW + _cg_at_Radius).x)"));
            assertTrue(stage.contains("#define _Core (_cg_at_Core < 0 ? _cgm_Core : CG_ROW(CG_PROPERTY_ROW + _cg_at_Core))"));
            assertTrue(stage.contains("#define _Bands(i) _cg_arr_Bands(i)"));
            assertTrue(stage.contains("if (_cg_at_Bands >= 0) return CG_ROW(CG_PROPERTY_ROW + _cg_at_Bands + k);"));
            assertFalse(stage.contains("#define _Steps"));
            // Before the user's code, which reads them.
            assertTrue(stage.indexOf("#define _Radius") < stage.indexOf("void vertex(out v2f o)") || !stage.contains("void vertex"));
        }
    }

    @Test
    public void anArrayWithoutRowsReadsTheMaterialAlone() {
        String plain = ROWS.replace("#pragma cg_use rows\n", "");
        CgParsedShader parsed = CgShaderParser.parse(plain, "test:plain.shader");
        String frag = CgMaterialShaderCompiler.compile(parsed, NO_BUFFERS).fragmentSource();
        assertTrue(frag.contains("#define _Bands(i) _cg_arr_Bands(i)"));
        assertFalse(frag.contains("CG_ROW"));
        assertFalse(frag.contains("#define _Radius"));
        assertNull(new CgMaterialProperties(parsed.properties()).property(CgMaterialProperty.rowSlotName("_Radius")));
    }

    @Test
    public void anArrayOfAnotherTypeIsRefused() {
        assertThrows(CgShaderParseException.class, () -> CgShaderParser.parse(
                ROWS.replace("color[3]", "float[3]"), "test:bad.shader"));
    }
}
