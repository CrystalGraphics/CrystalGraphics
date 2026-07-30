package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGlStateManager;

/**
 * Public entry point for scoped GL state save/restore.
 *
 * <pre>{@code
 * try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
 *     // GL operations here
 * }
 * }</pre>
 *
 * <p>Same API as before the V2 move, now in {@code platform} beside {@link CgGL} — deduplication happens
 * where the GL call is made, and the shadow has to live where the deduplication happens.</p>
 */
public final class CgGlState {

    private CgGlState() {}

    private static CgGlStateManager manager = new CgGlStateManager(CgGlStateProvider.glGet());

    /** The state manager. Never null — a {@code glGet}-backed one exists from class-init. */
    public static CgGlStateManager manager() {
        return manager;
    }

    /**
     * Installs a platform-specific provider, replacing the {@code glGet} default.
     *
     * <p>Each platform has a cheaper authority than the driver: Angelica's {@code GLStateManager} answers
     * every domain from public getters at no {@code glGet} cost, Blaze3D's answers roughly half.</p>
     */
    public static void setProvider(CgGlStateProvider provider) {
        manager.setProvider(provider);
    }

    /**
     * Marks every domain untrustworthy.
     *
     * <p>For the boundaries that are not scopes: frame start, render-pass entry, context creation, resource
     * reload, and any code that resets GL state wholesale with raw GL that {@link CgGL} cannot see.</p>
     */
    public static void invalidateAllIfPresent() {
        manager.invalidateAll();
    }

    /** Discards the shadow. Call on GL context destruction — a shadow describes exactly one context. */
    public static void reset() {
        manager = new CgGlStateManager(CgGlStateProvider.glGet());
    }

    /** Marks a restore point for the given domains. */
    public static CgGlScope save(CgGlSlot... slots) {
        return manager.save(slots);
    }

    /**
     * Marks a restore point around a block that hands control to <strong>foreign rendering code</strong> —
     * Minecraft's item or entity renderers, another mod's callback.
     *
     * <p>Use this, not {@link #save}, whenever GL writes happen through something other than {@link CgGL}.
     * A plain {@code save} trusts the shadow across the block and will silently deduplicate away the very
     * calls needed to undo what the foreign code did. See
     * {@link CgGlStateManager#hostForeign(CgGlSlot...)} for the full contract, including the half of the
     * problem this cannot solve (Minecraft's own state mirror goes stale from <em>our</em> writes too).</p>
     */
    public static CgGlScope hostForeign(CgGlSlot... slots) {
        return manager.hostForeign(slots);
    }

    /** Saves only the shader program domain. */
    public static CgGlScope saveProgram() {
        return save(CgGlSlot.PROGRAM);
    }

    /** Saves the four binding domains. */
    public static CgGlScope saveFull() {
        return save(CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.VERTEX_INPUT);
    }

    /** Saves all sixteen. Prefer naming what you disturb — {@code TEXTURES} is by far the costly adopt. */
    public static CgGlScope saveAll() {
        return save(CgGlSlot.values());
    }
}
