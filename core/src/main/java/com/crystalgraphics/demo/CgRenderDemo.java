package com.crystalgraphics.demo;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A development demo: the sixteen spheres of {@link CgVfxShowcase} floating in front of the player, drawn through
 * {@link CgWorldRenderer} under the host's own camera. Off unless asked for.
 *
 * <pre>{@code
 * -Dcrystalgraphics.demo=true                         // draw them
 * -Dcrystalgraphics.demo.capture=build/demo.png       // and write the world, with no GUI over it, to a PNG
 * -Dcrystalgraphics.demo.captureAt=300                // this many world frames after the spheres were placed
 * }</pre>
 *
 * <p>The grid is placed on the first world frame, on a whole block some blocks along the camera's view and a little
 * above it, so terrain in front does not hide it, and stays there until the camera jumps far from it.</p>
 */
public final class CgRenderDemo {

    public static final CgRenderDemo INSTANCE = new CgRenderDemo();

    private static final Logger LOGGER = LogManager.getLogger("CgRenderDemo");

    private static final boolean ENABLED = Boolean.getBoolean("crystalgraphics.demo");
    private static final String CAPTURE = System.getProperty("crystalgraphics.demo.capture");
    private static final int CAPTURE_AT = Integer.getInteger("crystalgraphics.demo.captureAt", 300);

    private static final int AHEAD = 12;      // blocks from the eye to the grid's centre, along the view
    private static final int ABOVE = 2;       // and up the screen
    private static final int REANCHOR_DISTANCE = 48;

    private boolean installed;
    private boolean anchored;
    private long anchorX, anchorY, anchorZ;
    private int worldFrames;

    private final CgVfxShowcase showcase = new CgVfxShowcase();

    /** The transparent stage's frame, for the capture callback, which runs inside that firing. */
    private CgHostFrame captured;
    private final Runnable capture = () -> capture(captured.width(), captured.height());

    private CgRenderDemo() {
    }

    /** Submits the spheres every frame, once, when {@code -Dcrystalgraphics.demo=true}. */
    public void install() {
        if (installed || !ENABLED) return;
        installed = true;
        CgWorldRenderer.get().onFrame(this::frame);
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
        showcase.delete();
    }

    private void frame(CgHostView view) {
        // Again after a jump: the first world frames can see the default spawn, before the server places the player.
        if (!anchored || farFromGrid(view)) anchor(view);
        showcase.submit(CgWorldRenderer.get(), anchorX + 0.5, anchorY, anchorZ + 0.5, CgFrameClock.seconds());
    }

    private boolean farFromGrid(CgHostView view) {
        double dx = view.x() - anchorX, dy = view.y() - anchorY, dz = view.z() - anchorZ;
        return dx * dx + dy * dy + dz * dz > REANCHOR_DISTANCE * REANCHOR_DISTANCE;
    }

    /** Puts the grid ahead of the eye and up the screen, whatever the host folds into its view matrix. */
    private void anchor(CgHostView view) {
        Matrix4f toWorld = new Matrix4f(view.view()).invert();
        Vector3f eye = toWorld.transformPosition(new Vector3f());
        Vector3f forward = toWorld.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
        Vector3f up = toWorld.transformDirection(new Vector3f(0f, 1f, 0f)).normalize();
        anchorX = (long) Math.floor(view.x() + eye.x + forward.x * AHEAD + up.x * ABOVE);
        anchorY = (long) Math.floor(view.y() + eye.y + forward.y * AHEAD + up.y * ABOVE);
        anchorZ = (long) Math.floor(view.z() + eye.z + forward.z * AHEAD + up.z * ABOVE);
        anchored = true;
        worldFrames = 0;
        LOGGER.info("[CgRenderDemo] spheres around ({}, {}, {}), camera at ({}, {}, {})",
                anchorX, anchorY, anchorZ, view.x(), view.y(), view.z());
    }

    /** The host's target as it stands after the transparent stage. Synchronous: a diagnostic, once. */
    private static void capture(int w, int h) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        CgGL.glReadPixels(0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, pixels);
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
