package com.crystalgraphics.vfx;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxParticleSet;
import com.crystalgraphics.vfx.path.CgVfxPath;
import com.crystalgraphics.vfx.render.CgVfxQuads;
import com.crystalgraphics.vfx.render.CgVfxRibbons;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;

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
    private final Matrix4f scaled = new Matrix4f(), sized = new Matrix4f(), turned = new Matrix4f();
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
        draw(system.sphereMesh(), effect, layer, x, y, z, transform, ex, ey, ez, ew).submit();
    }

    /**
     * Draws {@code layer} on the ribbon mesh ({@code CgVfxRibbons}), its unit cube placed by {@code transform} at
     * {@code (x, y, z)} from {@code effect}'s origin and scaled by the layer's radius: stateless particles the shader
     * places inside that cube. Its shader reads the same per-draw data as {@link #mesh}'s.
     */
    public void ribbons(CgVfxEffect effect, CgVfxLayer layer, float x, float y, float z, Matrix4fc transform,
                        float ex, float ey, float ez, float ew) {
        draw(system.ribbonMesh(), effect, layer, x, y, z, transform, ex, ey, ez, ew).submit();
    }

    /**
     * Draws {@code emitter}'s particles through every layer of {@code effect}'s look in the emitter's slot, as its
     * renderer says (plan vfx-particles):
     * <ul>
     *   <li>{@code MESHES}: a {@link #mesh} per particle, turned by its spin and sized by its size over life;
     *       {@code CG_OBJECT_CUSTOM1} is its life 0..1, its seed, its opacity and its heat.</li>
     *   <li>{@code QUADS} and {@code ARCS}: one draw per {@link CgVfxQuads#COUNT} (or {@link CgVfxRibbons#COUNT})
     *       particles, reading the frame's particle records ({@code #pragma cg_use particle}).
     *       {@code CG_OBJECT_CUSTOM0}: the first record, how many, the layer's radius and parameter;
     *       {@code CG_OBJECT_CUSTOM1}: the draw's centre minus the effect's origin, and the effect's age, so
     *       {@code CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz} is the origin the records are relative to. An
     *       {@code ARCS} draw is centred on the emitter's source, so {@code CG_OBJECT_TO_WORLD[3]} is the source.</li>
     * </ul>
     *
     * <pre>{@code
     * for (CgVfxEmitterInstance emitter : emitters) frame.particles(this, emitter);
     * }</pre>
     */
    public void particles(CgVfxEffect effect, CgVfxEmitterInstance emitter) {
        if (emitter.particles().count() == 0) return;
        String slot = emitter.emitter().layer();
        List<CgVfxLayer> layers = effect.look().layers();
        for (int k = 0; k < layers.size(); k++) {
            CgVfxLayer layer = layers.get(k);
            if (!slot.equals(layer.slot())) continue;
            switch (emitter.emitter().renderer()) {
                case MESHES -> particleMeshes(effect, emitter, layer);
                case QUADS -> particleDraws(effect, emitter, layer, system.quadMesh(), CgVfxQuads.COUNT, 6, false);
                case ARCS -> particleDraws(effect, emitter, layer, system.ribbonMesh(), CgVfxRibbons.COUNT,
                        CgVfxRibbons.INDICES, true);
            }
        }
    }

    private void particleMeshes(CgVfxEffect effect, CgVfxEmitterInstance emitter, CgVfxLayer layer) {
        CgVfxEmitter def = emitter.emitter();
        CgVfxParticleSet p = emitter.particles();
        for (int i = 0; i < p.count(); i++) {
            float t = p.progress(i), turn = p.seed[i] * 6.2831853f + p.spin[i];
            turned.rotationXYZ(turn * 1.7f, turn * 2.3f, turn).scale(p.size[i] * def.sizeAt(t));
            mesh(effect, layer, p.x(i, alpha), p.y(i, alpha), p.z(i, alpha), turned, t, p.seed[i], def.opacityAt(t), p.heat[i]);
        }
    }

    /**
     * Draws of up to {@code perDraw} particles each, {@code indicesEach} of the mesh's indices a particle, their
     * transform the particles' bounding box: the unit cube, stated, since the quads have no bounds of their own.
     */
    private void particleDraws(CgVfxEffect effect, CgVfxEmitterInstance emitter, CgVfxLayer layer, CgMesh mesh,
                               int perDraw, int indicesEach, boolean aroundSource) {
        CgVfxParticleSet p = emitter.particles();
        int base = system.particleBase(emitter);
        CgVfxValues values = effect.values();
        float stretch = Math.max(layer.radius(), 1f) * 4f;
        for (int start = 0; start < p.count(); start += perDraw) {
            int n = Math.min(perDraw, p.count() - start);
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE, margin = 0f;
            for (int i = start; i < start + n; i++) {
                float x = p.x(i, alpha), y = p.y(i, alpha), z = p.z(i, alpha);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                margin = Math.max(margin, p.size[i] * stretch);
            }
            float cx, cy, cz, hx, hy, hz;
            if (aroundSource) {
                // An arc is a circle round the source through its particle: the box round the widest circle.
                cx = emitter.sourceX();
                cy = emitter.sourceY();
                cz = emitter.sourceZ();
                float reach = 0f;
                for (int i = start; i < start + n; i++) {
                    float dx = p.x[i] - cx, dy = p.y[i] - cy, dz = p.z[i] - cz;
                    reach = Math.max(reach, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                hx = hy = hz = reach + margin;
            } else {
                cx = (minX + maxX) * 0.5f;
                cy = (minY + maxY) * 0.5f;
                cz = (minZ + maxZ) * 0.5f;
                hx = (maxX - minX) * 0.5f + margin;
                hy = (maxY - minY) * 0.5f + margin;
                hz = (maxZ - minZ) * 0.5f + margin;
            }
            scaled.scaling(Math.max(hx, 1.0e-3f), Math.max(hy, 1.0e-3f), Math.max(hz, 1.0e-3f));
            CgWorldRenderer.Draw draw = world.draw(mesh, system.material(layer)).indices(0, n * indicesEach)
                    .bounds(-1f, -1f, -1f, 1f, 1f, 1f)
                    .at(effect.originX + cx, effect.originY + cy, effect.originZ + cz).transform(scaled)
                    .custom(0, base + start, n, layer.radius(), layer.parameter())
                    .custom(1, cx, cy, cz, effect.age);
            color(draw, 2, layer.colorA(), values);
            color(draw, 3, layer.colorB(), values);
            draw.priority(layer.priority()).submit();
        }
    }

    /**
     * Draws {@code layer} on one camera-facing quad ({@code CgMesh.quads(1)}) at {@code (x, y, z)} from {@code effect}'s
     * origin, {@code size} times the layer's radius from its centre to an edge: one particle. Each is its own draw, so
     * the world renderer sorts alpha-blended ones back to front and instances neighbours. Its shader reads the same
     * per-draw data as {@link #mesh}'s, {@code (ex, ey, ez, ew)} being the particle's own, and turns the quad itself:
     *
     * <pre>{@code
     * vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
     * vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
     * vec3 world = CG_OBJECT_TO_WORLD[3].xyz
     *         + (right * FX_QUAD_CORNER.x + up * FX_QUAD_CORNER.y) * length(CG_OBJECT_TO_WORLD[0].xyz);
     * }</pre>
     */
    public void billboard(CgVfxEffect effect, CgVfxLayer layer, float x, float y, float z, float size,
                          float ex, float ey, float ez, float ew) {
        // Its bounds: the cube its transform scales, whichever way the shader turns it.
        draw(CgMesh.quads(1), effect, layer, x, y, z, sized.scaling(size), ex, ey, ez, ew).bounds(-1f, -1f, -1f, 1f, 1f, 1f)
                .submit();
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

    /** A draw of {@code layer} on {@code mesh} with the per-draw data every effect shader reads, for the caller to submit. */
    private CgWorldRenderer.Draw draw(CgMesh mesh, CgVfxEffect effect, CgVfxLayer layer, float x, float y, float z,
                                      Matrix4fc transform, float ex, float ey, float ez, float ew) {
        CgVfxValues values = effect.values();
        scaled.set(transform).scale(layer.radius());
        CgWorldRenderer.Draw draw = world.draw(mesh, system.material(layer))
                .at(effect.originX + x, effect.originY + y, effect.originZ + z).transform(scaled)
                .custom(0, layer.radius(), layer.parameter(), effect.age, effect.seed)
                .custom(1, ex, ey, ez, ew);
        color(draw, 2, layer.colorA(), values);
        color(draw, 3, layer.colorB(), values);
        return draw.priority(layer.priority());
    }

    private static void color(CgWorldRenderer.Draw draw, int slot, CgVfxParam param, CgVfxValues values) {
        if (param == null) return;
        draw.custom(slot, values.get(param, 0), values.get(param, 1), values.get(param, 2), values.get(param, 3));
    }
}
