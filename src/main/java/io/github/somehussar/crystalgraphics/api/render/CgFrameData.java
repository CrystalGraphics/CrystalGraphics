package io.github.somehussar.crystalgraphics.api.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.LongSupplier;

/**
 * Per-frame mutable data bundle: camera matrices, time, viewport, and the world-time sampler.
 *
 * <p>Replaces and merges the former {@code CgFrameConfig} (camera/scene state) and
 * {@code CgFrameUniforms} (GPU upload fields) into a single MC-agnostic holder.
 * Pre-allocate one instance and mutate fields in-place each frame to avoid per-frame
 * allocation at 60+ FPS.</p>
 *
 * <h3>Harness / standalone usage</h3>
 * <pre>{@code
 * CgFrameData fd = pipeline.getFrameData();
 * fd.viewMatrix.set(glViewBuf);
 * fd.projMatrix.set(glProjBuf);
 * fd.timeSecs = (float)(System.nanoTime() / 1e9);
 * fd.viewportW = Display.getWidth();
 * fd.viewportH = Display.getHeight();
 * fd.deriveFromViewMatrix();
 * fd.farPlane = 1000f;
 * }</pre>
 *
 * <h3>Minecraft usage (set in mc/ package — not here)</h3>
 * <pre>{@code
 * fd.worldTimeSampler = () -> Minecraft.getMinecraft().theWorld.getTotalWorldTime();
 * }</pre>
 *
 * <p>Analogues: Unity {@code CameraData}; Filament {@code FView} camera state.</p>
 * <p>JOML types — pre-allocated, mutated each frame. Never replaced.</p>
 */
public final class CgFrameData {

    /** World→view transform. Set from {@code GL11.glGetFloat(GL_MODELVIEW_MATRIX, buf)}. */
    public final Matrix4f viewMatrix    = new Matrix4f();

    /** View→clip transform. Set from {@code GL11.glGetFloat(GL_PROJECTION_MATRIX, buf)}. */
    public final Matrix4f projMatrix    = new Matrix4f();

    /**
     * World-space camera position. Derived by {@link #deriveFromViewMatrix()} or set directly.
     * Used by {@code submit()} to compute {@code cameraDepth} for each command's AABB center.
     */
    public final Vector3f cameraPos     = new Vector3f();

    /**
     * World-space camera forward direction (normalized, pointing INTO the scene).
     * OpenGL convention: camera looks down -Z in view space → forward in world space
     * is the negation of the view matrix's Z-axis column.
     * Computed by {@link #deriveFromViewMatrix()}.
     */
    public final Vector3f cameraForward = new Vector3f(0f, 0f, -1f);

    /** Camera near plane distance (positive, world units). */
    public float nearPlane = 0.1f;

    /** Camera far plane distance (positive, world units). Used to normalize depth bucket. */
    public float farPlane  = 1000f;

    /** Time in seconds. Uploaded to {@code cg_Time} uniform each frame. */
    public float timeSecs  = 0f;

    /** Viewport width in pixels. For {@code cg_Resolution} uniform. */
    public int   viewportW = 0;

    /** Viewport height in pixels. */
    public int   viewportH = 0;

    /**
     * Set to {@code true} by the MC integration layer when anaglyph stereo mode is active.
     * Defaults to {@code false}. When {@code false}, the anaglyph replay guard in
     * {@code CgRenderPipeline.executeOpaquePass()} is bypassed entirely — every frame
     * sorts and releases commands normally, which is the correct behavior for harness
     * mode and non-anaglyph MC. Set to {@code true} in the mc/ package where anaglyph mode
     * is detected alongside {@code worldTimeSampler} assignment.
     */
    public boolean anaglyphModeEnabled = false;

    /**
     * Internal MC-agnostic world tick counter used to back {@link #getCurrentWorldTime()}.
     */
    public LongSupplier worldTimeSampler = () -> 0L;
    
    /**
     * MC-agnostic world tick counter. Used by the anaglyph guard to detect second-eye replay.
     * Assign in the mc/ package: {@code fd.worldTimeSampler = () -> mc.theWorld.getTotalWorldTime()}.
     * Default returns {@code 0L} — safe for harness and tests.
     * 
     * @return the current total world time long value
     */
    public long getCurrentWorldTime() {
        return worldTimeSampler.getAsLong();
    }

    /**
     * Derives {@link #cameraForward} and {@link #cameraPos} from the current {@link #viewMatrix}.
     * Call immediately after setting viewMatrix each frame.
     *
     * <p>In JOML column-major layout, the view matrix columns are:<br>
     *   col0 = right vector, col1 = up vector, col2 = camera -forward (backward), col3 = translation.<br>
     * Camera forward (into scene) = -col2 = (-m20, -m21, -m22) (JOML: m20() = col2row0).</p>
     *
     * <p>Camera world position for pure rotation+translation view matrix V = R*T:<br>
     *   pos = -R^T * t = -(row0·t, row1·t, row2·t)<br>
     *   where t = (m30, m31, m32) (JOML col3) and rows of R are cols of V upper 3x3.</p>
     */
    public void deriveFromViewMatrix() {
        // Camera forward: negation of view matrix col2 (the -Z axis of camera in world space)
        cameraForward.set(-viewMatrix.m20(), -viewMatrix.m21(), -viewMatrix.m22()).normalize();

        // Camera world position: -R^T * t
        float tx = viewMatrix.m30(), ty = viewMatrix.m31(), tz = viewMatrix.m32();
        cameraPos.x = -(viewMatrix.m00() * tx + viewMatrix.m10() * ty + viewMatrix.m20() * tz);
        cameraPos.y = -(viewMatrix.m01() * tx + viewMatrix.m11() * ty + viewMatrix.m21() * tz);
        cameraPos.z = -(viewMatrix.m02() * tx + viewMatrix.m12() * ty + viewMatrix.m22() * tz);
    }

    /**
     * MVP: directional light deferred to v2. Always returns {@code false}.
     * Shadow pass is not executed in Phase 1.
     */
    public boolean hasDirectionalLight() {
        return false;
    }
}
