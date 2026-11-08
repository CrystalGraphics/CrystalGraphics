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
     * Copies into {@code answer} the fields of {@code slot} this provider guesses rather than reads, from
     * {@code truth}: fields the host sets before each use, so a scope restoring the guess is harmless. A comparison
     * against the driver (the cache's own checks, {@code state.roundTrip}) then skips them. Nothing by default.
     *
     * <pre>{@code
     * @Override public void excuse(CgGlSlot slot, CgGlStateShadow answer, CgGlStateShadow truth) {
     *     if (slot == CgGlSlot.SCISSOR) answer.scissorW = truth.scissorW;   // ...and the rest of the box
     * }
     * }</pre>
     */
    default void excuse(CgGlSlot slot, CgGlStateShadow answer, CgGlStateShadow truth) {}

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
     * Whether the host keeps a cache of each unit's texture binding that skips a bind it believes made (Blaze3D's
     * {@code GlStateManager}, Angelica's): a GL device's section, which binds behind {@code CgGL}, then reads the host's
     * units as it opens so it can set them back. A host with none rebinds before it samples. {@link #isFree()} by default.
     */
    default boolean hostCachesTextures() {
        return isFree();
    }

    /**
     * The domains the host's next draw relies on finding as it left them, as {@code 1 << slot.ordinal()} bits: those its
     * own cache skips a call in when it believes the value set, and those it sets once and assumes (Minecraft's
     * fixed-function program 0). Each host section holds a scope over them, so whatever the section changed there is set
     * back to the host's belief at {@code CgGL.toHost()}. None by default: a host with no cache (or one, like Angelica's,
     * that sees our calls) needs nothing.
     *
     * <pre>{@code
     * @Override public int sectionSlots() { return bit(CgGlSlot.BLEND) | bit(CgGlSlot.DEPTH) | bit(CgGlSlot.TEXTURES); }
     * }</pre>
     *
     * <ul>
     *   <li>A domain here is read at the section's open: from the host's cache where it holds it, else {@code glGet}.</li>
     *   <li>A cached domain the shadow does not model (Minecraft's clear colour) cannot be set back this way; the
     *       host's backend routes that call through its cache instead.</li>
     * </ul>
     */
    default int sectionSlots() {
        return 0;
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
