package com.crystalgraphics.mc.platform.state;

import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMaskState;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgPolygonOffsetState;
import com.crystalgraphics.api.state.CgScissorState;
import com.crystalgraphics.api.state.CgStateGroup;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.api.state.CgViewportState;
import com.crystalgraphics.gl.state.CgGlGetStateProvider;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.platform.gl.CgGL;
import com.mojang.blaze3d.platform.GlStateManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;

/**
 * Reads GL state from Minecraft's Blaze3D {@code GlStateManager} instead of the driver.
 *
 * <p>Every {@code glGet} is a driver synchronisation point. Blaze3D already maintains a CPU-side mirror of
 * much of the same state, so where it can answer, asking it is free.</p>
 *
 * <h3>⚠️ Written but never compiled or run</h3>
 * <p>{@code mc1201} is <strong>not in {@code settings.gradle.kts}</strong> — the build includes only
 * {@code platform}, {@code core}, {@code mc1710} and the native bindings. This file has therefore never
 * been through a compiler. Every field name below was verified by reading the extracted 1.20.1 and 1.20.4
 * sources, and the structure is deliberately fail-soft, but treat it as unproven until the module is wired
 * back in.</p>
 *
 * <h3>Blaze3D is a PARTIAL mirror — this is the important part</h3>
 * <p>It is tempting to assume MC tracks everything. It does not, and the gaps are not obvious:</p>
 *
 * <table>
 *   <tr><th>Domain</th><th>Blaze3D</th><th>Consequence here</th></tr>
 *   <tr><td>{@code VIEWPORT}</td><td>✅ public enum + public static getters</td><td>free, no reflection</td></tr>
 *   <tr><td>{@code DEPTH}</td><td>✅ mode / mask / func</td><td>fully answered</td></tr>
 *   <tr><td>{@code STENCIL}</td><td>✅ func / ref / mask / fail / zfail / zpass</td><td>fully answered</td></tr>
 *   <tr><td>{@code COLOR_MASK}</td><td>✅ red / green / blue / alpha</td><td>fully answered</td></tr>
 *   <tr><td>{@code BLEND}</td><td>⚠️ no blend equation</td><td>equation from {@code glGet}</td></tr>
 *   <tr><td>{@code CULL}</td><td>⚠️ no front face</td><td>winding from {@code glGet}</td></tr>
 *   <tr><td>{@code SCISSOR}</td><td>⚠️ enable flag only, no box</td><td>box from {@code glGet}</td></tr>
 *   <tr><td>{@code POLYGON_OFFSET}</td><td>⚠️ fill + line, no point</td><td>point from {@code glGet}</td></tr>
 *   <tr><td>{@code TEXTURES}</td><td>⚠️ first {@code TEXTURE_COUNT} (12) units only</td><td>see below</td></tr>
 *   <tr><td>{@code PROGRAM} {@code FBO} {@code VERTEX_INPUT} {@code POLYGON_MODE} {@code LINE_WIDTH} {@code POINT_SIZE}</td><td>❌ pass-through</td><td>entirely from {@code glGet}</td></tr>
 * </table>
 *
 * <p><strong>{@code TEXTURES} is deliberately NOT overridden.</strong> Blaze3D tracks only its first twelve
 * units, while {@code CgBindingPoints} allocates CrystalGraphics' own units counting <em>downward</em> from
 * the hardware maximum. Answering from Blaze3D would therefore report {@code 0} for exactly the units
 * CrystalGraphics uses, and since a scope restores what it adopted, that would <strong>unbind our own
 * textures</strong>. The full {@code glGet} sweep is correct; a partial answer here is worse than none.</p>
 *
 * <h3>Reflection, and why the failure mode is safe</h3>
 * <p>All nine holder fields are {@code private static final}, the nested classes are package-private (so
 * their {@code public} fields cannot be named from here), and {@code BooleanState.enabled} is private with
 * no getter. Iris solves this with a Mixin accessor; reflection is preferred instead because a miss can be
 * caught and <strong>fall through to {@code glGet}</strong>. A Mixin {@code @Accessor} against a renamed
 * field is a hard crash at class-load. Spanning 1.20.1 and 1.20.4, that resilience is worth more than the
 * nanoseconds.</p>
 *
 * <p>Field names are identical across 1.20.1 Forge, 1.20.1 Fabric and 1.20.4 NeoForge, and
 * {@code com.mojang.blaze3d} is never remapped — so one implementation covers all three loaders with no
 * mapping dependency.</p>
 *
 * <h3>Iris / Oculus — handled, and narrower than feared</h3>
 * <p>Verified against Iris 1.20.1 ({@code research_repos/iris}). Iris injects at {@code @At("HEAD")} with
 * {@code ci.cancel()} on {@code GlStateManager}'s own methods, so while it holds state locked for a shader
 * pack the Blaze3D body <strong>never runs</strong> and its mirror is not updated — real GL holds the pack's
 * override while the mirror still reports Iris's pre-override values.</p>
 *
 * <p>It intercepts exactly three things, so the response is targeted rather than wholesale:</p>
 * <ul>
 *   <li>{@code BLEND} — {@code _enableBlend} / {@code _disableBlend} / {@code _blendFunc} /
 *       {@code _blendFuncSeparate}, while {@code BlendModeStorage.isBlendLocked()}.</li>
 *   <li>{@code COLOR_MASK} — {@code _colorMask}, while {@code DepthColorStorage.isDepthColorLocked()}.</li>
 *   <li>{@code DEPTH}, <em>write mask only</em> — {@code _depthMask}, same lock. The depth test and compare
 *       function are never intercepted, so only that one boolean is taken from the driver.</li>
 * </ul>
 *
 * <p>{@code CULL}, {@code STENCIL}, {@code SCISSOR}, {@code POLYGON_OFFSET} and {@code VIEWPORT} are never
 * intercepted and stay trustworthy even under a shader pack. {@code MixinBooleanState.setUnknownState()} is
 * called only on {@code getBLEND().mode}, so the blend gate already covers it.</p>
 *
 * <p>Both lock predicates are {@code public static}, so the gate is one cached reflective call per read, and
 * only when Iris is installed. With no pack active the locks read {@code false} and nothing is lost.</p>
 *
 * <p><strong>The enumeration above is exhaustive, not assumed.</strong> Every Iris mixin targeting
 * {@code GlStateManager} or {@code RenderSystem} was checked:</p>
 * <ul>
 *   <li>{@code MixinGlStateManager_BlendOverride}, {@code MixinGlStateManager_DepthColorOverride},
 *       {@code MixinBooleanState} — the three gated above.</li>
 *   <li>{@code MixinGlStateManager_FramebufferBinding} — cancels {@code _glBindFramebuffer} and
 *       {@code _glUseProgram}. <strong>Harmless here:</strong> Blaze3D never tracked FBO or program
 *       (both are pass-through), so this class does not override those readers and they come from
 *       {@code glGet}, which is always truthful. They are also binding domains, excluded from
 *       redundant-call elimination entirely.</li>
 *   <li>{@code texture/MixinGlStateManager}, {@code MixinRenderSystem},
 *       {@code statelisteners/*} — all {@code @At("TAIL")} / {@code "RETURN"} notifiers, non-cancelling.
 *       They observe, they do not divert, so the mirror stays truthful.</li>
 * </ul>
 *
 * <p><strong>No separate Iris state provider is needed or possible.</strong> Iris keeps no general-purpose
 * mirror to read <em>from</em>: {@code BlendModeStorage.originalBlend*} holds what Minecraft <em>wanted</em>,
 * not what is bound. While a pack is locked the truth is the pack's override, which only the driver knows.
 * Falling back to {@code glGet} is therefore not a compromise — it is the only correct source.</p>
 */
public final class Blaze3DStateProvider extends CgGlGetStateProvider {

    // ── Cached handles. Any that fails to resolve leaves its domain on the glGet path. ──

    private static final Field BLEND       = holder("BLEND");
    private static final Field DEPTH       = holder("DEPTH");
    private static final Field CULL        = holder("CULL");
    private static final Field STENCIL     = holder("STENCIL");
    private static final Field SCISSOR     = holder("SCISSOR");
    private static final Field COLOR_MASK  = holder("COLOR_MASK");
    private static final Field POLY_OFFSET = holder("POLY_OFFSET");

    /** {@code BooleanState.enabled} — private, and there is no getter. */
    private static final Field BOOLEAN_ENABLED = booleanStateEnabled();

    private static Field holder(String name) {
        try {
            Field f = GlStateManager.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static Field booleanStateEnabled() {
        try {
            Class<?> c = Class.forName("com.mojang.blaze3d.platform.GlStateManager$BooleanState");
            Field f = c.getDeclaredField("enabled");
            f.setAccessible(true);
            return f;
        } catch (Throwable missing) {
            return null;
        }
    }

    // ── Iris / Oculus interception gate ───────────────────────────────────────
    //
    // Iris injects at @At("HEAD") with ci.cancel() on GlStateManager's own methods, so when it has state
    // "locked" for a shader pack the Blaze3D body NEVER RUNS and its mirror fields are not updated, while
    // real GL holds the pack's override. Reading the mirror then yields Iris's pre-override values — a
    // stale answer we would adopt as truth.
    //
    // Verified against Iris 1.20.1 (research_repos/iris), which cancels exactly:
    //   MixinGlStateManager_BlendOverride       -> _enableBlend, _disableBlend, _blendFunc,
    //                                              _blendFuncSeparate   [while isBlendLocked()]
    //   MixinGlStateManager_DepthColorOverride  -> _colorMask, _depthMask
    //                                              [while isDepthColorLocked()]
    //
    // Nothing else is intercepted, so CULL, STENCIL, SCISSOR, POLYGON_OFFSET and VIEWPORT stay trustworthy
    // even under a shader pack. MixinBooleanState's setUnknownState() is called only on getBLEND().mode
    // (IrisRenderSystem:306,312), so it is already covered by the blend gate.
    //
    // Both predicates are public static, so this costs one cached reflective call per read and only while
    // Iris is installed. When no pack is active the locks are false and the fast path is unaffected.

    private static final Method IS_BLEND_LOCKED = irisPredicate(
            "gl.blending.BlendModeStorage", "isBlendLocked");
    private static final Method IS_DEPTH_COLOR_LOCKED = irisPredicate(
            "gl.blending.DepthColorStorage", "isDepthColorLocked");

    /** Iris and its Forge port Oculus have used different root packages; try both. */
    private static Method irisPredicate(String suffix, String method) {
        for (String root : new String[] { "net.irisshaders.iris.", "net.coderbot.iris." }) {
            try {
                Method m = Class.forName(root + suffix).getMethod(method);
                m.setAccessible(true);
                return m;
            } catch (Throwable next) {
                // try the other root
            }
        }
        return null;
    }

    private static boolean locked(Method predicate) {
        if (predicate == null) return false;          // Iris absent — nothing can be locked
        try {
            return Boolean.TRUE.equals(predicate.invoke(null));
        } catch (Throwable unknown) {
            // Cannot tell, so assume the worst and take the driver's answer.
            return true;
        }
    }

    /** True when enough resolved to be worth installing at all. */
    public static boolean isAvailable() {
        return BOOLEAN_ENABLED != null && DEPTH != null && BLEND != null;
    }

    // ── Overrides ─────────────────────────────────────────────────────────────

    @Override
    protected CgStateGroup readBlend() {
        // Iris has blend locked: MC's blend calls are being cancelled before Blaze3D records them.
        if (locked(IS_BLEND_LOCKED)) return super.readBlend();
        Object blend = value(BLEND);
        if (blend == null) return super.readBlend();
        try {
            boolean enabled = enabled(field(blend, "mode"));
            int srcRgb   = intField(blend, "srcRgb");
            int dstRgb   = intField(blend, "dstRgb");
            int srcAlpha = intField(blend, "srcAlpha");
            int dstAlpha = intField(blend, "dstAlpha");

            // Blaze3D's BlendState carries no equation. Read exactly those two enums rather than calling
            // super, which would re-read the whole domain and defeat the point of overriding at all.
            int eqRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_EQUATION_RGB);
            int eqAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_EQUATION_ALPHA);
            return new CgBlendState(enabled, srcRgb, dstRgb, srcAlpha, dstAlpha, eqRgb, eqAlpha);
        } catch (Throwable mismatch) {
            return super.readBlend();
        }
    }

    @Override
    protected CgStateGroup readDepth() {
        Object depth = value(DEPTH);
        if (depth == null) return super.readDepth();
        try {
            // Unlike Angelica — whose DepthState.enabled is the WRITE MASK — Blaze3D keeps the test in
            // `mode` and the write mask in `mask`, which is the intuitive reading. Do not carry the
            // Angelica workaround across.
            boolean test  = enabled(field(depth, "mode"));
            int func      = intField(depth, "func");

            // Only _depthMask is cancelled while Iris holds depth/colour locked — the test and compare
            // function are never intercepted. So take the write mask from the driver and keep the rest
            // from Blaze3D, rather than discarding the whole domain.
            boolean write = locked(IS_DEPTH_COLOR_LOCKED)
                    ? CgGL.glGetBoolean(CgGL.GL_DEPTH_WRITEMASK)
                    : boolField(depth, "mask");
            return new CgDepthState(test, write, func);
        } catch (Throwable mismatch) {
            return super.readDepth();
        }
    }

    @Override
    protected CgStateGroup readCull() {
        Object cull = value(CULL);
        if (cull == null) return super.readCull();
        try {
            boolean enabled = enabled(field(cull, "enable"));
            int mode        = intField(cull, "mode");
            // No front face in Blaze3D — read just that one enum from the driver.
            return new CgCullState(enabled, mode, CgGL.glGetInteger(CgGL.GL_FRONT_FACE));
        } catch (Throwable mismatch) {
            return super.readCull();
        }
    }

    @Override
    protected CgStateGroup readStencil() {
        Object stencil = value(STENCIL);
        if (stencil == null) return super.readStencil();
        try {
            Object f = field(stencil, "func");
            // Blaze3D's StencilState has NO enable flag — only func/ref/mask/fail/zfail/zpass. The
            // GL_STENCIL_TEST toggle therefore has to come from the driver, one boolean.
            return new CgStencilState(
                    /* enabled   */ CgGL.glGetBoolean(CgGL.GL_STENCIL_TEST),
                    /* ref       */ intField(f, "ref"),
                    /* readMask  */ intField(f, "mask"),
                    /* writeMask */ intField(stencil, "mask"),
                    /* compFunc  */ intField(f, "func"),
                    /* passOp    */ intField(stencil, "zpass"),
                    /* failOp    */ intField(stencil, "fail"),
                    /* zfailOp   */ intField(stencil, "zfail"));
        } catch (Throwable mismatch) {
            return super.readStencil();
        }
    }

    @Override
    protected CgStateGroup readColorMask() {
        // Iris cancels _colorMask while depth/colour is locked, so the mirror is stale.
        if (locked(IS_DEPTH_COLOR_LOCKED)) return super.readColorMask();
        Object mask = value(COLOR_MASK);
        if (mask == null) return super.readColorMask();
        try {
            return CgColorMaskState.of(
                    boolField(mask, "red"), boolField(mask, "green"),
                    boolField(mask, "blue"), boolField(mask, "alpha"));
        } catch (Throwable mismatch) {
            return super.readColorMask();
        }
    }

    @Override
    protected CgStateGroup readScissor() {
        Object scissor = value(SCISSOR);
        if (scissor == null) return super.readScissor();
        try {
            boolean enabled = enabled(field(scissor, "mode"));
            // Blaze3D tracks only the flag, never the box — read just the box.
            IntBuffer box = CgBufferUtils.createIntBuffer(4);
            CgGL.glGetInteger(CgGL.GL_SCISSOR_BOX, box);
            return new CgScissorState(enabled, box.get(0), box.get(1), box.get(2), box.get(3));
        } catch (Throwable mismatch) {
            return super.readScissor();
        }
    }

    @Override
    protected CgStateGroup readPolygonOffset() {
        Object po = value(POLY_OFFSET);
        if (po == null) return super.readPolygonOffset();
        try {
            boolean fill = enabled(field(po, "fill"));
            boolean line = enabled(field(po, "line"));
            float factor = floatField(po, "factor");
            float units  = floatField(po, "units");
            // GL_POLYGON_OFFSET_POINT is absent from Blaze3D — read just that one flag.
            boolean point = CgGL.glGetBoolean(CgGL.GL_POLYGON_OFFSET_POINT);
            return new CgPolygonOffsetState(fill, line, point, factor, units);
        } catch (Throwable mismatch) {
            return super.readPolygonOffset();
        }
    }

    /**
     * The one domain Blaze3D answers through public API — {@code GlStateManager.Viewport} is a public enum
     * with public static accessors, so this needs neither reflection nor {@code glGet}.
     */
    @Override
    protected CgStateGroup readViewport() {
        try {
            return new CgViewportState(
                    GlStateManager.Viewport.x(), GlStateManager.Viewport.y(),
                    GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
        } catch (Throwable mismatch) {
            return super.readViewport();
        }
    }

    // TEXTURES is intentionally NOT overridden — see the class javadoc. Answering from Blaze3D's first
    // twelve units would report 0 for the high units CrystalGraphics reserves, and a scope restoring that
    // would unbind our own textures.
    //
    // PROGRAM, FBO, VERTEX_INPUT, POLYGON_MODE, LINE_WIDTH and POINT_SIZE are pass-through in Blaze3D and
    // so are left to the glGet base as well.

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Object value(Field holder) {
        if (holder == null) return null;
        try {
            return holder.get(null);
        } catch (Throwable failed) {
            return null;
        }
    }

    /** Reads {@code BooleanState.enabled}, which is private with no getter. */
    private static boolean enabled(Object booleanState) throws Exception {
        if (BOOLEAN_ENABLED == null) throw new IllegalStateException("BooleanState.enabled unresolved");
        return BOOLEAN_ENABLED.getBoolean(booleanState);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static int intField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    private static boolean boolField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(target);
    }

    private static float floatField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(target);
    }
}
