package com.crystalgraphics.gauntlet;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.platform.io.CgAtomicFile;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgRenderStage;

/**
 * Photographs the host's frame as the player sees it, into a PNG: Unreal's {@code FScreenshotRequest} for a run. The
 * engine reads the host's own target back at the frame's end, once the host's GUI has drawn too, so every version and
 * device takes it the same way and no host takes a screenshot of its own.
 *
 * <pre>{@code
 * CgGauntletCapture.Shot shot = CgGauntletCapture.request(Path.of("run/title.png"));
 * // ...frames later:
 * if (shot.done()) log(shot.written() ? "photographed" : shot.failure());
 * }</pre>
 *
 * <ul>
 *   <li>A controller calls {@code capture(name)} instead, which names the file in the run's folder.</li>
 *   <li>It is taken at the host's frame end, from the target its stages drew into that frame: whatever drew that
 *       frame, ours or the host's, is in it. A frame no stage fired in takes none; the next one does.</li>
 *   <li>The read lands frames after it is asked for: a hosted Vulkan device refuses a read that waits. The run waits
 *       for every shot before it quits.</li>
 * </ul>
 */
public final class CgGauntletCapture {

    private static final Logger LOGGER = LogManager.getLogger("CgGauntlet");

    /** Shots asked for and not yet read. */
    private static final Queue<Shot> WANTED = new ConcurrentLinkedQueue<>();
    /** Shots read or waiting on the GPU. */
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static boolean installed;
    /** This frame's picture, as its stages last saw the host: render thread. */
    private static int framebuffer, width, height;
    private static boolean seen, gui;

    private CgGauntletCapture() {
    }

    /** One photograph on its way. */
    public static final class Shot {
        private final Path file;
        private volatile boolean done, written;
        private volatile String failure = "";

        Shot(Path file) {
            this.file = file;
        }

        /** Where it is written. */
        public Path file() {
            return file;
        }

        /** Whether it has finished, written or failed. */
        public boolean done() {
            return done;
        }

        /** Whether the PNG is on disk. */
        public boolean written() {
            return written;
        }

        /** Why it was not written; empty when it was. */
        public String failure() {
            return failure;
        }

        void finish(boolean ok, String why) {
            written = ok;
            failure = why;
            done = true;
            PENDING.decrementAndGet();
        }
    }

    /** Asks for the next frame's picture to be written to {@code file}. Any thread. */
    public static Shot request(Path file) {
        install();
        Shot shot = new Shot(file);
        PENDING.incrementAndGet();
        WANTED.add(shot);
        return shot;
    }

    /** Shots asked for and not finished: the run waits for none before it quits. */
    public static int pending() {
        return PENDING.get();
    }

    private static synchronized void install() {
        if (installed) return;
        installed = true;
        // The stages only note the target; the frame end reads it. A host batches its GUI and draws it after the stage
        // fires, so a read at the stage misses it (1.20.1's title buttons).
        CgRenderStage.WORLD_TRANSPARENT.register(Integer.MAX_VALUE, frame -> note(frame.host(), false));
        CgRenderStage.HUD.register(Integer.MAX_VALUE, frame -> note(frame.host(), true));
        CgRenderStage.SCREEN.register(Integer.MAX_VALUE, frame -> note(frame.host(), true));
    }

    private static void note(CgHostFrame host, boolean withGui) {
        framebuffer = Math.max(host.mainFramebuffer(), 0);
        width = host.width();
        height = host.height();
        gui |= withGui;
        seen = true;
    }

    /** The host's frame end, inside its device section: every shot asked for is read from this frame's picture. */
    static void frameEnd() {
        boolean drawn = seen;
        seen = false;
        boolean withGui = gui;
        gui = false;
        if (!drawn || WANTED.isEmpty()) return;
        int w = width, h = height;
        for (Shot shot; (shot = WANTED.poll()) != null; ) {
            CgReadback.pixels(framebuffer, 0, 0, w, h, CgTextureType.RGBA8, writer(shot, w, h, withGui));
        }
    }

    private static CgReadback.Sink writer(Shot shot, int w, int h, boolean gui) {
        return new CgReadback.Sink() {
            @Override
            public void accept(ByteBuffer pixels) {
                try {
                    CgAtomicFile.write(shot.file, png(pixels, w, h));
                    LOGGER.info("gauntlet wrote {}x{} capture to {} ({})", w, h, shot.file.toAbsolutePath(),
                            gui ? "with the GUI" : "the world, the GUI hidden");
                    shot.finish(true, "");
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("gauntlet could not write {}", shot.file, e);
                    shot.finish(false, e.toString());
                }
            }

            @Override
            public void failed(String reason) {
                LOGGER.warn("gauntlet capture {} was never read: {}", shot.file, reason);
                shot.finish(false, reason);
            }
        };
    }

    /** Rows bottom first, RGBA, into a PNG. */
    private static byte[] png(ByteBuffer pixels, int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = ((h - 1 - y) * w + x) * 4;
                image.setRGB(x, y, (pixels.get(at) & 0xFF) << 16 | (pixels.get(at + 1) & 0xFF) << 8
                        | (pixels.get(at + 2) & 0xFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
