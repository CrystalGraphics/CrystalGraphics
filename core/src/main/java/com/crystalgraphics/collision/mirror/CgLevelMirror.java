package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.collision.entity.CgEntitySnapshot;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.job.reply.CgPendingReply;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One level's mirror: the asked-for parts of its world, kept at the end of every server tick for any number of
 * threads to read. {@code CgCollisionWorld}'s watches and snapshots read it; a mod never reaches it directly.
 *
 * <pre>{@code
 * CgLevelMirror mirror = CgLevelMirror.of(level, blocks, entities, freezer);   // null: this level is not mirrored
 * CgLevelMirror.Watch watch = mirror.watch(bounds);                            // the level's thread
 * CgMirrorVersion now = watch.version();                                       // any thread, any time
 * }</pre>
 *
 * <ul>
 *   <li><b>The level's thread does a fixed amount per write and per tick.</b> A write appends to its section's log
 *       ({@link CgMirrorColumn#write}); the publish at the tick's end takes first copies within a time budget,
 *       resolves the few shapes that depend on where they stand, and reads the entities as rows. Everything that grows
 *       with the area or the readers (replaying, describing, building tables, the version) is the worker's.</li>
 *   <li><b>A version is exactly the world at the end of one tick</b>: a section's version is its first copy with its
 *       logged writes up to that tick replayed in order, and every level's version for a tick is that tick's.</li>
 *   <li>A section is mirrored while a watch covers it, and for {@code crystalgraphics.mirror.idleTicks} after a
 *       snapshot last asked for it.</li>
 * </ul>
 */
public final class CgLevelMirror {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static final CgVar.Int BASE_BUDGET = CgVar.integer("crystalgraphics.mirror.baseBudget", 250, 0, 1_000_000,
            "Microseconds of each server tick a level mirror may spend copying sections it starts mirroring");
    static final CgVar.Int IDLE_TICKS = CgVar.integer("crystalgraphics.mirror.idleTicks", 200, 1, 72_000,
            "Server ticks a level mirror keeps an area a snapshot asked for once nothing asks again");
    static final CgVar.Int MEMORY_MB = CgVar.integer("crystalgraphics.mirror.memory", 64, 1, 65_536,
            "Megabytes of sections the level mirrors may hold; past it, snapshots' idle areas go first");
    static final CgVar.Bool VERIFY = CgVar.bool("crystalgraphics.mirror.verify", false, CgVar.DEV,
            "Compares every level mirror version against a copy taken at its tick, logging any difference: test servers only");
    static final CgVar.Bool STATS = CgVar.bool("crystalgraphics.mirror.stats", false, CgVar.DEV,
            "Logs each level mirror's cost every 100 server ticks: level-thread time by phase (average and worst), worker time, "
                    + "sections, first copies, resolved cells, entities");

    /** A section's cells, for the memory budget. */
    private static final int SECTION_BYTES = 4096 * 2 + 256;
    /** Ticks in which every mirrored column is audited once against its chunk: about a minute. */
    private static final int AUDIT_TICKS = 1200;
    /** Columns a publish looks for a missing chunk in, at most. */
    private static final int LOOKS_PER_PUBLISH = 64;

    private static final Map<Object, CgLevelMirror> MIRRORS = new WeakHashMap<>();
    private static boolean hooked;
    private static long epoch;
    static final AtomicLong VERIFY_DIFFERENCES = new AtomicLong();

    /** What a level's world freezes with each version: its hittable hitboxes inside the watched areas. */
    public interface Freezer {
        @Nullable
        Object freeze(CgAABBc within);
    }

    final CgMirrorHost host;
    private final WeakReference<Object> level;
    private final CgWorldQuery world;
    private final CgEntityQuery entities;
    private final Freezer freezer;
    final int minSection, sectionCount;
    final boolean positionalOnly;

    // ---- the level thread's
    /** Bumped at each publish: a section's first write after it marks the section dirty again. */
    int generation;
    private final HashMap<Long, CgMirrorColumn> columns = new HashMap<>();
    private final List<Watch> watches = new ArrayList<>();
    private final HashMap<BoxKey, Watch> asked = new HashMap<>();
    private final ArrayDeque<MirrorSection> waiting = new ArrayDeque<>();
    private MirrorSection[] dirty = new MirrorSection[16];
    private int dirtyCount;
    private MirrorSection[] positionalSections = new MirrorSection[16];
    private int[] positionalCells = new int[16];
    private int positionalCount;
    private final List<MirrorSection> dynamicSections = new ArrayList<>();
    private final List<MirrorSection> removedSections = new ArrayList<>();
    private final List<Long> unloadedColumns = new ArrayList<>(), droppedColumns = new ArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final List<Watch> readyWaiting = new ArrayList<>();
    private final List<CgAABBc> regions = new ArrayList<>();
    private boolean regionsStale;
    /** Columns in audit order, from {@link #auditAt}; rebuilt when the columns change. */
    private final List<CgMirrorColumn> auditOrder = new ArrayList<>();
    private boolean auditStale;
    private int auditAt;
    private int lastEntities;
    private double regionsMinY, regionsMaxY;
    private int wantedSections;
    private final CgMirrorShape scratch = new CgMirrorShape();
    private final double[] borderScratch = new double[4];

    // ---- the worker's
    private final MirrorPalette palette;
    private final HashMap<Long, MirrorSection[]> workerColumns = new HashMap<>();
    private final HashMap<Long, CgMirrorVersion.ColumnData> table = new HashMap<>();
    private final HashSet<Long> unloadedTable = new HashSet<>();
    private Object[] states = new Object[4096];
    private int[] found = new int[16];

    // ---- between them
    private final ConcurrentLinkedQueue<MirrorBatch> batches = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean working = new AtomicBoolean();
    private volatile CgMirrorVersion version;
    /** Sections the worker found differing from their chunk: written past the hook, so copied again. */
    private final ConcurrentLinkedQueue<MirrorSection> mismatched = new ConcurrentLinkedQueue<>();
    /** Positional cells the worker found in first copies, for the level thread to resolve from then on. */
    private final ConcurrentLinkedQueue<Found> discovered = new ConcurrentLinkedQueue<>();

    // ---- stats
    private static final String[] PHASES = {"watches", "resolve", "copies", "entities", "hittables", "handoff"};
    private long statLevelNanos, statLevelMax, statWorkerNanos, statDirty, statBases, statResolved, statEntities, statPublishes;
    private final long[] statPhases = new long[PHASES.length], statPhaseMax = new long[PHASES.length];

    private CgLevelMirror(CgMirrorHost host, Object level, CgWorldQuery world, CgEntityQuery entities, Freezer freezer) {
        this.host = host;
        this.level = new WeakReference<>(level);
        this.world = world;
        this.entities = entities;
        this.freezer = freezer;
        this.minSection = host.minSection(level);
        this.sectionCount = host.sections(level);
        this.positionalOnly = host.positionalOnly();
        this.palette = new MirrorPalette(host);
    }

    /**
     * {@code level}'s mirror, made at the first ask; null when the host does not mirror it (a client's level, a level
     * whose write hook did not apply). The level's thread.
     */
    @Nullable
    public static synchronized CgLevelMirror of(Object level, CgWorldQuery world, CgEntityQuery entities, Freezer freezer) {
        CgLevelMirror m = MIRRORS.get(level);
        if (m != null) return m;
        CgMirrorHost host = CgPlatform.get(CgMirrorHost.SERVICE);
        if (!host.mirrors(level)) return null;
        if (!hooked) {
            hooked = true;
            CgNetwork.onServerTick(CgLevelMirror::publishAll);
            CgNetwork.onSessionEnd(CgLevelMirror::clearAll);
        }
        m = new CgLevelMirror(host, level, world, entities, freezer);
        MIRRORS.put(level, m);
        return m;
    }

    /** The end of a server tick: every mirror publishes that tick. The server thread; {@code CgNetwork}'s tick. */
    static synchronized void publishAll() {
        long e = ++epoch;
        for (CgLevelMirror m : MIRRORS.values()) m.publish(e);
    }

    /** The epoch of the last publish. */
    static synchronized long epoch() {
        return epoch;
    }

    /** The session ended: every mirror goes, and its chunks are detached. */
    static synchronized void clearAll() {
        for (CgLevelMirror m : MIRRORS.values()) m.detachAll();
        MIRRORS.clear();
    }

    static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    /** The newest published version; null before the first. Any thread. */
    @Nullable
    public CgMirrorVersion version() {
        return version;
    }

    // =====================================================================================================
    // Watches
    // =====================================================================================================

    /**
     * Mirrors {@code bounds} until the watch closes. The level's thread; the watch itself is any thread's.
     *
     * @throws IllegalArgumentException when the area alone is past {@code crystalgraphics.mirror.memory}
     */
    public Watch watch(CgAABBc bounds) {
        Watch w = new Watch(bounds, false);
        open(w);
        return w;
    }

    /**
     * A one-off's watch for {@code bounds}: the same box asked again reuses it, and it closes once nothing has asked
     * for {@code crystalgraphics.mirror.idleTicks}. The level's thread.
     */
    public Watch asked(CgAABBc bounds) {
        Watch w = new Watch(bounds, true);
        Watch known = asked.get(w.box);
        if (known != null) {
            known.lastAsked = generation;
            return known;
        }
        open(w);
        asked.put(w.box, w);
        return w;
    }

    private void open(Watch w) {
        long area = (long) w.columnsWide() * w.columnsDeep() * (w.s1 - w.s0 + 1);
        if (area * SECTION_BYTES > (long) MEMORY_MB.get() << 20) {
            throw new IllegalArgumentException("an area of " + area + " sections is past crystalgraphics.mirror.memory ("
                    + MEMORY_MB.get() + " MB): watch only what the work reaches");
        }
        w.lastAsked = generation;
        watches.add(w);
        want(w, 1);
    }

    private void close(Watch w) {
        if (!w.open) return;
        w.open = false;
        watches.remove(w);
        if (w.oneOff) asked.remove(w.box, w);
        want(w, -1);
    }

    private void move(Watch w, CgAABBc bounds) {
        if (!w.open) return;
        want(w, -1);
        w.set(bounds);
        want(w, 1);
    }

    /** Counts {@code w}'s sections wanted once more, or once less, starting and stopping what changes. */
    private void want(Watch w, int delta) {
        for (int cx = w.cx0; cx <= w.cx1; cx++) {
            for (int cz = w.cz0; cz <= w.cz1; cz++) {
                long k = key(cx, cz);
                CgMirrorColumn c = columns.get(k);
                if (c == null) {
                    if (delta < 0) continue;
                    c = new CgMirrorColumn(this, cx, cz, sectionCount);
                    columns.put(k, c);
                    regionsStale = auditStale = true;
                }
                for (int s = w.s0; s <= w.s1; s++) {
                    if (delta > 0) {
                        if (c.wants[s]++ == 0) {
                            MirrorSection section = new MirrorSection(c, s);
                            c.sections[s] = section;
                            waiting.add(section);
                            wantedSections++;
                        }
                    } else if (c.wants[s] > 0 && --c.wants[s] == 0) {
                        retire(c.sections[s]);
                        c.sections[s] = null;
                        wantedSections--;
                    }
                }
                if (delta < 0 && unwanted(c)) {
                    if (c.chunk != null) host.attach(c.chunk, null);
                    columns.remove(k);
                    droppedColumns.add(k);
                    regionsStale = auditStale = true;
                }
            }
        }
    }

    private static boolean unwanted(CgMirrorColumn c) {
        for (int n : c.wants) {
            if (n > 0) return false;
        }
        return true;
    }

    /** Takes {@code s} out of the next version, for good: a fresh section replaces it if it is wanted again. */
    private void retire(@Nullable MirrorSection s) {
        if (s == null) return;
        s.resync = true;
        s.based = false;
        removedSections.add(s);
    }

    // =====================================================================================================
    // What the hook reports (the level thread)
    // =====================================================================================================

    void dirty(MirrorSection s) {
        if (dirtyCount == dirty.length) dirty = Arrays.copyOf(dirty, dirtyCount * 2);
        dirty[dirtyCount++] = s;
    }

    void dynamicWrite(MirrorSection s, int cell) {
        s.addDynamic(cell);
        listDynamic(s);
    }

    void positionalWrite(MirrorSection s, int cell) {
        if (positionalCount == positionalSections.length) {
            positionalSections = Arrays.copyOf(positionalSections, positionalCount * 2);
            positionalCells = Arrays.copyOf(positionalCells, positionalCount * 2);
        }
        positionalSections[positionalCount] = s;
        positionalCells[positionalCount++] = cell;
    }

    /** {@code s}'s log overflowed: a fresh section takes its place and is copied again, first in line. */
    void resync(MirrorSection s) {
        CgMirrorColumn c = s.column;
        if (c.sections[s.index] != s) return;
        retire(s);
        MirrorSection fresh = new MirrorSection(c, s.index);
        c.sections[s.index] = fresh;
        waiting.addFirst(fresh);
    }

    void unloaded(CgMirrorColumn c) {
        if (columns.get(c.key) != c) return;
        c.chunk = null;
        c.unloaded = true;
        unloadedColumns.add(c.key);
        for (int i = 0; i < c.sections.length; i++) {
            MirrorSection s = c.sections[i];
            if (s == null) continue;
            retire(s);
            MirrorSection fresh = new MirrorSection(c, i);
            c.sections[i] = fresh;
            waiting.add(fresh);
        }
    }

    private void detachAll() {
        for (CgMirrorColumn c : columns.values()) {
            if (c.chunk != null) host.attach(c.chunk, null);
            c.chunk = null;
        }
        columns.clear();
    }

    // =====================================================================================================
    // The publish (the level thread, at the tick's end)
    // =====================================================================================================

    private void publish(long e) {
        Object lvl = level.get();
        if (lvl == null) return;
        long started = System.nanoTime();
        Runnable command;
        while ((command = commands.poll()) != null) command.run();
        MirrorSection differing;
        while ((differing = mismatched.poll()) != null) resync(differing);
        closeIdle();
        generation++;
        long t = phase(0, started);

        MirrorBatch b = new MirrorBatch(e);
        for (int i = 0; i < dirtyCount; i++) {
            MirrorSection s = dirty[i];
            if (s.based && !s.resync) b.dirty(s, s.log.tail());
            dirty[i] = null;
        }
        statDirty += dirtyCount;
        dirtyCount = 0;
        for (MirrorSection s : removedSections) b.removed(s);
        removedSections.clear();
        for (Long k : unloadedColumns) b.unloaded(k);
        unloadedColumns.clear();
        for (Long k : droppedColumns) b.dropped(k);
        droppedColumns.clear();

        resolvePositional(lvl, b);
        t = phase(1, t);
        takeCopies(lvl, b);
        // a worker behind would queue a copy of every section a tick
        if (VERIFY.get() && batches.isEmpty()) verifyCopies(b);
        else audit(b);
        t = phase(2, t);

        if (!watches.isEmpty()) {
            CgAABB union = new CgAABB();
            for (Watch w : watches) union.union(w.bounds);
            b.entities = CgEntitySnapshot.capture(entities, 1f, entityRegions(union), lastEntities + lastEntities / 8);
            lastEntities = b.entities.size();
            statEntities += lastEntities;
            t = phase(3, t);
            b.frozen = freezer.freeze(union);
            t = phase(4, t);
        }
        b.border = world.border(borderScratch) ? borderScratch.clone() : null;
        b.minY = world.minY();
        b.maxY = world.maxY();
        b.seaLevel = world.seaLevel();
        b.levelEpoch = world.levelEpoch();

        batches.add(b);
        if (working.compareAndSet(false, true)) CgJob.executor(CgJobLane.LATENCY).execute(this::work);
        settleReady();
        phase(5, t);
        long spent = System.nanoTime() - started;
        statLevelNanos += spent;
        statLevelMax = Math.max(statLevelMax, spent);
        if (++statPublishes % 100 == 0 && STATS.get()) logStats();
    }

    /**
     * The boxes entities are read over: the mirrored columns, each run of neighbours along x as one box, over the
     * watches' heights. Watches overlap; columns do not, so an entity is visited about once however many watch it.
     * Rebuilt when the columns change.
     */
    private List<CgAABBc> entityRegions(CgAABBc union) {
        if (!regionsStale && union.minY() == regionsMinY && union.maxY() == regionsMaxY) return regions;
        regionsStale = false;
        regionsMinY = union.minY();
        regionsMaxY = union.maxY();
        regions.clear();
        long[] keys = new long[columns.size()];
        int n = 0;
        for (CgMirrorColumn c : columns.values()) {
            // z, then x biased to unsigned so that runs sort together
            keys[n++] = (long) c.chunkZ << 32 | (c.chunkX + 0x80000000L) & 0xFFFFFFFFL;
        }
        Arrays.sort(keys);
        for (int i = 0; i < n; ) {
            int z = (int) (keys[i] >> 32), x0 = (int) ((keys[i] & 0xFFFFFFFFL) - 0x80000000L), x1 = x0;
            i++;
            while (i < n && (int) (keys[i] >> 32) == z && (int) ((keys[i] & 0xFFFFFFFFL) - 0x80000000L) == x1 + 1) {
                x1++;
                i++;
            }
            regions.add(CgAABB.of(x0 << 4, regionsMinY, z << 4, (x1 + 1) << 4, regionsMaxY, (z + 1) << 4));
        }
        return regions;
    }

    /** Adds the time since {@code since} to stats phase {@code i}; returns now. */
    private long phase(int i, long since) {
        long now = System.nanoTime();
        statPhases[i] += now - since;
        statPhaseMax[i] = Math.max(statPhaseMax[i], now - since);
        return now;
    }

    /** Snapshots' watches nothing asked for lately close; past the memory budget, the longest idle go first. */
    private void closeIdle() {
        int idle = IDLE_TICKS.get();
        long budget = ((long) MEMORY_MB.get() << 20) / SECTION_BYTES;
        for (Iterator<Watch> it = asked.values().iterator(); it.hasNext(); ) {
            Watch w = it.next();
            if (generation - w.lastAsked < idle) continue;
            it.remove();
            w.open = false;
            watches.remove(w);
            want(w, -1);
        }
        while (wantedSections > budget && !asked.isEmpty()) {
            Watch oldest = null;
            for (Watch w : asked.values()) {
                if (oldest == null || w.lastAsked < oldest.lastAsked) oldest = w;
            }
            close(oldest);
        }
    }

    /**
     * Shapes that depend on where they stand, resolved now, at the tick's end. On a positional-only host, the cells
     * written this tick and their neighbours; elsewhere every dynamic cell of every mirrored section (a piston moving, a
     * shulker box opening, with no write), and the cells the worker found in first copies, from now on.
     */
    private void resolvePositional(Object lvl, MirrorBatch b) {
        for (int i = 0; i < positionalCount; i++) {
            MirrorSection s = positionalSections[i];
            positionalSections[i] = null;
            if (!s.based || s.resync) continue;
            int cell = positionalCells[i];
            int x = s.x(cell), y = s.y(cell), z = s.z(cell);
            resolve(lvl, b, s, cell, x, y, z);
            resolveAt(lvl, b, x + 1, y, z);
            resolveAt(lvl, b, x - 1, y, z);
            resolveAt(lvl, b, x, y + 1, z);
            resolveAt(lvl, b, x, y - 1, z);
            resolveAt(lvl, b, x, y, z + 1);
            resolveAt(lvl, b, x, y, z - 1);
        }
        positionalCount = 0;
        Found f;
        while ((f = discovered.poll()) != null) {
            MirrorSection s = f.section;
            if (!s.based || s.resync || s.column.sections[s.index] != s) continue;
            for (int cell : f.cells) s.addDynamic(cell);
            listDynamic(s);
            b.settled(s);
        }
        for (int i = dynamicSections.size() - 1; i >= 0; i--) {
            MirrorSection s = dynamicSections.get(i);
            if (!s.based || s.resync || s.column.sections[s.index] != s || s.dynamicCount == 0) {
                s.listedDynamic = false;
                dynamicSections.set(i, dynamicSections.get(dynamicSections.size() - 1));
                dynamicSections.remove(dynamicSections.size() - 1);
                continue;
            }
            for (int k = s.dynamicCount - 1; k >= 0; k--) {
                int cell = s.dynamic[k];
                resolve(lvl, b, s, cell, s.x(cell), s.y(cell), s.z(cell));
                if (!scratch.dynamic) s.removeDynamic(cell);
            }
        }
    }

    private void listDynamic(MirrorSection s) {
        if (s.listedDynamic || s.dynamicCount == 0) return;
        s.listedDynamic = true;
        dynamicSections.add(s);
    }

    private void resolveAt(Object lvl, MirrorBatch b, int x, int y, int z) {
        CgMirrorColumn c = columns.get(key(x >> 4, z >> 4));
        if (c == null) return;
        int i = (y >> 4) - minSection;
        if (i < 0 || i >= c.sections.length) return;
        MirrorSection s = c.sections[i];
        if (s == null || !s.based || s.resync) return;
        resolve(lvl, b, s, (y & 15) << 8 | (z & 15) << 4 | (x & 15), x, y, z);
    }

    private void resolve(Object lvl, MirrorBatch b, MirrorSection s, int cell, int x, int y, int z) {
        host.resolveAt(lvl, x, y, z, scratch.clear());
        b.resolved(s, cell, b.shapes.intern(scratch));
        statResolved++;
    }

    /** First copies of sections waiting for one, nearest the oldest asks first, inside the tick's budget. */
    private void takeCopies(Object lvl, MirrorBatch b) {
        long deadline = System.nanoTime() + BASE_BUDGET.get() * 1000L;
        int looks = 0;
        int n = waiting.size();
        for (int i = 0; i < n && !waiting.isEmpty(); i++) {
            if (System.nanoTime() > deadline) break;
            MirrorSection s = waiting.poll();
            CgMirrorColumn c = s.column;
            if (c.sections[s.index] != s || columns.get(c.key) != c) continue;
            if (c.chunk == null) {
                if (looks++ >= LOOKS_PER_PUBLISH) {
                    waiting.add(s);
                    continue;
                }
                Object chunk = host.chunk(lvl, c.chunkX, c.chunkZ);
                if (chunk == null || !host.attach(chunk, c)) {
                    if (!c.unloaded) {
                        c.unloaded = true;
                        b.unloaded(c.key);
                    }
                    waiting.add(s);
                    continue;
                }
                c.chunk = chunk;
                c.unloaded = false;
            }
            if (positionalOnly) {
                char[] cells = new char[4096];
                int x0 = c.chunkX << 4, z0 = c.chunkZ << 4, y0 = minSection + s.index << 4;
                for (int cell = 0; cell < 4096; cell++) {
                    host.resolveAt(lvl, x0 + (cell & 15), y0 + (cell >> 8), z0 + (cell >> 4 & 15), scratch.clear());
                    cells[cell] = b.shapes.intern(scratch);
                    if (scratch.dynamic) s.addDynamic(cell);
                }
                listDynamic(s);
                b.base(s, cells);
            } else {
                b.base(s, host.copy(c.chunk, s.index));
            }
            s.based = true;
            statBases++;
        }
    }

    /** {@code crystalgraphics.mirror.verify}: a copy of every mirrored section, for the worker to compare. */
    private void verifyCopies(MirrorBatch b) {
        if (host.positionalOnly()) return;
        for (CgMirrorColumn c : columns.values()) {
            if (c.chunk == null) continue;
            for (MirrorSection s : c.sections) {
                if (s != null && s.based && !s.resync) b.verify(s, host.copy(c.chunk, s.index));
            }
        }
    }

    /**
     * A few columns a tick, in turn, copied for the worker to compare: what catches a write that bypassed the hook (a mod
     * setting a section's states directly). A section that differs is copied again.
     */
    private void audit(MirrorBatch b) {
        if (positionalOnly || columns.isEmpty()) return;
        if (auditStale) {
            auditOrder.clear();
            auditOrder.addAll(columns.values());
            auditStale = false;
            auditAt = 0;
        }
        for (int n = (auditOrder.size() + AUDIT_TICKS - 1) / AUDIT_TICKS; n > 0; n--) {
            if (auditAt >= auditOrder.size()) auditAt = 0;
            CgMirrorColumn c = auditOrder.get(auditAt++);
            if (c.chunk == null) continue;
            for (MirrorSection s : c.sections) {
                if (s != null && s.based && !s.resync) b.verify(s, host.copy(c.chunk, s.index));
            }
        }
    }

    /** Settles each {@link Watch#ready} whose watch the newest version completes. */
    private void settleReady() {
        CgMirrorVersion v = version;
        if (v == null || readyWaiting.isEmpty()) return;
        for (Iterator<Watch> it = readyWaiting.iterator(); it.hasNext(); ) {
            Watch w = it.next();
            if (!w.open) {
                it.remove();
                continue;
            }
            if (!v.complete(w.bounds)) continue;
            it.remove();
            CgPendingReply<CgMirrorVersion> reply = w.ready;
            w.ready = null;
            if (reply != null) reply.resolve(v);
        }
    }

    private void logStats() {
        StringBuilder phases = new StringBuilder();
        for (int i = 0; i < PHASES.length; i++) {
            phases.append(i == 0 ? "" : ", ").append(PHASES[i]).append(' ').append(statPhases[i] / 100_000).append('/')
                    .append(statPhaseMax[i] / 1000);
            statPhases[i] = statPhaseMax[i] = 0;
        }
        LOGGER.info("[cg-mirror] {}: {} columns, {} sections wanted, {} waiting; per tick {} µs level thread (max {}; {}), "
                        + "{} µs worker, {} dirty sections, {} first copies, {} cells resolved, {} entities in {} boxes; "
                        + "{} shapes; verify differences {}",
                level.get(), columns.size(), wantedSections, waiting.size(), statLevelNanos / 100_000, statLevelMax / 1000,
                phases, statWorkerNanos / 100_000, statDirty / 100.0, statBases / 100.0, statResolved / 100.0,
                statEntities / 100.0, regions.size(), palette.count, VERIFY_DIFFERENCES.get());
        statLevelNanos = statLevelMax = statWorkerNanos = statDirty = statBases = statResolved = statEntities = 0;
    }

    // =====================================================================================================
    // The worker
    // =====================================================================================================

    private void work() {
        do {
            MirrorBatch b;
            while ((b = batches.poll()) != null) {
                long started = System.nanoTime();
                try {
                    apply(b);
                } catch (RuntimeException failed) {
                    LOGGER.error("[cg-mirror] a level mirror's publish failed", failed);
                }
                statWorkerNanos += System.nanoTime() - started;
            }
            working.set(false);
        } while (!batches.isEmpty() && working.compareAndSet(false, true));
    }

    /** Column {@code (chunkX, chunkZ)} as each thread holds it. For tests' failures; the level thread, once idle. */
    String describe(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        CgMirrorColumn c = columns.get(k);
        StringBuilder out = new StringBuilder("column ").append(chunkX).append(", ").append(chunkZ).append(": ");
        if (c == null) return out.append("not wanted").toString();
        out.append("chunk ").append(c.chunk != null).append(", unloaded ").append(c.unloaded).append(", sections [");
        for (MirrorSection s : c.sections) {
            out.append(s == null ? "-" : (s.based ? "b" : "") + (s.resync ? "r" : "") + (s.ready ? "R" : "")
                    + (waiting.contains(s) ? "w" : "")).append(' ');
        }
        out.append("]; worker unloaded ").append(unloadedTable.contains(k)).append(", data ").append(table.get(k) != null);
        return out.toString();
    }

    /** Waits until every batch handed over is published. For tests. */
    void awaitIdle() throws InterruptedException {
        while (working.get() || !batches.isEmpty()) Thread.sleep(1);
    }

    private void apply(MirrorBatch b) {
        HashMap<Long, Boolean> changed = new HashMap<>();
        for (int i = 0; i < b.removedCount; i++) {
            MirrorSection s = b.removed[i];
            MirrorSection[] col = workerColumns.get(s.column.key);
            if (col != null && col[s.index] == s) col[s.index] = null;
            changed.put(s.column.key, Boolean.TRUE);
        }
        for (int i = 0; i < b.droppedCount; i++) {
            workerColumns.remove(b.dropped[i]);
            unloadedTable.remove(b.dropped[i]);
            changed.put(b.dropped[i], Boolean.TRUE);
        }
        for (int i = 0; i < b.unloadedCount; i++) {
            unloadedTable.add(b.unloaded[i]);
            changed.put(b.unloaded[i], Boolean.TRUE);
        }
        for (int i = 0; i < b.baseCount; i++) {
            MirrorSection s = b.bases[i];
            base(s, b.baseCopies[i], b);
            s.baseEpoch = b.epoch;
            columnOf(s)[s.index] = s;
            unloadedTable.remove(s.column.key);
            changed.put(s.column.key, Boolean.TRUE);
        }
        for (int i = 0; i < b.dirtyCount; i++) {
            MirrorSection s = b.dirty[i];
            if (s.baseEpoch < 0) continue;
            s.log.replay(b.marks[i], (cell, state) -> write(s, cell, state));
            changed.put(s.column.key, Boolean.TRUE);
        }
        for (int i = 0; i < b.resolvedCount; i++) {
            MirrorSection s = b.resolved[i];
            if (s.baseEpoch < 0) continue;
            s.set(b.resolvedCells[i], palette.intern(b.shapes, b.resolvedEntries[i]));
            changed.put(s.column.key, Boolean.TRUE);
        }
        for (int i = 0; i < b.settledCount; i++) {
            MirrorSection s = b.settled[i];
            if (s.baseEpoch < 0) continue;
            s.ready = true;
            changed.put(s.column.key, Boolean.TRUE);
        }
        if (b.verifyCount > 0) verify(b);

        for (Long k : changed.keySet()) rebuild(k);
        CgEntityQuery seen = b.entities == null ? CgEntityQuery.NONE : b.entities.index();
        CgMirrorVersion last = version;
        Map<Long, CgMirrorVersion.ColumnData> published = changed.isEmpty() && last != null ? last.columns : new HashMap<>(table);
        version = new CgMirrorVersion(b.epoch, published, palette.view(), minSection, sectionCount, seen,
                b.frozen, b.border, b.minY, b.maxY, b.seaLevel, b.levelEpoch);
    }

    private MirrorSection[] columnOf(MirrorSection s) {
        MirrorSection[] col = workerColumns.get(s.column.key);
        if (col == null) {
            col = new MirrorSection[sectionCount];
            workerColumns.put(s.column.key, col);
        }
        return col;
    }

    /**
     * A section's first copy into its cells. Positional cells found in it go to the level thread, and the section is
     * held back until a publish has resolved them.
     */
    private void base(MirrorSection s, @Nullable Object copy, MirrorBatch b) {
        s.cells = null;
        s.uniform = ShapeStore.AIR;
        if (copy instanceof char[]) {
            char[] local = (char[]) copy;
            for (int cell = 0; cell < 4096; cell++) s.set(cell, palette.intern(b.shapes, local[cell]));
            s.ready = true;
            return;
        }
        int n = 0;
        if (copy != null) {
            host.states(copy, states);
            for (int cell = 0; cell < 4096; cell++) {
                if (write(s, cell, states[cell])) continue;
                if (n == found.length) found = Arrays.copyOf(found, n * 2);
                found[n++] = cell;
            }
            Arrays.fill(states, null);
        }
        s.ready = n == 0;
        if (n > 0) discovered.add(new Found(s, Arrays.copyOf(found, n)));
    }

    /** One replayed or copied cell; false for a positional one, left as air until the level thread resolves it. */
    private boolean write(MirrorSection s, int cell, @Nullable Object state) {
        int entry = palette.entryOf(state);
        s.set(cell, entry < 0 ? ShapeStore.AIR : (char) entry);
        return entry >= 0;
    }

    /** The column {@code k} as the next version holds it. */
    private void rebuild(Long k) {
        MirrorSection[] col = workerColumns.get(k);
        boolean unloaded = unloadedTable.contains(k);
        if (col == null && !unloaded) {
            table.remove(k);
            return;
        }
        CgMirrorVersion.ColumnData data = new CgMirrorVersion.ColumnData(sectionCount, unloaded);
        if (col != null && !unloaded) {
            for (int i = 0; i < col.length; i++) {
                MirrorSection s = col[i];
                if (s == null || !s.ready) continue;
                data.present[i] = true;
                data.cells[i] = s.cells;
                data.uniform[i] = s.uniform;
                s.shared = true;
            }
        }
        table.put(k, data);
    }

    /** Compares the replayed sections with the copies taken at their tick. */
    private void verify(MirrorBatch b) {
        for (int i = 0; i < b.verifyCount; i++) {
            MirrorSection s = b.verified[i];
            if (s.baseEpoch < 0 || !s.ready) continue;
            Object copy = b.verifyCopies[i];
            if (copy != null) host.states(copy, states);
            else Arrays.fill(states, null);
            int differ = 0;
            for (int cell = 0; cell < 4096; cell++) {
                int entry = copy == null ? ShapeStore.AIR : palette.entryOf(states[cell]);
                if (entry >= 0 && entry != s.get(cell)) differ++;
            }
            Arrays.fill(states, null);
            if (differ == 0) continue;
            mismatched.add(s);
            if (VERIFY_DIFFERENCES.getAndAdd(differ) < 20) {
                LOGGER.warn("[cg-mirror] section ({}, {}, {}) differs from its chunk in {} cells, written past the hook: "
                        + "copying it again", s.column.chunkX, minSection + s.index, s.column.chunkZ, differ);
            }
        }
    }

    // =====================================================================================================
    // A watch
    // =====================================================================================================

    /**
     * An area a level mirror keeps, until {@link #close}d: {@link #version} is the newest version, which holds it once
     * {@link #complete}. Opened on the level's thread; read, moved and closed on any.
     *
     * <pre>{@code
     * CgLevelMirror.Watch watch = mirror.watch(bounds);
     * CgMirrorVersion v = watch.version();     // each tick, from a worker
     * watch.move(next);                        // a boss walking
     * watch.close();
     * }</pre>
     */
    public final class Watch implements AutoCloseable {

        final boolean oneOff;
        volatile CgAABB bounds;
        int cx0, cz0, cx1, cz1, s0, s1;
        BoxKey box;
        boolean open = true;
        int lastAsked;
        @Nullable CgPendingReply<CgMirrorVersion> ready;

        Watch(CgAABBc bounds, boolean oneOff) {
            this.oneOff = oneOff;
            set(bounds);
        }

        void set(CgAABBc b) {
            bounds = new CgAABB(b);
            cx0 = CgMirrorVersion.floor(b.minX()) >> 4;
            cz0 = CgMirrorVersion.floor(b.minZ()) >> 4;
            cx1 = CgMirrorVersion.floor(b.maxX()) >> 4;
            cz1 = CgMirrorVersion.floor(b.maxZ()) >> 4;
            // a block's fluid height asks the block above it
            s0 = clampSection((CgMirrorVersion.floor(b.minY()) >> 4) - minSection);
            s1 = clampSection((CgMirrorVersion.floor(b.maxY()) + 1 >> 4) - minSection);
            box = new BoxKey(cx0, cz0, cx1, cz1, s0, s1);
        }

        private int clampSection(int s) {
            return Math.max(0, Math.min(sectionCount - 1, s));
        }

        int columnsWide() {
            return cx1 - cx0 + 1;
        }

        int columnsDeep() {
            return cz1 - cz0 + 1;
        }

        /** The area it keeps. */
        public CgAABBc bounds() {
            return bounds;
        }

        /** The newest version of its level; null before the first publish. Any thread. */
        @Nullable
        public CgMirrorVersion version() {
            return version;
        }

        /** Whether the newest version holds all of it. Any thread. */
        public boolean complete() {
            CgMirrorVersion v = version;
            return v != null && v.complete(bounds);
        }

        /**
         * The first version holding all of it: settled now when the newest does, else at a later tick's end. The
         * level's thread, where it settles.
         */
        public CgReply<CgMirrorVersion> ready() {
            CgMirrorVersion v = version;
            if (v != null && v.complete(bounds)) return CgReply.of(v);
            if (ready == null) {
                ready = new CgPendingReply<>(null);
                readyWaiting.add(this);
            }
            return ready;
        }

        /** Keeps {@code next} instead, from the next tick's end. Any thread. */
        public void move(CgAABBc next) {
            CgAABB copy = new CgAABB(next);
            commands.add(() -> CgLevelMirror.this.move(this, copy));
        }

        /** Stops keeping it, from the next tick's end. Any thread; a second close does nothing. */
        @Override
        public void close() {
            commands.add(() -> CgLevelMirror.this.close(this));
        }
    }

    /** The sections a one-off asked for, which the same box asked again finds. */
    private static final class BoxKey {
        final int cx0, cz0, cx1, cz1, s0, s1;

        BoxKey(int cx0, int cz0, int cx1, int cz1, int s0, int s1) {
            this.cx0 = cx0;
            this.cz0 = cz0;
            this.cx1 = cx1;
            this.cz1 = cz1;
            this.s0 = s0;
            this.s1 = s1;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BoxKey)) return false;
            BoxKey k = (BoxKey) o;
            return cx0 == k.cx0 && cz0 == k.cz0 && cx1 == k.cx1 && cz1 == k.cz1 && s0 == k.s0 && s1 == k.s1;
        }

        @Override
        public int hashCode() {
            return ((((cx0 * 31 + cz0) * 31 + cx1) * 31 + cz1) * 31 + s0) * 31 + s1;
        }
    }

    /** Positional cells the worker found in a section's first copy. */
    private static final class Found {
        final MirrorSection section;
        final int[] cells;

        Found(MirrorSection section, int[] cells) {
            this.section = section;
            this.cells = cells;
        }
    }
}
