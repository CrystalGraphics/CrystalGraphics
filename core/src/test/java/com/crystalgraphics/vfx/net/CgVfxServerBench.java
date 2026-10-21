package com.crystalgraphics.vfx.net;

import com.crystalgraphics.collision.CollisionFakes;
import com.crystalgraphics.net.CgNetTesting;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgPlayerQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.vfx.CgVfxAuthorityTest;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertTrue;

/**
 * The server's tick under {@value #WAVES} authoritative waves in one level, one viewer: what a dedicated server
 * pays to run effects other players' gameplay depends on. Scene id {@code bench-server}.
 *
 * <pre>{@code
 * CG_BENCH_OUT=gl-debug-harness/harness-output/bench-server/bench-server.json \
 *     ./gradlew :core:test --tests "*CgVfxServerBench"
 * python gl-debug-harness/bench.py compare gl-debug-harness/harness-output/bench-server/bench-server.json
 * }</pre>
 *
 * <p>Each wave is held, aimed along the level's floor and fired after a warm-up, so the measured ticks cast and
 * publish every tick. The tick is the server half of a loop-back network: receiving, every effect's six steps and
 * the publish. Easy to get wrong: a sequence per player does not exist yet, so the load is waves alone.</p>
 */
public class CgVfxServerBench {

    static final int WAVES = Integer.getInteger("crystalgraphics.bench.server.waves", 100);
    private static final int WARMUP_TICKS = 200;
    private static final int MEASURED_TICKS = 600;
    /** A server tick, 20 Hz. */
    private static final long TARGET_NANOS = 50_000_000L;

    private static final CgVfxCue WAVE = CgVfxCue.define("bench_server:wave", CgEnergyWave.kamehameha(),
            (look, cue) -> CgEnergyWave.fromCue(look, cue)).authoritative().build();

    private static final class Body {
        final UUID id = UUID.randomUUID();
    }

    private static final Object OVERWORLD = new Object();
    private final Body body = new Body();

    private static final class Levels extends CollisionFakes.Levels {
        @Override public CgWorldQuery world(Object level) { return CgVfxAuthorityTest.FLOOR; }
        @Override public String dimension(Object handle) {
            return handle instanceof Body || handle == OVERWORLD ? "minecraft:overworld" : null;
        }
        @Override public boolean position(Object entity, double[] out) {
            if (!(entity instanceof Body)) return false;
            out[0] = 0;
            out[1] = 64;
            out[2] = 0;
            return true;
        }
        @Override public int entityId(Object entity) { return entity instanceof Body ? 7 : -1; }
        @Override public boolean alive(Object entity) { return entity instanceof Body; }
        @Override public Object level(Object handle, String dimension) {
            return "minecraft:overworld".equals(dimension) ? OVERWORLD : null;
        }
    }

    private static final class Players extends CollisionFakes.Players {
        @Override public UUID playerId(Object entity) { return entity instanceof Body ? ((Body) entity).id : null; }
    }

    private final class Loopback implements CgNetworkChannel {
        BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };
        @Override public void sendToServer(byte[] frame) { inbound.accept(body, frame); }
        @Override public void sendToPlayer(Object player, byte[] frame) { inbound.accept(null, frame); }
        @Override public void setInboundHandler(BiConsumer<Object, byte[]> handler) { inbound = handler; }
        @Override public int maxFrameBytes() { return 32_000; }
        @Override public boolean isAvailable() { return true; }
    }

    @Before
    public void setUp() {
        CgNetTesting.reset();
        CgVfxCueClient.resetForTesting();
        CgPlatform.provide(CgLevels.SERVICE, new Levels());
        CgPlatform.provide(CgPlayerQuery.SERVICE, new Players());
        CgNetwork.install(new Loopback(), player -> ((Body) player).id);
        CgNetwork.serverStarting(null);
        CgVfxCueClient.install();
    }

    @After
    public void tearDown() {
        CgNetTesting.reset();
        CgVfxCueClient.resetForTesting();
        CgPlatform.provide(CgLevels.SERVICE, null);
        CgPlatform.provide(CgPlayerQuery.SERVICE, null);
    }

    private static void tick() {
        CgNetwork.serverTick();
        CgNetwork.clientTick();
    }

    @Test
    public void theServerTickUnderAHundredWaves() throws IOException {
        CgNetwork.playerJoined(body.id, "Steve", () -> body);
        CgNetwork.clientConnected();
        for (int t = 0; t < 12; t++) tick();

        CgEnergyWave[] beams = new CgEnergyWave[WAVES];
        for (int i = 0; i < WAVES; i++) {
            CgVfxCue.Playing playing = WAVE.at(body, (i % 10) * 6, 64, (i / 10) * 6).toward(1, 0, 0).start();
            beams[i] = playing.<CgVfxSystemInstance<CgEnergyWave>>effect()
                    .set(CgEnergyWave.RELEASE, Float.POSITIVE_INFINITY).set(CgEnergyWave.AIM, 1, 0, 0).behaviour();
        }
        for (int t = 0; t < WARMUP_TICKS; t++) tick();
        for (CgEnergyWave beam : beams) beam.instance().set(CgEnergyWave.RELEASE, beam.instance().age());

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long[] nanos = new long[MEASURED_TICKS];
        long[] bytes = new long[MEASURED_TICKS];
        for (int t = 0; t < MEASURED_TICKS; t++) {
            long memory = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            CgNetwork.serverTick();
            nanos[t] = System.nanoTime() - start;
            bytes[t] = threads.getThreadAllocatedBytes(thread) - memory;
            CgNetwork.clientTick();
        }

        String line = String.format(Locale.ROOT, "[bench-server] %d waves: server tick median %.3f ms, p99 %.3f ms, max %.3f ms;"
                        + " %d B/tick allocated", WAVES, percentile(nanos, 500) / 1e6, percentile(nanos, 990) / 1e6,
                percentile(nanos, 1000) / 1e6, percentile(bytes, 500));
        System.out.println(line);
        String out = System.getenv("CG_BENCH_OUT");
        if (out != null && !out.isEmpty()) write(Paths.get(out), nanos, bytes);
        assertTrue("a server tick is 50 ms; " + line, percentile(nanos, 500) < TARGET_NANOS);
    }

    private static long percentile(long[] values, int permille) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) (((long) permille * sorted.length + 999L) / 1000L);
        return sorted[Math.max(0, Math.min(sorted.length, rank) - 1)];
    }

    private static String measure(long[] v) {
        long sum = 0;
        for (long x : v) sum += x;
        return String.format(Locale.ROOT, "{\"count\": %d, \"median\": %d, \"p99\": %d, \"p99.9\": %d, \"max\": %d, \"sum\": %d}",
                v.length, percentile(v, 500), percentile(v, 990), percentile(v, 999), percentile(v, 1000), sum);
    }

    /** bench.py's result format, version 1: the server tick as the frame's wall and cpu. */
    private static void write(Path path, long[] nanos, long[] bytes) throws IOException {
        String json = "{\n  \"version\": 1,\n  \"scene\": \"bench-server\",\n  \"cell\": \"server\",\n  \"commit\": \""
                + System.getProperty("crystalgraphics.harness.commit", "unknown") + "\",\n  \"targetNanos\": " + TARGET_NANOS
                + ",\n  \"frames\": " + nanos.length + ",\n  \"measures\": {\n    \"wall\": " + measure(nanos)
                + ",\n    \"cpu\": " + measure(nanos) + ",\n    \"gpu\": null,\n    \"allocatedBytes\": " + measure(bytes)
                + ",\n    \"directBytes\": null\n  },\n  \"hitches\": {\"count\": 0, \"ours\": 0, \"unexplained\": 0},\n"
                + "  \"gcCollections\": 0,\n  \"gcPauseMillis\": 0,\n"
                + "  \"jvm\": {\"compilations\": null, \"deoptimisations\": null, \"safepoints\": null, \"classLoads\": null},\n"
                + "  \"counters\": {}\n}\n";
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }
}
