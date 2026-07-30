package com.crystalgraphics.platform.gl.state;

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
 *   <tr><td>1.7.10 + Angelica</td><td>Angelica's {@code GLStateManager} (public getters)</td><td>none</td></tr>
 *   <tr><td>1.20.x</td><td>Blaze3D {@code GlStateManager} — <em>partial</em>, cached reflection</td><td>residual</td></tr>
 *   <tr><td>vanilla, harness</td><td>batched {@code glGet}</td><td>full</td></tr>
 * </table>
 *
 * <h3>Fills the shadow rather than returning a value</h3>
 * <p>{@link #read} writes into {@code target} instead of returning an object. With a flat shadow there is
 * no value type to return, and a platform that can answer only part of a domain — Blaze3D tracks blend
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
