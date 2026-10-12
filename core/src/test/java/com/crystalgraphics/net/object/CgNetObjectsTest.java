package com.crystalgraphics.net.object;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.crystalgraphics.serialization.CgCodecs;
import com.sun.management.ThreadMXBean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Networked objects between a server and clients on the rig: who is sent what, once, and when. */
public class CgNetObjectsTest {

    static final CgNetField<Float> HEAT = CgNetField.ofFloat("heat");
    static final CgNetField<Integer> AMMO = CgNetField.ofInt("ammo").ownerOnly();
    static final CgNetField<Boolean> SHOWN = CgNetField.ofBool("shown").skipOwner();
    static final CgNetField<Integer> SEED = CgNetField.ofInt("seed").initialOnly();
    static final CgNetField<Integer> SECRET = CgNetField.ofInt("secret").serverOnly();
    static final CgNetField<String> LABEL = CgNetField.of("label", CgCodecs.STRING).fallback("none");
    static final CgNetField<UUID> TARGET = CgNetField.of("target", CgCodecs.UUID);
    static final CgNetField<Double> RANGE = CgNetField.ofDouble("range").fallback(8.0);

    static final CgNetType TURRET = CgNetType.define("test:turret")
            .fields(HEAT, AMMO, SHOWN, SEED, SECRET, LABEL, TARGET, RANGE).range(32).persisted().build();
    static final CgNetType SPARK = CgNetType.define("test:spark").fields(HEAT).build();
    static final CgNetType STORM = CgNetType.define("test:storm").fields(HEAT).build();

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();

    private final List<String> heard = new ArrayList<>();
    private CgNetTestRig rig;

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(2);
        TURRET.onClient(obj -> new Recorder("turret"));
        SPARK.onClient(obj -> new Recorder("spark"));
        STORM.onClient(obj -> new Recorder("storm"));
        rig.tick(3);   // the hellos
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private final class Recorder implements CgNetBehaviour {
        final String name;

        Recorder(String name) {
            this.name = name;
        }

        @Override
        public void spawned(CgNetObject obj, float age) {
            heard.add(name + " spawned " + (int) age);
        }

        @Override
        public void changed(CgNetObject obj, CgNetField<?> field) {
            heard.add(name + " " + field.name() + " " + obj.get(field));
        }

        @Override
        public void moved(CgNetObject obj) {
            heard.add(name + " moved " + (int) obj.x());
        }

        @Override
        public void despawned(CgNetObject obj, boolean killed) {
            heard.add(name + (killed ? " killed" : " despawned"));
        }
    }

    private CgNetObject one(int client, CgNetType type) {
        List<CgNetObject> seen = rig.client(client).visible(type);
        assertEquals("client " + client + " sees one " + type.name(), 1, seen.size());
        return seen.get(0);
    }

    @Test
    public void aSpawnReachesEveryViewerOnceWithWhatWasSetBeforeIt() {
        CgNetObject turret = TURRET.spawn(rig.at(0, 0, 0)).setFloat(HEAT, 0.5f).set(LABEL, "north").start();
        rig.tick(1);
        assertEquals(List.of("turret spawned 0", "turret spawned 0"), heard);
        for (int c = 0; c < 2; c++) {
            CgNetObject seen = one(c, TURRET);
            assertEquals(turret.id(), seen.id());
            assertEquals(0.5f, seen.getFloat(HEAT), 0f);
            assertEquals("north", seen.get(LABEL));
            assertEquals("never set: the fallback, not sent", 8.0, seen.getDouble(RANGE), 0);
            assertNull(seen.get(TARGET));
        }
        rig.tick(5);
        assertEquals("nothing more: no change", 2, heard.size());
    }

    @Test
    public void theReportCountsATicksObjectsViewersAndBytes() {
        TURRET.spawn(rig.at(0, 0, 0)).setFloat(HEAT, 0.5f).start();
        rig.tick(1);
        assertEquals(1, TURRET.tickLive);
        assertEquals("both clients see it", 2, TURRET.tickViewers);
        assertTrue("its spawns took bytes", TURRET.tickBytes > 0);
        String report = CgNetObjects.report();
        assertTrue(report, report.contains("test:turret") && report.contains("1 live   2 seen"));

        rig.tick(1);
        assertEquals("a quiet tick sends nothing", 0, TURRET.tickBytes);
        assertEquals(1, TURRET.tickLive);
    }

    @Test
    public void changesInOneTickArriveAsOneInDeclarationOrder() {
        CgNetObject turret = TURRET.spawn(rig.at(0, 0, 0)).start();
        rig.tick(1);
        heard.clear();
        UUID target = UUID.randomUUID();
        turret.set(LABEL, "a").setFloat(HEAT, 1f).setFloat(HEAT, 2f).set(TARGET, target).setFloat(HEAT, 3f);
        rig.tick(1);
        assertEquals(List.of("turret heat 3.0", "turret label a", "turret target " + target,
                "turret heat 3.0", "turret label a", "turret target " + target), heard);
        assertEquals(target, one(1, TURRET).get(TARGET));
    }

    @Test
    public void ownerOnlyAndSkipOwnerFieldsReachOnlyTheirSide() {
        CgNetObject turret = TURRET.spawn(rig.at(0, 0, 0)).owner(rig.client(0).id())
                .setInt(AMMO, 50).setBool(SHOWN, true).setInt(SECRET, 7).start();
        rig.tick(1);
        CgNetObject owners = one(0, TURRET), others = one(1, TURRET);
        assertTrue(owners.isMine());
        assertFalse(others.isMine());
        assertEquals(50, owners.getInt(AMMO));
        assertEquals("hidden information", 0, others.getInt(AMMO));
        assertFalse("the owner showed it already", owners.getBool(SHOWN));
        assertTrue(others.getBool(SHOWN));
        assertEquals("never sent", 0, owners.getInt(SECRET));

        turret.setInt(AMMO, 49);
        rig.tick(1);
        assertEquals(49, owners.getInt(AMMO));
        assertEquals(0, others.getInt(AMMO));
    }

    @Test
    public void initialOnlyIsSpawnDataNotAChange() {
        CgNetObject turret = TURRET.spawn(rig.at(0, 0, 0)).setInt(SEED, 1).start();
        rig.client(1).disconnect();
        rig.tick(1);
        turret.setInt(SEED, 2);
        rig.tick(1);
        assertEquals("not sent as a change", 1, one(0, TURRET).getInt(SEED));
        rig.client(1).connect();
        rig.tick(4);
        assertEquals("a later spawn carries the value now", 2, one(1, TURRET).getInt(SEED));
    }

    @Test
    public void aLateViewerGetsCurrentStateAndTheRightAge() {
        rig.client(1).moveTo(100, 0, 0);
        CgNetObject turret = TURRET.spawn(rig.at(0, 0, 0)).setFloat(HEAT, 1f).start();
        rig.tick(10);
        turret.setFloat(HEAT, 2f);
        rig.tick(10);
        assertEquals("out of range", 0, rig.client(1).visible(TURRET).size());
        heard.clear();
        rig.client(1).moveTo(10, 0, 0);
        rig.tick(1);
        assertEquals(List.of("turret spawned 20"), heard);
        assertEquals(2f, one(1, TURRET).getFloat(HEAT), 0f);
        assertEquals(turret.age(), one(1, TURRET).age());

        rig.client(1).moveTo(100, 0, 0);
        rig.tick(1);
        assertEquals("leaving view ends it at once", "turret killed", heard.get(heard.size() - 1));
        assertEquals(0, rig.client(1).visible(TURRET).size());
    }

    @Test
    public void aMoveIsDecidedAgainAndReachesWhoStillSeesIt() {
        rig.client(1).moveTo(30, 0, 0);
        CgNetObject turret = TURRET.spawn(rig.at(0, 0, 0)).start();
        rig.tick(1);
        heard.clear();
        turret.moveTo(-10, 0, 0);
        rig.tick(1);
        heard.sort(null);
        assertEquals("one client still sees it, the other no longer", List.of("turret killed", "turret moved -10"), heard);
        assertEquals(-10, one(0, TURRET).x(), 0);
    }

    @Test
    public void despawnIsGracefulAndKillIsAtOnce() {
        CgNetObject a = TURRET.spawn(rig.at(0, 0, 0)).start();
        CgNetObject b = STORM.spawn(CgAnchor.dimension(CgNetTestRig.DIMENSION)).start();
        rig.tick(1);
        heard.clear();
        a.despawn();
        b.kill();
        rig.tick(1);
        assertEquals("each client in turn", List.of("turret despawned", "storm killed", "turret despawned", "storm killed"), heard);
        assertEquals(0, TURRET.size());
        assertEquals(0, rig.client(0).visible(TURRET).size());
    }

    @Test
    public void aLifetimeEndsItGracefully() {
        TURRET.spawn(rig.at(0, 0, 0)).lifetime(5).start();
        rig.tick(4);
        assertEquals(1, TURRET.size());
        rig.tick(2);
        assertEquals(0, TURRET.size());
        assertTrue(heard.contains("turret despawned"));
    }

    @Test
    public void aFiredObjectIsBuiltByWhoSeesItNowAndNoLaterViewer() {
        rig.client(1).disconnect();
        SPARK.spawn(rig.at(0, 0, 0)).setFloat(HEAT, 2f).fire();
        rig.tick(1);
        rig.client(1).connect();
        rig.tick(5);
        assertEquals(List.of("spark spawned 0"), heard);
        assertEquals("kept nowhere", 0, SPARK.size());
        assertEquals("a transient object is never in the client's table", 0, rig.client(0).visible(SPARK).size());
    }

    @Test
    public void aDimensionAndAGlobalObjectIgnoreRange() {
        rig.client(1).moveTo(10_000, 0, 0);
        STORM.spawn(CgAnchor.dimension(CgNetTestRig.DIMENSION)).start();
        rig.tick(1);
        one(1, STORM);
        rig.client(1).dimension("rig:nether");
        rig.tick(1);
        assertEquals(0, rig.client(1).visible(STORM).size());
        STORM.spawn(CgAnchor.global()).start();
        rig.tick(1);
        assertEquals(1, rig.client(1).visible(STORM).size());
    }

    @Test
    public void onChangeHearsTheSpawnAndEachChange() {
        List<Float> heats = new ArrayList<>();
        STORM.onChange(HEAT, (obj, heat) -> heats.add(heat));
        rig.client(1).disconnect();
        CgNetObject storm = STORM.spawn(CgAnchor.global()).setFloat(HEAT, 1f).start();
        rig.tick(1);
        storm.setFloat(HEAT, 2f);
        rig.tick(1);
        assertEquals(List.of(1f, 2f), heats);
    }

    @Test
    public void aPersistedObjectComesBackWithItsFieldsOwnerAndAge() throws Exception {
        rig.saveTo(world.getRoot().toPath());
        UUID target = UUID.randomUUID();
        TURRET.spawn(rig.at(3, 4, 5)).owner(rig.client(0).id()).setInt(AMMO, 12).setInt(SECRET, 9).set(TARGET, target)
                .start();
        SPARK.spawn(rig.at(0, 0, 0)).start();   // not persisted
        rig.tick(40);
        CgNetObject[] before = new CgNetObject[1];
        TURRET.forEach(obj -> before[0] = obj);
        long age = before[0].age();
        rig.restart();
        assertEquals(1, TURRET.size());
        assertEquals(0, SPARK.size());
        CgNetObject[] back = new CgNetObject[1];
        TURRET.forEach(obj -> back[0] = obj);
        assertEquals(12, back[0].getInt(AMMO));
        assertEquals(9, back[0].getInt(SECRET));
        assertEquals(target, back[0].get(TARGET));
        assertEquals(rig.client(0).id(), back[0].owner());
        assertEquals(3, back[0].x(), 0);
        assertEquals(age, back[0].age());
        rig.tick(4);
        assertEquals(12, one(0, TURRET).getInt(AMMO));
    }

    @Test
    public void aPersistedTypeDeclaredAfterTheServerStartedIsLoadedNotSavedOver() throws Exception {
        Path root = world.getRoot().toPath();
        rig.saveTo(root);
        TURRET.spawn(rig.at(0, 0, 0)).setInt(AMMO, 7).start();
        rig.restart();
        Path saved = root.resolve("crystalgraphics/objects/test");
        Files.copy(saved.resolve("turret.dat"), saved.resolve("late.dat"));

        CgNetType late = CgNetType.define("test:late").fields(HEAT, AMMO, SHOWN, SEED, SECRET, LABEL, TARGET, RANGE)
                .persisted().build();
        assertEquals(1, late.size());
        CgNetObject[] back = new CgNetObject[1];
        late.forEach(obj -> back[0] = obj);
        assertEquals(7, back[0].getInt(AMMO));
    }

    @Test
    public void aTickOfChangesIsOneFramePerPlayer() {
        List<CgNetObject> turrets = new ArrayList<>();
        for (int i = 0; i < 50; i++) turrets.add(TURRET.spawn(rig.at(i % 10, 0, 0)).start());
        rig.tick(1);
        int[] before = {rig.client(0).framesReceived(), rig.client(1).framesReceived()};
        long bytes = rig.client(1).bytesReceived();
        for (CgNetObject turret : turrets) turret.setFloat(HEAT, 0.25f);
        rig.tick(1);
        System.out.println("[net-objects] 50 float changes reach a player as " + (rig.client(1).bytesReceived() - bytes)
                + " bytes on the channel");
        assertEquals(before[0] + 1, rig.client(0).framesReceived());
        assertEquals(before[1] + 1, rig.client(1).framesReceived());
        for (CgNetObject seen : rig.client(1).visible(TURRET)) assertEquals(0.25f, seen.getFloat(HEAT), 0f);
    }

    @Test
    public void aSteadyTickAllocatesNothingInTheLayer() {
        List<CgNetObject> turrets = new ArrayList<>();
        for (int i = 0; i < 50; i++) turrets.add(TURRET.spawn(rig.at(i % 10, 0, 0)).start());
        rig.tick(5);
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        for (int warm = 0; warm < 3000; warm++) CgNetObjects.serverTick();
        // A deoptimization rematerializes scalar-replaced objects once, a few hundred bytes after other tests' paths; a
        // per-tick allocation shows in every window.
        long quiet = Long.MAX_VALUE;
        for (int window = 0; window < 3 && quiet != 0; window++) {
            long before = threads.getThreadAllocatedBytes(thread);
            for (int t = 0; t < 1000; t++) CgNetObjects.serverTick();
            quiet = threads.getThreadAllocatedBytes(thread) - before;
        }

        long busy = 0;
        for (int t = 0; t < 1200; t++) {
            for (CgNetObject turret : turrets) turret.setFloat(HEAT, t);
            long at = threads.getThreadAllocatedBytes(thread);
            CgNetObjects.serverTick();
            if (t >= 200) busy += threads.getThreadAllocatedBytes(thread) - at;
        }
        System.out.println("[net-objects] 1000 quiet ticks allocated " + quiet + " B; 1000 ticks of 50 changes to 2 players "
                + busy + " B (" + busy / 1000 + " B a tick, the frames and their send)");
        assertEquals("a tick with nothing to send (net channel on: " + CgTrace.isEnabled(CgChannels.NET) + ")", 0, quiet);
    }
}
