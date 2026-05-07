package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderPreprocessor;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.material.*;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.util.io.CgIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.function.Consumer;

/**
 * User-facing material that wraps a compiled {@code .shader} file.
 *
 * <p>A {@code CgMaterial} encapsulates a linked GLSL shader program,
 * property uniforms (declared in the {@code Properties { }} block), and the
 * {@code bind()} / {@code unbind()} lifecycle for draw calls. One material
 * instance is shared across both non-instanced ({@code drawDirect()}) and
 * instanced ({@code drawInstanced(N)}) draw calls — no modification, no
 * variants, no {@code #ifdef INSTANCED}.</p>
 *
 * <h3>Load pattern</h3>
 * <pre>{@code
 * CgMaterial material = CgMaterial.load("mymod:shaders/terrain.shader");
 * material.applyBindings(b -> b.vec4("_Color", 1f, 0f, 0f, 1f));
 * }</pre>
 *
 * <h3>Per-frame bind pattern</h3>
 * <pre>{@code
 * // pipeline.beginFrame() uploads frame UBO automatically.
 * // Write per-object data:
 * objectBuffer.writeSingle(modelMatrix);
 * material.bind();
 * mesh.drawDirect();
 * material.unbind();
 * }</pre>
 *
 * <h3>Ownership</h3>
 * <p>The material owns its backing {@link CgShader}. Call {@link #delete()} to free it.
 * {@link CgUniformBuffer} and {@link CgShaderBuffer} passed to {@link #bind} are
 * <em>caller-owned</em> — this class never deletes them.</p>
 *
 * <h3>Scope guardrails</h3>
 * <ul>
 *   <li>No render-state management — no blend, depth, cull setters.</li>
 *   <li>No shader variants — no DEPTH/SHADOW pass in this MVP.</li>
 *   <li>No hot-reload.</li>
 * </ul>
 */
public final class CgMaterial {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterial");

    /** The compiled shader backing this material. Owned by this instance. */
    private final CgShader shader;

    /** Live property objects for this material, containing current values. */
    private final List<CgMaterialProperty> properties;

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    // ── Constructor (private — use load()) ───────────────────────────────────

    private CgMaterial(CgShader shader, List<CgMaterialProperty> properties) {
        this.shader      = shader;
        this.properties  = properties;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Returns the cached material for {@code resourcePath}, loading it on first access.
     * Delegates to {@link CgMaterialRegistry#get()} which caches by resource path.
     *
     * @param resourcePath resource path to the {@code .shader} file,
     *                     e.g. {@code "mymod:shaders/terrain.shader"}
     * @return a ready-to-use {@code CgMaterial}; the same instance is returned on repeated calls
     */
    public static CgMaterial load(String resourcePath) {
        return CgMaterialRegistry.get().getOrCreate(resourcePath);
    }

    /**
     * Returns the cached material for {@code key.name()}, loading it on first access.
     * Delegates to {@link CgMaterialRegistry#get()}.
     *
     * @param key typed material key
     * @return a ready-to-use {@code CgMaterial}; the same instance is returned for the same key
     */
    public static CgMaterial load(CgMaterialKey key) {
        return CgMaterialRegistry.get().getOrCreate(key);
    }

    /**
     * Compiles a new {@code CgMaterial} from a {@code .shader} file.
     * This is the actual factory — called only by {@link CgMaterialRegistry}.
     * External callers must use {@link #load(String)} or {@link #load(CgMaterialKey)}.
     *
     * <p>Steps performed:</p>
     * <ol>
     *   <li>Reads source via {@link CgIO#loadSource}.</li>
     *   <li>Parses structure via {@link CgShaderParser#parse}.</li>
     *   <li>Detects GPU path via {@link CgCapabilities#preferredShaderBufferPath()}.</li>
     *   <li>Generates GLSL via {@link CgMaterialShaderCompiler#compile}.</li>
     *   <li>Resolves {@code #include} directives via {@link CgShaderPreprocessor}.</li>
     *   <li>Compiles via {@link CgShaderFactory#fromSource(String, String, CgVertexFormat)}.</li>
     *   <li>Wires the {@code CgFrameBlock} UBO persistently via
     *       {@code shader.bindings().ubo(CgMaterialPipeline.getInstance().frameBuffer())}
     *       so it survives hot-reload recompile automatically.</li>
     *   <li>Applies property default values from the {@code Properties} block.</li>
     * </ol>
     *
     * @param resourcePath resource path to the {@code .shader} file
     * @return a ready-to-use {@code CgMaterial}
     * @throws IllegalArgumentException if the source cannot be loaded
     * @throws CgShaderParseException on parse error
     * @throws IllegalStateException if compilation or linking fails
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    static CgMaterial create(String resourcePath) {
        // Step 1: Load .shader source
        String source;
        try {
            source = CgIO.loadSource(resourcePath);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not load shader source from: " + resourcePath, e);
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("Could not load shader source from: " + resourcePath);
        }

        // Step 2: Structural parse — resource path included in all parse exception messages
        CgParsedShader parsed = CgShaderParser.parse(source, resourcePath);

        // Step 3: Detect GPU path (throws for NONE / pre-GL-3.3 hardware)
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().preferredShaderBufferPath();

        // Step 4: Generate GLSL source strings
        CgMaterialShaderCompiler.CompiledSource compiled = CgMaterialShaderCompiler.compile(parsed, path);

        String processedVert = new CgShaderPreprocessor().process(compiled.vertexSource(), resourcePath);
        String processedFrag = new CgShaderPreprocessor().process(compiled.fragmentSource(), resourcePath);

        if (System.getProperty("crystalgraphics.material.dumpGlsl") != null) {
            LOGGER.info("=== CgMaterial GLSL dump for '{}' ===", resourcePath);
            LOGGER.info("--- VERTEX ---\n{}", processedVert);
            LOGGER.info("--- FRAGMENT ---\n{}", processedFrag);
            LOGGER.info("=== end GLSL dump ===");
        }

        // Step 6: Compile and link via existing backend waterfall
        CgShader shader = CgShaderFactory.fromSource(processedVert, processedFrag, CgVertexFormat.SPATIAL);

        // Fail-fast if compile/link failed — never return a broken material
        if (!shader.isCompiled()) {
            String err = shader.getLastCompileError();
            shader.delete();
            throw new IllegalStateException(
                "CgMaterial.load() failed for '" + resourcePath + "': " + err);
        }

        // Step 7: Wire CgFrameBlock UBO persistently via the bindings system so it
        // survives hot-reload recompile automatically (no explicit GL31 calls needed).
        CgMaterial mat = new CgMaterial(shader, parsed.properties());
        mat.shader.bindings().ubo(CgMaterialPipeline.getInstance().frameBuffer());
        // Defaults are already parsed into each CgMaterialProperty by fromDecl() in the parser.
        // They will be flushed to GL uniforms on the first bind() call via applyTo().

        return mat;
    }

    // ── Property bindings ─────────────────────────────────────────────────────

    /**
     * Applies persistent property bindings to the underlying shader.
     * The consumer receives the shader's {@link CgShaderBindings} instance directly —
     * values written here persist across frames until overwritten.
     *
     * <pre>{@code
     * material.applyBindings(b -> {
     *     b.set1f("_Alpha", 0.5f);
     *     b.vec4("_Color", 1f, 0f, 0f, 1f);
     * });
     * }</pre>
     *
     * @param consumer receives the persistent bindings; must not be null
     * @return this for chaining
     */
    public CgMaterial applyBindings(Consumer<CgShaderBindings> consumer) {
        checkNotDeleted();
        consumer.accept(shader.bindings());
        return this;
    }

    // ── Draw-time API ─────────────────────────────────────────────────────────

    /**
     * Binds this material for rendering. Must be called after
     * {@link CgMaterialPipeline#beginFrame} and after writing object records into
     * {@link CgMaterialPipeline#objectBuffer()}.
     *
     * <h3>Operations performed in order</h3>
     * <ol>
     *   <li>Stage property values + TBO sampler (if TBO path) into ephemeral bindings</li>
     *   <li>{@code shader.bind()} — activates the GL program and flushes all bindings</li>
     *   <li>Binds the pipeline's object buffer using its last-written record count</li>
     * </ol>
     */
    public void bind() {
        checkNotDeleted();

        final CgShaderBuffer buf = CgMaterialPipeline.getInstance().objectBuffer();
        shader.applyBindings(b -> {
            for (CgMaterialProperty prop : properties) {
                prop.applyTo(b);
            }
            if (buf.getPath() == CgCapabilities.ShaderBufferPath.TBO) {
                b.set1i("cg_ObjectTBO", buf.getBindingLocation());
            }
        });

        shader.bind();
        buf.bind();
    }

    /**
     * Unbinds shader, frame UBO, and object buffer without GL errors.
     *
     * <p>Must be called after the draw call sequence to leave GL state clean.</p>
     */
    public void unbind() {
        if (deleted) return;
        shader.unbind();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the pipeline's shared per-object SSBO/TBO.
     * Equivalent to {@code CgMaterialPipeline.getInstance().objectBuffer()}.
     *
     * <p>Each object record is exactly {@link CgMaterialPipeline#OBJECT_FORMAT} = 48 floats.
     * Use named writes — unwritten fields are auto-zeroed per record:</p>
     * <pre>{@code
     * CgShaderBuffer buf = material.objectBuffer();
     * CgBufferWriter w = buf.beginWrite(N);
     * for (int i = 0; i < N; i++) {
     *     Matrix4f model  = ...;
     *     Matrix4f normal = new Matrix4f(model).invert().transpose();
     *     w.beginRecord()
     *      .mat4("modelMatrix", model)
     *      .mat4("normalMatrix", normal)
     *      .vec4("custom0", r, g, b, a);   // custom1-3 auto-zeroed
     *     buf.endRecord();
     * }
     * buf.endWrite();
     * material.bind();
     * mesh.drawInstanced(N);
     * material.unbind();
     * }</pre>
     *
     * <p>The buffer auto-grows when {@code N} exceeds its current capacity.
     * Each {@code beginWrite/endWrite} cycle overwrites the previous content —
     * write a fresh batch every frame.</p>
     *
     * @return the pipeline's object buffer; never {@code null}
     * @throws IllegalStateException if {@link CgMaterialPipeline} has not been initialized
     */
    public CgShaderBuffer objectBuffer() {
        return CgMaterialPipeline.getInstance().objectBuffer();
    }

    /**
     * Returns the backing {@link CgShader} for advanced use (e.g. wiring a UBO block
     * after initial setup).
     *
     * @return the compiled shader handle; never {@code null}
     */
    public CgShader getShader() {
        checkNotDeleted();
        return shader;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Marks this material's backing shader dirty so it recompiles on the next {@link #bind()}.
     * Called by {@link CgMaterialRegistry#reloadAll()} during hot-reload.
     */
    public void reload() {
        if (!deleted) {
            shader.markDirty();
        }
    }

    /**
     * Frees the backing shader program. After this call, the material is unusable.
     *
     * <p>Idempotent — subsequent calls are no-ops.</p>
     */
    public void delete() {
        if (!deleted) {
            deleted = true;
            shader.delete();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("CgMaterial has been deleted");
        }
    }
}
