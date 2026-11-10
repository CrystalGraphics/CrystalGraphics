package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The hand-over around anything that paints with CrystalGraphics inside the host's frame, outside a world pass: a
 * screen, a HUD layer. The host knows where it draws and what it assumes next: it opens a device section, names its
 * target as the current one, and sets back what its own renderer assumes.
 *
 * <pre>{@code
 * CgHostPaint.enter();
 * try { paint(); } finally { CgHostPaint.leave(); }
 *
 * // a host, once, on a client
 * CgPlatform.provide(CgHostPaint.HOST, new HostPaintModern());
 * }</pre>
 *
 * <ul>
 *   <li>{@link #leave()} must follow every {@link #enter()}, or the host's next draw runs on our state.</li>
 *   <li>Without a host (a server, a test) both do nothing.</li>
 * </ul>
 */
public final class CgHostPaint {

    /** What a host answers. One class per era, {@code HostPaint<Era>}. */
    public interface Host {

        /** Takes the frame from the host: a device section open, the host's target the current one. */
        void enter();

        /** Hands the frame back with the state the host's own renderer assumes. */
        void leave();

        Host NONE = new Host() {
            @Override public void enter() { }
            @Override public void leave() { }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_paint", Host.NONE);

    private CgHostPaint() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    public static void enter() {
        host().enter();
    }

    public static void leave() {
        host().leave();
    }
}
