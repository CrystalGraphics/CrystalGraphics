package io.github.somehussar.crystalgraphics.gl.state;

import io.github.somehussar.crystalgraphics.api.state.CgGlSlot;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

import java.lang.reflect.Method;

/**
 * Thin static dispatcher for capturing and restoring GL state as {@link CgGlScope}s.
 *
 * <p>This class replaces {@code CgStateBoundary}. It resolves the mirror-vs-glGet trust
 * decision once per save call and delegates capture to the appropriate inner class in
 * {@link CgGlStates}. No GL calls are made in this class itself — all GL access is inside
 * the inner classes of {@link CgGlStates}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
 *     // GL operations here
 * }
 * // captured slots restored
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Issues GL calls; must only be used on the render thread that owns the GL context.</p>
 *
 * @see CgGlSlot
 * @see CgGlScope
 * @see CgGlStates
 */
public final class CgGlState {
    
    private CgGlState() {}

    /**
     * Captures the specified GL state slots and returns a scope that restores them on close.
     *
     * <p>Only the listed slots are queried; all others are left uncaptured.
     * If no slots are given, returns a no-op scope (zero GL calls).</p>
     *
     * @param slots the GL state domains to capture
     * @return a scope that restores the captured state; never {@code null}
     */
    public static CgGlScope save(CgGlSlot... slots) {
        SlotState[] states = new SlotState[CgGlSlot.values().length];
        if (slots == null || slots.length == 0) return new CgGlScope(states);

        // Resolve mirror-vs-glGet trust ONCE before any capture.
        // Passed only to FboState and ProgramState — the two slots that can use the mirror.
        boolean forceGlGet  = Boolean.getBoolean("crystalgraphics.boundary.forceGlGet");
        boolean gapOnlyMode = isGapOnlyModeSafe();
        boolean mirrorOkFbo = GLStateMirror.getCurrentFboFamily() != CallFamily.UNKNOWN;
        boolean mirrorOkPrg = GLStateMirror.getCurrentProgramFamily() != CallFamily.UNKNOWN
                              || GLStateMirror.getProgramId() == 0;
        boolean useGlGet    = forceGlGet || gapOnlyMode || !mirrorOkFbo || !mirrorOkPrg;

        ContextCapabilities caps = GLContext.getCapabilities();

        for (CgGlSlot slot : slots) {
            states[slot.ordinal()] = capture(slot, caps, useGlGet);
        }
        return new CgGlScope(states);
    }

    private static SlotState capture(CgGlSlot slot, ContextCapabilities caps, boolean useGlGet) {
        switch (slot) {
            case FBO:            return CgGlStates.FboState.capture(caps, useGlGet);
            case PROGRAM:        return CgGlStates.ProgramState.capture(caps, useGlGet);
            case TEXTURES:       return CgGlStates.TextureState.capture(caps);
            case VERTEX_INPUT:   return CgGlStates.VertexState.capture(caps);
            case BLEND:          return CgGlStates.BlendState.capture(caps);
            case DEPTH:          return CgGlStates.DepthState.capture();
            case CULL:           return CgGlStates.CullState.capture();
            case STENCIL:        return CgGlStates.StencilState.capture();
            case COLOR_MASK:     return CgGlStates.ColorMaskState.capture();
            case VIEWPORT:       return CgGlStates.ViewportState.capture();
            case SCISSOR:        return CgGlStates.ScissorState.capture();
            case POLYGON_OFFSET: return CgGlStates.PolygonOffsetState.capture();
            case ALPHA_TEST:     return CgGlStates.AlphaTestState.capture();
            case LINE_WIDTH:     return CgGlStates.LineWidthState.capture();
            case POLYGON_MODE:   return CgGlStates.PolygonModeState.capture();
            case POINT_SIZE:     return CgGlStates.PointSizeState.capture();
            default: throw new IllegalArgumentException("Unknown CgGlSlot: " + slot);
        }
    }

    /**
     * Saves only the shader program slot.
     * Convenience shorthand for {@code save(CgGlSlot.PROGRAM)}.
     */
    public static CgGlScope saveProgram() {
        return save(CgGlSlot.PROGRAM);
    }

    /**
     * Saves FBO, program, textures, and vertex input — the four binding slots.
     * Convenience shorthand for {@code save(FBO, PROGRAM, TEXTURES, VERTEX_INPUT)}.
     */
    public static CgGlScope saveFull() {
        return save(CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.VERTEX_INPUT);
    }

    /**
     * Saves all 16 GL state slots.
     *
     * <p><strong>Performance note</strong>: issues many {@code glGet} calls.
     * Prefer saving only the slots you intend to modify.</p>
     */
    public static CgGlScope saveAll() {
        return save(CgGlSlot.values());
    }

    private static Class<?> coreMod;
    private static Method gapOnlyMode;
    private static boolean isGapOnlyModeSafe() {
        try {
            if(coreMod == null) coreMod= Class.forName("io.github.somehussar.crystalgraphics.mc.coremod.CrystalGraphicsCoremod");
            if(gapOnlyMode == null) coreMod.getMethod("isGapOnlyMode");
            
            Object v = gapOnlyMode.invoke(null);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
