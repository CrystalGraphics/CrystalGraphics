package com.crystalgraphics.mc.render.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgShadingModels;
import com.crystalgraphics.api.material.CgSurfaceMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.CgDepthConvention;
import com.crystalgraphics.render.host.CgHostLevel;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.target.CgRenderTarget;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

/**
 * The host of {@link CgPocketLevel} where no Minecraft runs (the GL debug harness, a test): each block's exposed faces in
 * the block's colour, shaded per face as Minecraft shades blocks, drawn into a target with depth, so everything above the
 * host (a scene beside the level's depth, picking, slicing by layer, editing) runs as in a game.
 *
 * <pre>{@code
 * CgPlatform.provide(CgPocketLevel.HOST, new CgStandInPocketLevels());
 * }</pre>
 *
 * <ul>
 *   <li>Every block is a full cube, picked and outlined as one box; glass is see-through and hides no face behind it.
 *       Block entities, ticking and light are not drawn.</li>
 *   <li>Needs a host whose world draws reversed, as the engine does (the harness): elsewhere {@code create} throws.</li>
 *   <li>Render thread, as every host.</li>
 * </ul>
 */
public final class CgStandInPocketLevels implements CgPocketLevel.Host {

    private static final CgMesh FACE = CgMeshShapes.quad();
    /** The six faces in CgDirection's order (down, up, north, south, west, east): Minecraft's shade for each. */
    private static final float[] SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};
    private static final Quaternionfc[] TURN = {
            new Quaternionf().rotateX((float) Math.PI / 2), new Quaternionf().rotateX((float) -Math.PI / 2),
            new Quaternionf().rotateY((float) Math.PI), new Quaternionf(),
            new Quaternionf().rotateY((float) -Math.PI / 2), new Quaternionf().rotateY((float) Math.PI / 2)};
    private final Map<String, CgMaterial> materials = new HashMap<>();

    /** One level: its blocks as cubes in a scene of its own, and a target per view that asks. */
    private static final class Level {
        final CgScene scene = new CgScene();
        final Map<Long, Cell> cells = new HashMap<>();
        final Map<Object, Target> targets = new IdentityHashMap<>();
        int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
    }

    /** A block and its faces drawn, null where hidden. */
    private static final class Cell {
        final int x, y, z;
        final CgBlockState state;
        final boolean glass;
        final CgMeshComponent[] faces = new CgMeshComponent[6];

        Cell(int x, int y, int z, CgBlockState state) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.glass = state.block().contains("glass");
        }

        void clear() {
            for (int i = 0; i < 6; i++) {
                if (faces[i] != null) faces[i].remove();
                faces[i] = null;
            }
        }
    }

    private static final class Target {
        final CgView view;
        @Nullable CgRenderTarget picture;

        Target(CgScene scene) {
            view = CgView.of(scene);
        }
    }

    @Override
    @Nullable
    public String absent() {
        return null;
    }

    @Override
    public Object create() {
        if (!CgDepthConvention.worldReversed()) {
            throw new IllegalStateException("the stand-in pocket level draws as the engine does: its host's world must draw reversed");
        }
        return new Level();
    }

    @Override
    public void setBlock(Object level, int x, int y, int z, @Nullable CgBlockState state) {
        Level l = (Level) level;
        Cell old = l.cells.remove(key(x, y, z));
        if (old != null) old.clear();
        if (state != null) l.cells.put(key(x, y, z), new Cell(x, y, z, state));
        faces(l, x, y, z);
        for (CgDirection d : CgDirection.values()) faces(l, x + d.stepX(), y + d.stepY(), z + d.stepZ());
    }

    // The cell's exposed faces drawn, the rest removed: a face shows where its neighbour is absent, hidden by the layers
    // shown, or see-through
    private void faces(Level l, int x, int y, int z) {
        Cell c = l.cells.get(key(x, y, z));
        if (c == null) return;
        boolean shown = y >= l.minY && y <= l.maxY;
        for (CgDirection d : CgDirection.values()) {
            int i = d.ordinal();
            Cell n = l.cells.get(key(x + d.stepX(), y + d.stepY(), z + d.stepZ()));
            boolean covered = n != null && !n.glass && n.y >= l.minY && n.y <= l.maxY && !(c.glass && n.glass);
            boolean wanted = shown && !covered && !(c.glass && n != null && n.glass);
            if (wanted && c.faces[i] == null) {
                c.faces[i] = l.scene.mesh(FACE, material(c.state, i))
                        .at(x + 0.5 + d.stepX() * 0.5, y + 0.5 + d.stepY() * 0.5, z + 0.5 + d.stepZ() * 0.5)
                        .rotation(TURN[i]).spawn();
            } else if (!wanted && c.faces[i] != null) {
                c.faces[i].remove();
                c.faces[i] = null;
            }
        }
    }

    @Override
    public CgHostLevel.Drawn draw(Object level, Object key, int width, int height, Matrix4fc view, double ox, double oy,
                                  double oz, Matrix4fc projection, float partialTick, Runnable components) {
        Level l = (Level) level;
        Target t = l.targets.computeIfAbsent(key, k -> new Target(l.scene));
        if (t.picture == null || t.picture.width() != width || t.picture.height() != height) {
            if (t.picture != null) t.picture.release();
            t.picture = CgRenderTarget.create("stand_in_level", width, height, CgRenderTarget.Format.RGBA8).clearColor(0x00000000);
        }
        t.view.camera().set(ox, oy, oz, view, projection);
        CgExternalTarget into = t.picture.beginDraw(true);
        CgStageHost.renderView(t.view, into);
        components.run();
        t.picture.drawn();
        return new CgHostLevel.Drawn(into, t.picture.texture());
    }

    /** Through unit cells from the origin (Amanatides and Woo's walk), the first visible block met within reach. */
    @Override
    @Nullable
    public CgPocketLevel.Hit pick(Object level, double ox, double oy, double oz, double dx, double dy, double dz, double reach) {
        Level l = (Level) level;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0.0) return null;
        dx /= length;
        dy /= length;
        dz /= length;
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = boundary(ox, dx), ty = boundary(oy, dy), tz = boundary(oz, dz);
        double ddx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double ddy = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double ddz = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double t = 0.0;
        CgDirection face = null;
        while (t <= reach) {
            Cell cell = l.cells.get(key(x, y, z));
            if (cell != null && face != null && y >= l.minY && y <= l.maxY) {
                List<CgAABBc> outline = new ArrayList<>(1);
                outline.add(CgAABB.of(x, y, z, x + 1, y + 1, z + 1));
                return new CgPocketLevel.Hit(new CgBlockPos(x, y, z), face, ox + dx * t, oy + dy * t, oz + dz * t,
                        cell.state, outline);
            }
            if (tx <= ty && tx <= tz) {
                t = tx;
                tx += ddx;
                x += sx;
                face = sx > 0 ? CgDirection.WEST : CgDirection.EAST;
            } else if (ty <= tz) {
                t = ty;
                ty += ddy;
                y += sy;
                face = sy > 0 ? CgDirection.DOWN : CgDirection.UP;
            } else {
                t = tz;
                tz += ddz;
                z += sz;
                face = sz > 0 ? CgDirection.NORTH : CgDirection.SOUTH;
            }
        }
        return null;
    }

    @Override
    public void visibleRange(Object level, int minY, int maxY) {
        Level l = (Level) level;
        l.minY = minY;
        l.maxY = maxY;
        for (Cell c : l.cells.values()) faces(l, c.x, c.y, c.z);
    }

    @Override
    public void tick(Object level) {
    }

    @Override
    public void forget(Object level, Object key) {
        Target t = ((Level) level).targets.remove(key);
        if (t != null && t.picture != null) t.picture.release();
    }

    @Override
    public void close(Object level) {
        Level l = (Level) level;
        for (Target t : l.targets.values()) if (t.picture != null) t.picture.release();
        l.targets.clear();
        for (Cell c : l.cells.values()) c.clear();
        l.cells.clear();
    }

    // The block's own colour, as the stand-in block pictures draw it, shaded for the face; glass let through
    private CgMaterial material(CgBlockState state, int face) {
        String block = state.block();
        return materials.computeIfAbsent(block + "#" + face, k -> {
            boolean glass = block.contains("glass");
            int colour = glass ? 0x60C0E0F0 : 0xFF000000 | (block.hashCode() * 0x9E3779B1 >>> 8) & 0x7F7F7F | 0x303030;
            float shade = SHADE[face];
            int shaded = colour & 0xFF000000 | Math.round((colour >> 16 & 0xFF) * shade) << 16
                    | Math.round((colour >> 8 & 0xFF) * shade) << 8 | Math.round((colour & 0xFF) * shade);
            CgSurfaceMaterial.Builder b = CgSurfaceMaterial.of("crystalgraphics:stand_in_block/" + block.replace(':', '/') + "/" + face)
                    .tint(shaded).shading(CgShadingModels.UNLIT);
            if (glass) b.blend(CgBlendMode.ALPHA);
            return b.build();
        });
    }

    // The distance along a unit ray to the first cell boundary on one axis
    private static double boundary(double origin, double direction) {
        if (direction == 0) return Double.POSITIVE_INFINITY;
        double cell = Math.floor(origin);
        return direction > 0 ? (cell + 1 - origin) / direction : (origin - cell) / -direction;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) y & 0xFFF;
    }
}
