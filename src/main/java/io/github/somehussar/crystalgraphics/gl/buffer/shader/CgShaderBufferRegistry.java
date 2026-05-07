package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;

import java.util.HashMap;
import java.util.Map;

/**
 * Global registry of user-created SSBO/TBO and UBO shader buffers.
 *
 * <p>Provides lifecycle management (via {@link #deleteAll()}) and binding-point enforcement
 * for user-owned buffers. All buffers obtained through this registry must use binding points
 * {@code >= CgBindingPoints.USER_START} ({@value CgBindingPoints#USER_START}).</p>
 *
 * <p><strong>Engine-internal buffers bypass this registry</strong>: the per-object SSBO/TBO and
 * per-frame UBO owned by {@code CgMaterialPipeline} occupy engine-reserved binding points 0 and 1
 * (below {@code USER_START}). They are managed directly by {@code CgMaterialPipeline} and are
 * never inserted into this registry.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CgShaderBuffer myBuf = CgShaderBufferRegistry.get()
 *     .getOrCreate(MyFormats.PARTICLE_FORMAT, CgBindingPoints.USER_START);
 *
 * CgUniformBuffer myUbo = CgShaderBufferRegistry.get()
 *     .getOrCreateUbo(MyFormats.LIGHT_FORMAT, "LightBlock", CgBindingPoints.USER_START + 1);
 * }</pre>
 *
 * <p>All registered buffers are deleted by {@link #deleteAll()}, which is called from
 * {@link CgGraphicsLifecycle#destroyContext()}.</p>
 */
public final class CgShaderBufferRegistry {

    private static final CgShaderBufferRegistry INSTANCE = new CgShaderBufferRegistry();

    /** SSBO/TBO cache — keyed by (format, bindingPoint). */
    private final Map<SsboKey, CgShaderBuffer> ssboCache = new HashMap<>();

    /** UBO cache — keyed by (format, bindingPoint, blockName). */
    private final Map<UboKey, CgUniformBuffer> uboCache = new HashMap<>();

    private CgShaderBufferRegistry() {}

    /** Returns the global singleton registry. */
    public static CgShaderBufferRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns (or lazily creates) a format-aware SSBO/TBO for the given format and binding point.
     * The buffer starts at capacity 1 and auto-grows on {@link CgShaderBuffer#beginWrite(int)}.
     *
     * @param format          typed format descriptor for the buffer records
     * @param bindingPoint    binding slot; must be {@code >= CgBindingPoints.USER_START}
     * @return the cached or newly-created shader buffer
     * @throws IllegalArgumentException if {@code bindingPoint < CgBindingPoints.USER_START}
     */
    public CgShaderBuffer getOrCreate(CgBufferFormat format, int bindingPoint) {
        CgBindingPoints.validateBindingPoint(bindingPoint);
        SsboKey key = new SsboKey(format, bindingPoint);
        CgShaderBuffer existing = ssboCache.get(key);
        if (existing != null) return existing;
        CgShaderBuffer buf = CgShaderBuffer.create(format, bindingPoint);
        ssboCache.put(key, buf);
        return buf;
    }

    /**
     * Returns (or lazily creates) a format-aware UBO for the given format, block name, and binding point.
     *
     * <p>The {@code blockName} is part of the cache key — two UBOs with different block names
     * but the same format and binding point are distinct resources.</p>
     *
     * @param format          typed format descriptor
     * @param blockName       GLSL uniform block name (e.g. {@code "LightBlock"})
     * @param bindingPoint    binding slot; must be {@code >= CgBindingPoints.USER_START}
     * @return the cached or newly-created UBO
     * @throws IllegalArgumentException if {@code bindingPoint < CgBindingPoints.USER_START}
     */
    public CgUniformBuffer getOrCreateUbo(CgBufferFormat format, String blockName, int bindingPoint) {
        CgBindingPoints.validateBindingPoint(bindingPoint);
        UboKey key = new UboKey(format, bindingPoint, blockName);
        CgUniformBuffer existing = uboCache.get(key);
        if (existing != null) return existing;
        CgUniformBuffer ubo = CgUniformBuffer.create(format, blockName, bindingPoint);
        uboCache.put(key, ubo);
        return ubo;
    }

    /**
     * Deletes all registered buffers and clears both caches.
     * Called by {@link CgGraphicsLifecycle#destroyContext()}.
     * Must be called on the GL thread.
     */
    public void deleteAll() {
        for (CgShaderBuffer buf : ssboCache.values()) {
            buf.delete();
        }
        ssboCache.clear();

        for (CgUniformBuffer ubo : uboCache.values()) {
            ubo.delete();
        }
        uboCache.clear();
    }

    // ── Composite key types ───────────────────────────────────────────────────

    /**
     * Value-equal cache key for the SSBO/TBO cache.
     *
     * @param format       Buffer format. Value equality.
     * @param bindingPoint GL binding point.
     */
    @Desugar
    record SsboKey(CgBufferFormat format, int bindingPoint) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SsboKey)) return false;
            SsboKey other = (SsboKey) o;
            return bindingPoint == other.bindingPoint && format.equals(other.format);
        }

        @Override
        public int hashCode() {
            return 31 * format.hashCode() + bindingPoint;
        }
    }

    /**
     * Value-equal cache key for the UBO cache. Includes the block name because two UBOs
     * with different block names but the same format and binding point are distinct resources.
     *
     * @param format       Buffer format. Value equality.
     * @param bindingPoint GL binding point.
     * @param blockName    GLSL uniform block name.
     */
    @Desugar
    record UboKey(CgBufferFormat format, int bindingPoint, String blockName) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UboKey)) return false;
            UboKey other = (UboKey) o;
            return bindingPoint == other.bindingPoint
                    && format.equals(other.format)
                    && blockName.equals(other.blockName);
        }

        @Override
        public int hashCode() {
            int h = format.hashCode();
            h = 31 * h + bindingPoint;
            h = 31 * h + blockName.hashCode();
            return h;
        }
    }
}
