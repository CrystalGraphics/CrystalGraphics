package com.crystalgraphics.gl.buffer.shader;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.CgCapabilities;
import com.crystalgraphics.api.buffer.CgBufferFormat;
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
 * <p><strong>Engine-internal buffers bypass this registry</strong>: the per-object SSBO/TBO and
 * per-frame UBO owned by {@code CgMaterialPipeline} occupy engine-reserved binding points 0 and 1
 * (below {@code USER_START}). They are managed directly by {@code CgMaterialPipeline} and are
 * never inserted into this registry.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // userIndex 0 = first user slot (binding point CgBindingPoints.USER_START)
 * CgShaderBuffer myBuf = CgShaderBufferRegistry.get()
 *     .getOrCreate("myData", MyFormats.PARTICLE_FORMAT, 0);
 *
 * CgUniformBuffer myUbo = CgShaderBufferRegistry.get()
 *     .getOrCreateUbo(MyFormats.LIGHT_FORMAT, "LightBlock", 1);
 * }</pre>
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
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        int binding = (path == CgCapabilities.ShaderBufferPath.TBO)
                ? CgBindingPoints.USER_START_TBO + userIndex
                : CgBindingPoints.USER_START_SSBO + userIndex;
        ShaderBufferKey key = new ShaderBufferKey(name, format, binding);
        CgShaderBuffer existing = shaderBufferCache.get(key);
        if (existing != null) return existing;
        CgShaderBuffer buf = CgShaderBuffer.create(name, format, userIndex);
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
        int binding = CgBindingPoints.USER_START_UBO + userIndex;
        ShaderBufferKey key = new ShaderBufferKey(name, format, binding);
        CgUniformBuffer existing = uboCache.get(key);
        if (existing != null) return existing;
        CgUniformBuffer ubo = CgUniformBuffer.create(format, name, userIndex);
        uboCache.put(key, ubo);
        return ubo;
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
