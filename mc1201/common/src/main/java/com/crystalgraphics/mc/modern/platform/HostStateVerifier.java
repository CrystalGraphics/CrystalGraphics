package com.crystalgraphics.mc.modern.platform;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
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

    /** Set once, because a flag read per frame is a system-property lookup per frame. */
    private static final boolean ENABLED = Boolean.getBoolean(FLAG);

    /** Domains already reported. @see HostStateVerifier */
    private static final Set<String> REPORTED = new LinkedHashSet<>();

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
    public static void verify(String pass) {
        if (!ENABLED) return;
        try {
            checkCap(pass, "BLEND", GL11C.GL_BLEND, boolField("BLEND", "mode"));
            checkCap(pass, "DEPTH_TEST", GL11C.GL_DEPTH_TEST, boolField("DEPTH", "mode"));
            checkCap(pass, "CULL_FACE", GL11C.GL_CULL_FACE, boolField("CULL", "enable"));
            checkCap(pass, "SCISSOR_TEST", GL11C.GL_SCISSOR_TEST, boolField("SCISSOR", "mode"));
            checkCap(pass, "POLYGON_OFFSET_FILL", GL11C.GL_POLYGON_OFFSET_FILL,
                    boolField("POLY_OFFSET", "mode"));

            checkInt(pass, "activeTexture",
                    GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE) - GL13C.GL_TEXTURE0,
                    intStatic("activeTexture"));
            checkInt(pass, "depthFunc", GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                    intOf(state("DEPTH"), "func"));
            checkBool(pass, "depthMask", GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                    boolOf(state("DEPTH"), "mask"));
        } catch (Throwable t) {
            // Reflection refused, or a field moved between versions. Say so once and stop trying:
            // a diagnostic that spams or throws is worse than one that admits it cannot read.
            reportOnce("reflection", "could not read GlStateManager (" + t + ") -- verifier disabled");
        }
    }

    // ── Comparisons ────────────────────────────────────────────────────────────────────────────────

    private static void checkCap(String pass, String domain, int cap, Boolean host) {
        if (host == null) return;
        boolean driver = GL11C.glIsEnabled(cap);
        if (driver != host) {
            reportOnce(domain, "after " + pass + ": " + domain + " -- driver=" + driver + " host=" + host);
        }
    }

    private static void checkInt(String pass, String domain, int driver, Integer host) {
        if (host == null || driver == host) return;
        reportOnce(domain, "after " + pass + ": " + domain + " -- driver=" + driver + " host=" + host);
    }

    private static void checkBool(String pass, String domain, boolean driver, Boolean host) {
        if (host == null || driver == host) return;
        reportOnce(domain, "after " + pass + ": " + domain + " -- driver=" + driver + " host=" + host);
    }

    private static void reportOnce(String domain, String message) {
        if (REPORTED.add(domain)) {
            System.err.println("[cg-host-verify] " + message);
        }
    }

    // ── Reading the shadow ─────────────────────────────────────────────────────────────────────────

    private static Object state(String name) throws Exception {
        Field f = GlStateManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Integer intStatic(String name) throws Exception {
        Field f = GlStateManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    /** {@code STATE.field.enabled}, where the middle field is a {@code BooleanState}. */
    private static Boolean boolField(String stateName, String booleanStateField) throws Exception {
        Object holder = state(stateName);
        Field bs = holder.getClass().getDeclaredField(booleanStateField);
        bs.setAccessible(true);
        Object booleanState = bs.get(holder);
        Field enabled = booleanState.getClass().getDeclaredField("enabled");
        enabled.setAccessible(true);
        return enabled.getBoolean(booleanState);
    }

    private static Integer intOf(Object holder, String field) throws Exception {
        Field f = holder.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(holder);
    }

    private static Boolean boolOf(Object holder, String field) throws Exception {
        Field f = holder.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.getBoolean(holder);
    }
}
