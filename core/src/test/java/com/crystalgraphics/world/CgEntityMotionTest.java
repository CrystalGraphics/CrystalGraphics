package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.function.IntConsumer;

import com.sun.management.ThreadMXBean;
import org.junit.Before;
import org.junit.Test;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;

/** The motion type's derivation: its events, timers and arm poses from scripted host facts: what every version's host feeds it. */
public class CgEntityMotionTest {

    private static final float DT = 0.05f;

    private final Script host = new Script();
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private CgEntityMotion state;

    @Before
    public void setUp() {
        state = new CgEntityMotion();
        host.facts.clear();
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
    }

    private void step() {
        assertTrue(state.update(host, CgWorldQuery.NONE, 7, 1f, DT, pose));
    }

    @Test
    public void firstUpdateRaisesNothing() {
        host.facts.hurtTime = 10;
        host.facts.bits |= CgEntityFacts.SWINGING | CgEntityFacts.USING;
        step();
        assertFalse(state.firedAny());
    }

    @Test
    public void jumpAndLandFromTheHighestPoint() {
        host.facts.y = 64;
        step();
        host.facts.bits = CgEntityFacts.LIVING;
        host.facts.moveY = 0.42;
        host.facts.y = 64.42;
        step();
        assertTrue(state.fired(CgEntityMotion.JUMPED_BIT));
        host.facts.y = 65.25;
        step();
        host.facts.moveY = -0.5;
        host.facts.y = 61;
        step();
        assertEquals(2 * DT, state.airSeconds(), 1e-6);
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
        host.facts.y = 60;
        step();
        assertTrue(state.fired(CgEntityMotion.LANDED_BIT));
        assertEquals(5.25f, state.landHeight(), 1e-4);
        assertEquals(0f, state.airSeconds(), 0f);
    }

    @Test
    public void walkingOffALedgeIsNoJump() {
        step();
        host.facts.bits = CgEntityFacts.LIVING;
        host.facts.moveY = -0.08;
        step();
        assertFalse(state.fired(CgEntityMotion.JUMPED_BIT));
    }

    @Test
    public void hurtWhenItsCountdownRestarts() {
        step();
        host.facts.hurtTime = 10;
        step();
        assertTrue(state.fired(CgEntityMotion.HURT_BIT));
        assertEquals(0f, state.secondsSinceHurt(), 0f);
        host.facts.hurtTime = 9;
        step();
        assertFalse(state.fired(CgEntityMotion.HURT_BIT));
        assertEquals(DT, state.secondsSinceHurt(), 1e-6);
        host.facts.hurtTime = 10;
        step();
        assertTrue("a second hit before the first wore off", state.fired(CgEntityMotion.HURT_BIT));
    }

    @Test
    public void swingStartsAndRestarts() {
        step();
        host.facts.bits |= CgEntityFacts.SWINGING;
        host.facts.swing = 0.1f;
        step();
        assertTrue(state.fired(CgEntityMotion.SWING_STARTED_BIT));
        assertEquals("its hand, the same frame", CgEntityProperties.MAIN, state.swingHand());
        host.facts.swing = 0.6f;
        step();
        assertFalse(state.fired(CgEntityMotion.SWING_STARTED_BIT));
        host.facts.swing = 0.05f;
        step();
        assertTrue("clicked again mid-swing", state.fired(CgEntityMotion.SWING_STARTED_BIT));
    }

    @Test
    public void useCountedWhereTheHostCannotSay() {
        step();
        host.facts.bits |= CgEntityFacts.USING;
        host.facts.useLength = 72000;
        host.facts.item[CgEntityFacts.MAIN] = 261;
        host.facts.use[CgEntityFacts.MAIN] = CgEntityFacts.USE_BOW;
        step();
        assertTrue(state.fired(CgEntityMotion.USE_STARTED_BIT));
        assertEquals(0f, state.useSeconds(), 0f);
        step();
        step();
        assertEquals(2 * DT, state.useSeconds(), 1e-6);
        assertEquals(3600f, state.useLength(), 0f);
        assertEquals(CgEntityProperties.ARM_BOW, state.armPose(CgEntityProperties.MAIN));
        host.facts.bits &= ~CgEntityFacts.USING;
        step();
        assertTrue(state.fired(CgEntityMotion.USE_STOPPED_BIT));
        assertEquals(0f, state.useSeconds(), 0f);
    }

    @Test
    public void useFromTheHostsTicks() {
        host.facts.bits |= CgEntityFacts.USING;
        host.facts.useTicks = 4;
        host.facts.useLength = 32;
        step();
        assertEquals((4 + 1f) / 20f, state.useSeconds(), 1e-6);
        host.facts.useTicks = 0;
        step();
        assertTrue("its ticks went back: a new use", state.fired(CgEntityMotion.USE_STARTED_BIT));
        assertTrue(state.fired(CgEntityMotion.USE_STOPPED_BIT));
    }

    @Test
    public void mountAndDismount() {
        step();
        host.facts.vehicle = 40;
        step();
        assertTrue(state.fired(CgEntityMotion.MOUNTED_BIT));
        assertTrue(state.riding());
        host.facts.vehicle = -1;
        step();
        assertTrue(state.fired(CgEntityMotion.DISMOUNTED_BIT));
    }

    @Test
    public void diesOnce() {
        step();
        host.facts.bits |= CgEntityFacts.DEAD;
        host.facts.deathTime = 1;
        step();
        assertTrue(state.fired(CgEntityMotion.DIED_BIT));
        host.facts.deathTime = 10;
        step();
        assertFalse(state.fired(CgEntityMotion.DIED_BIT));
        assertEquals(0.5f, state.secondsDead(), 1e-6);
    }

    @Test
    public void armPosesFollowMinecraftsRule() {
        host.facts.item[CgEntityFacts.MAIN] = 1;
        host.facts.item[CgEntityFacts.OFF] = 2;
        host.facts.bits |= CgEntityFacts.CHARGED_MAIN;
        step();
        assertEquals(CgEntityProperties.ARM_CROSSBOW_HOLD, state.armPose(CgEntityProperties.MAIN));
        assertEquals("a two-handed pose leaves the other hand holding", CgEntityProperties.ARM_ITEM,
                state.armPose(CgEntityProperties.OFF));
        host.facts.bits |= CgEntityFacts.SWINGING;
        step();
        assertEquals("swinging lowers a charged crossbow", CgEntityProperties.ARM_ITEM, state.armPose(CgEntityProperties.MAIN));
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.USING;
        host.facts.useHand = CgEntityFacts.OFF;
        host.facts.use[CgEntityFacts.OFF] = CgEntityFacts.USE_BLOCK;
        step();
        assertEquals(CgEntityProperties.ARM_BLOCK, state.armPose(CgEntityProperties.OFF));
        assertEquals(CgEntityProperties.ARM_ITEM, state.armPose(CgEntityProperties.MAIN));
        host.facts.item[CgEntityFacts.OFF] = 0;
        step();
        assertEquals(CgEntityProperties.ARM_EMPTY, state.armPose(CgEntityProperties.OFF));
    }

    @Test
    public void swimAmountEasesAsMinecraftsDoes() {
        step();
        host.facts.bits |= CgEntityFacts.SWIM_POSE | CgEntityFacts.IN_WATER;
        step();
        assertEquals(0.09f, state.swimAmount(), 1e-6);
        assertTrue(state.swimming());
        for (int i = 0; i < 20; i++) step();
        assertEquals(1f, state.swimAmount(), 0f);
        host.facts.bits &= ~CgEntityFacts.IN_WATER;
        step();
        assertTrue(state.crawling());
    }

    @Test
    public void speedsInBlocksASecond() {
        host.facts.moveX = 0.3;
        host.facts.moveZ = 0.4;
        host.facts.moveY = -0.1;
        step();
        assertEquals(10f, state.speed(), 1e-5);
        assertEquals(-2f, state.verticalSpeed(), 1e-5);
    }

    @Test
    public void headAgainstBody() {
        pose[CgEntityQuery.BODY_YAW] = 170;
        pose[CgEntityQuery.HEAD_YAW] = -170;
        step();
        assertEquals(20f, state.headYaw(), 1e-4);
    }

    @Test
    public void aFailedReadChangesNothing() {
        host.facts.item[CgEntityFacts.MAIN] = 5;
        host.facts.vehicle = 3;
        step();
        host.answers = false;
        assertFalse(state.update(host, CgWorldQuery.NONE, 7, 1f, DT, pose));
        assertEquals(5, state.item(CgEntityProperties.MAIN));
        assertEquals(3, state.vehicle());
        host.answers = true;
        step();
        assertFalse(state.fired(CgEntityMotion.MOUNTED_BIT));
    }

    @Test
    public void inputFromThePlayer() {
        host.facts.bits |= CgEntityFacts.INPUT | CgEntityFacts.INPUT_SPRINT;
        host.facts.inputStrafe = 1f;
        host.facts.inputForward = 1f;
        step();
        assertTrue(state.inputFromPlayer());
        assertEquals("a diagonal is no faster than a straight", 1f, state.input(), 1e-6);
        assertEquals(45f, state.inputDirection(), 1e-4);
        assertTrue(state.sprintHeld());
        assertFalse(state.jumpHeld());
    }

    @Test
    public void intentReadFromMovement() {
        // yaw 90 faces -x (Minecraft's yaw 0 faces +z); moving -x is ahead, moving -z is to its right
        pose[CgEntityQuery.YAW] = 90;
        host.facts.moveX = -0.2;
        step();
        assertFalse(state.inputFromPlayer());
        assertEquals(1f, state.input(), 1e-6);
        assertEquals(0f, state.inputDirection(), 1e-3);
        host.facts.moveX = 0;
        host.facts.moveZ = -0.2;
        step();
        assertEquals(-90f, state.inputDirection(), 1e-3);
        host.facts.bits |= CgEntityFacts.SNEAKING;
        step();
        assertEquals("sneaking, as Minecraft scales its input", 0.3f, state.input(), 1e-6);
        host.facts.moveZ = 0.001;
        step();
        assertEquals("friction's last drift is standing", 0f, state.input(), 0f);
    }

    private void step(float partialTick) {
        assertTrue(state.update(host, CgWorldQuery.NONE, 7, partialTick, DT / 3, pose));
    }

    @Test
    public void speedIsSmoothBetweenTicks() {
        step(0.1f);
        host.facts.moveX = 0.3;                    // the next tick: from standing to 6 blocks a second
        step(0.05f);
        assertEquals("a tick in, still near the last tick's", 0.3f, state.speed(), 1e-4);
        step(0.5f);
        assertEquals(3f, state.speed(), 1e-4);
        step(0.95f);
        assertEquals(5.7f, state.speed(), 1e-4);
        assertEquals(120f, state.acceleration(), 1e-3);
        step(0.2f);                                // the next tick, moving the same
        assertEquals(6f, state.speed(), 1e-4);
        assertEquals(0f, state.acceleration(), 1e-4);
    }

    @Test
    public void aServerTicksEveryUpdate() {
        step();
        host.facts.moveZ = 0.2;
        step();
        assertEquals(4f, state.speed(), 1e-5);
        host.facts.moveZ = 0.1;
        step();
        assertEquals(2f, state.speed(), 1e-5);
        assertEquals(-40f, state.acceleration(), 1e-3);
    }

    @Test
    public void turnRateAndMoveDirection() {
        pose[CgEntityQuery.BODY_YAW] = 0;
        step();
        pose[CgEntityQuery.BODY_YAW] = -9;         // yaw falling: a turn to the left
        host.facts.moveX = 0.2;                    // +x is left of a body facing +z
        step();
        assertEquals(9f / DT, state.turnRate(), 1e-2);
        assertEquals(90f - 9f, state.moveDirection(), 1e-3);
    }

    @Test
    public void groundDistance() {
        step();
        assertEquals(0f, state.groundDistance(), 0f);
        host.facts.bits = CgEntityFacts.LIVING;
        step();
        assertEquals("no blocks to ask", Float.POSITIVE_INFINITY, state.groundDistance(), 0f);
    }

    /** A host whose one entity does what the test sets. */
    private static final class Script implements CgEntityQuery {
        final CgEntityFacts facts = new CgEntityFacts();
        boolean answers = true;

        @Override
        public boolean facts(int id, float partialTick, CgEntityFacts out) {
            if (!answers) return false;
            out.bits = facts.bits;
            out.moveX = facts.moveX;
            out.moveY = facts.moveY;
            out.moveZ = facts.moveZ;
            out.y = facts.y;
            out.vehicle = facts.vehicle;
            out.hurtTime = facts.hurtTime;
            out.deathTime = facts.deathTime;
            out.swing = facts.swing;
            out.swingHand = facts.swingHand;
            out.useHand = facts.useHand;
            out.useTicks = facts.useTicks;
            out.useLength = facts.useLength;
            out.inputStrafe = facts.inputStrafe;
            out.inputForward = facts.inputForward;
            System.arraycopy(facts.item, 0, out.item, 0, 2);
            System.arraycopy(facts.use, 0, out.use, 0, 2);
            return true;
        }

        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }
        @Override public boolean pose(int id, float partialTick, double[] out) { return false; }
        @Override public int flags(int id) { return 0; }
        @Override public int kind(int id) { return KIND_OTHER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return -1; }
        @Override public int rootVehicle(int id) { return -1; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) { }
    }
    @Test
    public void aWarmFrameAllocatesNothing() {
        CgNetObject motion = CgEntityProperties.MOTION.of(9001);
        host.facts.bits |= CgEntityFacts.SWINGING;
        for (int f = 0; f < 20000; f++) frame(motion, f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) frame(motion, f);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }

    /** One frame's update and publish, the swing and the height varying. */
    private void frame(CgNetObject motion, int f) {
        host.facts.swing = (f % 20) / 20f;
        host.facts.y = 64 + (f % 7) * 0.1;
        host.facts.hurtTime = f % 30 == 0 ? 10 : 0;
        if (state.update(host, CgWorldQuery.NONE, 7, 1f, DT, pose)) state.publish(motion);
    }
}
