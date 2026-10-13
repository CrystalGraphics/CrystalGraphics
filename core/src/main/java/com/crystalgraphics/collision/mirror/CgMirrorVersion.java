package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * A level mirror's world at the end of one server tick: every mirrored section, the entities and the hittables as they
 * stood then. Immutable; any number of threads read it at once. {@code CgCollisionWorld} turns one into the frozen world
 * a watch or a snapshot hands out: a mod never reads it directly.
 *
 * <ul>
 *   <li>{@link #blocks} is a view, made in time proportional to the columns it covers, never a copy.</li>
 *   <li>A column inside the bounds that is not yet mirrored, or not loaded, reads as unloaded.</li>
 * </ul>
 */
public final class CgMirrorVersion {

    final long epoch;
    /** By chunk key; immutable once published. */
    final Map<Long, ColumnData> columns;
    final MirrorPalette.View palette;
    final int minSection, sections;
    final CgEntityQuery entities;
    @Nullable final Object frozen;
    @Nullable final double[] border;
    final int minY, maxY, seaLevel, levelEpoch;

    CgMirrorVersion(long epoch, Map<Long, ColumnData> columns, MirrorPalette.View palette, int minSection, int sections,
                    CgEntityQuery entities, @Nullable Object frozen, @Nullable double[] border, int minY, int maxY,
                    int seaLevel, int levelEpoch) {
        this.epoch = epoch;
        this.columns = columns;
        this.palette = palette;
        this.minSection = minSection;
        this.sections = sections;
        this.entities = entities;
        this.frozen = frozen;
        this.border = border;
        this.minY = minY;
        this.maxY = maxY;
        this.seaLevel = seaLevel;
        this.levelEpoch = levelEpoch;
    }

    /** The server tick it shows, counted from the first publish of this process. */
    public long epoch() {
        return epoch;
    }

    /** Its blocks inside {@code bounds}; outside them, nothing and loaded, as a snapshot reads. */
    public CgWorldQuery blocks(CgAABBc bounds) {
        return new MirrorBlocks(this, bounds);
    }

    /** Its entities: those touching any watched area of the level, and the wholes and mounts they belong to. */
    public CgEntityQuery entities() {
        return entities;
    }

    /** What the level's world froze with it (its hittable hitboxes); the creator's own type. */
    @Nullable
    public Object frozen() {
        return frozen;
    }

    /** Whether every column inside {@code bounds} is either mirrored through its height there or known unloaded. */
    public boolean complete(CgAABBc bounds) {
        int s0 = sectionOf(floor(bounds.minY())), s1 = sectionOf(floor(bounds.maxY()));
        for (int cx = floor(bounds.minX()) >> 4; cx <= floor(bounds.maxX()) >> 4; cx++) {
            for (int cz = floor(bounds.minZ()) >> 4; cz <= floor(bounds.maxZ()) >> 4; cz++) {
                ColumnData c = columns.get(CgLevelMirror.key(cx, cz));
                if (c == null) return false;
                if (!c.unloaded && !c.covers(s0, s1)) return false;
            }
        }
        return true;
    }

    /** The section index of block {@code y} in a column, clamped to the column. */
    int sectionOf(int y) {
        return Math.max(0, Math.min(sections - 1, (y >> 4) - minSection));
    }

    static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** One mirrored column as a version holds it. */
    static final class ColumnData {
        /** Per section: its cells, or null for a {@link #uniform} one. */
        final char[][] cells;
        final char[] uniform;
        /** Per section: mirrored and ready. */
        final boolean[] present;
        final boolean unloaded;

        ColumnData(int sections, boolean unloaded) {
            cells = new char[sections][];
            uniform = new char[sections];
            present = new boolean[sections];
            this.unloaded = unloaded;
        }

        boolean covers(int s0, int s1) {
            for (int s = s0; s <= s1; s++) {
                if (!present[s]) return false;
            }
            return true;
        }

        /** The entry at {@code cell} of section {@code s}, or -1 when that section is not present. */
        int entry(int s, int cell) {
            if (!present[s]) return -1;
            char[] c = cells[s];
            return c == null ? uniform[s] : c[cell];
        }
    }
}
