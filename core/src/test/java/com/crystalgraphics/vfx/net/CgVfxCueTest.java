package com.crystalgraphics.vfx.net;

import com.crystalgraphics.net.CgNetTesting;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.vfx.CgVfxAuthorityTest;
import com.crystalgraphics.vfx.CgVfxCollision;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.world.CgWorldQueries;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Cues over one player's loopback: what the server sends, the client builds, as the server sent it. */
public class CgVfxCueTest {

    private static final CgVfxSchema SCHEMA = new CgVfxSchema();
    private static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 1f);
    private static final CgVfxParam KI = SCHEMA.color("ki", 1f, 1f, 1f, 1f);
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).build();
    private static final String SPARK_ID = "cue_test:spark", SEEKER_ID = "cue_test:seeker", WAVE_ID = "cue_test:wave";
    /** Each target a client's seeker was given. */
    private static final List<double[]> SEEN = new ArrayList<>();

    /** What the factory built, and the play it built each from. */
    private static final List<Probe> BUILT = new ArrayList<>();
    private static final List<CgVfxCue.Play<?>> CUES = new ArrayList<>();
    /** The plays each viewer's wave was built from, and the waves. */
    private static final List<CgVfxCue.Play<?>> WAVE_CUES = new ArrayList<>();
    private static final List<CgEnergyWave> WAVES = new ArrayList<>();

    static final class Probe extends CgVfxEffect {
        Probe(double x, double y, double z) {
            super(LOOK, x, y, z);
        }

        @Override
        protected void tick(float dt) {
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    /** Goes along its direction to the first block: found on the server, given to its viewers. */
    static final class Seeker extends CgVfxEffect implements CgVfxCue.Live<Object>, CgVfxCue.Authoritative<Object> {
        private final CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
        private double dx, dy = -1, dz, endX = Double.NaN, endY, endZ;
        private boolean published;

        Seeker(double x, double y, double z) {
            super(LOOK, x, y, z);
        }

        @Override
        protected void tick(float dt) {
            if (state() == State.STOPPING) die();
            if (!authoritative() || !Double.isNaN(endX)) return;
            if (raycastAll(originX, originY, originZ, dx, dy, dz, 100, 0.2, -1, hit)) {
                endX = hit.x;
                endY = hit.y;
                endZ = hit.z;
                collided(CgVfxCollision.Hit.BLOCK, -1, endX, endY, endZ, 0f, 1f, 0f, 0f, 0f, 0f);
            }
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }

        @Override
        public void cueChanged(CgVfxCue.Play<Object> cue) {
            if (cue.hasTarget() && !authoritative()) SEEN.add(new double[]{cue.tx(), cue.ty(), cue.tz(), cue.struck()});
        }

        @Override
        public boolean publish(CgVfxCue.Play<Object> cue) {
            if (Double.isNaN(endX) || published) return false;
            published = true;
            cue.target(endX, endY, endZ).struck(42);
            return true;
        }
    }

    private static final class Body {
        final UUID id = UUID.randomUUID();
        boolean alive = true;
    }

    private final class Players implements CgServerPlayers {
        @Override
        public String dimension(Object handle) {
            return handle instanceof Body ? "minecraft:overworld" : null;
        }

        @Override
        public boolean position(Object entity, double[] out) {
            if (!(entity instanceof Body)) return false;
            out[0] = 0;
            out[1] = 64;
            out[2] = 0;
            return true;
        }

        @Override
        public UUID playerId(Object entity) {
            return entity instanceof Body ? ((Body) entity).id : null;
        }

        @Override
        public int entityId(Object entity) {
            return entity instanceof Body ? 7 : -1;
        }

        @Override
        public boolean alive(Object entity) {
            return entity instanceof Body && ((Body) entity).alive;
        }

        @Override
        public void trackingChunk(Object level, int chunkX, int chunkZ, Consumer<UUID> out) {
        }

        @Override
        public void trackingEntity(Object entity, Consumer<UUID> out) {
        }

        @Override
        public Path saveDirectory(Object handle) {
            return world.getRoot().toPath();
        }
    }

    private final class Loopback implements CgNetworkChannel {
        BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        @Override public void sendToServer(byte[] frame) { inbound.accept(body, frame); }
        @Override public void sendToPlayer(Object player, byte[] frame) { inbound.accept(null, frame); }
        @Override public void setInboundHandler(BiConsumer<Object, byte[]> handler) { inbound = handler; }
        @Override public int maxFrameBytes() { return 32_000; }
        @Override public boolean isAvailable() { return true; }
    }

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();

    private final Body body = new Body();
    private CgVfxCue<CgVfxParams> spark;

    @BeforeClass
    public static void registerOnce() {
        reset();
        CgVfxRegistry.register(CgVfxCue.declare(SPARK_ID), LOOK, (look, cue) -> {
            CUES.add(cue);
            Probe probe = new Probe(cue.x(), cue.y(), cue.z());
            BUILT.add(probe);
            return probe;
        });
        CgVfxRegistry.register(CgVfxCue.declare(SEEKER_ID), LOOK, (look, cue) -> {
            if (cue.hasTarget()) SEEN.add(new double[]{cue.tx(), cue.ty(), cue.tz(), cue.struck()});   // published before it was added
            return new Seeker(cue.x(), cue.y(), cue.z());
        });
        CgVfxRegistry.register(CgVfxCue.declare(WAVE_ID), CgEnergyWave.kamehameha(), (look, cue) -> {
            CgEnergyWave wave = CgEnergyWave.fromCue(look, cue);
            if (!cue.authoritative()) {
                WAVE_CUES.add(cue);
                WAVES.add(wave);
            }
            return wave;
        });
    }

    @Before
    public void setUp() {
        reset();
        BUILT.clear();
        CUES.clear();
        SEEN.clear();
        WAVE_CUES.clear();
        WAVES.clear();
        CgPlatform.provide(CgServerPlayers.SERVICE, new Players());
        CgNetwork.install(new Loopback(), player -> ((Body) player).id);
        spark = CgVfxCue.declare(SPARK_ID);
        CgVfxCueClient.install();
    }

    @After
    public void tearDown() {
        reset();
        CgPlatform.provide(CgServerPlayers.SERVICE, null);
        CgPlatform.provide(CgLevelQueries.SERVICE, null);
    }

    private static void reset() {
        CgNetTesting.reset();
        CgVfxWire.resetForTesting();
        CgVfxCueClient.resetForTesting();
    }

    private void join() {
        CgNetwork.playerJoined(body.id, "Steve", () -> body);
        CgNetwork.clientConnected();
        settle();
    }

    private static void settle() {
        for (int i = 0; i < 12; i++) {
            CgNetwork.serverTick();
            CgNetwork.clientTick();
        }
    }

    @Test
    public void aOneShotIsBuiltAsTheServerSentIt() {
        join();
        spark.at(body, 1, 64, 2).toward(0, 0, 4).seed(0x12345600)
                .params(CgVfxParams.of().set("radius", 3f).color("ki", 0xFF0000FF)).send();
        settle();

        assertEquals(1, BUILT.size());
        Probe probe = BUILT.get(0);
        CgVfxCue.Play<?> cue = CUES.get(0);
        assertEquals(1.0, cue.x(), 0.0);
        assertEquals(1.0, cue.dz(), 1e-9);
        assertEquals((0x12345600 >>> 8) / (float) (1 << 24), probe.seed(), 0f);
        assertEquals(3f, probe.get(RADIUS), 0f);
        assertEquals(0f, probe.get(KI, 0), 0f);
        assertEquals(1f, probe.get(KI, 2), 0f);
    }

    @Test
    public void exceptLeavesOutThePlayerWhoAlreadyShowedIt() {
        join();
        spark.at(body, 0, 64, 0).except(body).send();
        settle();

        assertEquals(0, BUILT.size());
    }

    @Test
    public void aFollowedOneCarriesTheEntityAndPoint() {
        join();
        spark.at(body, 0, 64, 0).follow(body, "crystalgraphics:hands_forward").keepOffset().send();
        settle();

        assertEquals(7, CUES.get(0).following());
        assertEquals("crystalgraphics:hands_forward", CUES.get(0).point());
        assertTrue(CUES.get(0).keepsOffset());
    }

    @Test
    public void aLateViewerStartsALastingOneAtItsAge() {
        spark.at(body, 0, 64, 0).start();
        for (int i = 0; i < 40; i++) CgNetwork.serverTick();
        join();

        assertEquals(1, BUILT.size());
        float age = CUES.get(0).age();
        assertTrue("joined at " + age, age >= 2f && age < 3f);
    }

    @Test
    public void aChangeReachesTheEffectsParameters() {
        join();
        CgVfxCue.Playing<CgVfxParams> playing = spark.at(body, 0, 64, 0)
                .params(CgVfxParams.of().set("radius", 1f)).start();
        settle();
        playing.params(CgVfxParams.of().set("radius", 5f));
        settle();

        assertEquals(1, BUILT.size());
        assertEquals(5f, BUILT.get(0).get(RADIUS), 0f);
    }

    @Test
    public void stopIsGracefulAndKillIsAtOnce() {
        join();
        CgVfxCue.Playing<CgVfxParams> stopped = spark.at(body, 0, 64, 0).start();
        CgVfxCue.Playing<CgVfxParams> killed = spark.at(body, 0, 64, 0).start();
        settle();
        stopped.stop();
        killed.kill();
        settle();

        assertEquals(CgVfxEffect.State.STOPPING, BUILT.get(0).state());
        assertEquals(CgVfxEffect.State.DEAD, BUILT.get(1).state());
        assertEquals(0, CgVfxWire.playing.size());
    }

    @Test
    public void aLifetimeOrALostEntityEndsIt() {
        join();
        spark.at(body, 0, 64, 0).lifetime(0.2f).start();
        CgVfxCue.Playing<CgVfxParams> following = spark.at(body, 0, 64, 0).follow(body, null).start();
        settle();
        assertEquals(CgVfxEffect.State.STOPPING, BUILT.get(0).state());
        assertTrue(following.playing());

        body.alive = false;
        settle();
        assertEquals(CgVfxEffect.State.STOPPING, BUILT.get(1).state());
    }

    @Test
    public void aPersistedOneResumesAfterARestart() {
        spark.at(body, 0, 64, 0).lifetime(600f).persist().start();
        CgNetwork.closeAll("server stopping");
        assertEquals(0, CgVfxWire.kept.size());

        CgNetwork.serverStarting(null);
        assertEquals(1, CgVfxWire.kept.size());
        join();
        assertEquals(1, BUILT.size());
    }

    @Test
    public void aPredictionIsRefusedUntilAllowedAndRateLimited() {
        join();
        spark.at(0, 64, 0).predict();
        settle();
        assertEquals(1, BUILT.size());
        assertEquals(1, spark.refused());

        AtomicInteger allowed = new AtomicInteger();
        spark.fromClients((player, play) -> allowed.incrementAndGet() >= 0);
        spark.at(500, 64, 0).predict();
        settle();
        assertEquals(2, spark.refused());

        for (int i = 0; i < CgVfxWire.BURST + 5; i++) spark.at(1, 64, 0).predict();
        settle();
        assertEquals((int) CgVfxWire.BURST, allowed.get());
        assertEquals(7, spark.refused());
    }

    @Test
    public void anAuthorityDecidesWhereItEndsAndHearsWhatItStrikes() {
        CgPlatform.provide(CgLevelQueries.SERVICE, new CgLevelQueries() {
            @Override public CgWorldQuery world(Object level) { return level == body ? CgVfxAuthorityTest.FLOOR : CgWorldQuery.NONE; }
            @Override public CgEntityQuery entities(Object level) { return CgEntityQuery.NONE; }
        });
        CgVfxCue<CgVfxParams> seeker = CgVfxCue.declare(SEEKER_ID).authoritative();
        join();
        List<String> heard = new ArrayList<>();
        CgVfxCue.Playing<CgVfxParams> playing = seeker.at(body, 2, 64, 3).toward(0, -1, 0).start();
        playing.onCollide(c -> heard.add(c.hit() + " at y " + Math.round(c.y()) + " on " + Thread.currentThread().getName()));
        settle();

        String thread = Thread.currentThread().getName();
        assertEquals("heard on the server's tick", List.of("BLOCK at y 0 on " + thread), heard);
        assertEquals("the viewer was given where it ends", 1, SEEN.size());
        assertEquals(2.0, SEEN.get(0)[0], 1e-6);
        assertEquals(0.0, SEEN.get(0)[1], 1e-6);
        assertEquals(3.0, SEEN.get(0)[2], 1e-6);
        assertEquals("and what it struck", 42.0, SEEN.get(0)[3], 0.0);

        playing.stop();
        settle();
        assertEquals(CgVfxEffect.State.DEAD, playing.effect().state());
    }

    @Test
    public void anAuthoritativeWaveIsDrivenOnTheServerLikeALocalOne() {
        CgPlatform.provide(CgLevelQueries.SERVICE, new CgLevelQueries() {
            @Override public CgWorldQuery world(Object level) { return CgVfxAuthorityTest.FLOOR; }
            @Override public CgEntityQuery entities(Object level) { return CgEntityQuery.NONE; }
        });
        CgVfxCue<CgVfxParams> cue = CgVfxCue.declare(WAVE_ID).authoritative();
        join();
        CgVfxCue.Playing<CgVfxParams> playing = cue.at(body, 0, 64, 0).toward(0, 0, 1).start();
        CgEnergyWave beam = playing.effect();
        beam.hold().aim(1, 0, 0);
        settle();

        CgVfxCue.Play<?> added = WAVE_CUES.get(0);
        assertTrue("built charging", ((CgVfxParams) added.params()).get(CgEnergyWave.CUE_HOLD, 0f) > 0f);
        assertEquals("aimed as the server aimed it", 1.0, added.dx(), 1e-6);
        assertTrue("homing on the end the server found", added.hasTarget());

        float firedAt = beam.age();
        beam.fire();
        settle();
        float[] published = {Float.NaN};
        CgVfxWire.playing.forEach(h -> published[0] = ((CgVfxParams) h.value().params()).get(CgEnergyWave.CUE_FIRE, Float.NaN));
        assertEquals("its release published", firedAt, published[0], 0f);

        beam.stop();
        settle();
        assertFalse("stopping it ended the cue", playing.playing());
        assertEquals(0, CgVfxWire.playing.size());
    }

    @Test
    public void whatTheServerSetsOnItsEffectReachesEveryViewer() {
        CgPlatform.provide(CgLevelQueries.SERVICE, new CgLevelQueries() {
            @Override public CgWorldQuery world(Object level) { return CgVfxAuthorityTest.FLOOR; }
            @Override public CgEntityQuery entities(Object level) { return CgEntityQuery.NONE; }
        });
        CgVfxCue<CgVfxParams> cue = CgVfxCue.declare(WAVE_ID).authoritative();
        join();
        CgVfxCue.Playing<CgVfxParams> playing = cue.at(body, 0, 64, 0).toward(1, 0, 0)
                .params(CgVfxParams.of().set("radius", 0.5f)).start();
        settle();
        CgEnergyWave beam = playing.effect();
        beam.set(CgEnergyWave.RADIUS, 0.4f);
        settle();
        assertEquals("a viewer playing it", 0.4f, WAVES.get(0).get(CgEnergyWave.RADIUS), 0f);

        playing.params(CgVfxParams.of().set("unrelated", 1f));
        settle();
        float[] carried = {Float.NaN};
        CgVfxWire.playing.forEach(h -> carried[0] = ((CgVfxParams) h.value().params()).get("radius", Float.NaN));
        assertEquals("and one joining later", 0.4f, carried[0], 0f);
        assertEquals("a params call sets on top", 0.4f, beam.get(CgEnergyWave.RADIUS), 0f);

        playing.params(CgVfxParams.of().set("radius", 0.7f));
        settle();
        assertEquals("the server's effect takes the cue's", 0.7f, beam.get(CgEnergyWave.RADIUS), 0f);
        assertEquals(0.7f, WAVES.get(0).get(CgEnergyWave.RADIUS), 0f);
    }

    @Test(expected = IllegalStateException.class)
    public void hearingNeedsAnAuthority() {
        spark.at(body, 0, 64, 0).start().onCollide(c -> { });
    }

    @Test
    public void aViewersWaveReportsTheEntityItsServerStruck() {
        CgVfxCue.Play<Object> cue = new CgVfxCue.Play<>(null, "cue_test:wave", 0, 0, 0);
        cue.toward(1, 0, 0).target(8, 0, 0).struck(42);
        CgVfxSystem vfx = new CgVfxSystem();
        CgEnergyWave wave = CgVfx.owner("cue_test", vfx).play(CgEnergyWave.fromCue(CgEnergyWave.kamehameha(), cue));
        List<String> heard = new ArrayList<>();
        wave.onCollide(c -> heard.add(c.part() + " " + c.hit() + " " + c.entity()));
        for (int f = 0; f <= 600; f++) vfx.update(f / 60.0);

        assertEquals(List.of("beam ENTITY 42"), heard);
    }
}
