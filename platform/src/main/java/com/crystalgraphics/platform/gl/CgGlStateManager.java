package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.CgGlGetProvider;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlStateProvider;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>An <strong>outermost</strong> {@link #save} outside a host section re-reads the named domains
 * unconditionally; a <strong>nested</strong> one may trust the shadow. Between two such scopes, Minecraft or
 * another mod ran, and anything could have written state through an API we cannot see. Adopting lazily there
 * is exactly the bug that once disabled blending and rendered every glyph as an opaque block.</p>
 *
 * <p>Inside a host section ({@link CgGL#fromHost()} to {@link CgGL#toHost()}) only our code touches GL: the
 * outermost {@code fromHost} forgets the shadow, host code run inside goes through {@code hostForeign}, which
 * forgets it again, so even an outermost scope reads only what is untrusted. A host brackets its whole
 * frame, or each outermost scope in it pays a {@code glGet} per declared domain.</p>
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

    /** Matches {@code ScissorStack}'s allowance; observed worst case is three. */
    private static final int MAX_DEPTH = 16;

    static {
        if (SLOT_COUNT > 32) {
            throw new IllegalStateException(
                    "CgGlSlot has " + SLOT_COUNT + " constants; a scope's slot mask holds 32. "
                  + "Widen Frame.mask to long before adding more.");
        }
    }

    // ── Trust is per FIELD: the values one setter writes ──────────────────────
    //
    // Per domain it was wrong: glEnable(GL_DEPTH_TEST) vouched for depthMask and depthFunc at values nobody
    // had read, and a later glDepthMask equal to that default was elided. A write vouches for what it wrote;
    // a scope's read vouches for the whole domain.
    private static final long
            F_BLEND_ENABLE = 1L,       F_BLEND_FUNC = 1L << 1,    F_BLEND_EQUATION = 1L << 2,
            F_DEPTH_TEST = 1L << 3,    F_DEPTH_MASK = 1L << 4,    F_DEPTH_FUNC = 1L << 5,
            F_CULL_ENABLE = 1L << 6,   F_CULL_FACE = 1L << 7,     F_FRONT_FACE = 1L << 8,
            F_STENCIL_TEST = 1L << 9,  F_STENCIL_FUNC = 1L << 10, F_STENCIL_OP = 1L << 11,
            F_STENCIL_MASK = 1L << 12, F_ALPHA_TEST = 1L << 13,   F_ALPHA_FUNC = 1L << 14,
            F_COLOR_MASK = 1L << 15,   F_VIEWPORT = 1L << 16,     F_SCISSOR_TEST = 1L << 17,
            F_SCISSOR_BOX = 1L << 18,  F_OFFSET_FILL = 1L << 19,  F_OFFSET_LINE = 1L << 20,
            F_OFFSET_POINT = 1L << 21, F_OFFSET_VALUES = 1L << 22, F_MODE_FRONT = 1L << 23,
            F_MODE_BACK = 1L << 24,    F_LINE_WIDTH = 1L << 25,   F_POINT_SIZE = 1L << 26,
            F_PROGRAM = 1L << 27,      F_DRAW_FBO = 1L << 28,     F_READ_FBO = 1L << 29,
            F_ACTIVE_TEXTURE = 1L << 30, F_VERTEX_ARRAY = 1L << 31, F_ARRAY_BUFFER = 1L << 32,
            F_ELEMENT_BUFFER = 1L << 33;
    private static final long ALL_FIELDS = (1L << 34) - 1;
    /** Each unit's {@code GL_TEXTURE_2D} binding is a field of its own, kept in a separate mask. */
    private static final int ALL_UNITS = -1;

    private static final long[] SLOT_FIELDS = new long[SLOT_COUNT];

    static {
        for (CgGlSlot s : SLOTS) SLOT_FIELDS[s.ordinal()] = fieldsOf(s);
    }

    private static long fieldsOf(CgGlSlot slot) {
        switch (slot) {
            case BLEND:          return F_BLEND_ENABLE | F_BLEND_FUNC | F_BLEND_EQUATION;
            case DEPTH:          return F_DEPTH_TEST | F_DEPTH_MASK | F_DEPTH_FUNC;
            case CULL:           return F_CULL_ENABLE | F_CULL_FACE | F_FRONT_FACE;
            case STENCIL:        return F_STENCIL_TEST | F_STENCIL_FUNC | F_STENCIL_OP | F_STENCIL_MASK;
            case ALPHA_TEST:     return F_ALPHA_TEST | F_ALPHA_FUNC;
            case COLOR_MASK:     return F_COLOR_MASK;
            case VIEWPORT:       return F_VIEWPORT;
            case SCISSOR:        return F_SCISSOR_TEST | F_SCISSOR_BOX;
            case POLYGON_OFFSET: return F_OFFSET_FILL | F_OFFSET_LINE | F_OFFSET_POINT | F_OFFSET_VALUES;
            case POLYGON_MODE:   return F_MODE_FRONT | F_MODE_BACK;
            case LINE_WIDTH:     return F_LINE_WIDTH;
            case POINT_SIZE:     return F_POINT_SIZE;
            case PROGRAM:        return F_PROGRAM;
            case FBO:            return F_DRAW_FBO | F_READ_FBO;
            case TEXTURES:       return F_ACTIVE_TEXTURE;
            case VERTEX_INPUT:   return F_VERTEX_ARRAY | F_ARRAY_BUFFER | F_ELEMENT_BUFFER;
            default: throw new IllegalStateException("No fields for slot " + slot);
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

    /**
     * Diagnostic switch: every outermost scope re-reads what it declares, inside a host section too.
     * {@code -Dcrystalgraphics.state.rereadEachScope=true}
     *
     * <p>Rules out the trust a section gives, for a host that runs foreign GL inside one without
     * {@code hostForeign}, and measures what that trust saves.</p>
     */
    private static final boolean REREAD_EACH_SCOPE = Boolean.getBoolean("crystalgraphics.state.rereadEachScope");

    private static final CgTraceChannel GL = CgTrace.channel("crystalgraphics.gl");
    private static final int ADOPT = CgTrace.name("glState.adopt");
    private static final int ADOPT_COUNT = CgTrace.name("glState.adopt.count");

    /** {@code -Dcrystalgraphics.state.roundTrip=true}; null when off. @see RoundTrip */
    private final RoundTrip roundTrip = Boolean.getBoolean("crystalgraphics.state.roundTrip") ? new RoundTrip() : null;

    /** Set by {@link #verifyAgainst}; null when verification is off, which is the normal case. */
    private CgGlStateProvider truth;
    private boolean verifying;
    private int verifyReported;
    private final CgGlStateShadow verifyTracked = new CgGlStateShadow();
    private final CgGlStateShadow verifyActual = new CgGlStateShadow();

    private final CgGlStateShadow current = new CgGlStateShadow();
    private long unknownFields = ALL_FIELDS;
    private int unknownUnits = ALL_UNITS;
    private Thread owner;

    private CgGlStateProvider provider;

    /** Set while a {@link CgGlRecording} captures: every write issues, and scopes and invalidations are recorded. */
    private CgGlRecording recording;
    private final CgGlStateShadow liveCurrent = new CgGlStateShadow();
    private long liveUnknownFields;
    private int liveUnknownUnits;

    private final Frame[] frames = new Frame[MAX_DEPTH];
    private int depth;

    /**
     * Diagnostics, read by {@code core} and recorded there — plain fields rather than a callback for
     * four counters.
     */
    public long callsIssued, callsSkipped, adopted, disagreements;

    public CgGlStateManager(CgGlStateProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        this.provider = provider;
        for (int i = 0; i < MAX_DEPTH; i++) frames[i] = new Frame();
        if (Boolean.getBoolean("crystalgraphics.state.verify")) truth = CgGlStateProvider.glGet();
    }

    /**
     * Checks the shadow against {@code truth} before trusting it for any decision. Diagnosis only: a
     * {@code glGet} per decision.
     *
     * <pre>{@code
     * -Dcrystalgraphics.state.verify=true          // against the driver, from the first frame
     * manager.verifyAgainst(stubProvider);         // a test's own truth
     * manager.verifyAgainst(null);                 // off
     * }</pre>
     *
     * <p>A disagreement is logged once per domain, tracked beside actual, counted in {@link #disagreements},
     * and the shadow takes the actual value — so the decision that follows is made against the truth, and a
     * run with this on renders correctly while it reports. {@code FBO} and {@code PROGRAM} are never elided,
     * so never checked.</p>
     */
    public void verifyAgainst(CgGlStateProvider truth) {
        this.truth = truth;
    }

    public void setProvider(CgGlStateProvider p) {
        if (p == null) throw new IllegalArgumentException("provider must not be null");
        this.provider = p;
        // Values vouched for by the previous provider describe a state this one has not confirmed.
        invalidateAll();
    }

    // ── Trust ─────────────────────────────────────────────────────────────────

    /** Whether every field of {@code slot} is known — which only a scope's read or a write of each one gives. */
    public boolean isTrusted(CgGlSlot slot) {
        return (unknownFields & SLOT_FIELDS[slot.ordinal()]) == 0 && (slot != CgGlSlot.TEXTURES || unknownUnits == 0);
    }

    /** The bound draw framebuffer as the shadow knows it, or -1 when it does not. Asks the driver nothing. */
    public int knownDrawFramebuffer() {
        return (unknownFields & F_DRAW_FBO) == 0 ? current.drawFbo : -1;
    }

    /**
     * The scissor as the shadow knows it: 1 with {@code box} filled ({@code x, y, w, h}, GL's bottom-left pixels)
     * when the test is on, 0 when it is off, -1 when the shadow does not know. Asks the driver nothing.
     */
    public int knownScissor(int[] box) {
        if ((unknownFields & F_SCISSOR_TEST) != 0) return -1;
        if (!current.scissorTest) return 0;
        if ((unknownFields & F_SCISSOR_BOX) != 0) return -1;
        box[0] = current.scissorX;
        box[1] = current.scissorY;
        box[2] = current.scissorW;
        box[3] = current.scissorH;
        return 1;
    }

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

    /** Whether a write of {@code fields} must reach the driver regardless of what the shadow holds. */
    private boolean stale(CgGlSlot slot, long fields) {
        if (mustIssue(slot) || (unknownFields & fields) != 0) return true;
        if (truth != null) verify(slot);
        return false;
    }

    private boolean staleUnit(int unit) {
        if (mustIssue(CgGlSlot.TEXTURES) || (unknownUnits & (1 << unit)) != 0) return true;
        if (truth != null) verify(CgGlSlot.TEXTURES);
        return false;
    }

    private boolean mustIssue(CgGlSlot slot) {
        // `verifying`: the read in verify() steps the active texture unit through CgGL, and those calls must
        // reach the driver rather than be judged against the shadow being checked.
        return (DEDUP_EXEMPT & (1 << slot.ordinal())) != 0 || NO_DEDUP || forcing || verifying || recording != null;
    }

    private void verify(CgGlSlot slot) {
        verifying = true;
        try {
            verifyTracked.copyFrom(current);
            verifyActual.copyFrom(current);
            truth.read(slot, verifyActual);
            // Also undoes what the read's own CgGL calls wrote into `current`.
            current.copyFrom(verifyActual);
            excuseUntrusted(verifyTracked, verifyActual);
            String diff = verifyTracked.differences(verifyActual);
            if (diff == null) return;
            disagreements++;
            int bit = 1 << slot.ordinal();
            if ((verifyReported & bit) == 0) {
                verifyReported |= bit;
                System.err.println("[crystalgraphics] state.verify: " + slot + " disagrees with the driver: " + diff);
                // Where it was noticed, not who wrote it -- the writer is whatever ran since this domain's
                // last CgGL write, which is usually one frame of the stack below.
                new Throwable("state.verify: " + slot).printStackTrace();
            }
        } finally {
            verifying = false;
        }
    }

    /** Copies every field the shadow does not vouch for from {@code actual}, so only a trusted field can disagree. */
    private void excuseUntrusted(CgGlStateShadow t, CgGlStateShadow a) {
        long u = unknownFields;
        if ((u & F_BLEND_ENABLE) != 0) t.blendEnabled = a.blendEnabled;
        if ((u & F_BLEND_FUNC) != 0) {
            t.blendSrcRgb = a.blendSrcRgb; t.blendDstRgb = a.blendDstRgb;
            t.blendSrcAlpha = a.blendSrcAlpha; t.blendDstAlpha = a.blendDstAlpha;
        }
        if ((u & F_BLEND_EQUATION) != 0) { t.blendEqRgb = a.blendEqRgb; t.blendEqAlpha = a.blendEqAlpha; }
        if ((u & F_DEPTH_TEST) != 0) t.depthTest = a.depthTest;
        if ((u & F_DEPTH_MASK) != 0) t.depthMask = a.depthMask;
        if ((u & F_DEPTH_FUNC) != 0) t.depthFunc = a.depthFunc;
        if ((u & F_CULL_ENABLE) != 0) t.cullEnabled = a.cullEnabled;
        if ((u & F_CULL_FACE) != 0) t.cullFace = a.cullFace;
        if ((u & F_FRONT_FACE) != 0) t.frontFace = a.frontFace;
        if ((u & F_STENCIL_TEST) != 0) t.stencilTest = a.stencilTest;
        if ((u & F_STENCIL_FUNC) != 0) {
            t.stencilFunc = a.stencilFunc; t.stencilRef = a.stencilRef; t.stencilValueMask = a.stencilValueMask;
        }
        if ((u & F_STENCIL_OP) != 0) {
            t.stencilFail = a.stencilFail; t.stencilZFail = a.stencilZFail; t.stencilZPass = a.stencilZPass;
        }
        if ((u & F_STENCIL_MASK) != 0) t.stencilWriteMask = a.stencilWriteMask;
        if ((u & F_ALPHA_TEST) != 0) t.alphaTest = a.alphaTest;
        if ((u & F_ALPHA_FUNC) != 0) { t.alphaFunc = a.alphaFunc; t.alphaRef = a.alphaRef; }
        if ((u & F_COLOR_MASK) != 0) t.colorMaskPacked = a.colorMaskPacked;
        if ((u & F_VIEWPORT) != 0) {
            t.viewportX = a.viewportX; t.viewportY = a.viewportY; t.viewportW = a.viewportW; t.viewportH = a.viewportH;
        }
        if ((u & F_SCISSOR_TEST) != 0) t.scissorTest = a.scissorTest;
        if ((u & F_SCISSOR_BOX) != 0) {
            t.scissorX = a.scissorX; t.scissorY = a.scissorY; t.scissorW = a.scissorW; t.scissorH = a.scissorH;
        }
        if ((u & F_OFFSET_FILL) != 0) t.polygonOffsetFill = a.polygonOffsetFill;
        if ((u & F_OFFSET_LINE) != 0) t.polygonOffsetLine = a.polygonOffsetLine;
        if ((u & F_OFFSET_POINT) != 0) t.polygonOffsetPoint = a.polygonOffsetPoint;
        if ((u & F_OFFSET_VALUES) != 0) {
            t.polygonOffsetFactor = a.polygonOffsetFactor; t.polygonOffsetUnits = a.polygonOffsetUnits;
        }
        if ((u & F_MODE_FRONT) != 0) t.polygonModeFront = a.polygonModeFront;
        if ((u & F_MODE_BACK) != 0) t.polygonModeBack = a.polygonModeBack;
        if ((u & F_LINE_WIDTH) != 0) t.lineWidth = a.lineWidth;
        if ((u & F_POINT_SIZE) != 0) t.pointSize = a.pointSize;
        if ((u & F_PROGRAM) != 0) t.programId = a.programId;
        if ((u & F_DRAW_FBO) != 0) t.drawFbo = a.drawFbo;
        if ((u & F_READ_FBO) != 0) t.readFbo = a.readFbo;
        if ((u & F_ACTIVE_TEXTURE) != 0) t.activeTextureUnit = a.activeTextureUnit;
        if ((u & F_VERTEX_ARRAY) != 0) t.vertexArray = a.vertexArray;
        if ((u & F_ARRAY_BUFFER) != 0) t.arrayBuffer = a.arrayBuffer;
        if ((u & F_ELEMENT_BUFFER) != 0) t.elementArrayBuffer = a.elementArrayBuffer;
        for (int unit = 0; unit < CgGlStateShadow.MAX_TEXTURE_UNITS; unit++) {
            if ((unknownUnits & (1 << unit)) != 0) t.boundTexture2D[unit] = a.boundTexture2D[unit];
        }
    }

    /**
     * Suspends deduplication while a domain that is not wholly trusted is being restored, so every field of
     * it reaches the driver. Scoped to one {@code reissue} of one domain; restoring a domain nobody disturbed
     * still costs zero GL calls.
     */
    private boolean forcing;

    /** Set while a scope restores: its values are what GL held, which {@link CgGL} must issue as they are. */
    private boolean restoring;

    boolean restoring() { return restoring; }

    private boolean issue(long fields) { unknownFields &= ~fields; callsIssued++; wrote(fields); return true; }

    /** Set while a scope adopts: the texture read moves the active unit, which is not a write of ours. */
    private boolean adopting;

    /** The highest texture unit our own code has selected: a unit above it is one we cannot have disturbed. */
    private int highestUnit;

    /** Tells the round trip what reached the driver, when it is on and the write is the code under test. */
    private void wrote(long fields) {
        if (roundTrip != null && !restoring && !verifying && !adopting && recording == null) roundTrip.wrote(fields);
    }

    private boolean skip() { callsSkipped++; return false; }

    /** Marks domains untrustworthy without touching GL. For boundaries that are not scopes. */
    public void invalidate(CgGlSlot... slots) {
        if (recording != null) recording.recordInvalidate(slots);
        forget(slots);
    }

    private void forget(CgGlSlot... slots) {
        for (CgGlSlot s : slots) {
            unknownFields |= SLOT_FIELDS[s.ordinal()];
            if (s == CgGlSlot.TEXTURES) unknownUnits = ALL_UNITS;
        }
    }

    public void invalidateAll() {
        if (recording != null) recording.recordInvalidateAll();
        unknownFields = ALL_FIELDS;
        unknownUnits = ALL_UNITS;
    }

    /**
     * Whether this thread may touch GL state at all — true before anything has claimed it.
     *
     * <p>The question {@link #assertOwner} answers with an exception, asked in advance. A caller that
     * is merely FORWARDED to from a foreign thread needs to decline rather than throw: on 1.7.10 FML's
     * splash thread calls {@code Minecraft.resize} while the client thread already owns the shadow, and
     * an exception there kills the splash thread mid-frame so it never releases the GL context —
     * which surfaces, three layers away, as {@code SplashProgress.finish} failing to make the context
     * current and taking the game down with it.</p>
     */
    public boolean ownedByCurrentThread() {
        Thread claimed = owner;
        return claimed == null || claimed == Thread.currentThread();
    }

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
        if (cap == CgGL.GL_BLEND)        { if (!flagChanged(CgGlSlot.BLEND, F_BLEND_ENABLE, current.blendEnabled, enable)) return false; current.blendEnabled = enable; return issue(F_BLEND_ENABLE); }
        if (cap == CgGL.GL_DEPTH_TEST)   { if (!flagChanged(CgGlSlot.DEPTH, F_DEPTH_TEST, current.depthTest, enable)) return false; current.depthTest = enable; return issue(F_DEPTH_TEST); }
        if (cap == CgGL.GL_CULL_FACE)    { if (!flagChanged(CgGlSlot.CULL, F_CULL_ENABLE, current.cullEnabled, enable)) return false; current.cullEnabled = enable; return issue(F_CULL_ENABLE); }
        if (cap == CgGL.GL_STENCIL_TEST) { if (!flagChanged(CgGlSlot.STENCIL, F_STENCIL_TEST, current.stencilTest, enable)) return false; current.stencilTest = enable; return issue(F_STENCIL_TEST); }
        if (cap == CgGL.GL_ALPHA_TEST)   { if (!flagChanged(CgGlSlot.ALPHA_TEST, F_ALPHA_TEST, current.alphaTest, enable)) return false; current.alphaTest = enable; return issue(F_ALPHA_TEST); }
        if (cap == CgGL.GL_SCISSOR_TEST) { if (!flagChanged(CgGlSlot.SCISSOR, F_SCISSOR_TEST, current.scissorTest, enable)) return false; current.scissorTest = enable; return issue(F_SCISSOR_TEST); }
        if (cap == CgGL.GL_POLYGON_OFFSET_FILL)
            { if (!flagChanged(CgGlSlot.POLYGON_OFFSET, F_OFFSET_FILL, current.polygonOffsetFill, enable)) return false; current.polygonOffsetFill = enable; return issue(F_OFFSET_FILL); }
        if (cap == CgGL.GL_POLYGON_OFFSET_LINE)
            { if (!flagChanged(CgGlSlot.POLYGON_OFFSET, F_OFFSET_LINE, current.polygonOffsetLine, enable)) return false; current.polygonOffsetLine = enable; return issue(F_OFFSET_LINE); }
        if (cap == CgGL.GL_POLYGON_OFFSET_POINT)
            { if (!flagChanged(CgGlSlot.POLYGON_OFFSET, F_OFFSET_POINT, current.polygonOffsetPoint, enable)) return false; current.polygonOffsetPoint = enable; return issue(F_OFFSET_POINT); }
        // An untracked capability. Always issue — we cannot say whether it is redundant, and guessing that
        // it is would drop a real call.
        return true;
    }

    private boolean flagChanged(CgGlSlot slot, long field, boolean held, boolean wanted) {
        return stale(slot, field) || held != wanted;
    }

    public boolean blendFuncChanged(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        assertOwner();
        if (!stale(CgGlSlot.BLEND, F_BLEND_FUNC)
                && current.blendSrcRgb == srcRgb && current.blendDstRgb == dstRgb
                && current.blendSrcAlpha == srcAlpha && current.blendDstAlpha == dstAlpha) return skip();
        current.blendSrcRgb = srcRgb; current.blendDstRgb = dstRgb;
        current.blendSrcAlpha = srcAlpha; current.blendDstAlpha = dstAlpha;
        return issue(F_BLEND_FUNC);
    }

    public boolean blendEquationChanged(int modeRgb, int modeAlpha) {
        assertOwner();
        if (!stale(CgGlSlot.BLEND, F_BLEND_EQUATION)
                && current.blendEqRgb == modeRgb && current.blendEqAlpha == modeAlpha) return skip();
        current.blendEqRgb = modeRgb; current.blendEqAlpha = modeAlpha;
        return issue(F_BLEND_EQUATION);
    }

    public boolean depthMaskChanged(boolean flag) {
        assertOwner();
        if (!stale(CgGlSlot.DEPTH, F_DEPTH_MASK) && current.depthMask == flag) return skip();
        current.depthMask = flag;
        return issue(F_DEPTH_MASK);
    }

    public boolean depthFuncChanged(int func) {
        assertOwner();
        if (!stale(CgGlSlot.DEPTH, F_DEPTH_FUNC) && current.depthFunc == func) return skip();
        current.depthFunc = func;
        return issue(F_DEPTH_FUNC);
    }

    public boolean cullFaceChanged(int mode) {
        assertOwner();
        if (!stale(CgGlSlot.CULL, F_CULL_FACE) && current.cullFace == mode) return skip();
        current.cullFace = mode;
        return issue(F_CULL_FACE);
    }

    public boolean frontFaceChanged(int mode) {
        assertOwner();
        if (!stale(CgGlSlot.CULL, F_FRONT_FACE) && current.frontFace == mode) return skip();
        current.frontFace = mode;
        return issue(F_FRONT_FACE);
    }

    public boolean stencilFuncChanged(int func, int ref, int mask) {
        assertOwner();
        if (!stale(CgGlSlot.STENCIL, F_STENCIL_FUNC) && current.stencilFunc == func
                && current.stencilRef == ref && current.stencilValueMask == mask) return skip();
        current.stencilFunc = func; current.stencilRef = ref; current.stencilValueMask = mask;
        return issue(F_STENCIL_FUNC);
    }

    public boolean stencilOpChanged(int sfail, int dpfail, int dppass) {
        assertOwner();
        if (!stale(CgGlSlot.STENCIL, F_STENCIL_OP) && current.stencilFail == sfail
                && current.stencilZFail == dpfail && current.stencilZPass == dppass) return skip();
        current.stencilFail = sfail; current.stencilZFail = dpfail; current.stencilZPass = dppass;
        return issue(F_STENCIL_OP);
    }

    public boolean stencilMaskChanged(int mask) {
        assertOwner();
        if (!stale(CgGlSlot.STENCIL, F_STENCIL_MASK) && current.stencilWriteMask == mask) return skip();
        current.stencilWriteMask = mask;
        return issue(F_STENCIL_MASK);
    }

    public boolean alphaFuncChanged(int func, float ref) {
        assertOwner();
        if (!stale(CgGlSlot.ALPHA_TEST, F_ALPHA_FUNC) && current.alphaFunc == func && current.alphaRef == ref) return skip();
        current.alphaFunc = func; current.alphaRef = ref;
        return issue(F_ALPHA_FUNC);
    }

    public boolean colorMaskChanged(boolean r, boolean g, boolean b, boolean a) {
        assertOwner();
        int nibble = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        int packed = 0;
        for (int t = 0; t < 8; t++) packed |= nibble << (t * 4);
        if (!stale(CgGlSlot.COLOR_MASK, F_COLOR_MASK) && current.colorMaskPacked == packed) return skip();
        current.colorMaskPacked = packed;
        return issue(F_COLOR_MASK);
    }

    public boolean colorMaskiChanged(int buf, boolean r, boolean g, boolean b, boolean a) {
        assertOwner();
        if (buf < 0 || buf >= 8) return true;                 // outside what we model; always issue
        int shift = buf * 4;
        int nibble = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        int packed = (current.colorMaskPacked & ~(0xF << shift)) | (nibble << shift);
        if (!stale(CgGlSlot.COLOR_MASK, F_COLOR_MASK) && current.colorMaskPacked == packed) return skip();
        current.colorMaskPacked = packed;
        // One target of eight vouches for nothing about the other seven, so trust is left as it was.
        callsIssued++;
        wrote(F_COLOR_MASK);
        return true;
    }

    public boolean viewportChanged(int x, int y, int w, int h) {
        assertOwner();
        if (!stale(CgGlSlot.VIEWPORT, F_VIEWPORT) && current.viewportX == x && current.viewportY == y
                && current.viewportW == w && current.viewportH == h) return skip();
        current.viewportX = x; current.viewportY = y; current.viewportW = w; current.viewportH = h;
        return issue(F_VIEWPORT);
    }

    public boolean scissorChanged(int x, int y, int w, int h) {
        assertOwner();
        if (!stale(CgGlSlot.SCISSOR, F_SCISSOR_BOX) && current.scissorX == x && current.scissorY == y
                && current.scissorW == w && current.scissorH == h) return skip();
        current.scissorX = x; current.scissorY = y; current.scissorW = w; current.scissorH = h;
        return issue(F_SCISSOR_BOX);
    }

    public boolean polygonOffsetChanged(float factor, float units) {
        assertOwner();
        if (!stale(CgGlSlot.POLYGON_OFFSET, F_OFFSET_VALUES)
                && current.polygonOffsetFactor == factor && current.polygonOffsetUnits == units) return skip();
        current.polygonOffsetFactor = factor; current.polygonOffsetUnits = units;
        return issue(F_OFFSET_VALUES);
    }

    public boolean polygonModeChanged(int face, int mode) {
        assertOwner();
        boolean front = face == CgGL.GL_FRONT || face == CgGL.GL_FRONT_AND_BACK;
        boolean back  = face == CgGL.GL_BACK  || face == CgGL.GL_FRONT_AND_BACK;
        long fields = (front ? F_MODE_FRONT : 0) | (back ? F_MODE_BACK : 0);
        boolean same = !stale(CgGlSlot.POLYGON_MODE, fields)
                && (!front || current.polygonModeFront == mode)
                && (!back  || current.polygonModeBack  == mode);
        if (same) return skip();
        if (front) current.polygonModeFront = mode;
        if (back)  current.polygonModeBack  = mode;
        return issue(fields);
    }

    public boolean lineWidthChanged(float width) {
        assertOwner();
        if (!stale(CgGlSlot.LINE_WIDTH, F_LINE_WIDTH) && current.lineWidth == width) return skip();
        current.lineWidth = width;
        return issue(F_LINE_WIDTH);
    }

    public boolean pointSizeChanged(float size) {
        assertOwner();
        if (!stale(CgGlSlot.POINT_SIZE, F_POINT_SIZE) && current.pointSize == size) return skip();
        current.pointSize = size;
        return issue(F_POINT_SIZE);
    }

    public boolean programChanged(int program) {
        assertOwner();
        if (!stale(CgGlSlot.PROGRAM, F_PROGRAM) && current.programId == program) return skip();
        current.programId = program;
        return issue(F_PROGRAM);
    }

    public boolean fboChanged(int target, int fbo) {
        assertOwner();
        boolean draw = target != CgGL.GL_READ_FRAMEBUFFER;
        boolean read = target != CgGL.GL_DRAW_FRAMEBUFFER;
        long fields = (draw ? F_DRAW_FBO : 0) | (read ? F_READ_FBO : 0);

        if (!stale(CgGlSlot.FBO, fields)
                && (!draw || current.drawFbo == fbo) && (!read || current.readFbo == fbo)) return skip();

        if (draw) current.drawFbo = fbo;
        if (read) current.readFbo = fbo;
        return issue(fields);
    }

    // -- Deletions ----------------------------------------------------------------------------------

    /**
     * Forgets a deleted texture, so the next bind of a recycled id is issued rather than elided.
     *
     * <pre>{@code
     * CgGL.glDeleteTextures(id);   // calls this for you
     * }</pre>
     *
     * <p>GL unbinds a deleted object and may hand the same id straight back from the next {@code glGen},
     * so an id left in the shadow makes the next bind of a DIFFERENT object look redundant. The unit is
     * then left bound to nothing, which samples as opaque black -- and under premultiplied {@code over}
     * that erases what is behind it rather than drawing nothing. Zero is what GL itself reverts the
     * binding to, so the shadow stays true rather than merely cautious.</p>
     *
     * <p>Same rule as {@link #vertexArrayChanged}: what can no longer be trusted is dropped.</p>
     */
    public void textureDeleted(int texture) {
        if (texture == 0) return;
        for (int unit = 0; unit < CgGlStateShadow.MAX_TEXTURE_UNITS; unit++) {
            if (current.boundTexture2D[unit] == texture) current.boundTexture2D[unit] = 0;
        }
    }

    /** @see #textureDeleted */
    public void framebufferDeleted(int fbo) {
        if (fbo == 0) return;
        if (current.drawFbo == fbo) current.drawFbo = 0;
        if (current.readFbo == fbo) current.readFbo = 0;
    }

    /** @see #textureDeleted */
    public void bufferDeleted(int buffer) {
        if (buffer == 0) return;
        if (current.arrayBuffer == buffer) current.arrayBuffer = 0;
        if (current.elementArrayBuffer == buffer) current.elementArrayBuffer = 0;
    }

    /** @see #textureDeleted */
    public void vertexArrayDeleted(int array) {
        if (array == 0) return;
        if (current.vertexArray != array) return;
        current.vertexArray = 0;
        // The element binding belonged to the deleted VAO; VAO 0 carries its own and we never saw it.
        current.elementArrayBuffer = CgGlStateShadow.UNKNOWN_BINDING;
    }

    /** @see #textureDeleted */
    public void programDeleted(int program) {
        if (program == 0) return;
        if (current.programId == program) current.programId = 0;
    }

    public boolean activeTextureChanged(int texture) {
        assertOwner();
        int unit = texture - CgGL.GL_TEXTURE0;
        if (unit < 0 || unit >= CgGlStateShadow.MAX_TEXTURE_UNITS) return true;
        if (unit > highestUnit && !verifying && !adopting) highestUnit = unit;
        if (!stale(CgGlSlot.TEXTURES, F_ACTIVE_TEXTURE) && current.activeTextureUnit == unit) return skip();
        current.activeTextureUnit = unit;
        return issue(F_ACTIVE_TEXTURE);
    }

    public boolean textureChanged(int target, int texture) {
        assertOwner();
        // Only GL_TEXTURE_2D is modelled; other targets are always issued rather than assumed redundant.
        if (target != CgGL.GL_TEXTURE_2D) return true;
        if ((unknownFields & F_ACTIVE_TEXTURE) != 0) {
            // Which unit this lands on is unknown, so whatever any unit's binding was believed to be may
            // now be wrong.
            unknownUnits = ALL_UNITS;
            callsIssued++;
            wrote(F_ACTIVE_TEXTURE);
            return true;
        }
        int unit = current.activeTextureUnit;
        if (unit < 0 || unit >= CgGlStateShadow.MAX_TEXTURE_UNITS) return true;
        if (!staleUnit(unit) && current.boundTexture2D[unit] == texture) return skip();
        current.boundTexture2D[unit] = texture;
        unknownUnits &= ~(1 << unit);
        callsIssued++;
        wrote(F_ACTIVE_TEXTURE);   // the TEXTURES domain; a unit's binding has no field bit of its own
        return true;
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
        if (!stale(CgGlSlot.VERTEX_INPUT, F_VERTEX_ARRAY) && current.vertexArray == array) return skip();
        current.vertexArray = array;
        current.elementArrayBuffer = CgGlStateShadow.UNKNOWN_BINDING;
        unknownFields |= F_ELEMENT_BUFFER;
        return issue(F_VERTEX_ARRAY);
    }

    public boolean bufferChanged(int target, int buffer) {
        assertOwner();
        if (target == CgGL.GL_ARRAY_BUFFER) {
            if (!stale(CgGlSlot.VERTEX_INPUT, F_ARRAY_BUFFER) && current.arrayBuffer == buffer) return skip();
            current.arrayBuffer = buffer;
            return issue(F_ARRAY_BUFFER);
        }
        if (target == CgGL.GL_ELEMENT_ARRAY_BUFFER) {
            if (!stale(CgGlSlot.VERTEX_INPUT, F_ELEMENT_BUFFER) && current.elementArrayBuffer == buffer) return skip();
            current.elementArrayBuffer = buffer;
            return issue(F_ELEMENT_BUFFER);
        }
        return true;    // uniform/shader-storage etc. — not modelled, always issued
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /** Sets the live shadow aside; the recording starts knowing nothing, and no write is elided. */
    void beginRecording(CgGlRecording r) {
        assertOwner();
        if (recording != null) throw new IllegalStateException("A CgGlRecording is already capturing");
        liveCurrent.copyFrom(current);
        liveUnknownFields = unknownFields;
        liveUnknownUnits = unknownUnits;
        unknownFields = ALL_FIELDS;
        unknownUnits = ALL_UNITS;
        recording = r;
    }

    /** Puts the live shadow back. It is still true: nothing a recording captures reaches the driver. */
    void endRecording() {
        recording = null;
        current.copyFrom(liveCurrent);
        unknownFields = liveUnknownFields;
        unknownUnits = liveUnknownUnits;
    }

    /** A recorded scope closed: what it restores is known only on replay. */
    void forgetRecorded(boolean foreign, CgGlSlot... slots) {
        if (foreign) {
            unknownFields = ALL_FIELDS;
            unknownUnits = ALL_UNITS;
        } else {
            forget(slots);
        }
    }

    /** Whether a query made while recording is about state the recording itself set. */
    boolean recordedSets(int pname) {
        switch (pname) {
            case CgGL.GL_DRAW_FRAMEBUFFER_BINDING: return (unknownFields & F_DRAW_FBO) == 0;
            case CgGL.GL_READ_FRAMEBUFFER_BINDING: return (unknownFields & F_READ_FBO) == 0;
            case CgGL.GL_CURRENT_PROGRAM:          return (unknownFields & F_PROGRAM) == 0;
            case CgGL.GL_VERTEX_ARRAY_BINDING:     return (unknownFields & F_VERTEX_ARRAY) == 0;
            case CgGL.GL_ARRAY_BUFFER_BINDING:     return (unknownFields & F_ARRAY_BUFFER) == 0;
            case CgGL.GL_ACTIVE_TEXTURE:           return (unknownFields & F_ACTIVE_TEXTURE) == 0;
            case CgGL.GL_TEXTURE_BINDING_2D:
                return (unknownFields & F_ACTIVE_TEXTURE) == 0 && (unknownUnits & (1 << current.activeTextureUnit)) == 0;
            case CgGL.GL_BLEND:        return (unknownFields & F_BLEND_ENABLE) == 0;
            case CgGL.GL_DEPTH_TEST:   return (unknownFields & F_DEPTH_TEST) == 0;
            case CgGL.GL_CULL_FACE:    return (unknownFields & F_CULL_ENABLE) == 0;
            case CgGL.GL_SCISSOR_TEST: return (unknownFields & F_SCISSOR_TEST) == 0;
            case CgGL.GL_STENCIL_TEST: return (unknownFields & F_STENCIL_TEST) == 0;
            default: return false;
        }
    }

    /**
     * A query made while recording, answered from what the recording itself set.
     *
     * @throws IllegalStateException unless {@link #recordedSets} answers true for it
     */
    int recordedInteger(int pname) {
        switch (pname) {
            case CgGL.GL_DRAW_FRAMEBUFFER_BINDING: return recorded(F_DRAW_FBO, pname, current.drawFbo);
            case CgGL.GL_READ_FRAMEBUFFER_BINDING: return recorded(F_READ_FBO, pname, current.readFbo);
            case CgGL.GL_CURRENT_PROGRAM:          return recorded(F_PROGRAM, pname, current.programId);
            case CgGL.GL_VERTEX_ARRAY_BINDING:     return recorded(F_VERTEX_ARRAY, pname, current.vertexArray);
            case CgGL.GL_ARRAY_BUFFER_BINDING:     return recorded(F_ARRAY_BUFFER, pname, current.arrayBuffer);
            case CgGL.GL_ACTIVE_TEXTURE:
                return CgGL.GL_TEXTURE0 + recorded(F_ACTIVE_TEXTURE, pname, current.activeTextureUnit);
            case CgGL.GL_TEXTURE_BINDING_2D: {
                int unit = recorded(F_ACTIVE_TEXTURE, pname, current.activeTextureUnit);
                if ((unknownUnits & (1 << unit)) != 0) throw notRecorded(pname);
                return current.boundTexture2D[unit];
            }
            default:
                throw notRecorded(pname);
        }
    }

    /** @see #recordedInteger */
    boolean recordedBoolean(int pname) {
        switch (pname) {
            case CgGL.GL_BLEND:        return recorded(F_BLEND_ENABLE, pname, current.blendEnabled ? 1 : 0) != 0;
            case CgGL.GL_DEPTH_TEST:   return recorded(F_DEPTH_TEST, pname, current.depthTest ? 1 : 0) != 0;
            case CgGL.GL_CULL_FACE:    return recorded(F_CULL_ENABLE, pname, current.cullEnabled ? 1 : 0) != 0;
            case CgGL.GL_SCISSOR_TEST: return recorded(F_SCISSOR_TEST, pname, current.scissorTest ? 1 : 0) != 0;
            case CgGL.GL_STENCIL_TEST: return recorded(F_STENCIL_TEST, pname, current.stencilTest ? 1 : 0) != 0;
            default:
                throw notRecorded(pname);
        }
    }

    private int recorded(long field, int pname, int value) {
        if ((unknownFields & field) != 0) throw notRecorded(pname);
        return value;
    }

    private static IllegalStateException notRecorded(int pname) {
        return new IllegalStateException("0x" + Integer.toHexString(pname) + " was not set by this recording");
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

    /**
     * Declares writes <strong>meant to stay</strong>: state set for the host, which a scope would undo.
     *
     * <pre>{@code
     * try (CgGlScope s = CgGlState.handOver(CgGlSlot.FBO, CgGlSlot.VIEWPORT)) {
     *     CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, minecraftMainTarget);   // what the host draws next into
     *     CgGL.glViewport(0, 0, width, height);
     * }   // nothing restored
     * }</pre>
     *
     * <p>Closing restores nothing. What it buys is the declaration: the round trip's leak report treats
     * these domains as intended rather than as a write nobody will undo, so a leak that is left is one
     * nobody meant.</p>
     */
    public CgGlScope handOver(CgGlSlot... slots) {
        return open(false, true, slots);
    }

    /**
     * Runs {@code body} — foreign drawing, GL written behind {@link CgGL}'s back — inside a {@link #hostForeign}
     * scope. While a {@link CgGlRecording} captures, the body is recorded and runs on replay instead, in order.
     */
    public void hostForeign(Runnable body, CgGlSlot... slots) {
        if (recording != null) {
            recording.recordForeign(body, slots);
            unknownFields = ALL_FIELDS;
            unknownUnits = ALL_UNITS;
            return;
        }
        try (CgGlScope ignored = hostForeign(slots)) {
            body.run();
        }
    }

    private CgGlScope open(boolean foreign, CgGlSlot... slots) {
        return open(foreign, false, slots);
    }

    private CgGlScope open(boolean foreign, boolean handOver, CgGlSlot... slots) {
        assertOwner();
        // A foreign block with nothing declared still has to invalidate on exit, so it needs a real frame.
        if ((slots == null || slots.length == 0) && !foreign) return CgGlScope.NOOP_SCOPE;
        if (slots == null) slots = NO_SLOTS;
        if (recording != null) return recording.recordScope(foreign, slots);
        if (depth == MAX_DEPTH) {
            throw new IllegalStateException(
                    "GL state scope nesting exceeded " + MAX_DEPTH + "; unbalanced save() somewhere");
        }

        Frame f = frames[depth++];
        f.mask = 0;
        f.closed = false;
        f.foreign = foreign;
        f.handOver = handOver;

        // Outermost and outside a host section: the host may have run since we last knew anything, so re-read
        // unconditionally -- trusting the shadow there is the bug that once left blending disabled and every
        // glyph an opaque block. Inside a section only our code touches GL: its outermost fromHost forgot the
        // shadow, and hostForeign forgets it again, so an untrusted slot is all that needs reading. A free
        // provider is read at every depth: trust saves nothing there, and a host rebinding through its own
        // manager defeats it.
        boolean reread = provider.isFree() || (depth == 1 && (REREAD_EACH_SCOPE || !CgGL.inHostSection()));
        for (CgGlSlot slot : slots) {
            int bit = 1 << slot.ordinal();
            if ((f.mask & bit) != 0) continue;
            if (!handOver && (reread || !isTrusted(slot))) adopt(slot);   // a hand-over restores nothing
            f.mask |= bit;
        }
        f.saved.copyFrom(current);
        if (roundTrip != null) roundTrip.opened(f);
        return f;
    }

    private void adopt(CgGlSlot slot) {
        adopting = true;
        long t = CgTrace.stamp(GL);
        try {
            provider.read(slot, current);
        } finally {
            adopting = false;
            CgTrace.zoneDone(GL, ADOPT, t);
            CgTrace.add(GL, ADOPT_COUNT, 1);
        }
        unknownFields &= ~SLOT_FIELDS[slot.ordinal()];
        if (slot == CgGlSlot.TEXTURES) unknownUnits = 0;
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
        /** {@link #handOver}: closing restores nothing. */
        private boolean handOver;
        /** What GL held when this opened, as the host and the driver answer, and who opened it: the round
         *  trip's, allocated only when it is on. */
        private CgGlStateShadow before, beforeDriver;
        private Throwable openedAt;
        /** Texture units the open read covered: a unit first selected inside this scope has no "before". */
        private int unitsAtOpen;

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
            restoring = true;
            try {
                for (CgGlSlot slot : SLOTS) {
                    if (handOver) break;
                    if ((mask & (1 << slot.ordinal())) == 0) continue;
                    // A domain not wholly trusted is re-established in full — see `forcing`. A trusted one takes
                    // the normal deduplicated path and usually emits nothing.
                    forcing = mustIssue(slot) || !isTrusted(slot);
                    try {
                        reissue(slot, saved);
                    } finally {
                        forcing = false;
                    }
                }
            } finally {
                restoring = false;
            }
            if (roundTrip != null) roundTrip.closed(this);
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
                // One call when the faces agree: a core profile accepts only GL_FRONT_AND_BACK.
                if (s.polygonModeFront == s.polygonModeBack) {
                    CgGL.glPolygonMode(CgGL.GL_FRONT_AND_BACK, s.polygonModeFront);
                } else {
                    CgGL.glPolygonMode(CgGL.GL_FRONT, s.polygonModeFront);
                    CgGL.glPolygonMode(CgGL.GL_BACK,  s.polygonModeBack);
                }
                break;
            case LINE_WIDTH: CgGL.glLineWidth(s.lineWidth); break;
            case POINT_SIZE: CgGL.glPointSize(s.pointSize); break;
            case PROGRAM:    CgGL.glUseProgram(s.programId); break;
            case FBO:
                if (s.drawFbo == s.readFbo) {
                    CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, s.drawFbo);   // a matching pair needs one bind
                } else {
                    CgGL.glBindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, s.drawFbo);
                    CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, s.readFbo);
                }
                break;
            case TEXTURES:
                for (int unit = 0; unit < CgGlStateShadow.MAX_TEXTURE_UNITS; unit++) {
                    // A unit is skipped only when its binding is known to match; an unknown one is rebound.
                    if ((unknownUnits & (1 << unit)) == 0 && current.boundTexture2D[unit] == s.boundTexture2D[unit]) continue;
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

    // ── Round trip ────────────────────────────────────────────────────────────

    /** Reaches the driver past a host cache, for {@link RoundTrip}; null where {@code glGet} already does. */
    private CgGlStateProvider driverReader;
    /** Domains the host virtualises, so the driver is not expected to agree with its cache on them. */
    private int virtualised;

    /**
     * Gives the round trip a way to the driver that a host's cache cannot answer for.
     *
     * <pre>{@code
     * // Angelica answers glGet from its cache, and binds programs of its own behind glUseProgram(0)
     * CgGlState.setDriverReader(RawDriverProvider1710.create(), CgGlSlot.PROGRAM);
     * }</pre>
     *
     * @param virtualised domains the host deliberately holds differently from the driver; the driver
     *                    comparisons skip them, while the host view still checks them
     */
    public void setDriverReader(CgGlStateProvider reader, CgGlSlot... virtualised) {
        this.driverReader = reader;
        this.virtualised = 0;
        for (CgGlSlot s : virtualised) this.virtualised |= 1 << s.ordinal();
    }

    /**
     * Proves a scope hands back what it found: {@code -Dcrystalgraphics.state.roundTrip=true}.
     *
     * <pre>{@code
     * [crystalgraphics] state.roundTrip: not restored in the host view (depth 1) by the scope opened at CgUiPaintContext.beginFrame:620 -- viewportW before=2560 after=490
     * }</pre>
     *
     * <p>On open, every declared domain is read; after the close restores, it is read again, and a field
     * that differs is reported with the code that opened the scope. Every depth, not only the outermost: a
     * nested scope that restores wrongly corrupts our own drawing rather than the host's.</p>
     *
     * <p>Two views, where a host has two. The <b>host view</b> is {@code glGet} -- what the host sees next,
     * which under Angelica is Angelica's cache, since every GL call of ours and of Minecraft goes through
     * it. The <b>driver</b> is a {@link #setDriverReader driver reader}, installed only where a cache
     * answers {@code glGet}. With both, each close also compares the two, so a report names which one a
     * scope failed: the driver, the host's cache, or the cache disagreeing with the driver -- which is the
     * host's fault, not ours.</p>
     *
     * <p>And every write that reaches the driver is checked against the domains the open scopes declared.
     * A write outside them is never restored -- a <b>leak</b>, reported with the code that wrote it:</p>
     *
     * <pre>{@code
     * [crystalgraphics] state.roundTrip: leaked cullFace at CgRenderPipeline.executeOpaquePass:340 (depth 1) -- no open scope declares its domain
     * }</pre>
     *
     * <p>This is what makes the host's own state manager safe to reason about: a domain every write of ours
     * restores is one its cache ends where it started, whether the backend routes it through the cache or
     * not.</p>
     *
     * <p>Diagnosis only: two reads per scope, four with a driver reader, a stack per leaked write. A run is
     * judged by its lines, all through log4j into the client's own log: {@code ARMED} at start, totals
     * after 100 scopes and every 1000 after, so a run that never checked cannot pass for one that found
     * nothing.</p>
     */
    private final class RoundTrip {
        private final Logger log = LogManager.getLogger("CrystalGraphics");
        private final CgGlStateShadow after = new CgGlStateShadow();
        private final CgGlStateShadow afterDriver = new CgGlStateShadow();
        private final CgGlStateShadow keep = new CgGlStateShadow();
        private final Set<String> reported = new HashSet<>();
        private long checked, hostFailed, driverFailed, cacheFailed, leaks;

        void wrote(long fields) {
            long declared = 0;
            for (int d = 0; d < depth; d++) {
                int mask = frames[d].mask;
                for (CgGlSlot s : SLOTS) if ((mask & (1 << s.ordinal())) != 0) declared |= SLOT_FIELDS[s.ordinal()];
            }
            long leaked = fields & ~declared;
            if (leaked == 0) return;
            leaks++;
            if (reported.size() >= 100) return;
            Throwable at = new Throwable("written here");
            String names = fieldNames(leaked);
            String site = site(at);
            if (!reported.add("leak|" + site + '|' + names)) return;
            log.warn("[crystalgraphics] state.roundTrip: leaked " + names + " at " + site + " (depth " + depth
                    + ") -- no open scope declares its domain", at);
        }

        RoundTrip() {
            log.info("[crystalgraphics] state.roundTrip: ARMED -- every scope's domains are read on open and "
                    + "again after it restores");
        }

        void opened(Frame f) {
            if (f.handOver) return;
            f.unitsAtOpen = highestUnit + 1;
            if (f.before == null) f.before = new CgGlStateShadow();
            // An outermost scope on plain glGet outside a host section has just adopted every declared domain
            // from the driver; inside one it trusted the shadow, which is what this check exists to question.
            if (depth == 1 && !CgGL.inHostSection() && provider == CgGlStateProvider.glGet()) f.before.copyFrom(f.saved);
            else read(CgGlStateProvider.glGet(), f.mask, f.before);
            if (driverReader != null) {
                if (f.beforeDriver == null) f.beforeDriver = new CgGlStateShadow();
                read(driverReader, f.mask, f.beforeDriver);
            }
            f.openedAt = new Throwable("scope opened here");
        }

        void closed(Frame f) {
            if (f.handOver) return;   // its writes are meant to stay
            read(CgGlStateProvider.glGet(), f.mask, after);
            if (checked++ == 0) {
                log.info("[crystalgraphics] state.roundTrip: first scope checked (depth " + depth
                        + "), driver reader: " + (driverReader != null ? driverReader.getClass().getSimpleName() : "none needed"));
            }
            String where = " (depth " + depth + ")";
            untouchedUnits(f.before, after, f.unitsAtOpen);
            String diff = f.before.differences(after, f.mask);
            if (diff != null) {
                hostFailed++;
                report("not restored in the host view" + where, f.openedAt, diff, "before=", "after=");
            }
            if (driverReader != null) {
                read(driverReader, f.mask, afterDriver);
                untouchedUnits(f.beforeDriver, afterDriver, f.unitsAtOpen);
                untouchedUnits(after, afterDriver, f.unitsAtOpen);
                int real = f.mask & ~virtualised;
                String driverDiff = f.beforeDriver.differences(afterDriver, real);
                if (driverDiff != null) {
                    driverFailed++;
                    report("not restored in the driver" + where, f.openedAt, driverDiff, "before=", "after=");
                }
                String cacheDiff = after.differences(afterDriver, real);
                if (cacheDiff != null) {
                    cacheFailed++;
                    report("host cache disagrees with the driver" + where, f.openedAt, cacheDiff, "host=", "driver=");
                }
            }
            if (checked == 100 || checked % 1000 == 0) {
                log.info("[crystalgraphics] state.roundTrip: " + checked + " scopes checked -- not restored: "
                        + hostFailed + " in the host view, " + driverFailed + " in the driver; host cache "
                        + "disagreed with the driver " + cacheFailed + " times; " + leaks + " leaked writes; "
                        + adopted + " domains adopted");
            }
        }

        /** Reads {@code mask}'s domains without leaving a trace in the shadow or the trust masks. */
        private void read(CgGlStateProvider from, int mask, CgGlStateShadow into) {
            keep.copyFrom(current);
            long fields = unknownFields;
            int units = unknownUnits;
            // The texture read moves the active unit through CgGL; those calls must reach the driver.
            verifying = true;
            try {
                for (CgGlSlot s : SLOTS) {
                    if ((mask & (1 << s.ordinal())) == 0) continue;
                    // Only the units our code has selected: reading all 32 twice per scope was most of the
                    // probe's cost, and a unit we never select is one we cannot have failed to restore.
                    if (s == CgGlSlot.TEXTURES && from instanceof CgGlGetProvider) {
                        ((CgGlGetProvider) from).readTextures(into, highestUnit + 1);
                    } else {
                        from.read(s, into);
                    }
                }
            } finally {
                verifying = false;
                current.copyFrom(keep);
                unknownFields = fields;
                unknownUnits = units;
            }
        }

        /** A field name in a {@link CgGlStateShadow#differences} string: the word before " tracked=". */
        private final Pattern FIELD_IN_DIFF = Pattern.compile("(\\w+) tracked=");

        /** Units from {@code limit} up were not read at open; {@code to} takes {@code from}'s so they agree. */
        private void untouchedUnits(CgGlStateShadow from, CgGlStateShadow to, int limit) {
            for (int u = limit; u < CgGlStateShadow.MAX_TEXTURE_UNITS; u++) {
                to.boundTexture2D[u] = from.boundTexture2D[u];
            }
        }

        /** Once per (what, site, fields): a scope that restores wrongly does so every frame. */
        private void report(String what, Throwable openedAt, String diff, String was, String is) {
            String site = site(openedAt);
            // Names by pattern, not by splitting on ", ": a texture-unit array prints with those inside it.
            StringBuilder fields = new StringBuilder();
            Matcher name = FIELD_IN_DIFF.matcher(diff);
            while (name.find()) fields.append(name.group(1)).append(',');
            if (reported.size() >= 100 || !reported.add(what + '|' + site + '|' + fields)) return;
            String line = "[crystalgraphics] state.roundTrip: " + what + " by the scope opened at " + site
                    + " -- " + diff.replace("tracked=", was).replace("actual=", is);
            if (reported.size() <= 10) log.warn(line, openedAt); else log.warn(line);
        }

        /** Names for the {@code F_*} bits, in bit order. */
        private final String[] fieldName = {
                "blendEnable", "blendFunc", "blendEquation", "depthTest", "depthMask", "depthFunc",
                "cullEnable", "cullFace", "frontFace", "stencilTest", "stencilFunc", "stencilOp", "stencilMask",
                "alphaTest", "alphaFunc", "colorMask", "viewport", "scissorTest", "scissorBox",
                "polygonOffsetFill", "polygonOffsetLine", "polygonOffsetPoint", "polygonOffset",
                "polygonModeFront", "polygonModeBack", "lineWidth", "pointSize", "program", "drawFbo", "readFbo",
                "texture", "vertexArray", "arrayBuffer", "elementBuffer"};

        private String fieldNames(long fields) {
            StringBuilder out = new StringBuilder();
            for (int bit = 0; bit < fieldName.length; bit++) {
                if ((fields & (1L << bit)) != 0) out.append(out.length() == 0 ? "" : ",").append(fieldName[bit]);
            }
            return out.toString();
        }

        /** The first frame outside this package: the code that asked for the scope. */
        private String site(Throwable openedAt) {
            for (StackTraceElement e : openedAt.getStackTrace()) {
                String c = e.getClassName();
                if (c.startsWith("com.crystalgraphics.platform.gl.")) continue;
                return c.substring(c.lastIndexOf('.') + 1) + '.' + e.getMethodName() + ':' + e.getLineNumber();
            }
            return "?";
        }
    }
}
