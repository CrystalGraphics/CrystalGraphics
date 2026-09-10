package com.crystalgraphics.mc.modern.platform;

import com.mojang.blaze3d.platform.GlStateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11C;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Answers the one question {@code Blaze3dGLBackend} cannot answer about itself: <b>is Minecraft's
 * shadow of the GL state still true?</b>
 *
 * <pre>{@code
 * java ... -Dcrystalgraphics.host.verify=true
 * # [cg-host-verify] after opaque: BLEND -- driver=true host=false
 * }</pre>
 *
 * <p><b>Off unless asked for.</b> It reads the driver, which means {@code glGet} calls and a pipeline
 * stall, so it is a diagnostic and never a guard.
 *
 * <h3>What it is for</h3>
 *
 * <p>A missing override in {@code Blaze3dGLBackend} produces no exception. Our call reaches the driver
 * as intended; only Minecraft's cache goes stale, and the damage lands on <i>Minecraft's</i> next draw
 * — a texture silently not bound, a blend silently not enabled. By the time anything looks wrong the
 * cause is several draws behind, in another codebase. This turns that into a line naming the domain.
 *
 * <p>The compile-time half is {@code Blaze3dMirrorTest}, which proves the override list has not shrunk.
 * It cannot prove the list is <i>complete</i>, because completeness is a fact about Minecraft. This can:
 * it asks the driver and the host and compares.
 *
 * <h3>Reading a private shadow</h3>
 *
 * <p>{@code GlStateManager}'s state objects are private static finals, so this is reflection, and
 * reflection that is allowed to fail: a JVM that refuses the access, or a Minecraft that renames a
 * field, must degrade to "could not read" and never to a crash. A diagnostic that can break the game
 * is worse than no diagnostic.
 *
 * <p>Each disagreement is reported <b>once per domain per process</b>. It is called after every pass,
 * and a stale cache stays stale — without this the log would be one line per frame for ever.
 */
public final class HostStateVerifier {

    private static final String FLAG = "crystalgraphics.host.verify";

    /**
     * Set once, because a flag read per frame is a lookup per frame.
     *
     * <p><b>Two channels, and the second is not redundant.</b> A launcher does not necessarily pass
     * per-instance JVM arguments through to the game — PrismLauncher did not, measured — so a
     * diagnostic that only reads a system property is unreachable by exactly the person most likely
     * to need it: someone running an installed client who cannot edit a command line.
     * {@code CRYSTALGRAPHICS_HOST_VERIFY=true} in the environment does the same job.
     */
    private static final boolean ENABLED =
            Boolean.getBoolean(FLAG) || "true".equalsIgnoreCase(System.getenv("CRYSTALGRAPHICS_HOST_VERIFY"));

    /**
     * Through log4j, so it lands in the client's own {@code latest.log} beside everything else.
     *
     * <p>Not {@code System.err}: that goes to the launcher console and not to the file anyone reads
     * afterwards, which made the first version of this class unfalsifiable — a run that found nothing
     * and a run that never happened looked identical.
     */
    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    /** Domains already reported. @see HostStateVerifier */
    private static final Set<String> REPORTED = new LinkedHashSet<>();

    /** Announced once, so "no disagreements" is distinguishable from "never ran". */
    private static boolean announced;

    /** Says once that a comparison actually happened, which is the half the announcement cannot. */
    private static boolean verifiedOnce;

    private HostStateVerifier() { }

    /** Whether {@code -Dcrystalgraphics.host.verify=true} was passed. */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Compares the driver against Minecraft's shadow and logs any domain that disagrees.
     *
     * @param pass where this was called from, so a report says which of our passes left it wrong
     */
    /**
     * Says once, at platform setup, whether this is armed.
     *
     * <p>Called from where the backend is built rather than from {@link #verify}, and that is the
     * point: if the announcement appears and no comparison ever does, the passes are not running —
     * which is a different bug from the flag not arriving, and the two were indistinguishable while
     * the only output came from {@code verify} itself.
     */
    public static void announceIfEnabled() {
        if (!ENABLED || announced) return;
        announced = true;
        LOG.info("[cg-host-verify] ARMED -- the driver will be compared against GlStateManager after "
                + "every pass. A line per disagreeing domain follows, or silence if they agree.");
    }

    public static void verify(String pass) {
        if (!ENABLED) return;
        if (!verifiedOnce) {
            verifiedOnce = true;
            LOG.info("[cg-host-verify] first comparison ran after the {} pass", pass);
        }
        try {
            Map<Integer, Boolean> host = readBooleanStates();
            if (host.isEmpty()) {
                reportOnce("shape", "found no BooleanState in GlStateManager -- its shape has changed");
                return;
            }
            for (Map.Entry<Integer, Boolean> e : host.entrySet()) {
                int cap = e.getKey();
                boolean driver = GL11C.glIsEnabled(cap);
                if (driver != e.getValue()) {
                    reportOnce("cap:" + cap, "after " + pass + ": GL cap 0x" + Integer.toHexString(cap)
                            + " -- driver=" + driver + " host=" + e.getValue());
                }
            }
        } catch (Throwable t) {
            reportOnce("reflection", "could not read GlStateManager (" + t + ") -- verifier disabled");
        }
    }

    /**
     * Every {@code BooleanState} Minecraft holds, as {@code GL cap -> what it believes}.
     *
     * <p><b>Found by SHAPE, not by name.</b> The first version read {@code BLEND.mode.enabled} and
     * friends, which worked on NeoForge and failed on Forge and Fabric with
     * {@code NoSuchFieldException} — those ship SRG and intermediary member names, so a field called
     * {@code mode} in the source is called something else in the jar. Names are a mapping artefact;
     * the shape is not.
     *
     * <p>So: walk {@code GlStateManager}'s static fields, walk each one's instance fields, and treat
     * any object whose class declares exactly one {@code int} and one {@code boolean} as a
     * {@code BooleanState}. The int is the GL cap it guards — {@code GL_BLEND}, {@code GL_DEPTH_TEST}
     * and the rest — which makes the reading self-describing: we do not need to know what Minecraft
     * calls a field to know which cap it is about.
     */
    private static Map<Integer, Boolean> readBooleanStates() throws Exception {
        Map<Integer, Boolean> found = new LinkedHashMap<>();
        for (Field staticField : GlStateManager.class.getDeclaredFields()) {
            if (!Modifier.isStatic(staticField.getModifiers())) continue;
            staticField.setAccessible(true);
            Object holder = staticField.get(null);
            if (holder == null || holder.getClass().getName().startsWith("java.")) continue;
            collectFrom(holder, found);
        }
        return found;
    }

    private static void collectFrom(Object holder, Map<Integer, Boolean> found) throws Exception {
        if (readBooleanState(holder, found)) return;
        for (Field f : holder.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object child = f.get(holder);
            if (child != null && !child.getClass().getName().startsWith("java.")) {
                readBooleanState(child, found);
            }
        }
    }

    /** @return whether {@code candidate} was a BooleanState, by shape: exactly one int and one boolean. */
    private static boolean readBooleanState(Object candidate, Map<Integer, Boolean> found) throws Exception {
        Field capField = null;
        Field enabledField = null;
        int others = 0;
        for (Field f : candidate.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == int.class && capField == null) capField = f;
            else if (f.getType() == boolean.class && enabledField == null) enabledField = f;
            else others++;
        }
        if (capField == null || enabledField == null || others != 0) return false;
        capField.setAccessible(true);
        enabledField.setAccessible(true);
        found.put(capField.getInt(candidate), enabledField.getBoolean(candidate));
        return true;
    }

    private static void reportOnce(String domain, String message) {
        if (REPORTED.add(domain)) {
            LOG.warn("[cg-host-verify] {}", message);
        }
    }
}
