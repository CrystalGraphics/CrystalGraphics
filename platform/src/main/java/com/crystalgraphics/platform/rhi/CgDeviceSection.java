package com.crystalgraphics.platform.rhi;

/**
 * Hands the installed device's command list to code drawing through {@link CgRHI} and back, while another front (the
 * GL compatibility layer) shares the device: the host knows how its fronts share it. Nothing where no host installed one.
 *
 * <pre>{@code
 * CgDeviceSection.begin();          // the other front's open pass ends; nothing of it records until end()
 * try {
 *     CgRenderPass pass = CgRHI.get().encoder().beginPass(desc);
 *     ...
 * } finally {
 *     CgDeviceSection.end();
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Nothing goes through the other front between {@link #begin} and {@link #end}: its uploads and binds come
 *       before.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgDeviceSection {

    /** What a host does at each end of a section. */
    public interface Host {
        void begin();

        void end();
    }

    private static volatile Host host;

    private CgDeviceSection() {}

    /** Installs {@code h}; null when its device goes. */
    public static void install(Host h) {
        host = h;
    }

    public static void begin() {
        Host h = host;
        if (h != null) h.begin();
    }

    public static void end() {
        Host h = host;
        if (h != null) h.end();
    }
}
