package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.service.CgHostPaint;
import net.minecraft.client.Minecraft;


/**
 * The modern GL hand-over behind {@link CgHostPaint}, plus the context checks a paint asks before it starts.
 *
 * <pre>{@code
 * if (!HostGlHandoff.contextIsLive()) return;
 * CgHostPaint.enter();
 * try { paint(); } finally { CgHostPaint.leave(); }
 * }</pre>
 *
 * <p>There are two state shadows here, each blind to the other: CrystalGraphics' {@code CgGlState} and Blaze3D's
 * {@code GlStateManager}. {@link #enter()} drops ours on the way in; the host section's close puts Minecraft's back on
 * the way out.</p>
 */
public final class HostGlHandoff {

    private HostGlHandoff() {}

    /**
     * Whether there is a GL context to paint into. Ask before every paint:
     *
     * <pre>{@code
     * if (host == null || !HostGlHandoff.contextIsLive()) return;
     * }</pre>
     *
     * <p>{@code GameShuttingDownEvent} destroys the context while the render thread still draws the
     * save-progress screen, so a paint after it binds deleted materials.</p>
     */
    public static boolean contextIsLive() {
        return CgGraphicsLifecycle.isInitialized();
    }

    /**
     * Brings a context up if there is not one yet, for painting OUTSIDE a world render.
     *
     * <p>The engine initialises lazily on the first world pass, and this desktop also opens over the
     * TITLE SCREEN, where no world pass ever runs — so {@link #contextIsLive()} answered false for the
     * life of the process and every paint was skipped in silence.</p>
     */
    public static void ensureContext(int width, int height) {
        if (width > 0 && height > 0) CgEngineLifecycle.ensureGraphicsContext(width, height);
    }

    /**
     * Minecraft writes GL state behind CrystalGraphics' back every frame, so our shadow must be dropped
     * before we paint or each stale field is a bind we skip. Cheap: it drops trust, it does not read the
     * driver.
     */
    static void enter() {
        CgGL.fromHost();
        CgGlState.invalidateAllIfPresent();
        // Bound, not assumed: below 1.21.5 the shadow answers "the main target" unasked, so a framebuffer left
        // bound since Minecraft's last bindWrite turns our bind of the main target into an elided one, and
        // the paint lands there unseen. From 1.21.5 Minecraft binds one only inside its own passes.
        LifecycleModern.bindMainTarget(Minecraft.getInstance());
        // 26.2's world is reversed-Z with a 0..1 clip range; the desktop and its previews are not.
        OwnDepthConvention.enter();
    }

    /**
     * Hands Minecraft back its frame. The section's close sets every domain Blaze3D's cache keeps back to its belief
     * ({@code HostStateModern.sectionSlots()}): the active unit and each unit's binding among them, which Minecraft
     * would otherwise skip binding over ours and present its framebuffer sampling our texture.
     */
    static void leave() {
        OwnDepthConvention.leave();
        CgGL.toHost();
    }
}
