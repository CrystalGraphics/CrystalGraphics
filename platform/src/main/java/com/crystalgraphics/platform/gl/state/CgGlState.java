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
     * <p>For a host with a cheaper authority than the driver. Only 1.7.10 installs one, over Angelica's
     * {@code GLStateManager}, which answers most domains from public getters at no {@code glGet} cost.</p>
     */
    public static void setProvider(CgGlStateProvider provider) {
        manager.setProvider(provider);
    }

    /**
     * A way to the driver past the host's cache, for {@code -Dcrystalgraphics.state.roundTrip}.
     * Only a host whose {@code glGet} a cache answers needs one. @see CgGlStateManager#setDriverReader
     */
    public static void setDriverReader(CgGlStateProvider reader, CgGlSlot... virtualised) {
        manager.setDriverReader(reader, virtualised);
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

    /**
     * Runs foreign drawing inside a {@link #hostForeign(CgGlSlot...)} scope — the form that survives a
     * {@code CgGlRecording}, which records {@code body} and runs it on replay in order.
     *
     * <pre>{@code
     * CgGlState.hostForeign(() -> itemRenderer.render(stack), CgGlSlot.BLEND, CgGlSlot.DEPTH, CgGlSlot.PROGRAM);
     * }</pre>
     */
    public static void hostForeign(Runnable body, CgGlSlot... slots) {
        manager.hostForeign(body, slots);
    }

    /**
     * Declares writes meant to stay, for the host to draw with; closing restores nothing.
     * @see CgGlStateManager#handOver
     */
    public static CgGlScope handOver(CgGlSlot... slots) {
        return manager.handOver(slots);
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
