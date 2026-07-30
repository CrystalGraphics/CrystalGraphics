package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlStateProvider;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;

/**
 * CPU-side shadow of GL state, with per-call redundancy elimination and scoped save/restore.
 *
 * <p>Every {@code glGet} is a driver synchronisation point — the GPU must drain queued work to answer one.
 * The design this replaces captured state that way on <em>every material bind</em>, and a single observed
 * frame spent <strong>346.8 ms</strong> doing it. Nothing here reads GL except through a
 * {@link CgGlStateProvider} at scope boundaries.</p>
 *
 * <h2>How it is reached</h2>
 * <p>Not directly. {@link CgGL}'s state setters consult it and skip the driver call when the value is
 * already current:</p>
 * <pre>{@code
 * public static void glDepthMask(boolean flag) {
 *     if (STATE.depthMaskChanged(flag)) backend.glDepthMask(flag);
 * }
 * }</pre>
 *
 * <p>That single chokepoint is the whole point of living in {@code platform}. The previous design tracked
 * state in {@code core} at the level of composite value objects, which left two paths — a typed one that
 * recorded, and a raw {@code CgGL} one that could only invalidate — and needed an observer, a bridge and a
 * build-time guard to hold the two together. Callers had to remember to announce raw writes; forty-six
 * hand-placed notifications later, a base class was still missed. Here, there is nothing to remember.</p>
 *
 * <h2>Truth, and where it comes from</h2>
 * <p>The shadow cannot be <em>derived</em> by watching other code: process-wide interception is not
 * omniscient (Angelica's transformer has been observed redirecting ours into its own). So truth is
 * <strong>asserted</strong> — a value is known because this class just wrote it — and <strong>poured</strong>
 * in from a {@link CgGlStateProvider} at scope boundaries, where the platform's own state manager is a
 * better authority than the driver.</p>
 *
 * <h2>Trust does not survive leaving our control</h2>
 * <p>An <strong>outermost</strong> {@link #save} re-reads the named domains unconditionally; a
 * <strong>nested</strong> one may trust the shadow. Between two outermost scopes, Minecraft or another mod
 * ran, and anything could have written state through an API we cannot see. Adopting lazily at the outermost
 * level is exactly the bug that once disabled blending and rendered every glyph as an opaque block.</p>
 *
 * <h2>Restore is not a second write path</h2>
 * <p>{@link #restore} re-issues through {@link CgGL}, so it goes through the same deduplication as any other
 * write. A domain nobody disturbed inside the scope compares equal and issues <strong>nothing</strong>, and
 * there is no separate restore implementation that can drift from the apply one — a real hazard in the
 * previous design, where the framebuffer slot's restore carried call-family logic its capture knew nothing
 * about.</p>
 *
 * <h3>Thread safety</h3>
 * <p>None, deliberately. A GL context belongs to one thread; a second one here corrupts the shadow rather
 * than failing, so it is asserted rather than accommodated.</p>
 */
public final class CgGlStateManager {

    private static final CgGlSlot[] SLOTS = CgGlSlot.values();
    private static final CgGlSlot[] NO_SLOTS = new CgGlSlot[0];
    private static final int SLOT_COUNT = SLOTS.length;
    private static final int ALL_UNKNOWN = (SLOT_COUNT == 32) ? -1 : (1 << SLOT_COUNT) - 1;

    /** Matches {@code ScissorStack}'s allowance; observed worst case is three. */
    private static final int MAX_DEPTH = 16;

    static {
        if (SLOT_COUNT > 32) {
            throw new IllegalStateException(
                    "CgGlSlot has " + SLOT_COUNT + " constants; the trust bitmask holds 32. "
                  + "Widen unknownMask and Frame.mask to long before adding more.");
        }
    }

    /**
     * Kill switch: never eliminate a call. {@code -Dcrystalgraphics.state.noDedup=true}
     *
     * <p>A wrong elimination is a <em>missing</em> GL call, which renders incorrectly and never throws. This
     * removes the decision without removing the manager, so one run answers "is the shadow lying?" instead
     * of requiring a bisect. Also the support answer for a user with a broken modpack.</p>
     */
    private static final boolean NO_DEDUP = Boolean.getBoolean("crystalgraphics.state.noDedup");

    private final CgGlStateShadow current = new CgGlStateShadow();
    private int unknownMask = ALL_UNKNOWN;
    private Thread owner;

    private CgGlStateProvider provider;

    private final Frame[] frames = new Frame[MAX_DEPTH];
    private int depth;

    /**
     * Diagnostics. Plain fields because {@code CgProfiler} lives in {@code core}, which {@code platform}
     * must not depend on — read them from there rather than adding a callback for four counters.
     */
    public long callsIssued, callsSkipped, adopted;

    public CgGlStateManager(CgGlStateProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        this.provider = provider;
        for (int i = 0; i < MAX_DEPTH; i++) frames[i] = new Frame();
    }

    public void setProvider(CgGlStateProvider p) {
        if (p == null) throw new IllegalArgumentException("provider must not be null");
        this.provider = p;
        // Values vouched for by the previous provider describe a state this one has not confirmed.
        invalidateAll();
    }

    // ── Trust ─────────────────────────────────────────────────────────────────

    public boolean isTrusted(CgGlSlot slot) { return (unknownMask & (1 << slot.ordinal())) == 0; }

    /**
     * Domains that are tracked but <strong>never deduplicated</strong> — every write is issued.
     *
     * <p>Deduplication is only ever a bet that nothing wrote GL behind our back, and the stake differs
     * sharply by domain. These two are where the bet pays least and loses worst:</p>
     *
     * <ul>
     *   <li><strong>{@code FBO}</strong> — bound a handful of times per frame, so eliminating those calls
     *       saves nothing measurable; and a wrong framebuffer binding draws into the wrong target, which
     *       frequently produces <em>no visible output at all</em> rather than wrong output.</li>
     *   <li><strong>{@code PROGRAM}</strong> — {@code glUseProgram} is a cheap call with no driver
     *       synchronisation, while Minecraft, Iris and every shader mod rebind programs constantly. It is
     *       the single binding most likely to be changed behind us.</li>
     * </ul>
     *
     * <p>Keeping them <em>tracked</em> still matters: the shadow is what a scope restores from, and always
     * issuing means restore cannot re-establish a stale remembered value either. The frequently-bound
     * domains ({@code TEXTURES}, {@code VERTEX_INPUT}) stay deduplicated, because there the call volume is
     * high enough for the elimination to be worth the narrower risk.</p>
     *
     * <p>Context for the trade: the measured win — 346.8 ms of {@code glGet} per frame down to 0.00 ms —
     * came from removing driver synchronisation in scope capture, <strong>not</strong> from eliminating
     * writes. Write deduplication is a second-order gain, so exempting a domain costs almost none of it.</p>
     */
    private static final int DEDUP_EXEMPT =
            (1 << CgGlSlot.FBO.ordinal()) | (1 << CgGlSlot.PROGRAM.ordinal());

    private boolean stale(CgGlSlot slot) {
        int bit = 1 << slot.ordinal();
        if ((DEDUP_EXEMPT & bit) != 0) return true;
        return NO_DEDUP || forcing || (unknownMask & bit) != 0;
    }

    /**
     * Suspends deduplication while a whole domain is being re-established.
     *
     * <p>Needed because trust is tracked per <em>domain</em> but a domain is written field by field. The
     * first field to be re-issued calls {@code issue()}, which marks the domain trusted — and every
     * remaining field of that same domain then compares equal to the stale shadow and is skipped. A restore
     * that had to re-establish {@code DEPTH} would emit {@code glEnable(GL_DEPTH_TEST)} and silently drop
     * {@code glDepthMask} and {@code glDepthFunc}.</p>
     *
     * <p>Scoped to exactly one {@code reissue} of one stale domain, so the ordinary case — restoring a
     * domain nobody disturbed — still costs zero GL calls.</p>
     */
    private boolean forcing;

    private boolean issue(CgGlSlot slot) { unknownMask &= ~(1 << slot.ordinal()); callsIssued++; return true; }

    private boolean skip() { callsSkipped++; return false; }

    /** Marks domains untrustworthy without touching GL. For boundaries that are not scopes. */
    public void invalidate(CgGlSlot... slots) {
        for (CgGlSlot s : slots) unknownMask |= 1 << s.ordinal();
    }

    public void invalidateAll() { unknownMask = ALL_UNKNOWN; }

    private void assertOwner() {
        Thread t = Thread.currentThread();
        if (owner == null) { owner = t; return; }
        if (owner != t) {
            throw new IllegalStateException(
                    "CgGlStateManager touched from " + t.getName() + " but owned by " + owner.getName()
                  + ". A GL context is single-threaded, and an off-thread write corrupts the shadow "
                  + "rather than failing outright.");
        }
    }

    // ── Per-call deduplication ────────────────────────────────────────────────
    //
    // One method per CgGL state setter. Each returns true when the value actually changed — "yes, tell the
    // driver" — and records as it goes, so there is no separate recording step to forget.
    //
    // All sixteen domains participate. The previous design excluded the four binding domains because they
    // had their own established APIs with too many call sites to notify reliably; with CgGL as the single
    // chokepoint that reasoning no longer applies, and the allow-list is gone.

    public boolean capabilityChanged(int cap, boolean enable) {
        assertOwner();
        if (cap == CgGL.GL_BLEND)        return flagChanged(CgGlSlot.BLEND,   current.blendEnabled, enable) && set(() -> current.blendEnabled = enable, CgGlSlot.BLEND);
        if (cap == CgGL.GL_DEPTH_TEST)   return flagChanged(CgGlSlot.DEPTH,   current.depthTest,    enable) && set(() -> current.depthTest    = enable, CgGlSlot.DEPTH);
        if (cap == CgGL.GL_CULL_FACE)    return flagChanged(CgGlSlot.CULL,    current.cullEnabled,  enable) && set(() -> current.cullEnabled  = enable, CgGlSlot.CULL);
        if (cap == CgGL.GL_STENCIL_TEST) return flagChanged(CgGlSlot.STENCIL, current.stencilTest,  enable) && set(() -> current.stencilTest  = enable, CgGlSlot.STENCIL);
        if (cap == CgGL.GL_ALPHA_TEST)   return flagChanged(CgGlSlot.ALPHA_TEST, current.alphaTest, enable) && set(() -> current.alphaTest    = enable, CgGlSlot.ALPHA_TEST);
        if (cap == CgGL.GL_SCISSOR_TEST) return flagChanged(CgGlSlot.SCISSOR, current.scissorTest,  enable) && set(() -> current.scissorTest  = enable, CgGlSlot.SCISSOR);
        if (cap == CgGL.GL_POLYGON_OFFSET_FILL)
            return flagChanged(CgGlSlot.POLYGON_OFFSET, current.polygonOffsetFill, enable) && set(() -> current.polygonOffsetFill = enable, CgGlSlot.POLYGON_OFFSET);
        if (cap == CgGL.GL_POLYGON_OFFSET_LINE)
            return flagChanged(CgGlSlot.POLYGON_OFFSET, current.polygonOffsetLine, enable) && set(() -> current.polygonOffsetLine = enable, CgGlSlot.POLYGON_OFFSET);
        if (cap == CgGL.GL_POLYGON_OFFSET_POINT)
            return flagChanged(CgGlSlot.POLYGON_OFFSET, current.polygonOffsetPoint, enable) && set(() -> current.polygonOffsetPoint = enable, CgGlSlot.POLYGON_OFFSET);
        // An untracked capability. Always issue — we cannot say whether it is redundant, and guessing that
        // it is would drop a real call.
        return true;
    }

    private boolean flagChanged(CgGlSlot slot, boolean held, boolean wanted) {
        return stale(slot) || held != wanted;
    }

    /** Applies the field write, marks the domain trusted and counts the call. Always returns true. */
    private boolean set(Runnable write, CgGlSlot slot) {
        write.run();
        return issue(slot);
    }

    public boolean blendFuncChanged(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        assertOwner();
        if (!stale(CgGlSlot.BLEND)
                && current.blendSrcRgb == srcRgb && current.blendDstRgb == dstRgb
                && current.blendSrcAlpha == srcAlpha && current.blendDstAlpha == dstAlpha) return skip();
        current.blendSrcRgb = srcRgb; current.blendDstRgb = dstRgb;
        current.blendSrcAlpha = srcAlpha; current.blendDstAlpha = dstAlpha;
        return issue(CgGlSlot.BLEND);
    }

    public boolean blendEquationChanged(int modeRgb, int modeAlpha) {
        assertOwner();
        if (!stale(CgGlSlot.BLEND)
                && current.blendEqRgb == modeRgb && current.blendEqAlpha == modeAlpha) return skip();
        current.blendEqRgb = modeRgb; current.blendEqAlpha = modeAlpha;
        return issue(CgGlSlot.BLEND);
    }

    public boolean depthMaskChanged(boolean flag) {
        assertOwner();
        if (!stale(CgGlSlot.DEPTH) && current.depthMask == flag) return skip();
        current.depthMask = flag;
        return issue(CgGlSlot.DEPTH);
    }

    public boolean depthFuncChanged(int func) {
        assertOwner();
        if (!stale(CgGlSlot.DEPTH) && current.depthFunc == func) return skip();
        current.depthFunc = func;
        return issue(CgGlSlot.DEPTH);
    }

    public boolean cullFaceChanged(int mode) {
        assertOwner();
        if (!stale(CgGlSlot.CULL) && current.cullFace == mode) return skip();
        current.cullFace = mode;
        return issue(CgGlSlot.CULL);
    }

    public boolean frontFaceChanged(int mode) {
        assertOwner();
        if (!stale(CgGlSlot.CULL) && current.frontFace == mode) return skip();
        current.frontFace = mode;
        return issue(CgGlSlot.CULL);
    }

    public boolean stencilFuncChanged(int func, int ref, int mask) {
        assertOwner();
        if (!stale(CgGlSlot.STENCIL) && current.stencilFunc == func
                && current.stencilRef == ref && current.stencilValueMask == mask) return skip();
        current.stencilFunc = func; current.stencilRef = ref; current.stencilValueMask = mask;
        return issue(CgGlSlot.STENCIL);
    }

    public boolean stencilOpChanged(int sfail, int dpfail, int dppass) {
        assertOwner();
        if (!stale(CgGlSlot.STENCIL) && current.stencilFail == sfail
                && current.stencilZFail == dpfail && current.stencilZPass == dppass) return skip();
        current.stencilFail = sfail; current.stencilZFail = dpfail; current.stencilZPass = dppass;
        return issue(CgGlSlot.STENCIL);
    }

    public boolean stencilMaskChanged(int mask) {
        assertOwner();
        if (!stale(CgGlSlot.STENCIL) && current.stencilWriteMask == mask) return skip();
        current.stencilWriteMask = mask;
        return issue(CgGlSlot.STENCIL);
    }

    public boolean alphaFuncChanged(int func, float ref) {
        assertOwner();
        if (!stale(CgGlSlot.ALPHA_TEST) && current.alphaFunc == func && current.alphaRef == ref) return skip();
        current.alphaFunc = func; current.alphaRef = ref;
        return issue(CgGlSlot.ALPHA_TEST);
    }

    public boolean colorMaskChanged(boolean r, boolean g, boolean b, boolean a) {
        assertOwner();
        int nibble = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        int packed = 0;
        for (int t = 0; t < 8; t++) packed |= nibble << (t * 4);
        if (!stale(CgGlSlot.COLOR_MASK) && current.colorMaskPacked == packed) return skip();
        current.colorMaskPacked = packed;
        return issue(CgGlSlot.COLOR_MASK);
    }

    public boolean colorMaskiChanged(int buf, boolean r, boolean g, boolean b, boolean a) {
        assertOwner();
        if (buf < 0 || buf >= 8) return true;                 // outside what we model; always issue
        int shift = buf * 4;
        int nibble = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        int packed = (current.colorMaskPacked & ~(0xF << shift)) | (nibble << shift);
        if (!stale(CgGlSlot.COLOR_MASK) && current.colorMaskPacked == packed) return skip();
        current.colorMaskPacked = packed;
        return issue(CgGlSlot.COLOR_MASK);
    }

    public boolean viewportChanged(int x, int y, int w, int h) {
        assertOwner();
        if (!stale(CgGlSlot.VIEWPORT) && current.viewportX == x && current.viewportY == y
                && current.viewportW == w && current.viewportH == h) return skip();
        current.viewportX = x; current.viewportY = y; current.viewportW = w; current.viewportH = h;
        return issue(CgGlSlot.VIEWPORT);
    }

    public boolean scissorChanged(int x, int y, int w, int h) {
        assertOwner();
        if (!stale(CgGlSlot.SCISSOR) && current.scissorX == x && current.scissorY == y
                && current.scissorW == w && current.scissorH == h) return skip();
        current.scissorX = x; current.scissorY = y; current.scissorW = w; current.scissorH = h;
        return issue(CgGlSlot.SCISSOR);
    }

    public boolean polygonOffsetChanged(float factor, float units) {
        assertOwner();
        if (!stale(CgGlSlot.POLYGON_OFFSET)
                && current.polygonOffsetFactor == factor && current.polygonOffsetUnits == units) return skip();
        current.polygonOffsetFactor = factor; current.polygonOffsetUnits = units;
        return issue(CgGlSlot.POLYGON_OFFSET);
    }

    public boolean polygonModeChanged(int face, int mode) {
        assertOwner();
        boolean front = face == CgGL.GL_FRONT || face == CgGL.GL_FRONT_AND_BACK;
        boolean back  = face == CgGL.GL_BACK  || face == CgGL.GL_FRONT_AND_BACK;
        boolean same = !stale(CgGlSlot.POLYGON_MODE)
                && (!front || current.polygonModeFront == mode)
                && (!back  || current.polygonModeBack  == mode);
        if (same) return skip();
        if (front) current.polygonModeFront = mode;
        if (back)  current.polygonModeBack  = mode;
        return issue(CgGlSlot.POLYGON_MODE);
    }

    public boolean lineWidthChanged(float width) {
        assertOwner();
        if (!stale(CgGlSlot.LINE_WIDTH) && current.lineWidth == width) return skip();
        current.lineWidth = width;
        return issue(CgGlSlot.LINE_WIDTH);
    }

    public boolean pointSizeChanged(float size) {
        assertOwner();
        if (!stale(CgGlSlot.POINT_SIZE) && current.pointSize == size) return skip();
        current.pointSize = size;
        return issue(CgGlSlot.POINT_SIZE);
    }

    public boolean programChanged(int program) {
        assertOwner();
        if (!stale(CgGlSlot.PROGRAM) && current.programId == program) return skip();
        current.programId = program;
        return issue(CgGlSlot.PROGRAM);
    }

    /**
     * Framebuffer binding, with the call family derived from the target rather than passed in.
     *
     * <p>An {@code EXT_framebuffer_object} name is not valid in a Core call, so switching families must
     * release through the family that owns the name. {@code EXT} identifies itself by binding
     * {@code GL_FRAMEBUFFER_EXT}; Core and {@code ARB_framebuffer_object} share one object namespace and
     * need no distinction between them.</p>
     */
    public boolean fboChanged(int target, int fbo) {
        assertOwner();
        CgGlStateShadow.FboFamily family = target == CgGL.GL_FRAMEBUFFER_EXT
                ? CgGlStateShadow.FboFamily.EXT
                : CgGlStateShadow.FboFamily.CORE_OR_ARB;

        boolean crossFamily = current.fboFamily != CgGlStateShadow.FboFamily.UNKNOWN
                && current.fboFamily != family;

        boolean draw = target != CgGL.GL_READ_FRAMEBUFFER;
        boolean read = target != CgGL.GL_DRAW_FRAMEBUFFER;

        if (!crossFamily && !stale(CgGlSlot.FBO)
                && (!draw || current.drawFbo == fbo) && (!read || current.readFbo == fbo)) return skip();

        if (draw) current.drawFbo = fbo;
        if (read) current.readFbo = fbo;
        current.fboFamily = family;
        return issue(CgGlSlot.FBO);
    }

    /** Minecraft's {@code OpenGlHelper} wrapper picks the API itself, so it is its own family. */
    public boolean fboCompatChanged(int fbo) {
        assertOwner();
        boolean crossFamily = current.fboFamily != CgGlStateShadow.FboFamily.MC_WRAPPER;
        if (!crossFamily && !stale(CgGlSlot.FBO)
                && current.drawFbo == fbo && current.readFbo == fbo) return skip();
        current.drawFbo = fbo; current.readFbo = fbo;
        current.fboFamily = CgGlStateShadow.FboFamily.MC_WRAPPER;
        return issue(CgGlSlot.FBO);
    }

    public boolean activeTextureChanged(int texture) {
        assertOwner();
        int unit = texture - CgGL.GL_TEXTURE0;
        if (unit < 0 || unit >= CgGlStateShadow.MAX_TEXTURE_UNITS) return true;
        if (!stale(CgGlSlot.TEXTURES) && current.activeTextureUnit == unit) return skip();
        current.activeTextureUnit = unit;
        return issue(CgGlSlot.TEXTURES);
    }

    public boolean textureChanged(int target, int texture) {
        assertOwner();
        // Only GL_TEXTURE_2D is modelled; other targets are always issued rather than assumed redundant.
        if (target != CgGL.GL_TEXTURE_2D) return true;
        int unit = current.activeTextureUnit;
        if (unit < 0 || unit >= CgGlStateShadow.MAX_TEXTURE_UNITS) return true;
        if (!stale(CgGlSlot.TEXTURES) && current.boundTexture2D[unit] == texture) return skip();
        current.boundTexture2D[unit] = texture;
        return issue(CgGlSlot.TEXTURES);
    }

    /**
     * Binds a vertex array object, and gives up what we knew about the element array binding.
     *
     * <p>The VAO carries its own element array binding and restores it on bind, invisibly to us — see
     * {@link CgGlStateShadow#elementArrayBuffer}. Anything we believed about that binding described the
     * <em>previous</em> VAO, so it has to be dropped here or the next bind of an already-"current" IBO is
     * elided and the draw fails.</p>
     */
    public boolean vertexArrayChanged(int array) {
        assertOwner();
        if (!stale(CgGlSlot.VERTEX_INPUT) && current.vertexArray == array) return skip();
        current.vertexArray = array;
        current.elementArrayBuffer = CgGlStateShadow.UNKNOWN_BINDING;
        return issue(CgGlSlot.VERTEX_INPUT);
    }

    public boolean bufferChanged(int target, int buffer) {
        assertOwner();
        if (target == CgGL.GL_ARRAY_BUFFER) {
            if (!stale(CgGlSlot.VERTEX_INPUT) && current.arrayBuffer == buffer) return skip();
            current.arrayBuffer = buffer;
            return issue(CgGlSlot.VERTEX_INPUT);
        }
        if (target == CgGL.GL_ELEMENT_ARRAY_BUFFER) {
            if (!stale(CgGlSlot.VERTEX_INPUT) && current.elementArrayBuffer == buffer) return skip();
            current.elementArrayBuffer = buffer;
            return issue(CgGlSlot.VERTEX_INPUT);
        }
        return true;    // uniform/shader-storage etc. — not modelled, always issued
    }

    // ── Scopes ────────────────────────────────────────────────────────────────

    /**
     * Marks a restore point for the named domains.
     *
     * @throws IllegalStateException if nesting exceeds the pool, which would leak an unrestorable scope
     */
    public CgGlScope save(CgGlSlot... slots) {
        return open(false, slots);
    }

    /**
     * Marks a restore point around a block that hands control to <strong>foreign rendering code</strong> —
     * Minecraft's {@code ItemRenderer}, an entity render, another mod's callback.
     *
     * <p>Not a defensive measure against a hostile mod: hosting Minecraft's own renderers inside a
     * CrystalGUI panel or a preview viewport is a designed, frequent thing this engine does. Foreign code
     * writes GL through paths {@link CgGL} never sees, so on exit the shadow is not merely suspect, it is
     * <em>known</em> to be describing a world that no longer exists.</p>
     *
     * <p>So this differs from {@link #save} in exactly one way: <strong>on exit it invalidates every domain
     * before restoring</strong>, which forces the declared ones to be re-asserted for real instead of being
     * deduplicated away against a stale shadow. Domains you did not declare stay marked unknown, so the next
     * write to them re-establishes truth rather than assuming it.</p>
     *
     * <p>Entry is <em>free</em> — no {@code glGet}, because our shadow is still truthful going in; we issued
     * everything in it. The whole cost is one re-assert of what you named, on the way out.</p>
     *
     * <pre>{@code
     * try (CgGlScope s = CgGlState.manager().hostForeign(CgGlSlot.BLEND, CgGlSlot.DEPTH, CgGlSlot.PROGRAM)) {
     *     minecraft.getItemRenderer().renderStatic(stack, ...);
     * }   // blend/depth/program re-asserted; everything else marked unknown
     * }</pre>
     *
     * <p>Declaring nothing is legitimate and still useful: it invalidates on exit without restoring
     * anything, for when you intend to set up fresh state afterwards regardless.</p>
     *
     * <h3>This only fixes our half</h3>
     * <p>Minecraft keeps its <em>own</em> shadow ({@code GlStateManager} on 1.20.x, Angelica's
     * {@code GLStateManager} on 1.7.10), and every write we make through {@code CgGL} is equally invisible
     * to it. Before calling into MC, state MC cares about should be set through <em>MC's</em> API so its
     * mirror is truthful too. This scope cannot do that for you — it is on the wrong side of the boundary.
     * On 1.7.10 with Angelica the problem largely dissolves, because our provider reads Angelica's mirror,
     * which observed both sides.</p>
     */
    public CgGlScope hostForeign(CgGlSlot... slots) {
        return open(true, slots);
    }

    private CgGlScope open(boolean foreign, CgGlSlot... slots) {
        assertOwner();
        // A foreign block with nothing declared still has to invalidate on exit, so it needs a real frame.
        if ((slots == null || slots.length == 0) && !foreign) return CgGlScope.NOOP_SCOPE;
        if (slots == null) slots = NO_SLOTS;
        if (depth == MAX_DEPTH) {
            throw new IllegalStateException(
                    "GL state scope nesting exceeded " + MAX_DEPTH + "; unbalanced save() somewhere");
        }

        Frame f = frames[depth++];
        f.mask = 0;
        f.closed = false;
        f.foreign = foreign;

        // Outermost: foreign code ran since we last knew anything, so re-read unconditionally. Nested: the
        // enclosing scope already established truth. Trusting the shadow at the outermost level is the bug
        // that once left blending disabled and every glyph an opaque block.
        boolean outermost = depth == 1;
        for (CgGlSlot slot : slots) {
            int bit = 1 << slot.ordinal();
            if ((f.mask & bit) != 0) continue;
            if (outermost || !isTrusted(slot)) adopt(slot);
            f.mask |= bit;
        }
        f.saved.copyFrom(current);
        return f;
    }

    private void adopt(CgGlSlot slot) {
        provider.read(slot, current);
        unknownMask &= ~(1 << slot.ordinal());
        adopted++;
    }

    /** The value {@code slot} reverts to when the innermost scope covering it closes, or {@code null}. */
    public CgGlStateShadow baselineFor(CgGlSlot slot) {
        int bit = 1 << slot.ordinal();
        for (int d = depth - 1; d >= 0; d--) {
            if ((frames[d].mask & bit) != 0) return frames[d].saved;
        }
        return null;
    }

    public int depth() { return depth; }

    /**
     * A restore point. Pooled, so entering a scope allocates nothing.
     *
     * <p>Restores by re-issuing through {@link CgGL}, which puts it through the same deduplication as any
     * other write: a domain nobody disturbed issues nothing at all, and there is no second write path to
     * drift from the first.</p>
     */
    public final class Frame implements CgGlScope {
        private final CgGlStateShadow saved = new CgGlStateShadow();
        private int mask;
        private boolean closed;
        private boolean foreign;

        private Frame() {}

        @Override
        public void restore() {
            if (closed) return;
            if (depth == 0 || frames[depth - 1] != this) {
                throw new IllegalStateException(
                        "GL state scopes closed out of order; use try-with-resources");
            }
            closed = true;
            // Foreign code wrote GL behind CgGL's back, so the shadow is describing a world that no longer
            // exists. Dropping trust FIRST is what makes the reissue below actually reach the driver —
            // without it every restore would be deduplicated away against exactly the stale values that are
            // wrong, which is the silent-elision failure this scope exists to prevent.
            if (foreign) invalidateAll();
            for (CgGlSlot slot : SLOTS) {
                if ((mask & (1 << slot.ordinal())) == 0) continue;
                // A stale domain must be re-established in full, not just up to its first field — see
                // `forcing`. A trusted one takes the normal deduplicated path and usually emits nothing.
                boolean force = stale(slot);
                forcing = force;
                try {
                    reissue(slot, saved);
                } finally {
                    forcing = false;
                }
            }
            depth--;
        }

        @Override
        public void close() { restore(); }
    }

    /**
     * Re-establishes one domain from a saved shadow, through {@link CgGL}.
     *
     * <p>The only per-domain code in this class. It replaces twelve value-object {@code emit()}
     * implementations, and because it goes through {@code CgGL} it inherits deduplication for free.</p>
     */
    private void reissue(CgGlSlot slot, CgGlStateShadow s) {
        switch (slot) {
            case BLEND:
                setCap(CgGL.GL_BLEND, s.blendEnabled);
                CgGL.glBlendFuncSeparate(s.blendSrcRgb, s.blendDstRgb, s.blendSrcAlpha, s.blendDstAlpha);
                CgGL.glBlendEquationSeparate(s.blendEqRgb, s.blendEqAlpha);
                break;
            case DEPTH:
                setCap(CgGL.GL_DEPTH_TEST, s.depthTest);
                CgGL.glDepthFunc(s.depthFunc);
                CgGL.glDepthMask(s.depthMask);
                break;
            case CULL:
                setCap(CgGL.GL_CULL_FACE, s.cullEnabled);
                if (s.cullFace != 0) CgGL.glCullFace(s.cullFace);
                CgGL.glFrontFace(s.frontFace);
                break;
            case STENCIL:
                setCap(CgGL.GL_STENCIL_TEST, s.stencilTest);
                CgGL.glStencilFunc(s.stencilFunc, s.stencilRef, s.stencilValueMask);
                CgGL.glStencilMask(s.stencilWriteMask);
                CgGL.glStencilOp(s.stencilFail, s.stencilZFail, s.stencilZPass);
                break;
            case ALPHA_TEST:
                setCap(CgGL.GL_ALPHA_TEST, s.alphaTest);
                CgGL.glAlphaFunc(s.alphaFunc, s.alphaRef);
                break;
            case COLOR_MASK: {
                int p = s.colorMaskPacked;
                boolean uniform = true;
                int n0 = p & 0xF;
                for (int t = 1; t < 8 && uniform; t++) uniform = ((p >>> (t * 4)) & 0xF) == n0;
                if (uniform) {
                    CgGL.glColorMask((n0 & 1) != 0, (n0 & 2) != 0, (n0 & 4) != 0, (n0 & 8) != 0);
                } else {
                    for (int t = 0; t < 8; t++) {
                        int n = (p >>> (t * 4)) & 0xF;
                        CgGL.glColorMaski(t, (n & 1) != 0, (n & 2) != 0, (n & 4) != 0, (n & 8) != 0);
                    }
                }
                break;
            }
            case VIEWPORT:
                CgGL.glViewport(s.viewportX, s.viewportY, s.viewportW, s.viewportH);
                break;
            case SCISSOR:
                setCap(CgGL.GL_SCISSOR_TEST, s.scissorTest);
                CgGL.glScissor(s.scissorX, s.scissorY, s.scissorW, s.scissorH);
                break;
            case POLYGON_OFFSET:
                setCap(CgGL.GL_POLYGON_OFFSET_FILL,  s.polygonOffsetFill);
                setCap(CgGL.GL_POLYGON_OFFSET_LINE,  s.polygonOffsetLine);
                setCap(CgGL.GL_POLYGON_OFFSET_POINT, s.polygonOffsetPoint);
                CgGL.glPolygonOffset(s.polygonOffsetFactor, s.polygonOffsetUnits);
                break;
            case POLYGON_MODE:
                CgGL.glPolygonMode(CgGL.GL_FRONT, s.polygonModeFront);
                CgGL.glPolygonMode(CgGL.GL_BACK,  s.polygonModeBack);
                break;
            case LINE_WIDTH: CgGL.glLineWidth(s.lineWidth); break;
            case POINT_SIZE: CgGL.glPointSize(s.pointSize); break;
            case PROGRAM:    CgGL.glUseProgram(s.programId); break;
            case FBO:
                if (s.fboFamily == CgGlStateShadow.FboFamily.MC_WRAPPER) {
                    CgGL.glBindFramebufferCompat(s.drawFbo);
                } else if (s.fboFamily == CgGlStateShadow.FboFamily.EXT || s.drawFbo == s.readFbo) {
                    // EXT has no draw/read split; and a matching pair needs only one bind.
                    CgGL.glBindFramebuffer(
                            s.fboFamily == CgGlStateShadow.FboFamily.EXT
                                    ? CgGL.GL_FRAMEBUFFER_EXT : CgGL.GL_FRAMEBUFFER, s.drawFbo);
                } else {
                    CgGL.glBindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, s.drawFbo);
                    CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, s.readFbo);
                }
                break;
            case TEXTURES:
                for (int unit = 0; unit < CgGlStateShadow.MAX_TEXTURE_UNITS; unit++) {
                    if (current.boundTexture2D[unit] == s.boundTexture2D[unit]) continue;
                    CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
                    CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, s.boundTexture2D[unit]);
                }
                CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + s.activeTextureUnit);
                break;
            case VERTEX_INPUT:
                // Order matters: binding an element buffer while a VAO is active records it INTO that VAO,
                // so the VAO must be restored first or an unrelated VAO is silently corrupted.
                CgGL.glBindVertexArray(s.vertexArray);
                CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, s.arrayBuffer);
                // Restoring the VAO already restored its element binding — that is VAO state. Re-issuing is
                // only needed when the saved name is known; UNKNOWN means "whatever the VAO says", which the
                // line above has just reinstated. Binding the sentinel would be a GL error.
                if (s.elementArrayBuffer != CgGlStateShadow.UNKNOWN_BINDING) {
                    CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, s.elementArrayBuffer);
                }
                break;
            default:
                throw new IllegalStateException("No reissue for slot " + slot);
        }
    }

    private static void setCap(int cap, boolean enable) {
        if (enable) CgGL.glEnable(cap); else CgGL.glDisable(cap);
    }
}
