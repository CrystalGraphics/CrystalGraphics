package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The GPU path's schedule against the CPU path it stands for (vfx-gpu §13.7): an instance stepped by {@code schedule}
 * queues exactly the spawns one stepped by {@code tick} makes, finishes within a step or two of the CPU's last particle
 * dying, and never has more alive than its definition's {@code peakAlive}.
 */
public class CgVfxEmitterScheduleTest {

    private static final float DT = 1f / 60f;

    @Test
    public void burstsScheduleTheSpawnsTickMakes() {
        check(CgVfxEmitter.builder("bursts").capacity(4000).burst(0f, 300).burst(0.5f, 200).burst(2.4f, 120)
                .life(1f, 2f).speed(2f, 6f).module(new CgVfxModule.Gravity(9.8f)).build(), 1f);
    }

    @Test
    public void ratesAndBurstsThinnedByShare() {
        check(CgVfxEmitter.builder("rate").capacity(4000).rate(400f, 0f, 1.5f).burst(0.2f, 50)
                .life(0.5f, 1.2f).speed(1f, 3f).module(new CgVfxModule.Drag(2f, 0f)).build(), 0.6f);
    }

    @Test
    public void groundedParticlesStillDieOfAge() {
        check(CgVfxEmitter.builder("debris").capacity(4000).burst(0f, 400).life(2f, 3.5f).speed(4f, 12f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f)).build(), 1f);
    }

    @Test
    public void anOpenRateFinishesOnceStoppedOrCoasting() {
        CgVfxEmitter wake = CgVfxEmitter.builder("wake").capacity(400).rate(60f, 0f, Float.POSITIVE_INFINITY)
                .life(0.5f, 1f).build();
        CgVfxEmitterInstance stopped = new CgVfxEmitterInstance(wake, 0.3f), coasting = new CgVfxEmitterInstance(wake, 0.3f);
        stopped.start(0f, 0f, 0f);
        coasting.start(0f, 0f, 0f);
        for (int i = 0; i < 120; i++) {
            stopped.schedule(DT, 0.0, 0.0, 0.0);
            coasting.schedule(DT, 0.0, 0.0, 0.0);
        }
        assertFalse(stopped.finished());
        stopped.stop();
        for (int i = 0; i < 90; i++) {
            stopped.schedule(DT, 0.0, 0.0, 0.0);
            coasting.coast(DT);
            assertEquals(0, stopped.stepCandidates());
        }
        assertTrue(stopped.finished());
        assertTrue(coasting.finished());
    }

    @Test
    public void rateScaleScalesTheRateAlone() {
        CgVfxEmitter definition = CgVfxEmitter.builder("scaled").capacity(4000).rate(120f, 0f, Float.POSITIVE_INFINITY)
                .burst(0f, 10).life(1f, 1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(definition, 0.3f).rateScale(0f);
        instance.start(0f, 0f, 0f);
        instance.schedule(DT, 0.0, 0.0, 0.0);
        assertEquals(10, instance.stepCandidates());
        int spawned = 0;
        instance.rateScale(3f);
        for (int i = 0; i < 60; i++) {
            instance.schedule(DT, 0.0, 0.0, 0.0);
            spawned += instance.stepCandidates();
        }
        assertEquals(360, spawned, 1);
    }

    @Test
    public void childrenStepAtTheirParentsOrigin() {
        CgVfxEmitter flash = CgVfxEmitter.builder("flash").capacity(64).life(0.1f, 0.1f).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").capacity(64).burst(0f, 10).life(1f, 1f)
                .event(CgVfxEvent.onCollision().spawn(flash, 1)).build();
        CgVfxEmitterInstance parent = new CgVfxEmitterInstance(sparks, 0.5f);
        parent.start(0f, 7f, 0f);
        parent.schedule(DT, -21.0, 0.0, 14.0);
        CgVfxEmitterInstance child = parent.child(0);
        assertTrue(child.scheduled());
        assertEquals(-21.0, child.originX(), 0.0);
        assertEquals(14.0, child.originZ(), 0.0);
    }

    @Test
    public void curveRowSamplesSizeThenOpacity() {
        CgVfxEmitter definition = CgVfxEmitter.builder("curves")
                .size(CgKeyframes.start(0f, 0.5f).to(1f, 2f, CgEasings.LINEAR).build()).build();
        float[] row = new float[2 + 2 * CgVfxGpuEmitter.CURVE_TEXELS];
        definition.writeCurves(row, 2, CgVfxGpuEmitter.CURVE_TEXELS);
        for (int i = 0; i < CgVfxGpuEmitter.CURVE_TEXELS; i++) {
            float progress = i / (CgVfxGpuEmitter.CURVE_TEXELS - 1f);
            assertEquals(definition.sizeAt(progress), row[2 + 2 * i], 0f);
            assertEquals(definition.opacityAt(progress), row[3 + 2 * i], 0f);
        }
        assertEquals(0.5f, row[2], 0f);
        assertEquals(2f, row[row.length - 2], 1e-6f);
    }

    @Test
    public void noParticleGetsPastItsReach() {
        CgVfxAir air = new CgVfxAir().wind(3f, 0f, -2f).gusts(0.6f, 0.4f);
        CgVfxEmitter[] definitions = {
                CgVfxEmitter.builder("falling").capacity(2000).burst(0f, 600).life(1f, 2.5f).speed(2f, 9f).shape(0.4f)
                        .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Wind(2f)).build(),
                CgVfxEmitter.builder("rising").capacity(2000).rate(300f, 0f, 1f).life(1.5f, 3f).speed(0.5f, 2f).heat(1f)
                        .module(new CgVfxModule.Buoyancy(16f, 0.9f)).module(new CgVfxModule.Turbulence(8f, 0.12f, 0.6f))
                        .module(new CgVfxModule.Updraft(12f, 2f, 6f, 1.5f)).module(new CgVfxModule.Wind(1f)).build()};
        for (CgVfxEmitter definition : definitions) {
            CgVfxEmitterInstance instance = new CgVfxEmitterInstance(definition, 0.37f);
            instance.start(0.5f, 1f, -0.25f);
            float reach = definition.reach(air.maxSpeed()), far = 0f;
            for (int step = 0; step < 400 && !instance.finished(); step++) {
                air.tick(step * DT);
                instance.tick(DT, air, 120.0, 70.0, -40.0);
                CgVfxParticleSet p = instance.particles();
                for (int i = 0; i < p.count(); i++) {
                    float dx = p.x[i] - 0.5f, dy = p.y[i] - 1f, dz = p.z[i] + 0.25f;
                    far = Math.max(far, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
            assertTrue(definition.name() + " reached " + far + " past its reach " + reach, far <= reach);
        }
    }

    private static void check(CgVfxEmitter definition, float share) {
        CgVfxEmitterInstance cpu = new CgVfxEmitterInstance(definition, 0.71f).share(share);
        CgVfxEmitterInstance gpu = new CgVfxEmitterInstance(definition, 0.71f).share(share);
        cpu.start(0f, 0f, 0f);
        gpu.start(0f, 0f, 0f);
        cpu.ground(-1f);
        CgVfxAir air = new CgVfxAir();
        int peak = 0, cpuFinished = -1, gpuFinished = -1;
        for (int step = 0; step < 600 && (cpuFinished < 0 || gpuFinished < 0); step++) {
            int before = cpu.spawnedSoFar();
            cpu.tick(DT, air, 10.0, 64.0, -5.0);
            gpu.schedule(DT, 10.0, 64.0, -5.0);
            assertEquals(definition.name() + " step " + step + "'s first spawn", before, gpu.stepFirstSpawn());
            assertEquals(definition.name() + " step " + step + "'s candidates", cpu.spawnedSoFar() - before,
                    gpu.stepCandidates());
            peak = Math.max(peak, cpu.particles().count());
            if (cpuFinished < 0 && cpu.finished()) cpuFinished = step;
            if (gpuFinished < 0 && gpu.finished()) gpuFinished = step;
            assertEquals(0, gpu.particles().count());
        }
        assertTrue(definition.name() + " finished on the CPU", cpuFinished >= 0);
        assertTrue(definition.name() + " finished at step " + gpuFinished + " for the CPU's " + cpuFinished,
                gpuFinished >= cpuFinished && gpuFinished <= cpuFinished + 2);
        assertTrue(definition.name() + " had " + peak + " alive, past peakAlive " + definition.peakAlive(),
                peak <= definition.peakAlive());
    }
}
