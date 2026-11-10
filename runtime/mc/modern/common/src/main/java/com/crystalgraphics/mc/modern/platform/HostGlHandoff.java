package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgraphics.render.graph.CgExecutor;
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
 * <p>{@link #enter()} opens a host section, which reads Blaze3D's {@code GlStateManager} and sets it back at
 * {@link #leave()} ({@code HostStateModern}), and names the main target as what the paint draws into.</p>
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

    /** What the open paint draws into: Minecraft's main target. */
    private static CgExecutor.Into into;

    /** Takes the frame for a paint, its current target the main target. */
    static void enter() {
        CgDeviceSection.begin();
        Minecraft mc = Minecraft.getInstance();
        into = CgExecutor.into(LifecycleModern.bindMainTarget(mc), Windows.of(mc).getWidth(), Windows.of(mc).getHeight());
    }

    /** Hands Minecraft back its frame, as it believes it left it. */
    static void leave() {
        try {
            if (into != null) into.close();
        } finally {
            into = null;
            CgDeviceSection.end();
        }
    }
}
