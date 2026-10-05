package com.crystalgraphics.vulkan;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureCubemap;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.gpu.CgUploads;
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
import com.sun.management.ThreadMXBean;
import org.joml.Matrix4f;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
            List<String> multiDrawn = new ArrayList<>();
            for (CgParsedPass pass : parsed.passes()) {
                List<Set<String>> variants = pass == forward ? subsets(parsed.featureNames()) : List.of(Set.of());
                for (Set<String> keywords : variants) {
                    programs++;
                    if (shader.getOrCompile(pass.name(), keywords) == null)
                        failures.add(path + " pass " + pass.name() + " " + keywords + ": did not link (see log)");
                }
                multiDrawn.add(pass.name());
            }
            for (CgRenderPassVariant generated : List.of(CgRenderPassVariant.DEPTH, CgRenderPassVariant.SHADOW)) {
                String name = generated.lightModeName();
                if (parsed.getPassByName(name) == null && shader.hasCompiledPass(name)) multiDrawn.add(name);
            }
            for (String pass : multiDrawn) {   // what an executor binds for a run of draws joined into one call
                programs++;
                if (shader.getOrCompile(pass, Set.of(CgMaterialShaderCompiler.MULTI_DRAW)) == null)
                    failures.add(path + " pass " + pass + " multi-draw: did not link (see log)");
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

    /**
     * A texture made and filled on a worker lands from the worker's lease, with no copy on this thread: a device copy
     * from the unpack buffer where nothing converts, staged by the device where the driver would convert.
     */
    @Test
    public void texturesFilledOnAWorkerLandFromItsLeases() throws InterruptedException {
        CgUploads.tick();   // the render thread picks the tier and makes the first block
        assertEquals(CgUploads.Tier.UNPACK, CgUploads.tier());
        ByteBuffer rgba = ByteBuffer.allocateDirect(32 * 32 * 4), rgb = ByteBuffer.allocateDirect(8 * 8 * 3);
        CgTexture2D[] made = new CgTexture2D[2];
        Thread worker = new Thread(() -> {
            made[0] = CgTexture2D.createFromPixels(32, 32, rgba, CgTextureSpec.RGBA8_LINEAR);
            made[1] = CgTexture2D.createEmpty(8, 8, CgTextureSpec.RGBA8_LINEAR);
            made[1].uploadRegion(0, 0, 0, 8, 8, rgb, CgGL.GL_RGB, CgGL.GL_UNSIGNED_BYTE);
        });
        worker.start();
        worker.join();

        int mark = device.mark();
        CgDeferral.applyAll();
        List<String> landed = device.logSince(mark);
        assertEquals(landed.toString(), 1, landed.stream().filter(c -> c.startsWith("copyBufferToTexture")).count());
        assertEquals("the RGB region converts, so its lease was direct memory: " + landed, 1,
                landed.stream().filter(c -> c.startsWith("writeTexture")).count());
        assertNotEquals(0, made[0].getId());
        made[0].delete();
        made[1].delete();
    }

    /** A cubemap made and filled on a worker lands each face from the worker's lease, as a 2D texture does. */
    @Test
    public void aCubemapFilledOnAWorkerLandsFromItsLeases() throws InterruptedException {
        CgUploads.tick();
        ByteBuffer face = ByteBuffer.allocateDirect(16 * 16 * 4);
        CgTextureCubemap[] made = new CgTextureCubemap[1];
        Thread worker = new Thread(() -> {
            made[0] = CgTextureCubemap.createEmpty(16, CgTextureSpec.RGBA8_LINEAR);
            for (int f = 0; f < 6; f++) made[0].uploadFace(f, 0, 0, 0, 16, 16, face, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        });
        worker.start();
        worker.join();

        int mark = device.mark();
        CgDeferral.applyAll();
        List<String> landed = device.logSince(mark);
        assertEquals(landed.toString(), 6, landed.stream().filter(c -> c.startsWith("copyBufferToTexture")).count());
        assertNotEquals(0, made[0].getId());
        made[0].delete();
        CgDeferral.applyAll();
    }

    /** A retained shader buffer made and written on a worker holds what it wrote once the render thread lands it. */
    @Test
    public void aRetainedShaderBufferWrittenOnAWorkerLands() throws InterruptedException {
        CgBufferFormat format = CgBufferFormat.builder("Height", CgBufferFormat.MemoryLayout.STD430).vec4("h").build();
        CgShaderBuffer[] made = new CgShaderBuffer[1];
        Thread worker = new Thread(() -> {
            made[0] = CgShaderBuffer.create("Heights", format, 0);
            made[0].beginWrite(4);
            for (int i = 0; i < 4; i++) {
                made[0].writer().beginRecord().vec4("h", i, 0f, 0f, 0f);
                made[0].endRecord();
            }
            made[0].endWrite();
        });
        worker.start();
        worker.join();

        CgDeferral.applyAll();
        int id = made[0].getGlBufferId();
        assertNotEquals(0, id);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, id);
        ByteBuffer held = CgGL.glMapBufferRange(CgGL.GL_COPY_READ_BUFFER, 0, 64, CgGL.GL_MAP_READ_BIT, null)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) assertEquals("record " + i, i, held.getFloat(16 * i), 0f);
        CgGL.glUnmapBuffer(CgGL.GL_COPY_READ_BUFFER);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);

        Thread deleter = new Thread(made[0]::delete);
        deleter.start();
        deleter.join();
        CgDeferral.applyAll();
        assertEquals("deleted on the render thread", 0, made[0].getGlBufferId());
    }

    /** A deferred upload costs its thread a copy into a pooled lease and no allocation, once the pool is warm. */
    @Test
    public void aDeferredUploadAllocatesNothingOnItsThread() throws InterruptedException {
        CgUploads.tick();
        CgTexture2D target = CgTexture2D.createEmpty(64, 64, CgTextureSpec.RGBA8_LINEAR);
        ByteBuffer tile = ByteBuffer.allocateDirect(16 * 16 * 4);
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] allocated = new long[2];
        for (int round = 0; round < 2; round++) {
            int r = round;
            Thread worker = new Thread(() -> {
                long before = threads.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < 50; i++) target.uploadRegion(0, (i % 4) * 16, (i / 4 % 4) * 16, 16, 16, tile, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
                allocated[r] = threads.getCurrentThreadAllocatedBytes() - before;
            });
            worker.start();
            worker.join();
            CgDeferral.applyAll();
        }
        assertTrue("50 warm uploads allocated " + allocated[1] + " bytes (the first 50: " + allocated[0] + ")",
                allocated[1] < 2048);
        target.delete();
        CgDeferral.applyAll();
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
