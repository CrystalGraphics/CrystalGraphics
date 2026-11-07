package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The host's window, in device pixels: what a UI is laid out against.
 *
 * <pre>{@code
 * float logicalWidth = CgHostWindow.width() / scale;
 *
 * // a host, once, on a client
 * CgPlatform.provide(CgHostWindow.HOST, new HostWindowModern());
 * }</pre>
 *
 * <ul>
 *   <li>Without a host (a server, a test) the window is 0 by 0.</li>
 *   <li>The size is the window's, not the GUI-scaled one.</li>
 * </ul>
 */
public final class CgHostWindow {

    /** What a host answers. One class per era, {@code HostWindow<Era>}. */
    public interface Host {

        /** The window's width in device pixels; 0 with none. */
        int width();

        /** The window's height in device pixels; 0 with none. */
        int height();

        Host NONE = new Host() {
            @Override public int width() { return 0; }
            @Override public int height() { return 0; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_window", Host.NONE);

    private CgHostWindow() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** The window's width in device pixels. */
    public static int width() {
        return host().width();
    }

    /** The window's height in device pixels. */
    public static int height() {
        return host().height();
    }
}
