package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static com.crystalgraphics.world.CgEntityProperties.*;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.IntConsumer;

import com.sun.management.ThreadMXBean;
import org.junit.Before;
import org.junit.Test;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;

/** The motion type's derivation: its events, timers and arm poses from scripted host facts: what every version's host feeds it. */
public class CgEntityMotionTest {

    private static final float DT = 0.05f;

    private final Script host = new Script();
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    /** A fresh entity per test: objects by id outlive a test. */
    private static int nextId = 20_000;
    private CgEntityMotion state;
    private CgNetObject motion;

    @Before
    public void setUp() {
        state = new CgEntityMotion();
        motion = CgEntityProperties.MOTION.of(nextId++);
        host.facts.clear();
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
    }

    private void step() {
        assertTrue(state.update(motion, host, CgWorldQuery.NONE, 7, 1f, DT, pose));
    }

    @Test
    public void firstUpdateRaisesNothing() {
        host.facts.hurtTime = 10;
        host.facts.bits |= CgEntityFacts.SWINGING | CgEntityFacts.USING;
        step();
        for (CgNetField<Boolean> event : List.of(JUMPED, LANDED, HURT, SWING_STARTED, USE_STARTED, USE_STOPPED,
                MOUNTED, DISMOUNTED, DIED)) {
            assertFalse(event.toString(), motion.getBool(event));
        }
    }

    @Test
    public void jumpAndLandFromTheHighestPoint() {
        host.facts.y = 64;
        step();
        host.facts.bits = CgEntityFacts.LIVING;
        host.facts.moveY = 0.42;
        host.facts.y = 64.42;
        step();
        assertTrue(motion.getBool(JUMPED));
        host.facts.y = 65.25;
        step();
        host.facts.moveY = -0.5;
        host.facts.y = 61;
        step();
        assertEquals(2 * DT, motion.getFloat(AIR_TIME), 1e-6);
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
        host.facts.y = 60;
        step();
        assertTrue(motion.getBool(LANDED));
        assertEquals(5.25f, motion.getFloat(LAND_HEIGHT), 1e-4);
        assertEquals(0f, motion.getFloat(AIR_TIME), 0f);
    }

    @Test
    public void walkingOffALedgeIsNoJump() {
        step();
        host.facts.bits = CgEntityFacts.LIVING;
        host.facts.moveY = -0.08;
        step();
        assertFalse(motion.getBool(JUMPED));
    }

    @Test
    public void hurtWhenItsCountdownRestarts() {
        step();
        host.facts.hurtTime = 10;
        step();
        assertTrue(motion.getBool(HURT));
        assertEquals(0f, motion.getFloat(HURT_TIME), 0f);
        host.facts.hurtTime = 9;
        step();
        assertFalse(motion.getBool(HURT));
        assertEquals(DT, motion.getFloat(HURT_TIME), 1e-6);
        host.facts.hurtTime = 10;
        step();
        assertTrue("a second hit before the first wore off", motion.getBool(HURT));
    }

    @Test
    public void swingStartsAndRestarts() {
        step();
        host.facts.bits |= CgEntityFacts.SWINGING;
        host.facts.swing = 0.1f;
        step();
        assertTrue(motion.getBool(SWING_STARTED));
        assertEquals("its hand, the same frame", MAIN, motion.getInt(SWING_HAND));
        host.facts.swing = 0.6f;
        step();
        assertFalse(motion.getBool(SWING_STARTED));
        host.facts.swing = 0.05f;
        step();
        assertTrue("clicked again mid-swing", motion.getBool(SWING_STARTED));
    }

    @Test
    public void useCountedWhereTheHostCannotSay() {
        step();
        host.facts.bits |= CgEntityFacts.USING;
        host.facts.useLength = 72000;
        host.facts.item[CgEntityFacts.MAIN] = 261;
        host.facts.use[CgEntityFacts.MAIN] = CgEntityFacts.USE_BOW;
        step();
        assertTrue(motion.getBool(USE_STARTED));
        assertEquals(0f, motion.getFloat(USE_TIME), 0f);
        step();
        step();
        assertEquals(2 * DT, motion.getFloat(USE_TIME), 1e-6);
        assertEquals(3600f, motion.getFloat(USE_LENGTH), 0f);
        assertEquals(CgEntityProperties.ARM_BOW, motion.getInt(MAIN_ARM));
        host.facts.bits &= ~CgEntityFacts.USING;
        step();
        assertTrue(motion.getBool(USE_STOPPED));
        assertEquals(0f, motion.getFloat(USE_TIME), 0f);
    }

    @Test
    public void useFromTheHostsTicks() {
        host.facts.bits |= CgEntityFacts.USING;
        host.facts.useTicks = 4;
        host.facts.useLength = 32;
        step();
        assertEquals((4 + 1f) / 20f, motion.getFloat(USE_TIME), 1e-6);
        host.facts.useTicks = 0;
        step();
        assertTrue("its ticks went back: a new use", motion.getBool(USE_STARTED));
        assertTrue(motion.getBool(USE_STOPPED));
    }

    @Test
    public void mountAndDismount() {
        step();
        host.facts.vehicle = 40;
        step();
        assertTrue(motion.getBool(MOUNTED));
        assertTrue(motion.getBool(RIDING));
        host.facts.vehicle = -1;
        step();
        assertTrue(motion.getBool(DISMOUNTED));
    }

    @Test
    public void diesOnce() {
        step();
        host.facts.bits |= CgEntityFacts.DEAD;
        host.facts.deathTime = 1;
        step();
        assertTrue(motion.getBool(DIED));
        host.facts.deathTime = 10;
        step();
        assertFalse(motion.getBool(DIED));
        assertEquals(0.5f, motion.getFloat(DEAD_TIME), 1e-6);
    }

    @Test
    public void armPosesFollowMinecraftsRule() {
        host.facts.item[CgEntityFacts.MAIN] = 1;
        host.facts.item[CgEntityFacts.OFF] = 2;
        host.facts.bits |= CgEntityFacts.CHARGED_MAIN;
        step();
        assertEquals(CgEntityProperties.ARM_CROSSBOW_HOLD, motion.getInt(MAIN_ARM));
        assertEquals("a two-handed pose leaves the other hand holding", CgEntityProperties.ARM_ITEM,
                motion.getInt(OFF_ARM));
        host.facts.bits |= CgEntityFacts.SWINGING;
        step();
        assertEquals("swinging lowers a charged crossbow", CgEntityProperties.ARM_ITEM, motion.getInt(MAIN_ARM));
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.USING;
        host.facts.useHand = CgEntityFacts.OFF;
        host.facts.use[CgEntityFacts.OFF] = CgEntityFacts.USE_BLOCK;
        step();
        assertEquals(CgEntityProperties.ARM_BLOCK, motion.getInt(OFF_ARM));
        assertEquals(CgEntityProperties.ARM_ITEM, motion.getInt(MAIN_ARM));
        host.facts.item[CgEntityFacts.OFF] = 0;
        step();
        assertEquals(CgEntityProperties.ARM_EMPTY, motion.getInt(OFF_ARM));
    }

    @Test
    public void swimAmountEasesAsMinecraftsDoes() {
        step();
        host.facts.bits |= CgEntityFacts.SWIM_POSE | CgEntityFacts.IN_WATER;
        step();
        assertEquals(0.09f, motion.getFloat(SWIM_AMOUNT), 1e-6);
        assertTrue(motion.getBool(SWIMMING));
        for (int i = 0; i < 20; i++) step();
        assertEquals(1f, motion.getFloat(SWIM_AMOUNT), 0f);
        host.facts.bits &= ~CgEntityFacts.IN_WATER;
        step();
        assertTrue(motion.getBool(CRAWLING));
    }

    @Test
    public void speedsInBlocksASecond() {
        host.facts.moveX = 0.3;
        host.facts.moveZ = 0.4;
        host.facts.moveY = -0.1;
        step();
        assertEquals(10f, motion.getFloat(SPEED), 1e-5);
        assertEquals(-2f, motion.getFloat(VERTICAL_SPEED), 1e-5);
    }

    @Test
    public void headAgainstBody() {
        pose[CgEntityQuery.BODY_YAW] = 170;
        pose[CgEntityQuery.HEAD_YAW] = -170;
        step();
        assertEquals(20f, motion.getFloat(HEAD_YAW), 1e-4);
    }

    @Test
    public void aFailedReadChangesNothing() {
        host.facts.item[CgEntityFacts.MAIN] = 5;
        host.facts.vehicle = 3;
        step();
        host.answers = false;
        assertFalse(state.update(motion, host, CgWorldQuery.NONE, 7, 1f, DT, pose));
        assertEquals(5, motion.getInt(MAIN_ITEM));
        assertEquals(3, motion.getInt(VEHICLE));
        host.answers = true;
        step();
        assertFalse(motion.getBool(MOUNTED));
    }

    @Test
    public void inputFromThePlayer() {
        host.facts.bits |= CgEntityFacts.INPUT | CgEntityFacts.INPUT_SPRINT;
        host.facts.inputStrafe = 1f;
        host.facts.inputForward = 1f;
        step();
        assertTrue(motion.getBool(INPUT_FROM_PLAYER));
        assertEquals("a diagonal is no faster than a straight", 1f, motion.getFloat(INPUT), 1e-6);
        assertEquals(45f, motion.getFloat(INPUT_DIRECTION), 1e-4);
        assertTrue(motion.getBool(SPRINT_HELD));
        assertFalse(motion.getBool(JUMP_HELD));
    }

    @Test
    public void intentReadFromMovement() {
        // yaw 90 faces -x (Minecraft's yaw 0 faces +z); moving -x is ahead, moving -z is to its right
        pose[CgEntityQuery.YAW] = 90;
        host.facts.moveX = -0.2;
        step();
        assertFalse(motion.getBool(INPUT_FROM_PLAYER));
        assertEquals(1f, motion.getFloat(INPUT), 1e-6);
        assertEquals(0f, motion.getFloat(INPUT_DIRECTION), 1e-3);
        host.facts.moveX = 0;
        host.facts.moveZ = -0.2;
        step();
        assertEquals(-90f, motion.getFloat(INPUT_DIRECTION), 1e-3);
        host.facts.bits |= CgEntityFacts.SNEAKING;
        step();
        assertEquals("sneaking, as Minecraft scales its input", 0.3f, motion.getFloat(INPUT), 1e-6);
        host.facts.moveZ = 0.001;
        step();
        assertEquals("friction's last drift is standing", 0f, motion.getFloat(INPUT), 0f);
    }

    private void step(float partialTick) {
        assertTrue(state.update(motion, host, CgWorldQuery.NONE, 7, partialTick, DT / 3, pose));
    }

    @Test
    public void speedIsSmoothBetweenTicks() {
        step(0.1f);
        host.facts.moveX = 0.3;                    // the next tick: from standing to 6 blocks a second
        step(0.05f);
        assertEquals("a tick in, still near the last tick's", 0.3f, motion.getFloat(SPEED), 1e-4);
        step(0.5f);
        assertEquals(3f, motion.getFloat(SPEED), 1e-4);
        step(0.95f);
        assertEquals(5.7f, motion.getFloat(SPEED), 1e-4);
        assertEquals(120f, motion.getFloat(ACCELERATION), 1e-3);
        step(0.2f);                                // the next tick, moving the same
        assertEquals(6f, motion.getFloat(SPEED), 1e-4);
        assertEquals(0f, motion.getFloat(ACCELERATION), 1e-4);
    }

    @Test
    public void aServerTicksEveryUpdate() {
        step();
        host.facts.moveZ = 0.2;
        step();
        assertEquals(4f, motion.getFloat(SPEED), 1e-5);
        host.facts.moveZ = 0.1;
        step();
        assertEquals(2f, motion.getFloat(SPEED), 1e-5);
        assertEquals(-40f, motion.getFloat(ACCELERATION), 1e-3);
    }

    @Test
    public void turnRateAndMoveDirection() {
        pose[CgEntityQuery.BODY_YAW] = 0;
        step();
        pose[CgEntityQuery.BODY_YAW] = -9;         // yaw falling: a turn to the left
        host.facts.moveX = 0.2;                    // +x is left of a body facing +z
        step();
        assertEquals(9f / DT, motion.getFloat(TURN_RATE), 1e-2);
        assertEquals(90f - 9f, motion.getFloat(MOVE_DIRECTION), 1e-3);
    }

    @Test
    public void groundDistance() {
        step();
        assertEquals(0f, motion.getFloat(GROUND_DISTANCE), 0f);
        host.facts.bits = CgEntityFacts.LIVING;
        step();
        assertEquals("no blocks to ask", Float.POSITIVE_INFINITY, motion.getFloat(GROUND_DISTANCE), 0f);
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
        host.facts.bits |= CgEntityFacts.SWINGING;
        for (int f = 0; f < 20000; f++) frame(f);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        int frames = 10000;
        for (int f = 0; f < frames; f++) frame(f);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes + " bytes over " + frames + " warm frames", bytes < frames);
    }

    /** One frame's update, the swing and the height varying. */
    private void frame(int f) {
        host.facts.swing = (f % 20) / 20f;
        host.facts.y = 64 + (f % 7) * 0.1;
        host.facts.hurtTime = f % 30 == 0 ? 10 : 0;
        state.update(motion, host, CgWorldQuery.NONE, 7, 1f, DT, pose);
    }
}
