package com.crystalgraphics.platform.gl.tracked;

/**
 * A device that can move layouts and fence transfers on its own, for a client that cannot state them: the tracked
 * backend's {@code CgGL} streams outside a graph, which GL leaves implicit. Off, the device records only what is
 * stated ({@code crystalgraphics.vulkan.explicit}); a device that never infers ignores it.
 *
 * <pre>{@code
 * if (device instanceof CgImplicitLayouts l) l.implicitLayouts(false);   // a graph states every transition from here
 * ... the graph's steps ...
 * if (device instanceof CgImplicitLayouts l) l.implicitLayouts(true);    // GL streams again
 * }</pre>
 *
 * <p>Goes with the tracked backend (S7).</p>
 */
public interface CgImplicitLayouts {

    void implicitLayouts(boolean on);
}
