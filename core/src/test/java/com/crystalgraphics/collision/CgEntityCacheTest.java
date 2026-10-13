package com.crystalgraphics.collision;

import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.CollisionFakes.Yard;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.sun.management.ThreadMXBean;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Random;

import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_MONSTER;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** A batch's entity cache against the host it caches: 200 mobs over a 64-block square. */
public class CgEntityCacheTest {

    private static final int MOBS = 200;
    private static final CgShape BALL = CgSphere.of(3);

    private final Table table = new Table();
    private final CgCollisionWorld world = CgCollisionWorld.of(new Yard(), table);
    private final CgHits hits = new CgHits();

    public CgEntityCacheTest() {
        Random r = new Random(7);
        for (int id = 0; id < MOBS; id++) table.mob(id, r.nextDouble() * 64 - 32, r.nextDouble() * 64 - 32, KIND_MONSTER);
    }

    private String answer(CgQuery q) {
        int n = q.into(hits);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(hits.get(i).entity()).append('@').append(Math.round(hits.get(i).distance() * 1e6)).append(' ');
        return s.toString();
    }

    private String[] queries(long seed) {
        Random r = new Random(seed);
        String[] out = new String[300];
        for (int i = 0; i < out.length; i++) {
            double x = r.nextDouble() * 80 - 40, z = r.nextDouble() * 80 - 40, size = 0.5 + r.nextDouble() * 12;
            out[i] = i % 3 == 0
                    ? answer(world.cast(CgSphere.of(size / 4)).at(x, 1, z).along(r.nextGaussian(), 0, r.nextGaussian(), size * 3))
                    : answer(world.overlap(CgSphere.of(size)).at(x, 1, z).source(i % MOBS));
        }
        return out;
    }

    @Test
    public void aBatchAnswersAsTheHostDoes() {
        String[] direct = queries(11);
        String[] batched;
        try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
            warm();
            batched = queries(11);
        }
        assertArrayEquals(direct, batched);
        try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
            warm();
            assertEquals("bounds past its cells go to the host", MOBS,
                    world.overlap(CgAABB.of(-200, -10, -200, 200, 10, 200)).count());
        }
    }

    @Test
    public void anEntityNoHitWasHandedStaysWhereTheBatchFoundIt() {
        table.mob(500, 100, 100, KIND_MONSTER);
        try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
            warm();
            assertEquals(1, world.overlap(BALL).at(100, 1, 100).count());
            table.move(500, 120, 0, 120);
            assertEquals("seen where the batch found it", 1, world.overlap(BALL).at(100, 1, 100).count());
        }
        try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
            assertEquals("a new batch sees the move", 0, world.overlap(BALL).at(100, 1, 100).count());
        }
    }

    @Test
    public void whatAHitWasHandedIsSeenAsItNowIs() {
        table.mob(500, 100, 100, KIND_MONSTER).mob(501, 140, 140, KIND_MONSTER);
        try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
            warm();
            assertEquals(1, world.overlap(BALL).at(140, 1, 140).count());
            world.overlap(BALL).at(100, 1, 100).forEach(h -> table.move(h.entity(), 140, 0, 141));
            assertEquals("found where the hit moved it", 2, world.overlap(BALL).at(140, 1, 140).count());
            assertEquals("and gone from where it was", 0, world.overlap(BALL).at(100, 1, 100).count());
            world.overlap(BALL).at(140, 1, 140).forEach(h -> table.kill(h.entity()));
            assertEquals("a kill is seen at once", 0, world.overlap(BALL).at(140, 1, 140).count());
        }
    }

    @Test
    public void changedHandsABystanderBackToTheHost() {
        table.mob(500, 100, 100, KIND_MONSTER);
        try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
            warm();
            assertEquals(1, world.overlap(BALL).at(100, 1, 100).count());
            table.move(500, 120, 0, 120);
            world.changed(500);
            assertEquals(0, world.overlap(BALL).at(100, 1, 100).count());
            assertEquals(1, world.overlap(BALL).at(120, 1, 120).count());
            table.mob(502, 100, 100, KIND_MONSTER);
            world.changed();
            assertEquals("an entity added is found once the batch reads again", 1,
                    world.overlap(BALL).at(100, 1, 100).count());
        }
    }

    /** Asks past the batch's threshold, so what follows reads the cache. */
    private void warm() {
        for (int i = 0; i < CgEntityCache.CACHE_AFTER; i++) world.entities();
    }

    @Test
    public void batchedQueriesAllocateNothingAndCostLess() {
        double[][] at = new double[1000][];
        Random r = new Random(3);
        for (int i = 0; i < at.length; i++) at[i] = new double[]{r.nextDouble() * 64 - 32, 1, r.nextDouble() * 64 - 32};
        for (int warm = 0; warm < 30; warm++) {
            run(at, 1000, false);
            run(at, 1000, true);
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), quiet = Long.MAX_VALUE;
        for (int window = 0; window < 3; window++) {
            long before = bean.getThreadAllocatedBytes(thread);
            run(at, 1000, true);
            quiet = Math.min(quiet, bean.getThreadAllocatedBytes(thread) - before);
        }
        assertEquals("a warm batch allocates nothing", 0, quiet);
        for (int n : new int[]{100, 1000}) {
            long direct = best(at, n, false), batched = best(at, n, true);
            System.out.printf("S8: %d overlaps of a 3-block ball over %d entities: direct %.1f us, batched %.1f us%n",
                    n, MOBS, direct / 1e3, batched / 1e3);
        }
    }

    private long best(double[][] at, int n, boolean batched) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 40; i++) {
            long t = System.nanoTime();
            run(at, n, batched);
            best = Math.min(best, System.nanoTime() - t);
        }
        return best;
    }

    private int run(double[][] at, int n, boolean batched) {
        int found = 0;
        if (!batched) {
            for (int i = 0; i < n; i++) found += world.overlap(BALL).at(at[i][0], at[i][1], at[i][2]).into(hits);
            return found;
        }
        try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
            for (int i = 0; i < n; i++) found += world.overlap(BALL).at(at[i][0], at[i][1], at[i][2]).into(hits);
        }
        return found;
    }
}
