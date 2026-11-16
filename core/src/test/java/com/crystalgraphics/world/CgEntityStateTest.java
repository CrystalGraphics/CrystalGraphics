package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.function.IntConsumer;
import org.junit.Before;
import org.junit.Test;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;

/** The state's events, timers and arm poses from scripted host facts: what every version's host feeds it. */
public class CgEntityStateTest {

    private static final float DT = 0.05f;

    private final Script host = new Script();
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private CgEntityState state;

    @Before
    public void setUp() {
        state = new CgEntityState();
        host.facts.clear();
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
    }

    private void step() {
        assertTrue(state.update(host, 7, 1f, DT, pose));
    }

    @Test
    public void firstUpdateRaisesNothing() {
        host.facts.hurtTime = 10;
        host.facts.bits |= CgEntityFacts.SWINGING | CgEntityFacts.USING;
        step();
        assertEquals(0, state.events());
    }

    @Test
    public void jumpAndLandFromTheHighestPoint() {
        host.facts.y = 64;
        step();
        host.facts.bits = CgEntityFacts.LIVING;
        host.facts.moveY = 0.42;
        host.facts.y = 64.42;
        step();
        assertTrue(state.happened(CgEntityState.JUMPED));
        host.facts.y = 65.25;
        step();
        host.facts.moveY = -0.5;
        host.facts.y = 61;
        step();
        assertEquals(2 * DT, state.airSeconds(), 1e-6);
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
        host.facts.y = 60;
        step();
        assertTrue(state.happened(CgEntityState.LANDED));
        assertEquals(5.25f, state.landHeight(), 1e-4);
        assertEquals(0f, state.airSeconds(), 0f);
    }

    @Test
    public void walkingOffALedgeIsNoJump() {
        step();
        host.facts.bits = CgEntityFacts.LIVING;
        host.facts.moveY = -0.08;
        step();
        assertFalse(state.happened(CgEntityState.JUMPED));
    }

    @Test
    public void hurtWhenItsCountdownRestarts() {
        step();
        host.facts.hurtTime = 10;
        step();
        assertTrue(state.happened(CgEntityState.HURT));
        assertEquals(0f, state.secondsSinceHurt(), 0f);
        host.facts.hurtTime = 9;
        step();
        assertFalse(state.happened(CgEntityState.HURT));
        assertEquals(DT, state.secondsSinceHurt(), 1e-6);
        host.facts.hurtTime = 10;
        step();
        assertTrue("a second hit before the first wore off", state.happened(CgEntityState.HURT));
    }

    @Test
    public void swingStartsAndRestarts() {
        step();
        host.facts.bits |= CgEntityFacts.SWINGING;
        host.facts.swing = 0.1f;
        step();
        assertTrue(state.happened(CgEntityState.SWING_STARTED));
        host.facts.swing = 0.6f;
        step();
        assertFalse(state.happened(CgEntityState.SWING_STARTED));
        host.facts.swing = 0.05f;
        step();
        assertTrue("clicked again mid-swing", state.happened(CgEntityState.SWING_STARTED));
    }

    @Test
    public void useCountedWhereTheHostCannotSay() {
        step();
        host.facts.bits |= CgEntityFacts.USING;
        host.facts.useLength = 72000;
        host.facts.item[CgEntityFacts.MAIN] = 261;
        host.facts.use[CgEntityFacts.MAIN] = CgEntityFacts.USE_BOW;
        step();
        assertTrue(state.happened(CgEntityState.USE_STARTED));
        assertEquals(0f, state.useSeconds(), 0f);
        step();
        step();
        assertEquals(2 * DT, state.useSeconds(), 1e-6);
        assertEquals(3600f, state.useLength(), 0f);
        assertEquals(CgEntityState.ARM_BOW, state.armPose(CgEntityState.MAIN));
        host.facts.bits &= ~CgEntityFacts.USING;
        step();
        assertTrue(state.happened(CgEntityState.USE_STOPPED));
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
        assertTrue("its ticks went back: a new use", state.happened(CgEntityState.USE_STARTED));
        assertTrue(state.happened(CgEntityState.USE_STOPPED));
    }

    @Test
    public void mountAndDismount() {
        step();
        host.facts.vehicle = 40;
        step();
        assertTrue(state.happened(CgEntityState.MOUNTED));
        assertTrue(state.riding());
        host.facts.vehicle = -1;
        step();
        assertTrue(state.happened(CgEntityState.DISMOUNTED));
    }

    @Test
    public void diesOnce() {
        step();
        host.facts.bits |= CgEntityFacts.DEAD;
        host.facts.deathTime = 1;
        step();
        assertTrue(state.happened(CgEntityState.DIED));
        host.facts.deathTime = 10;
        step();
        assertFalse(state.happened(CgEntityState.DIED));
        assertEquals(0.5f, state.secondsDead(), 1e-6);
    }

    @Test
    public void armPosesFollowMinecraftsRule() {
        host.facts.item[CgEntityFacts.MAIN] = 1;
        host.facts.item[CgEntityFacts.OFF] = 2;
        host.facts.bits |= CgEntityFacts.CHARGED_MAIN;
        step();
        assertEquals(CgEntityState.ARM_CROSSBOW_HOLD, state.armPose(CgEntityState.MAIN));
        assertEquals("a two-handed pose leaves the other hand holding", CgEntityState.ARM_ITEM,
                state.armPose(CgEntityState.OFF));
        host.facts.bits |= CgEntityFacts.SWINGING;
        step();
        assertEquals("swinging lowers a charged crossbow", CgEntityState.ARM_ITEM, state.armPose(CgEntityState.MAIN));
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.USING;
        host.facts.useHand = CgEntityFacts.OFF;
        host.facts.use[CgEntityFacts.OFF] = CgEntityFacts.USE_BLOCK;
        step();
        assertEquals(CgEntityState.ARM_BLOCK, state.armPose(CgEntityState.OFF));
        assertEquals(CgEntityState.ARM_ITEM, state.armPose(CgEntityState.MAIN));
        host.facts.item[CgEntityFacts.OFF] = 0;
        step();
        assertEquals(CgEntityState.ARM_EMPTY, state.armPose(CgEntityState.OFF));
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
        assertFalse(state.update(host, 7, 1f, DT, pose));
        assertEquals(5, state.item(CgEntityState.MAIN));
        assertEquals(3, state.vehicle());
        host.answers = true;
        step();
        assertFalse(state.happened(CgEntityState.MOUNTED));
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
}
