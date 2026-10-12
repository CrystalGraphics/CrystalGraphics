package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.serialization.CgCodecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Server behaviours, predictions with keys, and predicted fields, on the rig. */
public class CgNetAuthorityTest {

    static final CgNetField<Integer> POWER = CgNetField.ofInt("power");
    static final CgNetField<Float> YAW = CgNetField.ofFloat("yaw").predicted()
            .sanitizedBy((obj, yaw) -> Math.max(-1f, Math.min(1f, yaw)));

    static final CgNetEvent<Integer> VENT = CgNetEvent.of("vent", CgCodecs.INT);

    static final CgNetType BEAM = CgNetType.define("test:beam").fields(POWER).events(VENT).linger(5).build();
    static final CgNetType WARD = CgNetType.define("test:ward2").fields(POWER).persisted().build();
    static final CgNetType SLASH = CgNetType.define("test:slash").fields(POWER).build();
    static final CgNetType SPARK = CgNetType.define("test:spark2").fields(POWER).build();
    static final CgNetType GHOST = CgNetType.define("test:ghost").fields(POWER).keepRefused().build();
    static final CgNetType TURRET = CgNetType.define("test:turret2").fields(POWER, YAW).build();

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();

    private final List<String> heard = new ArrayList<>();
    private CgNetTestRig rig;

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(2).saveTo(world.getRoot().toPath());
        for (CgNetType type : new CgNetType[]{BEAM, WARD, SLASH, SPARK, GHOST, TURRET}) {
            type.onClient(obj -> new CgNetBehaviour() {
                @Override
                public void spawned(CgNetObject o, float age) {
                    heard.add("spawned " + o.type().name() + (o.isPredicted() ? " predicted" : ""));
                }

                @Override
                public void changed(CgNetObject o, CgNetField<?> field) {
                    heard.add(field.name() + " " + o.get(field));
                }

                @Override
                public void event(CgNetObject o, CgNetEvent<?> event, Object value) {
                    heard.add(event.name() + " " + value);
                }

                @Override
                public void stopping(CgNetObject o) {
                    heard.add(o.type().name() + " stopping");
                }

                @Override
                public void despawned(CgNetObject o, boolean killed) {
                    heard.add(o.type().name() + (killed ? " killed" : " despawned"));
                }
            });
        }
        rig.tick(3);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    /** A server behaviour that counts its ticks into POWER and takes {@code finishing} ticks to finish. */
    private CgNetBehaviour counter(List<String> log, int finishing) {
        return new CgNetBehaviour() {
            int left = -1;

            @Override
            public void spawned(CgNetObject obj, float age) {
                log.add("spawned at " + (int) age);
            }

            @Override
            public void tick(CgNetObject obj) {
                if (left >= 0) left--;
                else obj.setInt(POWER, obj.getInt(POWER) + 1);
            }

            @Override
            public void despawned(CgNetObject obj, boolean killed) {
                log.add(killed ? "killed" : "despawned");
                left = finishing;
            }

            @Override
            public boolean done() {
                boolean done = left == 0;
                if (done) log.add("done");
                return done;
            }
        };
    }

    @Test
    public void theServerBehaviourTicksAndItsChangesLeaveThatTick() {
        List<String> log = new ArrayList<>();
        BEAM.onServer(obj -> counter(log, 3));
        CgNetObject beam = BEAM.spawn(rig.at(0, 0, 0)).start();
        rig.tick(3);
        assertEquals(List.of("spawned at 0"), log);
        assertEquals(3, beam.getInt(POWER));
        assertEquals("the client has this tick's value", 3, rig.client(0).visible(BEAM).get(0).getInt(POWER));

        beam.despawn();
        rig.tick(1);
        assertTrue("stopping, still its viewers'", rig.client(0).visible(BEAM).get(0).isStopping());
        rig.tick(5);
        assertEquals("ticked until done", List.of("spawned at 0", "despawned", "done"), log);
        assertEquals(0, rig.client(0).visible(BEAM).size());
    }

    @Test
    public void aGracefulEndFinishesInFrontOfItsViewersAndSpawnsForNobodyNew() {
        BEAM.onServer(obj -> new CgNetBehaviour() {
            int left = -1;

            @Override
            public void tick(CgNetObject o) {
                if (left > 0 && --left == 0) o.emit(VENT, 7);
            }

            @Override
            public void despawned(CgNetObject o, boolean killed) {
                left = 4;
            }

            @Override
            public boolean done() {
                return left == 0;
            }
        });
        rig.client(1).disconnect();
        CgNetObject beam = BEAM.spawn(rig.at(0, 0, 0)).start();
        rig.tick(2);
        assertEquals(CgNetTestRig.DIMENSION, beam.level());
        heard.clear();

        beam.despawn();
        rig.tick(1);
        rig.client(1).connect();
        rig.tick(2);
        assertTrue(beam.isStopping());
        assertTrue("still its viewer's", rig.client(0).visible(BEAM).get(0).isStopping());
        assertEquals("nobody new is sent it", 0, rig.client(1).visible(BEAM).size());

        rig.tick(2);
        assertEquals("told, then what it emitted finishing, then the end",
                List.of("test:beam stopping", "vent 7", "test:beam despawned"), heard);
        assertEquals(0, rig.client(0).visible(BEAM).size());
    }

    @Test
    public void aLingerEndsAtTheTypesCapAndAKillDoesNotLinger() {
        List<String> log = new ArrayList<>();
        BEAM.onServer(obj -> counter(log, 1000));
        BEAM.spawn(rig.at(0, 0, 0)).start();
        rig.tick(1);
        BEAM.forEach(CgNetObject::despawn);
        rig.tick(20);
        assertEquals("never done: dropped at the cap", List.of("spawned at 0", "despawned"), log);

        log.clear();
        BEAM.spawn(rig.at(0, 0, 0)).start();
        rig.tick(1);
        BEAM.forEach(CgNetObject::kill);
        rig.tick(1);
        assertEquals(List.of("spawned at 0", "killed"), log);
    }

    @Test
    public void aDormantObjectsBehaviourDoesNotTickAndAPersistedOneResumesItsAge() {
        List<String> log = new ArrayList<>();
        WARD.onServer(obj -> counter(log, 0));
        CgNetObject ward = WARD.spawn(CgAnchor.chunk(CgNetTestRig.DIMENSION, 0, 0)).start();
        rig.tick(2);
        rig.unloadChunk(0, 0).tick(5);
        assertEquals(2, ward.getInt(POWER));
        rig.loadChunk(0, 0).tick(8);
        assertEquals(10, ward.getInt(POWER));

        rig.restart().tick(1);
        assertEquals("spawned at 0", log.get(0));
        assertEquals("loaded with its age", "spawned at 15", log.get(1));
    }

    @Test
    public void aPredictionIsAdoptedWithoutADuplicateAndTheServersFieldsWin() {
        SLASH.acceptPredictionsFrom((peer, slash) -> {
            slash.setInt(POWER, Math.min(slash.getInt(POWER), 10));
            return true;
        });
        CgNetObject predicted = rig.client(0).predict(SLASH, CgAnchor.at(1, 0, 0)).setInt(POWER, 50).start();
        assertEquals("shown at once", List.of("spawned test:slash predicted"), heard);
        assertEquals(50, predicted.getInt(POWER));
        assertEquals(1, rig.client(0).visible(SLASH).size());
        heard.clear();

        rig.tick(3);
        assertFalse(predicted.isPredicted());
        assertEquals(1, SLASH.size());
        assertEquals(List.of(predicted), rig.client(0).visible(SLASH));
        assertEquals("the server's value, and only it", 10, predicted.getInt(POWER));
        assertTrue(predicted.isMine());
        heard.sort(null);
        assertEquals("the other client builds it once; the predictor only hears the correction",
                List.of("power 10", "spawned test:slash"), heard);
    }

    @Test
    public void aRefusedPredictionEndsAndAGhostOneFinishes() {
        SLASH.acceptPredictionsFrom((peer, slash) -> false);
        long refused = SLASH.refusedPredictions();
        rig.client(0).predict(SLASH, CgAnchor.at(1, 0, 0)).start();
        rig.tick(3);
        assertEquals(List.of("spawned test:slash predicted", "test:slash killed"), heard);
        assertEquals(refused + 1, SLASH.refusedPredictions());
        assertEquals(0, rig.client(0).visible(SLASH).size());

        heard.clear();
        rig.client(0).predict(GHOST, CgAnchor.at(1, 0, 0)).start();
        rig.tick(3);
        assertEquals("no rule: refused, kept as it was meant to finish",
                List.of("spawned test:ghost predicted", "test:ghost despawned"), heard);
    }

    @Test
    public void aDelayedPredictionIsAdoptedLateAndOnePastTheTimeoutEnds() {
        SLASH.acceptPredictionsFrom((peer, slash) -> true);
        rig.latencyTicks(15);
        rig.client(0).predict(SLASH, CgAnchor.at(1, 0, 0)).start();
        rig.tick(35);
        assertEquals("adopted, never duplicated", 1, rig.client(0).visible(SLASH).size());
        assertEquals(List.of("spawned test:slash predicted", "spawned test:slash"), heard);

        heard.clear();
        rig.latencyTicks(25);
        rig.client(0).predict(SLASH, CgAnchor.at(2, 0, 0)).start();
        rig.tick(45);
        assertEquals("the 40-tick timeout passed first", List.of("spawned test:slash predicted", "test:slash killed"), heard);
    }

    @Test
    public void aFiredPredictionOnAnEntityIsShownOnceByItsPredictor() {
        CgNetTestRig.Entity zombie = rig.entity(3, 0, 0);
        SPARK.acceptPredictionsFrom((peer, spark) -> spark.entity() == zombie);
        rig.client(0).predict(SPARK, CgAnchor.onEntity(zombie.id())).fire();
        rig.tick(3);
        assertEquals(List.of("spawned test:spark2 predicted", "spawned test:spark2"), heard);
        assertEquals(0, SPARK.refusedPredictions());
    }

    @Test
    public void anOwnersPredictedFieldIsShownAtOnceAndCorrectedOnlyWhenSanitized() {
        CgNetObject turret = TURRET.spawn(rig.at(0, 0, 0)).owner(rig.client(0).id()).start();
        rig.tick(2);
        CgNetObject mine = rig.client(0).visible(TURRET).get(0);
        heard.clear();

        mine.setFloat(YAW, 0.5f);
        assertEquals("applied here at once", List.of("yaw 0.5"), heard);
        rig.tick(3);
        assertEquals(0.5f, turret.getFloat(YAW), 0f);
        assertEquals("the others are sent it; the owner is not sent its own value back", List.of("yaw 0.5", "yaw 0.5"), heard);

        heard.clear();
        mine.setFloat(YAW, 7f);
        rig.tick(3);
        assertEquals(1f, turret.getFloat(YAW), 0f);
        assertEquals(1f, mine.getFloat(YAW), 0f);
        assertEquals("predicted, then corrected; the other client hears the clamp", List.of("yaw 7.0", "yaw 1.0", "yaw 1.0"), heard);
    }
}
