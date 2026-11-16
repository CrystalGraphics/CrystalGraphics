package com.crystalgraphics.net.object;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;

/** An entity's properties: a type on entities, its local fields kept on each side and never sent, its objects made and ended with the entity. */
public class CgNetEntitiesTest {

    /** Each side works this out for itself. */
    static final CgNetField<Float> SPEED = CgNetField.ofFloat("speed").local();
    static final CgNetEvent<Void> LANDED = CgNetEvent.of("landed");
    static final CgNetType MOTION = CgNetType.define("entitytest:motion").fields(SPEED).events(LANDED)
            .onEntities(type -> type.equals("test:zombie")).build();

    /** The server's, sent to whoever sees the entity; and one each side keeps. */
    static final CgNetField<Float> KI = CgNetField.ofFloat("ki");
    static final CgNetField<Integer> GEAR = CgNetField.ofInt("gear").local();
    static final CgNetType POWER = CgNetType.define("entitytest:power").fields(KI, GEAR)
            .onEntities(type -> type.equals("test:zombie")).build();

    private static final List<String> HEARD = new ArrayList<>();

    static {
        MOTION.onServer(obj -> new CgNetBehaviour() {
            @Override public void tick(CgNetObject o) { o.setFloat(SPEED, o.getFloat(SPEED) + 1f); }
            @Override public void despawned(CgNetObject o, boolean killed) { HEARD.add("ended"); }
        });
        MOTION.onEvent(LANDED, (obj, none) -> HEARD.add("landed " + obj.entityId()));
    }

    private CgNetTestRig rig;
    private final List<CgNetTestRig.Entity> alive = new ArrayList<>();

    /** Answers a pose for every rig entity still alive: what the presence sweep asks. */
    private final CgEntityQuery entities = new CgEntityQuery() {
        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }
        @Override public boolean pose(int id, float partialTick, double[] out) {
            for (CgNetTestRig.Entity e : alive) if (e.id() == id && e.alive) return true;
            return false;
        }
        @Override public int flags(int id) { return 0; }
        @Override public int kind(int id) { return KIND_OTHER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }
        @Override public void within(double a, double b, double c, double d, double e, double f, IntConsumer visitor) { }
    };

    @Before
    public void setUp() {
        HEARD.clear();
        rig = CgNetTestRig.of(1).world(CgWorldQuery.NONE, entities);
        rig.tick(4);
    }

    @After
    public void tearDown() {
        CgNetEntities.clear(false);
        rig.close();
    }

    private CgNetTestRig.Entity zombie() {
        CgNetTestRig.Entity e = rig.entity(0, 64, 0);
        alive.add(e);
        CgNetEntities.enteredServer(e, "test:zombie");
        return e;
    }

    @Test
    public void aLocalTypeIsEachSidesOwnAndNeverSent() {
        CgNetTestRig.Entity e = zombie();
        CgNetObject server = MOTION.find(e);
        assertNotNull("made as the entity entered", server);
        assertTrue(MOTION.isLocal());
        CgNetEntities.tickServer();
        CgNetEntities.tickServer();
        assertEquals("its behaviour ticks", 2f, server.get(SPEED), 0f);
        rig.tick(2);
        assertEquals("nothing of it goes on the wire", 0, rig.client(0).visible(MOTION).size());

        CgNetEntities.entered(e.id(), "test:zombie");
        CgNetObject client = CgNetEntities.client(MOTION, e.id(), false);
        assertNotNull("the client makes its own", client);
        assertNotSame(server, client);
        client.setFloat(SPEED, 7f);
        assertEquals("a client writes its own", 7f, client.get(SPEED), 0f);
        assertTrue("the server's is its own", server.get(SPEED) != 7f);

        client.emit(LANDED, null);
        assertEquals("an event is heard on the side that emits it", List.of("landed " + e.id()), HEARD);
    }

    @Test
    public void aSentTypeOnEntitiesReachesTheClientWithItsLocalFieldsItsOwn() {
        CgNetTestRig.Entity e = zombie();
        CgNetObject server = POWER.find(e);
        assertNotNull(server);
        server.setFloat(KI, 42f).setInt(GEAR, 3);
        rig.tick(2);
        CgNetObject client = rig.client(0).find(POWER, e.id());
        assertNotNull("sent to the client that sees it", client);
        assertEquals(42f, client.get(KI), 0f);
        assertEquals("a local field is never sent", 0, (int) client.get(GEAR));
        client.setInt(GEAR, 2);
        assertEquals("and a client writes its own", 2, (int) client.get(GEAR));
    }

    @Test
    public void anEntityItsHostNoLongerHasTakesItsLocalObjectsWithIt() {
        CgNetTestRig.Entity e = zombie();
        assertNotNull(MOTION.find(e));
        e.kill();
        CgNetEntities.tickServer();
        assertNull("gone with its entity, with no leave event", MOTION.find(e));
        assertEquals(List.of("ended"), HEARD);

        CgNetTestRig.Entity f = zombie();
        CgNetEntities.leftServer(f);
        assertNull("or at once, where the host says", MOTION.find(f));
    }

    @Test
    public void aLocalTypeRefusesWhatWouldNeedTheWire() {
        CgNetField<Float> a = CgNetField.ofFloat("a").local();
        try {
            CgNetType.define("entitytest:saved").fields(a).perHolder().persisted().build();
            throw new AssertionError("a local type cannot be saved");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("never saved"));
        }
        try {
            CgNetField.ofFloat("b").local().predicted();
            throw new AssertionError("a local field cannot be predicted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("cannot be predicted"));
        }
    }
}
