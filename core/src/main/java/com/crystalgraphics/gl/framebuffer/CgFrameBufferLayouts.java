package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Framebuffer attachment layouts the current driver has found complete, kept across launches under
 * {@code crystalgraphics/cache/framebuffer/<driver>.txt}, so a layout's {@code glCheckFramebufferStatus} runs once per
 * driver rather than once per launch. On a threaded driver each check waits for its whole queue: 120 to 140 ms behind the
 * loading screen's compiles (Forge 1.20.1).
 *
 * <pre>{@code
 * String key = kind + "|" + format.attachmentKey();
 * if (!CgFrameBufferLayouts.complete(key)) { check(); CgFrameBufferLayouts.found(key); }
 * }</pre>
 *
 * Render thread. A driver update is another driver, so its layouts are checked again.
 */
final class CgFrameBufferLayouts {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    private static final Set<String> COMPLETE = new HashSet<>();
    /** The capabilities the file was read for: a new context may be another driver. */
    @Nullable private static CgCapabilities readFor;
    @Nullable private static Path file;

    private CgFrameBufferLayouts() {}

    /** Whether {@code key}'s layout was found complete on this driver, this launch or an earlier one. */
    static boolean complete(String key) {
        load();
        return COMPLETE.contains(key);
    }

    /** {@code key}'s layout found complete: kept, and written for later launches on a worker. */
    static void found(String key) {
        load();
        if (!COMPLETE.add(key) || file == null) return;
        Path at = file;
        byte[] line = (key + "\n").getBytes(StandardCharsets.UTF_8);
        CgJob.run(() -> append(at, line));
    }

    private static void load() {
        CgCapabilities caps = CgCapabilities.detect();
        if (caps == readFor) return;
        readFor = caps;
        COMPLETE.clear();
        Path dir = CgCacheDirectory.of("framebuffer");
        file = dir == null ? null : dir.resolve(CgCacheDirectory.driver() + ".txt");
        if (file == null) return;
        try {
            Collections.addAll(COMPLETE, new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n"));
            COMPLETE.remove("");
        } catch (NoSuchFileException e) {
            // A first launch on this driver: every layout is checked once
        } catch (IOException e) {
            LOG.debug("Framebuffer layouts not read from {}: {}", file, e.toString());
        }
    }

    private static synchronized void append(Path at, byte[] line) {
        try {
            Files.write(at, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.debug("Framebuffer layout not kept in {}: {}", at, e.toString());
        }
    }
}
