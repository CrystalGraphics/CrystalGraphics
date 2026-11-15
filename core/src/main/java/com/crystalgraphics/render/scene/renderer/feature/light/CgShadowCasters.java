package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.scene.renderer.feature.field.CgMeshDistanceFields;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Our opaque draws as shadow casters this firing: each a row of its mesh's distance field for MegaLights' rays
 * (Unreal's distance field shadowing), and each a mesh for the sun's map. Both kinds of row share one texture, the
 * casters' then the sun's cascades', since the sample pass reads four buffers already.
 */
final class CgShadowCasters {

    /** How many a firing, the floats of each one's row, and how far from the eye. */
    static final int MOST = 256, FLOATS = 20;
    private static final float REACH = 96f;
    /** Where the sun's cascade rows start in the texture: world/lights/sample.compute's SUN_ROWS_AT. */
    static final int SUN_ROWS_AT = MOST * FLOATS / 4;

    private final CgLightGrid grid;
    /** Our meshes' distance fields, and this firing's rows. */
    final CgMeshDistanceFields fields = new CgMeshDistanceFields();
    private final float[] rows = new float[MOST * FLOATS];
    int count;
    /** The rows, five RGBA32F texels each, then the sun's: made once. */
    CgGraphTexture texture;
    private ByteBuffer staging, sunStaging;
    private final Matrix4f local = new Matrix4f(), inverse = new Matrix4f(), voxel = new Matrix4f();
    private final Vector3f scale = new Vector3f();

    /** This firing's casters for the sun's map: mesh, range, eye-relative model and bounding sphere. */
    final CgMesh[] sunMeshes = new CgMesh[MOST];
    final int[] sunRanges = new int[MOST * 3];
    final float[] sunModels = new float[MOST * 16], sunBounds = new float[MOST * 4];
    int sunCount;
    private final CgAABB sunBox = new CgAABB();
    private final Matrix4f sunModel = new Matrix4f();
    private final Vector3f sunCentre = new Vector3f();

    CgShadowCasters(CgLightGrid grid) {
        this.grid = grid;
    }

    /** None yet this firing. */
    void reset() {
        count = 0;
        sunCount = 0;
    }

    /**
     * One draw as a caster, after the firing is placed: {@code submesh}, {@code first} and {@code count} its range
     * (submesh -1 the whole mesh), at an absolute position, its local transform the 16 floats from {@code at}. Traced
     * through its mesh's distance field once that is made; past {@link #MOST} or {@link #REACH} blocks, not at all.
     */
    void add(CgMesh mesh, int submesh, int first, int count, double x, double y, double z, float[] transforms, int at) {
        if (!CgLightSettings.SHADOWS.get()) return;
        double dx = x - grid.cameraX, dy = y - grid.cameraY, dz = z - grid.cameraZ;
        if (dx * dx + dy * dy + dz * dz > (double) REACH * REACH) return;
        if (sunCount < MOST) sun(mesh, submesh, first, count, (float) dx, (float) dy, (float) dz, transforms, at);
        if (this.count == MOST) return;
        CgMeshDistanceFields.Entry entry = fields.field(mesh, submesh, first, count);
        if (entry == null) return;
        CgMeshDistanceFields.Field field = entry.made;
        local.set(transforms, at);
        inverse.translation((float) dx, (float) dy, (float) dz).mul(local).invertAffine();
        voxel.scaling(1f / field.voxel()).translate(-field.ox(), -field.oy(), -field.oz()).mul(inverse);
        // World units a unit of the mesh, its narrowest axis: a distance read from the field never overstates the world's.
        local.getScale(scale);
        float s = Math.min(scale.x, Math.min(scale.y, scale.z));
        int r = this.count++ * FLOATS;
        Matrix4f m = voxel;
        float[] c = rows;
        c[r] = m.m00(); c[r + 1] = m.m10(); c[r + 2] = m.m20(); c[r + 3] = m.m30();
        c[r + 4] = m.m01(); c[r + 5] = m.m11(); c[r + 6] = m.m21(); c[r + 7] = m.m31();
        c[r + 8] = m.m02(); c[r + 9] = m.m12(); c[r + 10] = m.m22(); c[r + 11] = m.m32();
        c[r + 12] = field.nx(); c[r + 13] = field.ny(); c[r + 14] = field.nz(); c[r + 15] = entry.offset(fields);
        c[r + 16] = s; c[r + 17] = field.voxel() * s; c[r + 18] = field.voxel(); c[r + 19] = 0f;
    }

    /** One caster of the sun's map: its eye-relative model, and its bounds as a sphere for the cascades' culling. */
    private void sun(CgMesh mesh, int submesh, int first, int count, float dx, float dy, float dz, float[] transforms, int at) {
        if (mesh.bounds(sunBox) == null) return;
        int s = sunCount++;
        sunMeshes[s] = mesh;
        sunRanges[s * 3] = submesh;
        sunRanges[s * 3 + 1] = first;
        sunRanges[s * 3 + 2] = count;
        sunModel.set(transforms, at).translateLocal(dx, dy, dz).get(sunModels, s * 16);
        sunModel.transformPosition(sunCentre.set((float) sunBox.centerX(), (float) sunBox.centerY(), (float) sunBox.centerZ()));
        sunModel.getScale(scale);
        float half = (float) Math.sqrt(sunBox.halfX() * sunBox.halfX() + sunBox.halfY() * sunBox.halfY()
                + sunBox.halfZ() * sunBox.halfZ());
        sunBounds[s * 4] = sunCentre.x;
        sunBounds[s * 4 + 1] = sunCentre.y;
        sunBounds[s * 4 + 2] = sunCentre.z;
        sunBounds[s * 4 + 3] = half * Math.max(scale.x, Math.max(scale.y, scale.z));
    }

    /** The texture and its stagings, made once. */
    void texture() {
        if (texture != null) return;
        texture = CgGraphTexture.requested("cg_world_lights.casters", new CgTextureDesc(SUN_ROWS_AT + CgSunShadows.CASCADES * 4, 1,
                CgFormat.RGBA32_FLOAT));
        staging = ByteBuffer.allocateDirect(MOST * FLOATS * 4).order(ByteOrder.nativeOrder());
        sunStaging = ByteBuffer.allocateDirect(CgSunShadows.CASCADES * 16 * 4).order(ByteOrder.nativeOrder());
    }

    /** The fields made since the last firing, then this firing's rows. */
    void upload(CgRecording recording) {
        fields.upload(recording);
        if (fields.atlas() == null) count = 0;
        if (count > 0) {
            texture();
            staging.clear();
            for (int i = 0; i < count * FLOATS; i++) staging.putFloat(rows[i]);
            staging.flip();
            recording.update(texture, 0, 0, 0, count * FLOATS / 4, 1, staging);
        }
        CgTrace.counter(CgChannels.WORLD, "world.lights-casters", count);
    }

    /** The sun's cascades' rows after the casters', four texels each, and its casters let go. */
    void uploadSun(CgRecording recording, float[] cascadeRows) {
        sunStaging.clear();
        for (float v : cascadeRows) sunStaging.putFloat(v);
        sunStaging.flip();
        recording.update(texture, 0, SUN_ROWS_AT, 0, CgSunShadows.CASCADES * 4, 1, sunStaging);
        Arrays.fill(sunMeshes, 0, sunCount, null);
    }
}
