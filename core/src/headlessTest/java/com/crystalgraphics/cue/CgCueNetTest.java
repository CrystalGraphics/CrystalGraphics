package com.crystalgraphics.cue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceLocator;
import com.crystalgraphics.tag.CgTagRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgCueNetTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CgNetTestRig rig;
    private CgVfxAssets assets;
    private CgCueManager server, client;
    private final CgCueManagerTest.Recorder seen = new CgCueManagerTest.Recorder();
    private final List<CgSequencePlayer> sequences = new ArrayList<>();

    @After
    public void close() {
        if (rig != null) rig.close();
    }

    /** A server and {@code clients} players, every client's cues into one manager, sequences on a headless sequencer. */
    private void start(CgAssetRegistry registry, int clients, boolean timelines) {
        assets = new CgVfxAssets(registry, CgJobLoop.create("cue-net-test"));
        CgCueNet.install();
        rig = CgNetTestRig.of(clients);
        CgSequencer sequencer = new CgWorld(true).add(new CgSequencer());
        CgCueManager.SequenceStarter starter = !timelines ? null : (cue, sequence, at) -> {
            CgSequencePlayer p = sequencer.play(sequence, Map.of("caster", new CgSequenceLocator("caster")));
            sequences.add(p);
            return p;
        };
        server = new CgWorld(true).add(new CgCueManager(assets)).sequences(starter);
        client = new CgWorld().add(new CgCueManager(assets)).effects(seen).sequences(starter);
        CgCueNet.client(obj -> client);
    }

    /** Server ticks, three client frames each, loads running, until {@code done}. */
    private int ticks(int most, BooleanSupplier done) throws InterruptedException {
        int n = 0;
        while (n < most && !done.getAsBoolean()) {
            server.update(0.05f);
            rig.tick(1);
            for (int f = 0; f < 3; f++) client.update(0.05f / 3);
            assets.drain();
            assets.publish();
            Thread.sleep(1);
            n++;
        }
        return n;
    }

    private void ready(String id) throws InterruptedException {
        CgAssetRef<CgCueDefinition> ref = assets.declare(CgGameplayKinds.CUE, id);
        ref.request(CgLoadPriority.MOVE);
        ticks(3000, ref::ready);
        assertTrue(id + ": " + ref.failure(), ref.ready());
    }

    /** {@code -Pkits=novamod=...}: the Nova Cannon's cue, from its file, on two clients and one that arrives late. */
    @Test
    public void theNovaCannonPlaysFromItsFileOnTwoClientsAndALateJoiner() throws InterruptedException {
        String kits = System.getProperty("crystalgraphics.kits", "");
        String nova = null;
        for (String kit : kits.split(";")) if (kit.startsWith("novamod=")) nova = kit.substring("novamod=".length());
        Assume.assumeTrue("no -Pkits=novamod=...", nova != null);
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scan("novamod", Paths.get(nova), CgAssetSource.DEV);
        start(registry, 3, false);
        rig.latencyTicks(2);
        rig.client(2).moveTo(5000, 64, 0);   // out of range: the late joiner
        ready("novamod:nova_cannon");

        CgCuePlaying beam = server.add(CgTagRegistry.global().require("novamod:cue.nova_cannon"),
                new CgCueParameters().at(0, 64, 0).anchor(rig.at(0, 64, 0)).seed(7));
        beam.writes("radius", (time, out) -> out[0] = 1.1f);
        ticks(200, () -> seen.made.size() >= 2 && seen.made.get(1).starts == 1);
        assertEquals("two clients in range play it", 2, seen.made.size());
        assertEquals(1, seen.made.get(0).starts);
        assertTrue("the server plays it too: authoritative", beam.active() && beam.definition() != null);

        ticks(40, () -> false);   // two seconds on
        rig.client(2).moveTo(0, 64, 0);
        ticks(200, () -> seen.made.size() >= 3 && seen.made.get(2).starts == 1);
        assertEquals("the late joiner plays it", 3, seen.made.size());
        float age = seen.made.get(2).startAge;
        assertTrue("at its age: " + age, age > 1.9f);

        beam.remove();
        ticks(40, () -> seen.made.get(0).removes == 1 && seen.made.get(2).removes == 1);
        assertEquals(1, seen.made.get(0).removes);
        assertEquals(1, seen.made.get(2).removes);
    }

    private static void write(Path ns, String file, String text) throws IOException {
        Path p = ns.resolve(file);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private CgAssetRegistry beamKit() throws IOException {
        Path ns = tmp.newFolder("assets", "cuenet").toPath();
        write(ns, "tags.yaml", "_v: 1\ntags: [ cuenet:cue.beam, cuenet:cue.spark ]\n");
        write(ns, "cues/beam.yaml", "_v: 1\ntag: cuenet:cue.beam\nkind: looping\nauthoritative: true\nrange: 96\n"
                + "writes: { Head: { meaning: the head's distance from the root, replication: goal } }\n"
                + "fields: [ { name: level, type: int } ]\nsequence: cuenet:beam\n");
        write(ns, "sequences/beam.yaml", "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 6000]\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\ntracks:\n"
                + "  - { id: t1, kind: event, side: ours, binding: { role: caster }, sections: [ { id: a, keys: [[100, cuenet:glow]] } ] }\n");
        write(ns, "cues/spark.yaml", "_v: 1\ntag: cuenet:cue.spark\nkind: burst\nburst: { sounds: [ { sound: cuenet:pop } ] }\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scanResources(tmp.getRoot());
        return registry;
    }

    /**
     * A data-only beam: the server's head flies until an obstacle stops it, and a viewer's goal spring reaches where it
     * stopped without ever passing it.
     */
    @Test
    public void aGoalWriteStopsWhereTheServersDoesWithNoSlideThroughAnObstacle() throws InterruptedException, IOException {
        start(beamKit(), 2, false);
        rig.latencyTicks(2);
        ready("cuenet:beam");
        double[] t = {0};
        float stop = 50f;
        CgCuePlaying beam = server.add(CgTagRegistry.global().require("cuenet:cue.beam"),
                new CgCueParameters().at(0, 64, 0).anchor(rig.at(0, 64, 0)))
                .writes("Head", (time, out) -> out[0] = (float) Math.min(stop, 3 + 160 * (time - t[0])));
        beam.field("level", 3);
        ticks(1, () -> false);
        t[0] = 0.05;   // the head leaves as it starts
        ticks(100, () -> !rig.client(0).visible(CgCueNet.TYPE).isEmpty());
        CgNetObject obj = rig.client(0).visible(CgCueNet.TYPE).get(0);
        CgCueDefinition.Write head = assets.<CgCueDefinition>declare(CgGameplayKinds.CUE, "cuenet:beam").loaded().writes().get("Head");
        CgCueNet.Replica viewer = new CgCueNet.Replica(obj, 0, head);

        float[] out = new float[4];
        float most = 0;
        double time = 0;
        for (int tick = 0; tick < 60; tick++) {
            server.update(0.05f);
            rig.tick(1);
            for (int f = 0; f < 3; f++) {
                time += 0.05 / 3;
                viewer.write(time, out);
                most = Math.max(most, out[0]);
            }
        }
        assertTrue("never past the obstacle: " + most, most <= stop + 1e-3f);
        assertEquals("where the server's head stopped", stop, out[0], 0.01f);
        assertEquals("its own field", 3.0, obj.getDouble(CgCueNet.V.get(0)), 0);
    }

    @Test
    public void aBurstReachesOnlyThoseInRangeAndNeverALaterViewer() throws InterruptedException, IOException {
        start(beamKit(), 2, false);
        rig.client(1).moveTo(5000, 64, 0);
        ready("cuenet:spark");
        server.execute(CgTagRegistry.global().require("cuenet:cue.spark"), new CgCueParameters().at(0, 64, 0).anchor(rig.at(0, 64, 0)));
        ticks(20, () -> seen.bursts >= 1);
        assertEquals(1, seen.bursts);
        rig.client(1).moveTo(0, 64, 0);
        ticks(20, () -> false);
        assertEquals("fired: a later viewer never sees it", 1, seen.bursts);
        assertFalse(server.lateDropped() > 0);
    }

    @Test
    public void aLateJoinerStartsALoopingCueAtItsAgeWithItsSequenceSought() throws InterruptedException, IOException {
        start(beamKit(), 2, true);
        rig.client(1).moveTo(5000, 64, 0);
        ready("cuenet:beam");
        CgCuePlaying beam = server.add(CgTagRegistry.global().require("cuenet:cue.beam"),
                new CgCueParameters().at(0, 64, 0).anchor(rig.at(0, 64, 0)));
        ticks(200, () -> seen.made.size() == 1 && seen.made.get(0).starts == 1);
        assertEquals(1, seen.made.size());
        ticks(40, () -> false);
        rig.client(1).moveTo(0, 64, 0);
        ticks(200, () -> seen.made.size() == 2 && seen.made.get(1).starts == 1);
        float age = seen.made.get(1).startAge;
        assertTrue("at its age: " + age, age > 1.9f);
        CgSequencePlayer late = sequences.get(sequences.size() - 1);
        assertEquals("its sequence sought to its age", age, late.seconds(), 0.06);
        beam.kill();
        ticks(20, () -> seen.made.get(1).kills == 1);
        assertEquals(1, seen.made.get(1).kills);
        assertTrue(late.stopped());
    }
}
