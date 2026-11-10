package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.BooleanSupplier;

/**
 * A host's state as its own cache holds it, so a section opens without a driver round trip: each
 * {@link GLHostState.Domain}'s first {@value #CHECKS} reads are compared with {@code glGet}, and a domain that disagrees,
 * or that the cache does not answer, reads {@code glGet} from then on, logged once.
 *
 * <pre>{@code
 * GLHostCache cache = new GLHostCache(GlStateManager.class, 8, GlStateManager::setActiveTexture, driverUnit);
 * GLHostState host = new GLCheckedHostState("GlStateManager", driver, new GLCheckedHostState.Answers() {
 *     public boolean answer(Domain domain, GLContextState s) {
 *         if (cache.read(domain, s)) return true;
 *         if (domain != Domain.PROGRAM) return false;   // glGet
 *         s.program = 0;                                // what the host holds at every hook
 *         return true;
 *     }
 *     public int alphaTest() { return cache.alphaTest(); }
 * }, false);
 * }</pre>
 *
 * <ul>
 *   <li>Answer only what the host's cache holds, or what the host holds at every hook. A field nothing reads as a value
 *       (a scissor box set before each use) stays {@link GLContextState#UNKNOWN}: the check skips it, and the section
 *       leaves it as the device set it. A guess the section should set back but the driver may not share is
 *       {@link Answers#excuse}d: the check skips it, and the device sets it before relying on it.</li>
 *   <li>What no domain holds (bound samplers, pixel store, the device's conventions) is never read: as the section
 *       closes it goes back to GL's defaults, and the clip range to the host's.</li>
 *   <li>The checks see only the first reads: a guess that holds at startup and changes later is not caught.</li>
 *   <li>{@code -Dcrystalgraphics.host.stateCache=false} reads every domain with {@code glGet}.</li>
 * </ul>
 */
public class GLCheckedHostState implements GLHostState {

    /** What a host's cache answers. */
    public interface Answers {
        /** Fills {@code domain}'s fields of {@code into}; false where the cache does not hold it, for {@code glGet}. */
        boolean answer(Domain domain, GLContextState into);

        /**
         * Sets unknown in {@code answer} what this host answers by a guess the driver may not share (a binding the host
         * makes before each use), so a check compares the rest.
         */
        default void excuse(Domain domain, GLContextState answer) {}

        /** 1 where the host believes its alpha test on, 0 off, {@link GLContextState#UNKNOWN} to ask GL. */
        default int alphaTest() {
            return GLContextState.UNKNOWN;
        }
    }

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    /** Reads of a domain compared with {@code glGet} before it is trusted. */
    public static final int CHECKS = 600;

    private static final CgVar.Bool STATE_CACHE = CgVar.bool("crystalgraphics.host.stateCache", true, CgVar.DEV,
            "A device section reads the host's state from its GlStateManager; false reads it with glGet (HostStateModern"
                    + " 1.13-26.2 on GL, HostStateLegacy). Taken as the host's GL device starts.");

    /** After each pass, the driver against the host's own state cache; a host reads it when it installs. */
    public static final CgVar.Bool VERIFY = CgVar.bool("crystalgraphics.host.verify", false, CgVar.DEV | CgVar.RESTART,
            "After each pass, compare the driver against the host's GlStateManager and name the domain that disagrees"
                    + " (modern nodes; also CRYSTALGRAPHICS_HOST_VERIFY=true).");

    private static final CgTraceChannel GL = CgTrace.channel("crystalgraphics.gl");
    /** Per domain, reads that reached the driver: a fallback domain's, or a check's. */
    private static final int[] DRIVER_READS = new int[Domain.values().length];

    static {
        for (Domain d : Domain.values()) DRIVER_READS[d.ordinal()] = CgTrace.name("hostState.glGet." + d);
    }

    private static volatile BooleanSupplier deferChecks = () -> false;

    private final String host;
    private final GLStateQuery query;
    private final Answers answers;
    private final boolean clipZeroToOne;
    private final int[] checked = new int[Domain.values().length];
    private final GLContextState answer = new GLContextState(), truth = new GLContextState();
    /** Domains read with {@code glGet}, as bits by ordinal: a check disagreed, or the cache does not answer them. */
    private int fallback;
    private boolean reported;

    /**
     * @param host          what the log calls the cache, as in "Blaze3D's BLEND disagrees with the driver"
     * @param gl            the context's driver, for the checks and the fallback
     * @param clipZeroToOne whether the host set clip control to {@code 0..1} (Minecraft 26.2 on GL)
     */
    public GLCheckedHostState(String host, CgGLDriver gl, Answers answers, boolean clipZeroToOne) {
        this.host = host;
        this.query = new GLStateQuery(gl);
        this.answers = answers;
        this.clipZeroToOne = clipZeroToOne;
        if (!STATE_CACHE.get()) fallback = -1;
    }

    /**
     * While {@code defer} answers true, reads are answered from the cache unchecked and the checks wait: a check's
     * {@code glGet} waits behind every link a worker context has with the driver (NVIDIA, 100-250 ms on a cold start),
     * and a start's first reads fall in its links. Render thread, once.
     *
     * <pre>{@code
     * GLCheckedHostState.deferChecksWhile(CgLoadingScreen::hostDraws);
     * }</pre>
     */
    public static void deferChecksWhile(BooleanSupplier defer) {
        deferChecks = defer;
    }

    @Override
    public void read(GLContextState into) {
        for (Domain d : Domain.values()) {
            int bit = 1 << d.ordinal();
            if ((fallback & bit) == 0 && answers.answer(d, into)) {
                if (checked[d.ordinal()] < CHECKS && !deferChecks.getAsBoolean()) check(d, into);
                continue;
            }
            if ((fallback & bit) == 0) {
                fallback |= bit;
                LOG.info("[cg] {}'s cache holds no {}: read with glGet", host, d);
            }
            CgTrace.add(GL, DRIVER_READS[d.ordinal()], 1);
            query.read(d, into);
        }
    }

    @Override
    public void guessed(GLContextState adopted) {
        for (Domain d : Domain.values()) {
            if ((fallback & 1 << d.ordinal()) == 0) answers.excuse(d, adopted);
        }
    }

    @Override
    public void entered(CgGLDriver gl) {
        query.alphaOff(answers.alphaTest());
    }

    @Override
    public void conventions(CgGLDriver gl) {
        GLStateQuery.defaults(gl, clipZeroToOne);
        query.alphaBack(gl);
    }

    /** Compares {@code d}'s answer with the driver's; on a difference the driver's stands, for good. */
    private void check(Domain d, GLContextState into) {
        checked[d.ordinal()]++;
        CgTrace.add(GL, DRIVER_READS[d.ordinal()], 1);
        answer.invalidate();
        truth.invalidate();
        answers.answer(d, answer);
        answers.excuse(d, answer);
        query.read(d, truth);
        String diff = answer.differences(truth);
        if (diff == null) {
            if (checked[d.ordinal()] == CHECKS) agreed();
            return;
        }
        fallback |= 1 << d.ordinal();
        query.read(d, into);
        LOG.warn("[cg] {}'s {} disagrees with the driver ({}); reading it with glGet from now on", host, d, diff);
    }

    /** Logs once, when the first domain passes every check, with each domain's checks so far. */
    private void agreed() {
        if (reported) return;
        reported = true;
        StringBuilder counts = new StringBuilder();
        for (Domain d : Domain.values()) {
            if (checked[d.ordinal()] == 0 || (fallback & 1 << d.ordinal()) != 0) continue;
            counts.append(counts.length() == 0 ? "" : ", ").append(d).append(' ').append(checked[d.ordinal()]);
        }
        LOG.info("[cg] {}'s cache agrees with the driver ({})", host, counts);
    }
}
