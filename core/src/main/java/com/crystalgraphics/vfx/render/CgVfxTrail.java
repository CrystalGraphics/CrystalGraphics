package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * A trail behind a moving point: the last {@code capacity} points it passed through, drawn as a ribbon turned to face
 * the eye, widest at the head and tapering to nothing at the tail ({@code trail.shader}). Its mesh is rewritten every
 * frame it draws ({@link CgMesh.Usage#FRAME}) from the points the caller pushes.
 *
 * <pre>{@code
 * CgVfxTrail trail = new CgVfxTrail(64);
 * CgMaterial glow = CgMaterial.load("crystalgraphics:shaders/vfx/particle/trail.shader");
 * // each frame (or tick) the point moves:
 * trail.push(x, y, z, 0.15f);
 * trail.submit(CgWorldRenderer.get(), glow, 0.4f, 0.8f, 1f, 1.5f);
 * // when it is done:
 * trail.release();
 * }</pre>
 *
 * <p>Many trails in one mesh, written relative to one origin:</p>
 *
 * <pre>{@code
 * static void writeAll(CgMeshWriter m, List<CgVfxTrail> trails) {
 *     for (int i = 0; i < trails.size(); i++) trails.get(i).writeTo(m, 0.0, 0.0, 0.0);
 * }
 * merged.edit(trails, MyEffect::writeAll);
 * world.draw(merged, glow).at(0.0, 0.0, 0.0).custom(0, r, g, b, strength).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Each point writes two vertices in {@code CgVertexFormat.SPATIAL}: the point, its tangent scaled to its half-width
 *       in the normal, and (how far from the tail 0..1, side 0 or 1) in the uv; the shader extrudes them toward the
 *       eye. A merged mesh's bounds must be padded by the widest half-width ({@link #widest()}).</li>
 *   <li>Points are kept as float offsets from the first one pushed; the draw stands there in doubles.</li>
 *   <li>Allocates nothing per frame. Push and submit from one thread.</li>
 * </ul>
 */
public final class CgVfxTrail {

    private final int capacity;
    private final float[] x, y, z, width;
    private int first, count;
    private double ox, oy, oz;
    private boolean anchored;
    private float widest;
    private CgMesh mesh;

    public CgVfxTrail(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity must be >= 2, got " + capacity);
        this.capacity = capacity;
        x = new float[capacity];
        y = new float[capacity];
        z = new float[capacity];
        width = new float[capacity];
    }

    /** Adds a head point, dropping the oldest once full. {@code halfWidth} is the ribbon's half-width there. */
    public void push(double px, double py, double pz, float halfWidth) {
        if (!anchored) {
            ox = px;
            oy = py;
            oz = pz;
            anchored = true;
        }
        int at;
        if (count == capacity) {
            at = first;
            first = (first + 1) % capacity;
        } else {
            at = (first + count) % capacity;
            count++;
        }
        x[at] = (float) (px - ox);
        y[at] = (float) (py - oy);
        z[at] = (float) (pz - oz);
        width[at] = halfWidth;
        widest = Math.max(widest, halfWidth);
    }

    /** Forgets every point; the next push anchors the trail anew. */
    public void clear() {
        first = count = 0;
        anchored = false;
        widest = 0f;
    }

    public int count() {
        return count;
    }

    /** The widest half-width pushed since the last {@link #clear}: how far the ribbon reaches past its points. */
    public float widest() {
        return widest;
    }

    /** Rewrites this trail's own mesh and draws it, coloured {@code (r, g, b)} at {@code strength}. Nothing below two points. */
    public void submit(CgWorldRenderer world, CgMaterial material, float r, float g, float b, float strength) {
        if (count < 2) return;
        if (mesh == null) mesh = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> write(m, this));
        else mesh.edit(this, CgVfxTrail::write);
        mesh.pad(widest);
        world.draw(mesh, material).at(ox, oy, oz).custom(0, r, g, b, strength).submit();
    }

    private static void write(CgMeshWriter m, CgVfxTrail trail) {
        trail.writeTo(m, trail.ox, trail.oy, trail.oz);
    }

    /** Appends this trail's vertices and triangles to {@code m}, positioned relative to {@code (originX, originY, originZ)}. */
    public void writeTo(CgMeshWriter m, double originX, double originY, double originZ) {
        if (count < 2) return;
        float dx = (float) (ox - originX), dy = (float) (oy - originY), dz = (float) (oz - originZ);
        float tx = 0f, ty = 1f, tz = 0f;
        int previousA = -1, previousB = -1;
        for (int i = 0; i < count; i++) {
            int at = (first + i) % capacity;
            int before = (first + Math.max(i - 1, 0)) % capacity, after = (first + Math.min(i + 1, count - 1)) % capacity;
            float ax = x[after] - x[before], ay = y[after] - y[before], az = z[after] - z[before];
            float length = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            // A point that has not moved keeps the last direction.
            if (length > 1.0e-6f) {
                tx = ax / length;
                ty = ay / length;
                tz = az / length;
            }
            float w = width[at], along = (float) i / (count - 1);
            float px = x[at] + dx, py = y[at] + dy, pz = z[at] + dz;
            int a = m.vertex().position(px, py, pz).uv(along, 0f).normal(tx * w, ty * w, tz * w).end();
            int b = m.vertex().position(px, py, pz).uv(along, 1f).normal(tx * w, ty * w, tz * w).end();
            if (previousA >= 0) m.quad(previousA, previousB, b, a);
            previousA = a;
            previousB = b;
        }
    }

    /** Releases its mesh; the trail may draw again, and makes a new one. */
    public void release() {
        if (mesh != null) mesh.release();
        mesh = null;
    }
}
