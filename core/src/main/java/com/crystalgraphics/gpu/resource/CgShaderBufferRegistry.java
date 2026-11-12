package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

/**
 * The engine's shared shader buffers, one per name, layout and slot, deleted together at shutdown
 * ({@link #deleteAll()}). A consumer's buffer is {@code CgBuffer} ({@code CgBuffers.create}).
 *
 * <pre>{@code
 * // rewritten every frame, before the draws that read it: the frame ring
 * CgShaderBuffer shapes = CgShaderBufferRegistry.get()
 *     .getOrCreateInternal("CgShapeTable", FORMAT, CgBindingModel.token("shape"), CgBufferLifetime.FRAME);
 * }</pre>
 *
 * <ul>
 *   <li>A name, format and slot asked for again returns the same buffer, and must ask for the same
 *       {@link CgBufferLifetime}: two lifetimes at one slot is a conflict, and throws.</li>
 *   <li>{@link #ownerOf} answers the buffer made at a slot: what a pass binds there when no draw names its own.</li>
 *   <li>All registered buffers are deleted by {@link #deleteAll()}, from {@code CgEngineLifecycle.shutdown()}.</li>
 * </ul>
 */
public final class CgShaderBufferRegistry {

    private static final CgShaderBufferRegistry INSTANCE = new CgShaderBufferRegistry();

    private final Map<ShaderBufferKey, CgShaderBuffer> shaderBufferCache = new HashMap<>();
    private final Map<ShaderBufferKey, CgUniformBuffer> uboCache = new HashMap<>();

    /** Each slot's engine buffer, made by {@link #getOrCreateInternal}; the same by {@link #ownerAt}'s index. */
    private final Map<CgBindingModel.Slot, CgShaderBuffer> owners = new HashMap<>();
    private CgShaderBuffer[] ownersAt = new CgShaderBuffer[4 * 128];

    private CgShaderBufferRegistry() {}

    /** Returns the global singleton registry. */
    public static CgShaderBufferRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns (or lazily creates) a storage buffer its shaders read at {@code slot}, its contents kept until the next
     * upload ({@link CgBufferLifetime#RETAINED}).
     *
     * <p><strong>Engine-internal.</strong> One slot per engine subsystem: never share one across unrelated owners.</p>
     */
    public CgShaderBuffer getOrCreateInternal(String name, CgBufferLayout format, CgBindingModel.Slot slot) {
        return getOrCreateInternal(name, format, slot, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #getOrCreateInternal(String, CgBufferLayout, CgBindingModel.Slot)}, with the contents'
     * {@link CgBufferLifetime}. What the quad and curve renderers' instance data use.
     *
     * <pre>{@code
     * CgShaderBuffer instances = CgShaderBufferRegistry.get()
     *         .getOrCreateInternal("QuadInstances", FORMAT, CgBindingModel.token("quad"), CgBufferLifetime.FRAME);
     * instances.uploadRaw(data, floats);   // the next pass binds the new range
     * }</pre>
     */
    public CgShaderBuffer getOrCreateInternal(String name, CgBufferLayout format, CgBindingModel.Slot slot,
                                              CgBufferLifetime lifetime) {
        ShaderBufferKey key = new ShaderBufferKey(name, format, slot);
        CgShaderBuffer existing = shaderBufferCache.get(key);
        if (existing != null) return sameLifetime(existing, lifetime);
        CgShaderBuffer buf = CgShaderBuffer.createInternal(name, format, slot, lifetime);
        shaderBufferCache.put(key, buf);
        own(slot, buf);
        return buf;
    }

    /**
     * The engine buffer made at {@code slot} by {@link #getOrCreateInternal}, or null if none has been: what a pass
     * binds there for the draws that name none of their own.
     *
     * <pre>{@code
     * CgShaderBuffer frames = CgShaderBufferRegistry.get().ownerOf(CgParticleBuffer.SLOT);
     * }</pre>
     */
    @Nullable
    public CgShaderBuffer ownerOf(CgBindingModel.Slot slot) {
        return owners.get(slot);
    }

    /**
     * {@link #ownerOf} by a packed slot ({@link CgBindingModel#pack}), allocating nothing: what a set reads at a slot
     * no pass parameter and no snapshot fills.
     */
    @Nullable
    public CgShaderBuffer ownerAt(int packed) {
        int i = CgBindingModel.setOf(packed) * 128 + CgBindingModel.bindingOf(packed);
        return i < ownersAt.length ? ownersAt[i] : null;
    }

    private void own(CgBindingModel.Slot slot, CgShaderBuffer buffer) {
        owners.put(slot, buffer);
        int i = slot.set() * 128 + slot.binding();
        if (i >= ownersAt.length) ownersAt = Arrays.copyOf(ownersAt, i * 2);
        ownersAt[i] = buffer;
    }

    /** A uniform block its shaders read at {@code slot}, as the text renderer's block is. */
    public CgUniformBuffer getOrCreateUboInternal(CgBufferLayout format, String name, CgBindingModel.Slot slot,
                                                  CgBufferLifetime lifetime) {
        ShaderBufferKey key = new ShaderBufferKey(name, format, slot);
        CgUniformBuffer existing = uboCache.get(key);
        if (existing != null) return sameLifetime(existing, lifetime);
        CgUniformBuffer ubo = new CgUniformBuffer(name, format, slot, lifetime);
        uboCache.put(key, ubo);
        own(slot, ubo);
        return ubo;
    }

    // One slot, one lifetime: a second caller asking for the other would read the first's storage wrongly.
    private static <B extends CgShaderBuffer> B sameLifetime(B existing, CgBufferLifetime asked) {
        if (existing.getLifetime() != asked) {
            throw new IllegalStateException("Shader buffer '" + existing.getName() + "' already exists as "
                    + existing.getLifetime() + ", asked for " + asked);
        }
        return existing;
    }

    /**
     * Deletes all registered buffers and clears both caches. Called by {@code CgEngineLifecycle.shutdown()}, on the
     * render thread.
     */
    public void deleteAll() {
        for (CgShaderBuffer buf : shaderBufferCache.values()) buf.delete();
        shaderBufferCache.clear();
        owners.clear();
        Arrays.fill(ownersAt, null);
        for (CgUniformBuffer ubo : uboCache.values()) ubo.delete();
        uboCache.clear();
    }

    /** A buffer's identity in the caches: its name, layout (by value) and slot. */
    @Desugar
    record ShaderBufferKey(String name, CgBufferLayout format, CgBindingModel.Slot slot) {}
}
