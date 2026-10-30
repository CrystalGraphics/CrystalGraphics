package com.crystalgraphics.frame;

import com.crystalgraphics.render.CgFrameClock;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CgTickScheduleTest {

    private final CgWorld world = new CgWorld();
    private final List<String> ran = new ArrayList<>();

    private CgTickFunction add(CgTickGroup group, String name) {
        return world.schedule().add(group, name, dt -> ran.add(name));
    }

    private void frame(double at) {
        CgFrameClock.set(at);
        world.frame(null);
    }

    @Test
    public void groupsRunInOrderAndTiesBreakByName() {
        add(CgTickGroup.SUBMIT, "t:s");
        add(CgTickGroup.PLAYS, "t:b");
        add(CgTickGroup.PLAYS, "t:a");
        add(CgTickGroup.SEQUENCES, "t:q");
        frame(1);
        assertEquals(java.util.Arrays.asList("t:a", "t:b", "t:q", "t:s"), ran);
    }

    @Test
    public void prerequisiteRunsFirstAndDemotesTheGroup() {
        CgTickFunction late = add(CgTickGroup.EFFECTS, "t:late");
        CgTickFunction early = add(CgTickGroup.PLAYS, "t:early").after(late);
        frame(1);
        assertEquals(CgTickGroup.EFFECTS, early.effectiveGroup());
        assertEquals(java.util.Arrays.asList("t:late", "t:early"), ran);
    }

    @Test
    public void aCycleIsIgnoredAndBothStillRun() {
        CgTickFunction a = add(CgTickGroup.PLAYS, "t:a");
        CgTickFunction b = add(CgTickGroup.PLAYS, "t:b").after(a);
        a.after(b);
        frame(1);
        assertEquals(2, ran.size());
    }

    @Test
    public void aThrowingFunctionIsDisabledOthersContinue() {
        CgTickFunction bad = world.schedule().add(CgTickGroup.PLAYS, "t:bad", dt -> {
            ran.add("t:bad");
            throw new IllegalStateException("boom");
        });
        add(CgTickGroup.PLAYS, "t:ok");
        frame(1);
        frame(2);
        assertTrue(bad.disabled());
        assertEquals(java.util.Arrays.asList("t:bad", "t:ok", "t:ok"), ran);
    }

    @Test
    public void intervalAndDeltaAreSecondsSinceItLastRan() {
        List<Float> dts = new ArrayList<>();
        world.schedule().add(CgTickGroup.PLAYS, "t:slow", dts::add).interval(0.5);
        frame(1);
        frame(1.2);
        frame(1.6);
        assertEquals(2, dts.size());
        assertEquals(0f, dts.get(0), 0f);
        assertEquals(0.6f, dts.get(1), 1e-4f);
    }

    @Test
    public void aStallReachesFunctionsAsOneSlowFrame() {
        List<Float> dts = new ArrayList<>();
        world.schedule().add(CgTickGroup.PLAYS, "t:play", dts::add);
        frame(1);
        frame(5);          // a 4 s hitch: Unreal's MaxUndilatedFrameTime
        frame(5.25);
        assertEquals(CgWorld.MAX_FRAME_SECONDS, dts.get(1), 1e-6f);
        assertEquals(0.25f, dts.get(2), 1e-6f);
    }

    @Test
    public void anOverrunIsCreditedAndAPauseDoesNotConsumeTheCooldown() {
        List<Float> dts = new ArrayList<>();
        boolean[] paused = {false};
        world.paused(() -> paused[0]);
        world.schedule().add(CgTickGroup.PLAYS, "t:slow", dts::add).interval(0.5);
        frame(1);          // runs, due 1.5
        frame(1.25);
        frame(1.6);        // runs 0.1 late, due 2.0 not 2.1
        frame(1.9);
        assertEquals(2, dts.size());
        frame(2.0);
        assertEquals(3, dts.size());
        paused[0] = true;  // due 2.5; 10 s paused push it to 12.5
        for (int i = 1; i <= 40; i++) frame(2.0 + 0.25 * i);
        paused[0] = false;
        frame(12.4);
        assertEquals(3, dts.size());
        frame(12.5);
        assertEquals(4, dts.size());
    }

    @Test
    public void pausedWorldSkipsAllButWhilePaused() {
        world.paused(() -> true);
        add(CgTickGroup.PLAYS, "t:gameplay");
        add(CgTickGroup.PLAYS, "t:always").whilePaused(true);
        frame(1);
        assertEquals(java.util.Collections.singletonList("t:always"), ran);
    }

    @Test
    public void serverWorldRunsOnlyWhatAsked() {
        CgWorld server = new CgWorld(true);
        List<String> out = new ArrayList<>();
        server.schedule().add(CgTickGroup.PLAYS, "t:client", dt -> out.add("client"));
        server.schedule().add(CgTickGroup.PLAYS, "t:server", dt -> out.add("server")).onServer(true);
        CgFrameClock.set(1);
        server.frame(null);
        assertEquals(java.util.Collections.singletonList("server"), out);
    }

    @Test
    public void closedFunctionStopsAndDuplicateNameIsRefused() {
        CgTickFunction f = add(CgTickGroup.PLAYS, "t:once");
        frame(1);
        f.close();
        frame(2);
        assertEquals(1, ran.size());
        add(CgTickGroup.PLAYS, "t:dup");
        try {
            add(CgTickGroup.PLAYS, "t:dup");
            fail();
        } catch (IllegalArgumentException | IllegalStateException expected) {
        }
    }

    @Test
    public void aPlayQueuedBySequencesStartsInTheSameFrame() {
        List<String> started = new ArrayList<>();
        List<String> queue = new ArrayList<>();
        world.schedule().add(CgTickGroup.SEQUENCES, "t:seq", dt -> queue.add("fx"));
        world.schedule().add(CgTickGroup.EFFECTS, "t:fx", dt -> {
            started.addAll(queue);
            queue.clear();
        });
        frame(1);
        assertEquals(java.util.Collections.singletonList("fx"), started);
    }

    @Test
    public void subsystemsInitializeInOrderAndDeinitializeReversed() {
        List<String> log = new ArrayList<>();
        world.add(new CgWorldSubsystem() {
            @Override protected void initialize() { log.add("init1"); }
            @Override protected void deinitialize() { log.add("end1"); }
        });
        CgWorldSubsystem second = world.add(new CgWorldSubsystem() {
            @Override protected void initialize() { log.add("init2"); }
            @Override protected void deinitialize() { log.add("end2"); }
        });
        assertSame(world, second.world());
        assertSame(second, world.subsystem(second.getClass()));
        world.close();
        assertEquals(java.util.Arrays.asList("init1", "init2", "end2", "end1"), log);
    }
}
