package com.crystalgraphics.vfx.sparse;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxTrace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A sparse volume's tiles: a pool of {@link #TILE}-cell tiles shared by up to {@code owners} volumes, each a grid of
 * {@link #SPAN} cells a side holding tiles only where it has something, found through its page table. OpenVDB's and
 * Houdini's sparse Pyro's layout. The fluids and the grids each keep one; their kernels read it through
 * {@code fx_sparse.glsl} ({@code fx_sparse_slot_of}, {@code fx_sparse_pool_uvw}), whatever the volume.
 *
 * <pre>{@code
 * CgVfxTiles tiles = new CgVfxTiles("vfx.grid", 4);
 * tiles.pool(recording, 256);                        // render thread, whenever the pool's size changes: all empty
 * // each frame, before the kernels
 * tiles.live(aliveMask);                             // bit b for each volume alive: what each is kept
 * tiles.grow(now);                                   // kept where the GPU found something, grown on its faces
 * tiles.wantSphere(owner, x, y, z, radius, cell, now);
 * tiles.finish(now);                                 // frees what nothing asked for, sets usedLayers()
 * tiles.upload(recording);                           // TILES and the page tables that changed
 * // ... kernels binding tiles.tileRows(), tiles.pages(), and OCCUPIED = tiles.occupancy() ...
 * tiles.readOccupancy(recording);                    // what the next grow keeps, a frame or two late
 * tiles.stepped();                                   // a tile's first step is behind it
 * }</pre>
 *
 * <ul>
 *   <li>A slot's tile is fresh from {@link #want} until {@link #stepped}: its kernels read nothing of what the pool held
 *       there before.</li>
 *   <li>{@code now} is milliseconds on the owner's clock (its atlas's {@code now()}), never a frame count.</li>
 *   <li>A tile nothing asks for, and the GPU found nothing in, goes after {@link #FREE_AFTER} ms.</li>
 *   <li>Free tiles go to whoever asks, until a volume is refused one; for {@link #FREE_AFTER} ms after, only to a
 *       volume under its even share among those alive ({@link #live}). Once the pool is full, a volume under its share
 *       takes a tile of the volume furthest past its own, an empty one first, else the least recently needed, whose
 *       cells there are lost: one spreading without end never starves the rest, and one alone may take the whole pool.
 *       A volume refused a tile grows no further there: the counter {@code <name>.pool-full} and one warning say so.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgVfxTiles {

    /** Cells a tile side; tiles a page table side; cells a volume's grid side, its origin in the middle. */
    public static final int TILE = 16, PAGES = 32, SPAN = TILE * PAGES;
    /** Pool tiles a layer side: slot s is pool tile (s % LAYER, s / LAYER % LAYER, s / LAYER²). */
    public static final int LAYER = 8;
    /** A tile's side in a pool with two cells of apron round it, for filtered and cubic reads that never leave it. */
    public static final int APRON_TILE = TILE + 4;
    /** Each slot's flags in {@link #occupancy()}: something in it, then something near its -x, +x, -y, +y, -z, +z face. */
    public static final int FLAGS = 8;
    /**
     * Milliseconds a tile stays once nothing asks for it. Every {@code now} handed in is its owner's clock in milliseconds,
     * so a tile lasts as long at 60 fps as at 300.
     */
    public static final int FREE_AFTER = 500;

    private static final Logger LOG = LogManager.getLogger("CgVfxTiles");
    private static final CgFrameBufferFormat R32F = CgFrameBufferFormat.builder("vfx.tiles.r32f")
            .color(0, CgTextureType.R32F).build();

    private final String name;
    private final int owners;
    private final int tilesCounter, fullCounter;
    /** Each owner's page table: a tile's slot + 1, 0 for none; whether it changed since its upload; its lowest tile y. */
    private final int[][] pageTable;
    private final boolean[] pagesDirty;
    /** Each owner's tile rows it may hold, inclusive: x0, y0, z0, x1, y1, z1. */
    private final int[] bounds;
    private final int[] held;
    /** A coarsened owner's page table before ({@link #coarsen}); the slots it held, while it does. */
    private final int[] previous = new int[PAGES * PAGES * PAGES];
    private int[] mine = new int[0];
    /** Each slot's owner (-1 free), its tile's place in that page table, when last asked for, fresh. */
    private int[] slotOwner = new int[0], slotPage = new int[0], slotSeen = new int[0];
    private boolean[] slotFresh = new boolean[0];
    /** What the GPU found, FLAGS a slot, as the last readback delivered it. */
    private int[] occupied = new int[0];
    private int poolTiles, usedLayers, readSlots, full, share, inUse;
    /** When a volume was last refused a tile: the pool is contended until FREE_AFTER past it. */
    private int contended = Integer.MIN_VALUE / 2;
    private final ByteBuffer pageBytes = ByteBuffer.allocate(PAGES * PAGES * PAGES * 4).order(ByteOrder.nativeOrder());
    private ByteBuffer staging = ByteBuffer.allocate(0);
    @Nullable
    private CgGraphTexture pages;
    @Nullable
    private CgGraphBuffer tileRows, occupancy;
    private final CgReadback.Sink occupancySink = data -> {
        int n = Math.min(Math.min(readSlots * FLAGS, occupied.length), data.remaining() / 4);
        for (int i = 0; i < n; i++) occupied[i] = data.getInt(i * 4);
        Arrays.fill(occupied, n, occupied.length, 0);
    };

    /** A pool for up to {@code owners} volumes, its trace counters under {@code name}. Empty until {@link #pool}. */
    public CgVfxTiles(String name, int owners) {
        this.name = name;
        this.owners = owners;
        pageTable = new int[owners][PAGES * PAGES * PAGES];
        pagesDirty = new boolean[owners];
        bounds = new int[owners * 6];
        for (int b = 0; b < owners; b++) bounds(b, 0, 0, 0, PAGES - 1, PAGES - 1, PAGES - 1);
        held = new int[owners];
        tilesCounter = CgTrace.name(name + ".tiles");
        fullCounter = CgTrace.name(name + ".pool-full");
    }

    /** Remakes the pool at {@code tiles} tiles (a multiple of {@code LAYER²}): every volume empty, their tables cleared. */
    public void pool(CgRecording recording, int tiles) {
        if (pages != null) recording.release(pages);
        poolTiles = tiles;
        slotOwner = new int[tiles];
        Arrays.fill(slotOwner, -1);
        slotPage = new int[tiles];
        slotSeen = new int[tiles];
        slotFresh = new boolean[tiles];
        mine = new int[tiles];
        occupied = new int[tiles * FLAGS];
        usedLayers = 0;
        share = tiles;
        inUse = 0;
        Arrays.fill(held, 0);
        for (int b = 0; b < owners; b++) {
            Arrays.fill(pageTable[b], 0);
            pagesDirty[b] = true;
        }
        pages = CgGraphTexture.requested(name + ".pages", CgTextureDesc.volume(PAGES, PAGES, PAGES * owners, R32F));
    }

    /** A dying context took the GPU side: the next {@link #pool} makes it again. */
    public void lost() {
        pages = null;
        tileRows = occupancy = null;
        poolTiles = 0;
        usedLayers = 0;
    }

    /** Layers of the pool: its slots over {@code LAYER²}. */
    public int layers() {
        return poolTiles / (LAYER * LAYER);
    }

    public int poolTiles() {
        return poolTiles;
    }

    /** Layers up to the highest slot given out, as of {@link #finish}: what kernels dispatch over. */
    public int usedLayers() {
        return usedLayers;
    }

    /** Which volumes are alive this frame, bit b for volume b: each is kept the pool over that many. */
    public void live(int mask) {
        share = poolTiles / Math.max(1, Integer.bitCount(mask));
    }

    /**
     * A slot for volume {@code b}: a free one, else, while it is under its share, a tile of the volume furthest past its
     * own, taken back; -1 for none. Contended, one at its share takes no free slot: a volume climbing past its share on
     * a slot freed a moment ago was taken back by the next under it, its live tiles swapped out and in every second.
     */
    private int slotFor(int b, int now) {
        if (inUse < poolTiles && (held[b] < share || now - contended > FREE_AFTER)) {
            for (int s = 0; s < poolTiles; s++) if (slotOwner[s] < 0) return s;
        }
        if (held[b] >= share) return -1;
        int over = -1;
        for (int o = 0; o < owners; o++) if (o != b && held[o] > share && (over < 0 || held[o] > held[over])) over = o;
        if (over < 0) return -1;
        // An empty tile first: every live one was asked for this frame, so by age alone a live one ties with the rest.
        int taken = -1;
        for (int s = 0; s < poolTiles; s++) if (slotOwner[s] == over && (taken < 0 || before(s, taken))) taken = s;
        free(taken);
        return taken;
    }

    private boolean before(int s, int than) {
        boolean empty = occupied[s * FLAGS] == 0, thanEmpty = occupied[than * FLAGS] == 0;
        return empty != thanEmpty ? empty : slotSeen[s] < slotSeen[than];
    }

    /**
     * Owner {@code b}'s tiles outside rows {@code x0} to {@code x1}, {@code y0} to {@code y1} and {@code z0} to
     * {@code z1} (inclusive) are refused: a gas wholly under its floor, or outside its domain. Those it holds there are
     * freed once nothing keeps them.
     */
    public void bounds(int b, int x0, int y0, int z0, int x1, int y1, int z1) {
        int at = b * 6;
        bounds[at] = x0;
        bounds[at + 1] = y0;
        bounds[at + 2] = z0;
        bounds[at + 3] = x1;
        bounds[at + 4] = y1;
        bounds[at + 5] = z1;
    }

    /** Every owner's page table, {@link #PAGES} cubed texels each stacked along z: a tile's slot + 1, 0 for none. */
    @Nullable
    public CgGraphTexture pages() {
        return pages;
    }

    /** Each slot: its owner + 1 (0 free), its tile {@code x | y << 8 | z << 16}, 1 while fresh, 0; as {@code uvec4}. */
    @Nullable
    public CgGraphBuffer tileRows() {
        return tileRows;
    }

    /** {@link #FLAGS} uints a slot, which the volume's own kernel sets and {@link #readOccupancy} reads back. */
    @Nullable
    public CgGraphBuffer occupancy() {
        return occupancy;
    }

    public int tilesInUse() {
        return inUse;
    }

    /** Keeps every slot the GPU last found something in, and asks for its neighbour on each face it found something near. */
    public void grow(int now) {
        for (int s = 0; s < poolTiles; s++) {
            int b = slotOwner[s];
            if (b < 0 || occupied[s * FLAGS] == 0) continue;
            slotSeen[s] = now;
            int page = slotPage[s], tx = page % PAGES, ty = page / PAGES % PAGES, tz = page / (PAGES * PAGES);
            int at = s * FLAGS;
            int x0 = occupied[at + 1] != 0 ? -1 : 0, x1 = occupied[at + 2] != 0 ? 1 : 0;
            int y0 = occupied[at + 3] != 0 ? -1 : 0, y1 = occupied[at + 4] != 0 ? 1 : 0;
            int z0 = occupied[at + 5] != 0 ? -1 : 0, z1 = occupied[at + 6] != 0 ? 1 : 0;
            for (int dz = z0; dz <= z1; dz++) {
                for (int dy = y0; dy <= y1; dy++) {
                    for (int dx = x0; dx <= x1; dx++) {
                        if (dx != 0 || dy != 0 || dz != 0) want(b, tx + dx, ty + dy, tz + dz, now);
                    }
                }
            }
        }
    }

    /**
     * Asks for owner {@code b}'s tiles round the sphere {@code radius} blocks about {@code (x, y, z)} from its grid's
     * middle, its cells {@code cell} blocks.
     */
    public void wantSphere(int b, float x, float y, float z, float radius, float cell, int now) {
        float half = SPAN * 0.5f, r = radius + cell;
        int x0 = tile((x - r) / cell + half), x1 = tile((x + r) / cell + half);
        int y0 = tile((y - r) / cell + half), y1 = tile((y + r) / cell + half);
        int z0 = tile((z - r) / cell + half), z1 = tile((z + r) / cell + half);
        for (int tz = z0; tz <= z1; tz++) {
            for (int ty = y0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++) want(b, tx, ty, tz, now);
            }
        }
    }

    /**
     * Owner {@code b}'s tile {@code (tx, ty, tz)} kept this frame, given a slot if it has none; none past its grid or
     * floor. False when it was refused one: at its share of a full pool.
     */
    public boolean want(int b, int tx, int ty, int tz, int now) {
        int at = b * 6;
        if (tx < Math.max(0, bounds[at]) || ty < Math.max(0, bounds[at + 1]) || tz < Math.max(0, bounds[at + 2])
                || tx > Math.min(PAGES - 1, bounds[at + 3]) || ty > Math.min(PAGES - 1, bounds[at + 4])
                || tz > Math.min(PAGES - 1, bounds[at + 5])) {
            return true;
        }
        int page = tx + PAGES * (ty + PAGES * tz);
        int had = pageTable[b][page];
        if (had != 0) {
            slotSeen[had - 1] = now;
            return true;
        }
        int s = slotFor(b, now);
        if (s >= 0) {
            held[b]++;
            inUse++;
            slotOwner[s] = b;
            slotPage[s] = page;
            slotSeen[s] = now;
            slotFresh[s] = true;
            pageTable[b][page] = s + 1;
            pagesDirty[b] = true;
            return true;
        }
        contended = now;
        CgVfxTrace.count(fullCounter, 1);
        if (full++ == 0) {
            LOG.warn("[{}] the pool's {} tiles are given out: a volume at its share ({}) was refused one", name, poolTiles,
                    share);
        }
        return false;
    }

    /** Frees every tile nothing asked for in {@link #FREE_AFTER} ms, and sets {@link #usedLayers}. */
    public void finish(int now) {
        int highest = -1;
        for (int s = 0; s < poolTiles; s++) {
            if (slotOwner[s] < 0) continue;
            if (now - slotSeen[s] > FREE_AFTER) {
                free(s);
                continue;
            }
            highest = s;
        }
        usedLayers = highest < 0 ? 0 : highest / (LAYER * LAYER) + 1;
        CgVfxTrace.count(tilesCounter, tilesInUse());
    }

    /**
     * Owner {@code b}'s tiles mapped onto a grid of cells twice as wide about the same middle: tile {@code t} becomes
     * {@code t / 2 + PAGES / 4} on each axis, eight to one, each in a slot {@code b} held, none fresh, for the caller to
     * fill from the old through {@link #previous()}.
     */
    public void coarsen(int b, int now) {
        System.arraycopy(pageTable[b], 0, previous, 0, previous.length);
        int n = 0;
        for (int s = 0; s < poolTiles; s++) {
            if (slotOwner[s] != b) continue;
            mine[n++] = s;
            free(s);
        }
        int used = 0;
        for (int page = 0; page < previous.length && used < n; page++) {
            if (previous[page] == 0) continue;
            int tx = page % PAGES / 2 + PAGES / 4, ty = page / PAGES % PAGES / 2 + PAGES / 4,
                    tz = page / (PAGES * PAGES) / 2 + PAGES / 4;
            int to = tx + PAGES * (ty + PAGES * tz);
            if (pageTable[b][to] != 0) continue;
            int s = mine[used++];
            held[b]++;
            inUse++;
            slotOwner[s] = b;
            slotPage[s] = to;
            slotSeen[s] = now;
            slotFresh[s] = false;
            pageTable[b][to] = s + 1;
        }
        pagesDirty[b] = true;
    }

    /** The page table {@link #coarsen} replaced: a tile's slot + 1, 0 for none. */
    public int[] previous() {
        return previous;
    }

    /** Owner {@code b}'s tiles all given back. */
    public void freeAll(int b) {
        for (int s = 0; s < poolTiles; s++) if (slotOwner[s] == b) free(s);
        Arrays.fill(pageTable[b], 0);
        pagesDirty[b] = true;
    }

    private void free(int s) {
        int b = slotOwner[s];
        if (b < 0) return;
        pageTable[b][slotPage[s]] = 0;
        pagesDirty[b] = true;
        held[b]--;
        inUse--;
        slotOwner[s] = -1;
        slotFresh[s] = false;
        for (int f = 0; f < FLAGS; f++) occupied[s * FLAGS + f] = 0;
    }

    /**
     * Owner {@code b}'s box round the tiles it holds, from its grid's middle in blocks at {@code cell}, into
     * {@code out} (low x, y, z, high x, y, z); false when it holds none.
     */
    public boolean bounds(int b, float cell, float[] out) {
        int lx = PAGES, ly = PAGES, lz = PAGES, hx = -1, hy = -1, hz = -1;
        for (int s = 0; s < poolTiles; s++) {
            if (slotOwner[s] != b) continue;
            int page = slotPage[s], tx = page % PAGES, ty = page / PAGES % PAGES, tz = page / (PAGES * PAGES);
            lx = Math.min(lx, tx);
            ly = Math.min(ly, ty);
            lz = Math.min(lz, tz);
            hx = Math.max(hx, tx);
            hy = Math.max(hy, ty);
            hz = Math.max(hz, tz);
        }
        if (hx < 0) return false;
        float half = SPAN * 0.5f;
        out[0] = (lx * TILE - half) * cell;
        out[1] = (ly * TILE - half) * cell;
        out[2] = (lz * TILE - half) * cell;
        out[3] = ((hx + 1) * TILE - half) * cell;
        out[4] = ((hy + 1) * TILE - half) * cell;
        out[5] = ((hz + 1) * TILE - half) * cell;
        return true;
    }

    /** {@code TILES} for the used layers and every page table that changed, uploaded; {@link #occupancy} sized. */
    public void upload(CgRecording recording) {
        int slots = Math.max(1, usedLayers) * LAYER * LAYER;
        tileRows = fit(recording, tileRows, name + ".tileRows", layers() * LAYER * LAYER * 4);
        occupancy = fit(recording, occupancy, name + ".occupancy", layers() * LAYER * LAYER * FLAGS);
        if (staging.capacity() < slots * 16) staging = ByteBuffer.allocate(slots * 32).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int s = 0; s < slots; s++) {
            int b = s < poolTiles ? slotOwner[s] : -1, page = b < 0 ? 0 : slotPage[s];
            int tx = page % PAGES, ty = page / PAGES % PAGES, tz = page / (PAGES * PAGES);
            staging.putInt(s * 16, b + 1);
            staging.putInt(s * 16 + 4, tx | ty << 8 | tz << 16);
            staging.putInt(s * 16 + 8, b >= 0 && slotFresh[s] ? 1 : 0);
            staging.putInt(s * 16 + 12, 0);
        }
        staging.limit(slots * 16);
        recording.update(tileRows, 0, staging);
        for (int b = 0; b < owners; b++) {
            if (!pagesDirty[b]) continue;
            pagesDirty[b] = false;
            pageBytes.clear();
            int[] table = pageTable[b];
            for (int i = 0; i < table.length; i++) pageBytes.putFloat(i * 4, table[i]);
            pageBytes.limit(table.length * 4);
            recording.update(pages, 0, 0, 0, b * PAGES, PAGES, PAGES, PAGES, pageBytes);
        }
    }

    /** {@link #occupancy} read back for a later {@link #grow}: after the kernel that set it. */
    public void readOccupancy(CgRecording recording) {
        readSlots = usedLayers * LAYER * LAYER;
        recording.readback(occupancy, 0, (long) readSlots * FLAGS * 4, occupancySink);
    }

    /** Every slot's first step behind it: none fresh. */
    public void stepped() {
        Arrays.fill(slotFresh, false);
    }

    /** The tile row holding cell {@code cell} of a volume's grid. */
    public static int tile(float cell) {
        return (int) Math.floor(cell / TILE);
    }

    /** {@code buffer} if it holds {@code words}, else a power-of-two one that does, the old released. */
    public static CgGraphBuffer fit(CgRecording recording, @Nullable CgGraphBuffer buffer, String name, int words) {
        long bytes = Math.max(256L, words * 4L);
        if (buffer != null && buffer.size() >= bytes) return buffer;
        if (buffer != null) recording.release(buffer);
        return CgGraphBuffer.persistent(name, CgBufferDesc.of(Long.highestOneBit(bytes - 1) << 1,
                CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }
}
