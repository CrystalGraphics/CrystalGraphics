package com.crystalgraphics.vfx.particle.spawn;

import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

import java.util.Objects;

/**
 * An emitter's life cycle: how long a loop lasts, how many run, the wait before each, and what its live particles do
 * once it stops. Mirrors Niagara's Emitter State module with a Self life cycle ({@code FNiagaraEmitterStateData}), its
 * defaults included. Immutable: each call answers a copy. Bursts re-fire every loop and a rate's curve runs over each
 * loop's seconds ({@link CgVfxSpawnRate}, {@link CgVfxSpawnBurst}).
 *
 * <pre>{@code
 * CgVfxEmitter.builder("core").state(CgVfxEmitterState.infinite(2.9f)).burst(0f, 1)...   // a new core every 2.9 s
 * CgVfxEmitter.builder("sparks").state(CgVfxEmitterState.ONCE).burst(0f, 120)...         // one burst, ever
 * CgVfxEmitter.builder("smoke").state(CgVfxEmitterState.once(1.6f)).rate(400f)...        // 1.6 s of smoke
 * }</pre>
 *
 * More of it:
 *
 * <pre>{@code
 * CgVfxEmitterState.loops(3, 0.5f).delay(0.2f, 0.6f).recalculateDelayEachLoop()   // a random wait before each loop
 * CgVfxEmitterState.infinite(0.8f, 1.4f).recalculateDurationEachLoop()           // loops of uneven length
 * CgVfxEmitterState.infinite(1f).delay(0.5f).delayFirstLoopOnly()                // half a second late, then every second
 * CgVfxEmitterState.ONCE.inactiveResponse(CgVfxInactiveResponse.KILL)            // stop() takes its particles with it
 * }</pre>
 *
 * <ul>
 *   <li>Unset, an emitter is {@link #DEFAULT}: Niagara's, looping for ever, a loop a second. An emitter that plays once
 *       says so, or its bursts fire again every {@link #DEFAULT_DURATION} seconds.</li>
 *   <li>A duration or delay range is rolled once an instance starts, as Niagara's, and again each loop only when asked
 *       ({@link #recalculateDurationEachLoop}, {@link #recalculateDelayEachLoop}); from the instance's seed, so every
 *       client agrees.</li>
 *   <li>A burst after the loop's duration never fires; a rate runs only within each loop, never in its delay.</li>
 *   <li>A duration of {@code Float.POSITIVE_INFINITY} is Niagara's infinite Loop Duration Mode: the loop never ends,
 *       and a {@link CgVfxLoopBehavior#ONCE once} emitter of it finishes when its bursts have fired, if it has no rate.</li>
 * </ul>
 */
public final class CgVfxEmitterState {

    /** A loop's seconds when none is given: Niagara's ({@code LoopDuration}) and Cascade's ({@code EmitterDuration}). */
    public static final float DEFAULT_DURATION = 1f;

    /** Niagara's default: looping for ever, {@link #DEFAULT_DURATION} seconds a loop, its particles let finish. */
    public static final CgVfxEmitterState DEFAULT = infinite(DEFAULT_DURATION);

    /** One loop that never ends: its bursts fire once at their times, and a rate runs until stopped. */
    public static final CgVfxEmitterState ONCE = once(Float.POSITIVE_INFINITY);

    private final CgVfxLoopBehavior loopBehavior;
    private final int loopCount;
    private final float durationMin, durationMax, delayMin, delayMax;
    private final boolean recalculateDuration, delayFirstLoopOnly, recalculateDelay;
    private final CgVfxInactiveResponse inactiveResponse;

    private CgVfxEmitterState(CgVfxLoopBehavior loopBehavior, int loopCount, float durationMin, float durationMax,
                              boolean recalculateDuration, float delayMin, float delayMax, boolean delayFirstLoopOnly,
                              boolean recalculateDelay, CgVfxInactiveResponse inactiveResponse) {
        if (!(durationMin >= 0f && durationMax >= durationMin)) {
            throw new IllegalArgumentException("a loop of " + durationMin + " to " + durationMax + " s");
        }
        if (!(delayMin >= 0f && delayMax >= delayMin)) throw new IllegalArgumentException("a delay of " + delayMin + " to " + delayMax + " s");
        if (loopCount < 1) throw new IllegalArgumentException(loopCount + " loops");
        this.loopBehavior = loopBehavior;
        this.loopCount = loopCount;
        this.durationMin = durationMin;
        this.durationMax = durationMax;
        this.recalculateDuration = recalculateDuration;
        this.delayMin = delayMin;
        this.delayMax = delayMax;
        this.delayFirstLoopOnly = delayFirstLoopOnly;
        this.recalculateDelay = recalculateDelay;
        this.inactiveResponse = Objects.requireNonNull(inactiveResponse, "inactiveResponse");
    }

    private static CgVfxEmitterState of(CgVfxLoopBehavior behavior, int count, float min, float max) {
        return new CgVfxEmitterState(behavior, count, min, max, false, 0f, 0f, false, false, CgVfxInactiveResponse.COMPLETE);
    }

    /** One loop of {@code duration} seconds. */
    public static CgVfxEmitterState once(float duration) {
        return of(CgVfxLoopBehavior.ONCE, 1, duration, duration);
    }

    /** One loop of between {@code min} and {@code max} seconds, rolled when the instance starts. */
    public static CgVfxEmitterState once(float min, float max) {
        return of(CgVfxLoopBehavior.ONCE, 1, min, max);
    }

    /** {@code count} loops of {@code duration} seconds each. */
    public static CgVfxEmitterState loops(int count, float duration) {
        return of(CgVfxLoopBehavior.MULTIPLE, count, duration, duration);
    }

    /** {@code count} loops of between {@code min} and {@code max} seconds. */
    public static CgVfxEmitterState loops(int count, float min, float max) {
        return of(CgVfxLoopBehavior.MULTIPLE, count, min, max);
    }

    /** Loops of {@code duration} seconds until its instance is stopped. */
    public static CgVfxEmitterState infinite(float duration) {
        return of(CgVfxLoopBehavior.INFINITE, 1, duration, duration);
    }

    /** Loops of between {@code min} and {@code max} seconds until its instance is stopped. */
    public static CgVfxEmitterState infinite(float min, float max) {
        return of(CgVfxLoopBehavior.INFINITE, 1, min, max);
    }

    /** Rolls its duration range again for every loop: Niagara's {@code bRecalculateDurationEachLoop}. */
    public CgVfxEmitterState recalculateDurationEachLoop() {
        return new CgVfxEmitterState(loopBehavior, loopCount, durationMin, durationMax, true, delayMin, delayMax,
                delayFirstLoopOnly, recalculateDelay, inactiveResponse);
    }

    /** Waits {@code seconds} before each loop: Niagara's Loop Delay. */
    public CgVfxEmitterState delay(float seconds) {
        return delay(seconds, seconds);
    }

    /** Waits between {@code min} and {@code max} seconds before each loop, rolled when the instance starts. */
    public CgVfxEmitterState delay(float min, float max) {
        return new CgVfxEmitterState(loopBehavior, loopCount, durationMin, durationMax, recalculateDuration, min, max,
                delayFirstLoopOnly, recalculateDelay, inactiveResponse);
    }

    /** Waits its delay before the first loop alone: Niagara's {@code bDelayFirstLoopOnly}. */
    public CgVfxEmitterState delayFirstLoopOnly() {
        return new CgVfxEmitterState(loopBehavior, loopCount, durationMin, durationMax, recalculateDuration, delayMin,
                delayMax, true, recalculateDelay, inactiveResponse);
    }

    /** Rolls its delay range again before every loop: Niagara's {@code bRecalculateDelayEachLoop}. */
    public CgVfxEmitterState recalculateDelayEachLoop() {
        return new CgVfxEmitterState(loopBehavior, loopCount, durationMin, durationMax, recalculateDuration, delayMin,
                delayMax, delayFirstLoopOnly, true, inactiveResponse);
    }

    /** What its live particles do once it stops ({@link CgVfxInactiveResponse}); {@code COMPLETE} unless given. */
    public CgVfxEmitterState inactiveResponse(CgVfxInactiveResponse response) {
        return new CgVfxEmitterState(loopBehavior, loopCount, durationMin, durationMax, recalculateDuration, delayMin,
                delayMax, delayFirstLoopOnly, recalculateDelay, response);
    }

    public CgVfxLoopBehavior loopBehavior() {
        return loopBehavior;
    }

    /** How many loops a {@link CgVfxLoopBehavior#MULTIPLE} emitter runs; 1 otherwise. */
    public int loopCount() {
        return loopCount;
    }

    /** How many loops it runs in all: {@code Integer.MAX_VALUE} for {@link CgVfxLoopBehavior#INFINITE}. */
    public int loops() {
        return switch (loopBehavior) {
            case ONCE -> 1;
            case MULTIPLE -> loopCount;
            case INFINITE -> Integer.MAX_VALUE;
        };
    }

    /** The shortest a loop lasts, in seconds. */
    public float durationMin() {
        return durationMin;
    }

    /** The longest a loop lasts; {@code Float.POSITIVE_INFINITY} for a loop that never ends. */
    public float durationMax() {
        return durationMax;
    }

    public boolean recalculatesDurationEachLoop() {
        return recalculateDuration;
    }

    public float delayMin() {
        return delayMin;
    }

    public float delayMax() {
        return delayMax;
    }

    /** Whether only the first loop waits its delay ({@link #delayFirstLoopOnly()}). */
    public boolean delaysFirstLoopOnly() {
        return delayFirstLoopOnly;
    }

    public boolean recalculatesDelayEachLoop() {
        return recalculateDelay;
    }

    public CgVfxInactiveResponse inactiveResponse() {
        return inactiveResponse;
    }

    /**
     * How long loop {@code loop} (0 first) of an instance whose seed's bits are {@code seed} lasts: the same on every
     * side, so the books and every client agree.
     */
    public float durationOf(int seed, int loop) {
        if (durationMax == durationMin) return durationMin;
        // Negative particle indices: draws the kernel never makes.
        return durationMin + (durationMax - durationMin) * CgVfxEmitterInstance.rand(seed, -1 - (recalculateDuration ? loop : 0), 3);
    }

    /**
     * The delay before loop {@code loop} (0 first) of an instance whose seed's bits are {@code seed}: the same on every
     * side.
     *
     * <pre>{@code
     * float firstLoopStarts = state.delayBefore(instance.books().seedBits(), 0);
     * }</pre>
     */
    public float delayBefore(int seed, int loop) {
        if (delayMax == 0f || delayFirstLoopOnly && loop > 0) return 0f;
        if (delayMax == delayMin) return delayMin;
        return delayMin + (delayMax - delayMin) * CgVfxEmitterInstance.rand(seed, -1 - (recalculateDelay ? loop : 0), 0);
    }

    /** Seconds from one loop's start to the next's, at the least: what bounds how many loops overlap a particle's life. */
    public float shortestPeriod() {
        return durationMin + (delayFirstLoopOnly ? 0f : delayMin);
    }
}
