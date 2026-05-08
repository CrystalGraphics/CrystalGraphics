package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderPreprocessor;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.material.*;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.util.io.CgIO;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
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
 * <h3>Hot-reload</h3>
 * <p>{@link #markDirty()} marks the material dirty; the full load→parse→compile
 * pipeline runs lazily on the next {@link #bind()} call via {@link #recompile()}.
 * The backing {@link CgShader} reference is stable across reloads — only the
 * underlying GL program ID changes.</p>
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
 * </ul>
 */
public final class CgMaterial {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterial");

    /** The compiled shader backing this material. Owned by this instance. Null until first {@link #recompile()}. */
    private CgShader shader;

    /** Live property objects for this material, containing current values. Set/updated by {@link #recompile()}. */
    private List<CgMaterialProperty> properties;

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    /**
     * Whether this material needs a full recompile on the next {@link #bind()}.
     * Set by {@link #markDirty()}; cleared at the start of {@link #recompile()}.
     */
    private boolean dirty;

    /**
     * Resource path to the {@code .shader} file.
     * {@code null} for programmatic or shader-graph materials that have no backing file.
     * Set post-construction via {@link #setResourcePath(String)} in {@link #create(String)}.
     */
    @Setter
    private String resourcePath;

    // ── Constructor (private — use load()) ───────────────────────────────────

    private CgMaterial() {}

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
     * Creates a new {@code CgMaterial} from a {@code .shader} file.
     * Called only by {@link CgMaterialRegistry}; external callers use {@link #load}.
     *
     * @throws IllegalArgumentException  if the source cannot be loaded or is empty
     * @throws CgShaderParseException    on parse error
     * @throws IllegalStateException     if compilation or linking fails
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    static CgMaterial create(String resourcePath) {
        CgMaterial mat = new CgMaterial();
        mat.setResourcePath(resourcePath);
        mat.recompile();
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
     *   <li>If {@link #dirty}, calls {@link #recompile()} to hot-reload the shader.</li>
     *   <li>Stage property values into ephemeral bindings</li>
     *   <li>{@code shader.bind()} — activates the GL program and flushes all bindings</li>
     *   <li>Binds the pipeline's object buffer and wires it to the active shader</li>
     * </ol>
     */
    public void bind() {
        checkNotDeleted();

        if (dirty) recompile();
        if (shader == null) return;

        shader.applyBindings(b -> {
            for (CgMaterialProperty prop : properties) {
                prop.applyTo(b);
            }
        });

        shader.bind();
        objectBuffer().bind(shader);
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
     * Marks this material dirty so it will be fully recompiled from {@link #resourcePath}
     * on the next {@link #bind()} call. Called by {@link CgMaterialRegistry#reloadAll()}
     * during hot-reload (F3+T).
     *
     * <p>If the material has already been deleted, this is a no-op.</p>
     */
    public void markDirty() {
        if (!deleted) dirty = true;
    }

    /**
     * Compiles (or recompiles) this material from {@link #resourcePath}.
     * Serves as the single compilation path for both initial load and hot-reload.
     *
     * <p>On first call ({@code shader == null}): throws on any failure — never returns
     * with a broken state. On subsequent calls (hot-reload): logs errors and keeps the
     * old program running on failure.</p>
     *
     * <p>No-op when {@code resourcePath} is null (shader-graph / programmatic materials).</p>
     */
    public void recompile() {
        dirty = false;
        if (resourcePath == null) return;

        boolean isFirst = (shader == null);

        String source;
        try {
            source = CgIO.loadSource(resourcePath);
        } catch (Exception e) {
            if (isFirst) throw new IllegalArgumentException("Could not load shader source from: " + resourcePath, e);
            LOGGER.error("Reload failed for '{}': could not load source — {}", resourcePath, e.getMessage());
            return;
        }
        if (source == null || source.isEmpty()) {
            if (isFirst) throw new IllegalArgumentException("Could not load shader source from: " + resourcePath);
            LOGGER.error("Reload failed for '{}': empty source", resourcePath);
            return;
        }

        CgParsedShader parsed;
        try {
            parsed = CgShaderParser.parse(source, resourcePath);
        } catch (CgShaderParseException e) {
            if (isFirst) throw e;
            LOGGER.error("Reload failed for '{}': parse error — {}", resourcePath, e.getMessage());
            return;
        }

        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().preferredShaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE) {
            if (isFirst) throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader materials");
            LOGGER.error("Reload failed for '{}': GL 3.3+ not available", resourcePath);
            return;
        }

        CgMaterialShaderCompiler.CompiledSource compiled = CgMaterialShaderCompiler.compile(parsed, path);
        String processedVert = new CgShaderPreprocessor().process(compiled.vertexSource(), resourcePath);
        String processedFrag = new CgShaderPreprocessor().process(compiled.fragmentSource(), resourcePath);

        if (System.getProperty("crystalgraphics.material.dumpGlsl") != null) {
            LOGGER.info("=== CgMaterial GLSL dump for '{}' ===", resourcePath);
            LOGGER.info("--- VERTEX ---\n{}", processedVert);
            LOGGER.info("--- FRAGMENT ---\n{}", processedFrag);
            LOGGER.info("=== end GLSL dump ===");
        }

        if (isFirst) {
            shader = CgShaderFactory.fromSource(processedVert, processedFrag, CgVertexFormat.SPATIAL);
            if (!shader.isCompiled()) {
                String err = shader.getLastCompileError();
                shader.delete();
                shader = null;
                throw new IllegalStateException("CgMaterial.create() failed for '" + resourcePath + "': " + err);
            }
        } else {
            shader.setSource(processedVert, processedFrag);
            shader.recompile();
            if (!shader.isCompiled()) {
                LOGGER.error("Reload failed for '{}': compile error — {}", resourcePath, shader.getLastCompileError());
                return;
            }
        }

        properties = new ArrayList<>(parsed.properties());
        CgMaterialPipeline.getInstance().frameBuffer().bind(shader);

        if (!isFirst) {
            LOGGER.info("Reloaded '{}'", resourcePath);
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
            if (shader != null) shader.delete();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("CgMaterial has been deleted");
        }
    }
}
