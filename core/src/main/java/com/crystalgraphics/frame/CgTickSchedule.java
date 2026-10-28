package com.crystalgraphics.frame;

import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A world's tick functions, run in {@link CgTickGroup} order each frame. Reached through {@link CgWorld#schedule()}.
 *
 * <pre>{@code
 * CgTickSchedule schedule = world.schedule();
 * CgTickFunction seq = schedule.add(CgTickGroup.SEQUENCES, "mymod:sequencer", dt -> players.evaluate(dt));
 * schedule.add(CgTickGroup.EFFECTS, "mymod:effects", dt -> effects.step(dt)).after(seq);
 * }</pre>
 *
 * <p>Mirrors Unreal's tick task manager ({@code FTickTaskManager::QueueTickFunction}, {@code RunTickGroup}): a
 * function runs in the latest group among its own and its prerequisites', after its prerequisites; a prerequisite
 * cycle is reported and ignored. Within a group, functions with nothing between them run in name order, so the order
 * never depends on who registered first.</p>
 *
 * <ul>
 *   <li>A function that throws is disabled for the world and logged once, with its name; the rest run on.</li>
 *   <li>Each group is a trace zone {@code frame.<group>}, each function a zone {@code frame.<group>/<name>} inside
 *       it.</li>
 *   <li>It adds and removes from any thread, and a function may add or close others from its body: the change takes
 *       effect from the next frame.</li>
 * </ul>
 */
public final class CgTickSchedule {

    private static final Logger LOGGER = LogManager.getLogger("CgWorld");
    private static final CgTickFunction[] NONE = new CgTickFunction[0];
    private static final int[] GROUP_ZONES = new int[CgTickGroup.values().length];
    private static final Comparator<CgTickFunction> BY_NAME = new Comparator<CgTickFunction>() {
        @Override public int compare(CgTickFunction a, CgTickFunction b) {
            return a.name().compareTo(b.name());
        }
    };

    static {
        for (CgTickGroup group : CgTickGroup.values()) GROUP_ZONES[group.ordinal()] = CgTrace.name(group.zone());
    }

    private final CgWorld world;
    private final List<CgTickFunction> all = new ArrayList<>();
    private final Set<String> reportedCycles = new HashSet<>();
    private volatile CgTickFunction[][] plan;

    CgTickSchedule(CgWorld world) {
        this.world = world;
    }

    /**
     * Adds a function to {@code group}. {@code name} is {@code mod:function} and unique in the world.
     *
     * @throws IllegalArgumentException for a name without a mod, or one already in use
     */
    public synchronized CgTickFunction add(CgTickGroup group, String name, CgTickFunction.Body body) {
        if (group == null || body == null) throw new IllegalArgumentException("group and body are required");
        if (name == null || name.indexOf(':') < 1 || name.endsWith(":")) {
            throw new IllegalArgumentException("a tick function is named mod:function, not '" + name + "'");
        }
        for (CgTickFunction f : all) {
            if (f.name().equals(name)) throw new IllegalArgumentException("tick function " + name + " exists");
        }
        CgTickFunction function = new CgTickFunction(this, group, name, body);
        all.add(function);
        plan = null;
        return function;
    }

    /** How many functions it holds. */
    public synchronized int size() {
        return all.size();
    }

    /** Every function in the order it runs this frame. For tools and tests. */
    public List<CgTickFunction> order() {
        List<CgTickFunction> out = new ArrayList<>();
        for (CgTickFunction[] group : plan()) Collections.addAll(out, group);
        return out;
    }

    synchronized void changed() {
        plan = null;
    }

    synchronized void remove(CgTickFunction function) {
        if (all.remove(function)) {
            function.closed = true;
            plan = null;
        }
    }

    /** Runs every group once, in order. */
    void run() {
        run(CgTickGroup.BEGIN, CgTickGroup.SUBMIT);
    }

    /** The groups {@code from} through {@code to}, in order, on the frame clock. */
    void run(CgTickGroup from, CgTickGroup to) {
        run(from, to, CgFrameClock.seconds());
    }

    /** The groups {@code from} through {@code to}, in order, at {@code now} seconds on the clock they run by. */
    void run(CgTickGroup from, CgTickGroup to, double now) {
        CgTickFunction[][] groups = plan();
        boolean paused = world.paused(), server = world.server();
        for (int g = from.ordinal(); g <= to.ordinal(); g++) {
            CgTickFunction[] group = groups[g];
            if (group.length == 0) continue;
            long mark = CgTrace.allocationBegin(CgChannels.WORLD);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, group[0].groupZoneId)) {
                for (CgTickFunction f : group) runOne(f, now, paused, server);
            }
            CgTrace.allocationEnd(CgChannels.WORLD, group[0].groupZoneId, mark);
        }
    }

    private void runOne(CgTickFunction f, double now, boolean paused, boolean server) {
        if (f.disabled || f.closed || server && !f.onServer()) return;
        double last = f.lastRun;
        boolean first = Double.isNaN(last);
        double gap = Double.isNaN(f.seen) ? 0 : now - f.seen;
        f.seen = now;
        if (paused && !f.whilePaused()) {
            f.lastRun = now;
            f.due += gap;                        // game time counts the cooldown, so a pause does not
            return;
        }
        if (now < f.due) return;
        f.lastRun = now;
        if (f.interval() > 0) f.due = (Double.isNaN(f.due) ? now : f.due) + f.interval();   // credit an overrun, as FTickTaskLevel does
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, f.zoneId)) {
            f.tick(first ? 0f : (float) (now - last));
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            f.disabled = true;
            LOGGER.error("Tick function {} threw and is disabled for this world", f.name(), t);
        }
    }

    private CgTickFunction[][] plan() {
        CgTickFunction[][] built = plan;
        if (built != null) return built;
        synchronized (this) {
            if (plan == null) plan = build();
            return plan;
        }
    }

    /** Each function's group is the latest among its own and its prerequisites'; then each group is sorted. */
    private CgTickFunction[][] build() {
        List<CgTickFunction> byName = new ArrayList<>(all);
        Collections.sort(byName, BY_NAME);

        // Depth-first from each function in name order: a back edge is a cycle, and that wait is dropped.
        Map<CgTickFunction, Integer> state = new IdentityHashMap<>();
        Map<CgTickFunction, Set<CgTickFunction>> ignored = new IdentityHashMap<>();
        for (CgTickFunction f : byName) resolve(f, state, ignored);

        int groups = CgTickGroup.values().length;
        List<List<CgTickFunction>> buckets = new ArrayList<>(groups);
        for (int i = 0; i < groups; i++) buckets.add(new ArrayList<CgTickFunction>());
        for (CgTickFunction f : byName) buckets.get(f.effective.ordinal()).add(f);

        CgTickFunction[][] out = new CgTickFunction[groups][];
        for (int g = 0; g < groups; g++) {
            out[g] = buckets.get(g).isEmpty() ? NONE : sort(buckets.get(g), ignored);
            for (CgTickFunction f : out[g]) {
                f.groupZoneId = GROUP_ZONES[g];
                f.zoneId = CgTrace.name(f.effective.zone() + "/" + f.name());
            }
        }
        return out;
    }

    private void resolve(CgTickFunction f, Map<CgTickFunction, Integer> state,
                         Map<CgTickFunction, Set<CgTickFunction>> ignored) {
        Integer seen = state.get(f);
        if (seen != null && seen == 2) return;
        state.put(f, 1);
        CgTickGroup latest = f.group();
        List<CgTickFunction> prerequisites = new ArrayList<>(f.prerequisites());
        Collections.sort(prerequisites, BY_NAME);
        for (CgTickFunction p : prerequisites) {
            if (p.closed) continue;
            Integer s = state.get(p);
            if (s != null && s == 1) {
                Set<CgTickFunction> set = ignored.get(f);
                if (set == null) ignored.put(f, set = Collections.newSetFromMap(new IdentityHashMap<CgTickFunction, Boolean>()));
                set.add(p);
                if (reportedCycles.add(f.name() + ">" + p.name())) {
                    LOGGER.warn("Tick functions {} and {} wait for each other; {}'s wait for {} is ignored",
                            f.name(), p.name(), f.name(), p.name());
                }
                continue;
            }
            resolve(p, state, ignored);
            if (p.effective.compareTo(latest) > 0) latest = p.effective;
        }
        f.effective = latest;
        state.put(f, 2);
    }

    /** A topological order of one group, the ready function with the smallest name first. */
    private static CgTickFunction[] sort(List<CgTickFunction> bucket, Map<CgTickFunction, Set<CgTickFunction>> ignored) {
        Map<CgTickFunction, Integer> waiting = new IdentityHashMap<>();
        Map<CgTickFunction, List<CgTickFunction>> after = new IdentityHashMap<>();
        for (CgTickFunction f : bucket) {
            int count = 0;
            Set<CgTickFunction> skip = ignored.get(f);
            for (CgTickFunction p : f.prerequisites()) {
                if (p.closed || p.effective != f.effective || skip != null && skip.contains(p)) continue;
                count++;
                List<CgTickFunction> list = after.get(p);
                if (list == null) after.put(p, list = new ArrayList<>());
                list.add(f);
            }
            waiting.put(f, count);
        }
        PriorityQueue<CgTickFunction> ready = new PriorityQueue<>(bucket.size(), BY_NAME);
        for (CgTickFunction f : bucket) if (waiting.get(f) == 0) ready.add(f);
        CgTickFunction[] out = new CgTickFunction[bucket.size()];
        int n = 0;
        while (!ready.isEmpty()) {
            CgTickFunction f = ready.poll();
            out[n++] = f;
            List<CgTickFunction> list = after.get(f);
            if (list == null) continue;
            for (CgTickFunction next : list) {
                int left = waiting.get(next) - 1;
                waiting.put(next, left);
                if (left == 0) ready.add(next);
            }
        }
        if (n != out.length) throw new IllegalStateException("tick functions still wait after cycles were cut");
        return out;
    }
}
