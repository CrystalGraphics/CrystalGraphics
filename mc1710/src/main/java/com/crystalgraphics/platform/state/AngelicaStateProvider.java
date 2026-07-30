package com.crystalgraphics.platform.state;

import com.crystalgraphics.platform.gl.state.CgGlGetProvider;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;

import java.lang.reflect.Method;

/**
 * Reads GL state from Angelica's {@code GLStateManager} instead of the driver.
 *
 * <p>Angelica redirects roughly two hundred GL call sites process-wide, so its mirror sees writes from
 * Minecraft and from every other mod — coverage CrystalGraphics could never obtain for itself, and the
 * reason our own redirector was abandoned rather than extended. Where Angelica is present it is a strictly
 * better authority than {@code glGet}, and free: every value below is a plain field read.</p>
 *
 * <h3>Reflection, deliberately</h3>
 * <p>Angelica is an optional mod and is not on this module's compile classpath, so it cannot be referenced
 * directly. Handles resolve once and cache; <strong>any failure falls through to
 * {@link CgGlGetProvider}'s driver read</strong>. A mapping that breaks against a future Angelica therefore
 * costs performance, never correctness — which is the whole reason to prefer reflection over a hard
 * dependency here, and why this is not a Mixin accessor.</p>
 *
 * <h3>Two traps, both found by reading Angelica's source rather than assuming</h3>
 * <ul>
 *   <li><strong>{@code DepthState.enabled} is the depth WRITE MASK, not the depth test.</strong>
 *       {@code GLStateManager.glDepthMask(mask)} stores into {@code depthState.setEnabled(mask)}, while the
 *       depth <em>test</em> lives in a separate {@code depthTest} stack. Mapping the obvious-looking name
 *       onto the test would silently invert depth behaviour. Note this is the <em>opposite</em> of
 *       Blaze3D's convention — do not carry either workaround across.</li>
 *   <li><strong>Angelica's {@code BlendState} carries no blend equation</strong>, exactly like Blaze3D's.
 *       The equation is left to the driver read rather than invented.</li>
 * </ul>
 */
public final class AngelicaStateProvider extends CgGlGetProvider {

    private static final String GLSM = "com.gtnewhorizons.angelica.glsm.GLStateManager";

    /** {@code null} disables every override and leaves the {@code glGet} base entirely in charge. */
    private static final Class<?> STATE_MANAGER = resolve();

    private static Class<?> resolve() {
        try {
            return Class.forName(GLSM);
        } catch (Throwable notPresent) {
            return null;
        }
    }

    /** Whether Angelica's state manager is on the classpath and usable. */
    public static boolean isAvailable() {
        return STATE_MANAGER != null;
    }

    private static final Method BLEND_STATE = getter("getBlendState");
    private static final Method BLEND_MODE  = getter("getBlendMode");
    private static final Method DEPTH_STATE = getter("getDepthState");
    private static final Method DEPTH_TEST  = getter("getDepthTest");
    private static final Method CULL_STATE  = getter("getCullState");
    private static final Method ALPHA_STATE = getter("getAlphaState");
    private static final Method ALPHA_TEST  = getter("getAlphaTest");
    private static final Method COLOR_MASK  = getter("getColorMask");
    private static final Method VIEWPORT    = getter("getViewportState");

    private static Method getter(String name) {
        if (STATE_MANAGER == null) return null;
        try {
            Method m = STATE_MANAGER.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable missing) {
            return null;
        }
    }

    // ── Overrides ─────────────────────────────────────────────────────────────

    @Override
    protected void readBlend(CgGlStateShadow t) {
        Object state = call(BLEND_STATE);
        Object mode  = call(BLEND_MODE);
        if (state == null || mode == null) { super.readBlend(t); return; }
        try {
            t.blendEnabled  = (Boolean) invoke(mode,  "isEnabled");
            t.blendSrcRgb   = (Integer) invoke(state, "getSrcRgb");
            t.blendDstRgb   = (Integer) invoke(state, "getDstRgb");
            t.blendSrcAlpha = (Integer) invoke(state, "getSrcAlpha");
            t.blendDstAlpha = (Integer) invoke(state, "getDstAlpha");
            // Angelica does not track the equation; take just those two enums from the driver.
            t.blendEqRgb   = com.crystalgraphics.platform.gl.CgGL.glGetInteger(
                    com.crystalgraphics.platform.gl.CgGL.GL_BLEND_EQUATION_RGB);
            t.blendEqAlpha = com.crystalgraphics.platform.gl.CgGL.glGetInteger(
                    com.crystalgraphics.platform.gl.CgGL.GL_BLEND_EQUATION_ALPHA);
        } catch (Throwable mismatch) {
            super.readBlend(t);
        }
    }

    @Override
    protected void readDepth(CgGlStateShadow t) {
        Object state = call(DEPTH_STATE);
        Object test  = call(DEPTH_TEST);
        if (state == null || test == null) { super.readDepth(t); return; }
        try {
            // NOT a typo: depthState.enabled is the WRITE MASK; the TEST is the separate depthTest stack.
            t.depthMask = (Boolean) invoke(state, "isEnabled");
            t.depthFunc = (Integer) invoke(state, "getFunc");
            t.depthTest = (Boolean) invoke(test,  "isEnabled");
        } catch (Throwable mismatch) {
            super.readDepth(t);
        }
    }

    @Override
    protected void readCull(CgGlStateShadow t) {
        Object cull = call(CULL_STATE);
        if (cull == null) { super.readCull(t); return; }
        try {
            boolean enabled = (Boolean) invoke(cull, "isEnabled");
            // Angelica's cullState is enable-only; face and winding still come from the driver.
            super.readCull(t);
            t.cullEnabled = enabled;
        } catch (Throwable mismatch) {
            super.readCull(t);
        }
    }

    @Override
    protected void readAlpha(CgGlStateShadow t) {
        Object state = call(ALPHA_STATE);
        Object test  = call(ALPHA_TEST);
        if (state == null || test == null) { super.readAlpha(t); return; }
        try {
            t.alphaTest = (Boolean) invoke(test,  "isEnabled");
            t.alphaFunc = (Integer) invoke(state, "getFunction");
            t.alphaRef  = (Float)   invoke(state, "getReference");
        } catch (Throwable mismatch) {
            super.readAlpha(t);
        }
    }

    @Override
    protected void readColorMask(CgGlStateShadow t) {
        Object mask = call(COLOR_MASK);
        if (mask == null) { super.readColorMask(t); return; }
        try {
            // ColorMask exposes plain public fields rather than generated getters.
            int nibble = (field(mask, "red")   ? 1 : 0) | (field(mask, "green") ? 2 : 0)
                       | (field(mask, "blue")  ? 4 : 0) | (field(mask, "alpha") ? 8 : 0);
            int packed = 0;
            for (int i = 0; i < 8; i++) packed |= nibble << (i * 4);
            t.colorMaskPacked = packed;
        } catch (Throwable mismatch) {
            super.readColorMask(t);
        }
    }

    @Override
    protected void readViewport(CgGlStateShadow t) {
        Object vp = call(VIEWPORT);
        if (vp == null) { super.readViewport(t); return; }
        try {
            t.viewportX = intField(vp, "x");     t.viewportY = intField(vp, "y");
            t.viewportW = intField(vp, "width"); t.viewportH = intField(vp, "height");
        } catch (Throwable mismatch) {
            super.readViewport(t);
        }
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Object call(Method m) {
        if (m == null) return null;
        try {
            return m.invoke(null);
        } catch (Throwable failed) {
            return null;
        }
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static boolean field(Object target, String name) throws Exception {
        return target.getClass().getField(name).getBoolean(target);
    }

    private static int intField(Object target, String name) throws Exception {
        return target.getClass().getField(name).getInt(target);
    }
}
