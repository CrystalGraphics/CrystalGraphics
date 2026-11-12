package com.crystalgraphics.gpu.resource;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.buffer.CgBufferLayout;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The engine's shared shader buffers, one per name, layout and reserved binding point, deleted together at shutdown
 * ({@link #deleteAll()}). A consumer's buffer is {@code CgBuffer} ({@code CgBuffers.create}).
 *
 * <pre>{@code
 * // rewritten every frame, before the draws that read it: the frame ring
 * CgShaderBuffer shapes = CgShaderBufferRegistry.get()
 *     .getOrCreateInternal("CgShapeTable", FORMAT, CgBindingPoints.SHAPE_TABLE, CgBufferLifetime.FRAME);
 * }</pre>
 *
 * <p>A name, format and binding asked for again returns the same buffer, and must ask for the same
 * {@link CgBufferLifetime}: two lifetimes at one binding is a conflict, and throws.</p>
 *
 * <p>All registered buffers are deleted by {@link #deleteAll()}, which is called from
 * {@code CgEngineLifecycle.shutdown()}.</p>
 */
public final class CgShaderBufferRegistry {

    private static final CgShaderBufferRegistry INSTANCE = new CgShaderBufferRegistry();

    /** SSBO cache — keyed by (name, format, bindingPoint). */
    private final Map<ShaderBufferKey, CgShaderBuffer> shaderBufferCache = new HashMap<>();

    /** UBO cache — keyed by (name, format, bindingPoint). Separate cache, same key type. */
    private final Map<ShaderBufferKey, CgUniformBuffer> uboCache = new HashMap<>();

    /** Each engine-reserved binding's buffer, made by {@link #getOrCreateInternal}. */
    private final Map<CgBindingPoints.Binding, CgShaderBuffer> owners = new HashMap<>();

    private CgShaderBufferRegistry() {}

    /** Returns the global singleton registry. */
    public static CgShaderBufferRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns (or lazily creates) a format-aware SSBO at an <strong>engine-reserved</strong>
     * binding — a {@link CgBindingPoints.Binding} (such as {@code CgBindingPoints.QUAD_RENDERER})
     * rather than an index. Unlike
     * {@link #getOrCreate(String, CgBufferLayout, int)}, no offset is added — the binding
     * resolves itself to an absolute SSBO binding point (see
     * {@link CgBindingPoints.Binding#resolve()}), used verbatim, matching
     * {@link CgShaderBuffer#createInternal(String, CgBufferLayout, int)}'s own contract.
     *
     * <p>Still participates in this registry's cache (dedup by name+format+binding) and in
     * {@link #deleteAll()} teardown, same as {@link #getOrCreate(String, CgBufferLayout, int)} —
     * the only difference is binding-point resolution.</p>
     *
     * <p><strong>Engine-internal.</strong> Reserve a new {@link CgBindingPoints.Binding} constant
     * (resolved in {@link CgBindingPoints#init(CgCapabilities)})
     * for each distinct engine subsystem that needs one — do not share a single reserved
     * {@code Binding} across unrelated consumers, and do not call this with a raw user-chosen
     * integer.</p>
     *
     * @param name    debug/sampler name for the buffer
     * @param format  typed format descriptor for the buffer records
     * @param binding the reserved {@link CgBindingPoints.Binding} to bind at
     * @return the cached or newly-created shader buffer
     */
    public CgShaderBuffer getOrCreateInternal(String name, CgBufferLayout format, CgBindingPoints.Binding binding) {
        return getOrCreateInternal(name, format, binding, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #getOrCreateInternal(String, CgBufferLayout, CgBindingPoints.Binding)}, with the contents'
     * {@link CgBufferLifetime}. What the quad and curve renderers' instance data use.
     *
     * <pre>{@code
     * CgShaderBuffer instances = CgShaderBufferRegistry.get()
     *         .getOrCreateInternal("QuadInstances", FORMAT, CgBindingPoints.QUAD_RENDERER, CgBufferLifetime.FRAME);
     * instances.uploadRaw(data, floats);   // re-binds at the new offset
     * mesh.drawInstanced(count);
     * }</pre>
     */
    public CgShaderBuffer getOrCreateInternal(String name, CgBufferLayout format, CgBindingPoints.Binding binding,
                                              CgBufferLifetime lifetime) {
        int resolvedBinding = binding.resolve();
        ShaderBufferKey key = new ShaderBufferKey(name, format, resolvedBinding);
        CgShaderBuffer existing = shaderBufferCache.get(key);
        if (existing != null) return sameLifetime(existing, lifetime);
        CgShaderBuffer buf = CgShaderBuffer.createInternal(name, format, resolvedBinding, lifetime);
        shaderBufferCache.put(key, buf);
        owners.put(binding, buf);
        return buf;
    }

    /**
     * The engine buffer made at {@code binding} by {@link #getOrCreateInternal}, or null if none has been: what a draw
     * reading a buffer of its own there puts back after it.
     *
     * <pre>{@code
     * CgShaderBuffer frames = CgShaderBufferRegistry.get().ownerOf(CgBindingPoints.PARTICLES);
     * if (frames != null) frames.bind();
     * }</pre>
     */
    @Nullable
    public CgShaderBuffer ownerOf(CgBindingPoints.Binding binding) {
        return owners.get(binding);
    }

    /**
     * A uniform block at an <strong>engine-reserved</strong>
     * slot -- a {@code CgBindingPoints} UBO constant used verbatim, as the text renderer's block is.
     */
    public CgUniformBuffer getOrCreateUboInternal(CgBufferLayout format, String name, int bindingPoint,
                                                  CgBufferLifetime lifetime) {
        return ubo(format, name, bindingPoint, lifetime);
    }

    private CgUniformBuffer ubo(CgBufferLayout format, String name, int bindingPoint, CgBufferLifetime lifetime) {
        ShaderBufferKey key = new ShaderBufferKey(name, format, bindingPoint);
        CgUniformBuffer existing = uboCache.get(key);
        if (existing != null) return sameLifetime(existing, lifetime);
        CgUniformBuffer ubo = new CgUniformBuffer(name, format, bindingPoint, lifetime);
        uboCache.put(key, ubo);
        return ubo;
    }

    // One binding, one lifetime: a second caller asking for the other would read the first's storage wrongly.
    private static <B extends CgShaderBuffer> B sameLifetime(B existing, CgBufferLifetime asked) {
        if (existing.getLifetime() != asked) {
            throw new IllegalStateException("Shader buffer '" + existing.getName() + "' already exists as "
                    + existing.getLifetime() + ", asked for " + asked);
        }
        return existing;
    }

    /**
     * Deletes all registered buffers and clears both caches.
     * Called by {@code CgEngineLifecycle.shutdown()}.
     * Must be called on the GL thread.
     */
    public void deleteAll() {
        for (CgShaderBuffer buf : shaderBufferCache.values()) {
            buf.delete();
        }
        shaderBufferCache.clear();
        owners.clear();

        for (CgUniformBuffer ubo : uboCache.values()) {
            ubo.delete();
        }
        uboCache.clear();
    }

    // ── Composite key types ───────────────────────────────────────────────────

    /**
     * Value-equal cache key for the SSBO cache. Covers every SSBO
     * since they share the same identity contract.
     *
     * @param name         Buffer debug/sampler name. Part of the identity contract.
     * @param format       Buffer format. Value equality.
     * @param bindingPoint GL binding point.
     */
    @Desugar
    record ShaderBufferKey(String name, CgBufferLayout format, int bindingPoint) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ShaderBufferKey)) return false;
            ShaderBufferKey other = (ShaderBufferKey) o;
            return bindingPoint == other.bindingPoint
                    && format.equals(other.format)
                    && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            int h = name.hashCode();
            h = 31 * h + format.hashCode();
            h = 31 * h + bindingPoint;
            return h;
        }
    }

}
