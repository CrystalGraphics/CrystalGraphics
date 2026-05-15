package com.crystalgraphics.api.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.gl.mesh.CgMesh;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * One render submission: mesh + material + transform + metadata.
 * All instances are owned by {@link CgRenderCommandPool} — never allocate directly.
 *
 * <p>Fields are pooled — reset on acquire, never null-ed for JOML references.
 * Set {@link #worldAabb} before calling {@link CgRenderCommandQueue#submit(CgRenderCommand)}.
 * The queue derives {@link #normalMatrix}, {@link #cameraDepth}, {@link #passFlags},
 * and {@link #sortKey} at submit time.</p>
 *
 * <p>Analogues:</p>
 * <ul>
 *   <li>Filament: {@code Command.PrimitiveInfo} (key + mi + rph + rasterState + instanceCount + variant)</li>
 *   <li>Godot: {@code GeometryInstanceSurfaceDataCache} (flags + sort + depth + priority)</li>
 *   <li>bgfx: DrawCall accumulated from setVertexBuffer/setTransform/setState + submit()</li>
 * </ul>
 */
public final class CgRenderCommand {

    // ── Sort key (computed at submit() time) ──────────────────────────────────

    /**
     * 64-bit sort key. Computed at submit() by CgSortKey.encode().
     * Drives ALL ordering decisions: pass bucket, depth, material grouping.
     * Filament: Command.key (uint64_t sort key).
     */
    long sortKey;

    // ── Draw data ─────────────────────────────────────────────────────────────

    /**
     * Mesh to draw. Same standalone VAO for both drawDirect() and drawInstanced().
     * Filament: rph (render primitive handle); bgfx: vertexBufferHandle + indexBufferHandle.
     * Never null during execution.
     */
    public CgMesh mesh;

    /**
     * Material to bind. Carries compiled GL programs (per variant), render state,
     * property UBO, and sampler bindings.
     * Filament: mi (FMaterialInstance*); Unity: DrawingSettings.material.
     */
    public CgMaterial material;

    // ── Transform data (JOML — pre-allocated, never replaced) ────────────────

    /**
     * World-space model matrix. Pre-allocated JOML Matrix4f.
     * Corresponds to OBJECT_FORMAT.modelMatrix (floats 0-15).
     * Written into the pipeline object buffer before draw.
     * Caller sets via: cmd.modelMatrix.set(...) or cmd.modelMatrix.identity().translate(...)
     */
    public final Matrix4f modelMatrix  = new Matrix4f();

    /**
     * Normal matrix (inverse-transpose of upper-left 3x3 of modelMatrix).
     * Stored as Matrix4f for STD430 mat4 alignment; shader reads upper-left 3x3 as mat3.
     * Pre-allocated. Corresponds to OBJECT_FORMAT.normalMatrix (floats 16-31).
     * Derived automatically at submit() time — do NOT pre-set.
     */
    public final Matrix4f normalMatrix = new Matrix4f();

    /**
     * World-space AABB: [minX, minY, minZ, maxX, maxY, maxZ].
     * Pre-allocated float[6] — no JOML equivalent for raw AABB.
     * <strong>Must be set every frame before submit()</strong> — reset() initialises
     * these to NaN so a forgotten AABB is caught at submit() validation.
     * Used for frustum culling (v2). MVP: field present, no culling performed.
     */
    public final float[] worldAabb = new float[6];

    /**
     * Per-instance custom data for OBJECT_FORMAT.custom0-3 (floats 32-47).
     * Pre-allocated JOML Vector4f. Zeroed by reset() via .set(0,0,0,0).
     */
    public final Vector4f custom0 = new Vector4f();
    /** Per-instance custom1 channel. */
    public final Vector4f custom1 = new Vector4f();
    /** Per-instance custom2 channel. */
    public final Vector4f custom2 = new Vector4f();
    /** Per-instance custom3 channel. */
    public final Vector4f custom3 = new Vector4f();

    /**
     * CPU-only per-command identity tag. Never written to the GPU SSBO.
     * Use to identify this command's instance slot inside a {@link CgPreDrawHook}:
     * {@code batch[offset + k].tag} is the tag for {@code gl_InstanceID = k}.
     * Reset to {@code null} by the pool each frame.
     */
    @Nullable public String tag;

    /**
     * Optional hook fired once per merged batch, after material bind, before draw.
     * Fires in every pass that draws this batch (depth prepass + forward).
     * Must be the same object reference across commands that should batch together.
     * Reset to {@code null} by the pool each frame.
     *
     * @see CgPreDrawHook
     */
    @Nullable public CgPreDrawHook preDrawHook;

    // ── Sort/routing metadata ─────────────────────────────────────────────────

    /**
     * Render queue value. Controls pass bucket in sort key and draw order.
     * Any integer is valid — use {@link CgRenderQueue} constants as anchors.
     * Values {@code < TRANSPARENT_THRESHOLD} (2500) → opaque/alpha-test buckets.
     * Values {@code >= TRANSPARENT_THRESHOLD} → transparent bucket.
     * Default: {@link CgRenderQueue#GEOMETRY} (2000).
     */
    public int queueSlot = CgRenderQueue.GEOMETRY;

    /**
     * User-assigned render priority within the queue slot (0-15, default 0).
     * Higher priority renders LATER in the same slot.
     */
    public int renderPriority;

    /**
     * Camera-space depth, set at submit() time from the AABB center projected
     * onto the camera forward vector. Do not set — computed automatically.
     */
    float cameraDepth;

    /**
     * Bitmask of passes this command participates in.
     * Computed at submit() time, not by caller.
     * See {@link #FLAG_SHADOW}, {@link #FLAG_OPAQUE}, etc.
     */
    public int passFlags;
    public static final int FLAG_SHADOW      = 1;
    /** Pass flag: this command is in the opaque (GEOMETRY) bucket. */
    public static final int FLAG_OPAQUE      = 2;
    /** Pass flag: this command is in the alpha-test bucket. */
    public static final int FLAG_ALPHA_TEST  = 4;
    /** Pass flag: this command is in the transparent bucket. */
    public static final int FLAG_TRANSPARENT = 8;
    /** Pass flag: this command is in the overlay bucket. */
    public static final int FLAG_OVERLAY     = 16;

    // ── Pool management ───────────────────────────────────────────────────────

    /** Back-pointer to owning pool. Set at pool creation, never changed. */
    CgRenderCommandPool pool;

    /** Whether this slot is currently acquired (true) or available (false). */
    boolean acquired;

    /**
     * Resets all mutable fields to defaults. Called by the pool on acquire().
     * worldAabb is initialised to NaN so a forgotten AABB is detected at submit().
     */
    void reset() {
        mesh           = null;
        material       = null;
        queueSlot      = CgRenderQueue.GEOMETRY;
        renderPriority = 0;
        cameraDepth    = 0f;
        passFlags      = 0;
        sortKey        = 0L;
        modelMatrix.identity();
        normalMatrix.identity();
        custom0.set(0, 0, 0, 0);
        custom1.set(0, 0, 0, 0);
        custom2.set(0, 0, 0, 0);
        custom3.set(0, 0, 0, 0);
        tag         = null;
        preDrawHook = null;
        // Initialise AABB to NaN — submit() validation catches forgotten AABB immediately
        worldAabb[0] = Float.NaN;
        worldAabb[1] = Float.NaN;
        worldAabb[2] = Float.NaN;
        worldAabb[3] = Float.NaN;
        worldAabb[4] = Float.NaN;
        worldAabb[5] = Float.NaN;
    }
}
