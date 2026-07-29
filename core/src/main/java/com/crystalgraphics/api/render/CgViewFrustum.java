package com.crystalgraphics.api.render;

import com.crystalgraphics.util.profiling.CgProfiler;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Visibility test for an axis-aligned bounding box against a view-projection matrix.
 *
 * <p>General-purpose and deliberately not tied to any one subsystem: the same six-plane test
 * answers "is this mesh in the camera frustum" for {@link CgRenderPipeline} and "is this glyph
 * quad on screen" for the text renderer. Both are consumers, neither is the owner.</p>
 *
 * <h3>Usage — build once per view, test many times</h3>
 * <pre>{@code
 * // once per frame / per view change
 * frustum.set(new Matrix4f(projection).mul(view));
 *
 * // then, per object — allocation-free
 * if (!frustum.testAabb(cmd.worldAabb)) continue;   // skip it
 * }</pre>
 *
 * <p>Mutable and intended to be reused. Nothing here allocates, so it is safe to call from the
 * innermost draw loop; construct one per renderer and {@link #set} it each frame rather than
 * making a new instance.</p>
 *
 * <h3>2D is not a special case</h3>
 * <p>An orthographic projection produces a box-shaped frustum, so screen-space culling is the
 * same six-plane test with a different matrix — there is no separate 2D code path and none should
 * be added. {@link #testRect} is a convenience that pins z to 0 for callers whose geometry is
 * genuinely planar; it is not a different algorithm.</p>
 *
 * <p>The matrix passed to {@link #set} must be the <em>full</em> transform that maps the
 * coordinate space of the AABBs being tested into clip space. For world geometry that is
 * {@code projection * view}; for UI text drawn through a {@code PoseStack} it is
 * {@code projection * modelView}, and the AABBs are then the quads' own local coordinates.
 * Mixing spaces — testing local-space geometry against a world-space frustum — silently culls
 * everything or nothing, which is why the space is called out here rather than left implied.</p>
 *
 * <h3>Why this wraps JOML rather than extracting planes itself</h3>
 * <p>{@link FrustumIntersection} already implements Gribb–Hartmann plane extraction with the
 * standard early-exit plane ordering and a cached "last rejecting plane" optimisation that pays
 * off heavily on spatially-coherent inputs — exactly the access pattern both consumers have.
 * Hand-rolling it means re-deriving sign conventions and normalisation that are easy to get
 * subtly wrong and produce culling that is correct in the common case and wrong at the edges,
 * which is the worst possible failure mode for a visibility test. JOML is already a hard
 * dependency of this module (every matrix here is a JOML type), so this costs nothing.</p>
 *
 * <h3>Counters</h3>
 * <p>{@code cull.tested} and {@code cull.rejected} are recorded on every test. A culler that
 * rejects nothing is pure overhead, and one that rejects almost everything usually means the
 * matrix is wrong rather than that the scene is empty — neither is visible without counting.</p>
 */
public final class CgViewFrustum {

    private final FrustumIntersection intersection = new FrustumIntersection();

    /** True once {@link #set} has been called; guards against testing an undefined frustum. */
    private boolean initialised;

    /**
     * Extracts the six clip planes from {@code viewProjection}.
     *
     * @param viewProjection the full transform from the tested geometry's space into clip space
     *                       (see the class javadoc on matching spaces)
     */
    public CgViewFrustum set(Matrix4f viewProjection) {
        if (viewProjection == null) throw new IllegalArgumentException("viewProjection must not be null");
        intersection.set(viewProjection);
        initialised = true;
        return this;
    }

    /** Whether {@link #set} has been called on this instance. */
    public boolean isInitialised() {
        return initialised;
    }

    /**
     * Tests an AABB laid out as {@code [minX, minY, minZ, maxX, maxY, maxZ]} — the exact layout of
     * {@link CgRenderCommand#worldAabb}, which {@link CgRenderCommandQueue#submit} already
     * validates as finite and correctly ordered, so no revalidation happens here.
     *
     * @return {@code true} if the box is at least partially visible
     * @throws IllegalArgumentException if the array is not length 6
     */
    public boolean testAabb(float[] aabb) {
        if (aabb == null || aabb.length != 6) {
            throw new IllegalArgumentException("aabb must be a float[6] of [minX,minY,minZ,maxX,maxY,maxZ]");
        }
        return testAabb(aabb[0], aabb[1], aabb[2], aabb[3], aabb[4], aabb[5]);
    }

    /**
     * Tests an AABB against the frustum.
     *
     * <p>Conservative, as a frustum test must be: it can report a box visible that is in fact
     * outside (a large box straddling several planes), never the reverse. Over-reporting costs a
     * wasted draw; under-reporting drops geometry the user should see.</p>
     *
     * @return {@code true} if the box is at least partially visible, and always {@code true} if
     *         {@link #set} has never been called — an unconfigured culler must not hide anything
     */
    public boolean testAabb(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (!initialised) return true;
        CgProfiler.count("cull.tested");
        boolean visible = intersection.testAab(minX, minY, minZ, maxX, maxY, maxZ);
        if (!visible) CgProfiler.count("cull.rejected");
        return visible;
    }

    /**
     * Planar convenience for geometry that lives on the {@code z = 0} plane — UI quads, glyphs,
     * sprites. Equivalent to {@link #testAabb} with {@code minZ = maxZ = 0}.
     *
     * <p>Note the argument order is {@code (minX, minY, maxX, maxY)}, i.e. two corners, not
     * position-and-size. A caller holding {@code (x, y, w, h)} passes {@code (x, y, x + w, y + h)}.</p>
     */
    public boolean testRect(float minX, float minY, float maxX, float maxY) {
        return testAabb(minX, minY, 0f, maxX, maxY, 0f);
    }

    /**
     * Sphere test, for callers that have a centre and radius and would otherwise synthesise an
     * AABB around it. Cheaper than the box test (one dot product per plane, no corner selection).
     */
    public boolean testSphere(float x, float y, float z, float radius) {
        if (!initialised) return true;
        CgProfiler.count("cull.tested");
        boolean visible = intersection.testSphere(x, y, z, radius);
        if (!visible) CgProfiler.count("cull.rejected");
        return visible;
    }

    /** Forgets the current planes, so every subsequent test reports visible until {@link #set}. */
    public void clear() {
        initialised = false;
    }
}
