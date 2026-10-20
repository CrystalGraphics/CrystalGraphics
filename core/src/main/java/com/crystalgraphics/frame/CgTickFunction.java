package com.crystalgraphics.frame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One piece of work a world runs each frame, in a {@link CgTickGroup}: Unreal's {@code FTickFunction}. Made by
 * {@link CgTickSchedule#add}; the flags and prerequisites are set on it afterwards.
 *
 * <pre>{@code
 * CgTickFunction seq = world.schedule().add(CgTickGroup.SEQUENCES, "mymod:sequencer", this::evaluate);
 * CgTickFunction fx = world.schedule().add(CgTickGroup.EFFECTS, "mymod:effects", this::step)
 *         .after(seq)                 // a prerequisite: never "who registered first"
 *         .whilePaused(true);         // still runs while the game is paused
 *
 * CgTickFunction autosave = world.schedule().add(CgTickGroup.POSE, "mymod:autosave", dt -> save())
 *         .interval(30);              // at most once in 30 seconds, given the seconds since it last ran
 * autosave.close();                   // stops it
 * }</pre>
 *
 * <ul>
 *   <li>A name is unique in its world and reads {@code mod:function}: it breaks ties inside a group, and a function
 *       that throws is disabled and logged under it.</li>
 *   <li>{@link #after} may name a function of a later group; this one then runs in that group, after it (Unreal
 *       demotes it the same way). A cycle is logged and its last edge ignored.</li>
 *   <li>The body receives the seconds since it last ran, on the frame clock; 0 the first time.</li>
 * </ul>
 */
public final class CgTickFunction {

    /** What a tick function runs. {@code dt} is the seconds since it last ran. */
    @FunctionalInterface
    public interface Body {
        void tick(float dt);
    }

    private final CgTickSchedule schedule;
    private final String name;
    private final CgTickGroup group;
    private final Body body;
    private final List<CgTickFunction> prerequisites = new ArrayList<>();

    private double interval;
    private boolean whilePaused, onServer, workers;

    // Written by the schedule on the frame thread.
    boolean disabled, closed;
    double lastRun = Double.NaN;
    double due = Double.NaN;
    double seen = Double.NaN;
    CgTickGroup effective;
    int zoneId, groupZoneId;

    CgTickFunction(CgTickSchedule schedule, CgTickGroup group, String name, Body body) {
        this.schedule = schedule;
        this.group = group;
        this.name = name;
        this.body = body;
    }

    /** {@code mod:function}, unique in its world. */
    public String name() {
        return name;
    }

    /** The group it was added to. */
    public CgTickGroup group() {
        return group;
    }

    /** The group it runs in: its own, or the latest of its prerequisites'. Valid once the schedule has run. */
    public CgTickGroup effectiveGroup() {
        return effective != null ? effective : group;
    }

    /** Runs only after {@code other} has run this frame. Several may be named. */
    public CgTickFunction after(CgTickFunction other) {
        if (other == this) throw new IllegalArgumentException("a tick function cannot follow itself: " + name);
        if (other.schedule != schedule) throw new IllegalArgumentException(other.name + " belongs to another world");
        synchronized (schedule) {
            if (!prerequisites.contains(other)) prerequisites.add(other);
            schedule.changed();
        }
        return this;
    }

    /** At most once in {@code seconds}; its body then receives the seconds since it last ran. 0 runs it every frame. */
    public CgTickFunction interval(double seconds) {
        if (!(seconds >= 0)) throw new IllegalArgumentException("interval " + seconds);
        interval = seconds;
        return this;
    }

    /** Whether it also runs while the game is paused. Off by default. */
    public CgTickFunction whilePaused(boolean on) {
        whilePaused = on;
        return this;
    }

    /** Whether it also runs in a server's world. Off by default: presentation never runs there. */
    public CgTickFunction onServer(boolean on) {
        onServer = on;
        return this;
    }

    /** Declares that its body fans out to {@code CgJob.forEach} and joins before it returns. */
    public CgTickFunction workers(boolean on) {
        workers = on;
        return this;
    }

    public double interval() {
        return interval;
    }

    public boolean whilePaused() {
        return whilePaused;
    }

    public boolean onServer() {
        return onServer;
    }

    public boolean workers() {
        return workers;
    }

    /** Whether it threw and was disabled for its world. */
    public boolean disabled() {
        return disabled;
    }

    /** Takes it out of its world's schedule. Others that named it as a prerequisite no longer wait for it. */
    public void close() {
        schedule.remove(this);
    }

    List<CgTickFunction> prerequisites() {
        return Collections.unmodifiableList(prerequisites);
    }

    void tick(float dt) {
        body.tick(dt);
    }

    @Override
    public String toString() {
        return name + "@" + effectiveGroup();
    }
}
