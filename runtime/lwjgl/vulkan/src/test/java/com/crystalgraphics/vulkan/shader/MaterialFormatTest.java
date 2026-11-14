package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.CgMaterialShader;
import com.crystalgraphics.material.CgMaterialShaderRegistry;
import com.crystalgraphics.material.shader.CgGlslWriter;
import com.crystalgraphics.material.shader.CgGlslProgram;
import com.crystalgraphics.material.shader.CgEngineKeywords;
import com.crystalgraphics.material.shader.CgShaderPass;
import com.crystalgraphics.material.shader.CgVarying;
import com.crystalgraphics.material.shader.CgShaderSpec;
import com.crystalgraphics.material.shader.syntax.CgShaderError;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.util.io.CgIO;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The material format: each shape and every engine shader built by shaderc on the null device, every pass and keyword
 * variant, as a Vulkan device builds it; and what each shape's parse reads.
 */
public class MaterialFormatTest {

    /** Each shape, under {@code cgformat:shaders/}. */
    private static final List<String> SHAPES = List.of("opaque", "masked", "translucent", "glow", "haze", "waves",
            "sparks", "keywords", "scene_in", "bits", "volume", "post_chain", "both");

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
    public void aFactoryFadesWhatTheMaterialAddsAndStartsItsSurface() {
        CgShaderPass forward = parse(path("faded")).passes().get(0);
        String body = forward.fragmentBody();
        assertTrue(body, body.contains("s.light = vec2(15.0, 15.0);"));                     // its defaults
        assertTrue(body, body.contains("s.baseColor *= _cg_fade;") && body.contains("s.opacity *= _cg_fade;"));
        assertTrue(body, body.contains("cg_Light = s.light;"));                              // the defaults' hook runs
        assertTrue(body, body.contains("gl_FragDepth = cg_WindowDepth(s.depth);"));
    }

    @Test
    public void everyPassAndVariantBuildsOnTheDevice() {
        List<String> failures = new ArrayList<>();
        int programs = 0;
        // and docs/MATERIAL_FORMAT.md's examples, so the guide compiles as written
        List<String> shapes = new ArrayList<>(List.of("puff", "wobble", "faded","guide/glass", "guide/moss", "guide/haze",
                "guide/sparks", "guide/shell", "guide/desaturate", "guide/soft_glow", "guide/pill", "guide/shimmer", "guide/layers"));
        shapes.addAll(SHAPES);
        for (String shape : shapes) programs += build(path(shape), failures);
        assertTrue(String.join(System.lineSeparator(), failures), failures.isEmpty());
        assertTrue("built " + programs, programs > SHAPES.size());
    }

    /** Every engine shader builds, every pass and keyword variant: one not in the format fails naming its #domain line. */
    @Test
    public void everyEngineShaderInTheFormatBuilds() throws Exception {
        URL root = MaterialFormatTest.class.getClassLoader().getResource("assets/crystalgraphics/shaders");
        assertNotNull("the engine's shaders on the classpath", root);
        URI uri = root.toURI();
        List<String> failures = new ArrayList<>();
        int shaders = 0;
        // In a jar when core is built, a directory when it is a source set on the classpath.
        try (FileSystem jar = "jar".equals(uri.getScheme()) ? FileSystems.newFileSystem(uri, Map.of()) : null;
             Stream<Path> files = Files.walk(jar != null ? jar.provider().getPath(uri) : Paths.get(uri))) {
            Path dir = jar != null ? jar.provider().getPath(uri) : Paths.get(uri);
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".shader"))::iterator) {
                shaders++;
                build("crystalgraphics:shaders/" + dir.relativize(f).toString().replace('\\', '/'), failures);
            }
        }
        assertTrue(String.join(System.lineSeparator(), failures), failures.isEmpty());
        assertTrue("no engine shader in the format", shaders > 0);
    }

    /** Builds every pass and keyword variant of {@code path}, and its generated depth pass; how many programs. */
    private static int build(String path, List<String> failures) {
        CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreate(path);
        shader.recompile();   // another test may have parsed it, compiling nothing
        if (shader.hasCompileFailed()) {
            failures.add(shader.lastCompileError());
            return 0;
        }
        int programs = 0;
        CgShaderSpec parsed = shader.getLastParsed();
        CgShaderPass forward = parsed.pass(CgRenderPassVariant.FORWARD);
        for (CgShaderPass pass : parsed.passes()) {
            for (Set<String> keywords : pass == forward ? variants(parsed.features()) : List.of(Set.<String>of())) {
                programs++;
                if (shader.getOrCompile(pass.name(), keywords) == null) {
                    failures.add(path + " pass " + pass.name() + " " + keywords + ": did not build (see log)");
                }
            }
        }
        if (parsed.queue() < 3000 && !shader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName())) {
            failures.add(path + ": the generated depth pass did not build (see log)");
        }
        return programs;
    }

    /** Every combination of up to six keywords; past that each alone, and none. */
    private static List<Set<String>> variants(List<String> names) {
        if (names.size() <= 6) return subsets(names);
        List<Set<String>> out = new ArrayList<>(List.of(Set.of()));
        for (String n : names) out.add(Set.of(n));
        return out;
    }

    @Test
    public void worldPositionOffsetReachesTheDepthPass() {
        CgShaderSpec waves = parse(path("waves"));
        CgShaderPass forward = waves.passes().get(0);
        assertFalse("the material's vertex() is no simple vertex", forward.simpleVertex());
    }

    @Test
    public void aHelperReachesOnlyTheStagesCallingIt() {
        CgShaderSpec waves = parse(path("waves"));
        CgShaderPass forward = waves.passes().get(0);
        assertEquals(CgShaderPass.FRAGMENT, stagesOf(forward, "float ripple("));
        assertEquals(CgShaderPass.VERTEX, stagesOf(forward, "float wave("));
    }

    @Test
    public void marksAndTheGlobalDomainAreRead() {
        CgShaderSpec keywords = parse(path("keywords"));
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

    @Test
    public void onlyTheParticleFieldsNamedReachTheFragment() {
        // light: the factory's defaults() reads it.
        assertEquals(List.of("uv0", "_cg_in_Particle_color", "_cg_in_Particle_relativeTime", "_cg_in_Particle_light"),
                fieldNames(parse(path("puff")).passes().get(0)));
    }

    @Test
    public void meshesAndParticlesTakeTheirOwnFactory() {
        CgShaderSpec both = parse(path("both"));
        for (String usage : List.of("FX_ON_MESH_PARTICLES", "FX_ON_SPRITES", "FX_ON_RIBBONS")) {
            assertTrue(usage + " declared", both.features().contains(usage));
            assertFalse(usage + " served, not taken undeclared", both.implicitFeatures().contains(usage));
        }
        String vertex = both.passes().get(0).vertexBody();
        assertTrue(vertex, vertex.contains("defined(FX_ON_MESH_PARTICLES) || defined(FX_ON_SPRITES) || defined(FX_ON_RIBBONS)"));
    }

    @Test
    public void aRingNamedPassesTheWholeRing() {
        List<String> fields = fieldNames(parse(path("volume")).passes().get(0));
        assertTrue(fields.toString(), fields.containsAll(List.of("_cg_in_Tube_ring_position", "_cg_in_Tube_ring_radius",
                "_cg_in_Tube_ring_intensity", "_cg_in_Tube_origin")));
        assertFalse(fields.toString(), fields.contains("_cg_in_Tube_row"));
    }

    @Test
    public void aModsFactoryIsReadFromItsFile() {
        assertEquals(List.of("uv0", "_cg_in_Wobble_phase"), fieldNames(parse(path("wobble")).passes().get(0)));
        try {
            CgShaderSpec.read("#domain surface\nMaterial { VertexFactory cgformat:Nowhere }\n"
                    + "void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(1.0); }\n", "test:nowhere");
            fail("no factory file");
        } catch (CgShaderError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cgformat:shaders/factories/Nowhere.factory"));
        }
    }

    @Test
    public void aPostFileSaysWhereItDraws() {
        assertEquals("BEFORE_TRANSLUCENCY", parse(path("crystalgraphics:shaders/post/height_fog")).postLocation());
        assertEquals(CgMaterialDomain.POST, parse(path("post_chain")).domain());
    }

    private static List<String> fieldNames(CgShaderPass pass) {
        List<String> out = new ArrayList<>();
        for (CgVarying v : pass.varyings()) out.add(v.name());
        return out;
    }

    private static int stagesOf(CgShaderPass pass, String declaration) {
        for (CgShaderPass.Code c : pass.code()) if (c.text().contains(declaration)) return c.stages();
        throw new AssertionError(declaration + " is in no chunk of " + pass.name());
    }

    private static CgShaderSpec parse(String path) {
        String source = CgIO.loadSource(path);
        assertNotNull("no source at " + path, source);
        return CgShaderSpec.read(source, path);
    }

    private static String path(String name) {
        return (name.contains(":") ? name : "cgformat:shaders/" + name) + ".shader";
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
