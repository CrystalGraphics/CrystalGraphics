package com.crystalgraphics.mc.v1710.platform.gl;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.state.GLCheckedHostState;
import com.crystalgraphics.platform.gl.state.GLHostState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;

/**
 * Minecraft 1.7.10's host state, with no {@code glGet} once checked: the bindings it holds at every hook answered from
 * Minecraft, every other domain kept by the attribute stack (1.7.10's context is a compatibility one), which costs no
 * round trip.
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(driver, translator, w, h, false, HostState1710.create(driver));
 * }</pre>
 *
 * <ul>
 *   <li>What it answers: program 0 and no vertex array (1.7.10 draws fixed-function), and Minecraft's main framebuffer
 *       bound, or none where framebuffers are off.</li>
 *   <li>Each answer is compared with {@code glGet} for its first reads ({@link GLCheckedHostState}); a shader mod binding
 *       its own program or framebuffer at a hook is caught there, and that domain reads {@code glGet} from then on.</li>
 * </ul>
 */
public final class HostState1710 {

    private HostState1710() {}

    /** Minecraft's host state over {@code gl}. Render thread. */
    public static GLHostState create(CgGLDriver gl) {
        return new GLCheckedHostState("Minecraft 1.7.10", gl, (domain, s) -> {
            switch (domain) {
                case PROGRAM:
                    s.program = 0;
                    return true;
                case VERTEX_ARRAY:
                    s.vertexArray = 0;
                    return true;
                case FRAMEBUFFERS:
                    s.drawFramebuffer = s.readFramebuffer = OpenGlHelper.isFramebufferEnabled()
                            ? Minecraft.getMinecraft().getFramebuffer().framebufferObject : 0;
                    return true;
                default:
                    return false;   // the attribute stack keeps it
            }
        }, false);
    }
}
