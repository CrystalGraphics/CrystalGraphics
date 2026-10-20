package com.crystalgraphics.easing;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/** T9: a curve over life evaluated at 256 ages, as a module's books read one per particle. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CgCurveBench {

    private static final int AGES = 256;

    private final CgKeyframes opacity = CgKeyframes.start(0f, 0f).to(0.08f, 1f, CgEasings.OUT_QUAD)
            .to(0.3f, 0.85f, CgEasings.LINEAR).to(0.6f, 0.9f, CgEasings.IN_OUT_SINE).to(0.85f, 0.4f, CgEasings.IN_QUAD)
            .to(1f, 0f, CgEasings.IN_CUBIC).build();
    private final float[] ages = new float[AGES];

    public CgCurveBench() {
        for (int i = 0; i < AGES; i++) ages[i] = (i * 0.618034f) % 1f;
    }

    @Benchmark
    @OperationsPerInvocation(AGES)
    public float evaluate() {
        float sum = 0f;
        for (int i = 0; i < AGES; i++) sum += opacity.at(ages[i]);
        return sum;
    }
}
