package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A development demo drawn through {@link CgWorldRenderer} under the host's own camera, on unless turned off:
 * {@link CgVfxBlasts}, about 360,000 GPU particles bursting over the ground ahead of the player, or the sixteen spheres
 * and thirty beams of {@link CgVfxShowcase}.
 *
 * <pre>{@code
 * -Dcrystalgraphics.demo=false                        // draw nothing
 * -Dcrystalgraphics.demo=spheres                      // the showcase's spheres and beams instead of the blasts
 * -Dcrystalgraphics.demo.blasts=240                   // how many blasts, 120 by default
 * -Dcrystalgraphics.demo.sky=false                    // with the spheres: keep the world's own sky, not the showcase's
 * -Dcrystalgraphics.demo.capture=build/demo.png       // and write the world, with no GUI over it, to a PNG
 * -Dcrystalgraphics.demo.captureAt=300                // this many world frames after the demo was placed
 * }</pre>
 *
 * <p>The demo is placed on the first world frame, some blocks ahead of the camera, standing on the world's ground there
 * ({@link CgWorldQueries#groundBelow}); until the ground answers (no world, or its chunk still loading) it floats a little
 * above the eye, and settles onto the ground once it does. It stays there however far the player walks or flies, and
 * moves only when the level changes or the camera jumps more than {@code TELEPORT} blocks in one frame: a teleport, or
 * the server placing the player after the first frames saw the default spawn.</p>
 */
public final class CgRenderDemo {

    public static final CgRenderDemo INSTANCE = new CgRenderDemo();

    private static final Logger LOGGER = LogManager.getLogger("CgRenderDemo");

    private static final String MODE = System.getProperty("crystalgraphics.demo");
    private static final boolean ENABLED = !"false".equals(MODE), SPHERES = "spheres".equals(MODE);
    private static final boolean SKY = !"false".equals(System.getProperty("crystalgraphics.demo.sky"));
    private static final String CAPTURE = System.getProperty("crystalgraphics.demo.capture");
    private static final int CAPTURE_AT = Integer.getInteger("crystalgraphics.demo.captureAt", 300);

    // blocks from the eye to the demo's centre, along the view: the blasts' near edge some blocks ahead
    private static final int AHEAD = SPHERES ? 12 : (int) CgVfxBlasts.REACH + 10;
    private static final int ABOVE = 2;       // and up the screen, while it floats
    private static final int TELEPORT = 32;   // blocks the camera may move in one frame before the grid follows
    private static final int SEARCH = 24;     // blocks over the eye the ground search starts from, down twice as far

    private boolean installed;
    private boolean anchored, grounded;
    private long anchorX, anchorZ;
    private double anchorY, eyeY;
    private double lastX, lastY, lastZ;
    private int levelEpoch;
    private int worldFrames;

    private final CgVfxShowcase showcase = SPHERES ? CgVfxShowcase.stress(30) : null;
    private final CgVfxBlasts blasts = SPHERES ? null : new CgVfxBlasts(Integer.getInteger("crystalgraphics.demo.blasts", 120));

    /** The transparent stage's frame, for the capture callback, which runs inside that firing. */
    private CgHostFrame captured;
    private final Runnable capture = () -> capture(captured.mainFramebuffer(), captured.width(), captured.height());

    /** The blasts' readout: the median of the particles drawn each frame over the last half second, top left. */
    private static final long COUNT_WINDOW_NANOS = 500_000_000L;
    private static final int COUNT_PX = 40;
    private CgTextRenderer counter;
    private CgFont counterFont;
    private boolean counterFailed;
    private int[] countSamples = new int[256];
    private int countSampleCount, countMedian = -1;
    private long countWindowStart;
    private String countText = "Particles: -";
    private final Runnable drawCount = this::drawCount;
    private CgRenderDemo() {
    }

    /** Submits the demo every frame, once, unless {@code -Dcrystalgraphics.demo=false}. */
    public void install() {
        if (installed || !ENABLED) return;
        installed = true;
        CgWorldRenderer.get().onFrame(this::frame);
        if (blasts != null) {
            CgRenderStage.WORLD_TRANSPARENT.register(CgWorldRenderer.ORDER + 1000,
                    frame -> frame.callback("demo.particles", drawCount));        }
        if (CAPTURE != null) {
            CgRenderStage.WORLD_TRANSPARENT.register(CgWorldRenderer.ORDER + 1, frame -> {
                if (++worldFrames != CAPTURE_AT) return;
                captured = frame.host();
                frame.callback("demo.capture", capture);
            });
        }
    }

    /** Releases GPU resources. Call on context destroy. */
    public void dispose() {
        if (showcase != null) showcase.delete();
        if (blasts != null) blasts.delete();
        if (counter != null && !counter.isDeleted()) counter.delete();
        counter = null;
    }

    /** Samples this frame's particles drawn and writes the latest half second's median over the frame. */
    private void drawCount() {
        if (counterFailed) return;
        long now = System.nanoTime();
        if (countSampleCount == countSamples.length) countSamples = Arrays.copyOf(countSamples, countSampleCount * 2);
        countSamples[countSampleCount++] = CgVfxSystem.particlesDrawn();        if (now - countWindowStart >= COUNT_WINDOW_NANOS) {
            Arrays.sort(countSamples, 0, countSampleCount);
            int median = countSamples[countSampleCount / 2];
            if (median != countMedian) {
                countMedian = median;
                countText = String.format("Particles: %,d", median);
            }
            countSampleCount = 0;
            countWindowStart = now;
        }
        try {
            if (counterFont == null) {
                CgSystemFonts fonts = CgSystemFonts.get();
                CgSystemFontFace face = fonts.generic(CgGenericFamily.SANS_SERIF, CgFontStyle.REGULAR);
                if (face == null) {
                    counterFailed = true;
                    LOGGER.warn("[CgRenderDemo] no sans-serif font installed: no particle count");
                    return;
                }
                counterFont = fonts.load(face, CgFontStyle.REGULAR, COUNT_PX);
            }
            if (counter == null) counter = CgTextRenderer.create();
            counter.beginBatch();
            counter.draw().text(countText).font(counterFont).at(16f, 16f + COUNT_PX).color(0xFFFF2A2A)
                    .stroke(0.12f, 0xFF000000).submit();
            counter.endBatch();
        } catch (RuntimeException e) {
            counterFailed = true;
            LOGGER.error("[CgRenderDemo] the particle count failed; it stays off", e);
        }
    }

    private void frame(CgHostView view) {
        // Again after a jump: the first world frames can see the default spawn, before the server places the player.
        int epoch = CgPlatform.get(CgWorldQuery.SERVICE).levelEpoch();
        if (!anchored || jumped(view) || epoch != levelEpoch) {
            levelEpoch = epoch;
            anchor(view);
        }
        if (!grounded) ground();
        lastX = view.x();
        lastY = view.y();
        lastZ = view.z();
        if (blasts != null) {
            blasts.submit(CgWorldRenderer.get(), anchorX + 0.5, anchorY, anchorZ + 0.5, CgFrameClock.seconds());
            return;
        }
        showcase.submit(CgWorldRenderer.get(), anchorX + 0.5, anchorY, anchorZ + 0.5, CgFrameClock.seconds());
        if (SKY) showcase.submitSky(CgWorldRenderer.get(), view.x(), view.y(), view.z());
    }

    private boolean jumped(CgHostView view) {
        double dx = view.x() - lastX, dy = view.y() - lastY, dz = view.z() - lastZ;
        return dx * dx + dy * dy + dz * dz > TELEPORT * TELEPORT;
    }

    /**
     * Puts the grid ahead of the eye, whatever the host folds into its view matrix: along the view and up the screen
     * while it floats, and level with the eye where it will stand on the ground.
     */
    private void anchor(CgHostView view) {
        Matrix4f toWorld = new Matrix4f(view.view()).invert();
        Vector3f eye = toWorld.transformPosition(new Vector3f());
        Vector3f forward = toWorld.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
        Vector3f up = toWorld.transformDirection(new Vector3f(0f, 1f, 0f)).normalize();
        double level = Math.hypot(forward.x, forward.z);
        double aheadX = level > 1.0e-3 ? forward.x / level : forward.x, aheadZ = level > 1.0e-3 ? forward.z / level : forward.z;
        anchorX = (long) Math.floor(view.x() + eye.x + aheadX * AHEAD);
        anchorZ = (long) Math.floor(view.z() + eye.z + aheadZ * AHEAD);
        eyeY = view.y() + eye.y;
        anchorY = Math.floor(eyeY + forward.y * AHEAD + up.y * ABOVE);
        anchored = true;
        grounded = false;
        worldFrames = 0;
        LOGGER.info("[CgRenderDemo] demo around ({}, {}, {}), camera at ({}, {}, {})",
                anchorX, anchorY, anchorZ, view.x(), view.y(), view.z());
    }

    /** Stands the grid on the ground under it, once the world answers. */
    private void ground() {
        double floor = CgWorldQueries.groundBelow(anchorX + 0.5, eyeY + SEARCH, anchorZ + 0.5, SEARCH * 3);
        if (Double.isNaN(floor)) return;
        anchorY = floor;
        grounded = true;
        LOGGER.info("[CgRenderDemo] demo on the ground at y {}", floor);
    }

    /**
     * The host's target as it stands after the transparent stage, written once the GPU has copied it, frames later: a
     * hosted Vulkan device refuses a read that waits.
     */
    private static void capture(int framebuffer, int w, int h) {
        CgReadback.pixels(Math.max(framebuffer, 0), 0, 0, w, h, CgTextureType.RGBA8, pixels -> write(pixels, w, h));
    }

    private static void write(ByteBuffer pixels, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = ((h - 1 - y) * w + x) * 4;
                image.setRGB(x, y, (pixels.get(at) & 0xFF) << 16 | (pixels.get(at + 1) & 0xFF) << 8
                        | (pixels.get(at + 2) & 0xFF));
            }
        }
        File out = new File(CAPTURE).getAbsoluteFile();
        try {
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            ImageIO.write(image, "png", out);
            LOGGER.info("[CgRenderDemo] wrote {}x{} capture to {}", w, h, out);
        } catch (IOException e) {
            LOGGER.error("[CgRenderDemo] could not write {}", out, e);
        }
    }
}
