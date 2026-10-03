package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A material's {@code Buffers { }}: what it parses to, what it refuses, and the GLSL each buffer path reads it with. */
public class CgMaterialBuffersTest {

    private static final String PASS = "Pass {\n"
            + "    Tags { \"LightMode\" = \"Forward\" }\n"
            + "    struct v2f { vec4 color; };\n"
            + "    void vertex(out v2f o) {\n"
            + "        Spark s = SPARKS(CG_DRAW_INSTANCE);\n"
            + "        gl_Position = CG_MATRIX_MVP * vec4(s.positionLife.xyz, 1.0);\n"
            + "        o.color = vec4(HEAT(gl_InstanceID));\n"
            + "    }\n"
            + "    void fragment(in v2f i, out vec4 fragColor) { fragColor = i.color; }\n"
            + "}\n";

    private static String shader(String materialScope) {
        return "#type none\n" + materialScope + PASS;
    }

    private static final String SPARKS = shader(
            "struct Unused { vec4 a; };\n"
            + "struct Spark { vec4 positionLife; vec4 velocitySeed; };\n"
            + "Buffers {\n"
            + "    SPARKS (\"Sparks\", Spark, readonly)   // records a kernel wrote\n"
            + "    HEAT   (\"Heat\",   float, readonly)\n"
            + "}\n");

    @After
    public void clearCapabilities() throws Exception {
        Field cache = CgCapabilities.class.getDeclaredField("cachedCaps");
        cache.setAccessible(true);
        cache.set(null, null);
    }

    @Test
    public void parsesBuffersAndTheStructsTheyName() {
        CgParsedShader parsed = CgShaderParser.parse(SPARKS, "test");
        assertEquals(2, parsed.buffers().size());
        CgBufferDecl sparks = parsed.buffer("SPARKS");
        assertEquals("Spark", sparks.element());
        assertEquals(32, sparks.stride());
        assertEquals(1, parsed.buffer("HEAT").index());
        assertTrue(parsed.bufferStructs().contains("struct Spark { vec4 positionLife; vec4 velocitySeed; };"));
        assertFalse("a struct no buffer names stays out", parsed.bufferStructs().contains("Unused"));
        assertNull(parsed.buffer("NONE"));
    }

    @Test
    public void refusesWhatEveryTierCannotRead() {
        refuses("struct S { vec4 a; };\nBuffers { B (\"B\", S, readwrite) }\n", "only reads");
        refuses("Buffers { B (\"B\", vec3, readonly) }\n", "every tier holds");
        refuses("struct S { vec4 a; float b; };\nBuffers { B (\"B\", S, readonly) }\n", "every tier holds");
        refuses("Buffers { B (\"B\", S, readonly) }\nstruct S { vec4 a; };\n", "declared before");
        refuses("Buffers { a (\"B\", float, readonly) }\n", "cannot name");
        refuses("Buffers {\n A (\"A\", float, readonly)\n B (\"B\", float, readonly)\n C (\"C\", float, readonly)\n"
                + " D (\"D\", float, readonly)\n E (\"E\", float, readonly)\n}\n", "at most 4");
    }

    @Test
    public void storagePathDeclaresABlockInBothStages() throws Exception {
        path(CgCapabilities.ShaderBufferPath.SSBO_GL43);
        CgMaterialShaderCompiler.CompiledSource cs = compile(SPARKS);
        for (String stage : new String[]{cs.vertexSource(), cs.fragmentSource()}) {
            assertTrue(stage.contains("layout(std430) readonly buffer CgMaterialBuffer_SPARKS { Spark _cg_SPARKS[]; };"));
            assertTrue(stage.contains("Spark SPARKS(uint i)"));
            assertTrue("the struct before the block",
                    stage.indexOf("struct Spark") < stage.indexOf("CgMaterialBuffer_SPARKS"));
        }
    }

    @Test
    public void texturePathReadsTexels() throws Exception {
        path(CgCapabilities.ShaderBufferPath.TBO);
        CgMaterialShaderCompiler.CompiledSource cs = compile(SPARKS);
        for (String stage : new String[]{cs.vertexSource(), cs.fragmentSource()}) {
            assertTrue(stage.contains("uniform usamplerBuffer _cg_tbo_SPARKS;"));
            assertTrue("a field a texel", stage.contains("v.velocitySeed = uintBitsToFloat(texelFetch(_cg_tbo_SPARKS, t + 1));"));
            assertTrue(stage.contains("return uintBitsToFloat(texelFetch(_cg_tbo_HEAT, t).r);"));
            assertFalse(stage.contains("CgMaterialBuffer_"));
        }
    }

    @Test
    public void aVertexPlacedFromABufferKeepsItsBodyInTheDepthPass() throws Exception {
        path(CgCapabilities.ShaderBufferPath.TBO);
        CgParsedShader parsed = CgShaderParser.parse(SPARKS, "test");
        CgMaterialShaderCompiler.CompiledSource depth = CgMaterialShaderCompiler.compileDepthAutoGen(parsed,
                parsed.passes().get(0), Collections.emptyList(), null, CgMaterialShaderCompiler.CompileConfig.DEFAULT);
        assertTrue(depth.vertexSource().contains("SPARKS(CG_DRAW_INSTANCE)"));
    }

    private static CgMaterialShaderCompiler.CompiledSource compile(String source) {
        return CgMaterialShaderCompiler.compile(CgShaderParser.parse(source, "test"), Collections.emptyList());
    }

    private static void refuses(String materialScope, String says) {
        try {
            CgShaderParser.parse(shader(materialScope), "test");
            fail("parsed: " + materialScope);
        } catch (CgShaderParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(says));
        }
    }

    private static void path(CgCapabilities.ShaderBufferPath path) throws Exception {
        Constructor<CgCapabilities> ctor = CgCapabilities.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CgCapabilities stub = ctor.newInstance();
        Field pathField = CgCapabilities.class.getDeclaredField("shaderBufferPath");
        pathField.setAccessible(true);
        pathField.set(stub, path);
        Field cache = CgCapabilities.class.getDeclaredField("cachedCaps");
        cache.setAccessible(true);
        cache.set(null, stub);
    }
}
