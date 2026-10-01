package com.crystalgraphics.gl.buffer.shader;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;

import java.util.HashMap;
import java.util.Map;

/**
 * Global registry of user-created SSBO/TBO and UBO shader buffers.
 *
 * <p>Provides lifecycle management (via {@link #deleteAll()}) for user-owned buffers.
 * All buffers obtained through this registry use binding points derived from a 0-based
 * {@code userIndex}: the actual binding point is {@code userIndex + CgBindingPoints.USER_START_SSBO}
 * (SSBO path) or {@code userIndex + CgBindingPoints.USER_START_TBO} (TBO path).</p>
 *
 * <p><strong>Engine-internal buffers bypass this registry</strong>: the object records and the frame block
 * occupy engine-reserved binding points, resolved at runtime, and the executor binds them per pass.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // userIndex 0 = first user slot (binding point CgBindingPoints.USER_START)
 * CgShaderBuffer myBuf = CgShaderBufferRegistry.get()
 *     .getOrCreate("myData", MyFormats.PARTICLE_FORMAT, 0);
 *
 * CgUniformBuffer myUbo = CgShaderBufferRegistry.get()
 *     .getOrCreateUbo(MyFormats.LIGHT_FORMAT, "LightBlock", 1);
 *
 * // Rewritten every frame, before the draws that read it: the frame ring.
 * CgShaderBuffer instances = CgShaderBufferRegistry.get()
 *     .getOrCreate("myInstances", MyFormats.INSTANCE_FORMAT, 2, CgBufferLifetime.FRAME);
 * }</pre>
 *
 * <p>A name, format and binding asked for again returns the same buffer, and must ask for the same
 * {@link CgBufferLifetime}: two lifetimes at one binding is a conflict, and throws.</p>
 *
 * <p>All registered buffers are deleted by {@link #deleteAll()}, which is called from
 * {@link CgGraphicsLifecycle#destroyContext()}.</p>
 */
public final class CgShaderBufferRegistry {

    private static final CgShaderBufferRegistry INSTANCE = new CgShaderBufferRegistry();

    /** SSBO/TBO cache — keyed by (name, format, bindingPoint). */
    private final Map<ShaderBufferKey, CgShaderBuffer> shaderBufferCache = new HashMap<>();

    /** UBO cache — keyed by (name, format, bindingPoint). Separate cache, same key type. */
    private final Map<ShaderBufferKey, CgUniformBuffer> uboCache = new HashMap<>();

    private CgShaderBufferRegistry() {}

    /** Returns the global singleton registry. */
    public static CgShaderBufferRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns (or lazily creates) a format-aware SSBO/TBO for the given name, format, and
     * 0-based user index. The actual binding point is {@code userIndex + CgBindingPoints.USER_START_SSBO}
     * (SSBO path) or {@code userIndex + CgBindingPoints.USER_START_TBO} (TBO path).
     *
     * @param name      debug/sampler name for the buffer
     * @param format    typed format descriptor for the buffer records
     * @param userIndex 0-based user slot index (0 = first user slot)
     * @return the cached or newly-created shader buffer
     */
    public CgShaderBuffer getOrCreate(String name, CgBufferFormat format, int userIndex) {
        return getOrCreate(name, format, userIndex, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #getOrCreate(String, CgBufferFormat, int)}, with the contents' {@link CgBufferLifetime}.
     *
     * <pre>{@code
     * CgShaderBuffer particles = CgShaderBufferRegistry.get()
     *         .getOrCreate("Particles", PARTICLE_FORMAT, 0, CgBufferLifetime.FRAME);
     * particles.beginWrite(n);
     * // ... n records ...
     * particles.endWrite();          // this frame's region, re-bound there
     * mesh.drawInstanced(n);
     * }</pre>
     */
    public CgShaderBuffer getOrCreate(String name, CgBufferFormat format, int userIndex, CgBufferLifetime lifetime) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        int binding = (path == CgCapabilities.ShaderBufferPath.TBO)
                ? CgBindingPoints.USER_START_TBO + userIndex
                : CgBindingPoints.USER_START_SSBO + userIndex;
        ShaderBufferKey key = new ShaderBufferKey(name, format, binding);
        CgShaderBuffer existing = shaderBufferCache.get(key);
        if (existing != null) return sameLifetime(existing, lifetime);
        CgShaderBuffer buf = CgShaderBuffer.create(name, format, userIndex, lifetime);
        shaderBufferCache.put(key, buf);
        return buf;
    }

    /**
     * Returns (or lazily creates) a format-aware SSBO/TBO at an <strong>engine-reserved</strong>
     * binding — a {@link CgBindingPoints.Binding} (such as {@code CgBindingPoints.QUAD_RENDERER})
     * rather than a {@code USER_START_*}-relative user index. Unlike
     * {@link #getOrCreate(String, CgBufferFormat, int)}, no offset is added — the binding
     * resolves itself to an absolute SSBO binding point or TBO texture unit (see
     * {@link CgBindingPoints.Binding#resolve()}), used verbatim, matching
     * {@link CgShaderBuffer#createInternal(String, CgBufferFormat, int)}'s own contract.
     *
     * <p>Still participates in this registry's cache (dedup by name+format+binding) and in
     * {@link #deleteAll()} teardown, same as {@link #getOrCreate(String, CgBufferFormat, int)} —
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
    public CgShaderBuffer getOrCreateInternal(String name, CgBufferFormat format, CgBindingPoints.Binding binding) {
        return getOrCreateInternal(name, format, binding, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #getOrCreateInternal(String, CgBufferFormat, CgBindingPoints.Binding)}, with the contents'
     * {@link CgBufferLifetime}. What the quad and curve renderers' instance data use.
     *
     * <pre>{@code
     * CgShaderBuffer instances = CgShaderBufferRegistry.get()
     *         .getOrCreateInternal("QuadInstances", FORMAT, CgBindingPoints.QUAD_RENDERER, CgBufferLifetime.FRAME);
     * instances.uploadRaw(data, floats);   // re-binds at the new offset
     * mesh.drawInstanced(count);
     * }</pre>
     */
    public CgShaderBuffer getOrCreateInternal(String name, CgBufferFormat format, CgBindingPoints.Binding binding,
                                              CgBufferLifetime lifetime) {
        int resolvedBinding = binding.resolve();
        ShaderBufferKey key = new ShaderBufferKey(name, format, resolvedBinding);
        CgShaderBuffer existing = shaderBufferCache.get(key);
        if (existing != null) return sameLifetime(existing, lifetime);
        CgShaderBuffer buf = CgShaderBuffer.createInternal(name, format, resolvedBinding, lifetime);
        shaderBufferCache.put(key, buf);
        return buf;
    }

    /**
     * Returns (or lazily creates) a format-aware UBO for the given format, block name, and
     * 0-based user index. The actual binding point is {@code userIndex + CgBindingPoints.USER_START_UBO}.
     *
     * <p>The {@code name} is part of the cache key — two UBOs with different names
     * but the same format and user index are distinct resources.</p>
     *
     * @param format    typed format descriptor
     * @param name      GLSL uniform block name (e.g. {@code "LightBlock"})
     * @param userIndex 0-based user slot index (0 = first user slot)
     * @return the cached or newly-created UBO
     */
    public CgUniformBuffer getOrCreateUbo(CgBufferFormat format, String name, int userIndex) {
        return getOrCreateUbo(format, name, userIndex, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #getOrCreateUbo(CgBufferFormat, String, int)}, with the contents' {@link CgBufferLifetime}.
     *
     * <pre>{@code
     * CgUniformBuffer light = CgShaderBufferRegistry.get()
     *         .getOrCreateUbo(LIGHT_FORMAT, "LightBlock", 0, CgBufferLifetime.FRAME);
     * light.writer().reset().beginRecord().vec4("color", r, g, b, 1f);
     * light.endRecord();
     * light.upload();     // before every draw that reads it; a compare when nothing moved this frame
     * }</pre>
     */
    public CgUniformBuffer getOrCreateUbo(CgBufferFormat format, String name, int userIndex, CgBufferLifetime lifetime) {
        return ubo(format, name, CgBindingPoints.USER_START_UBO + userIndex, lifetime);
    }

    /**
     * As {@link #getOrCreateUbo(CgBufferFormat, String, int, CgBufferLifetime)} at an <strong>engine-reserved</strong>
     * slot -- a {@code CgBindingPoints} UBO constant used verbatim, as the text renderer's block is.
     */
    public CgUniformBuffer getOrCreateUboInternal(CgBufferFormat format, String name, int bindingPoint,
                                                  CgBufferLifetime lifetime) {
        return ubo(format, name, bindingPoint, lifetime);
    }

    private CgUniformBuffer ubo(CgBufferFormat format, String name, int bindingPoint, CgBufferLifetime lifetime) {
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
     * Called by {@link CgGraphicsLifecycle#destroyContext()}.
     * Must be called on the GL thread.
     */
    public void deleteAll() {
        for (CgShaderBuffer buf : shaderBufferCache.values()) {
            buf.delete();
        }
        shaderBufferCache.clear();

        for (CgUniformBuffer ubo : uboCache.values()) {
            ubo.delete();
        }
        uboCache.clear();
    }

    // ── Composite key types ───────────────────────────────────────────────────

    /**
     * Value-equal cache key for the SSBO/TBO cache. Covers both SSBO and TBO paths
     * since they share the same identity contract.
     *
     * @param name         Buffer debug/sampler name. Part of the identity contract.
     * @param format       Buffer format. Value equality.
     * @param bindingPoint GL binding point.
     */
    @Desugar
    record ShaderBufferKey(String name, CgBufferFormat format, int bindingPoint) {

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
