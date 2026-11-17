package com.crystalgraphics.net.object;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.world.CgEntitySaves;

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

    /** Saved with the entity field by field: two persisted, one only for the session, one each side's own. */
    static final CgNetField<Integer> RAGE_LEVEL = CgNetField.ofInt("level").persisted();
    static final CgNetField<String> RAGE_TARGET = CgNetField.of("target", CgCodecs.STRING).persisted();
    static final CgNetField<Integer> RAGE_SEEN = CgNetField.ofInt("seen");
    static final CgNetField<Integer> RAGE_TICKS = CgNetField.ofInt("ticks").local();
    static final CgNetType RAGE = CgNetType.define("entitytest:rage").fields(RAGE_LEVEL, RAGE_TARGET, RAGE_SEEN, RAGE_TICKS)
            .onEntities(type -> type.equals("test:zombie")).build();

    @Test
    public void aPersistedTypeOnEntitiesIsSavedWithTheEntityAndBackWithIt() {
        Map<Object, CgNbtCompound> saves = new IdentityHashMap<>();
        CgPlatform.provide(CgEntitySaves.HOST, new CgEntitySaves.Host() {
            @Override public CgNbtCompound read(Object entity) { return saves.get(entity); }
            @Override public void write(Object entity, CgNbtCompound data) { saves.put(entity, data); }
            @Override public boolean savesWhenSaved() { return false; }
        });
        try {
            CgNetTestRig.Entity e = zombie();
            rig.tick(1);
            assertNull("nothing saved until it changes", saves.get(e));
            RAGE.find(e).setInt(RAGE_SEEN, 7).setInt(RAGE_TICKS, 9);
            rig.tick(1);
            assertNull("a change to fields not persisted saves nothing", saves.get(e));
            RAGE.find(e).setInt(RAGE_LEVEL, 5).set(RAGE_TARGET, "steve");
            rig.tick(1);
            CgNbtCompound saved = saves.get(e);
            assertNotNull("saved with the entity as it changed", saved);
            CgNbtCompound rage = (CgNbtCompound) saved.get(RAGE.name());
            assertEquals(5, (int) rage.get("level", CgCodecs.INT));
            assertEquals("steve", rage.get("target", CgCodecs.STRING));
            assertTrue("a local field is never saved", !rage.has("ticks"));
            assertTrue("nor one not persisted", !rage.has("seen"));

            // the game loads the entity again: a new one, holding what it saved
            CgNetTestRig.Entity loaded = rig.entity(0, 64, 0);
            alive.add(loaded);
            saves.put(loaded, saved);
            CgNetEntities.enteredServer(loaded, "test:zombie");
            CgNetObject back = RAGE.find(loaded);
            assertEquals("back as it was", 5, back.getInt(RAGE_LEVEL));
            assertEquals("steve", back.get(RAGE_TARGET));
            assertEquals("a local field starts afresh", 0, back.getInt(RAGE_TICKS));
            assertEquals("and one not persisted", 0, back.getInt(RAGE_SEEN));
        } finally {
            CgPlatform.provide(CgEntitySaves.HOST, null);
        }
    }

    /** A second persisted type on the same entities: saved in the same write. */
    static final CgNetField<Float> CALM = CgNetField.ofFloat("calm");
    static final CgNetType MOOD = CgNetType.define("entitytest:mood").fields(CALM)
            .onEntities(type -> type.equals("test:zombie")).persisted().build();

    @Test
    public void whereTheHostHooksTheSaveAChangeIsWrittenOnceAtTheEntitysSave() throws InterruptedException {
        Map<Object, CgNbtCompound> saves = new IdentityHashMap<>();
        int[] writes = {0};
        CgPlatform.provide(CgEntitySaves.HOST, new CgEntitySaves.Host() {
            @Override public CgNbtCompound read(Object entity) { return saves.get(entity); }
            @Override public void write(Object entity, CgNbtCompound data) { writes[0]++; saves.put(entity, data); }
            @Override public boolean savesWhenSaved() { return true; }
        });
        try {
            CgNetTestRig.Entity e = zombie();
            for (int t = 0; t < 20; t++) {
                RAGE.find(e).setInt(RAGE_LEVEL, t);
                MOOD.find(e).setFloat(CALM, t * 0.5f);
                rig.tick(1);
            }
            assertEquals("twenty ticks of changes write nothing", 0, writes[0]);
            CgEntitySaves.saving(e);
            assertEquals("the save writes both types at once", 1, writes[0]);
            CgNbtCompound saved = saves.get(e);
            assertEquals(19, (int) ((CgNbtCompound) saved.get(RAGE.name())).get("level", CgCodecs.INT));
            assertEquals(9.5f, ((CgNbtCompound) saved.get(MOOD.name())).get("calm", CgCodecs.FLOAT), 0f);
            CgEntitySaves.saving(e);
            assertEquals("a second save with nothing changed writes nothing", 1, writes[0]);

            RAGE.find(e).setInt(RAGE_LEVEL, 40);
            rig.tick(1);
            Thread client = new Thread(() -> {
                CgNetwork.tickStarted(CgNetwork.Side.CLIENT);
                CgEntitySaves.saving(e);                         // a client saving the entity (a creative pick)
            });
            client.start();
            client.join();
            assertEquals("a client's save of an entity writes nothing of the server's", 1, writes[0]);
            CgEntitySaves.saving(e);
            assertEquals("the server's next save does", 2, writes[0]);

            RAGE.find(e).setInt(RAGE_LEVEL, 41);
            rig.tick(1);
            CgNetObjects.serverStopped();
            assertEquals("the server stopping writes what is pending, ahead of the game's last save", 3, writes[0]);
            assertEquals(41, (int) ((CgNbtCompound) saves.get(e).get(RAGE.name())).get("level", CgCodecs.INT));
        } finally {
            CgPlatform.provide(CgEntitySaves.HOST, null);
        }
    }

    /** Two fields saved as one "x,y" text: a saved form of its own. */
    static final CgNetField<Integer> MARK_X = CgNetField.ofInt("x").persisted();
    static final CgNetField<Integer> MARK_Y = CgNetField.ofInt("y").persisted();
    static final CgNetType MARK = CgNetType.define("entitytest:mark").fields(MARK_X, MARK_Y)
            .onEntities(type -> type.equals("test:zombie"))
            .savedAs(new CgNetSaveForm() {
                @Override public <T> void write(CgNetObject obj, CgStateMap<T> out) {
                    out.putString("at", obj.getInt(MARK_X) + "," + obj.getInt(MARK_Y));
                }
                @Override public <T> void read(CgNetObject obj, CgStateMap<T> in) {
                    String[] at = in.getString("at", "0,0").split(",");
                    obj.setInt(MARK_X, Integer.parseInt(at[0])).setInt(MARK_Y, Integer.parseInt(at[1]));
                }
            })
            .build();

    @Test
    public void aTypeWithASavedFormOfItsOwnIsSavedAndReadThroughIt() {
        Map<Object, CgNbtCompound> saves = new IdentityHashMap<>();
        CgPlatform.provide(CgEntitySaves.HOST, new CgEntitySaves.Host() {
            @Override public CgNbtCompound read(Object entity) { return saves.get(entity); }
            @Override public void write(Object entity, CgNbtCompound data) { saves.put(entity, data); }
            @Override public boolean savesWhenSaved() { return false; }
        });
        try {
            CgNetTestRig.Entity e = zombie();
            MARK.find(e).setInt(MARK_X, 12).setInt(MARK_Y, -3);
            rig.tick(1);
            CgNbtCompound mark = (CgNbtCompound) saves.get(e).get(MARK.name());
            assertEquals("its own form", "12,-3", mark.get("at", CgCodecs.STRING));
            assertTrue("in place of the fields", !mark.has("x") && !mark.has("y"));

            CgNetTestRig.Entity loaded = rig.entity(0, 64, 0);
            alive.add(loaded);
            saves.put(loaded, saves.get(e));
            CgNetEntities.enteredServer(loaded, "test:zombie");
            assertEquals("read back through it", 12, MARK.find(loaded).getInt(MARK_X));
            assertEquals(-3, MARK.find(loaded).getInt(MARK_Y));
        } finally {
            CgPlatform.provide(CgEntitySaves.HOST, null);
        }
    }

    @Test
    public void aSavedFormOnATypeThatSavesNothingIsRefused() {
        try {
            CgNetType.define("entitytest:unsaved_form").fields(CgNetField.ofInt("n")).perHolder()
                    .savedAs(MARK.saveForm).build();
            throw new AssertionError("a form nothing would ask for");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("saves no field"));
        }
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
