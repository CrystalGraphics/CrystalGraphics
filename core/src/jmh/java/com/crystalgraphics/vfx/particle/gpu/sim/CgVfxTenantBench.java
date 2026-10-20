package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * T9: the VFX update's tenant loop over 64 effects of two GPU emitters each, with no GPU: every effect's tick, its
 * emitters' books, their admission into GPU slots, and each pool's queued steps taken as the world's recording takes
 * them. Range's layout and the frame's tenant walk need the binding points, so a GL context: not here.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CgVfxTenantBench {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("bench:tenants")).build();
    private static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("bench_sparks").capacity(512).rate(200f)
            .life(0.4f, 0.8f).speed(1f, 4f).module(new CgVfxModule.Gravity(9.8f)).build();
    private static final CgVfxEmitter SMOKE = CgVfxEmitter.builder("bench_smoke").capacity(256).ratePerBlock(4f)
            .life(1f, 2f).module(new CgVfxModule.Buoyancy(2f, 0.5f)).build();

    static final class Fountain extends CgVfxEffect<Fountain> {
        final CgVfxEmitterInstance sparks = new CgVfxEmitterInstance(SPARKS, 0.21f);
        final CgVfxEmitterInstance smoke = new CgVfxEmitterInstance(SMOKE, 0.73f);

        Fountain(double x) {
            super(LOOK, x, 64, 0);
            sparks.start(0f, 1f, 0f);
            smoke.start(0f, 0f, 0f);
        }

        @Override
        protected void tick(float dt) {
            smoke.moveTo((float) Math.sin(age * 3f) * 2f, 0f, 0f);
            tick(sparks, dt);
            tick(smoke, dt);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    private CgVfxSystem vfx;
    private List<CgVfxParticlePool> pools;
    private double clock;

    @Setup(Level.Trial)
    public void play() {
        vfx = new CgVfxSystem();
        pools = CgVfxParticlePool.pools();
        for (int i = 0; i < 64; i++) vfx.play(new Fountain(i * 3));
        for (int f = 0; f < 120; f++) updateTenants();
    }

    @TearDown(Level.Trial)
    public void clear() {
        vfx.delete();
    }

    /** One frame: 64 effects' ticks, 128 emitters' books and admission, then the pools' steps taken. */
    @Benchmark
    public void updateTenants() {
        clock += 1.0 / 60.0;
        vfx.update(clock);
        for (int p = 0; p < pools.size(); p++) pools.get(p).takeSteps();
    }
}
