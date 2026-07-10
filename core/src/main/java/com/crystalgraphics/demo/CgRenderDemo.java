package com.crystalgraphics.demo;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderCommand;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Platform-agnostic 3D render pipeline demo.
 *
 * <p>Renders a 4×4 grid of lit, rainbow-tinted unit cubes via
 * {@link CgRenderPipeline}. Each cube carries a unique HSV colour encoded in
 * {@code cmd.custom0}, which the {@code demo_render.shader} reads through
 * {@code CG_OBJECT_CUSTOM0}. The camera orbits the grid automatically and zooms
 * with the mouse wheel.</p>
 *
 * <h3>GL contract</h3>
 * <p>All GL calls happen inside {@link CgRenderPipeline} — no {@code org.lwjgl.*}
 * imports are present in this class.</p>
 *
 * <h3>Platform wiring</h3>
 * <p>Each platform adapter calls two methods per frame:</p>
 * <ol>
 *   <li>{@link #renderOpaque(float, int, int, int)} — from the pre-translucent
 *       render hook ({@code AFTER_BLOCK_ENTITIES} / {@code onBeforeTranslucentBlocks}).
 *       This method sets {@code CgFrameData}, submits the 16 cube commands, then
 *       executes the depth prepass and opaque forward pass.</li>
 *   <li>{@link #renderTransparent()} — from the post-translucent render hook
 *       ({@code AFTER_PARTICLES} / {@code onAfterTranslucentContent}).
 *       Executes the transparent pass and ends the frame.</li>
 * </ol>
 * <p>These two calls replace the platform's direct {@code executeOpaquePass} /
 * {@code executeTransparentPass} / {@code endFrame} invocations while the demo
 * is active.</p>
 */
public final class CgRenderDemo {

    public static final CgRenderDemo INSTANCE = new CgRenderDemo();

    private static final Logger LOGGER = LogManager.getLogger("CgRenderDemo");

    private static final int   GRID          = 4;                  // 4×4 = 16 cubes
    private static final float GRID_STEP     = 1.5f;
    private static final float ORBIT_DEG_SEC = 20f;

    private boolean enabled     = true;
    private boolean initialized = false;

    private CgMesh     cubeMesh;
    private CgMaterial cubeMaterial;

    private float orbitAngleDeg    = 0f;
    private float orbitRadius      = 10f;
    private float orbitElevationDeg = 30f;
    private long  lastNanos        = -1L;

    // Pre-allocated — never replaced across frames.
    private final Matrix4f scratchView = new Matrix4f();
    private final Matrix4f scratchProj = new Matrix4f();

    private CgRenderDemo() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opaque pass: lazy-initialises resources, advances the orbit camera,
     * uploads {@link CgFrameData}, submits 16 cube commands, then drives the
     * depth prepass and opaque forward pass.
     *
     * <p>Call from the pre-translucent render hook on each platform, passing the
     * GL framebuffer ID that holds the current scene depth (used for the per-frame
     * depth snapshot blit into {@code cg_DepthBuffer}).</p>
     *
     * @param partialTick  frame interpolation factor [0, 1]
     * @param w            current viewport width in pixels
     * @param h            current viewport height in pixels
     * @param sourceFboId  GL framebuffer object ID to blit depth from
     *                     (pass {@code 0} for the default framebuffer)
     */
    public void renderOpaque(float partialTick, int w, int h, int sourceFboId) {
        if (!enabled) return;
        try {
            ensureResources();
            advanceCamera();
            populateFrameData(w, h);
            submitGeometry();
            CgRenderPipeline.getInstance().executeOpaquePass(partialTick, sourceFboId);
        } catch (Exception e) {
            LOGGER.error("CgRenderDemo opaque pass failed", e);
            enabled = false;
        }
    }

    /**
     * Transparent pass and frame end. No transparent geometry is submitted by
     * this demo, so this is effectively a no-op for the draw calls — but it
     * must still be called to release the command pool via {@code endFrame()}.
     *
     * <p>Call from the post-translucent render hook on each platform.</p>
     */
    public void renderTransparent() {
        if (!enabled) return;
        try {
            CgRenderPipeline.getInstance().executeTransparentPass();
            CgRenderPipeline.getInstance().endFrame();
        } catch (Exception e) {
            LOGGER.error("CgRenderDemo transparent pass failed", e);
            enabled = false;
        }
    }

    /**
     * Adjusts the orbit radius in response to a mouse-wheel scroll event.
     *
     * @param delta raw wheel delta ({@code > 0} = scroll up = zoom in)
     */
    public void onMouseWheel(int delta) {
        if (delta > 0) orbitRadius = Math.max(2f,  orbitRadius - 0.5f);
        else           orbitRadius = Math.min(30f, orbitRadius + 0.5f);
    }

    /** Releases GPU resources. Call on context destroy. */
    public void dispose() {
        if (cubeMesh != null) { cubeMesh.delete(); cubeMesh = null; }
        cubeMaterial = null; // owned by CgMaterialRegistry — do not delete
        initialized  = false;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void ensureResources() {
        if (initialized) return;
        cubeMesh     = CgMesh.upload(CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL));
        cubeMaterial = CgMaterial.load("crystalgraphics:shaders/demo_render.shader");
        initialized  = true;
        LOGGER.info("[CgRenderDemo] resources initialised (mesh={}, material={})",
                cubeMesh, cubeMaterial);
    }

    private void advanceCamera() {
        long now = System.nanoTime();
        if (lastNanos > 0) {
            float dt = (now - lastNanos) * 1e-9f;
            orbitAngleDeg += ORBIT_DEG_SEC * dt;
        }
        lastNanos = now;
    }

    private void populateFrameData(int w, int h) {
        float angleRad = (float) Math.toRadians(orbitAngleDeg);
        float elevRad  = (float) Math.toRadians(orbitElevationDeg);
        float cosElev  = (float) Math.cos(elevRad);
        float eyeX     = orbitRadius * cosElev * (float) Math.sin(angleRad);
        float eyeY     = orbitRadius * (float) Math.sin(elevRad);
        float eyeZ     = orbitRadius * cosElev * (float) Math.cos(angleRad);

        scratchView.identity().lookAt(eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f);

        float aspect = (w > 0 && h > 0) ? (float) w / h : 1f;
        scratchProj.identity().perspective((float) Math.toRadians(60.0), aspect, 0.1f, 200f);

        CgFrameData fd = CgRenderPipeline.getInstance().getFrameData();
        fd.viewMatrix.set(scratchView);
        fd.projMatrix.set(scratchProj);
        fd.timeSecs  = (float)(System.nanoTime() / 1_000_000_000.0);
        fd.viewportW = w;
        fd.viewportH = h;
        fd.farPlane  = 200f;
        fd.deriveFromViewMatrix();
    }

    private void submitGeometry() {
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        float half = (GRID - 1) * GRID_STEP * 0.5f;

        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                float x = i * GRID_STEP - half;
                float z = j * GRID_STEP - half;

                CgRenderCommand cmd = pipeline.acquireCommand();
                cmd.mesh      = cubeMesh;
                cmd.material  = cubeMaterial;
                cmd.queueSlot = CgRenderQueue.GEOMETRY;
                cmd.modelMatrix.identity().translation(x, 0f, z);

                float hue   = (i * GRID + j) / (float)(GRID * GRID);
                float[] rgb = hsvToRgb(hue, 0.85f, 1.0f);
                cmd.custom0.set(rgb[0], rgb[1], rgb[2], 1f);

                cmd.worldAabb[0] = x - 0.5f;  cmd.worldAabb[3] = x + 0.5f;
                cmd.worldAabb[1] =    -0.5f;   cmd.worldAabb[4] =    0.5f;
                cmd.worldAabb[2] = z - 0.5f;   cmd.worldAabb[5] = z + 0.5f;

                pipeline.submit(cmd);
            }
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
