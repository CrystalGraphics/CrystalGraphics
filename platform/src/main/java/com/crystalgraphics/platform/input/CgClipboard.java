package com.crystalgraphics.platform.input;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The system clipboard's text, through the window toolkit the host runs on.
 *
 * <pre>{@code
 * CgClipboard.set(selection);
 * String pasted = CgClipboard.get();   // "" when it holds no text
 * }</pre>
 *
 * Provided by the toolkit, once, from its client install:
 *
 * <pre>{@code
 * CgPlatform.provide(CgClipboard.HOST, ClipboardGlfw.of(window));
 * }</pre>
 *
 * <ul>
 *   <li>{@link #get} is never null: an empty clipboard, or one holding no text, is {@code ""}.</li>
 *   <li>Without a toolkit (a dedicated server, a test) it holds nothing and a write is dropped.</li>
 *   <li>Client thread: a toolkit reaches its clipboard where it polls. From another thread, hop with core's
 *       {@code CgHostThread}: {@code CgHostThread.CLIENT.run(() -> CgClipboard.set(text))}, and
 *       {@code CgHostThread.CLIENT.call(CgClipboard::get).then(paste)} for a read, answered on the asking thread.</li>
 * </ul>
 */
public final class CgClipboard {

    /** What a window toolkit answers. One class per toolkit: {@code ClipboardGlfw}, {@code ClipboardLwjgl2}, {@code ClipboardSdl}. */
    public interface Host {
        /** The clipboard's text, {@code ""} where it holds none. */
        String get();

        /** Puts {@code text} on the clipboard. */
        void set(String text);

        Host NONE = new Host() {
            @Override public String get() { return ""; }
            @Override public void set(String text) { }
        };
    }

    /** The slot a toolkit fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:clipboard", Host.NONE);

    private CgClipboard() {
    }

    /** The clipboard's text, {@code ""} where it holds none. */
    public static String get() {
        String text = CgPlatform.get(HOST).get();
        return text == null ? "" : text;
    }

    /** Puts {@code text} on the clipboard. */
    public static void set(String text) {
        CgPlatform.get(HOST).set(text);
    }
}
