package com.crystalgraphics.shadergraph;

import com.crystalgraphics.gl.material.parse.CgShaderParser;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 6.3.14 — <b>every property type a graph can expose must produce a shader that parses.</b>
 *
 * <h3>The landmine this exists for</h3>
 * <p>{@code CgPropertiesParser} hard-bans {@code vec3} as a material property type — STD140 pads it to
 * 16 bytes while the GLSL compiler places the next field 12 bytes later — and
 * {@link CgShaderType#propertyTypeName()} returned {@code "vec3"} for {@link CgShaderType#VEC3}. So
 * exposing a Vector 3 would have emitted a {@code .shader} that <b>fails to parse</b>, for one of the
 * three most common property types there is.</p>
 *
 * <p>Nothing hit it because no code path could reach {@code CgMasterNode.property()} yet. The property
 * feature is that path, which is why this test is written against the <b>real parser</b> rather than
 * against the emitted string: only the parser knows what it will refuse.</p>
 */
public class CgPropertyDeclarationTest {

    /** A minimal graph that compiles, so the assertions are about the Properties block and nothing else. */
    private static String emitWith(CgMasterNode master) {
        CgShaderGraph graph = new CgShaderGraph();
        graph.add(new CgShaderGraph.Instance("m", master, java.util.Map.of(), java.util.Map.of()));
        return CgShaderEmitter.emit(graph.output("m"), master).source();
    }

    private static void assertParses(String source) {
        // Parsing is the assertion. A generated file that merely LOOKS right is exactly what shipped
        // before, and the parser is the only thing that knows what it refuses.
        CgShaderParser.parse(source, "test:properties");
    }

    // ── The landmine ────────────────────────────────────────────────────────

    /** <b>A Vector 3 property is declared as vec4</b>, because vec3 is not a legal property type. */
    @Test
    public void aVec3PropertyIsDeclaredAsVec4() {
        assertEquals("vec4", CgShaderType.VEC3.propertyDeclarationType());
        assertEquals("and is read back narrowed", ".xyz", CgShaderType.VEC3.propertyAccessSuffix());

        String source = emitWith(new CgMasterNode()
                .property("_Dir", CgShaderType.VEC3, "(0,1,0,0)"));
        assertTrue(source, source.contains("_Dir (\"_Dir\", vec4) = (0,1,0,0)"));
        assertFalse("the banned token must not reach the file", source.contains("vec3)"));
        assertParses(source);
    }

    /**
     * <b>The constraint the workaround exists for, asserted directly.</b>
     *
     * <p>Without this, the test above only proves that {@code vec4} is written — not that {@code vec3}
     * would have been refused, which is the entire reason it is. It also marks the exit: if the parser
     * ever gains a legal {@code vec3} property, this fails, and that is the signal to delete
     * {@link CgShaderType#propertyDeclarationType()}'s special case rather than to leave a workaround
     * nobody can date.</p>
     */
    @Test
    public void theParserGenuinelyRefusesAVec3Property() {
        String handWritten = emitWith(new CgMasterNode())
                .replace("Queue = ", "Properties {\n    _Dir (\"_Dir\", vec3) = (0,1,0)\n}\n\nQueue = ");
        try {
            CgShaderParser.parse(handWritten, "test:vec3");
            fail("vec3 is banned as a property type — if it no longer is, the vec4 workaround in "
                    + "CgShaderType.propertyDeclarationType can go");
        } catch (RuntimeException expected) {
            assertTrue("and it must say why: " + expected.getMessage(),
                    expected.getMessage().contains("vec3"));
        }
    }

    /** Everything else declares as itself, and only VEC3 narrows on read. */
    @Test
    public void everyOtherTypeDeclaresAsItself() {
        for (CgShaderType type : CgShaderType.values()) {
            String name = type.propertyTypeName();
            if (name == null || type == CgShaderType.VEC3) continue;
            assertEquals(type + " must declare as itself", name, type.propertyDeclarationType());
            assertEquals(type + " must not need a suffix", "", type.propertyAccessSuffix());
        }
    }

    /** A matrix still has no property type — the plan's OUT column, stated as a test. */
    @Test
    public void matricesAreStillNotProperties() {
        for (CgShaderType type : new CgShaderType[]{CgShaderType.MAT2, CgShaderType.MAT3,
                CgShaderType.MAT4}) {
            assertNull(type + " has no property type", type.propertyTypeName());
            assertNull(type + " therefore has nothing to declare", type.propertyDeclarationType());
        }
    }

    // ── Vector adaptation ───────────────────────────────────────────────────

    /**
     * <b>Any numeric vector feeds any other, and the compiler writes the adaptation.</b>
     *
     * <p>Unity connects all of these and adapts; refusing does not teach a rule, it reads as the editor
     * being broken. A crash found the gap: a Vector 2 reaching a dynamic port widened it, and the
     * already-drawn edge downstream became vec2 -> vec3 and was refused mid-recompile.</p>
     *
     * <p>Padding is ZERO, and it is a real choice. One is defensible for a colour's alpha and wrong for
     * a direction, a UV or an offset; zero is the additive and positional identity, so a padded channel
     * contributes nothing wherever it lands. The type cannot tell the difference -- vec4 does not know it
     * is a colour -- so the rule is the one that is inert more often.</p>
     */
    @Test
    public void vectorsAdaptInBothDirections() {
        assertEquals("a scalar splats", "vec3(v)", CgShaderType.FLOAT.promote("v", CgShaderType.VEC3));
        assertEquals("wider truncates", "v.xy", CgShaderType.VEC4.promote("v", CgShaderType.VEC2));
        assertEquals("narrower pads with zero",
                "vec3(v, 0.0)", CgShaderType.VEC2.promote("v", CgShaderType.VEC3));
        assertEquals("and pads as many channels as it is short",
                "vec4(v, 0.0, 0.0)", CgShaderType.VEC2.promote("v", CgShaderType.VEC4));
        assertEquals("an identical type is left alone",
                "v", CgShaderType.VEC3.promote("v", CgShaderType.VEC3));

        for (CgShaderType from : new CgShaderType[]{CgShaderType.FLOAT, CgShaderType.VEC2,
                CgShaderType.VEC3, CgShaderType.VEC4}) {
            for (CgShaderType to : new CgShaderType[]{CgShaderType.FLOAT, CgShaderType.VEC2,
                    CgShaderType.VEC3, CgShaderType.VEC4}) {
                assertTrue(from + " must feed " + to, from.canFeed(to));
            }
        }
    }

    /** A sampler and a matrix are still not vectors, and neither converts into one. */
    @Test
    public void nonVectorsStillRefuse() {
        assertFalse(CgShaderType.SAMPLER2D.canFeed(CgShaderType.VEC4));
        assertFalse(CgShaderType.VEC4.canFeed(CgShaderType.SAMPLER2D));
        assertFalse(CgShaderType.MAT4.canFeed(CgShaderType.VEC4));
    }

    // ── Every shipped type, through the real parser ─────────────────────────

    /**
     * <b>The whole in-scope type set, in one shader, parsed.</b>
     *
     * <p>One shader rather than one per type on purpose: a {@code Properties} block is parsed as a
     * block, so a type that is individually fine but breaks its neighbour's alignment only shows up
     * when they are together.</p>
     */
    @Test
    public void everyInScopeTypeParses() {
        CgMasterNode master = new CgMasterNode()
                .property("_Amount", CgShaderType.FLOAT, "1.0")
                .property("_Count", CgShaderType.INT, "3")
                .property("_Toggle", CgShaderType.BOOL, "true")
                .property("_Uv", CgShaderType.VEC2, "(0,0)")
                .property("_Dir", CgShaderType.VEC3, "(0,1,0,0)")
                .property("_Tint", CgShaderType.VEC4, "(1,1,1,1)")
                .property("_Albedo", CgShaderType.SAMPLER2D, "\"white\"")
                .property("_Layers", CgShaderType.SAMPLER2D_ARRAY, "\"white\"")
                .property("_Volume", CgShaderType.SAMPLER3D, "\"white\"")
                .property("_Env", CgShaderType.SAMPLER_CUBE, "\"white\"");

        String source = emitWith(master);
        assertParses(source);
    }

    /** The spelling trap propertyTypeName already documents: GLSL says bool, a property says boolean. */
    @Test
    public void aBooleanPropertyIsSpelledBoolean() {
        String source = emitWith(new CgMasterNode().property("_On", CgShaderType.BOOL, "true"));
        assertTrue(source, source.contains("_On (\"_On\", boolean) = true"));
        assertParses(source);
    }

    /** A graph declaring nothing emits no Properties block at all. */
    @Test
    public void noPropertiesMeansNoBlock() {
        String source = emitWith(new CgMasterNode());
        assertFalse(source, source.contains("Properties {"));
        assertParses(source);
    }
}
