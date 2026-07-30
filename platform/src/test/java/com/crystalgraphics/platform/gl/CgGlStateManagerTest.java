package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.*;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the deduplication decisions in {@link CgGlStateManager}.
 *
 * <h3>No GL context needed, and that is not an accident</h3>
 * <p>The {@code *Changed} methods are pure: they compare against the shadow, record, and report whether the
 * driver should be told. Nothing in them touches GL — {@link CgGL} makes the call, and only if these say
 * so. That split is what makes the decision logic testable at all.</p>
 *
 * <p>What these cannot cover is whether {@code CgGL} calls them from the right places, or whether
 * {@code reissue} emits the right sequence — both need a live context. Every bug this subsystem has had was
 * a <em>missing</em> GL call, which renders wrongly and never throws, so treat a green suite here as
 * necessary and nowhere near sufficient. {@code -Dcrystalgraphics.state.verify=true} is what closes the
 * rest.</p>
 */
public class CgGlStateManagerTest {

    /** Fills whatever domain is asked for with a recognisable sentinel. */
    private static final class StubProvider implements CgGlStateProvider {
        int reads;

        @Override public void read(CgGlSlot slot, CgGlStateShadow t) {
            reads++;
            switch (slot) {
                case DEPTH: t.depthTest = true; t.depthMask = true; t.depthFunc = 0x0203; break;
                case BLEND: t.blendEnabled = true; t.blendSrcRgb = 1; t.blendDstRgb = 2; break;
                default: break;   // other domains left at their defaults, which is a valid value
            }
        }
    }

    private StubProvider provider;
    private CgGlStateManager mgr;
    private RecordingGlBackend gl;

    @Before
    public void setUp() {
        provider = new StubProvider();
        // Restore re-issues through CgGL, so closing a scope needs a backend. Without one, every test that
        // exercises restore NPEs — which is why restore went untested through two architectures.
        gl = RecordingGlBackend.install();

        // The manager under test must be the one CgGL routes to. Restore deliberately goes back out through
        // CgGL rather than writing the driver directly (so it inherits deduplication and cannot drift from
        // the apply path), and CgGL always resolves the *installed* manager. A standalone instance would
        // therefore have its scopes restored into a different manager — the calls reach the backend, but
        // this object never sees them.
        CgGlState.reset();
        CgGlState.setProvider(provider);
        mgr = CgGlState.manager();
    }

    // ── Deduplication ─────────────────────────────────────────────────────────

    @Test
    public void theFirstWriteAlwaysReachesTheDriver() {
        assertTrue("nothing is trusted yet, so it cannot be redundant", mgr.depthMaskChanged(true));
    }

    @Test
    public void anIdenticalRepeatIsEliminated() {
        assertTrue(mgr.depthMaskChanged(true));
        assertFalse("the second write is provably redundant", mgr.depthMaskChanged(true));
        assertFalse(mgr.depthMaskChanged(true));
    }

    @Test
    public void aChangedValueReachesTheDriverAgain() {
        assertTrue(mgr.depthMaskChanged(true));
        assertTrue(mgr.depthMaskChanged(false));
    }

    @Test
    public void domainsAreIndependent() {
        assertTrue(mgr.depthMaskChanged(true));
        assertTrue(mgr.cullFaceChanged(0x0405));
        assertFalse("depth is still current despite the cull write", mgr.depthMaskChanged(true));
    }

    /**
     * Per-field granularity — the point of the whole V2 move.
     *
     * <p>The previous design compared whole composite values, so setting only the depth <em>mask</em> twice
     * could not be recognised as redundant unless the test and compare function matched too.</p>
     */
    @Test
    public void fieldsWithinADomainAreTrackedSeparately() {
        assertTrue(mgr.depthFuncChanged(0x0201));
        assertTrue(mgr.depthMaskChanged(true));
        assertFalse("the func is unchanged", mgr.depthFuncChanged(0x0201));
        assertFalse("and so is the mask",    mgr.depthMaskChanged(true));
    }

    @Test
    public void multiArgumentWritesCompareEveryArgument() {
        assertTrue(mgr.blendFuncChanged(1, 2, 3, 4));
        assertFalse(mgr.blendFuncChanged(1, 2, 3, 4));
        assertTrue("one differing factor must re-issue", mgr.blendFuncChanged(1, 2, 3, 9));
    }

    /** The high-frequency binding domains dedupe — that is where the call volume justifies the risk. */
    @Test
    public void frequentBindingDomainsDedupe() {
        assertTrue(mgr.vertexArrayChanged(3));
        assertFalse(mgr.vertexArrayChanged(3));
        assertTrue(mgr.textureChanged(CgGL.GL_TEXTURE_2D, 8));
        assertFalse(mgr.textureChanged(CgGL.GL_TEXTURE_2D, 8));
    }

    /**
     * {@code PROGRAM} and {@code FBO} are tracked but deliberately never deduplicated.
     *
     * <p>Both are bound rarely enough that eliminating the call saves nothing measurable, and both are
     * changed behind our back constantly — Minecraft, Iris and every shader mod rebind programs, and a
     * wrong framebuffer binding draws into the wrong target, often producing no visible output at all.
     * Always issuing removes the whole class of failure for the two domains where the bet paid least.</p>
     *
     * <p>If this test starts failing because someone "optimised" the exemption away, read
     * {@code DEDUP_EXEMPT}'s javadoc before agreeing with them.</p>
     */
    @Test
    public void programAndFboAreNeverDeduplicated() {
        assertTrue(mgr.programChanged(7));
        assertTrue("a repeat program bind must still be issued", mgr.programChanged(7));
        assertTrue(mgr.programChanged(7));

        assertTrue(mgr.fboChanged(CgGL.GL_FRAMEBUFFER, 4));
        assertTrue("a repeat FBO bind must still be issued", mgr.fboChanged(CgGL.GL_FRAMEBUFFER, 4));
    }

    /** Exempt domains are still tracked, so a scope has a correct baseline to restore from. */
    @Test
    public void exemptDomainsAreStillTracked() {
        mgr.programChanged(11);
        CgGlScope s = mgr.save(CgGlSlot.PROGRAM);
        mgr.programChanged(22);
        gl.clear();
        s.close();
        assertTrue("restore must re-issue the saved program", gl.sawCall("glUseProgram"));
    }

    /**
     * A VAO switch must forget the element array binding.
     *
     * <p>Regression: {@code GL_ELEMENT_ARRAY_BUFFER} is per-VAO state that {@code glBindVertexArray} swaps
     * without any {@code glBindBuffer} to observe. Treating it as global made the rebind below look
     * redundant, so the IBO was never bound to VAO 2 and the draw died with <em>"Cannot use offsets when
     * Element Array Buffer Object is disabled"</em> — several calls away from the cause.</p>
     */
    @Test
    public void switchingVaoForgetsTheElementArrayBinding() {
        mgr.vertexArrayChanged(1);
        assertTrue(mgr.bufferChanged(CgGL.GL_ELEMENT_ARRAY_BUFFER, 42));
        assertFalse("still inside the same VAO, so this one is genuinely redundant",
                mgr.bufferChanged(CgGL.GL_ELEMENT_ARRAY_BUFFER, 42));

        mgr.vertexArrayChanged(2);
        assertTrue("VAO 2 has its own element binding — this must reach the driver",
                mgr.bufferChanged(CgGL.GL_ELEMENT_ARRAY_BUFFER, 42));
    }

    /** GL_ARRAY_BUFFER is global context state, not captured by a VAO, so a VAO switch must not disturb it. */
    @Test
    public void switchingVaoKeepsTheArrayBufferBinding() {
        mgr.vertexArrayChanged(1);
        assertTrue(mgr.bufferChanged(CgGL.GL_ARRAY_BUFFER, 9));
        mgr.vertexArrayChanged(2);
        assertFalse("GL_ARRAY_BUFFER survives a VAO change untouched",
                mgr.bufferChanged(CgGL.GL_ARRAY_BUFFER, 9));
    }

    /** A redundant VAO bind changes nothing, so it must not discard the element binding either. */
    @Test
    public void aRedundantVaoBindDoesNotForgetTheElementBinding() {
        mgr.vertexArrayChanged(1);
        mgr.bufferChanged(CgGL.GL_ELEMENT_ARRAY_BUFFER, 42);
        assertFalse(mgr.vertexArrayChanged(1));          // no-op
        assertFalse("nothing actually changed, so the binding is still known",
                mgr.bufferChanged(CgGL.GL_ELEMENT_ARRAY_BUFFER, 42));
    }

    // ── Trust ─────────────────────────────────────────────────────────────────

    @Test
    public void everyDomainStartsUntrusted() {
        for (CgGlSlot slot : CgGlSlot.values()) {
            assertFalse(slot + " must start untrusted", mgr.isTrusted(slot));
        }
    }

    @Test
    public void invalidationForcesTheNextWriteThrough() {
        assertTrue(mgr.depthMaskChanged(true));
        assertFalse(mgr.depthMaskChanged(true));
        mgr.invalidate(CgGlSlot.DEPTH);
        assertTrue("trust was lost, so the same value must be re-issued", mgr.depthMaskChanged(true));
    }

    @Test
    public void invalidateAllDropsEveryDomain() {
        mgr.depthMaskChanged(true);
        mgr.programChanged(4);
        mgr.invalidateAll();
        for (CgGlSlot slot : CgGlSlot.values()) assertFalse(mgr.isTrusted(slot));
    }

    // ── Adoption ──────────────────────────────────────────────────────────────

    @Test
    public void anOutermostSaveAdoptsEvenAnAlreadyTrustedDomain() {
        mgr.depthMaskChanged(true);                 // establishes trust
        assertTrue(mgr.isTrusted(CgGlSlot.DEPTH));
        int before = provider.reads;

        mgr.save(CgGlSlot.DEPTH);

        // Trust never survives leaving our control: between outermost scopes, Minecraft or another mod ran
        // and may have written state through an API we cannot observe.
        assertTrue("outermost save must re-read regardless of trust", provider.reads > before);
    }

    @Test
    public void aNestedSaveTrustsTheShadow() {
        mgr.save(CgGlSlot.DEPTH);
        int afterOuter = provider.reads;
        mgr.save(CgGlSlot.DEPTH);
        assertTrue("a nested save must not re-read — that is where the glGet saving comes from",
                provider.reads == afterOuter);
    }

    @Test
    public void adoptedValuesBecomeTheBaselineForDeduplication() {
        mgr.save(CgGlSlot.DEPTH);
        // The stub adopts depthMask = true, so writing true is already redundant.
        assertFalse("the adopted value must be deduplicated against", mgr.depthMaskChanged(true));
        assertTrue(mgr.depthMaskChanged(false));
    }

    @Test
    public void anEmptySaveIsANoOpScope() {
        int before = provider.reads;
        CgGlScope scope = mgr.save();
        scope.close();                       // NOOP_SCOPE: safe to close, restores nothing
        assertTrue(provider.reads == before);
    }

    // ── Hosting foreign code ──────────────────────────────────────────────────

    /**
     * The case that motivated {@code hostForeign}: rendering a Minecraft item or entity inside one of our
     * passes. Foreign code writes GL invisibly, so on the way out nothing may be assumed.
     */
    @Test
    public void hostForeignDropsAllTrustOnExit() {
        mgr.depthMaskChanged(true);
        mgr.programChanged(5);

        CgGlScope s = mgr.hostForeign(CgGlSlot.DEPTH);
        s.close();

        for (CgGlSlot slot : CgGlSlot.values()) {
            if (slot == CgGlSlot.DEPTH) continue;   // declared, so re-asserted and trusted again
            assertFalse(slot + " must be untrusted after foreign code ran", mgr.isTrusted(slot));
        }
    }

    /**
     * The whole point, and the part a plain {@code save()} gets wrong.
     *
     * <p>Foreign code set depth-mask false without telling us. Our shadow still says true. Restoring must
     * re-issue {@code glDepthMask(true)} for real — if trust were kept, the reissue would compare equal to
     * the stale shadow and be deduplicated into nothing, leaving the driver on the foreign value.</p>
     */
    @Test
    public void aDeclaredDomainIsReassertedForRealAfterForeignCode() {
        mgr.depthMaskChanged(true);                       // ours: mask = true
        CgGlScope s = mgr.hostForeign(CgGlSlot.DEPTH);
        gl.clear();
        s.close();                                        // reissues unconditionally

        // The assertion that matters: the call really reached the driver. Everything else here is the
        // manager agreeing with itself, which is exactly what a stale shadow also does.
        assertTrue("glDepthMask must actually be issued, not deduplicated against the stale shadow",
                gl.sawCall("glDepthMask"));
        assertTrue("re-asserted, so trusted again", mgr.isTrusted(CgGlSlot.DEPTH));
        assertFalse("and the re-assert restored our value, so writing it again is redundant",
                mgr.depthMaskChanged(true));
    }

    /** The contrast that proves the flag does something: an ordinary scope elides the same restore. */
    @Test
    public void anOrdinaryScopeDeduplicatesTheRestoreAway() {
        mgr.save(CgGlSlot.DEPTH);
        mgr.depthMaskChanged(true);
        CgGlScope s = mgr.save(CgGlSlot.DEPTH);
        gl.clear();
        s.close();
        assertFalse("nothing was disturbed, so restore must cost no GL calls at all",
                gl.sawCall("glDepthMask"));
    }

    /**
     * <strong>Every</strong> field of a re-established domain must be re-issued, not just the first.
     *
     * <p>Trust is per-domain but a domain is written field by field, so the first re-issue marks the domain
     * trusted and the rest then compare equal to the stale shadow and are skipped. This shipped broken
     * once: {@code DEPTH} emitted its enable and silently dropped both the write mask and the compare
     * function, leaving two thirds of the domain on whatever the foreign code set.</p>
     */
    @Test
    public void aForeignRestoreReassertsEveryFieldOfTheDomain() {
        mgr.depthMaskChanged(true);
        mgr.depthFuncChanged(0x0203);

        CgGlScope s = mgr.hostForeign(CgGlSlot.DEPTH);
        gl.clear();
        s.close();

        assertTrue("write mask", gl.sawCall("glDepthMask"));
        assertTrue("compare function", gl.sawCall("glDepthFunc"));
        assertTrue("test enable/disable", gl.sawCall("glEnable") || gl.sawCall("glDisable"));
    }

    /** Declaring nothing is legitimate: invalidate on exit, restore nothing. */
    @Test
    public void hostForeignWithNoSlotsStillInvalidates() {
        mgr.depthMaskChanged(true);
        CgGlScope s = mgr.hostForeign();
        assertTrue("must be a real frame, not the no-op scope", s != CgGlScope.NOOP_SCOPE);
        s.close();
        assertFalse(mgr.isTrusted(CgGlSlot.DEPTH));
        assertTrue("nothing is trusted, so the next write must reach the driver",
                mgr.depthMaskChanged(true));
    }

    /** Contrast: an ordinary scope keeps trust, which is correct only when CgGL saw every write. */
    @Test
    public void anOrdinaryNestedScopeKeepsTrust() {
        mgr.save(CgGlSlot.DEPTH);            // outermost, adopts
        mgr.depthMaskChanged(false);
        CgGlScope inner = mgr.save(CgGlSlot.DEPTH);
        inner.close();
        assertTrue("no foreign code ran, so the shadow is still authoritative",
                mgr.isTrusted(CgGlSlot.DEPTH));
    }

    // ── Fail-fast ─────────────────────────────────────────────────────────────

    @Test(expected = IllegalStateException.class)
    public void exceedingTheScopePoolThrowsRatherThanLeaking() {
        // A dropped frame is an unrestorable state leak, which would surface later as inexplicable
        // rendering rather than as an error here.
        for (int i = 0; i < 17; i++) mgr.save(CgGlSlot.DEPTH);
    }

    @Test(expected = IllegalStateException.class)
    public void aSecondThreadIsRejected() throws Throwable {
        mgr.depthMaskChanged(true);                 // claims the current thread
        final Throwable[] caught = new Throwable[1];
        Thread other = new Thread(() -> {
            try { mgr.depthMaskChanged(false); } catch (Throwable t) { caught[0] = t; }
        });
        other.start();
        other.join();
        // An off-thread write corrupts the shadow rather than failing, so it must be rejected outright.
        if (caught[0] != null) throw caught[0];
    }
}
