package com.crystalgraphics.vulkan;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.joml.Matrix4f;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * The engine's own code on the tracked backend over shaderc and a recording device: every shader CrystalGraphics
 * ships linked in every pass and keyword variant, and a frame drawn through each of its renderers (D3.4, D3.6).
 */
public class EngineOnTrackedBackendTest {

    /** The annotated format reference: prose and commented-out passes, and it does not parse. */
    private static final String DOCUMENTATION_ONLY = "example.shader";

    private static final String[][] RAW = {
            {"crystalgraphics:shader/default.vert", "crystalgraphics:shader/default.frag"},
            {"crystalgraphics:shader/diag_atlas.vert", "crystalgraphics:shader/diag_atlas.frag"}};

    private static ShadercGlslCompiler compiler;
    private static CgRecordingDevice device;
    private static CgTrackedGLBackend gl;

    /** One backend for the class: the engine's registries are process-wide and hold names from the first. */
    @BeforeClass
    public static void install() throws ClassNotFoundException {
        compiler = new ShadercGlslCompiler();
        device = new CgRecordingDevice(64, 64);
        gl = new CgTrackedGLBackend(device, compiler, true);
        CgGL.init(gl);
        CgCapabilities.init(new CgTrackedGLContext());
        CgCapabilities.clearCache();
        CgGlState.reset();
        CgGlState.setProvider(new CgTrackedStateProvider(gl));
        // CgGraphicsLifecycle.initContext's own steps, less the platform it would ask for a context.
        CgBindingPoints.init(CgCapabilities.detect());
        CgFallbackTextures.init();
        // text.shader's material is only complete once the text renderer has attached its UBO.
        Class.forName("com.crystalgraphics.text.render.CgTextRenderer");
    }

    @AfterClass
    public static void uninstall() {
        CgGlState.reset();
        compiler.close();
    }

    @Test
    public void everyMaterialPassAndKeywordVariantLinks() throws Exception {
        List<String> materials = shippedMaterials();
        List<String> failures = new ArrayList<>();
        int programs = 0;
        for (String path : materials) {
            CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreate(path);
            if (shader.getLastParsed() == null || shader.hasCompileFailed()) shader.recompile();
            if (shader.hasCompileFailed()) {
                failures.add(shader.lastCompileError());
                continue;
            }
            CgParsedShader parsed = shader.getLastParsed();
            CgParsedPass forward = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            for (CgParsedPass pass : parsed.passes()) {
                List<Set<String>> variants = pass == forward ? subsets(parsed.featureNames()) : List.of(Set.of());
                for (Set<String> keywords : variants) {
                    programs++;
                    if (shader.getOrCompile(pass.name(), keywords) == null)
                        failures.add(path + " pass " + pass.name() + " " + keywords + ": did not link (see log)");
                }
            }
            CgMaterial material = CgMaterial.newInstance(path);
            if (CgMaterialShader.SHADOWS_SUPPORTED && material.hasShadowCasterPass() && !shader.hasCompiledPass(CgRenderPassVariant.SHADOW.lightModeName()))
                failures.add(path + ": the generated shadow pass did not link (see log)");
            if (parsed.renderQueue() < 3000 && forward != null && !material.hasDepthPass()
                    && !shader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName()))
                failures.add(path + ": the generated depth pass did not link (see log)");
            material.delete();
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("no program was linked: the test proves nothing", programs > materials.size());
    }

    @Test
    public void everyRawShaderLinks() {
        List<String> failures = new ArrayList<>();
        for (String[] pair : RAW) {
            CgShader shader = CgShaderFactory.load(pair[0], pair[1]);
            if (!shader.isCompiled()) failures.add(pair[0] + ": " + shader.getLastCompileError());
            shader.delete();
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    /** The three ways the engine draws, recorded onto the device: every pass and draw it accepted, none refused. */
    @Test
    public void theEngineDrawsOntoTheDevice() {
        long draws = device.draws();
        int mark = device.mark();
        int passes = device.passes().size();
        CgPassConstants constants = CgImmediate.constants();
        constants.view.identity();
        constants.projection.identity().ortho(0, 64, 64, 0, -1, 1);
        constants.resolution(64, 64).cameraFromView();

        // An object draw: the frame block, the object buffer and a material, as a world pass draws them.
        CgMesh cube = CgMeshShapes.cube();
        CgMaterial demo = CgMaterial.load("crystalgraphics:shaders/demo_render.shader");
        try (CgImmediate draw = CgImmediate.begin(constants)) {
            draw.chunks().draw(demo.pipeline(CgInstanceKind.OBJECT), demo.captureBindings(draw.bindings()), cube);
            int at = draw.chunks().instance();
            float[] data = draw.chunks().data();
            new Matrix4f().translation(32, 32, 0).scale(16).get(data, at);
            new Matrix4f().get(data, at + 16);
        }

        // CgQuadRenderer's instance buffer, and a sampler left at its default.
        CgQuadRenderer quads = CgQuadRenderer.create();
        quads.begin();
        quads.useMaterial(CgMaterial.fromSource(QUAD_SHADER));
        quads.quad().at(4, 4).size(24, 24).color(0xFF40C0FF).submit();
        quads.flush();
        quads.end();

        // CgVectorRenderer's, read from both stages.
        CgVectorRenderer strokes = CgVectorRenderer.create();
        strokes.begin();
        strokes.useMaterial(CgMaterial.load("crystalgraphics:shaders/curve.shader"));
        strokes.curve().from(4, 60).via(32, 8).to(60, 60).width(3f).color(0xFFFFC24D).submit();
        strokes.flush();
        strokes.end();

        gl.endFrame();
        assertTrue("a draw was refused or dropped: " + gl.stats() + " error 0x" + Integer.toHexString(gl.glGetError())
                + System.lineSeparator() + String.join(System.lineSeparator(), device.logSince(mark)),
                device.draws() - draws >= 3);   // the object, the quads, the curve
        assertTrue(device.passes().size() > passes);
        quads.delete();
        strokes.delete();
    }

    /** The smallest quad material: what every CrystalGUI quad shader is built on. */
    private static final String QUAD_SHADER = """
            #type none
            #pragma cg_use quad
            Queue = "Overlay"
            Properties {
                _MainTex ("Texture", sampler2D) = "white"
            }
            struct v2f {
                vec2 uv;
                vec4 color;
            };
            Pass {
                RenderState {
                    Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
                    Cull OFF
                    DepthTest ALWAYS
                    DepthWrite OFF
                }
                void vertex(out v2f o) {
                    gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
                    o.uv = CG_QUAD_UV;
                    o.color = CG_QUAD_COLOR;
                }
                void fragment(in v2f i, out vec4 fragColor) {
                    fragColor = texture(_MainTex, i.uv) * i.color;
                }
            }
            """;

    /** Every {@code .shader} directly under core's {@code assets/crystalgraphics/shaders/}, jar or directory. */
    private static List<String> shippedMaterials() throws Exception {
        URI dir = CgMaterial.class.getResource("/assets/crystalgraphics/shaders/").toURI();
        try (FileSystem jar = "jar".equals(dir.getScheme()) ? FileSystems.newFileSystem(dir, Map.of()) : null;
             Stream<Path> files = Files.list(Path.of(dir))) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".shader") && !n.equals(DOCUMENTATION_ONLY))
                    .sorted().map(n -> "crystalgraphics:shaders/" + n).toList();
        }
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
