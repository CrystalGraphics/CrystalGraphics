package com.crystalgraphics.mc.modern.platform;

//? if >=26.2 {
/*import com.crystalgraphics.platform.gl.CgGL;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.ARBClipControl;
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
 *   <li>{@link #install} first, as the GL backend is made: it tells {@code CgGL} the clip control Minecraft set,
 *       which a device section puts back as it closes.</li>
 * </ul>
 */
public final class OwnDepthConvention {

    private OwnDepthConvention() {}

    //? if >=26.2 {
    /*private static final int GL_LOWER_LEFT = 0x8CA1;
    private static final int GL_NEGATIVE_ONE_TO_ONE = 0x935E;
    private static final int GL_ZERO_TO_ONE = 0x935F;

    // Whether enter switched Minecraft's zero-to-one off, for leave to switch back
    private static boolean switched;
    *///?}

    /** Tells {@code CgGL} Minecraft's clip control: once, on the render thread, as the GL backend is made. */
    public static void install() {
        //? if >=26.2 {
        /*// Minecraft sets zero-to-one once, where the driver has clip control, and says so here
        CgGL.setClipZeroToOne(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        *///?}
    }

    public static void enter() {
        //? if >=26.2 {
        /*if (GraphicsApi.vulkan()) {
            // The tracked backend applies GL's clip range to our own passes itself.
            CgGL.glClearDepth(1.0);
            return;
        }
        if (CgGL.isClipZeroToOne()) {
            ARBClipControl.glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
            CgGL.setClipZeroToOne(false);
            switched = true;
        }
        CgGL.glClearDepth(1.0);
        *///?}
    }

    public static void leave() {
        //? if >=26.2 {
        /*if (switched) {
            switched = false;
            ARBClipControl.glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);
            CgGL.setClipZeroToOne(true);
        }
        *///?}
    }
}
