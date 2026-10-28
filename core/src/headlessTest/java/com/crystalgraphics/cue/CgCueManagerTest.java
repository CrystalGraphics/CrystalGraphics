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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.SpawnCondition;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.world.CgAttachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgCueManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CgVfxAssets assets;
    private CgCueManager cues;
    private final Recorder effects = new Recorder();
    private CgGameplayTag hit, crit, aura, none;

    /** Counts what the manager asks of its effects, allocating nothing. */
    static final class Recorder implements CgCueEffects {
        int bursts;
        float lastAge;
        final List<Held> made = new ArrayList<>();

        @Override
        public void burst(CgCueDefinition cue, Effects block, CgCueParameters at, float age) {
            bursts++;
            lastAge = age;
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
        write(ns, "tags.yaml", "_v: 1\ntags: [ cuemgr:cue.hit.crit, cuemgr:cue.aura, cuemgr:cue.none ]\n");
        write(ns, "cues/hit.yaml", "_v: 1\ntag: cuemgr:cue.hit\nkind: burst\nburst: { sounds: [ { sound: cuemgr:boom } ] }\n");
        write(ns, "cues/aura.yaml", "_v: 1\ntag: cuemgr:cue.aura\nkind: looping\n"
                + "application: { sounds: [ { sound: cuemgr:start } ] }\nrecurring: { sounds: [ { sound: cuemgr:pulse } ] }\n"
                + "events: [ { id: e1, event: hit_world, sounds: [ { sound: cuemgr:hit } ] } ]\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        assets = new CgVfxAssets(registry, CgJobLoop.create("cue-manager-test"));
        cues = new CgWorld().add(new CgCueManager(assets)).effects(effects);
        CgTagRegistry tags = CgTagRegistry.global();
        hit = tags.require("cuemgr:cue.hit");
        crit = tags.require("cuemgr:cue.hit.crit");
        aura = tags.require("cuemgr:cue.aura");
        none = tags.require("cuemgr:cue.none");
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
        // Ready now: a burst within the window plays with its age.
        cues.execute(hit, at);
        cues.update(0.05f);
        assertEquals(1, effects.bursts);
        assertEquals(0.05f, effects.lastAge, 1e-6);
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
