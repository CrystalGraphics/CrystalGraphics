package com.crystalgraphics.vfx;

import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.path.CgVfxPath;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * What a {@link CgVfxEffect} draws through in one frame, handed to it by {@link CgVfxSystem#submit}.
 *
 * <pre>{@code
 * @Override protected void submit(CgVfxFrame frame) {
 *     path.build(points, n, 0.2f);
 *     int row = frame.path(path, seed, age);
 *     for (CgVfxLayer layer : look().layers()) frame.tube(this, path, row, layer);
 * }
 * }</pre>
 */
public final class CgVfxFrame {

    private final CgVfxSystem system;
    private final Matrix4f scaled = new Matrix4f(), sized = new Matrix4f();
    private CgWorldRenderer world;
    private float alpha;

    CgVfxFrame(CgVfxSystem system) {
        this.system = system;
    }

    void begin(CgWorldRenderer world, float alpha) {
        this.world = world;
        this.alpha = alpha;
    }

    /** How far this frame is between the last tick and the next, 0..1: draw positions moved on by this much. */
    public float alpha() {
        return alpha;
    }

    public CgWorldRenderer world() {
        return world;
    }

    /** Puts {@code path} in this frame's path texture and answers its row. */
    public int path(CgVfxPath path, float seed, float age) {
        return system.paths().add(path, seed, age);
    }

    /** Draws {@code layer} as a tube along {@code path}, at {@code row}, around {@code effect}'s origin. */
    public void tube(CgVfxEffect effect, CgVfxPath path, int row, CgVfxLayer layer) {
        system.tube().submit(world, layer.isVolume() ? system.sphereMesh() : system.tubeMesh(), system.material(layer), path, row,
                effect.originX, effect.originY, effect.originZ, layer, effect.values());
    }

    /**
     * Draws {@code layer} on a unit sphere placed by {@code transform} (rotation and scale, radius 1 before it) at
     * {@code (x, y, z)} from {@code effect}'s origin, scaled again by the layer's radius. What its shader reads:
     * <ul>
     *   <li>{@code CG_OBJECT_CUSTOM0}: the layer's radius, its parameter, the effect's age and its seed.</li>
     *   <li>{@code CG_OBJECT_CUSTOM1}: {@code (ex, ey, ez, ew)}, which the effect defines.</li>
     *   <li>{@code CG_OBJECT_CUSTOM2}, {@code CG_OBJECT_CUSTOM3}: the layer's two colours.</li>
     * </ul>
     * The model matrix's columns are the sphere's axes, its +z the effect's forward where it has one.
     */
    public void mesh(CgVfxEffect effect, CgVfxLayer layer, float x, float y, float z, Matrix4fc transform,
                     float ex, float ey, float ez, float ew) {
        draw(system.sphereMesh(), effect, layer, x, y, z, transform, ex, ey, ez, ew);
    }

    /**
     * Draws {@code layer} on the ribbon mesh ({@code CgVfxRibbons}), its unit cube placed by {@code transform} at
     * {@code (x, y, z)} from {@code effect}'s origin and scaled by the layer's radius: stateless particles the shader
     * places inside that cube. Its shader reads the same per-draw data as {@link #mesh}'s.
     */
    public void ribbons(CgVfxEffect effect, CgVfxLayer layer, float x, float y, float z, Matrix4fc transform,
                        float ex, float ey, float ez, float ew) {
        draw(system.ribbonMesh(), effect, layer, x, y, z, transform, ex, ey, ez, ew);
    }

    /**
     * Draws {@code layer} on one camera-facing quad ({@code CgVfxBillboard}) at {@code (x, y, z)} from {@code effect}'s
     * origin, {@code size} times the layer's radius from its centre to an edge: one particle. Each is its own draw, so
     * the world renderer sorts alpha-blended ones back to front and instances neighbours. Its shader reads the same
     * per-draw data as {@link #mesh}'s, {@code (ex, ey, ez, ew)} being the particle's own.
     */
    public void billboard(CgVfxEffect effect, CgVfxLayer layer, float x, float y, float z, float size,
                          float ex, float ey, float ez, float ew) {
        draw(system.billboardMesh(), effect, layer, x, y, z, sized.scaling(size), ex, ey, ez, ew);
    }

    /**
     * Draws {@code layer} on the ribbon mesh spread over {@code path}, at {@code row}: stateless particles that ride the
     * path itself, reading it through {@code fx_tube.glsl} ({@code fx_ring_at}). What its shader reads:
     * <ul>
     *   <li>{@code _FxPath}; the header gives the effect's age and seed.</li>
     *   <li>{@code CG_OBJECT_CUSTOM0}: the path's row, 0, the layer's radius, the layer's parameter.</li>
     *   <li>{@code CG_OBJECT_CUSTOM1}: the draw's centre minus the effect's origin, so
     *       {@code CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz} is the origin; {@code .w} an intensity.</li>
     *   <li>{@code CG_OBJECT_CUSTOM2}, {@code CG_OBJECT_CUSTOM3}: the layer's two colours.</li>
     * </ul>
     * The draw's bounds are the path's, grown by its widest ring times the layer's radius.
     */
    public void pathRibbons(CgVfxEffect effect, CgVfxPath path, int row, CgVfxLayer layer, float intensity) {
        int count = path.count();
        if (count < 2) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE, reach = 0f;
        for (int i = 0; i < count; i++) {
            minX = Math.min(minX, path.x(i)); maxX = Math.max(maxX, path.x(i));
            minY = Math.min(minY, path.y(i)); maxY = Math.max(maxY, path.y(i));
            minZ = Math.min(minZ, path.z(i)); maxZ = Math.max(maxZ, path.z(i));
            reach = Math.max(reach, path.radius(i));
        }
        reach *= Math.max(layer.radius(), 1f) * 2f;
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        // The ribbon mesh spans -1..1: half the extent each way.
        scaled.scaling((maxX - minX) * 0.5f + reach, (maxY - minY) * 0.5f + reach, (maxZ - minZ) * 0.5f + reach);
        CgVfxValues values = effect.values();
        CgWorldRenderer.Draw draw = world.draw(system.ribbonMesh(), system.material(layer))
                .at(effect.originX + cx, effect.originY + cy, effect.originZ + cz).transform(scaled)
                .custom(0, row, 0f, layer.radius(), layer.parameter())
                .custom(1, cx, cy, cz, intensity);
        color(draw, 2, layer.colorA(), values);
        color(draw, 3, layer.colorB(), values);
        draw.priority(layer.priority()).submit();
    }

    private void draw(CgMesh mesh, CgVfxEffect effect, CgVfxLayer layer, float x, float y, float z, Matrix4fc transform,
                      float ex, float ey, float ez, float ew) {
        CgVfxValues values = effect.values();
        scaled.set(transform).scale(layer.radius());
        CgWorldRenderer.Draw draw = world.draw(mesh, system.material(layer))
                .at(effect.originX + x, effect.originY + y, effect.originZ + z).transform(scaled)
                .custom(0, layer.radius(), layer.parameter(), effect.age, effect.seed)
                .custom(1, ex, ey, ez, ew);
        color(draw, 2, layer.colorA(), values);
        color(draw, 3, layer.colorB(), values);
        draw.priority(layer.priority()).submit();
    }

    private static void color(CgWorldRenderer.Draw draw, int slot, CgVfxParam param, CgVfxValues values) {
        if (param == null) return;
        draw.custom(slot, values.get(param, 0), values.get(param, 1), values.get(param, 2), values.get(param, 3));
    }
}
