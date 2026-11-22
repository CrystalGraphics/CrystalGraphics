package com.crystalgraphics.render.scene.component;

import java.util.Arrays;

import javax.annotation.Nullable;

import org.joml.Matrix4dc;
import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

/**
 * Lines and boxes in a scene, each line's width in screen pixels at any distance: Unreal's {@code ULineBatchComponent}.
 * What markers, outlines and a tool's preview draw with: a picked block's outline, a selection's box, a grid's axes.
 *
 * <pre>{@code
 * CgLineBatchComponent outline = CgLineBatchComponent.of(scene).thickness(2f).spawn();
 * for (CgAABBc box : hit.outline()) outline.box(box, 0xFF000000);     // as vanilla outlines a block
 * outline.clear();                                                     // the next pick's
 * }</pre>
 *
 * <pre>{@code
 * CgLineBatchComponent axes = CgLineBatchComponent.of(scene).foreground(true).thickness(3f).spawn();
 * axes.line(0, 0, 0, 1, 0, 0, 0xFFFF4040).line(0, 0, 0, 0, 1, 0, 0xFF40FF40).line(0, 0, 0, 0, 0, 1, 0xFF4080FF);
 * }</pre>
 *
 * <ul>
 *   <li>Lines are in its own space: placed, turned and attached as any component, they move with it.</li>
 *   <li>Depth-tested by default, writing no depth: one line never hides another. {@code foreground(true)} draws them
 *       over everything (Unreal's {@code SDPG_Foreground}).</li>
 *   <li>Unlit, translucent by their colour's alpha. Kept until {@link #clear}: unlike {@code CgDebugDraw}, nothing
 *       expires.</li>
 *   <li>Changed on any thread; the mesh is written again on the frame after a change.</li>
 * </ul>
 */
public final class CgLineBatchComponent extends CgPrimitiveComponent<CgLineBatchComponent> {

    static final String SHADER = "crystalgraphics:shaders/materials/line_batch.shader";
    static final String FOREGROUND_SHADER = "crystalgraphics:shaders/materials/line_batch_foreground.shader";

    // Segments in its own space, six floats each, and a colour each
    private float[] points = new float[6 * 16];
    private int[] colours = new int[16];
    private int count;
    private float thickness = 1f;
    private boolean foreground, dirty;
    @Nullable private CgMesh mesh;
    @Nullable private CgMaterialInterface material;
    private final CgMeshCollector collector = new CgMeshCollector();
    private final Matrix4f basis = new Matrix4f();
    // Written by the mesh writer from a snapshot taken under the lock
    private float[] writing = new float[0];
    private int[] writingColours = new int[0];
    private int writingCount;
    private float writingThickness;

    private CgLineBatchComponent(CgScene scene) {
        super(scene.primitives());
    }

    /** An empty batch in {@code scene}, to fill and spawn. */
    public static CgLineBatchComponent of(CgScene scene) {
        return new CgLineBatchComponent(scene);
    }

    /** Each line's width in screen pixels; 1 by default. */
    public synchronized CgLineBatchComponent thickness(float pixels) {
        if (!(pixels > 0f)) throw new IllegalArgumentException("a line's thickness is positive, not " + pixels);
        thickness = pixels;
        dirty = true;
        return this;
    }

    /** Over everything, the scene's depth ignored, where {@code on}; depth-tested by default. */
    public synchronized CgLineBatchComponent foreground(boolean on) {
        if (foreground != on) material = null;
        foreground = on;
        return this;
    }

    /** A line from {@code a} to {@code b}, in its own space, coloured ARGB. */
    public synchronized CgLineBatchComponent line(double ax, double ay, double az, double bx, double by, double bz, int argb) {
        if (count == colours.length) {
            colours = Arrays.copyOf(colours, count * 2);
            points = Arrays.copyOf(points, count * 12);
        }
        int at = count * 6;
        points[at] = (float) ax;
        points[at + 1] = (float) ay;
        points[at + 2] = (float) az;
        points[at + 3] = (float) bx;
        points[at + 4] = (float) by;
        points[at + 5] = (float) bz;
        colours[count++] = argb;
        dirty = true;
        return this;
    }

    /** A box's twelve edges, in its own space. */
    public CgLineBatchComponent box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb) {
        for (int i = 0; i < 4; i++) {
            double y = (i & 1) == 0 ? minY : maxY, z = (i & 2) == 0 ? minZ : maxZ;
            line(minX, y, z, maxX, y, z, argb);
            double x = (i & 1) == 0 ? minX : maxX;
            line(x, minY, z, x, maxY, z, argb);
            double y2 = (i & 2) == 0 ? minY : maxY;
            line(x, y2, minZ, x, y2, maxZ, argb);
        }
        return this;
    }

    public CgLineBatchComponent box(CgAABBc box, int argb) {
        return box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), argb);
    }

    /** Every line gone. */
    public synchronized CgLineBatchComponent clear() {
        count = 0;
        dirty = true;
        return this;
    }

    /** How many lines it holds. */
    public synchronized int lines() {
        return count;
    }

    // ── Engine side ──────────────────────────────────────────────────────────────

    /** Engine side, the world's precache: both layers' materials. */
    public static void precache(CgPipelineList list) {
        list.add(CgMaterial.load(SHADER), false);
        list.add(CgMaterial.load(FOREGROUND_SHADER), false);
    }

    /** Its rows are built per view from a mesh written again only on a change. */
    @Override
    boolean keepsCommands() {
        return false;
    }

    @Override
    public boolean keepsPrevious() {
        return false;
    }

    @Override
    void onSpawn() {
    }

    @Override
    void onRemove() {
        if (mesh != null) mesh.release();
        mesh = null;
    }

    @Override
    void onMoved() {
    }

    @Override
    void onLooked() {
    }

    @Override
    synchronized boolean kindBounds(CgAABB out) {
        if (count == 0) return false;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < count * 6; i += 3) {
            x0 = Math.min(x0, points[i]);
            y0 = Math.min(y0, points[i + 1]);
            z0 = Math.min(z0, points[i + 2]);
            x1 = Math.max(x1, points[i]);
            y1 = Math.max(y1, points[i + 1]);
            z1 = Math.max(z1, points[i + 2]);
        }
        out.set(x0, y0, z0, x1, y1, z1);
        return true;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
        CgMaterialInterface m;
        synchronized (this) {
            if (count == 0) return;
            if (dirty) {
                dirty = false;
                writing = Arrays.copyOf(points, count * 6);
                writingColours = Arrays.copyOf(colours, count);
                writingCount = count;
                writingThickness = thickness;
                if (mesh == null) mesh = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.DYNAMIC, w -> write(w, this));
                else mesh.edit(this, CgLineBatchComponent::write);
            }
            if (material == null) material = CgMaterial.load(foreground ? FOREGROUND_SHADER : SHADER);
            m = material;
        }
        Matrix4dc t = worldTransform();
        basis.set(t);
        basis.setTranslation(0f, 0f, 0f);
        collector.begin(out, this, camera, gather);
        collector.mesh(mesh, m).at(t.m30(), t.m31(), t.m32()).transform(basis).add();
    }

    // Each segment a quad of two triangles: its ends, each pushed either way across it by half the width
    private static void write(CgMeshWriter w, CgLineBatchComponent b) {
        w.topology(CgMeshTopology.TRIANGLES);
        float half = b.writingThickness * 0.5f;
        float[] p = b.writing;
        for (int i = 0; i < b.writingCount; i++) {
            int at = i * 6, argb = b.writingColours[i];
            float r = (argb >>> 16 & 0xFF) / 255f, g = (argb >>> 8 & 0xFF) / 255f, bl = (argb & 0xFF) / 255f;
            float a = (argb >>> 24) / 255f;
            float ax = p[at], ay = p[at + 1], az = p[at + 2], bx = p[at + 3], by = p[at + 4], bz = p[at + 5];
            float dx = bx - ax, dy = by - ay, dz = bz - az;
            // a0 a1 b1, a0 b1 b0: the tangent always points along the segment, the side's sign is the corner's
            corner(w, ax, ay, az, dx, dy, dz, -half, r, g, bl, a);
            corner(w, ax, ay, az, dx, dy, dz, half, r, g, bl, a);
            corner(w, bx, by, bz, dx, dy, dz, half, r, g, bl, a);
            corner(w, ax, ay, az, dx, dy, dz, -half, r, g, bl, a);
            corner(w, bx, by, bz, dx, dy, dz, half, r, g, bl, a);
            corner(w, bx, by, bz, dx, dy, dz, -half, r, g, bl, a);
        }
    }

    // The colour rides in the normal, its alpha and the signed half-width in uv, the segment's direction in the tangent
    private static void corner(CgMeshWriter w, float x, float y, float z, float dx, float dy, float dz, float side,
                               float r, float g, float b, float a) {
        w.vertex().position(x, y, z).uv(a, side).normal(r, g, b).tangent(dx, dy, dz, 1f).end();
    }
}
