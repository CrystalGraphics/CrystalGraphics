package com.crystalgraphics.vfx.world;

import com.crystalgraphics.platform.service.CgWorldQuery;

import java.nio.ByteBuffer;

/**
 * One section of the host world packed as {@link CgVfxVoxelWindow}'s volume holds it: four bytes a block, x fastest,
 * then y, then z. GL-free, so a test packs a world of its own.
 *
 * <pre>{@code
 * ByteBuffer section = ByteBuffer.allocateDirect(CgVfxVoxels.SECTION_BYTES);
 * if (CgVfxVoxels.pack(world, 32, 64, -16, section, new float[CgVfxVoxels.BOXES * 6])) upload(section);
 * int octants = CgVfxVoxels.octants(section.get(at)), sky = CgVfxVoxels.light(section.get(at + 2));
 * }</pre>
 *
 * <table>
 *   <tr><th>Byte</th><th>Holds</th></tr>
 *   <tr><td>0</td><td>which octants are solid: bit {@code ox | oy << 1 | oz << 2}, an octant solid when a collision box
 *       holds its centre</td></tr>
 *   <tr><td>1, 2</td><td>block and sky light, 0 to 15, times 17</td></tr>
 *   <tr><td>3</td><td>the fluid: its kind ({@code CgWorldQuery.FLUID_}) times 64, plus its height within the block in
 *       63rds</td></tr>
 * </table>
 */
public final class CgVfxVoxels {

    /** A section's blocks on a side. */
    public static final int SECTION = 16;
    public static final int SECTION_BYTES = SECTION * SECTION * SECTION * 4;
    /** The collision boxes a block's octants are taken from: more is a shape no block has. */
    public static final int BOXES = 8;

    private CgVfxVoxels() {
    }

    /**
     * Packs the section whose lowest block is {@code (x0, y0, z0)} into {@code out} from its position on, and answers
     * true; false, with {@code out} unspecified, when a column of it is not loaded. {@code boxes} holds
     * {@code BOXES * 6} floats. Render thread, as the query is.
     */
    public static boolean pack(CgWorldQuery world, int x0, int y0, int z0, ByteBuffer out, float[] boxes) {
        for (int z = 0; z < SECTION; z++) {
            for (int x = 0; x < SECTION; x++) if (!world.loaded(x0 + x, z0 + z)) return false;
        }
        int minY = world.minY(), maxY = world.maxY(), at = out.position();
        for (int z = 0; z < SECTION; z++) {
            for (int y = 0; y < SECTION; y++) {
                int by = y0 + y;
                for (int x = 0; x < SECTION; x++, at += 4) {
                    int bx = x0 + x, bz = z0 + z;
                    if (by < minY || by >= maxY) {
                        out.put(at, (byte) 0).put(at + 1, (byte) 0).put(at + 2, (byte) (by >= maxY ? 255 : 0))
                                .put(at + 3, (byte) 0);
                        continue;
                    }
                    int light = world.light(bx, by, bz);
                    out.put(at, (byte) octants(world, bx, by, bz, boxes))
                            .put(at + 1, (byte) (CgWorldQuery.blockLight(light) * 17))
                            .put(at + 2, (byte) (CgWorldQuery.skyLight(light) * 17))
                            .put(at + 3, (byte) fluid(world, bx, by, bz));
                }
            }
        }
        return true;
    }

    /** The block's solid octants: bit {@code ox | oy << 1 | oz << 2} set when a collision box holds that octant's centre. */
    public static int octants(CgWorldQuery world, int x, int y, int z, float[] boxes) {
        int n = Math.min(world.collisionBoxes(x, y, z, boxes), BOXES);
        if (n == 0) return 0;
        int bits = 0;
        for (int o = 0; o < 8; o++) {
            float cx = (o & 1) == 0 ? 0.25f : 0.75f, cy = (o & 2) == 0 ? 0.25f : 0.75f, cz = (o & 4) == 0 ? 0.25f : 0.75f;
            for (int b = 0; b < n; b++) {
                int i = b * 6;
                if (cx >= boxes[i] && cx <= boxes[i + 3] && cy >= boxes[i + 1] && cy <= boxes[i + 4]
                        && cz >= boxes[i + 2] && cz <= boxes[i + 5]) {
                    bits |= 1 << o;
                    break;
                }
            }
        }
        return bits;
    }

    private static int fluid(CgWorldQuery world, int x, int y, int z) {
        int kind = world.fluidKind(x, y, z);
        if (kind == CgWorldQuery.FLUID_NONE) return 0;
        float height = world.fluidHeight(x, y, z);
        int h = Float.isNaN(height) ? 0 : Math.round(Math.max(0f, Math.min(1f, height)) * 63f);
        return kind << 6 | h;
    }

    public static int octants(byte b) {
        return b & 0xFF;
    }

    public static int light(byte b) {
        return (b & 0xFF) / 17;
    }

    public static int fluidKind(byte b) {
        return (b & 0xFF) >> 6;
    }

    public static float fluidHeight(byte b) {
        return (b & 63) / 63f;
    }
}
