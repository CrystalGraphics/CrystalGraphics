package com.crystalgraphics.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.camera.CgCameraRigDefinition;

/**
 * The transient blend stack: Gameplay Cameras' {@code FTransientBlendStackCameraNodeEvaluator}. A rig pushed blends in
 * over the pose of everything below it. Once its blend is full and finished, what is below is popped.
 *
 * <pre>{@code
 * CgCameraBlendStack stack = new CgCameraBlendStack();
 * stack.push(null, host);                 // the host's own camera: a context with no rig
 * stack.push(skill, move);                // blends in by skill's enter transition, or cuts
 * stack.run(dt, pose);                    // each frame: pose is the stack's result
 * }</pre>
 *
 * <ul>
 *   <li>Pushing the rig already on top, for the same context, does nothing.</li>
 *   <li>The transition is the leaving rig's first exit, then the arriving rig's first enter; with neither, a cut.</li>
 *   <li>A rig's entry runs each frame from the pose blended below it, its nodes applied, and its blend weighs it over
 *       that pose. A context's own pose (a null rig) is its {@link CgCameraContext#initial()}.</li>
 * </ul>
 */
public final class CgCameraBlendStack {

    /** One rig blending in: its context, its blend, and its result this frame. */
    static final class Entry {
        @Nullable
        final CgCameraRig rig;
        final CgCameraContext context;
        final CgCameraBlend.Running blend;
        final CgCameraPose result = new CgCameraPose();
        boolean first = true;

        Entry(@Nullable CgCameraRig rig, CgCameraContext context, CgCameraBlend.Running blend) {
            this.rig = rig;
            this.context = context;
            this.blend = blend;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final CgCameraRig.Run run = new CgCameraRig.Run();

    /**
     * Pushes {@code rig} for {@code context}, or the context's own pose for a null rig. Returns whether it was pushed:
     * the same rig and context on top is not.
     */
    public boolean push(@Nullable CgCameraRig rig, CgCameraContext context) {
        Entry top = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        if (top != null && top.rig == rig && top.context == context) return false;
        CgCameraBlend blend = top == null ? CgCameraBlend.POP : transition(top.rig, rig);
        CgCameraBlend.Running running = blend.start(blend.time() == null ? 0f : seconds(blend.time(), top, rig));
        // A fixed-time blend broken off part-way: the new one takes that share of its time.
        if (top != null && !top.blend.finished()) running.interrupting(top.blend);
        entries.add(new Entry(rig, context, running));
        return true;
    }

    /** {@code FindTransition}: the leaving rig's exits, then the arriving rig's enters. */
    private static CgCameraBlend transition(@Nullable CgCameraRig from, @Nullable CgCameraRig to) {
        if (from != null) {
            for (CgCameraRigDefinition.Transition t : from.definition().exitTransitions()) {
                if (t.to() == null || (t.to().equals("host") && to == null)) return t.blend();
            }
        }
        if (to != null) {
            for (CgCameraRigDefinition.Transition t : to.definition().enterTransitions()) {
                if (t.to() == null) return t.blend();
            }
        }
        return CgCameraBlend.POP;
    }

    /** A blend's time: a number, or a binding read from the rig whose transition it is. */
    private static float seconds(Object time, @Nullable Entry top, @Nullable CgCameraRig to) {
        if (time instanceof Number n) return n.floatValue();
        if (time instanceof Map<?, ?> m && m.get("bind") instanceof String bind && bind.startsWith("rig.")) {
            String name = bind.substring(4);
            for (CgCameraRig r : new CgCameraRig[] {top == null ? null : top.rig, to}) {
                if (r == null) continue;
                for (CgCameraRigDefinition.Parameter p : r.definition().parameters()) {
                    if (p.name().equals(name) && r.parameter(name) instanceof Number v) return v.floatValue();
                }
            }
        }
        throw new IllegalArgumentException("a blend time of " + time + ": a number or { bind: rig.<name> }");
    }

    /**
     * One frame: each entry's blend advanced by {@code dt}, its pose run, and blended over {@code out}'s, which ends as
     * the stack's result. Entries under one fully blended in are popped.
     */
    public void run(float dt, CgCameraPose out) {
        int popBelow = -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            e.blend.advance(dt);
            // InternalUpdate: a rig runs from the pose blended below it; a context with none is its own pose.
            if (e.rig == null || i == 0) e.result.set(e.context.initial());
            else e.result.set(out);
            if (e.rig != null) {
                run.context = e.context;
                run.dt = dt;
                run.first = e.first;
                run.rig = e.rig;
                e.rig.run(run, e.result);
            }
            e.first = false;
            if (i == 0) out.set(e.result);
            else out.lerp(e.result, e.blend.factor());
            if (e.blend.full() && e.blend.finished()) popBelow = i;
        }
        for (int i = 0; i < popBelow; i++) entries.remove(0);
    }

    /** The rig on top, or null for none or a context's own pose. */
    @Nullable
    public CgCameraRig top() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1).rig;
    }

    /**
     * How much of the stack's result {@code owner}'s contexts make this frame: each entry its factor times what the
     * entries above it leave, summed; 0 for none.
     */
    public float weightOf(Object owner) {
        float above = 1f, sum = 0f;
        for (int i = entries.size() - 1; i >= 0 && above > 0f; i--) {
            Entry e = entries.get(i);
            float f = i == 0 ? 1f : e.blend.factor();
            if (e.context.owner() == owner) sum += above * f;
            above *= 1f - f;
        }
        return sum;
    }

    /** How many entries are blending or covered. */
    public int size() {
        return entries.size();
    }

    /** The top entry's blend factor this frame; 1 with none. */
    public float topFactor() {
        return entries.isEmpty() ? 1f : entries.get(entries.size() - 1).blend.factor();
    }
}
