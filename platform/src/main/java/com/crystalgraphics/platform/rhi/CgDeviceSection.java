package com.crystalgraphics.platform.rhi;

/**
 * A host section: the host hands its frame to the installed device and gets it back. On a host sharing the device's
 * context or device (Minecraft), the outermost section is where the device reads what the host left
 * ({@link CgRHI#fromHost}) and where it sets that back and hands over what it recorded ({@link CgRHI#toHost}). Nothing
 * where the device owns its context.
 *
 * <pre>{@code
 * CgDeviceSection.begin();          // a render event, a screen or the HUD hands us the frame
 * try {
 *     CgRenderPass pass = CgRHI.get().encoder().beginPass(desc);
 *     ...
 * } finally {
 *     CgDeviceSection.end();        // and gets it back
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Sections nest: only the outermost hands the device over and back, so work that may land inside one (a texture's
 *       upload, a graph's execution) opens its own, and a host's hook opens one round all of it, so the hand-over is
 *       once a hook.</li>
 *   <li>The host draws nothing between {@link #begin} and {@link #end}.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgDeviceSection {

    private static int depth;
    /** The device the open section handed the frame to. */
    private static CgRHI entered;

    private CgDeviceSection() {}

    public static void begin() {
        if (depth++ > 0) return;
        entered = CgRHI.installed() ? CgRHI.get() : null;
        if (entered != null) entered.fromHost();
    }

    public static void end() {
        if (depth == 0) throw new IllegalStateException("a device section ended that was not begun");
        if (--depth > 0) return;
        CgRHI device = entered;
        entered = null;
        if (device != null) device.toHost();
    }

    /**
     * {@link CgRHI#install}: a device installed inside an open section takes the frame for the rest of it; one
     * uninstalled there was handed back by whoever closed it.
     */
    static void installed(CgRHI rhi) {
        if (depth == 0) return;
        if (rhi == null) {
            entered = null;
            return;
        }
        if (entered != null) return;
        entered = rhi;
        rhi.fromHost();
    }

    /** Whether a section is open now. */
    public static boolean open() {
        return depth > 0;
    }
}
