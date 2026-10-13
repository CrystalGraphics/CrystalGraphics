package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.bounds.CgAABBc;

/**
 * A version's blocks inside a box, answering what collision asks as a snapshot does: boxes, their top and bottom,
 * fluid, loaded, the border, the level's height and epoch; the rest as {@link CgWorldQuery#NONE}. Immutable.
 *
 * <ul>
 *   <li>Outside its box there is nothing: no boxes, no fluid, and loaded, so sight passes.</li>
 *   <li>A column inside it that the version lacks, or holds unloaded, is unloaded here.</li>
 * </ul>
 */
final class MirrorBlocks implements CgWorldQuery {

    private final CgMirrorVersion v;
    private final MirrorPalette.View p;
    private final int x0, y0, z0, x1, y1, z1, cx0, cz0, cw, cd;
    private final CgMirrorVersion.ColumnData[] grid;
    private final boolean[] loaded;

    MirrorBlocks(CgMirrorVersion v, CgAABBc bounds) {
        this.v = v;
        this.p = v.palette;
        x0 = CgMirrorVersion.floor(bounds.minX());
        z0 = CgMirrorVersion.floor(bounds.minZ());
        y0 = Math.max(v.minY, CgMirrorVersion.floor(bounds.minY()));
        x1 = CgMirrorVersion.floor(bounds.maxX());
        z1 = CgMirrorVersion.floor(bounds.maxZ());
        y1 = Math.min(v.maxY - 1, CgMirrorVersion.floor(bounds.maxY()));
        cx0 = x0 >> 4;
        cz0 = z0 >> 4;
        cw = (x1 >> 4) - cx0 + 1;
        cd = (z1 >> 4) - cz0 + 1;
        grid = new CgMirrorVersion.ColumnData[cw * cd];
        loaded = new boolean[cw * cd];
        int s0 = v.sectionOf(y0), s1 = v.sectionOf(y1);
        for (int i = 0; i < cw; i++) {
            for (int k = 0; k < cd; k++) {
                CgMirrorVersion.ColumnData c = v.columns.get(CgLevelMirror.key(cx0 + i, cz0 + k));
                grid[i * cd + k] = c;
                loaded[i * cd + k] = c != null && !c.unloaded && c.covers(s0, s1);
            }
        }
    }

    /** The palette entry of a block, or -1 outside the box or in a column not loaded here. */
    private int entry(int x, int y, int z) {
        if (x < x0 || x > x1 || z < z0 || z > z1 || y < y0 || y > y1) return -1;
        return at(x, y, z);
    }

    /** The entry at a block anywhere the version holds its column, the box aside; -1 elsewhere. */
    private int at(int x, int y, int z) {
        int i = (x >> 4) - cx0, k = (z >> 4) - cz0;
        if (i < 0 || k < 0 || i >= cw || k >= cd || !loaded[i * cd + k]) return -1;
        int s = (y >> 4) - v.minSection;
        if (s < 0 || s >= v.sections) return -1;
        return grid[i * cd + k].entry(s, (y & 15) << 8 | (z & 15) << 4 | (x & 15));
    }

    @Override
    public float collisionTop(int x, int y, int z) {
        int e = entry(x, y, z);
        return e < 0 ? Float.NaN : p.top[e];
    }

    @Override
    public float collisionBottom(int x, int y, int z) {
        int e = entry(x, y, z);
        return e < 0 ? Float.NaN : p.bottom[e];
    }

    @Override
    public int collisionBoxes(int x, int y, int z, float[] out) {
        int e = entry(x, y, z);
        if (e < 0) return 0;
        int from = p.start[e], n = (p.start[e + 1] - from) / 6;
        System.arraycopy(p.boxes, from, out, 0, Math.min(n, out.length / 6) * 6);
        return n;
    }

    @Override
    public float fluidHeight(int x, int y, int z) {
        int e = entry(x, y, z);
        if (e < 0 || p.fluidKind[e] == FLUID_NONE) return Float.NaN;
        if (p.followsAbove[e]) {
            int above = at(x, y + 1, z);
            if (above >= 0 && p.fluidKind[above] == p.fluidKind[e]) return 1f;
        }
        return p.fluidHeight[e];
    }

    @Override
    public int fluidKind(int x, int y, int z) {
        int e = entry(x, y, z);
        return e < 0 ? FLUID_NONE : p.fluidKind[e];
    }

    @Override
    public boolean loaded(int x, int z) {
        if (x < x0 || x > x1 || z < z0 || z > z1) return true;
        return loaded[((x >> 4) - cx0) * cd + (z >> 4) - cz0];
    }

    @Override
    public boolean border(double[] out) {
        if (v.border == null) return false;
        System.arraycopy(v.border, 0, out, 0, 4);
        return true;
    }

    @Override public int minY() { return v.minY; }
    @Override public int maxY() { return v.maxY; }
    @Override public int seaLevel() { return v.seaLevel; }
    @Override public int levelEpoch() { return v.levelEpoch; }

    @Override public int surface(int x, int y, int z) { return NONE.surface(x, y, z); }
    @Override public float hardness(int x, int y, int z) { return NONE.hardness(x, y, z); }
    @Override public int lightEmission(int x, int y, int z) { return NONE.lightEmission(x, y, z); }
    @Override public int tint(int x, int y, int z) { return NONE.tint(x, y, z); }
    @Override public int biomeColor(int x, int y, int z, int kind) { return NONE.biomeColor(x, y, z, kind); }
    @Override public int precipitation(int x, int y, int z) { return NONE.precipitation(x, y, z); }
    @Override public int surfaceY(int x, int z, int kind) { return NONE.surfaceY(x, z, kind); }
    @Override public boolean spriteRect(int x, int y, int z, float[] out) { return NONE.spriteRect(x, y, z, out); }
    @Override public int light(int x, int y, int z) { return NONE.light(x, y, z); }
    @Override public int mapColor(int x, int y, int z) { return NONE.mapColor(x, y, z); }
}
