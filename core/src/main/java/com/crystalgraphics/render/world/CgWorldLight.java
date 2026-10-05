package com.crystalgraphics.render.world;

import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;

/**
 * The world's light at a point, as {@code CG_LIGHTMAP} takes it: block and sky light, 0 to 15 each, read from
 * {@link CgWorldQuery} once a frame per block. What a world draw is lit by unless it says otherwise, and what a
 * particle record carries.
 *
 * <pre>{@code
 * int light = CgWorldLight.at(x, y, z);
 * float block = CgWorldLight.block(light), sky = CgWorldLight.sky(light);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread: the thread that owns the level, never a frame builder.</li>
 *   <li>With no level (the harness, a title screen) every point is {@link #FULL}.</li>
 * </ul>
 */
public final class CgWorldLight {

    /** Full block and sky light: lit as if nothing shaded it. */
    public static final int FULL = 15 | 15 << 4;

    private static final int CAPACITY = 1 << 12, MASK = CAPACITY - 1;
    private static final long EMPTY = Long.MIN_VALUE;
    // Open addressing by packed block position, cleared each frame; past half full a frame reads uncached.
    private static final long[] KEYS = new long[CAPACITY];
    private static final int[] VALUES = new int[CAPACITY];
    private static int size;
    private static long frame = -1;
    /** Reads the host answered, and those of them past the cache's half: a frame touching more blocks than it holds. */
    private static final int QUERIES = CgTrace.name("world.light.queries"), UNCACHED = CgTrace.name("world.light.uncached");

    static {
        Arrays.fill(KEYS, EMPTY);
    }

    private CgWorldLight() {
    }

    /** The light at absolute {@code (x, y, z)}, {@code block | sky << 4}. */
    public static int at(double x, double y, double z) {
        CgWorldQuery world = CgPlatform.get(CgWorldQuery.SERVICE);
        if (world.levelEpoch() == 0) return FULL;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        long now = CgFrameRing.frame();
        if (now != frame) {
            if (size > 0) Arrays.fill(KEYS, EMPTY);
            size = 0;
            frame = now;
        }
        long key = ((long) bx & 0x3FFFFFF) << 38 | ((long) bz & 0x3FFFFFF) << 12 | (by & 0xFFF);
        int slot = (int) (key ^ key >>> 29 ^ key >>> 17) & MASK;
        while (KEYS[slot] != EMPTY) {
            if (KEYS[slot] == key) return VALUES[slot];
            slot = slot + 1 & MASK;
        }
        int light = world.light(bx, by, bz);
        CgTrace.add(CgChannels.WORLD, QUERIES, 1);
        if (size < CAPACITY / 2) {
            KEYS[slot] = key;
            VALUES[slot] = light;
            size++;
        } else {
            CgTrace.add(CgChannels.WORLD, UNCACHED, 1);
        }
        return light;
    }

    public static float block(int light) {
        return light & 0xF;
    }

    public static float sky(int light) {
        return light >> 4 & 0xF;
    }
}
