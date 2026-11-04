package com.crystalgraphics.cue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.sun.management.ThreadMXBean;
import org.joml.Vector3f;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.SpawnCondition;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.world.CgAttachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgCueManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CgVfxAssets assets;
    private CgCueManager cues;
    private final Recorder effects = new Recorder();
    private CgGameplayTag hit, crit, aura, none, flash;
    private CgWorld world;

    /** Counts what the manager asks of its effects, allocating nothing. */
    static final class Recorder implements CgCueEffects {
        int bursts;
        float lastAge;
        final CgCueParameters lastAt = new CgCueParameters();
        final List<Held> made = new ArrayList<>();

        @Override
        public void burst(CgCueDefinition cue, Effects block, CgCueParameters at, float age) {
            bursts++;
            lastAge = age;
            lastAt.set(at);
        }

        @Override
        public Lasting lasting(CgCueDefinition cue) {
            Held h = new Held();
            made.add(h);
            return h;
        }
    }

    static final class Held implements CgCueEffects.Lasting {
        int starts, removes, kills, resets, writes;
        float startAge;
        boolean done;

        @Override
        public void start(CgCueParameters at, float age) {
            starts++;
            startAge = age;
        }

        @Override
        public void write(Write write, float[] value) {
            writes++;
        }

        @Override
        public void remove(CgCueParameters at) {
            removes++;
        }

        @Override
        public boolean finished() {
            return done;
        }

        @Override
        public void kill() {
            kills++;
        }

        @Override
        public void reset() {
            resets++;
            done = false;
        }
    }

    private static void write(Path ns, String file, String text) throws IOException {
        Path p = ns.resolve(file);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    @Before
    public void kit() throws IOException {
        Path ns = tmp.newFolder("assets", "cuemgr").toPath();
        // What no cue owns is declared in code: a tag routed to its parent's cue, and one with no cue at all
        CgTagRegistry.global().register("cuemgr:cue.hit.crit");
        CgTagRegistry.global().register("cuemgr:cue.none");
        write(ns, "cues/hit.yaml", "_v: 1\ntag: cuemgr:cue.hit\nkind: burst\nburst: { sounds: [ { sound: cuemgr:boom } ] }\n");
        write(ns, "cues/aura.yaml", "_v: 1\ntag: cuemgr:cue.aura\nkind: looping\n"
                + "application: { sounds: [ { sound: cuemgr:start } ] }\nrecurring: { sounds: [ { sound: cuemgr:pulse } ] }\n"
                + "events: [ { id: e1, event: hit_world, sounds: [ { sound: cuemgr:hit } ] } ]\n");
        write(ns, "cues/flash.yaml", String.join("\n", "_v: 1", "tag: cuemgr:cue.flash", "kind: burst",
                "burst: { sequences: [ { sequence: cuemgr:flash } ] }", ""));
        write(ns, "sequences/flash.yaml", String.join("\n", "_v: 1", "tick_resolution: 20", "display_rate: 20", "range: [0, 20]", ""));
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        assets = new CgVfxAssets(registry, CgJobLoop.create("cue-manager-test"));
        world = new CgWorld();
        cues = world.add(new CgCueManager(assets)).effects(effects);
        CgTagRegistry tags = CgTagRegistry.global();
        hit = tags.require("cuemgr:cue.hit");
        crit = tags.require("cuemgr:cue.hit.crit");
        aura = tags.require("cuemgr:cue.aura");
        none = tags.require("cuemgr:cue.none");
        flash = tags.require("cuemgr:cue.flash");
    }

    /**
     * A burst whose block plays a sequence waits for the sequence too, then plays it in the world's sequencer at the
     * origin its effects give: Unreal's level sequence actor at the hit.
     */
    @Test
    public void aBurstsSequenceLoadsWithItThenPlaysAtItsOrigin() throws InterruptedException {
        CgSequencer sequencer = world.add(new CgSequencer());
        cues.execute(flash, new CgCueParameters().at(1, 2, 3));
        frames(0f, () -> effects.bursts == 1);
        CgAssetRef<CgSequenceDefinition> sequence = assets.declare(CgSequenceKinds.SEQUENCE, "cuemgr:flash");
        assertTrue("loaded before the burst played", sequence.ready());
        CgCueDefinition cue = assets.<CgCueDefinition>declare(CgGameplayKinds.CUE, "cuemgr:flash").loaded();
        CgSequencePlayer player = cues.playSequence(cue, cue.burst().sequences().get(0), (point, turn) -> {
            point[0] = 1;
            point[1] = 2;
            point[2] = 3;
            return true;
        }, 2f);
        assertNotNull(player);
        assertEquals("the play's scale, under its origin", 2f, player.origin().scale(null, new Vector3f()).x, 0f);
        assertEquals(1, sequencer.players().size());
        double[] at = new double[3];
        assertTrue(player.origin().place(at, null));
        assertEquals(3, at[2], 0);
    }

    /**
     * The inspector follows the last burst and its sequences: steps back seek, wrapping from 0 to the end less a frame;
     * forward from that comes round to 0, and from 0 queues a simulated frame; a pause holds the world's clock and puts
     * it back; a refire ends the hit it followed.
     */
    @Test
    public void theInspectorStepsTheLastBurstRoundItsFramesAndPausesTheClock() throws InterruptedException {
        CgSequencer sequencer = world.add(new CgSequencer());
        CgCueInspector inspector = world.add(new CgCueInspector());
        world.timeDilation(0.5f);
        cues.execute(flash, new CgCueParameters().at(1, 2, 3));
        frames(0f, () -> effects.bursts == 1);
        assertEquals(flash, inspector.current());
        CgCueDefinition cue = assets.<CgCueDefinition>declare(CgGameplayKinds.CUE, "cuemgr:flash").loaded();
        CgSequencePlayer player = cues.playSequence(cue, cue.burst().sequences().get(0), (point, turn) -> true, 1f);
        assertEquals(1.0, inspector.duration(), 1e-9);
        for (int i = 0; i < 25; i++) sequencer.evaluate(0.05f);     // played out, held at its end
        assertEquals(1.0, inspector.age(), 1e-6);
        double last = 1.0 - CgCueInspector.STEP;

        inspector.step(-1);                                         // from the end: the last frame
        assertTrue(inspector.paused());
        assertEquals(0f, world.timeDilation(), 0f);
        assertEquals(last, inspector.age(), 1e-6);
        inspector.step(1);                                          // from the last frame: round to 0
        assertEquals(0, inspector.age(), 1e-6);
        inspector.step(-1);                                         // from 0: the last frame
        assertEquals(last, inspector.age(), 1e-6);
        inspector.step(-2);
        assertEquals(last - 2 * CgCueInspector.STEP, inspector.age(), 1e-6);
        inspector.step(1);                                          // queued, simulated in the next frames
        assertEquals("a forward step waits for its frame", last - 2 * CgCueInspector.STEP, inspector.age(), 1e-6);

        inspector.setPaused(false);
        assertEquals("the speed it had", 0.5f, world.timeDilation(), 0f);
        inspector.refire();
        sequencer.evaluate(0f);
        assertTrue("the hit it followed ends at once", player.stopped());
        assertEquals(0, inspector.duration(), 0);
    }

    /**
     * A burst of particles alone is stepped through the systems it spawned: no length until they complete, so a step
     * back from 0 stays there; once complete, it wraps as a sequence does; a refire ends and releases them.
     */
    @Test
    public void theInspectorStepsTheSystemsABurstSpawnedOnceTheyComplete() throws InterruptedException {
        CgCueInspector inspector = world.add(new CgCueInspector());
        cues.execute(flash, new CgCueParameters().at(1, 2, 3));
        frames(0f, () -> effects.bursts == 1);
        Part system = new Part();
        inspector.spawned(system);
        system.seconds = 0.2;

        assertTrue("no length before it completes", Double.isNaN(inspector.duration()));
        assertEquals("Age: 0.200 / ? s", inspector.lines(CgCueInspector.Keys.DEFAULT).get(2));
        inspector.step(-1);
        assertEquals(0.2 - CgCueInspector.STEP, system.seconds, 1e-9);
        system.seconds = 0;
        inspector.step(-1);
        assertEquals("from 0 with no end: stays", 0, system.seconds, 1e-9);

        system.seconds = system.completed = 0.5;                    // played out
        assertEquals(0.5, inspector.duration(), 1e-9);
        inspector.step(-1);
        assertEquals("from the end: the last frame", 0.5 - CgCueInspector.STEP, system.seconds, 1e-9);
        inspector.step(1);
        assertEquals("round to 0", 0, system.seconds, 1e-9);

        inspector.refire();
        assertTrue(system.killed);
        assertTrue(system.released);
    }

    /** A system a burst spawned, as the cue's effects would hand it over. */
    private static final class Part implements CgCueInspector.Part {
        double seconds, completed = Double.NaN;
        boolean killed, released;

        @Override public double seconds() { return seconds; }
        @Override public double duration() { return completed; }
        @Override public void seek(double s) { seconds = s; }
        @Override public void kill() { killed = true; }
        @Override public void release() { released = true; }
    }

    /** Frames of {@code dt} with loads running between them, until {@code done}. */
    private void frames(float dt, BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 3000 && !done.getAsBoolean(); i++) {
            cues.update(dt);
            assets.drain();
            assets.publish();
            Thread.sleep(1);
        }
        assertTrue("timed out", done.getAsBoolean());
    }

    private void ready(String id) throws InterruptedException {
        CgAssetRef<CgCueDefinition> ref = assets.declare(CgGameplayKinds.CUE, id);
        ref.request(CgLoadPriority.MOVE);
        frames(0f, ref::ready);
    }

    @Test
    public void aTagWithoutACuePlaysItsNearestParents() throws InterruptedException {
        ready("cuemgr:hit");
        CgCueParameters at = new CgCueParameters().at(1, 2, 3);
        cues.execute(crit, at);
        cues.execute(hit, at);
        cues.execute(none, at);
        cues.update(0f);
        assertEquals(2, effects.bursts);
        assertEquals(1, cues.unrouted());
        // A burst cue does not handle add, and no ancestor's cue does.
        CgCuePlaying wrong = cues.add(hit, at);
        cues.update(0f);
        assertFalse(wrong.active());
        assertEquals(2, cues.unrouted());
        // Asked before playing, a tag answers the cue a play of it plays
        assertEquals("cuemgr:hit", cues.cue(crit).id());
        assertEquals("cuemgr:hit", cues.cue(hit).get().id());
        assertNull(cues.cue(none));
    }

    @Test
    public void aCueNotReadyWaitsThenPlaysAtItsAgeOrDropsALateBurst() throws InterruptedException {
        CgCueParameters at = new CgCueParameters();
        cues.execute(hit, at);
        CgCuePlaying p = cues.add(aura, at);
        cues.update(1f);   // queued a second before either cue is ready
        assertEquals(0, effects.bursts);
        assertTrue(p.active());
        frames(0f, () -> p.definition() != null);
        assertEquals(0, effects.bursts);
        assertEquals(1, cues.lateDropped());
        assertEquals(1, effects.made.get(0).starts);
        assertEquals(1f, effects.made.get(0).startAge, 1e-6);
        // Ready now: a burst plays at the clock it arrived at, so a hitch's whole step after it never makes it late.
        cues.execute(hit, at);
        cues.update(0.4f);
        assertEquals(1, effects.bursts);
        assertEquals(0f, effects.lastAge, 1e-6);
    }

    @Test
    public void aLoopingPlayRunsItsPhasesInOrderAndIsPooledPerCue() throws InterruptedException {
        CgCueParameters at = new CgCueParameters();
        CgCuePlaying p = cues.add(aura, at);
        p.event("hit_world", at);
        p.recur(at);
        p.remove();   // all before the cue is ready: none is lost or reordered
        frames(0f, () -> p.definition() != null);
        cues.update(0f);
        Held h = effects.made.get(0);
        assertEquals(1, h.starts);
        assertEquals(2, effects.bursts);   // the event and the recurrence
        assertEquals(1, h.removes);
        assertTrue("lingers until its effects end", p.active());
        h.done = true;
        cues.update(0f);
        assertFalse(p.active());
        assertEquals(1, h.resets);
        assertEquals(0, cues.playing());

        CgCuePlaying again = cues.add(aura, at);
        cues.update(0f);
        assertEquals("a second play reuses the pooled effects", 1, effects.made.size());
        assertEquals(2, h.starts);
        assertSame(h, effects.made.get(0));
        again.kill();
        cues.update(0f);
        assertEquals(1, h.kills);
        assertFalse(again.active());
    }

    @Test
    public void aPlayKilledBeforeItsCueIsReadyNeverStarts() throws InterruptedException {
        CgCuePlaying p = cues.add(aura, new CgCueParameters());
        p.kill();
        cues.update(0f);
        assertFalse(p.active());
        ready("cuemgr:aura");
        cues.update(0f);
        assertTrue(effects.made.isEmpty());
    }

    @Test
    public void aNetworkedPlayWithoutAnAnchorThrowsNamingItsCue() {
        CgCueManager server = new CgWorld(true).add(new CgCueManager(assets)).effects(effects);
        CgCueParameters at = new CgCueParameters().at(1, 2, 3);
        assertThrows(hit, () -> server.execute(hit, at));
        assertThrows(aura, () -> server.add(aura, at));
        assertThrows(aura, () -> cues.predict(aura, at, CgPredictionKey.open()));
        cues.execute(hit, at);   // a client's own play is sent nowhere and needs none
    }

    private static void assertThrows(CgGameplayTag tag, Runnable play) {
        try {
            play.run();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(tag + ": "));
            return;
        }
        throw new AssertionError("no throw for " + tag);
    }

    @Test
    public void spawnConditionsFollowShouldSpawn() {
        CgAttachment me = (point, turn) -> true, other = (point, turn) -> true;
        cues.localControl(a -> a == me);
        CgCueParameters mine = new CgCueParameters().instigator(me), theirs = new CgCueParameters().instigator(other);
        SpawnCondition local = new SpawnCondition(SpawnCondition.Source.INSTIGATOR, SpawnCondition.Policy.LOCAL_ONLY, 1f, List.of(), List.of());
        SpawnCondition notLocal = new SpawnCondition(SpawnCondition.Source.INSTIGATOR, SpawnCondition.Policy.NOT_LOCAL, 1f, List.of(), List.of());
        assertTrue(cues.spawns(local, mine, 0));
        assertFalse(cues.spawns(local, theirs, 0));
        assertTrue(cues.spawns(notLocal, theirs, 0));
        assertFalse(cues.spawns(notLocal, mine, 0));

        SpawnCondition dirt = new SpawnCondition(SpawnCondition.Source.INSTIGATOR, SpawnCondition.Policy.ALWAYS, 1f, List.of("dirt"), List.of());
        assertTrue(cues.spawns(dirt, new CgCueParameters().surface("dirt"), 0));
        assertFalse(cues.spawns(dirt, new CgCueParameters().surface("water"), 0));
        assertFalse(cues.spawns(dirt, new CgCueParameters(), 0));
        SpawnCondition dry = new SpawnCondition(SpawnCondition.Source.INSTIGATOR, SpawnCondition.Policy.ALWAYS, 1f, List.of(), List.of("water"));
        assertFalse(cues.spawns(dry, new CgCueParameters().surface("water"), 0));

        // A half chance: the same seed and entry agree everywhere, and about half of the seeds play.
        SpawnCondition half = new SpawnCondition(SpawnCondition.Source.INSTIGATOR, SpawnCondition.Policy.ALWAYS, 0.5f, List.of(), List.of());
        int played = 0;
        for (long seed = 0; seed < 10_000; seed++) {
            CgCueParameters s = new CgCueParameters().seed(seed);
            boolean a = cues.spawns(half, s, 3);
            assertEquals(a, cues.spawns(half, s, 3));
            if (a) played++;
        }
        assertTrue(String.valueOf(played), played > 4_700 && played < 5_300);
    }

    /** 300 bursts a second, five a frame at 60 frames a second, and a looping cue's writes pulled each frame. */
    @Test
    public void threeHundredBurstsASecondAllocateNothing() throws InterruptedException {
        ready("cuemgr:hit");
        ready("cuemgr:aura");
        CgCueParameters at = new CgCueParameters().at(4, 64, 4);
        CgCuePlaying loop = cues.add(aura, at).writes("BeamLength", (time, out) -> out[0] = (float) time);
        cues.update(0f);
        for (int warm = 0; warm < 600; warm++) {
            for (int i = 0; i < 5; i++) cues.execute(i % 2 == 0 ? hit : crit, at.seed(warm * 5L + i));
            cues.update(1f / 60f);
        }
        int before = effects.bursts;
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long quiet = Long.MAX_VALUE;
        for (int window = 0; window < 3; window++) {
            long from = threads.getThreadAllocatedBytes(thread);
            for (int frame = 0; frame < 60; frame++) {
                for (int i = 0; i < 5; i++) cues.execute(i % 2 == 0 ? hit : crit, at.seed(frame * 5L + i));
                cues.update(1f / 60f);
            }
            quiet = Math.min(quiet, threads.getThreadAllocatedBytes(thread) - from);
        }
        assertEquals(before + 3 * 300, effects.bursts);
        assertTrue(loop.active());
        assertEquals("bytes allocated over a second of 300 bursts", 0, quiet);
    }
}
