package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGlStateManager;

/**
 * Supplies the authoritative current value of a GL state domain — the one place truth enters
 * {@link CgGlStateManager}.
 *
 * <p>The shadow cannot be derived by watching other code run: process-wide interception is not omniscient,
 * as Angelica's transformer redirecting ours into its own demonstrates. So at each scope boundary the
 * platform pours its own view in, and each platform has a different best source.</p>
 *
 * <table>
 *   <tr><th>Platform</th><th>Source</th><th>{@code glGet} cost</th></tr>
 *   <tr><td>1.7.10 + Angelica</td><td>Angelica's {@code GLStateManager} (public getters); its {@code glGet}
 *       is answered from the same cache</td><td>none</td></tr>
 *   <tr><td>every other host, and the harness</td><td>{@code glGet}</td><td>full</td></tr>
 * </table>
 *
 * <h3>Fills the shadow rather than returning a value</h3>
 * <p>{@link #read} writes into {@code target} instead of returning an object. With a flat shadow there is
 * no value type to return, and a platform that can answer only part of a domain — Angelica tracks blend
 * factors but not the equation — can fill what it knows and leave the rest to
 * {@link #readByGlGet}.</p>
 *
 * <h3>Adoption is total</h3>
 * <p>Implementations must leave every field of the named domain populated. A domain left unset has no valid
 * restore baseline, so a scope could neither restore it nor safely leave it alone. Since {@code glGet} is
 * always available as a last resort, no platform genuinely cannot answer — which is why the default
 * implementation below is the fallback rather than an error.</p>
 */
public interface CgGlStateProvider {

    /**
     * Populates {@code target}'s fields for {@code slot} with this platform's authoritative view.
     *
     * @param slot   the domain to read
     * @param target the shadow to fill; only {@code slot}'s fields may be written
     */
    void read(CgGlSlot slot, CgGlStateShadow target);

    /**
     * One binding point of a domain captured at first write ({@link CgGlSlot#STORAGE_BUFFERS}, {@link CgGlSlot#IMAGES},
     * {@link CgGlSlot#INDIRECT_BUFFERS}): what a scope saves before our first write of it. {@code glGet} by default.
     *
     * @param index the storage binding point, the image unit, or the {@link CgGlStateShadow#INDIRECT_TARGETS} index
     */
    default void readBinding(CgGlSlot slot, int index, CgGlStateShadow target) {
        glGet().readBinding(slot, index, target);
    }

    /**
     * The active texture unit and the {@code GL_TEXTURE_2D} binding of each unit whose bit {@code units} sets; answers
     * the units it filled. Every unit by default.
     *
     * <pre>{@code
     * int filled = provider.readTextureUnits(shadow, 1 << 3 | 1 << 0);   // the active unit, units 0 and 3
     * }</pre>
     */
    default int readTextureUnits(CgGlStateShadow t, int units) {
        read(CgGlSlot.TEXTURES, t);
        return -1;
    }

    /**
     * The texture units the host samples through while it has the context, as a mask; every unit by default. A scope
     * neither reads nor restores a unit outside it: only our code binds there, and always explicitly.
     *
     * <pre>{@code
     * @Override public int hostUnits() { return shaderPackActive() ? -1 : (1 << 12) - 1; }   // Blaze3D's twelve
     * }</pre>
     */
    default int hostUnits() {
        return -1;
    }

    /**
     * Whether the host binds {@code slot}'s points itself while it has the context. Asked only of the domains
     * captured at first write: one the host never binds keeps the shadow across host sections, so a scope saves
     * it with no read. No by default: vanilla Minecraft binds no storage buffer, image unit or indirect buffer on
     * any version. A host running a shader pack's loader answers yes.
     */
    default boolean hostBinds(CgGlSlot slot) {
        return false;
    }

    /**
     * Whether the host writes GL only through {@link CgGL}, so the shadow stays true
     * between host sections: a host boundary then forgets nothing, and an outermost scope reads only what it does
     * not know. Foreign drawing inside {@code hostForeign} is still forgotten. No by default; Minecraft never.
     *
     * <pre>{@code
     * CgGlState.setProvider(new CgGlGetProvider() {
     *     @Override public boolean hostKeepsShadow() { return true; }   // the harness: every draw is CgGL's
     * });
     * }</pre>
     */
    default boolean hostKeepsShadow() {
        return false;
    }

    /**
     * Whether a read costs no driver round trip -- a host cache answers every one.
     *
     * <p>A nested scope then re-reads instead of trusting the shadow, which is free and catches a host
     * that rebinds inside our scope through its own manager, where {@code CgGL} cannot see it. Angelica
     * does: it binds framebuffers internally, and a nested scope that trusted the shadow restored ours.</p>
     */
    default boolean isFree() {
        return false;
    }

    /** The universal fallback: every domain read from the driver. */
    static CgGlStateProvider glGet() {
        return GlGetHolder.INSTANCE;
    }

    /** Lazy holder so the default provider is not constructed before a GL context exists. */
    final class GlGetHolder {
        static final CgGlGetProvider INSTANCE = new CgGlGetProvider();
        private GlGetHolder() {}
    }
}
