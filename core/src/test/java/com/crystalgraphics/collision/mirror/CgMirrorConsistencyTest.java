package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CollisionFakes;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static com.crystalgraphics.collision.mirror.MirrorFakes.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The mirror's accuracy, proved under load: this thread is the level's, writing at random (bursts past a section's log,
 * dynamic blocks moving with no write, chunks unloading and coming back) and publishing each tick, while the worker
 * builds versions beside it. Another thread compares every complete version it sees, cell by cell, with the world as
 * it stood at the end of that version's tick.
 */
public class CgMirrorConsistencyTest {

    private static final int TICKS = 500;
    /** 2 x 2 columns watched; column (4, 4) loaded and written but never watched. */
    private static final CgAABBc AREA = CgAABB.of(0, 0, 0, 31.99, 63, 31.99);

    private final Host host = new Host();

    @Before
    public void setUp() {
        CgPlatform.provide(CgMirrorHost.SERVICE, host);
        CgPlatform.provide(CgLevelQueries.SERVICE, new Queries(new CollisionFakes.Table()));
    }

    @After
    public void tearDown() {
        CgLevelMirror.clearAll();
        CgPlatform.provide(CgMirrorHost.SERVICE, null);
        CgPlatform.provide(CgLevelQueries.SERVICE, null);
    }

    @Test
    public void everyVersionIsExactlyItsTick() throws InterruptedException {
        run(false, 11);
    }

    @Test
    public void everyVersionIsExactlyItsTickOnAPositionalOnlyHost() throws InterruptedException {
        host.positional = true;
        run(true, 12);
    }

    /** The world as it stood at a tick's end: its chunks' states, which were loaded, and the dynamic blocks' phases. */
    private static final class Truth {
        final Map<Long, Integer[][]> chunks = new HashMap<>();
        final Map<Long, Integer> phases;

        Truth(Level level) {
            for (Map.Entry<Long, Chunk> e : level.chunks.entrySet()) {
                Integer[][] copy = new Integer[SECTIONS][];
                for (int s = 0; s < SECTIONS; s++) copy[s] = e.getValue().sections[s].clone();
                chunks.put(e.getKey(), copy);
            }
            phases = new HashMap<>(level.phases);
        }

        Level asLevel(boolean positionalOnly) {
            Level l = new Level(positionalOnly);
            for (Map.Entry<Long, Integer[][]> e : chunks.entrySet()) {
                Chunk c = l.load((int) (e.getKey() >> 32), (int) (long) e.getKey());
                for (int s = 0; s < SECTIONS; s++) c.sections[s] = e.getValue()[s];
            }
            l.phases.putAll(phases);
            return l;
        }
    }

    private void run(boolean positionalOnly, long seed) throws InterruptedException {
        Level level = new Level(positionalOnly);
        Random r = new Random(seed);
        for (int cx = 0; cx < 2; cx++) {
            for (int cz = 0; cz < 2; cz++) level.load(cx, cz);
        }
        level.load(4, 4);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                for (int y = 0; y < 20; y++) level.set(x, y, z, STONE);
            }
        }
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        CgLevelMirror mirror = CgLevelMirror.of(level, level, new CollisionFakes.Table(), b -> null);

        ConcurrentHashMap<Long, Truth> truths = new ConcurrentHashMap<>();
        AtomicInteger verified = new AtomicInteger(), differences = new AtomicInteger();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread verifier = new Thread(() -> {
            long last = -1;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    CgMirrorVersion v = mirror.version();
                    if (v == null || v.epoch() == last || !v.complete(AREA)) {
                        Thread.yield();
                        continue;
                    }
                    last = v.epoch();
                    Truth t = truths.get(v.epoch());
                    if (t == null) continue;
                    int d = CgLevelMirrorTest.differences(v.blocks(AREA), t.asLevel(positionalOnly), AREA);
                    differences.addAndGet(d);
                    verified.incrementAndGet();
                }
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "mirror-verifier");
        verifier.start();

        List<int[]> dynamics = new ArrayList<>();
        Chunk away = null;
        for (int tick = 0; tick < TICKS; tick++) {
            int writes = r.nextInt(40);
            if (tick % 97 == 13) writes = 3000;
            for (int i = 0; i < writes; i++) {
                int x = r.nextInt(32), y = r.nextInt(64), z = r.nextInt(32);
                Integer state = r.nextInt(3) == 0 ? AIR : SOLIDS[r.nextInt(SOLIDS.length)];
                level.set(x, y, z, state);
                if (DYNAMIC.equals(state)) dynamics.add(new int[]{x, y, z});
            }
            if (tick == 250) {
                // past a section's log: it copies itself again
                for (int i = 0; i < WriteLog.CAPACITY + 500; i++) {
                    level.set(i & 15, 30 + (i >> 8 & 7), i >> 4 & 15, (i & 1) == 0 ? STONE : SLAB);
                }
            }
            for (int[] d : dynamics) {
                if (r.nextInt(3) == 0) level.phase(d[0], d[1], d[2], r.nextInt(16));
            }
            for (int i = 0; i < 20; i++) level.set(64 + r.nextInt(16), r.nextInt(64), 64 + r.nextInt(16), STONE);
            if (tick % 120 == 60 && away == null) {
                away = level.chunks.get(CgLevelMirror.key(1, 0));
                level.unload(1, 0);
            } else if (tick % 120 == 90 && away != null) {
                Chunk back = level.load(1, 0);
                for (int s = 0; s < SECTIONS; s++) back.sections[s] = away.sections[s].clone();
                away = null;
            }

            CgLevelMirror.publishAll();
            long e = CgLevelMirror.epoch();
            truths.put(e, new Truth(level));
            truths.remove(e - 64);
            LockSupport.parkNanos(r.nextInt(300_000));
        }
        mirror.awaitIdle();
        Thread.sleep(50);
        verifier.interrupt();
        verifier.join();
        if (failed.get() != null) throw new AssertionError(failed.get());
        CgMirrorVersion last = mirror.version();
        assertTrue(last.complete(AREA));
        Level now = new Truth(level).asLevel(positionalOnly);
        assertEquals(CgLevelMirrorTest.described(last.blocks(AREA), now, AREA) + mirror.describe(1, 0), 0,
                CgLevelMirrorTest.differences(last.blocks(AREA), now, AREA));
        assertEquals("cells differing from their tick, over " + verified.get() + " versions", 0, differences.get());
        assertTrue("only " + verified.get() + " versions compared", verified.get() >= 50);
        watch.close();
    }
}
