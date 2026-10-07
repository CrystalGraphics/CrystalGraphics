package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Events on the CPU path (plan vfx-gpu X5), the reference the GPU's children are held to: where children spawn, which
 * ids they take, how they launch about the event's normal, how many are alive at once, and when their parent finishes.
 */
public class CgVfxEmitterEventsTest {

    private static final float DT = 1f / 60f;
    private static final CgVfxAir STILL = new CgVfxAir().wind(0f, 0f, 0f);

    @Test
    public void landingsSpawnChildrenWhereTheParentLands() {
        CgVfxEmitter dust = CgVfxEmitter.builder("dust").life(0.5f, 0.8f).speed(0f, 0f).shape(0f).build();
        CgVfxEmitter debris = CgVfxEmitter.builder("debris").capacity(400).burst(0f, 300).life(3f, 4f).speed(3f, 9f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0f, 1f, 1000f))
                .event(CgVfxEvent.onLanding().spawn(dust, 3).readback(5)).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(debris, 0.43f).ground(0f);
        instance.start(0f, 1f, 0f);
        CgVfxEmitterInstance children = instance.child(0);
        int peak = debris.peakChildren(0), most = 0, delivered = 0, dropped = 0;
        Set<Integer> seen = new HashSet<>();
        CgVfxEventRows[] heard = new CgVfxEventRows[1];
        for (int step = 0; step < 300 && !instance.finished(); step++) {
            instance.tick(DT, STILL, 0.0, 0.0, 0.0);
            Map<Integer, Integer> parents = byId(instance.particles());
            CgVfxParticleSet p = instance.particles(), c = children.particles();
            for (int i = 0; i < c.count(); i++) {
                if (!seen.add(c.id[i])) continue;
                int parentId = c.id[i] >>> 8, n = c.id[i] & 31;
                assertEquals(CgVfxEvent.childKey(parentId, 0, n), c.id[i]);
                assertTrue(n < 3);
                Integer at = parents.get(parentId);
                assertTrue("a child of " + parentId + " whose parent is gone", at != null);
                assertEquals(1f, p.resting[at], 0f);
                assertEquals(p.x[at], c.x[i], 0f);
                assertEquals(0f, c.y[i], 0f);
                assertEquals(p.z[at], c.z[i], 0f);
            }
            most = Math.max(most, c.count());
            if (instance.rowsDue()) {
                instance.deliverRows(Collections.singletonList((definition, event, rows) -> {
                    assertEquals(debris, definition);
                    heard[0] = rows;
                }));
                assertTrue(heard[0].count() <= 5);
                delivered += heard[0].count();
                dropped += heard[0].dropped();
                assertFalse(instance.rowsDue());
            }
        }
        assertTrue(instance.finished());
        assertEquals(300 * 3, seen.size());
        assertEquals(300, delivered + dropped);
        assertTrue(most + " alive past the child slot's " + peak, most <= peak);
    }

    @Test
    public void deathLaunchesChildrenAboutTheParentsVelocity() {
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").life(1f, 1f).speed(2f, 2f).shape(0f).launch(1f, 1f, 1f).build();
        CgVfxEmitter embers = CgVfxEmitter.builder("embers").capacity(200).burst(0f, 100).life(0.2f, 0.4f).speed(5f, 5f)
                .launch(-0.6f, 0.6f, 1f).event(CgVfxEvent.onDeath().spawn(sparks, 2).inherit(0.5f)).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(embers, 0.19f);
        instance.start(0f, 0f, 0f);
        Map<Integer, float[]> velocity = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (int step = 0; step < 120; step++) {
            CgVfxParticleSet p = instance.particles();
            for (int i = 0; i < p.count(); i++) velocity.put(p.id[i], new float[] {p.vx[i], p.vy[i], p.vz[i]});
            instance.tick(DT, STILL, 0.0, 0.0, 0.0);
            CgVfxParticleSet c = instance.child(0).particles();
            for (int i = 0; i < c.count(); i++) {
                if (!seen.add(c.id[i])) continue;
                // Straight up in its own frame is along the normal, the parent's heading; then half the parent's velocity.
                float[] v = velocity.get(c.id[i] >>> 8);
                float speed = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                assertEquals(v[0] * (2f / speed + 0.5f), c.vx[i], 1e-4f);
                assertEquals(v[1] * (2f / speed + 0.5f), c.vy[i], 1e-4f);
                assertEquals(v[2] * (2f / speed + 0.5f), c.vz[i], 1e-4f);
            }
        }
        assertEquals(200, seen.size());
    }

    @Test
    public void aParentFinishesWithItsChildren() {
        CgVfxEmitter smoke = CgVfxEmitter.builder("smoke").life(1f, 1f).speed(0f, 1f).build();
        CgVfxEmitter flash = CgVfxEmitter.builder("flash").burst(0f, 20).life(0.2f, 0.2f)
                .event(CgVfxEvent.onAge(0.1f).spawn(smoke, 1)).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(flash, 0.5f);
        instance.start(0f, 0f, 0f);
        int parentGone = -1, finished = -1;
        Set<Integer> children = new HashSet<>();
        for (int step = 0; step < 200 && finished < 0; step++) {
            instance.tick(DT, STILL, 0.0, 0.0, 0.0);
            CgVfxParticleSet c = instance.child(0).particles();
            for (int i = 0; i < c.count(); i++) children.add(c.id[i]);
            if (parentGone < 0 && step > 0 && instance.particles().count() == 0) parentGone = step;
            if (instance.finished()) finished = step;
        }
        assertEquals(20, children.size());
        // Its own last particle dies at 0.2 s, its children a second after they spawned at 0.1 s.
        assertTrue("finished at " + finished + ", its own particles gone at " + parentGone, finished - parentGone >= 45);
    }

    @Test
    public void scheduledChildrenFinishNoEarlierThanTheCpusAndSoonAfter() {
        CgVfxEmitter dust = CgVfxEmitter.builder("dust").life(0.4f, 0.9f).speed(0f, 1f).build();
        CgVfxEmitter debris = CgVfxEmitter.builder("debris").capacity(400).burst(0f, 200).burst(0.3f, 100)
                .life(0.5f, 1.5f).speed(2f, 6f).module(new CgVfxModule.Gravity(9.8f))
                .event(CgVfxEvent.onDeath().spawn(dust, 2)).build();
        CgVfxEmitterInstance cpu = new CgVfxEmitterInstance(debris, 0.8f), gpu = new CgVfxEmitterInstance(debris, 0.8f);
        cpu.start(0f, 0f, 0f);
        gpu.start(0f, 0f, 0f);
        int cpuDone = -1, gpuDone = -1;
        for (int step = 0; step < 400 && (cpuDone < 0 || gpuDone < 0); step++) {
            cpu.tick(DT, STILL, 0.0, 0.0, 0.0);
            gpu.schedule(DT, 0.0, 0.0, 0.0);
            assertTrue(gpu.child(0).scheduled());
            if (cpuDone < 0 && cpu.finished()) cpuDone = step;
            if (gpuDone < 0 && gpu.finished()) gpuDone = step;
        }
        assertTrue("gpu " + gpuDone + " before cpu " + cpuDone, gpuDone >= cpuDone);
        assertTrue("gpu " + gpuDone + " long after cpu " + cpuDone, gpuDone - cpuDone <= 40);
    }

    private static Map<Integer, Integer> byId(CgVfxParticleSet p) {
        Map<Integer, Integer> at = new HashMap<>();
        for (int i = 0; i < p.count(); i++) at.put(p.id[i], i);
        return at;
    }
}
