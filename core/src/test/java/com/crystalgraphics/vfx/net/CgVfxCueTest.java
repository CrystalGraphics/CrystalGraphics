package com.crystalgraphics.vfx.net;

import com.crystalgraphics.collision.CollisionFakes;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.net.CgNetTesting;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgPlayerQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxAuthorityTest;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Cues over one player's loopback: what the server sends, the client builds, as the server sent it. */
public class CgVfxCueTest {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:cue");
    private static final CgVfxParam.Scalar RADIUS = SCHEMA.scalar("radius", 1f);
    private static final CgVfxParam.Color KI = SCHEMA.color("ki", 0xFFFFFFFF);
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).build();
    private static final CgNetField<Integer> LEVEL = CgNetField.ofInt("cue_test/level");

    /** What the factories built, and the plays they built each from. */
    private static final List<Probe> BUILT = new ArrayList<>();
    private static final List<CgVfxCue.Play> CUES = new ArrayList<>();
    /** Each target a client's seeker was given, and what each viewer heard confirmed. */
    private static final List<double[]> SEEN = new ArrayList<>();
    private static final List<String> CONFIRMED = new ArrayList<>();
    /** The plays each viewer's wave was built from, and the waves. */
    private static final List<CgVfxCue.Play> WAVE_CUES = new ArrayList<>();
    private static final List<CgVfxSystemInstance<?>> WAVES = new ArrayList<>();

    private static final CgVfxCue SPARK = CgVfxCue.define("cue_test:spark", LOOK, CgVfxCueTest::probe).fields(LEVEL).build();
    private static final CgVfxCue KEPT = CgVfxCue.define("cue_test:kept", LOOK, CgVfxCueTest::probe).persisted().build();
    private static final CgVfxCue SEEKER = CgVfxCue.define("cue_test:seeker", LOOK, (look, cue) -> {
        if (cue.hasTarget()) SEEN.add(new double[]{cue.tx(), cue.ty(), cue.tz(), cue.struck()});   // published before it was seen
        return new Seeker(cue.x(), cue.y(), cue.z());
    }).authoritative().build().onConfirmedHit(h -> CONFIRMED.add(h.kind() + " at y " + Math.round(h.y())));
    private static final CgVfxCue WAVE = CgVfxCue.define("cue_test:wave", CgEnergyWave.kamehameha(), (look, cue) -> {
        CgVfxSystemInstance<CgEnergyWave> wave = CgEnergyWave.fromCue(look, cue);
        if (!cue.authoritative()) {
            WAVE_CUES.add(cue);
            WAVES.add(wave);
        }
        return wave;
    }).authoritative().build();

    private static CgVfxEffect<?> probe(CgVfxLook look, CgVfxCue.Play cue) {
        CUES.add(cue);
        Probe probe = new Probe(cue.x(), cue.y(), cue.z());
        BUILT.add(probe);
        return probe;
    }

    static final class Probe extends CgVfxEffect<Probe> {
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
    static final class Seeker extends CgVfxEffect<Seeker> implements CgVfxCue.Live, CgVfxCue.Authoritative {
        private final CgHit hit = new CgHit();
        private double endX = Double.NaN, endY, endZ;

        Seeker(double x, double y, double z) {
            super(LOOK, x, y, z);
        }

        @Override
        protected void tick(float dt) {
            if (state() == State.STOPPING) die();
            if (!authoritative() || !Double.isNaN(endX)) return;
            if (world().ray().at(originX, originY, originZ).along(0, -1, 0, 100).blocksToo().first(hit)) {
                endX = hit.x();
                endY = hit.y();
                endZ = hit.z();
                hit(hit);
            }
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }

        @Override
        public void cueChanged(CgVfxCue.Play cue) {
            if (cue.hasTarget() && !authoritative()) SEEN.add(new double[]{cue.tx(), cue.ty(), cue.tz(), cue.struck()});
        }

        @Override
        public void publish(CgVfxCue.Play cue) {
            if (!Double.isNaN(endX)) cue.target(endX, endY, endZ).struck(42);
        }
    }

    private static final class Body {
        final UUID id = UUID.randomUUID();
        boolean alive = true;
    }

    /** The overworld's server level. */
    private static final Object OVERWORLD = new Object();

    private final class Levels extends CollisionFakes.Levels {
        /** Each level's blocks: none until a test gives some. */
        Function<Object, CgWorldQuery> blocks = level -> CgWorldQuery.NONE;

        @Override
        public CgWorldQuery world(Object level) {
            return blocks.apply(level);
        }

        @Override
        public String dimension(Object handle) {
            return handle instanceof Body || handle == OVERWORLD ? "minecraft:overworld" : null;
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
        public int entityId(Object entity) {
            return entity instanceof Body ? 7 : -1;
        }

        @Override
        public boolean alive(Object entity) {
            return entity instanceof Body && ((Body) entity).alive;
        }

        @Override
        public Path saveDirectory(Object handle) {
            return world.getRoot().toPath();
        }

        @Override
        public Object level(Object handle, String dimension) {
            return "minecraft:overworld".equals(dimension) ? OVERWORLD : null;
        }

    }

    private static final class Players extends CollisionFakes.Players {
        @Override
        public UUID playerId(Object entity) {
            return entity instanceof Body ? ((Body) entity).id : null;
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
    private final Levels levels = new Levels();

    @Before
    public void setUp() {
        reset();
        BUILT.clear();
        CUES.clear();
        SEEN.clear();
        CONFIRMED.clear();
        WAVE_CUES.clear();
        WAVES.clear();
        CgPlatform.provide(CgLevels.SERVICE, levels);
        CgPlatform.provide(CgPlayerQuery.SERVICE, new Players());
        CgNetwork.install(new Loopback(), player -> ((Body) player).id);
        CgNetwork.serverStarting(null);
        CgVfxCueClient.install();
    }

    @After
    public void tearDown() {
        reset();
        CgPlatform.provide(CgLevels.SERVICE, null);
        CgPlatform.provide(CgPlayerQuery.SERVICE, null);
    }

    private static void reset() {
        CgNetTesting.reset();
        CgVfxCueClient.resetForTesting();
    }

    private void join() {
        CgNetwork.playerJoined(body.id, "Steve", () -> body);
        CgNetwork.clientConnected();
        settle();
    }

    private static void settle() {
        ticks(12);
    }

    private static void ticks(int n) {
        for (int i = 0; i < n; i++) {
            CgNetwork.serverTick();
            CgNetwork.clientTick();
        }
    }

    @Test
    public void aOneShotIsBuiltAsTheServerSentIt() {
        join();
        SPARK.at(body, 1, 64, 2).toward(0, 0, 4).seed(0x12345600)
                .set(RADIUS, 3f).set(KI, 0xFF0000FF).setInt(LEVEL, 2).send();
        settle();

        assertEquals(1, BUILT.size());
        Probe probe = BUILT.get(0);
        CgVfxCue.Play cue = CUES.get(0);
        assertEquals(1.0, cue.x(), 0.0);
        assertEquals(1.0, cue.dz(), 1e-6);
        assertEquals("its own field", 2, cue.getInt(LEVEL));
        assertEquals((0x12345600 >>> 8) / (float) (1 << 24), probe.seed(), 0f);
        assertEquals(3f, probe.get(RADIUS), 0f);
        assertEquals(0f, probe.get(KI, 0), 0f);
        assertEquals(1f, probe.get(KI, 2), 0f);
    }

    @Test
    public void aFollowedOneCarriesTheEntityAndPoint() {
        join();
        SPARK.at(body, 0, 64, 0).follow(body, "crystalgraphics:hands_forward").keepOffset().send();
        settle();

        assertEquals(7, CUES.get(0).followingId());
        assertEquals("crystalgraphics:hands_forward", CUES.get(0).point());
        assertTrue(CUES.get(0).keepsOffset());
    }

    @Test
    public void aLateViewerStartsALastingOneAtItsAge() {
        SPARK.at(body, 0, 64, 0).start();
        ticks(40);
        join();

        assertEquals(1, BUILT.size());
        float age = CUES.get(0).age();
        assertTrue("joined at " + age, age >= 2f && age < 3f);
    }

    @Test
    public void aChangeReachesTheEffectsParameters() {
        join();
        CgVfxCue.Playing playing = SPARK.at(body, 0, 64, 0).set(RADIUS, 1f).start();
        settle();
        playing.set(RADIUS, 5f);
        settle();

        assertEquals(1, BUILT.size());
        assertEquals(5f, BUILT.get(0).get(RADIUS), 0f);
    }

    @Test
    public void stopIsGracefulAndKillIsAtOnce() {
        join();
        CgVfxCue.Playing stopped = SPARK.at(body, 0, 64, 0).start();
        CgVfxCue.Playing killed = SPARK.at(body, 0, 64, 0).start();
        settle();
        stopped.stop();
        killed.kill();
        settle();

        assertEquals(CgVfxEffect.State.STOPPING, BUILT.get(0).state());
        assertEquals(CgVfxEffect.State.DEAD, BUILT.get(1).state());
        assertEquals(0, SPARK.type().size());
    }

    @Test
    public void aLifetimeOrALostEntityEndsIt() {
        join();
        SPARK.at(body, 0, 64, 0).lifetime(0.2f).start();
        CgVfxCue.Playing following = SPARK.at(body, 0, 64, 0).follow(body, (String) null).start();
        settle();
        assertEquals(CgVfxEffect.State.STOPPING, BUILT.get(0).state());
        assertTrue(following.playing());

        body.alive = false;
        settle();
        assertEquals(CgVfxEffect.State.STOPPING, BUILT.get(1).state());
    }

    @Test
    public void aPersistedOneResumesAfterARestart() {
        KEPT.at(body, 0, 64, 0).lifetime(600f).start();
        CgNetwork.closeAll("server stopping");
        assertEquals(0, KEPT.type().size());

        CgNetwork.serverStarting(null);
        assertEquals(1, KEPT.type().size());
        join();
        assertEquals(1, BUILT.size());
    }

    @Test
    public void aPredictionIsRefusedUntilAllowedOutOfReachAndOverTheRate() {
        join();
        SPARK.at(0, 64, 0).predict();
        settle();
        assertEquals("played here at once", 1, BUILT.size());
        assertEquals(1, SPARK.refused());
        assertEquals("and kept playing, refused", CgVfxEffect.State.PLAYING, BUILT.get(0).state());

        AtomicInteger allowed = new AtomicInteger();
        SPARK.fromClients((player, play) -> allowed.incrementAndGet() > 0);
        SPARK.at(500, 64, 0).predict();
        settle();
        assertEquals("out of reach", 2, SPARK.refused());
        assertEquals(0, allowed.get());

        SPARK.at(1, 64, 0).predict();
        settle();
        assertEquals("adopted, not sent back as a second", 3, BUILT.size());
        assertEquals(1, allowed.get());

        for (int i = 0; i < 30; i++) SPARK.at(1, 64, 0).predict();
        settle();
        long overRate = SPARK.refused() - 2;
        assertTrue("some refused over the rate: " + overRate, overRate >= 10);
        assertEquals(30, allowed.get() - 1 + overRate);
    }

    @Test
    public void anAuthorityDecidesWhereItEndsAndHearsWhatItStrikes() {
        levels.blocks = level -> level == OVERWORLD ? CgVfxAuthorityTest.FLOOR : CgWorldQuery.NONE;
        join();
        List<String> heard = new ArrayList<>();
        CgVfxCue.Playing playing = SEEKER.at(body, 2, 64, 3).toward(0, -1, 0).start();
        playing.onHit(h -> heard.add(h.kind() + " at y " + Math.round(h.y()) + " on " + Thread.currentThread().getName()));
        settle();

        String thread = Thread.currentThread().getName();
        assertEquals("heard on the server's tick", List.of("BLOCK at y 0 on " + thread), heard);
        assertEquals("and confirmed to its viewer", List.of("BLOCK at y 0"), CONFIRMED);
        assertEquals("the viewer was given where it ends", 1, SEEN.size());
        assertEquals(2.0, SEEN.get(0)[0], 1e-6);
        assertEquals(0.0, SEEN.get(0)[1], 1e-6);
        assertEquals(3.0, SEEN.get(0)[2], 1e-6);
        assertEquals("and what it struck", 42.0, SEEN.get(0)[3], 0.0);

        playing.stop();
        settle();
        assertEquals(CgVfxEffect.State.DEAD, playing.effect().state());
        assertEquals(0, SEEKER.type().size());
    }

    @Test
    public void anAuthoritativeWaveIsDrivenOnTheServerLikeALocalOne() {
        levels.blocks = level -> CgVfxAuthorityTest.FLOOR;
        join();
        CgVfxCue.Playing playing = WAVE.at(body, 0, 64, 0).toward(0, 0, 1).start();
        CgVfxSystemInstance<CgEnergyWave> beam = playing.effect();
        beam.set(CgEnergyWave.RELEASE, Float.POSITIVE_INFINITY).set(CgEnergyWave.AIM, 1, 0, 0);
        settle();

        CgVfxCue.Play added = WAVE_CUES.get(0);
        assertEquals("built charging", Float.POSITIVE_INFINITY, added.get(CgEnergyWave.RELEASE), 0f);
        assertEquals("aimed as the server aimed it", 1.0, added.get(CgEnergyWave.AIM, 0), 1e-6);
        assertTrue("homing on the end the server found", added.hasTarget());

        float firedAt = beam.age();
        beam.set(CgEnergyWave.RELEASE, firedAt);
        settle();
        assertEquals("its release published", firedAt, WAVE_CUES.get(WAVE_CUES.size() - 1).get(CgEnergyWave.RELEASE), 0f);

        beam.stop();
        settle();
        assertFalse("stopping it ended the cue", playing.playing());
        ticks(200 + 2);   // the type's linger
        assertEquals("and its object, once the effect played out", 0, WAVE.type().size());
    }

    @Test
    public void whatTheServerSetsOnItsEffectReachesEveryViewer() {
        levels.blocks = level -> CgVfxAuthorityTest.FLOOR;
        join();
        CgVfxCue.Playing playing = WAVE.at(body, 0, 64, 0).toward(1, 0, 0).set(CgEnergyWave.RADIUS, 0.5f).start();
        CgVfxSystemInstance<?> beam = playing.effect();
        assertEquals("the server's effect is built with what the play set", 0.5f, beam.get(CgEnergyWave.RADIUS), 0f);
        settle();
        beam.set(CgEnergyWave.RADIUS, 0.4f);
        settle();
        assertEquals("a viewer playing it", 0.4f, WAVES.get(0).get(CgEnergyWave.RADIUS), 0f);
        CgNetField<Float> radius = WAVE.look.of(CgEnergyWave.RADIUS)[0];
        assertEquals("and one joining later", 0.4f, playing.object().getFloat(radius), 0f);

        playing.set(CgEnergyWave.RADIUS, 0.7f);
        settle();
        assertEquals("the server's effect takes the Playing's", 0.7f, beam.get(CgEnergyWave.RADIUS), 0f);
        assertEquals(0.7f, WAVES.get(0).get(CgEnergyWave.RADIUS), 0f);
    }

    @Test
    public void aTweenAndAnEndingOnTheServersEffectPlayOnEveryViewer() {
        levels.blocks = level -> CgVfxAuthorityTest.FLOOR;
        join();
        CgVfxCue.Playing playing = WAVE.at(body, 0, 64, 0).toward(1, 0, 0).start();
        CgVfxSystemInstance<?> beam = playing.effect();
        settle();
        beam.animate(CgEnergyWave.RADIUS, 2f, 0.5f, CgEasings.LINEAR);
        settle();
        CgVfxSystemInstance<?> viewer = WAVES.get(0);
        assertNull("a viewer's eases on its own clock", viewer.ending());

        beam.end(CgEnergyWave.FADE);
        settle();
        assertSame("a viewer's ends as the server's did", CgEnergyWave.FADE, viewer.ending());
        assertEquals("and one joining later reads it", CgEnergyWave.FADE.name(), playing.object().get(CgVfxCue.ENDING));
    }

    @Test(expected = IllegalStateException.class)
    public void hearingNeedsAnAuthority() {
        SPARK.at(body, 0, 64, 0).start().onHit(h -> { });
    }

    @Test
    public void aViewersWaveReportsTheEntityItsServerStruck() {
        CgVfxCue.Play cue = WAVE.at(0, 0, 0).toward(1, 0, 0).target(8, 0, 0).struck(42);
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxSystemInstance<CgEnergyWave> wave = CgVfx.owner("cue_test", vfx).play(CgEnergyWave.fromCue(CgEnergyWave.kamehameha(), cue));
        List<String> heard = new ArrayList<>();
        wave.onHit(h -> heard.add(h.part() + " " + h.kind() + " " + h.entityId()));
        for (int f = 0; f <= 600; f++) vfx.update(f / 60.0);

        assertEquals(List.of("beam ENTITY 42"), heard);
    }
}
