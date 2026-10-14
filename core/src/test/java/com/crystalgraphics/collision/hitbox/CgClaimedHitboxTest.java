package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgCollisionQuery;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.CollisionFakes.Yard;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitClaim;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.sun.management.ThreadMXBean;
import org.junit.Before;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.crystalgraphics.platform.world.CgEntityQuery.KIND_MONSTER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Claimed hitboxes on a server and clients in one JVM, with latency: the owner's client decides what its swing hit and
 * the server checks each claim. The rig's sides share one table, so a mob the test moves after the owner's hit is where
 * the server judges the claim: a target that moved on, as it would at ping. A mob stands 2 blocks out on +x.
 */
public class CgClaimedHitboxTest {

    private static final List<Integer> SERVER = new CopyOnWriteArrayList<>(), REFUSED_HERE = new CopyOnWriteArrayList<>();
    private static final List<CgHitClaim.Refusal> REFUSED = new CopyOnWriteArrayList<>();

    private static final CgHitboxType PUNCH = claimed(CgHitboxType.define("claim_test:punch")
            .hitbox(obj -> CgHitbox.of(CgSphere.of(0.8)).activeFor(6).query(CgCollisionQuery::living)).claimed());
    private static final CgHitboxType UNASKED = claimed(CgHitboxType.define("claim_test:unasked")
            .hitbox(obj -> CgHitbox.of(CgSphere.of(0.8)).activeFor(6)).claimed());
    private static final CgHitboxType BRIEF = claimed(CgHitboxType.define("claim_test:brief")
            .hitbox(obj -> CgHitbox.of(CgSphere.of(0.8)).activeFor(40)).claimed().claimWindow(4));

    private static CgHitboxType claimed(CgHitboxType.Builder b) {
        return b.build()
                .onHit((obj, h) -> SERVER.add(h.entityId()))
                .onRefusedClaim((obj, c) -> REFUSED.add(c.reason()))
                .onRefusedHere((obj, c) -> REFUSED_HERE.add(c.target()));
    }

    static {
        PUNCH.acceptPredictionsFrom((peer, obj) -> true);
    }

    private final Table table = new Table().mob(2001, 2, 0, KIND_MONSTER);

    @Before
    public void setUp() {
        SERVER.clear();
        REFUSED.clear();
        REFUSED_HERE.clear();
    }

    private CgNetTestRig rig(int clients, int latency) {
        return CgNetTestRig.of(clients).world(new Yard(), table).tick(3).latencyTicks(latency);
    }

    private static CgNetObject punch(CgNetTestRig rig, CgHitboxType type) {
        return rig.client(0).predict(type.type(), rig.at(2, 0.9, 0)).start();
    }

    @Test
    public void aTargetThatMovedOnIsHitWhereTheOwnerSawIt() {
        try (CgNetTestRig rig = rig(1, 3)) {
            punch(rig, PUNCH);
            rig.tick(2);
            table.move(2001, 4, 0, 0);       // out of the server's swing, inside its padding
            rig.tick(20);
            assertEquals(Collections.singletonList(2001), SERVER);
            assertEquals(Collections.emptyList(), REFUSED);
        }
    }

    @Test
    public void aClaimTenBlocksOutIsRefusedAndItsOwnerTold() {
        try (CgNetTestRig rig = rig(1, 3)) {
            punch(rig, PUNCH);
            rig.tick(2);
            table.move(2001, 12, 0, 0);
            rig.tick(20);
            assertEquals(Collections.emptyList(), SERVER);
            assertEquals(Collections.singletonList(CgHitClaim.Refusal.OUT_OF_RANGE), REFUSED);
            assertEquals("the owner hears it, to undo what it showed", Collections.singletonList(2001), REFUSED_HERE);
            assertEquals(1, PUNCH.refusedClaims(CgHitClaim.Refusal.OUT_OF_RANGE));
        }
    }

    @Test
    public void onlyTheOwnerClaimsAndAClaimCannotHitTwiceWhatTheHitboxHitsOnce() {
        try (CgNetTestRig rig = rig(2, 2)) {
            CgNetObject mine = punch(rig, PUNCH);
            rig.tick(8);
            assertEquals("the other viewer's copy is cosmetic", Collections.singletonList(2001), SERVER);
            long before = PUNCH.refusedClaims(CgHitClaim.Refusal.RULE);
            CgHit again = new CgHit().setPoint(2, 0.9, 0, null).setEntity(2001, 2001);
            mine.send(CgHitboxType.CLAIM, new CgHitClaim().set(again, 0, 3));
            rig.tick(20);
            assertEquals(Collections.singletonList(2001), SERVER);
            assertEquals(before + 1, PUNCH.refusedClaims(CgHitClaim.Refusal.RULE));
        }
    }

    @Test
    public void aRefusedPredictionsClaimsNeverLeave() {
        try (CgNetTestRig rig = rig(1, 2)) {
            punch(rig, UNASKED);
            rig.tick(30);
            assertEquals(Collections.emptyList(), SERVER);
            assertEquals("nothing was claimed, so nothing refused", Collections.emptyList(), REFUSED);
        }
    }

    @Test
    public void aClaimFromOutsideTheWindowIsRefused() {
        try (CgNetTestRig rig = rig(1, 5)) {
            // spawned by the server: its owner's copy arrives five ticks late, and its claim five after that
            BRIEF.spawn(rig.at(2, 0.9, 0)).owner(rig.client(0).id()).start();
            rig.tick(30);
            assertEquals(Collections.emptyList(), SERVER);
            assertEquals(Collections.singletonList(CgHitClaim.Refusal.OUT_OF_WINDOW), REFUSED);
        }
    }

    @Test
    public void judgingAClaimAllocatesNothing() {
        try (CgNetTestRig rig = rig(1, 0)) {
            // no owner: nobody claims, and the test's own judging is all there is
            CgNetObject server = PUNCH.spawn(rig.at(2, 0.9, 0)).start();
            rig.tick(2);
            CgHitbox hitbox = CgHitboxType.hitbox(server);
            CgCollisionWorld world = CgCollisionWorld.of(server.level());
            CgAABB window = new CgAABB().set(1.2, 0.1, -0.8, 2.8, 1.7, 0.8);
            CgHit out = new CgHit();
            List<CgHitClaim.Refusal> first = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                CgHitClaim.Refusal r = hitbox.judge(world, 2001, 0, window, 3, out);
                if (i == 0) first.add(r);
            }
            assertEquals("the first is allowed, the rest hit what was hit", Arrays.asList((CgHitClaim.Refusal) null), first);
            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().getId();
            long least = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                long before = threads.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 100; i++) hitbox.judge(world, 2001, 0, window, 3, out);
                least = Math.min(least, threads.getThreadAllocatedBytes(thread) - before);
            }
            assertEquals("bytes allocated judging 100 claims", 0, least);
            assertTrue(SERVER.isEmpty());
        }
    }
}
