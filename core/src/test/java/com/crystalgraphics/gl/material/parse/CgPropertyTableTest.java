package com.crystalgraphics.gl.material.parse;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.gl.material.CgMaterialProperties;
import com.crystalgraphics.gl.material.CgMaterialProperty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A property array of 64 vec4s, a table: laid out a vec4 an element in the material block, and its accessor reads each
 * element's own field. The accessor's GLSL is the same text on every tier, so its reading is checked here on the CPU.
 */
public class CgPropertyTableTest {

    private static final String SOURCE = String.join(System.lineSeparator(),
            "#type spatial",
            "Properties {",
            "    _Ramp (\"Ramp\", vec4[64]) = (0, 0, 0, 1)",
            "    _After (\"After\", float) = 1.0",
            "}",
            "Pass {",
            "    void vertex(out v2f o) { }",
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = _Ramp(40) * _After; }",
            "}");

    private static CgMaterialProperty ramp() {
        List<CgMaterialProperty> props = CgShaderParser.parse(SOURCE, "test:table").properties();
        CgMaterialProperty p = props.get(0);
        assertEquals("_Ramp", p.getName());
        assertEquals(64, p.getCount());
        return p;
    }

    @Test
    public void aTableIsLaidOutAVec4AnElement() {
        List<CgMaterialProperty> props = CgShaderParser.parse(SOURCE, "test:table").properties();
        CgBufferFormat block = new CgMaterialProperties(props).buildUboFormat();
        CgMaterialProperty p = props.get(0);
        int base = block.getField(p.blockName(0)).getByteOffset();
        for (int i = 0; i < 64; i++) assertEquals(base + 16 * i, block.getField(p.blockName(i)).getByteOffset());
        assertEquals(base + 16 * 64, block.getField("_After").getByteOffset());
    }

    @Test
    public void everyElementReadsItsOwnField() {
        CgMaterialProperty p = ramp();
        StringBuilder sb = new StringBuilder();
        CgMaterialShaderCompiler.appendTableRead(sb, p, 64);
        String glsl = sb.toString();
        for (int k = 0; k < 64; k++) assertEquals("element " + k, p.blockName(k), read(glsl, k));
    }

    @Test
    public void aShortArrayKeepsOneLocal() {
        StringBuilder sb = new StringBuilder();
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Bands", "Bands", "color", "(1, 1, 1, 1)", 3);
        CgMaterialShaderCompiler.appendTableRead(sb, p, 3);
        assertTrue(sb.toString(), !sb.toString().contains("if (") && read(sb.toString(), 2).equals(p.blockName(2)));
    }

    private static final Pattern CHUNK = Pattern.compile(
            "(?:if \\(k < (\\d+)\\) )?\\{?\\s*vec4 a\\[\\d+\\] = vec4\\[\\d+\\]\\(([^)]*)\\);\\s*return a\\[k - (\\d+)\\];");

    /** Runs the emitted read for {@code k}: the first chunk whose guard holds, as the GPU would. */
    private static String read(String glsl, int k) {
        Matcher m = CHUNK.matcher(glsl);
        while (m.find()) {
            if (m.group(1) != null && k >= Integer.parseInt(m.group(1))) continue;
            String[] fields = m.group(2).split(",\\s*");
            return fields[k - Integer.parseInt(m.group(3))];
        }
        throw new AssertionError("no chunk reads " + k + " in " + glsl);
    }
}
