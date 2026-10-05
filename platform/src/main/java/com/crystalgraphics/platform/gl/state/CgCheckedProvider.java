package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Callable;

/**
 * A provider that answers from the host's own GL state cache instead of {@code glGet}, so a scope opening at a host
 * entry makes no driver round trip. Each domain's first {@value #CHECKS} reads are compared with {@code glGet}; a
 * domain that disagrees, or that the cache does not hold, reads {@code glGet} from then on, logged once.
 *
 * <pre>{@code
 * final class HostStateMine extends CgCheckedProvider {
 *     private final CgHostStateCache cache = new CgHostStateCache(GlStateManager.class, 8, GlStateManager::setActiveTexture, driverUnit);
 *
 *     HostStateMine() { super("GlStateManager", cache.missing()); }   // pass every domain the host cannot answer
 *
 *     @Override protected void answer(CgGlSlot slot, CgGlStateShadow t) {
 *         if (cache.answer(slot, t)) return;
 *         // the rest: PROGRAM, FBO, VERTEX_INPUT, as this host holds them at its hooks
 *     }
 *     @Override protected void answerTextures(CgGlStateShadow t, int units) { cache.textures(t, units); }
 *     @Override protected int unitsMask() { return cache.unitsMask(); }
 * }
 *
 * // On the render thread, once the backend is built:
 * CgCheckedProvider.install("GlStateManager", HostStateMine::new);
 * }</pre>
 *
 * <ul>
 *   <li>Answer only what the host's cache holds, or what the host holds at every hook; mark the rest missing. A
 *       field nothing reads as a value (a scissor box the host sets before each use) may be guessed if
 *       {@link #excuse} copies the driver's into the answer, so the check compares only the rest.</li>
 *   <li>The checks see only the first reads: a guess that holds at startup and changes later is not caught.</li>
 *   <li>{@code -Dcrystalgraphics.host.stateCache=false} keeps {@code glGet}.</li>
 * </ul>
 */
public abstract class CgCheckedProvider extends CgGlGetProvider {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    /** Reads of a domain compared with {@code glGet} before it is trusted. */
    public static final int CHECKS = 600;

    private static final int CAPTURED = bit(CgGlSlot.STORAGE_BUFFERS) | bit(CgGlSlot.IMAGES)
            | bit(CgGlSlot.INDIRECT_BUFFERS) | bit(CgGlSlot.TRANSFORM_FEEDBACK);

    private static final CgTraceChannel GL = CgTrace.channel("crystalgraphics.gl");
    /** Per domain, reads that reached the driver: a fallback domain's, or a check's. */
    private static final int[] DRIVER_READS = new int[CgGlSlot.values().length];
    static {
        for (CgGlSlot s : CgGlSlot.values()) DRIVER_READS[s.ordinal()] = CgTrace.name("glState.glGet." + s);
    }

    private final String host;
    private final int[] checked = new int[CgGlSlot.values().length];
    /** Domains read with {@code glGet}: a check disagreed, or the host's cache does not hold them. */
    private int fallback;
    private boolean reported;
    private final CgGlStateShadow truth = new CgGlStateShadow(), answer = new CgGlStateShadow();

    /**
     * @param host    what the log calls the cache, as in "Blaze3D's BLEND disagrees with the driver"
     * @param missing the domains the host cannot answer, as {@code 1 << slot.ordinal()} bits
     */
    protected CgCheckedProvider(String host, int missing) {
        this.host = host;
        this.fallback = missing | CAPTURED;
    }

    /** Makes the provider {@code build} answers the shadow's, or leaves {@code glGet} where it throws. */
    public static void install(String host, Callable<? extends CgCheckedProvider> build) {
        if ("false".equals(System.getProperty("crystalgraphics.host.stateCache"))) return;
        try {
            CgCheckedProvider provider = build.call();
            CgGlState.setProvider(provider);
            int glGet = provider.fallback & ~CAPTURED;
            LOG.info("[cg] state shadow reads {}'s cache{}", host, glGet == 0 ? "" : " (glGet for " + names(glGet) + ")");
        } catch (Exception | LinkageError refused) {
            LOG.info("[cg] state shadow reads glGet: {}'s cache not found ({})", host, refused.toString());
        }
    }

    /** Fills {@code slot}'s fields of {@code t} from the host's cache; never called for a missing domain. */
    protected abstract void answer(CgGlSlot slot, CgGlStateShadow t);

    /** The active unit and each unit in {@code units}' 2D binding, from the host's cache. */
    protected abstract void answerTextures(CgGlStateShadow t, int units);

    /** The units the host's cache models, as a mask; a read of any other goes to {@code glGet}. */
    protected abstract int unitsMask();

    /** The units the host's cache models: the host binds no other. */
    @Override
    public int hostUnits() {
        return unitsMask();
    }


    @Override
    public void read(CgGlSlot slot, CgGlStateShadow t) {
        if (slot == CgGlSlot.TEXTURES) {
            readTextureUnits(t, unitsMask());
            return;
        }
        if ((fallback & bit(slot)) != 0) {
            // A core profile has no alpha test, and its read answers without the driver.
            if (slot != CgGlSlot.ALPHA_TEST || !CgCapabilities.detect().isCoreProfile()) driverRead(slot);
            super.read(slot, t);
            return;
        }
        answer(slot, t);
        if (checked[slot.ordinal()] < CHECKS) check(slot, t, -1);
    }

    @Override
    public int readTextureUnits(CgGlStateShadow t, int units) {
        if ((fallback & bit(CgGlSlot.TEXTURES)) != 0 || (units & ~unitsMask()) != 0) {
            driverRead(CgGlSlot.TEXTURES);
            return super.readTextureUnits(t, units);
        }
        answerTextures(t, units);
        if (checked[CgGlSlot.TEXTURES.ordinal()] < CHECKS) check(CgGlSlot.TEXTURES, t, units);
        return units;
    }

    /** Compares {@code t}'s answer for {@code slot} with the driver's; on a difference the driver's stands, for good. */
    private void check(CgGlSlot slot, CgGlStateShadow t, int units) {
        checked[slot.ordinal()]++;
        driverRead(slot);
        truth.copyFrom(t);
        if (units >= 0) super.readTextureUnits(truth, units);
        else super.read(slot, truth);
        answer.copyFrom(t);
        excuse(slot, answer, truth);
        String diff = answer.differences(truth, bit(slot));
        if (diff == null) {
            if (checked[slot.ordinal()] == CHECKS) agreed();
            return;
        }
        fallback |= bit(slot);
        t.copyFrom(truth);
        LOG.warn("[cg] state shadow: {}'s {} disagrees with the driver ({}); reading it with glGet from now on", host, slot, diff);
    }

    /** Logs once, when the first domain passes every check, with each domain's checks so far. */
    private void agreed() {
        if (reported) return;
        reported = true;
        StringBuilder counts = new StringBuilder();
        for (CgGlSlot s : CgGlSlot.values()) {
            if (checked[s.ordinal()] == 0 || (fallback & bit(s)) != 0) continue;
            counts.append(counts.length() == 0 ? "" : ", ").append(s).append(' ').append(checked[s.ordinal()]);
        }
        LOG.info("[cg] state shadow: {}'s cache agrees with the driver ({})", host, counts);
    }

    private static void driverRead(CgGlSlot slot) {
        CgTrace.add(GL, DRIVER_READS[slot.ordinal()], 1);
    }

    private static String names(int slots) {
        StringBuilder b = new StringBuilder();
        for (CgGlSlot s : CgGlSlot.values()) if ((slots & bit(s)) != 0) b.append(b.length() == 0 ? "" : ", ").append(s);
        return b.toString();
    }

    /** {@code slot} as a bit of a domain mask. */
    public static int bit(CgGlSlot slot) {
        return 1 << slot.ordinal();
    }
}
