package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CgQuery;
import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hit.CgSentHit;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.world.CgEntityAttachments;
import com.sun.management.ThreadMXBean;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_MONSTER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Hitboxes over entities alone, ticked by hand unless a test says otherwise. */
public class CgHitboxTest {

    private static final CgHitPart TIP = CgHitPart.of("tip"), BLADE = CgHitPart.of("blade"),
            SHIELD = CgHitPart.of("shield");

    private static CgCollisionWorld world(Table table) {
        return CgCollisionWorld.of(CgWorldQuery.NONE, table);
    }

    @Test
    public void aRingGrowingPastEntitiesHitsEachOnceInDistanceOrder() {
        Table t = new Table().mob(1, 0, 8, KIND_MONSTER).mob(2, 2, 0, KIND_MONSTER).mob(3, -3.5, -3.5, KIND_MONSTER);
        CgCollisionWorld w = world(t);
        List<Integer> hits = new ArrayList<>();
        List<Double> when = new ArrayList<>();
        CgHitbox wave = CgHitbox.of(CgRing.front(1.2, 0.6)).at(0, 0.9, 0).radius(0, 12, 12).activeFor(12);
        wave.onHit(h -> {
            hits.add(h.entity());
            when.add(wave.age());
        });
        for (int i = 0; i < 20; i++) wave.tick(w, 1);
        assertEquals(Arrays.asList(2, 3, 1), hits);
        assertEquals("2 blocks out, reached in the tick its front passes 1.7", 2.0, when.get(0), 0);
        assertTrue(wave.stopped());
    }

    @Test
    public void aFastSphereDoesNotStepOverATarget() {
        CgCollisionWorld w = world(new Table().mob(1, 7.5, 0, KIND_MONSTER));
        int[] hits = {0};
        CgHitbox bolt = CgHitbox.of(CgSphere.of(0.25)).onHit(h -> hits[0]++);
        for (int i = 0; i <= 2; i++) {
            assertEquals("no tick lands on it", 0, w.overlap(CgSphere.of(0.25)).at(i * 5, 0.9, 0).count());
            bolt.at(i * 5, 0.9, 0).tick(w, 1);
        }
        assertEquals(1, hits[0]);
    }

    @Test
    public void aFastTurnFollowsTheArc() {
        // the attacker turns 170 degrees in one tick: toward -x, Minecraft's yaw 90
        Table t = new Table().mob(1, 0, 0, KIND_MONSTER).mob(2, -1.1, 0, KIND_MONSTER).mob(3, 1.1, 0, KIND_MONSTER);
        CgCollisionWorld w = world(t);
        List<Integer> hits = new ArrayList<>();
        CgHitbox swing = CgHitbox.follow(1, CgEntityAttachments.CENTRE).facing(CgHitbox.Facing.LOOK)
                .part(TIP, CgSphere.of(0.35), 0, 0, 1.1).source(1).onHit(h -> hits.add(h.entity()));
        swing.tick(w, 1);
        t.look(1, 170, 0);
        swing.tick(w, 1);
        assertEquals("the arc passes 2 and never 3", Arrays.asList(2), hits);
    }

    @Test
    public void partsShareOneHitListAndTheFirstListedWins() {
        // 2 is touched by both parts, 3 by the blade alone
        Table t = new Table().mob(1, 0, 0, KIND_MONSTER).mob(2, 0, 1.3, KIND_MONSTER).mob(3, 0.5, 0.3, KIND_MONSTER);
        CgCollisionWorld w = world(t);
        List<String> hits = new ArrayList<>();
        CgHitbox swing = CgHitbox.follow(1, CgEntityAttachments.CENTRE).facing(CgHitbox.Facing.LOOK).activeFor(5)
                .part(TIP, CgSphere.of(0.35), 0, 0, 1.1)
                .part(BLADE, CgCapsule.of(0.5, 0.3), 0, 0, 0.5)
                .source(1)
                .onHit(h -> hits.add(h.entity() + " " + h.part().name()));
        for (int i = 0; i < 5; i++) swing.tick(w, 1);
        hits.sort(null);
        assertEquals(Arrays.asList("2 tip", "3 blade"), hits);
    }

    @Test
    public void rehitAndMaxTargetsApplyTheRules() {
        Table t = new Table().mob(1, 1, 0, KIND_MONSTER).mob(2, 0, 1.5, KIND_MONSTER).mob(3, -2, 0, KIND_MONSTER);
        CgCollisionWorld w = world(t);
        int[] hits = {0};
        CgHitList list = new CgHitList();
        CgHitbox aura = CgHitbox.of(CgSphere.of(3)).at(0, 0.9, 0).rehit(5).maxHits(2).hitList(list).onHit(h -> hits[0]++);
        for (int i = 0; i < 20; i++) aura.tick(w, 1);
        assertEquals("each at ticks 1 and 6", 6, hits[0]);
        assertEquals(2, list.count(3));
        assertEquals(6.0, list.lastHit(3), 0);

        List<Integer> pierced = new ArrayList<>();
        CgHitbox pierce = CgHitbox.of(CgSphere.of(3)).at(0, 0.9, 0).maxTargets(2).onHit(h -> pierced.add(h.entity()));
        for (int i = 0; i < 3; i++) pierce.tick(w, 1);
        assertEquals("the two nearest", Arrays.asList(1, 2), pierced);

        CgHitList shared = new CgHitList();
        int[] shots = {0};
        CgHitbox.of(CgSphere.of(3)).at(0, 0.9, 0).hitList(shared).onHit(h -> shots[0]++).tick(w, 1);
        CgHitbox.of(CgSphere.of(3)).at(0, 0.9, 0).hitList(shared).onHit(h -> shots[0]++).tick(w, 1);
        assertEquals("a shared list hits each once between them", 3, shots[0]);
    }

    @Test
    public void aZoneHearsWhoComesAndGoes() {
        Table t = new Table().mob(1, 10, 0, KIND_MONSTER).mob(2, 1, 1, KIND_MONSTER);
        CgCollisionWorld w = world(t);
        List<String> events = new ArrayList<>();
        CgHitbox zone = CgHitbox.of(CgBox.of(2, 1, 2)).at(0, 1, 0)
                .onEnter(h -> events.add("in " + h.entity())).onExit(id -> events.add("out " + id));
        zone.tick(w, 1);
        t.move(1, 0, 0, 0);
        zone.tick(w, 1);
        zone.tick(w, 1);
        t.move(2, 10, 0, 10);
        zone.tick(w, 1);
        zone.stop();
        zone.tick(w, 1);
        assertEquals(Arrays.asList("in 2", "in 1", "out 2", "out 1"), events);
    }

    @Test
    public void aStartedHitboxIsTickedByTheEngineAndLeavesWhenDone() {
        CgCollisionWorld w = world(new Table().mob(1, 1, 0, KIND_MONSTER));
        int[] hits = {0};
        int running = CgHitboxes.running();
        CgHitbox blast = CgHitbox.of(CgSphere.of(3)).at(0, 0.9, 0).activeFrom(1).activeFor(2).rehit(0)
                .onHit(h -> hits[0]++).start(w);
        assertEquals(running + 1, CgHitboxes.running());
        for (int i = 0; i < 2; i++) CgHitboxes.tickThread();
        assertFalse(blast.stopped());
        CgHitboxes.tickThread();
        assertTrue(blast.stopped());
        assertEquals("active on ticks 2 and 3", 2, hits[0]);
        assertEquals(running, CgHitboxes.running());

        CgHitbox gone = CgHitbox.follow(99, CgEntityAttachments.FEET).part(null, CgSphere.of(1)).start(w);
        CgHitboxes.tickThread();
        assertTrue("its entity is gone", gone.stopped());
    }

    @Test
    public void aShieldIsMetBeforeItsOwnerAndStandsForThem() {
        // the player, 1, stands at z = 5 behind a shield at z = 4
        CgCollisionWorld w = world(new Table().mob(1, 0, 5, KIND_MONSTER));
        int[] heard = {0};
        CgHitbox shield = CgHitbox.of(SHIELD, CgBox.of(1, 1, 0.1)).at(0, 0.9, 4).source(1).hittable().passive()
                .onHit(h -> heard[0]++);
        shield.tick(w, 1);
        CgHit hit = new CgHit();

        assertTrue(w.ray().at(0, 0.9, 0).along(0, 0, 1, 10).first(hit));
        assertEquals(CgHit.Kind.HITBOX, hit.kind());
        assertEquals(1, hit.entity());
        assertSame(shield, hit.hitbox());
        assertSame(SHIELD, hit.hitboxPart());
        assertEquals("its front face", 3.9, hit.z(), 1e-9);
        assertTrue(w.ray().at(0, 0.9, 0).along(0, 0, 1, 10).noHitboxes().first(hit));
        assertEquals("passed through", CgHit.Kind.ENTITY, hit.kind());
        assertFalse("its owner's own queries never meet it",
                w.ray().at(0, 0.9, 0).along(0, 0, 1, 10).source(1).any());

        List<String> hits = new ArrayList<>();
        CgHitbox.of(CgSphere.of(3)).at(0, 0.9, 2).onHit(h -> hits.add(h.kind() + " " + h.entity())).tick(w, 1);
        assertEquals("the shield, nearer, counts its owner as hit", Arrays.asList("HITBOX 1"), hits);

        assertTrue(w.ray().at(0, 0.9, 0).along(0, 0, 1, 10).first(hit));
        CgHit sent = new CgSentHit().set(hit, -1).read(new CgHit(), null);
        assertEquals("over the network, its owner", "HITBOX 1", sent.kind() + " " + sent.entity());
        assertNull(sent.hitbox());

        CgCollisionWorld frozen = w.snapshot(0, 0, 0, 16);
        shield.stop();
        assertTrue(w.ray().at(0, 0.9, 0).along(0, 0, 1, 10).first(hit));
        assertEquals("stopped, it is met no more", CgHit.Kind.ENTITY, hit.kind());
        assertTrue(frozen.ray().at(0, 0.9, 0).along(0, 0, 1, 10).first(hit));
        assertEquals("the snapshot holds it as it was", CgHit.Kind.HITBOX, hit.kind());
        assertEquals("a passive hitbox hears nothing", 0, heard[0]);
    }

    @Test
    public void aHittableHitboxNeverMeetsItselfAndNeedsAnOwner() {
        CgCollisionWorld w = world(new Table().mob(2, 0, 0, KIND_MONSTER));
        List<String> hits = new ArrayList<>();
        CgHitbox aura = CgHitbox.follow(2, CgEntityAttachments.CENTRE).part(null, CgSphere.of(1)).hittable()
                .onHit(h -> hits.add(h.kind() + " " + h.entity()));
        aura.tick(w, 1);
        assertEquals("its owner's body, never its own", Arrays.asList("ENTITY 2"), hits);
        aura.stop();
        try {
            CgHitbox.of(CgSphere.of(1)).hittable().tick(w, 1);
            fail("a target with no owner");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("owner"));
        }
    }

    @Test
    public void aThousandHitboxesTickFastAndAllocateNothing() {
        Table crowd = new Table();
        Random random = new Random(7);
        for (int id = 0; id < 50; id++) {
            double a = random.nextDouble() * Math.PI * 2, r = random.nextDouble() * 9;
            crowd.mob(id, Math.cos(a) * r, Math.sin(a) * r, KIND_MONSTER);
        }
        CgCollisionWorld w = world(crowd);
        int[] hits = {0};
        CgHitbox[] boxes = new CgHitbox[1000];
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = CgHitbox.of(CgSphere.of(1.5)).rehit(4).query(CgQuery::living).onHit(h -> hits[0]++);
        }
        int ticks = 0;
        for (; ticks < 200; ticks++) tickAll(w, boxes, ticks);

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long quiet = Long.MAX_VALUE, best = Long.MAX_VALUE;
        int rounds = 50;
        for (int window = 0; window < 3; window++) {
            long before = threads.getThreadAllocatedBytes(thread);
            long t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) tickAll(w, boxes, ticks++);
            best = Math.min(best, System.nanoTime() - t0);
            quiet = Math.min(quiet, threads.getThreadAllocatedBytes(thread) - before);
        }
        long perTick = best / ((long) rounds * boxes.length);
        System.out.println("[collision] a moving hitbox among 50 entities: " + perTick + " ns a tick, " + quiet
                + " B over " + rounds * boxes.length + " ticks; " + hits[0] + " hits in all");
        assertTrue(hits[0] > 0);
        assertEquals("bytes over " + rounds * boxes.length + " hitbox ticks", 0, quiet);
        assertTrue("a hitbox tick took " + perTick + " ns", perTick < 3_000);
    }

    /** Each hitbox circles the crowd at its own phase, a block a tick. */
    private static void tickAll(CgCollisionWorld w, CgHitbox[] boxes, int tick) {
        for (int i = 0; i < boxes.length; i++) {
            double a = i * 0.37 + tick * 0.15, r = 2 + (i % 7);
            boxes[i].at(Math.cos(a) * r, 0.9, Math.sin(a) * r).tick(w, 1);
        }
    }
}
