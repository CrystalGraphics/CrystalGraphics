package com.crystalgraphics.vfx.particle.spawn;

import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

import java.util.Objects;

/**
 * An emitter's life cycle: how long a loop lasts, how many run, the wait before each, and what its live particles do
 * once it stops. Mirrors Niagara's Emitter State module with a Self life cycle. Immutable: each call answers a copy.
 * Bursts re-fire every loop and a rate's curve runs over each loop's seconds ({@link CgVfxSpawnRate},
 * {@link CgVfxSpawnBurst}).
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
 * CgVfxEmitterState.loops(3, 0.5f).delay(0.2f, 0.6f)           // three half-second loops, each after a random wait
 * CgVfxEmitterState.infinite(1f).delay(0.5f).delayFirstLoopOnly()   // starts half a second late, then every second
 * CgVfxEmitterState.ONCE.inactiveResponse(CgVfxInactiveResponse.KILL)   // stop() takes its particles with it
 * }</pre>
 *
 * <ul>
 *   <li>Unset, an emitter is {@link #DEFAULT}: Niagara's, looping for ever. An emitter that plays once says so, or its
 *       bursts fire again every {@link #DEFAULT_DURATION} seconds.</li>
 *   <li>A delay range is picked anew each loop, from the instance's seed and the loop's number: every client agrees.</li>
 *   <li>A burst after the loop's duration never fires; a rate runs only within each loop, never in its delay.</li>
 *   <li>A duration of {@code Float.POSITIVE_INFINITY} is Niagara's infinite Loop Duration Mode: the loop never ends,
 *       and a {@link CgVfxLoopBehavior#ONCE once} emitter of it finishes when its bursts have fired, if it has no rate.</li>
 * </ul>
 */
public final class CgVfxEmitterState {

    /** A loop's seconds when none is given. Epic's reference gives Niagara's none: to be checked (aaa-design §2.14). */
    public static final float DEFAULT_DURATION = 2f;

    /** Niagara's default: looping for ever, {@link #DEFAULT_DURATION} seconds a loop, its particles let finish. */
    public static final CgVfxEmitterState DEFAULT = infinite(DEFAULT_DURATION);

    /** One loop that never ends: its bursts fire once at their times, and a rate runs until stopped. */
    public static final CgVfxEmitterState ONCE = once(Float.POSITIVE_INFINITY);

    private final CgVfxLoopBehavior loopBehavior;
    private final int loopCount;
    private final float loopDuration, delayMin, delayMax;
    private final boolean delayFirstLoopOnly;
    private final CgVfxInactiveResponse inactiveResponse;

    private CgVfxEmitterState(CgVfxLoopBehavior loopBehavior, int loopCount, float loopDuration, float delayMin,
                              float delayMax, boolean delayFirstLoopOnly, CgVfxInactiveResponse inactiveResponse) {
        if (!(loopDuration >= 0f)) throw new IllegalArgumentException("a loop of " + loopDuration + " s");
        if (!(delayMin >= 0f && delayMax >= delayMin)) throw new IllegalArgumentException("a delay of " + delayMin + " to " + delayMax + " s");
        if (loopCount < 1) throw new IllegalArgumentException(loopCount + " loops");
        this.loopBehavior = loopBehavior;
        this.loopCount = loopCount;
        this.loopDuration = loopDuration;
        this.delayMin = delayMin;
        this.delayMax = delayMax;
        this.delayFirstLoopOnly = delayFirstLoopOnly;
        this.inactiveResponse = Objects.requireNonNull(inactiveResponse, "inactiveResponse");
    }

    /** One loop of {@code duration} seconds. */
    public static CgVfxEmitterState once(float duration) {
        return new CgVfxEmitterState(CgVfxLoopBehavior.ONCE, 1, duration, 0f, 0f, false, CgVfxInactiveResponse.COMPLETE);
    }

    /** {@code count} loops of {@code duration} seconds each. */
    public static CgVfxEmitterState loops(int count, float duration) {
        return new CgVfxEmitterState(CgVfxLoopBehavior.MULTIPLE, count, duration, 0f, 0f, false, CgVfxInactiveResponse.COMPLETE);
    }

    /** Loops of {@code duration} seconds until its instance is stopped. */
    public static CgVfxEmitterState infinite(float duration) {
        return new CgVfxEmitterState(CgVfxLoopBehavior.INFINITE, 1, duration, 0f, 0f, false, CgVfxInactiveResponse.COMPLETE);
    }

    /** Waits {@code seconds} before each loop: Niagara's Loop Delay. */
    public CgVfxEmitterState delay(float seconds) {
        return delay(seconds, seconds);
    }

    /** Waits between {@code min} and {@code max} seconds before each loop, picked anew each loop: Cascade's delay range. */
    public CgVfxEmitterState delay(float min, float max) {
        return new CgVfxEmitterState(loopBehavior, loopCount, loopDuration, min, max, delayFirstLoopOnly, inactiveResponse);
    }

    /** Waits its delay before the first loop alone: Cascade's {@code bDelayFirstLoopOnly}. */
    public CgVfxEmitterState delayFirstLoopOnly() {
        return new CgVfxEmitterState(loopBehavior, loopCount, loopDuration, delayMin, delayMax, true, inactiveResponse);
    }

    /** What its live particles do once it stops ({@link CgVfxInactiveResponse}); {@code COMPLETE} unless given. */
    public CgVfxEmitterState inactiveResponse(CgVfxInactiveResponse response) {
        return new CgVfxEmitterState(loopBehavior, loopCount, loopDuration, delayMin, delayMax, delayFirstLoopOnly, response);
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

    /** Seconds a loop lasts; {@code Float.POSITIVE_INFINITY} for a loop that never ends. */
    public float loopDuration() {
        return loopDuration;
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

    public CgVfxInactiveResponse inactiveResponse() {
        return inactiveResponse;
    }

    /**
     * The delay before loop {@code loop} (0 first) of an instance whose seed's bits are {@code seed}: the same on every
     * side, so the books and every client agree.
     *
     * <pre>{@code
     * float firstLoopStarts = state.delayBefore(instance.books().seedBits(), 0);
     * }</pre>
     */
    public float delayBefore(int seed, int loop) {
        if (delayMax == 0f || delayFirstLoopOnly && loop > 0) return 0f;
        // Negative particle indices: draws the kernel never makes.
        return delayMin + (delayMax - delayMin) * CgVfxEmitterInstance.rand(seed, -1 - loop, 0);
    }

    /** Seconds from one loop's start to the next's, at the least: what bounds how many loops overlap a particle's life. */
    public float shortestPeriod() {
        return loopDuration + (delayFirstLoopOnly ? 0f : delayMin);
    }
}
