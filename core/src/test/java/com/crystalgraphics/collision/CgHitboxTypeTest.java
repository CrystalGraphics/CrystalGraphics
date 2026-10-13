package com.crystalgraphics.collision;

import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.CollisionFakes.Yard;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetObjects;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.world.CgEntityAttachments;
import com.sun.management.ThreadMXBean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_MONSTER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Hitbox types on a server and two clients in one JVM: every side asks one table of entities, as Minecraft's sides
 * name the same entities by network id. Mobs stand 2, 5, 8 and 11.5 blocks out round the origin.
 */
public class CgHitboxTypeTest {

    private static final CgHitPart TIP = CgHitPart.of("tip"), BLADE = CgHitPart.of("blade");
    private static final CgNetField<Float> RADIUS = CgNetField.ofFloat("radius");
    private static final CgNetField<Integer> TAG = CgNetField.ofInt("tag");

    private record Heard(CgNetObject obj, int entity, CgHitPart part) {
    }

    private static final List<Heard> SERVER = new CopyOnWriteArrayList<>(), PREDICTED = new CopyOnWriteArrayList<>(),
            CONFIRMED = new CopyOnWriteArrayList<>();

    private static final CgHitboxType WAVE = CgHitboxType.define("hitbox_test:wave").fields(RADIUS).range(64)
            .hitbox(obj -> CgHitbox.of(CgRing.front(1.2, 0.6)).radius(0, obj.getFloat(RADIUS), 12).activeFor(12)
                    .living())
            .build();
    private static final CgHitboxType SLOW = CgHitboxType.define("hitbox_test:slow").fields(RADIUS).range(64)
            .hitbox(obj -> CgHitbox.of(CgRing.front(1.2, 0.6)).radius(0, obj.getFloat(RADIUS), 30).activeFor(30))
            .build();
    private static final CgHitboxType SWING = CgHitboxType.define("hitbox_test:swing")
            .hitbox(obj -> CgHitbox.follow(-1, CgEntityAttachments.FEET)
                    .part(TIP, CgSphere.of(0.6), 0, 0, 1.5).part(BLADE, CgCapsule.of(0.75, 0.4), 0, 0, 0.75)
                    .rehit(4))
            .build();
    private static final CgNetType FILLER = CgNetType.define("hitbox_test:filler").fields(TAG).range(96).build();

    static {
        for (CgHitboxType type : new CgHitboxType[]{WAVE, SLOW, SWING}) {
            type.onHit((obj, h) -> SERVER.add(new Heard(obj, h.entity(), h.part())))
                    .onPredictedHit((obj, h) -> PREDICTED.add(new Heard(obj, h.entity(), h.part())))
                    .onConfirmedHit((obj, h) -> CONFIRMED.add(new Heard(obj, h.entity(), h.part())));
        }
        WAVE.acceptPredictionsFrom((peer, obj) -> true);
    }

    /** Ticks from a spawn on the server to its copy on a rig client. */
    private static final int DELIVERY = 1;

    private final Table table = new Table().mob(2001, 2, 0, KIND_MONSTER).mob(2002, 0, 5, KIND_MONSTER)
            .mob(2003, -8, 0, KIND_MONSTER).mob(2004, 0, -11.5, KIND_MONSTER);

    @Before
    public void setUp() {
        SERVER.clear();
        PREDICTED.clear();
        CONFIRMED.clear();
        CgWorldQuery blocks = new Yard();
        CgPlatform.provide(CgLevelQueries.SERVICE, new CgLevelQueries() {
            @Override public CgWorldQuery world(Object level) { return blocks; }
            @Override public CgEntityQuery entities(Object level) { return table; }
        });
        CgPlatform.provide(CgEntityQuery.SERVICE, table);
    }

    @After
    public void tearDown() {
        CgPlatform.provide(CgLevelQueries.SERVICE, null);
        CgPlatform.provide(CgEntityQuery.SERVICE, null);
    }

    /** A rig whose clients have finished joining, as players have long before anything is cast at them. */
    private static CgNetTestRig rig(int clients) {
        return CgNetTestRig.of(clients).tick(3);
    }

    private static List<Integer> heard(List<Heard> list, CgNetObject obj) {
        List<Integer> out = new ArrayList<>();
        for (Heard h : list) if (h.obj() == obj) out.add(h.entity());
        return out;
    }

    private static CgNetObject seen(CgNetTestRig rig, int client, CgHitboxType type) {
        List<CgNetObject> visible = rig.client(client).visible(type.type());
        assertEquals("client " + client + " sees one", 1, visible.size());
        return visible.get(0);
    }

    @Test
    public void viewersPredictTheServersHitsAndHearThemConfirmedOnce() {
        try (CgNetTestRig rig = rig(2)) {
            CgNetObject wave = WAVE.spawn(rig.at(0, 0.9, 0)).setFloat(RADIUS, 12f).start();
            rig.tick(DELIVERY);
            CgNetObject alice = seen(rig, 0, WAVE), bob = seen(rig, 1, WAVE);
            rig.tick(20);
            List<Integer> all = Arrays.asList(2001, 2002, 2003, 2004);
            assertEquals(all, heard(SERVER, wave));
            assertTrue("it ends with its hitbox", wave.isRemoved());
            for (CgNetObject client : new CgNetObject[]{alice, bob}) {
                assertEquals("the same volume, the same hits", all, heard(PREDICTED, client));
                assertEquals("each server hit once, the last tick's included", all, heard(CONFIRMED, client));
            }
            assertEquals(0, rig.client(0).visible(WAVE.type()).size());
        }
    }

    @Test
    public void aLateJoinerCatchesUpSilently() {
        try (CgNetTestRig rig = rig(2)) {
            rig.client(1).moveTo(500, 0.9, 0);
            CgNetObject wave = SLOW.spawn(rig.at(0, 0.9, 0)).setFloat(RADIUS, 12f).start();
            rig.tick(15);
            assertEquals(Arrays.asList(2001, 2002), heard(SERVER, wave));
            rig.client(1).moveTo(0, 0.9, 0);
            rig.tick(DELIVERY);
            CgNetObject bob = seen(rig, 1, SLOW);
            rig.tick(30);
            assertEquals("only what came after it arrived", Arrays.asList(2003, 2004), heard(PREDICTED, bob));
            assertEquals(Arrays.asList(2003, 2004), heard(CONFIRMED, bob));
        }
    }

    @Test
    public void partsCrossTheWireAndTheFollowedEntitysRemovalStopsEverySide() {
        try (CgNetTestRig rig = rig(1)) {
            CgNetTestRig.Entity knight = rig.entity(0, 0, 0);
            table.mob(knight.id(), 0, 0, KIND_MONSTER).mob(2010, 0, 1.6, KIND_MONSTER);
            CgNetObject swing = SWING.spawn(CgAnchor.on(knight)).point(CgEntityAttachments.CENTRE)
                    .facing(CgHitbox.Facing.LOOK).source(knight.id()).start();
            rig.tick(DELIVERY);
            CgNetObject alice = seen(rig, 0, SWING);
            assertEquals(Arrays.asList(2010), heard(SERVER, swing));
            assertSame("the tip wins", TIP, SERVER.get(0).part());
            assertEquals(Arrays.asList(2010), heard(CONFIRMED, alice));
            assertSame("the part by its index, named on this side", TIP, CONFIRMED.get(0).part());
            assertNotNull(CgHitboxType.hitbox(swing));

            knight.kill();
            rig.tick(2);
            assertTrue(swing.isRemoved());
            assertTrue(CgHitboxType.hitbox(swing).stopped());
            assertTrue(CgHitboxType.hitbox(alice).stopped());
            assertEquals(0, rig.client(0).visible(SWING.type()).size());
        }
    }

    @Test
    public void aHitboxPosedByTheServerMovesAndTurnsOnEverySide() {
        try (CgNetTestRig rig = rig(1)) {
            CgNetObject swing = SWING.spawn(rig.at(0, 0.9, 30)).start();
            rig.tick(DELIVERY + 1);
            CgNetObject alice = seen(rig, 0, SWING);
            assertEquals(0, SERVER.size());
            // turned to face -z, its tip 1.5 ahead lands on 2001 at (2, 0, 0)
            CgHitboxType.pose(swing, new CgPose().at(2, 0.9, 1.5).facing(Math.PI, 0));
            rig.tick(3);
            assertEquals(Arrays.asList(2001), heard(SERVER, swing));
            assertSame(TIP, SERVER.get(0).part());
            assertEquals("the client's copy followed the pose", Arrays.asList(2001), heard(PREDICTED, alice));
        }
    }

    @Test
    public void itEndsWithItsSource() {
        table.mob(2050, 0, 40, KIND_MONSTER);
        try (CgNetTestRig rig = rig(1)) {
            CgNetObject wave = WAVE.spawn(rig.at(0, 0.9, 0)).source(2050).setFloat(RADIUS, 12f).start();
            rig.tick(2);
            table.remove(2050);
            rig.tick(2);
            assertTrue(wave.isRemoved());
            assertEquals(0, rig.client(0).visible(WAVE.type()).size());
        }
    }

    @Test
    public void anAdoptedPredictionKeepsItsHits() {
        try (CgNetTestRig rig = rig(2).latencyTicks(2)) {
            CgNetObject mine = rig.client(0).predict(WAVE.type(), rig.at(0, 0.9, 0)).setFloat(RADIUS, 12f).start();
            for (int i = 0; i < 20; i++) {
                rig.tick(1);
                assertTrue("never two copies", rig.client(0).visible(WAVE.type()).size() <= 1);
            }
            List<Integer> all = Arrays.asList(2001, 2002, 2003, 2004);
            assertEquals("predicted once each, across the adoption", all, heard(PREDICTED, mine));
            assertEquals(all, heard(CONFIRMED, mine));
            List<Integer> server = new ArrayList<>();
            for (Heard h : SERVER) server.add(h.entity());
            assertEquals(all, server);
        }
    }

    @Test
    public void aHitEmitsWithoutAllocating() {
        try (CgNetTestRig rig = rig(1)) {
            CgNetObject wave = SWING.spawn(rig.at(0, 0.9, 30)).start();
            CgSentHit sent = new CgSentHit().set(new CgHit().setEntity(2001, 2001, 1.5, 0, 0, Float.NaN, null), -1);
            for (int t = 0; t < 20; t++) {
                for (int i = 0; i < 50; i++) wave.emit(CgHitboxType.HIT, sent);
                rig.tick(1);
            }
            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().getId();
            long quiet = Long.MAX_VALUE;
            for (int window = 0; window < 5; window++) {
                long before = threads.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 50; i++) wave.emit(CgHitboxType.HIT, sent);
                quiet = Math.min(quiet, threads.getThreadAllocatedBytes(thread) - before);
                rig.tick(1);
            }
            long quietTick = rig.client(0).bytesReceived();
            rig.tick(1);
            quietTick = rig.client(0).bytesReceived() - quietTick;
            long busyTick = rig.client(0).bytesReceived();
            for (int i = 0; i < 50; i++) wave.emit(CgHitboxType.HIT, sent);
            rig.tick(1);
            busyTick = rig.client(0).bytesReceived() - busyTick;
            System.out.println("[collision] 50 hit events: " + quiet + " B allocated emitting; "
                    + (busyTick - quietTick) / 50.0 + " B each on the wire");
            assertEquals("bytes allocated by 50 emits", 0, quiet);
            assertTrue((busyTick - quietTick) / 50.0 < 28);
        }
    }

    @Test
    public void aFarViewerBehindTheBudget() {
        CgNetObjects.budget(1024);
        try (CgNetTestRig rig = rig(2)) {
            rig.client(1).moveTo(60, 0.9, 0);
            for (int i = 0; i < 400; i++) FILLER.spawn(rig.at(60, 0.9, i * 0.1)).setInt(TAG, i).start();
            CgNetObject wave = WAVE.spawn(rig.at(0, 0.9, 0)).setFloat(RADIUS, 12f).start();
            int arrived = -1;
            CgNetObject alice = null, bob = null;
            for (int t = 1; t <= 20; t++) {
                rig.tick(1);
                if (alice == null && !rig.client(0).visible(WAVE.type()).isEmpty()) {
                    alice = rig.client(0).visible(WAVE.type()).get(0);
                }
                if (bob == null && !rig.client(1).visible(WAVE.type()).isEmpty()) {
                    bob = rig.client(1).visible(WAVE.type()).get(0);
                    arrived = t;
                }
            }
            int far = bob == null ? 0 : heard(CONFIRMED, bob).size();
            System.out.println("[collision] behind a 1 KB budget and 400 nearer spawns, the far viewer got the wave"
                    + " at tick " + arrived + " and heard " + far + " of " + heard(SERVER, wave).size()
                    + " confirmed hits; the near viewer " + heard(CONFIRMED, alice).size());
            assertEquals(4, heard(SERVER, wave).size());
            assertEquals("the near viewer is served first, every hit confirmed", 4, heard(CONFIRMED, alice).size());
        } finally {
            CgNetObjects.budget(32 * 1024);
        }
    }
}
