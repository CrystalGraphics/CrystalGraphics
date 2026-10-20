package com.crystalgraphics.vfx;

import com.crystalgraphics.trace.CgTrace;

import java.util.Arrays;
import java.util.List;

/**
 * One definition's playing instances in a system, booked together in {@code EFFECTS}: Unreal's
 * {@code FNiagaraSystemSimulation}, one per system asset per world ({@code NiagaraWorldManager.cpp:719-742}). Its
 * instances are an array, swap-removed as they leave; each tick it books the due ones in a loop with the definition
 * read once, across simulations on the workers past the system's threshold. The instances themselves step in the
 * system's play order, which captures depend on. Package-private: it books through the effects' own booking calls.
 *
 * <pre>{@code
 * CgVfxSystemSimulation sim = simulations.computeIfAbsent(effect.definitionKey(), CgVfxSystemSimulation::new);
 * sim.add(effect);
 * sim.due(effect, booking);          // each tick it queued emitter ticks
 * sim.book(0, Integer.MAX_VALUE);    // its due instances' books
 * sim.clearDue();
 * if (sim.remove(effect)) simulations.remove(sim.key());
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, but {@link #book}, which a worker may run on its own batch of due instances.</li>
 * </ul>
 */
final class CgVfxSystemSimulation {

    private static final int EFFECT_EMITTERS_ZONE = CgTrace.name("vfx.effect.emitters");

    private final Object key;
    private CgVfxEffect<?>[] instances = new CgVfxEffect<?>[4];
    private int count;
    private CgVfxEffect<?>[] due = new CgVfxEffect<?>[4];
    private int dueCount;

    CgVfxSystemSimulation(Object key) {
        this.key = key;
    }

    /** The definition it simulates: a system's definition, or the look of an effect with code. */
    Object key() {
        return key;
    }

    /** How many instances play in it. */
    int instances() {
        return count;
    }

    /** Instance {@code i}, in no fixed order. */
    CgVfxEffect<?> instance(int i) {
        return instances[i];
    }

    /** How many are due this tick. */
    int dueCount() {
        return dueCount;
    }

    void add(CgVfxEffect<?> effect) {
        if (count == instances.length) instances = Arrays.copyOf(instances, count * 2);
        effect.simulationSlot = count;
        instances[count++] = effect;
    }

    /** Takes {@code effect} out, the last moved into its place; true when it was the last. */
    boolean remove(CgVfxEffect<?> effect) {
        int at = effect.simulationSlot;
        CgVfxEffect<?> last = instances[--count];
        instances[at] = last;
        last.simulationSlot = at;
        instances[count] = null;
        effect.simulationSlot = -1;
        return count == 0;
    }

    /** Queues {@code effect}'s books this tick; the first of a tick also queues the simulation on {@code booking}. */
    void due(CgVfxEffect<?> effect, List<CgVfxSystemSimulation> booking) {
        if (dueCount == due.length) due = Arrays.copyOf(due, dueCount * 2);
        if (dueCount == 0) booking.add(this);
        due[dueCount++] = effect;
    }

    /** Books up to {@code count} due instances from {@code from}; batches of one simulation touch disjoint instances. */
    void book(int from, int count) {
        int to = Math.min(dueCount, from + count);
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, EFFECT_EMITTERS_ZONE)) {
            for (int i = from; i < to; i++) {
                CgVfxEffect<?> effect = due[i];
                try {
                    effect.tickEmitters();
                } catch (RuntimeException e) {
                    effect.failure = e;
                }
            }
        }
    }

    /** Forgets this tick's queue, whether or not it was booked. */
    void clearDue() {
        Arrays.fill(due, 0, dueCount, null);
        dueCount = 0;
    }
}
