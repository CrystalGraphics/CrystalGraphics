package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CollisionFakes;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.sun.management.ThreadMXBean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Random;

import static com.crystalgraphics.collision.mirror.MirrorFakes.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgLevelMirrorTest {

    private static final CgAABBc AREA = CgAABB.of(0, 0, 0, 31.99, 63, 31.99);

    private final Host host = new Host();
    private Level level;
    private final CollisionFakes.Table entities = new CollisionFakes.Table();

    @Before
    public void setUp() {
        CgPlatform.provide(CgMirrorHost.SERVICE, host);
        CgPlatform.provide(CgLevels.SERVICE, new Queries(entities));
        level = new Level(false);
    }

    @After
    public void tearDown() {
        CgLevelMirror.clearAll();
        CgPlatform.provide(CgMirrorHost.SERVICE, null);
        CgPlatform.provide(CgLevels.SERVICE, null);
    }

    /** Fills 2 x 2 columns with a random yard. */
    private void yard(long seed) {
        Random r = new Random(seed);
        for (int cx = 0; cx < 2; cx++) {
            for (int cz = 0; cz < 2; cz++) level.load(cx, cz);
        }
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                int ground = 10 + r.nextInt(20);
                for (int y = 0; y < ground; y++) level.set(x, y, z, STONE);
                if (r.nextInt(4) == 0) level.set(x, ground, z, SOLIDS[r.nextInt(SOLIDS.length)]);
                if (r.nextInt(6) == 0) {
                    level.set(x, ground, z, WATER);
                    level.set(x, ground + 1, z, WATER);
                }
            }
        }
    }

    private CgLevelMirror mirror() {
        return CgLevelMirror.of(level, level, entities, within -> null);
    }

    private void tick() throws InterruptedException {
        CgLevelMirror.publishAll();
        mirror().awaitIdle();
    }

    /** Cells where the two answer differently inside {@code box}. */
    static int differences(CgWorldQuery a, CgWorldQuery b, CgAABBc box) {
        return compare(a, b, box, null);
    }

    /** The first few cells where the two answer differently, described: a failure's message. */
    static String described(CgWorldQuery a, CgWorldQuery b, CgAABBc box) {
        StringBuilder out = new StringBuilder();
        compare(a, b, box, out);
        return out.toString();
    }

    private static int compare(CgWorldQuery a, CgWorldQuery b, CgAABBc box, StringBuilder out) {
        int differ = 0;
        float[] ba = new float[48], bb = new float[48];
        for (int x = (int) box.minX(); x <= (int) box.maxX(); x++) {
            for (int z = (int) box.minZ(); z <= (int) box.maxZ(); z++) {
                if (a.loaded(x, z) != b.loaded(x, z)) {
                    if (out != null && differ < 5) out.append(String.format("(%d, %d) loaded %b, expected %b; ", x, z,
                            a.loaded(x, z), b.loaded(x, z)));
                    differ++;
                    continue;
                }
                if (!a.loaded(x, z)) continue;
                for (int y = (int) box.minY(); y <= (int) box.maxY(); y++) {
                    int na = a.collisionBoxes(x, y, z, ba), nb = b.collisionBoxes(x, y, z, bb);
                    boolean same = na == nb && a.fluidKind(x, y, z) == b.fluidKind(x, y, z)
                            && Float.compare(a.fluidHeight(x, y, z), b.fluidHeight(x, y, z)) == 0;
                    for (int i = 0; same && i < na * 6; i++) same = ba[i] == bb[i];
                    if (same) continue;
                    if (out != null && differ < 5) out.append(String.format("(%d, %d, %d) top %s fluid %s, expected %s %s; ",
                            x, y, z, a.collisionTop(x, y, z), a.fluidHeight(x, y, z), b.collisionTop(x, y, z),
                            b.fluidHeight(x, y, z)));
                    differ++;
                }
            }
        }
        return differ;
    }

    @Test
    public void aWatchedAreaAnswersAsTheLevelDoes() throws InterruptedException {
        yard(1);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        tick();
        assertTrue(watch.complete());
        assertEquals(0, differences(watch.latest().blocks(), level, AREA));
        watch.close();
    }

    @Test
    public void writesShowAtTheTickTheyWereMadeIn() throws InterruptedException {
        yard(2);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        tick();
        level.set(5, 40, 5, STONE);
        level.set(6, 40, 5, WATER);
        CgLevelMirror.publishAll();
        // made after the tick's end: the version of that tick must not show it
        level.set(5, 40, 5, SLAB);
        level.set(7, 40, 5, STONE);
        mirror().awaitIdle();
        CgWorldQuery seen = watch.latest().blocks();
        assertEquals(1f, seen.collisionTop(5, 40, 5), 0f);
        assertEquals(CgWorldQuery.FLUID_WATER, seen.fluidKind(6, 40, 5));
        assertTrue(Float.isNaN(seen.collisionTop(7, 40, 5)));
        tick();
        assertEquals(0, differences(watch.latest().blocks(), level, AREA));
        watch.close();
    }

    @Test
    public void aWritePastTheHookIsFoundByTheAuditAndCopiedAgain() throws InterruptedException {
        yard(3);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        tick();
        // a mod setting a section's states directly: the hook never hears it
        level.load(1, 1).sections[2][0] = STONE;
        for (int t = 0; t < 10 && differences(watch.latest().blocks(), level, AREA) > 0; t++) tick();
        assertEquals(described(watch.latest().blocks(), level, AREA), 0, differences(watch.latest().blocks(), level, AREA));
        watch.close();
    }

    @Test
    public void aDynamicShapeFollowsItsBlockWithNoWrite() throws InterruptedException {
        yard(3);
        level.set(3, 50, 3, DYNAMIC);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        tick();
        tick();
        assertTrue(watch.complete());
        assertEquals(1 / 16f, watch.latest().blocks().collisionTop(3, 50, 3), 1e-6f);
        level.phase(3, 50, 3, 7);
        tick();
        assertEquals(8 / 16f, watch.latest().blocks().collisionTop(3, 50, 3), 1e-6f);
        level.set(9, 50, 9, DYNAMIC);
        level.phase(9, 50, 9, 3);
        tick();
        assertEquals(4 / 16f, watch.latest().blocks().collisionTop(9, 50, 9), 1e-6f);
        level.phase(9, 50, 9, 5);
        tick();
        assertEquals(6 / 16f, watch.latest().blocks().collisionTop(9, 50, 9), 1e-6f);
        assertEquals(0, differences(watch.latest().blocks(), level, AREA));
        watch.close();
    }

    @Test
    public void anUnloadedChunkReadsUnloadedUntilItLoads() throws InterruptedException {
        yard(4);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        tick();
        level.unload(1, 1);
        tick();
        assertFalse(watch.latest().blocks().loaded(20, 20));
        assertTrue(watch.complete());
        level.load(1, 1).set(20, 30, 20, STONE);
        tick();
        tick();
        assertTrue(watch.latest().blocks().loaded(20, 20));
        assertEquals(0, differences(watch.latest().blocks(), level, AREA));
        watch.close();
    }

    @Test
    public void closingTheLastWatchDetachesItsChunks() throws InterruptedException {
        yard(5);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        assertNotNull(level.chunks.get(CgLevelMirror.key(0, 0)).column);
        watch.close();
        tick();
        assertNull(level.chunks.get(CgLevelMirror.key(0, 0)).column);
        assertFalse(mirror().version().complete(AREA));
    }

    @Test
    public void snapshotsOfOneBoxShareItAndItGoesOnceUnasked() throws InterruptedException {
        yard(6);
        CgCollisionWorld world = CgCollisionWorld.of(level);
        CgReply<CgCollisionWorld> first = world.snapshot(AREA);
        assertFalse(first.isDone());
        // a first copy, its dynamic cells resolved, then the reply settled at the next tick's end
        for (int i = 0; i < 4 && !first.isDone(); i++) tick();
        assertTrue(first.isDone());
        assertEquals(0, differences(first.result().blocks(), level, AREA));
        assertSame(mirror().asked(AREA), mirror().asked(AREA));
        CgReply<CgCollisionWorld> again = world.snapshot(AREA);
        assertTrue(again.isDone());
        assertEquals(0, differences(again.result().blocks(), level, AREA));
        for (int i = 0; i <= CgLevelMirror.IDLE_TICKS.get(); i++) CgLevelMirror.publishAll();
        mirror().awaitIdle();
        assertNull(level.chunks.get(CgLevelMirror.key(0, 0)).column);
    }

    @Test
    public void onAPositionalOnlyHostAWriteReshapesItsNeighbours() throws InterruptedException {
        host.positional = true;
        level = new Level(true);
        yard(7);
        level.set(10, 45, 10, FENCE);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        for (int i = 0; i < 20 && !watch.complete(); i++) tick();
        assertTrue(watch.complete());
        assertEquals(0, differences(watch.latest().blocks(), level, AREA));
        level.set(11, 45, 10, FENCE);
        level.set(10, 45, 11, FENCE);
        tick();
        float[] boxes = new float[6];
        watch.latest().blocks().collisionBoxes(10, 45, 10, boxes);
        assertEquals(0.375f - 0.1f, boxes[0], 1e-6f);
        assertEquals(0, differences(watch.latest().blocks(), level, AREA));
        watch.close();
    }

    @Test
    public void entitiesAreThoseOfTheTicksEnd() throws InterruptedException {
        yard(8);
        entities.mob(1, 5, 5, CgEntityQuery.KIND_MONSTER);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        tick();
        CgAABB box = new CgAABB();
        assertTrue(watch.latest().entities().box(1, 1f, box));
        entities.move(1, 9, 30, 9);
        CgLevelMirror.publishAll();
        entities.move(1, 20, 30, 20);
        mirror().awaitIdle();
        watch.latest().entities().box(1, 1f, box);
        assertEquals(9, box.centerX(), 0.01);
        watch.close();
    }

    @Test
    public void aWriteToAWatchedSectionAllocatesNothing() throws InterruptedException {
        yard(9);
        CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(AREA);
        tick();
        tick();
        Chunk chunk = level.chunks.get(CgLevelMirror.key(0, 0));
        for (int i = 0; i < 4000; i++) chunk.set(i & 15, 40, i >> 4 & 15, (i & 1) == 0 ? STONE : SLAB);
        tick();
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        for (int i = 0; i < 4000; i++) chunk.set(i & 15, 40, i >> 4 & 15, (i & 1) == 0 ? SLAB : STONE);
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
        assertTrue("4000 hooked writes allocated " + allocated + " bytes", allocated < 1024);
        tick();
        assertEquals(0, differences(watch.latest().blocks(), level, AREA));
        watch.close();
    }
}
