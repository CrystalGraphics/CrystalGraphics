package com.crystalgraphics.collision;

import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.CollisionFakes.Yard;
import com.crystalgraphics.collision.block.CgBlockSnapshot;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.platform.service.CgBlockCopy;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgSphere;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_MONSTER;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Snapshots against the worlds they were taken of, and many threads querying one. */
public class CgSnapshotTest {

    private final Table table = new Table();
    private final CgCollisionWorld live = CgCollisionWorld.of(new Yard(), table);

    public CgSnapshotTest() {
        Random r = new Random(5);
        for (int id = 0; id < 80; id++) table.mob(id, r.nextDouble() * 40 - 20, r.nextDouble() * 40 - 20, KIND_MONSTER);
    }

    /** Casts, rays, overlaps and sight round the yard's stair, slab, fence and water, and its wall at x = 24. */
    private static String[] ask(CgCollisionWorld world) {
        Random r = new Random(9);
        CgHit hit = new CgHit();
        String[] out = new String[400];
        for (int i = 0; i < out.length; i++) {
            double x = r.nextDouble() * 50 - 25, z = r.nextDouble() * 50 - 25, dx = r.nextGaussian(), dz = r.nextGaussian();
            switch (i % 5) {
                case 0:
                    out[i] = world.cast(CgSphere.of(0.4)).at(x, 1, z).along(dx, -0.3, dz, 20).blocksToo().first(hit)
                            ? hit.toString() : "-";
                    break;
                case 1:
                    out[i] = world.ray().at(x, 0.7, 5.5).along(1, 0, 0, 30).blocksOnly().first(hit) ? hit.toString() : "-";
                    break;
                case 2:
                    out[i] = "n" + world.overlap(CgSphere.of(1 + i % 7)).at(x, 1, z).count();
                    break;
                case 3:
                    out[i] = world.lineOfSight(x, 1.6, z, i % 80) + "";
                    break;
                default:
                    out[i] = world.exposure(x, 0.2, 5.5, i % 80) + "";
            }
        }
        return out;
    }

    @Test
    public void aSnapshotAnswersAsItsWorldDid() {
        CgCollisionWorld snapshot = live.snapshot(0, 0, 0, 60);
        assertArrayEquals(ask(live), ask(snapshot));
    }

    @Test
    public void aSnapshotCopiedFromSectionsAnswersAsOneReadBlockByBlock() {
        CgCollisionWorld sections = CgCollisionWorld.of(new SectionYard(), table);
        assertArrayEquals(ask(live), ask(sections.snapshot(0, 0, 0, 60)));
        CgHit hit = new CgHit();
        assertTrue(sections.snapshot(CgAABB.of(90, -2, -4, 110, 4, 4)).ray().at(95, 1, 0).along(1, 0, 0, 10)
                .blocksOnly().first(hit));
        assertEquals(CgHit.Kind.UNLOADED, hit.kind());
    }

    /** The yard copied as a host copies sections: air left alone, each kind of block keyed, the fence where it stands. */
    private static final class SectionYard extends Yard {
        @Override
        public boolean copyBlocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CgBlockCopy into) {
            Map<String, Integer> keys = new HashMap<>();
            float[] boxes = new float[6 * 4];
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!loaded(x, z)) continue;
                    into.loaded(x, z);
                    for (int y = minY; y <= maxY; y++) {
                        int n = collisionBoxes(x, y, z, boxes), kind = fluidKind(x, y, z);
                        if (n == 0 && kind == FLUID_NONE) continue;
                        float height = fluidHeight(x, y, z);
                        boolean fence = x == 4 && y == 0 && z == 5;
                        String what = Arrays.toString(Arrays.copyOf(boxes, n * 6)) + kind + height;
                        int key = fence ? CgBlockCopy.NO_KEY : keys.computeIfAbsent(what, w -> keys.size() * 1000);
                        int entry = key < 0 ? -1 : into.entry(key);
                        if (entry < 0) entry = into.add(key, boxes, n, kind, height);
                        into.set(x, y, z, entry);
                    }
                }
            }
            return true;
        }
    }

    @Test
    public void anUnloadedColumnStopsARayInASnapshotToo() {
        CgCollisionWorld snapshot = live.snapshot(CgAABB.of(90, -2, -4, 110, 4, 4));
        CgHit hit = new CgHit();
        assertTrue(snapshot.ray().at(95, 1, 0).along(1, 0, 0, 10).blocksOnly().first(hit));
        assertEquals(CgHit.Kind.UNLOADED, hit.kind());
        assertEquals(100, hit.blockX());
        assertFalse("outside it, every cell is loaded air", snapshot.ray().at(200, 1, 0).along(1, 0, 0, 10).blocksOnly().any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSnapshotPastItsCapThrows() {
        live.snapshot(0, 0, 0, CgBlockSnapshot.MAX_SIDE);
    }

    @Test
    public void manyThreadsQueryOneSnapshotAndAgree() throws InterruptedException {
        CgCollisionWorld snapshot = live.snapshot(0, 0, 0, 60);
        String[] expected = ask(live);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        String[][] got = new String[8][];
        Thread[] threads = new Thread[got.length];
        for (int t = 0; t < threads.length; t++) {
            int mine = t;
            threads[t] = new Thread(() -> {
                try {
                    for (int round = 0; round < 5; round++) got[mine] = ask(snapshot);
                } catch (Throwable e) {
                    failed.set(e);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        if (failed.get() != null) throw new AssertionError(failed.get());
        for (String[] each : got) assertArrayEquals(expected, each);
    }

    @Test
    public void aSnapshotStaysAsItWasTaken() {
        table.mob(500, 60, 60, KIND_MONSTER);
        CgCollisionWorld snapshot = live.snapshot(60, 0, 60, 8);
        table.move(500, 140, 0, 140).kill(500);
        assertEquals(1, snapshot.overlap(CgSphere.of(2)).at(60, 1, 60).count());
        assertEquals(0, live.overlap(CgSphere.of(2)).at(60, 1, 60).count());
        assertEquals("an entity it never held is unknown to it", 0, snapshot.entities().flags(3));
        assertTrue(snapshot.snapshot(0, 0, 0, 1) == snapshot);
    }

    @Test
    public void anEmptyWorldsSnapshotFreezesItsHittablesForEveryThread() throws InterruptedException {
        CgCollisionWorld arena = CgCollisionWorld.empty();
        CgPose pose = new CgPose();
        CgHitbox[] props = new CgHitbox[50];
        for (int id = 0; id < props.length; id++) {
            props[id] = CgHitbox.of(CgSphere.of(0.5)).source(id).hittable().passive().pose(pose.at(id * 2, 0, 0));
            props[id].tick(arena, 1);
        }
        try {
            CgCollisionWorld frozen = arena.snapshot(CgAABB.of(-10, -10, -10, 110, 10, 10));
            props[0].pose(pose.at(500, 0, 0)).tick(arena, 1);
            assertEquals(2, frozen.overlap(CgSphere.of(1.5)).at(1, 0, 0).count());
            assertEquals(1, arena.overlap(CgSphere.of(1.5)).at(1, 0, 0).count());
            assertEquals("only what touches its box", 2,
                    arena.snapshot(CgAABB.of(9, -1, -1, 13, 1, 1)).overlap(CgSphere.of(100)).at(0, 0, 0).count());

            int[] counts = new int[8];
            Thread[] threads = new Thread[counts.length];
            for (int t = 0; t < threads.length; t++) {
                int mine = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < 1000; i++) {
                        counts[mine] += frozen.overlap(CgSphere.of(1.5)).at(i % 100, 0, 0).count();
                    }
                });
                threads[t].start();
            }
            for (Thread t : threads) t.join();
            for (int c : counts) assertEquals(counts[0], c);
        } finally {
            for (CgHitbox p : props) p.stop();
        }
    }

    @Test
    public void aLevelsWorldIsNeverMadeOnAWorker() {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CgJob<CgCollisionWorld> job = CgJob.run(() -> CgCollisionWorld.of(new Object())).failed(thrown::set);
        long until = System.nanoTime() + 10_000_000_000L;
        while (!job.isDone() && System.nanoTime() < until) {
            CgJob.drain();
            Thread.yield();
        }
        assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof IllegalStateException);
        assertTrue(thrown.get().getMessage().contains("snapshot"));
    }
}
