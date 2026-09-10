package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * Presents a mouse cursor. One method, and it knows nothing about what a cursor <i>means</i>.
 *
 * <pre>{@code
 * // a consumer, which owns the vocabulary:
 * CgPlatform.get(CgCursorService.SERVICE).show(CgCursorImage.standard("pointing-hand"));
 * CgPlatform.get(CgCursorService.SERVICE).show(null);   // back to the system arrow
 *
 * // a loader, once, with whatever value its toolkit needs:
 * CgPlatform.provide(CgCursorService.SERVICE, new GlfwCursorService(window::handle));
 * }</pre>
 *
 * <p><b>A slot, not a bundle method</b> — an unpresented cursor is cosmetic, and this engine runs
 * where there is nothing to present to: a dedicated server, a headless test, a fixture with no
 * window. Those register nothing and read {@link #NONE}, rather than being made to supply a stub.
 *
 * <p>Easy to get wrong, in a line each:
 * <ul>
 *   <li><b>{@code show(null)} is the reset</b>, not a no-op — it asks for the system default.</li>
 *   <li>An implementation <b>caches by {@link CgCursorImage#name()}</b>. Uploading pixels per call
 *       leaks a native handle every time the pointer crosses an element.</li>
 *   <li>An implementation <b>must not throw.</b> This is called from hover handling on the frame
 *       thread; a toolkit that cannot honour a shape gives the caller the default and stops trying.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgCursorService {

    /** Presents nothing. What a server, a test or any host with no window reads. */
    CgCursorService NONE = image -> { };

    CgService<CgCursorService> SERVICE = CgService.of("crystalgraphics:cursor", NONE);

    /**
     * Show {@code image}, or the system default when it is null.
     *
     * @param image the picture to present, or null to restore the default arrow
     */
    void show(CgCursorImage image);
}
