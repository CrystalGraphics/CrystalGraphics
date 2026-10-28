package com.crystalgraphics.frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.platform.world.CgWorldBegin;

import static org.junit.Assert.assertEquals;

public class CgWorldBeginTest {

    private static List<String> ran(CgWorld world) {
        List<String> ran = new ArrayList<>();
        for (CgTickGroup g : CgTickGroup.values()) world.schedule().add(g, "test:" + g.name().toLowerCase(), dt -> ran.add(g.name()));
        return ran;
    }

    @Test
    public void theCameraHookRunsTheFrameUpToItsShakesOnceAndTheStageTheRest() {
        CgWorld world = new CgWorld();
        List<String> ran = ran(world);
        world.begin(null, 7);
        world.begin(null, 7);   // the hand's camera, a second pass: nothing more
        assertEquals(List.of("BEGIN", "PLAYS", "SEQUENCES", "POSE", "ATTACH", "CAMERA", "SCALABILITY", "EFFECTS", "SHAKE"), ran);
        ran.clear();
        world.frame(null, 7);
        assertEquals(List.of("LATE_ATTACH", "SUBMIT"), ran);
        assertEquals(0, world.beganLate());
    }

    @Test
    public void aFiringWithNoHookThisFrameRunsEverythingLateAndCountsIt() {
        CgWorld world = new CgWorld();
        List<String> ran = ran(world);
        world.begin(null, 1);
        world.frame(null, 1);
        ran.clear();
        world.frame(null, 2);   // frame 2's hook never ran
        assertEquals(Arrays.stream(CgTickGroup.values()).filter(g -> !g.tick()).count(), ran.size());
        assertEquals("BEGIN", ran.get(0));
        assertEquals("SUBMIT", ran.get(ran.size() - 1));
        assertEquals(CgWorldBegin.isAtStage() ? 0 : 1, world.beganLate());
    }

    @Test
    public void aShakeStartedByAnEffectIsSummedInTheSameFrame() {
        CgWorld world = new CgWorld();
        int[] started = {0}, summed = {-1};
        world.schedule().add(CgTickGroup.EFFECTS, "test:blast", dt -> started[0]++);
        world.schedule().add(CgTickGroup.SHAKE, "test:shake", dt -> summed[0] = started[0]);
        world.begin(null, 3);
        assertEquals("the frame's own shake, before the host takes its camera", 1, summed[0]);
    }

    @Test
    public void aGameTickRunsTheTickGroupsOnTheTickClockAndAServerOnlyItsOwn() {
        CgWorld client = new CgWorld();
        List<String> ran = ran(client);
        client.tick();
        assertEquals(List.of("NET_IN", "GAMEPLAY", "AUTHORITY", "NET_OUT"), ran);

        CgWorld server = new CgWorld(true);
        float[] dt = {-1};
        int[] runs = {0, 0, 0};
        server.schedule().add(CgTickGroup.AUTHORITY, "test:referee", d -> { runs[0]++; dt[0] = d; }).onServer(true);
        server.schedule().add(CgTickGroup.GAMEPLAY, "test:client-only", d -> runs[1]++);
        server.schedule().add(CgTickGroup.EFFECTS, "test:frame", d -> runs[2]++).onServer(true);
        for (int i = 0; i < 3; i++) server.tick();
        assertEquals(3, runs[0]);
        assertEquals((float) CgWorld.TICK_SECONDS, dt[0], 1e-6f);
        assertEquals("a function that did not ask never runs on a server", 0, runs[1]);
        assertEquals("a tick runs no frame group", 0, runs[2]);
        assertEquals(0, server.frames());
        assertEquals(3, server.ticks());
    }
}
