package com.crystalgraphics.render.scene.visibility;

import com.crystalgraphics.api.buffer.CgBuffer;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Every instanced row a view sees, culled on the GPU instance by instance against the view's frustum and its depth
 * pyramid: Unreal's instance culling ({@code InstanceCullBuildInstanceIdBufferCS}). Each kept instance's object id is
 * appended into its row's region of {@link #ids()} as a draw reads {@code cg_ObjectIds}, (id, 0), and the row's word of
 * {@link #counts()} counts them: the instance count a draw of the row reads ({@code CgIndirect.INSTANCES}), and the
 * ids it reads its records by ({@code CgDrawCommands.ids}).
 *
 * <pre>{@code
 * CgInstanceCulling culling = new CgInstanceCulling("mymod.culling");   // a renderer's, one per view it draws
 * CgViewVisibility seen = CgViewVisibility.of(view, scene, camera);
 * culling.cull(recording, scene, seen, camera, pyramid);               // one compute pass; null pyramid: frustum alone
 * for (int r = 0; r < culling.rows(); r++) {
 *     int b = culling.batch(r);                                         // its row of seen.batches()
 *     // draw seen.batches().mesh(b) instanced: ids from culling.ids() at element culling.first(r),
 *     // its instance count the word culling.word(r) of culling.counts()
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, in the recording of the pass that draws them: the ids and counts are rewritten by every cull.</li>
 *   <li>The pyramid is the scene's depth under the same camera ({@code CgGpuOps.depthPyramid}), built from what should
 *       hide the instances.</li>
 *   <li>An instance is tested by its mesh's box under its own record's transform: a mesh with no bounds keeps every
 *       instance.</li>
 * </ul>
 */
public final class CgInstanceCulling {

    /** Where a row's region of {@link #ids} starts: a whole binding offset (256 bytes, 32 entries). */
    private static final int ALIGN = 32;
    /** Bytes an entry: (id, 0), two words. */
    private static final int ENTRY = 8;
    private static final String PATH = "crystalgraphics:shaders/compute/scene/instance_cull.compute";
    private static final String[] PLANES = {"_Plane0", "_Plane1", "_Plane2", "_Plane3", "_Plane4", "_Plane5"};
    private static final int TESTED = CgTrace.name("scene.instances.tested");
    private static final int GPU = CgGpuTrace.name("scene.instances.cull");

    private static volatile CgKernel kernel;

    private final String name;
    private int rows;
    private int[] batch = new int[8], first = new int[8];
    @Nullable
    private CgGraphBuffer ids, counts;
    private int idCapacity, countCapacity;

    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f plane = new Vector4f();
    private final CgAABB box = new CgAABB();

    public CgInstanceCulling(String name) {
        this.name = name;
    }

    /** Builds its kernel's pipeline without waiting: what a renderer's precache calls. */
    public static void precache() {
        kernel().precache();
    }

    /**
     * Culls every instanced row of {@code seen} in one compute pass of {@code recording}, under {@code camera} (the
     * camera {@code seen} was made through) and against {@code pyramid}, or the frustum alone for null.
     */
    public void cull(CgRecording recording, CgScene scene, CgViewVisibility seen, CgCamera camera,
                     @Nullable CgGraphTexture pyramid) {
        CgMeshBatches batches = seen.batches();
        rows = 0;
        int need = 0;
        for (int b = 0, n = batches.size(); b < n; b++) {
            CgMeshBatches.Instances instances = batches.instances(b);
            if (instances == null || instances.count() == 0) continue;
            if (rows == batch.length) {
                batch = Arrays.copyOf(batch, rows * 2);
                first = Arrays.copyOf(first, rows * 2);
            }
            batch[rows] = b;
            first[rows] = need;
            need += (instances.count() + ALIGN - 1) / ALIGN * ALIGN;
            rows++;
        }
        if (rows == 0) return;
        CgBuffer records = scene.gpu().buffer();
        if (records == null) return;
        size(recording, need, rows);

        CgComputePass pass = recording.compute(name).timed(GPU);
        CgGpuOps.fill(pass, counts, 0, CgGpuCount.of(rows));
        viewProjection.set(camera.projection()).mul(camera.view());
        double tx = CgGpuScene.tile(camera.originX()), ty = CgGpuScene.tile(camera.originY()), tz = CgGpuScene.tile(camera.originZ());
        int tested = 0;
        for (int r = 0; r < rows; r++) {
            int b = batch[r];
            CgMeshBatches.Instances instances = batches.instances(b);
            int count = instances.count();
            tested += count;
            CgDispatch d = pass.dispatch(kernel(), count)
                    .bind("IDS", instances.gpuIds()).bind("RECORDS", records)
                    .bind("OUT", ids, first[r] * (long) ENTRY, (long) (count + ALIGN - 1) / ALIGN * ALIGN * ENTRY)
                    .counter("OUT", counts, r * 4L)
                    .set("_Count", count)
                    .set("_ViewTile", (float) tx, (float) ty, (float) tz, (float) CgGpuScene.TILE)
                    .set("_TileOffset", (float) (camera.originX() - tx * CgGpuScene.TILE),
                            (float) (camera.originY() - ty * CgGpuScene.TILE), (float) (camera.originZ() - tz * CgGpuScene.TILE), 0f);
            box(d, batches.mesh(b));
            view(d, camera, pyramid);
        }
        pass.end();
        CgTrace.add(CgChannels.WORLD, TESTED, tested);
    }

    /** Instanced rows the last cull culled. */
    public int rows() {
        return rows;
    }

    /** Row {@code r}'s batch in the visibility it culled. */
    public int batch(int r) {
        return batch[r];
    }

    /** Where row {@code r}'s kept ids start in {@link #ids()}, in entries of two words. */
    public int first(int r) {
        return first[r];
    }

    /** Row {@code r}'s count: its word of {@link #counts()}. */
    public int word(int r) {
        return r;
    }

    /** The kept instances' (object id, 0) entries, row by row, each row's from {@link #first}; null before a cull with a row. */
    @Nullable
    public CgGraphBuffer ids() {
        return ids;
    }

    /** A word per row: how many of its instances were kept. */
    @Nullable
    public CgGraphBuffer counts() {
        return counts;
    }

    /** Its buffers grown to {@code idWords} ids and {@code rowWords} counts, the old released in {@code recording}. */
    private void size(CgRecording recording, int idWords, int rowWords) {
        if (ids == null || idCapacity < idWords) {
            if (ids != null) recording.release(ids);
            idCapacity = Integer.highestOneBit(Math.max(ALIGN, idWords - 1)) << 1;
            ids = CgGraphBuffer.persistent(name + ".ids", CgBufferDesc.elements(idCapacity, ENTRY, CgBufferUsage.STORAGE));
        }
        if (counts == null || countCapacity < rowWords) {
            if (counts != null) recording.release(counts);
            countCapacity = Math.max(4, Integer.highestOneBit(Math.max(1, rowWords - 1)) << 1);
            counts = CgGraphBuffer.persistent(name + ".counts",
                    CgBufferDesc.of(countCapacity * 4L, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT, CgBufferUsage.COPY));
        }
    }

    /** {@code mesh}'s box, or a box no plane rejects for a mesh with none. */
    private void box(CgDispatch d, CgMesh mesh) {
        if (mesh.bounds(box) == null) {
            d.set("_Min", -1e30f, -1e30f, -1e30f, 0f).set("_Max", 1e30f, 1e30f, 1e30f, 0f);
            return;
        }
        d.set("_Min", (float) box.minX(), (float) box.minY(), (float) box.minZ(), 0f)
                .set("_Max", (float) box.maxX(), (float) box.maxY(), (float) box.maxZ(), 0f);
    }

    /** The view's frustum, clip rows and pyramid, as {@code cull.compute} takes them. */
    private void view(CgDispatch d, CgCamera camera, @Nullable CgGraphTexture pyramid) {
        Matrix4f vp = viewProjection;
        d.set("_ClipX", vp.m00(), vp.m10(), vp.m20(), vp.m30())
                .set("_ClipY", vp.m01(), vp.m11(), vp.m21(), vp.m31())
                .set("_ClipZ", vp.m02(), vp.m12(), vp.m22(), vp.m32())
                .set("_ClipW", vp.m03(), vp.m13(), vp.m23(), vp.m33());
        Matrix4f p = camera.projection();
        d.set("_Eye", p.m22(), p.m23(), p.m32(), p.m33());
        for (int i = 0; i < 6; i++) {
            vp.frustumPlane(i, plane);
            d.set(PLANES[i], plane.x, plane.y, plane.z, plane.w);
        }
        if (pyramid != null) {
            d.texture("_Pyramid", pyramid).set("_PyramidSize", pyramid.getWidth(), pyramid.getHeight(), pyramid.getLevels(), 0f);
        } else {
            d.texture("_Pyramid", CgFallbackTextures.WHITE_1x1).set("_PyramidSize", 0f, 0f, 0f, 0f);
        }
    }

    private static CgKernel kernel() {
        CgKernel k = kernel;
        if (k == null) {
            synchronized (CgInstanceCulling.class) {
                if (kernel == null) kernel = CgCompute.load(PATH).kernel("InstanceCull");
                k = kernel;
            }
        }
        return k;
    }
}
