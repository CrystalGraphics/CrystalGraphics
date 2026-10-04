package com.crystalgraphics.compute.ops;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * What {@link CgGpuOps#cull} tests a set of instances against and places them by: the view, where the set sits in it,
 * its mesh's box and levels, and optionally the scene's depth. Mutable, and reused frame to frame without allocating.
 *
 * <pre>{@code
 * CgCull cull = new CgCull().mesh(rockLods);                // once: the finest level's box, every level's height
 * // each frame, with the view the instances are drawn under:
 * cull.view(view, projection)                              // camera-relative, as the pass that draws them
 *     .place(place)                                        // the set's space into that camera-relative world
 *     .pyramid(depth);                                     // CgGpuOps.depthPyramid's, or null for none
 * CgGpuOps.cull(pass, cull, rocks, CgGpuCount.of(n), visible, counts, 0);
 * }</pre>
 *
 * <ul>
 *   <li>The view and the place are both camera-relative: subtract the camera from the set's origin in doubles, as
 *       {@code CgWorldRenderer} does, and put the difference in the place's translation.</li>
 *   <li>A plain mesh ({@link #mesh(CgMesh)}) has one level and is never culled by size; a {@link CgMeshLods} of up to
 *       {@link #MAX_LEVELS} levels culls what is smaller on screen than its last.</li>
 *   <li>Each kept record's light ({@code CG_OBJECT_LIGHT}) is its own unless {@link #light} stamps one.</li>
 * </ul>
 */
public final class CgCull {

    /** The levels a {@link CgMeshLods} may have. */
    public static final int MAX_LEVELS = 8;

    private static final String[] PLANES = {"_Plane0", "_Plane1", "_Plane2", "_Plane3", "_Plane4", "_Plane5"};

    final Matrix4f place = new Matrix4f();
    final Matrix3f placeNormal = new Matrix3f();
    final Matrix4f viewProjection = new Matrix4f();
    final Vector4f[] planes = {new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f(),
            new Vector4f()};
    /** Projection [2][2], [2][3], [3][2], [3][3]: what {@code cg_LinearEyeDepth} reads. */
    final float[] eye = new float[4];
    float screenY = 1f;
    final float[] box = new float[6];
    float pad;
    final float[] heights = new float[MAX_LEVELS];
    /** Levels by screen height; 0 for one never culled by size. */
    int levels;
    @Nullable
    CgGraphTexture pyramid;
    float lightBlock, lightSky;
    boolean stampLight;

    public CgCull() {
        view(new Matrix4f(), new Matrix4f());
    }

    /** The view and projection the instances are drawn under, camera-relative. */
    public CgCull view(Matrix4fc view, Matrix4fc projection) {
        projection.mul(view, viewProjection);
        for (int p = 0; p < 6; p++) viewProjection.frustumPlane(p, planes[p]);
        eye[0] = projection.m22();
        eye[1] = projection.m23();
        eye[2] = projection.m32();
        eye[3] = projection.m33();
        screenY = Math.abs(projection.m11());
        return this;
    }

    /** Where the set's space sits in the view's camera-relative world: each instance's model is {@code place * model}. */
    public CgCull place(Matrix4fc place) {
        this.place.set(place);
        this.place.normal(placeNormal);
        return this;
    }

    /** One mesh: its box, one level, never culled by size. Throws for a mesh with no bounds to give. */
    public CgCull mesh(CgMesh mesh) {
        boundsOf(mesh);
        levels = 0;
        return this;
    }

    /** A mesh's levels: the finest level's box, and each level's screen height. */
    public CgCull mesh(CgMeshLods lods) {
        if (lods.levelCount() > MAX_LEVELS) {
            throw new IllegalArgumentException(lods.levelCount() + " levels; a cull takes " + MAX_LEVELS);
        }
        boundsOf(lods.finest());
        levels = lods.levelCount();
        for (int i = 0; i < levels; i++) heights[i] = lods.screenHeight(i);
        return this;
    }

    /** The box every instance's model transforms, in place of the mesh's: for a shader that moves vertices. */
    public CgCull box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        box[0] = minX;
        box[1] = minY;
        box[2] = minZ;
        box[3] = maxX;
        box[4] = maxY;
        box[5] = maxZ;
        return this;
    }

    /** Grows the box by {@code pad} on every side, in the instance's own space. */
    public CgCull pad(float pad) {
        this.pad = pad;
        return this;
    }

    /** The scene's depth as {@link CgGpuOps#depthPyramid} built it, under the same view; null tests none. */
    public CgCull pyramid(@Nullable CgGraphTexture pyramid) {
        this.pyramid = pyramid;
        return this;
    }

    /** Stamps every kept record's light: block and sky, 0 to 15. */
    public CgCull light(float block, float sky) {
        lightBlock = block;
        lightSky = sky;
        stampLight = true;
        return this;
    }

    /** Keeps each record's own light. */
    public CgCull ownLight() {
        stampLight = false;
        return this;
    }

    /** The levels a cull keeps instances at: 1 for a plain mesh. */
    public int levels() {
        return Math.max(1, levels);
    }

    /** Its values on {@code d}, a dispatch of {@code cull.compute}'s kernel. */
    void apply(CgDispatch d) {
        Matrix4f p = place, vp = viewProjection;
        Matrix3f n = placeNormal;
        d.set("_Levels", levels)
                .set("_Heights0", heights[0], heights[1], heights[2], heights[3])
                .set("_Heights1", heights[4], heights[5], heights[6], heights[7])
                .set("_Min", box[0] - pad, box[1] - pad, box[2] - pad, 0f)
                .set("_Max", box[3] + pad, box[4] + pad, box[5] + pad, 0f)
                .set("_Place0", p.m00(), p.m01(), p.m02(), p.m03())
                .set("_Place1", p.m10(), p.m11(), p.m12(), p.m13())
                .set("_Place2", p.m20(), p.m21(), p.m22(), p.m23())
                .set("_Place3", p.m30(), p.m31(), p.m32(), p.m33())
                .set("_PlaceNormal0", n.m00(), n.m01(), n.m02(), 0f)
                .set("_PlaceNormal1", n.m10(), n.m11(), n.m12(), 0f)
                .set("_PlaceNormal2", n.m20(), n.m21(), n.m22(), 0f)
                .set("_ClipX", vp.m00(), vp.m10(), vp.m20(), vp.m30())
                .set("_ClipY", vp.m01(), vp.m11(), vp.m21(), vp.m31())
                .set("_ClipZ", vp.m02(), vp.m12(), vp.m22(), vp.m32())
                .set("_ClipW", vp.m03(), vp.m13(), vp.m23(), vp.m33())
                .set("_Eye", eye[0], eye[1], eye[2], eye[3])
                .set("_ScreenY", screenY)
                .set("_Light", lightBlock, lightSky, stampLight ? 1f : 0f, 0f);
        for (int i = 0; i < 6; i++) d.set(PLANES[i], planes[i].x, planes[i].y, planes[i].z, planes[i].w);
        if (pyramid != null) {
            d.texture("_Pyramid", pyramid)
                    .set("_PyramidSize", pyramid.getWidth(), pyramid.getHeight(), pyramid.getLevels(), 0f);
        } else {
            d.texture("_Pyramid", CgFallbackTextures.WHITE_1x1).set("_PyramidSize", 0f, 0f, 0f, 0f);
        }
    }

    private void boundsOf(CgMesh mesh) {
        if (mesh.bounds(box) == null) throw new IllegalArgumentException(mesh + " has no bounds: state them with box()");
    }
}
