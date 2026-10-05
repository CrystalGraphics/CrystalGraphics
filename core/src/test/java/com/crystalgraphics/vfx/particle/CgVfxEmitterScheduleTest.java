package com.crystalgraphics.vfx.particle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
