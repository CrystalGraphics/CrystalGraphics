package com.crystalgraphics.mc.modern.platform.gl;

// 1.13 ships LWJGL 3.1, which has no core-profile GLxxC classes.
//? if >=1.14 {
import com.crystalgraphics.lwjgl3.Lwjgl3GLBackend;
//?} else {
/*import com.crystalgraphics.lwjgl3.Lwjgl31GLBackend;
*///?}

//? if >=1.15 <1.21.5 {
import com.mojang.blaze3d.platform.GlStateManager;
//?}
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
//? if >=1.21.11 {
/*import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL33C;
*///?}
//? if >=1.19.2 <1.21.5 {
import com.mojang.blaze3d.vertex.BufferUploader;
//?}
//? if >=26.3 {
/*import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import com.crystalgraphics.sdl.SdlWorkerContext;
*///?}

/**
 * {@link Lwjgl3GLBackend} plus the host bookkeeping a section's edges cannot do. Minecraft's {@code GlStateManager}
 * skips a call its cache believes made; what the state shadow models of it, a host section sets back to Minecraft's
 * belief as it closes ({@code HostStateModern.sectionSlots()}), so every call here reaches the driver from tier 1.
 *
 * <ul>
 *   <li>The clear colour, which Minecraft caches below 1.21.5 and the shadow does not model, goes through
 *       {@code GlStateManager}.</li>
 *   <li>The first section turns off the {@code GL_DEBUG_OUTPUT} Minecraft enables.</li>
 *   <li>1.19.2 to 1.21.4 forget {@code BufferUploader}'s vertex array as the section closes; 1.21.11 unbinds the
 *       sampler objects it leaves on a unit we bind; 26.3 makes its worker context over SDL.</li>
 * </ul>
 *
 * <p>{@code -Dcrystalgraphics.host.verify=true} compares the driver with {@code GlStateManager} after each world
 * pass, naming a domain that disagrees.</p>
 */
//? if >=1.14 {
public final class Blaze3dGLBackend extends Lwjgl3GLBackend {
//?} else {
/*public final class Blaze3dGLBackend extends Lwjgl31GLBackend {
*///?}

    /** {@code GL_DEBUG_OUTPUT}, from KHR_debug. */
    private static final int DEBUG_OUTPUT = 0x92E0;

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    // gl.debugStacks and gl.debugPerf turn it on for themselves.
    private static final boolean KEEP_DEBUG_OUTPUT = KEEP_HOST_DEBUG_OUTPUT.get() || DEBUG_STACKS.get() || DEBUG_PERF.get();

    private boolean debugOutputChecked;

    // Minecraft turns GL_DEBUG_OUTPUT on at its default glDebugVerbosity of 1; on NVIDIA that made every GL call
    // several times dearer (the blasts scene 15.4 -> 9.6 ms a frame). Off for the context, once: switching it per
    // section cost the driver 1.1 ms a switch.
    @Override
    public void fromHost() {
        super.fromHost();
        if (debugOutputChecked) return;
        debugOutputChecked = true;
        if (!KEEP_DEBUG_OUTPUT && GL.getCapabilities().GL_KHR_debug && GL11.glIsEnabled(DEBUG_OUTPUT)) {
            GL11.glDisable(DEBUG_OUTPUT);
            LOGGER.info("[cg] Minecraft's GL debug output is off: on some drivers it slows every GL call "
                    + "(-Dcrystalgraphics.gl.keepHostDebugOutput=true keeps it)");
        }
    }

    // 26.3 ships SDL3 and no GLFW: its GL context (as Iris forces) shares with an SDL window's.
    //? if >=26.3 {
    /*@Override
    public CgGLWorkerContext createWorkerContext() {
        return SdlWorkerContext.create();
    }
    *///?}

    // HostStateModern answers the vertex input as Minecraft's no longer: it binds its own before each draw, but
    // remembers its immediate buffer's array, so that record goes when the frame goes back.
    //? if >=1.19.2 <1.21.5 {
    @Override
    public void toHost() {
        super.toHost();
        BufferUploader.reset();
    }
    //?}

    // 1.21.11 samples through sampler objects it leaves bound, always with a mipmapped min filter; over our mip-less
    // textures that is incomplete and reads black. It rebinds its own per draw.
    //? if >=1.21.11 {
    /*private int activeTextureUnit;

    @Override
    public void glActiveTexture(int texture) {
        super.glActiveTexture(texture);
        activeTextureUnit = texture - GL13.GL_TEXTURE0;
    }

    @Override
    public void glBindTexture(int target, int texture) {
        super.glBindTexture(target, texture);
        if (target == GL11.GL_TEXTURE_2D) GL33C.glBindSampler(activeTextureUnit, 0);
    }
    *///?}

    // Cached below 1.21.5 and in no domain of the shadow's, so no section can set it back. Below 1.15 the
    // same-package GlStateManager shim spells it.
    //? if <1.21.5 {
    @Override
    public void glClearColor(float r, float g, float b, float a) {
        GlStateManager._clearColor(r, g, b, a);
    }
    //?}
}
