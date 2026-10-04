package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.path.CgVfxPath;
import org.joml.Matrix4f;

/**
 * Draws a {@link CgVfxPath} as a tube: one static chunk mesh, {@link #SPANS} ring spans by {@link #SIDES} sides, drawn
 * once per chunk of the path and skinned onto its rings in the vertex shader ({@code fx_tube.glsl}).
 *
 * <p>Each chunk is its own {@link CgWorldRenderer} draw, at its centre and scaled to its bounds, so the world renderer
 * culls and sorts chunks one by one. What a chunk's shader reads:</p>
 * <ul>
 *   <li>{@code CG_OBJECT_CUSTOM0}: the path's row, the chunk's first ring, the layer's radius scale, the layer's
 *       parameter.</li>
 *   <li>{@code CG_OBJECT_CUSTOM1.xyz}: the chunk's centre minus the effect's origin, so
 *       {@code CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz} is the origin, camera-relative; {@code .w} how many
 *       rings from the first the chunk owns, each ring owned by one chunk.</li>
 *   <li>{@code CG_OBJECT_CUSTOM2}, {@code CG_OBJECT_CUSTOM3}: the layer's two colours, from the effect's values.</li>
 * </ul>
 * <p>The mesh's uv is (ring within the chunk, angle as 0..1), its seam column doubled; its positions span the unit cube
 * only so its bounds are right.</p>
 */
public final class CgVfxTube {

    public static final int SPANS = 16, SIDES = 24;
    private static final int DRAWS_TUBE = CgTrace.name("vfx.draws.tube"), DRAWS_TUBE_VOLUME = CgTrace.name("vfx.draws.tube-volume");
    /** How far a chunk's bounds reach past its rings, in radii: displacement, and the end caps' push. */
    private static final float BOUNDS_MARGIN = 1.6f;

    private final Matrix4f scale = new Matrix4f();

    /** A new chunk mesh, for {@code CgVfxSystem}, which releases it. */
    public static CgMesh mesh() {
        return CgMesh.build(CgVertexFormat.SPATIAL, CgVfxTube::write);
    }

    private static void write(CgMeshWriter m) {
        int columns = SIDES + 1;
        for (int r = 0; r <= SPANS; r++) {
            for (int s = 0; s < columns; s++) {
                float u = (float) s / SIDES;
                float a = (float) (2.0 * Math.PI * u);
                float c = (float) Math.cos(a), n = (float) Math.sin(a);
                m.vertex().position(0.5f * c, 0.5f * n, (float) r / SPANS - 0.5f).uv(r, u).normal(c, n, 0f).end();
            }
        }
        for (int r = 0; r < SPANS; r++) {
            for (int s = 0; s < SIDES; s++) {
                int a = r * columns + s, b = a + 1, c = a + columns, d = c + 1;
                // Counter-clockwise seen from outside: around the angle, then along the axis.
                m.triangle(a, b, c);
                m.triangle(b, d, c);
            }
        }
    }

    /**
     * Submits {@code path}, already in the frame's path texture at {@code row}, as one layer's chunks around the
     * effect origin {@code (ox, oy, oz)}: what {@code CgVfxFrame.tube} calls, with the system's mesh and material.
     */
    public void submit(CgWorldRenderer world, CgMesh mesh, CgMaterial material, CgVfxPath path, int row,
                       double ox, double oy, double oz, CgVfxLayer layer, CgVfxValues values) {
        int count = path.count();
        if (count < 2) return;
        float a0 = 0f, a1 = 0f, a2 = 0f, a3 = 0f, b0 = 0f, b1 = 0f, b2 = 0f, b3 = 0f;
        if (layer.colorA() != null) {
            a0 = values.get(layer.colorA(), 0); a1 = values.get(layer.colorA(), 1);
            a2 = values.get(layer.colorA(), 2); a3 = values.get(layer.colorA(), 3);
        }
        if (layer.colorB() != null) {
            b0 = values.get(layer.colorB(), 0); b1 = values.get(layer.colorB(), 1);
            b2 = values.get(layer.colorB(), 2); b3 = values.get(layer.colorB(), 3);
        }
        float scaleOf = layer.radius();
        for (int first = 0; first < count - 1; first += SPANS) {
            int last = Math.min(first + SPANS, count - 1);
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float reach = 0f;
            for (int i = first; i <= last; i++) {
                float x = path.x(i), y = path.y(i), z = path.z(i);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                reach = Math.max(reach, path.radius(i));
            }
            float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
            if (layer.isVolume()) {
                // A unit sphere round the chunk's rings and as far again as their light reaches.
                float half = 0.5f * (float) Math.sqrt(sq(maxX - minX) + sq(maxY - minY) + sq(maxZ - minZ));
                scale.scaling(half + reach * scaleOf);
            } else {
                reach *= scaleOf * BOUNDS_MARGIN;
                scale.scaling(maxX - minX + 2f * reach, maxY - minY + 2f * reach, maxZ - minZ + 2f * reach);
            }
            int owned = last == count - 1 ? last - first + 1 : SPANS;
            CgWorldRenderer.Draw draw = world.draw(mesh, material).at(ox + cx, oy + cy, oz + cz).transform(scale)
                    .custom(0, row, first, scaleOf, layer.parameter())
                    .custom(1, cx, cy, cz, owned)
                    .custom(2, a0, a1, a2, a3)
                    .custom(3, b0, b1, b2, b3)
                    .layer(CgVfxSystem.sortLayer(layer)).group(ox, oy, oz).order(layer.order());
            // A volume is soft light that adds: a quarter of the pixels draws it as well.
            if (layer.isVolume()) draw.halfResolution();
            draw.submit();
            CgVfxTrace.count(layer.isVolume() ? DRAWS_TUBE_VOLUME : DRAWS_TUBE, 1);
        }
    }

    private static float sq(float x) {
        return x * x;
    }
}
