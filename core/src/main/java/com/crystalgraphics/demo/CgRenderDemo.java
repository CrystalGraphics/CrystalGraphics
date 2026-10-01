package com.crystalgraphics.demo;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.platform.gl.CgGL;
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
 * A development demo: sixteen rainbow cubes standing on blocks in front of the player, drawn through
 * {@link CgWorldRenderer} under the host's own camera. Off unless asked for.
 *
 * <pre>{@code
 * -Dcrystalgraphics.demo=true                         // draw them
 * -Dcrystalgraphics.demo.capture=build/demo.png       // and write the world, with no GUI over it, to a PNG
 * -Dcrystalgraphics.demo.captureAt=300                // after this many world frames (300 by default)
 * }</pre>
 *
 * <p>The cubes are placed once, on the first world frame, on whole blocks a few blocks along the camera's view and
 * a little above it, so terrain in front does not hide them, and stay there. Each fills exactly one block cell, so a
 * capture shows whether the stage's view is the camera the world was drawn with: on the grid at every angle, or off
 * it.</p>
 */
public final class CgRenderDemo {

    public static final CgRenderDemo INSTANCE = new CgRenderDemo();

    private static final Logger LOGGER = LogManager.getLogger("CgRenderDemo");

    private static final boolean ENABLED = Boolean.getBoolean("crystalgraphics.demo");
    private static final String CAPTURE = System.getProperty("crystalgraphics.demo.capture");
    private static final int CAPTURE_AT = Integer.getInteger("crystalgraphics.demo.captureAt", 300);

    private static final int GRID = 4;        // 4×4 = 16 cubes
    private static final int GRID_STEP = 2;   // blocks between cubes
    private static final int AHEAD = 6;       // blocks from the eye to the grid's centre, along the view
    private static final int ABOVE = 2;       // and up the screen

    private boolean installed;
    private boolean anchored;
    private long anchorX, anchorY, anchorZ;
    private int worldFrames;

    private CgMesh cubeMesh;
    private CgMaterial cubeMaterial;
    private final float[][] colours = new float[GRID * GRID][];

    /** The transparent stage's frame, for the capture callback, which runs inside that firing. */
    private CgHostFrame captured;
    private final Runnable capture = () -> capture(captured.width(), captured.height());

    private CgRenderDemo() {
        for (int c = 0; c < colours.length; c++) colours[c] = hsvToRgb(c / (float) colours.length, 0.85f, 1.0f);
    }

    /** Submits the cubes every frame, once, when {@code -Dcrystalgraphics.demo=true}. */
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
        if (cubeMesh != null) { cubeMesh.delete(); cubeMesh = null; }
        cubeMaterial = null; // owned by CgMaterialRegistry — do not delete
    }

    private void frame(CgHostView view) {
        if (cubeMesh == null) {
            cubeMesh = CgMesh.upload(CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL));
            cubeMaterial = CgMaterial.load("crystalgraphics:shaders/demo_render.shader");
            LOGGER.info("[CgRenderDemo] resources initialised (mesh={}, material={})", cubeMesh, cubeMaterial);
        }
        if (!anchored) anchor(view);
        CgWorldRenderer world = CgWorldRenderer.get();
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                float[] rgb = colours[i * GRID + j];
                world.draw(cubeMesh, cubeMaterial)
                        .at(anchorX + (i - GRID / 2) * GRID_STEP + 0.5, anchorY + 0.5,
                                anchorZ + (j - GRID / 2) * GRID_STEP + 0.5)
                        .custom(0, rgb[0], rgb[1], rgb[2], 1f)
                        .submit();
            }
        }
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
        LOGGER.info("[CgRenderDemo] cubes on blocks around ({}, {}, {}), camera at ({}, {}, {})",
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

    private static float[] hsvToRgb(float h, float s, float v) {
        int   hi = (int)(h * 6f) % 6;
        float f  = h * 6f - (int)(h * 6f);
        float p  = v * (1f - s);
        float q  = v * (1f - f * s);
        float t  = v * (1f - (1f - f) * s);
        switch (hi) {
            case 0:  return new float[]{ v, t, p };
            case 1:  return new float[]{ q, v, p };
            case 2:  return new float[]{ p, v, t };
            case 3:  return new float[]{ p, q, v };
            case 4:  return new float[]{ t, p, v };
            default: return new float[]{ v, p, q };
        }
    }
}
