package com.crystalgraphics.vfx.gas;

import com.crystalgraphics.platform.world.CgWorldQuery;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Where a smoke grenade's smoke goes, as Counter-Strike 2 fills it: half-block cells flooded from where it lands, round
 * walls, down stairs and through doors, never through a block, until a budget of cells is spent. Spreading down is
 * cheap and up dear, so it pours into a room below before it climbs. Computed once, on the server where gameplay reads
 * it, and sent to every client as bytes, so all of them draw the same cells and agree on what it hides
 * ({@link CgVfxSmoke}).
 *
 * <pre>{@code
 * CgVfxSmokeFill fill = CgVfxSmokeFill.flood(CgVfxSmokeFill.blocks(world.blocks(), Float.NaN), x, y, z, 2600, 7f);
 * byte[] wire = fill.encode();                       // into the cue
 * CgVfxSmokeFill same = CgVfxSmokeFill.decode(wire); // on each client
 * int cost = fill.cost(cx, cy, cz);                  // how far the fill spread to reach a cell; -1 outside it
 * }</pre>
 *
 * <ul>
 *   <li>A cell is {@link #CELL} blocks a side, in absolute cells: {@code floor(x / CELL)}, a block's eighth.</li>
 *   <li>A cell is solid where a block's collision box holds its middle; an unloaded block is solid, so smoke never
 *       fills what the server cannot see.</li>
 *   <li>Cells lie within {@link #MOST_OFFSET} of where it landed, and its costs within {@link #MOST_COST}: reach is
 *       bounded by both, whatever the budget.</li>
 * </ul>
 */
public final class CgVfxSmokeFill {

    /** A cell's side in blocks: CS2's 20 units, half a metre. */
    public static final float CELL = 0.5f;
    /** What a straight step to a neighbour costs: across, down, up. A diagonal step costs its length so weighted. */
    public static final int ACROSS = 4, DOWN = 2, UP = 6;
    /**
     * Its 18 steps: the six faces and the twelve edges, each {@code dx, dy, dz, cost}. The cost is the step's length with
     * its rise weighted by {@link #UP} or {@link #DOWN} (a chamfer distance, Borgefors 1986), so in the open it spreads
     * as a squat dome; six faces alone made a diamond pyramid.
     */
    private static final int[] STEPS = steps();
    /** The dearest cell a fill reaches, and the farthest from its first cell along an axis, in cells. */
    public static final int MOST_COST = 254, MOST_OFFSET = 127;

    /** Whether cell {@code (x, y, z)}, in absolute cells, is solid. */
    @FunctionalInterface
    public interface Solids {
        boolean solid(int x, int y, int z);
    }

    private final int originX, originY, originZ;
    /** Four bytes a cell, cheapest first: its offset from the origin cell, then its cost. */
    private final byte[] cells;
    private final int count, mostCost;
    /** Open addressing: a cell's packed offset + 1, and its cost. */
    private final int[] keys;
    private final short[] costs;

    private CgVfxSmokeFill(int originX, int originY, int originZ, byte[] cells, int count) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.cells = cells;
        this.count = count;
        int size = Integer.highestOneBit(Math.max(4, count * 2)) << 1;
        keys = new int[size];
        costs = new short[size];
        int most = 0;
        for (int i = 0; i < count; i++) {
            int cost = cells[i * 4 + 3] & 0xFF;
            most = Math.max(most, cost);
            put(pack(cells[i * 4], cells[i * 4 + 1], cells[i * 4 + 2]), cost);
        }
        mostCost = most;
    }

    /**
     * The cells a smoke landing at {@code (x, y, z)} fills: cheapest first, round {@code solids}, at most {@code budget}
     * of them, none costing more than {@code reach} blocks of spreading across would. A landing point inside a solid cell
     * starts from the first open one above it, four at most; none open, it fills nothing.
     */
    public static CgVfxSmokeFill flood(Solids solids, double x, double y, double z, int budget, float reach) {
        int ox = (int) Math.floor(x / CELL), oy = (int) Math.floor(y / CELL), oz = (int) Math.floor(z / CELL);
        for (int up = 0; up < 4 && solids.solid(ox, oy, oz); up++) oy++;
        if (solids.solid(ox, oy, oz) || budget <= 0) return new CgVfxSmokeFill(ox, oy, oz, new byte[0], 0);
        int most = Math.min(MOST_COST, Math.max(1, Math.round(reach / CELL * ACROSS)));
        // Dijkstra over small costs: a bucket of packed offsets per cost.
        int[][] buckets = new int[most + 1][];
        int[] sizes = new int[most + 1];
        int tableSize = Integer.highestOneBit(Math.max(16, budget * 8)) << 1;
        int[] seenKeys = new int[tableSize];
        short[] best = new short[tableSize];
        byte[] out = new byte[budget * 4];
        int n = 0;
        push(buckets, sizes, 0, pack(0, 0, 0));
        relax(seenKeys, best, pack(0, 0, 0), 0);
        for (int cost = 0; cost <= most && n < budget; cost++) {
            for (int k = 0; k < sizes[cost] && n < budget; k++) {
                int key = buckets[cost][k];
                if (lookup(seenKeys, best, key) != cost) continue;   // settled cheaper already
                int dx = unpack(key, 16), dy = unpack(key, 8), dz = unpack(key, 0);
                relax(seenKeys, best, key, SETTLED);
                out[n * 4] = (byte) dx;
                out[n * 4 + 1] = (byte) dy;
                out[n * 4 + 2] = (byte) dz;
                out[n * 4 + 3] = (byte) cost;
                n++;
                for (int d = 0; d < STEPS.length; d += 4) {
                    int sx = STEPS[d], sy = STEPS[d + 1], sz = STEPS[d + 2];
                    int nx = dx + sx, ny = dy + sy, nz = dz + sz;
                    if (Math.abs(nx) > MOST_OFFSET || Math.abs(ny) > MOST_OFFSET || Math.abs(nz) > MOST_OFFSET) continue;
                    int next = cost + STEPS[d + 3];
                    if (next > most) continue;
                    // An edge step never cuts a corner: both cells it passes beside are open.
                    if (Math.abs(sx) + Math.abs(sy) + Math.abs(sz) == 2
                            && (sx != 0 && solids.solid(ox + dx + sx, oy + dy, oz + dz)
                                || sy != 0 && solids.solid(ox + dx, oy + dy + sy, oz + dz)
                                || sz != 0 && solids.solid(ox + dx, oy + dy, oz + dz + sz))) continue;
                    int nk = pack(nx, ny, nz);
                    int known = lookup(seenKeys, best, nk);
                    if (known != -1 && known <= next || known == SETTLED) continue;
                    if (solids.solid(ox + nx, oy + ny, oz + nz)) {
                        relax(seenKeys, best, nk, SETTLED);
                        continue;
                    }
                    relax(seenKeys, best, nk, next);
                    push(buckets, sizes, next, nk);
                }
            }
        }
        return new CgVfxSmokeFill(ox, oy, oz, Arrays.copyOf(out, n * 4), n);
    }

    /**
     * {@code world}'s blocks as cells, and below {@code floorY} solid too, NaN for no floor: what a host with no world
     * (the harness) stands its smoke on. Reads each block once.
     */
    public static Solids blocks(CgWorldQuery world, float floorY) {
        boolean live = world.levelEpoch() != 0;
        int floorCell = Float.isNaN(floorY) ? Integer.MIN_VALUE : (int) Math.floor(floorY / CELL);
        return new Solids() {
            private final float[] boxes = new float[6 * 16];
            private final HashMap<Long, Byte> masks = new HashMap<>();

            @Override
            public boolean solid(int x, int y, int z) {
                if (y < floorCell) return true;
                if (!live) return false;
                int bx = Math.floorDiv(x, 2), by = Math.floorDiv(y, 2), bz = Math.floorDiv(z, 2);
                long block = ((long) bx & 0x3FFFFFFL) << 38 | ((long) by & 0xFFFL) << 26 | (long) bz & 0x3FFFFFFL;
                Byte mask = masks.get(block);
                if (mask == null) {
                    mask = mask(bx, by, bz);
                    masks.put(block, mask);
                }
                int bit = (x & 1) | (y & 1) << 1 | (z & 1) << 2;
                return (mask & (1 << bit)) != 0;
            }

            /** A bit per cell of the block, set where a collision box holds the cell's middle; all set unloaded. */
            private byte mask(int bx, int by, int bz) {
                if (!world.loaded(bx, bz)) return (byte) 0xFF;
                int n = Math.min(world.collisionBoxes(bx, by, bz, boxes), boxes.length / 6);
                int mask = 0;
                for (int bit = 0; bit < 8; bit++) {
                    float cx = (bit & 1) == 0 ? 0.25f : 0.75f, cy = (bit & 2) == 0 ? 0.25f : 0.75f;
                    float cz = (bit & 4) == 0 ? 0.25f : 0.75f;
                    for (int b = 0; b < n; b++) {
                        int at = b * 6;
                        if (cx >= boxes[at] && cx <= boxes[at + 3] && cy >= boxes[at + 1] && cy <= boxes[at + 4]
                                && cz >= boxes[at + 2] && cz <= boxes[at + 5]) {
                            mask |= 1 << bit;
                            break;
                        }
                    }
                }
                return (byte) mask;
            }
        };
    }

    /** Its cells as bytes for the wire: its first cell, its count, then four bytes a cell. */
    public byte[] encode() {
        ByteBuffer out = ByteBuffer.allocate(16 + count * 4);
        out.putInt(originX).putInt(originY).putInt(originZ).putInt(count).put(cells, 0, count * 4);
        return out.array();
    }

    /** What {@link #encode} wrote; an empty fill for bytes that are not one. */
    public static CgVfxSmokeFill decode(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return new CgVfxSmokeFill(0, 0, 0, new byte[0], 0);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int x = in.getInt(), y = in.getInt(), z = in.getInt(), n = in.getInt();
        if (n < 0 || bytes.length != 16 + n * 4) return new CgVfxSmokeFill(x, y, z, new byte[0], 0);
        return new CgVfxSmokeFill(x, y, z, Arrays.copyOfRange(bytes, 16, bytes.length), n);
    }

    /** The cost the fill spread at to reach absolute cell {@code (x, y, z)}; -1 where it did not. */
    public int cost(int x, int y, int z) {
        int dx = x - originX, dy = y - originY, dz = z - originZ;
        if (Math.abs(dx) > MOST_OFFSET || Math.abs(dy) > MOST_OFFSET || Math.abs(dz) > MOST_OFFSET) return -1;
        return get(pack(dx, dy, dz));
    }

    /** How many cells it fills. */
    public int count() {
        return count;
    }

    /** Its dearest cell's cost: how far it spreads once whole. */
    public int mostCost() {
        return mostCost;
    }

    /** The absolute cell it was flooded from. */
    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    /** Cell {@code i}'s offset from {@link #originX()}..., cheapest first, and its cost. */
    public int offsetX(int i) {
        return cells[i * 4];
    }

    public int offsetY(int i) {
        return cells[i * 4 + 1];
    }

    public int offsetZ(int i) {
        return cells[i * 4 + 2];
    }

    public int cost(int i) {
        return cells[i * 4 + 3] & 0xFF;
    }

    /**
     * Its cells into a cube {@code side} cells a side centred on its first cell, a byte each: cost + 1, 0 for none. What
     * the GPU reads; a cell past the cube is left out.
     */
    void window(byte[] out, int side) {
        Arrays.fill(out, 0, side * side * side, (byte) 0);
        int half = side / 2;
        for (int i = 0; i < count; i++) {
            int x = offsetX(i) + half, y = offsetY(i) + half, z = offsetZ(i) + half;
            if (x < 0 || y < 0 || z < 0 || x >= side || y >= side || z >= side) continue;
            out[x + side * (y + side * z)] = (byte) Math.min(255, cost(i) + 1);
        }
    }

    private static int[] steps() {
        int[] out = new int[18 * 4];
        int n = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    int axes = Math.abs(x) + Math.abs(y) + Math.abs(z);
                    if (axes == 0 || axes == 3) continue;
                    double across = Math.sqrt(x * x + z * z) * ACROSS, rise = y > 0 ? UP : y < 0 ? DOWN : 0;
                    out[n++] = x;
                    out[n++] = y;
                    out[n++] = z;
                    out[n++] = (int) Math.round(Math.sqrt(across * across + rise * rise));
                }
            }
        }
        return out;
    }

    // A flood's table: a packed offset + 1, and its best cost so far, or SETTLED once a cell is taken or solid.
    private static final short SETTLED = Short.MAX_VALUE;

    private static int pack(int dx, int dy, int dz) {
        return (dx + 128) << 16 | (dy + 128) << 8 | (dz + 128);
    }

    private static int unpack(int key, int shift) {
        return ((key >> shift) & 0xFF) - 128;
    }

    private static void push(int[][] buckets, int[] sizes, int cost, int key) {
        int[] bucket = buckets[cost];
        if (bucket == null) bucket = buckets[cost] = new int[16];
        else if (sizes[cost] == bucket.length) bucket = buckets[cost] = Arrays.copyOf(bucket, bucket.length * 2);
        bucket[sizes[cost]++] = key;
    }

    /** The best cost known for {@code key}, SETTLED once taken; -1 never seen. */
    private static int lookup(int[] keys, short[] values, int key) {
        int mask = keys.length - 1;
        for (int at = mix(key) & mask; ; at = (at + 1) & mask) {
            if (keys[at] == 0) return -1;
            if (keys[at] == key + 1) return values[at];
        }
    }

    private static void relax(int[] keys, short[] values, int key, int cost) {
        int mask = keys.length - 1;
        for (int at = mix(key) & mask; ; at = (at + 1) & mask) {
            if (keys[at] == 0 || keys[at] == key + 1) {
                keys[at] = key + 1;
                values[at] = (short) cost;
                return;
            }
        }
    }

    private static int mix(int key) {
        int h = key * 0x9E3779B1;
        return h ^ (h >>> 15);
    }

    private void put(int key, int cost) {
        relax(keys, costs, key, cost);
    }

    private int get(int key) {
        int found = lookup(keys, costs, key);
        return found == SETTLED ? -1 : found;
    }
}
