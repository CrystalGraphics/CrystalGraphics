package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.material.CgMaterialShader;
import com.crystalgraphics.material.CgMaterialShaderRegistry;
import com.crystalgraphics.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.material.parse.CgParsedPass;
import com.crystalgraphics.material.parse.CgParsedShader;
import com.crystalgraphics.material.parse.CgShaderParser;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.util.io.CgIO;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The material format (P2.5 F1, F2) against the old: each shape hand-converted, its parse meaning what its old form's
 * meant (passes, their render state, queue, lighting, keywords, engine buffers), and every pass and keyword variant of
 * the new form built by shaderc on the null device, as a Vulkan device builds it.
 */
public class MaterialFormatTest {

    /** Each shape: its new file and the old file it stands for, under {@code cgformat:shaders/}. */
    private static final String[][] PAIRS = {
            {"opaque", "opaque.old"}, {"masked", "masked.old"}, {"translucent", "translucent.old"},
            {"glow", "glow.old"}, {"haze", "haze.old"}, {"waves", "waves.old"}, {"sparks", "sparks.old"},
            {"keywords", "keywords.old"}, {"scene_in", "crystalgraphics:shaders/world_scene_in"}};

    private static ShadercGlslCompiler compiler, bare;

    @BeforeClass
    public static void install() {
        compiler = new ShadercGlslCompiler();
        bare = new ShadercGlslCompiler(null, false, false);
        new CgNullRHI(64, 64).install(new ExplicitBindingCheck(compiler, bare));
        CgCapabilities.clearCache();
        CgFallbackTextures.init();
    }

    @AfterClass
    public static void uninstall() {
        compiler.close();
        bare.close();
    }

    @Test
    public void eachShapeMeansWhatItsOldFormMeant() {
        for (String[] pair : PAIRS) {
            CgParsedShader now = parse(path(pair[0])), was = parse(path(pair[1]));
            String what = pair[0] + ": ";
            assertNotNull(what + "not in the format", now.domain());
            assertEquals(what + "vertex type", was.shaderType(), now.shaderType());
            assertEquals(what + "queue", was.renderQueue(), now.renderQueue());
            assertEquals(what + "render type", was.renderType(), now.renderType());
            assertEquals(what + "lit", was.lit(), now.lit());
            assertEquals(what + "surface lit", was.surfaceLit(), now.surfaceLit());
            assertEquals(what + "fogged", was.fogged(), now.fogged());
            assertEquals(what + "mediated", was.mediated(), now.mediated());
            assertEquals(what + "linear colour", was.linearColor(), now.linearColor());
            assertEquals(what + "linear light", was.linearLight(), now.linearLight());
            assertEquals(what + "depth mode", was.depthMode(), now.depthMode());
            assertEquals(what + "scene colour margin", was.sceneColorMargin(), now.sceneColorMargin(), 0f);
            assertEquals(what + "keywords", was.featureNames(), now.featureNames());
            assertEquals(what + "permutations", was.permutations().dimensions(), now.permutations().dimensions());
            assertEquals(what + "engine buffers", was.engineBuffers(), now.engineBuffers());
            assertEquals(what + "buffers", was.buffers().size(), now.buffers().size());
            assertEquals(what + "properties", names(was.properties()), names(now.properties()));
            assertEquals(what + "pass count", was.passes().size(), now.passes().size());
            for (int p = 0; p < was.passes().size(); p++) {
                CgParsedPass a = was.passes().get(p), b = now.passes().get(p);
                assertEquals(what + "pass " + p, a.lightMode(), b.lightMode());
                CgRenderState ra = a.renderState(), rb = b.renderState();
                assertEquals(what + a.lightMode() + " blend", slot(ra.getBlend(), CgBlendState.DISABLED), slot(rb.getBlend(), CgBlendState.DISABLED));
                assertEquals(what + a.lightMode() + " depth", slot(ra.getDepth(), CgDepthState.TEST_WRITE), slot(rb.getDepth(), CgDepthState.TEST_WRITE));
                assertEquals(what + a.lightMode() + " cull", slot(ra.getCull(), CgCullState.BACK), slot(rb.getCull(), CgCullState.BACK));
                assertEquals(what + a.lightMode() + " stencil", slot(ra.getStencil(), CgStencilState.DISABLED), slot(rb.getStencil(), CgStencilState.DISABLED));
                assertEquals(what + a.lightMode() + " writes nothing", ra.writesNothing(), rb.writesNothing());
                assertEquals(what + a.lightMode() + " v2f", CgShaderParser.parseV2fFields(a), CgShaderParser.parseV2fFields(b));
            }
            assertEquals(what + "glow merge", CgMaterialShaderCompiler.emissionMerge(was),
                    CgMaterialShaderCompiler.emissionMerge(now));
        }
    }

    @Test
    public void everyPassAndVariantBuildsOnTheDevice() {
        List<String> failures = new ArrayList<>();
        int programs = 0;
        for (String[] pair : PAIRS) {
            String path = path(pair[0]);
            CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreate(path);
            shader.recompile();   // another test may have parsed it, compiling nothing
            if (shader.hasCompileFailed()) {
                failures.add(shader.lastCompileError());
                continue;
            }
            CgParsedShader parsed = shader.getLastParsed();
            CgParsedPass forward = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            for (CgParsedPass pass : parsed.passes()) {
                for (Set<String> keywords : pass == forward ? subsets(parsed.featureNames()) : List.of(Set.<String>of())) {
                    programs++;
                    if (shader.getOrCompile(pass.name(), keywords) == null) {
                        failures.add(path + " pass " + pass.name() + " " + keywords + ": did not build (see log)");
                    }
                }
            }
            CgMaterial material = CgMaterial.load(path);
            if (parsed.renderQueue() < 3000 && !CgMaterialProxy.of(material).hasDepthPass()
                    && !shader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName())) {
                failures.add(path + ": the generated depth pass did not build (see log)");
            }
        }
        assertTrue(String.join(System.lineSeparator(), failures), failures.isEmpty());
        assertTrue("built " + programs, programs > PAIRS.length);
    }

    @Test
    public void worldPositionOffsetReachesTheDepthPass() {
        CgParsedShader waves = parse(path("waves"));
        CgParsedPass forward = waves.passes().get(0);
        assertFalse("the material's vertex() is no simple vertex", CgMaterialShaderCompiler.isSimpleVertex(forward));
    }

    @Test
    public void aHelperReachesOnlyTheStagesCallingIt() {
        CgParsedShader waves = parse(path("waves"));
        String decls = waves.passes().get(0).globalDecls();
        assertTrue(decls, decls.contains("#if !defined(CG_VERTEX_STAGE)\nfloat ripple("));
        assertFalse(decls, decls.contains("#if !defined(CG_VERTEX_STAGE)\nfloat wave("));
    }

    @Test
    public void marksAndTheGlobalDomainAreRead() {
        CgParsedShader keywords = parse(path("keywords"));
        for (CgMaterialProperty p : keywords.properties()) {
            if (p.getName().equals("_Tint")) assertTrue("hdr", p.isHdr());
            if (p.getName().equals("_Rough")) assertEquals("range", 1f, p.getRangeMax(), 0f);
        }
        assertEquals(CgMaterialDomain.GLOBAL, parse(path("scene_in")).domain());
    }

    @Test
    public void aOneOfHoldsOneValue() {
        CgMaterial box = CgMaterial.load(path("keywords")).keyword("SHAPE_BOX", true);
        assertTrue(box.keyword("MOSS", true).isKeywordEnabled("SHAPE_BOX"));
        try {
            box.keyword("SHAPE_CYLINDER", true);
            fail("two values of SHAPE");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("SHAPE_BOX"));
        }
    }

    private static CgParsedShader parse(String path) {
        String source = CgIO.loadSource(path);
        assertNotNull("no source at " + path, source);
        return CgShaderParser.parse(source, path);
    }

    private static String path(String name) {
        return (name.contains(":") ? name : "cgformat:shaders/" + name) + ".shader";
    }

    /** A render state's slot as drawn: one a pass leaves undeclared takes the default. */
    private static <T> T slot(T declared, T fallback) {
        return declared != null ? declared : fallback;
    }

    private static List<String> names(List<CgMaterialProperty> props) {
        List<String> out = new ArrayList<>();
        for (CgMaterialProperty p : props) out.add(p.getName() + ":" + p.getType());
        return out;
    }

    private static List<Set<String>> subsets(List<String> names) {
        List<Set<String>> out = new ArrayList<>();
        for (int mask = 0; mask < 1 << names.size(); mask++) {
            Set<String> s = new LinkedHashSet<>();
            for (int i = 0; i < names.size(); i++) if ((mask & 1 << i) != 0) s.add(names.get(i));
            out.add(s);
        }
        return out;
    }
}
