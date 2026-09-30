package com.crystalgraphics.mc.modern.platform;

//? if >=26.2 {
/*import com.crystalgraphics.platform.gl.CgGL;
import org.lwjgl.opengl.ARBClipControl;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
*///?}

/**
 * Our own drawing's depth convention, over a host that renders differently: GL's default clip range
 * ({@code -1..1}) and a depth clear of {@code 1.0}, for anything drawn into our own targets with our own
 * projection -- a CrystalGUI desktop, a shader-graph preview.
 *
 * <pre>{@code
 * OwnDepthConvention.enter();
 * try {
 *     paintDesktop();
 * } finally {
 *     OwnDepthConvention.leave();
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Only Minecraft 26.2 differs: it sets clip control to {@code ZERO_TO_ONE} once, at device init, and
 *       clears depth to {@code 0.0} for its reversed-Z world. Everywhere else both calls do nothing.</li>
 *   <li>Not for the world passes, which draw into Minecraft's own depth with its projection.
 *       @see CgGL#setDepthReversed</li>
 *   <li>{@code leave} restores the host's clip control and nothing else: Minecraft sets its own clear
 *       depth before every clear.</li>
 * </ul>
 */
public final class OwnDepthConvention {

    private OwnDepthConvention() {}

    //? if >=26.2 {
    /*private static final int GL_LOWER_LEFT = 0x8CA1;
    private static final int GL_CLIP_ORIGIN = 0x935C;
    private static final int GL_CLIP_DEPTH_MODE = 0x935D;
    private static final int GL_NEGATIVE_ONE_TO_ONE = 0x935E;

    // The host's clip control, read on the first enter: Minecraft sets it once and never again, so no
    // frame after the first needs a glGet.
    private static boolean read;
    private static boolean clipControl;
    private static int hostOrigin;
    private static int hostDepthMode;
    *///?}

    public static void enter() {
        //? if >=26.2 {
        /*if (GraphicsApi.vulkan()) {
            // The tracked backend applies GL's clip range to our own passes itself.
            CgGL.glClearDepth(1.0);
            return;
        }
        if (!read) {
            read = true;
            GLCapabilities caps = GL.getCapabilities();
            clipControl = caps.OpenGL45 || caps.GL_ARB_clip_control;
            if (clipControl) {
                hostOrigin = GL11.glGetInteger(GL_CLIP_ORIGIN);
                hostDepthMode = GL11.glGetInteger(GL_CLIP_DEPTH_MODE);
            }
        }
        if (clipControl) ARBClipControl.glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
        CgGL.glClearDepth(1.0);
        *///?}
    }

    public static void leave() {
        //? if >=26.2 {
        /*if (clipControl && !GraphicsApi.vulkan()) ARBClipControl.glClipControl(hostOrigin, hostDepthMode);
        *///?}
    }
}
