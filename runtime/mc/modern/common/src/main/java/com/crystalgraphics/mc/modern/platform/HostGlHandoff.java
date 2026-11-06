package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.service.CgHostPaint;

//? if >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
*///?} else {
import com.mojang.blaze3d.platform.GlStateManager;
//?}

import org.lwjgl.opengl.GL13;
//? if <1.15 {
/*import org.lwjgl.opengl.GL20;
*///?}

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
 * {@code GlStateManager}. We write through {@code CgGL}, which Blaze3D never sees, so {@link #enter()} repairs ours on
 * the way in and {@link #leave()} repairs Minecraft's on the way out.</p>
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
        // 1.21.5 binds a target only inside its own render passes, so a screen or HUD paint finds the
        // last pass's still bound and the desktop composites into it, unseen.
        //? if >=1.21.5 {
        /*LifecycleModern.bindMainTarget(Minecraft.getInstance());
        *///?}
        // 26.2's world is reversed-Z with a 0..1 clip range; the desktop and its previews are not.
        OwnDepthConvention.enter();
    }

    /**
     * Hands Minecraft back the state its own renderer assumes.
     *
     * <p><b>Blaze3D caches the ACTIVE TEXTURE UNIT and every unit's binding.</b>
     * {@code GlStateManager._activeTexture} early-returns when its cache already names the unit, and
     * {@code _bindTexture} early-returns per unit. So a texture Minecraft binds after us is silently
     * DROPPED whenever its cache still names it, while the driver holds whatever CrystalGraphics last bound.
     * Minecraft then presents its framebuffer sampling OUR texture, and the whole window becomes a flat
     * fill -- white when what we left was the white pixel, dark when it was an empty layer target.
     *
     * <p>It reads as a compositing or animation fault and is neither: the drawing was never wrong, and a
     * {@code glReadPixels} of Minecraft's own target during a broken frame shows the UI intact. It
     * surfaces first on whatever painted last -- a context menu, a window animation -- because those are
     * simply the elements that leave a different texture bound.
     *
     * <p><b>Bounced through unit 1</b>, because setting unit 0 directly is elided by exactly the stale
     * cache being repaired. {@code _glUseProgram} is not cached and needs no such trick.
     *
     * <p>The 1.7.10 host has carried the same handoff since the identical white window was diagnosed
     * there; only the mechanism differs, and it differs in the half that matters -- on that loader the
     * repair is raw LWJGL because Minecraft's present is fixed-function and keeps no shadow of its own,
     * while here it MUST go through Blaze3D or Minecraft's cache is left describing the wrong world.
     */
    static void leave() {
        OwnDepthConvention.leave();
        // Ours ends here; the repair below is Minecraft's own state, through its own API.
        CgGL.toHost();
        // Under Vulkan Minecraft keeps no GL cache: the hand-over above was the whole of it.
        if (GraphicsApi.vulkan()) return;
        //? if >=1.21.5 {
        /*GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        // No program reset: from 1.21.5 Blaze3D binds a program only when its pipeline changes
        // (GlCommandEncoder.lastProgram), and the paint's own scope has put that program back. A reset left
        // Minecraft's next draw on the same pipeline with no program at all.
        *///?} elif >=1.15 {
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._glUseProgram(0);
        //?} else {
        /*// Before 1.15 the names lack the prefix, and the program is not cached at all.
        GlStateManager.activeTexture(GL13.GL_TEXTURE1);
        GlStateManager.activeTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(0);
        *///?}
        // The lines above went through Blaze3D and not CgGL, so our own shadow cannot see them
        // either -- the same rule, pointing the other way.
        CgGlState.invalidateAllIfPresent();
    }
}
