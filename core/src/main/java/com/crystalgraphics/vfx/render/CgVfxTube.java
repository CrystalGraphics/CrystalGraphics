package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.path.CgVfxPath;
import org.joml.Matrix4f;

/**
 * Draws a {@link CgVfxPath} as a tube: one static chunk mesh, {@link #SPANS} ring spans by {@link #SIDES} sides, drawn
 * once per chunk of the path and skinned onto its rings in the vertex shader ({@code fx_tube.glsl}).
 *
 * <p>Each chunk is its own {@link CgWorldRenderer} draw, at its centre and scaled to its bounds, so the world renderer
 * culls and sorts chunks one by one. What a chunk's shader reads:</p>
 * <ul>
 *   <li>{@code CG_OBJECT_CUSTOM0}: the path's row, the chunk's first ring, the layer's size, 0.</li>
 *   <li>{@code CG_OBJECT_CUSTOM1.xyz}: the chunk's centre minus the effect's origin, so
 *       {@code CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz} is the origin, camera-relative; {@code .w} how many
 *       rings from the first the chunk owns, each ring owned by one chunk.</li>
 *   <li>{@code CG_OBJECT_CUSTOM3}: the draw header ({@code fx_draw.glsl}): the effect's row for the layer, its age, its
 *       seed and its presence.</li>
 * </ul>
 * <p>The mesh's uv is (ring within the chunk, angle as 0..1), its seam column doubled, and a closed mesh's end centres
 * have angle -1; its positions span the unit cube only so its bounds are right.</p>
 */
public final class CgVfxTube {

    // A shell displaced per vertex shows a tooth wherever its displacement rises within one side: keep it across several.
    public static final int SPANS = 16, SIDES = 48;
    private static final int DRAWS_TUBE = CgTrace.name("vfx.draws.tube"), DRAWS_TUBE_VOLUME = CgTrace.name("vfx.draws.tube-volume");
    /** How far a chunk's bounds reach past its rings, in radii: displacement, and the end caps' push. */
    private static final float BOUNDS_MARGIN = 1.6f;

    private final Matrix4f scale = new Matrix4f();

    /** A new chunk mesh, for {@code CgVfxSystem}, which releases it; closed by a disc at each end for a closed layer. */
    public static CgMesh mesh(boolean closed) {
        return CgMesh.build(CgVertexFormat.SPATIAL, m -> write(m, closed));
    }

    private static void write(CgMeshWriter m, boolean closed) {
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
        if (!closed) return;
        // A centre per end, its uv's angle -1 (fx_tube_vertex), fanned counter-clockwise seen from outside: from -z first.
        int start = (SPANS + 1) * columns, end = start + 1;
        m.vertex().position(0f, 0f, -0.5f).uv(0f, -1f).normal(0f, 0f, -1f).end();
        m.vertex().position(0f, 0f, 0.5f).uv(SPANS, -1f).normal(0f, 0f, 1f).end();
        for (int s = 0; s < SIDES; s++) {
            m.triangle(start, s + 1, s);
            m.triangle(end, SPANS * columns + s, SPANS * columns + s + 1);
        }
    }

    /**
     * Submits {@code path}, already in the frame's path texture at {@code row}, as one layer's chunks around the
     * origin of {@code effect}, sorted in its group, {@code size} times its rings: what {@code CgVfxFrame.tube} calls,
     * with the system's mesh and the layer's material, and the effect's header.
     */
    public void submit(CgWorldRenderer world, CgMesh mesh, CgMaterial material, CgVfxLook.Placed placed, CgVfxPath path,
                       int row, CgVfxEffect<?> effect, float size, int valuesRow, float age, float seed, float presence) {
        int count = path.count();
        if (count < 2) return;
        double ox = effect.originX(), oy = effect.originY(), oz = effect.originZ();
        boolean volume = placed.volume();
        float scaleOf = size;
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
            if (volume) {
                // A unit sphere round the chunk's rings and as far again as their light reaches.
                float half = 0.5f * (float) Math.sqrt(sq(maxX - minX) + sq(maxY - minY) + sq(maxZ - minZ));
                scale.scaling(half + reach * scaleOf);
            } else {
                reach *= scaleOf * BOUNDS_MARGIN;
                scale.scaling(maxX - minX + 2f * reach, maxY - minY + 2f * reach, maxZ - minZ + 2f * reach);
            }
            int owned = last == count - 1 ? last - first + 1 : SPANS;
            CgWorldRenderer.Draw draw = world.draw(mesh, material).at(ox + cx, oy + cy, oz + cz).transform(scale)
                    .custom(0, row, first, scaleOf, 0f)
                    .custom(1, cx, cy, cz, owned)
                    .custom(3, valuesRow, age, seed, presence);
            CgVfxSystem.place(draw, placed, effect);
            // A volume is soft light that adds: a quarter of the pixels draws it as well.
            if (volume) draw.halfResolution();
            draw.submit();
            CgVfxTrace.count(volume ? DRAWS_TUBE_VOLUME : DRAWS_TUBE, 1);
        }
    }

    private static float sq(float x) {
        return x * x;
    }
}
