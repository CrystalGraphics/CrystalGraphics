package com.crystalgraphics.platform.input;

import com.crystalgraphics.platform.CgFrameThread;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The system clipboard's text, through the window toolkit the host runs on. Callable from any thread.
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
 *   <li>A toolkit reaches its clipboard on the frame thread only, so from any other thread a write is queued for the
 *       next frame ({@link CgFrameThread}), in order, and a read waits for that frame: {@value #READ_WAIT_MS} ms at
 *       most, then it answers {@code ""} and warns once. Read on a worker or a UI thread of its own, never one the frame
 *       thread waits on.</li>
 * </ul>
 */
public final class CgClipboard {

    private static final Logger LOGGER = LogManager.getLogger("CgPlatform");
    private static final long READ_WAIT_MS = 250L;
    private static volatile boolean readTimeoutReported;

    /** What a window toolkit answers. One class per toolkit: {@code ClipboardGlfw}, {@code ClipboardLwjgl2}, {@code ClipboardSdl}. */
    public interface Host {
        /** The clipboard's text, {@code ""} where it holds none. The frame thread. */
        String get();

        /** Puts {@code text} on the clipboard. The frame thread. */
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
        if (CgFrameThread.isCurrent()) return read();
        CompletableFuture<String> answer = new CompletableFuture<>();
        CgFrameThread.run(() -> answer.complete(read()));
        try {
            return answer.get(READ_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException late) {
            if (!readTimeoutReported) {
                readTimeoutReported = true;
                LOGGER.warn("The frame thread did not answer a clipboard read within {} ms; it read as empty", READ_WAIT_MS);
            }
            return "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Puts {@code text} on the clipboard: now on the frame thread, at the next frame from any other. */
    public static void set(String text) {
        CgFrameThread.run(() -> CgPlatform.get(HOST).set(text));
    }

    private static String read() {
        String text = CgPlatform.get(HOST).get();
        return text == null ? "" : text;
    }
}
