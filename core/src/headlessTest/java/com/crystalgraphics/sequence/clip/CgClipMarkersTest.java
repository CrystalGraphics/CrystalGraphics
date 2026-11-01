package com.crystalgraphics.sequence.clip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;
import com.crystalgraphics.sequence.spawn.CgSequenceSystem;
import com.crystalgraphics.sequence.event.CgSequenceEventListener.Side;

/** A GeckoLib clip's markers become its notify sequence, played inside a sequence or on the library's own playback. */
public class CgClipMarkersTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The system compiler's types, declared before any load here freezes them. */
    private static final CgAssetCompiler<?> SYSTEMS = CgVfxSystemAssets.COMPILER;

    private static final String CLIP = "{ \"format_version\": \"1.8.0\", \"animations\": { \"melee_6\": {\n"
            + "  \"animation_length\": 0.5, \"loop\": true,\n"
            + "  \"timeline\": { \"0.1\": \"begin=charge;\", \"0.25\": \"notify=swing_trail;\", \"0.4\": [\"end=charge;hit\"] },\n"
            + "  \"particle_effects\": { \"0.3\": { \"effect\": \"slash\", \"locator\": \"hand_r\" } },\n"
            + "  \"sound_effects\": { \"0.3\": { \"effect\": \"rpgcore:swing\" } } } } }";

    private static final String SHORT = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 100]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";

    private static final CgClipMarkers.Known ALL = new CgClipMarkers.Known() {
        @Override public boolean sequence(String id) {
            return Set.of("rpgcore:charge", "rpgcore:swing_trail", "rpgcore:open").contains(id);
        }

        @Override public boolean system(String id) {
            return id.equals("rpgcore:slash");
        }

        @Override public double length(String id) {
            return id.equals("rpgcore:open") ? Double.NaN : 0.1;
        }
    };

    private static final CgSequenceRole CASTER = new CgSequenceRole() {
        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.identity();
                return true;
            };
        }

        @Override public int slot(String name) {
            return 0;
        }

        @Override public void set(int slot, float[] v, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    };

    private static CgSequenceDefinition compile(String id, Map<?, ?> tree, Map<String, Object> deps) {
        CgAssetCompiler.Dependencies d = (CgType kind, String ref) -> deps.get(ref);
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, tree, null), d);
    }

    private static CgSequenceDefinition clip(String json) {
        Map<String, Object> deps = Map.of(
                "rpgcore:charge", compile("rpgcore:charge", (Map<?, ?>) CgAssetYaml.read(SHORT), Map.of()),
                "rpgcore:swing_trail", compile("rpgcore:swing_trail", (Map<?, ?>) CgAssetYaml.read(SHORT), Map.of()));
        return compile("rpgcore:core/melee/melee_6",
                CgClipMarkers.translate("rpgcore:core/melee/melee_6", "melee.animation.json", json, ALL), deps);
    }

    @Test
    public void eachMarkerBecomesItsSectionByTheTable() {
        CgSequenceDefinition d = clip(CLIP);
        Map<String, Integer> kinds = new TreeMap<>();
        for (CgSequenceTrack t : d.tracks()) kinds.merge(t.kind(), 1, Integer::sum);
        assertEquals(Map.of("subsequence", 2, "system", 1, "sound", 1, "event", 1), kinds);
        assertEquals("one spawnable, the slash", 1, d.spawnables().size());
        assertEquals("the caster's socket", "hand_r", d.spawnables().get(0).at().partName());
        assertEquals("rpgcore:core/melee/melee_6", d.id());
        assertEquals(List.of("rpgcore:core/melee/melee_6"),
                CgClipMarkers.ids("rpgcore", "animations/core/melee.animation.json", CLIP));
    }

    private static void fails(String json, String expected) {
        try {
            CgClipMarkers.translate("rpgcore:core/melee/melee_6", "melee.animation.json", json, ALL);
            fail("translated: " + expected);
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    @Test
    public void anUnknownIdOrAnUnpairedSpanFailsNamingFileAnimationTimeAndId() {
        fails(CLIP.replace("notify=swing_trail", "notify=missing"),
                "melee.animation.json: animation melee_6 at 0.2500s: rpgcore:missing names no sequence");
        fails(CLIP.replace("begin=charge;", ""), "at 0.4000s: rpgcore:charge ends with no begin open");
        fails(CLIP.replace("end=charge;", ""), "at 0.1000s: rpgcore:charge begins and is still open at the clip's end");
        fails(CLIP.replace("\"slash\"", "\"rpgcore:nothing\""), "at 0.3000s: rpgcore:nothing names no system");
        fails(CLIP.replace("notify=swing_trail", "notify=open"), "at 0.2500s: rpgcore:open has no length to play for");
    }

    /** A spawned system that records whether its play let it go or ended it. */
    private static final class Fired implements CgSequenceSystem {
        boolean active, released, destroyed, graceful;

        @Override public void activate(boolean reset) {
            active = true;
        }

        @Override public void deactivate() {
            active = false;
        }

        @Override public void kill() {
            active = false;
        }

        @Override public void detach() {
        }

        @Override public boolean active() {
            return active;
        }

        @Override public void age(float seconds) {
        }

        @Override public void destroy(boolean graceful) {
            destroyed = true;
            this.graceful = graceful;
        }

        @Override public void release() {
            released = true;
        }
    }

    private static final String KEY = "{ \"animations\": { \"melee_6\": { \"animation_length\": 0.5,"
            + "  \"particle_effects\": { \"0.1\": { \"effect\": \"slash\", \"locator\": \"hand_r\" } } } } }";

    private static Fired interrupted(boolean kill) {
        CgSequencer s = new CgWorld().add(new CgSequencer());
        List<Fired> made = new ArrayList<>();
        s.spawner("system", (player, spawnable, at) -> {
            Fired f = new Fired();
            made.add(f);
            return f;
        });
        CgSequencePlayer notifies = s.playClip(clip(KEY), CASTER, "rpgcore:core/melee/melee_6");
        for (double clip = 0.025; clip < 0.2; clip += 0.025) {
            notifies.drive(clip, false);
            s.evaluate(0.025f);
        }
        assertEquals("the key fired", 1, made.size());
        assertTrue(made.get(0).active);
        if (kill) notifies.kill();
        else notifies.stop();
        s.evaluate(0.025f);
        return made.get(0);
    }

    @Test
    public void aKeysSystemOutlivesAnInterruptedClip_butAKilledOneEndsAtOnce() {
        Fired stopped = interrupted(false);
        assertTrue("let go to play out, as Unreal's notify spawns and forgets", stopped.released);
        assertTrue("never ended by the clip's stop", !stopped.destroyed);
        Fired killed = interrupted(true);
        assertTrue("a kill ends it at once", killed.destroyed && !killed.graceful && !killed.released);
    }

    @Test
    public void theLibrarysPlaybackDrivesIt_eachLoopFiringAgain_andStopEndsIt() {
        CgSequencer s = new CgWorld(true).add(new CgSequencer());
        List<String> heard = new ArrayList<>();
        double[] now = {0};
        s.listen(Side.RPG, (p, phase, event, args) -> heard.add(event + "@" + Math.round(now[0] * 1000)));
        CgSequencePlayer notifies = s.playClip(clip(CLIP), CASTER, "rpgcore:core/melee/melee_6");
        double clip = 0;
        while (now[0] < 0.975 - 1e-9) {
            now[0] += 0.025;
            clip += 0.025;
            boolean looped = clip >= 0.5;
            if (looped) clip -= 0.5;
            notifies.drive(clip, looped);
            s.evaluate(0.025f);
        }
        assertEquals("the mod's own instruction, at its key each lap", List.of("hit@400", "hit@900"), heard);
        notifies.stop();
        s.evaluate(0.025f);
        assertTrue("stopped with its clip", s.players().stream().noneMatch(p -> p == notifies));
    }

    @Test
    public void aPlayerNoClipDrivesRefusesToBeDriven() {
        CgSequencer s = new CgWorld(true).add(new CgSequencer());
        CgSequencePlayer p = s.play(compile("rpgcore:charge", (Map<?, ?>) CgAssetYaml.read(SHORT), Map.of()), Map.of("caster", CASTER));
        try {
            p.drive(0.1, false);
            fail("drove a sequence no clip drives");
        } catch (IllegalStateException expected) {
        }
    }

    private Path kit() throws IOException {
        Path ns = tmp.newFolder("assets", "rpgcore").toPath();
        write(ns, "animations/core/melee.animation.json", CLIP);
        write(ns, "sequences/charge.yaml", SHORT);
        write(ns, "sequences/swing_trail.yaml", SHORT);
        write(ns, "vfx/systems/slash.yaml", "_v: 1\n");
        write(ns, "sequences/move.yaml", SHORT + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, "
                + "sections: [ { id: s1, range: [0, 100], clip: rpgcore:core/melee/melee_6 } ] }\n");
        return ns;
    }

    private static void write(Path ns, String file, String text) throws IOException {
        Path p = ns.resolve(file);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void theScanRegistersEachAnimation_andAnAnimationSectionTakesItsClipsNotifies() throws IOException, InterruptedException {
        kit();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scanResources(tmp.getRoot());
        assertNotNull(registry.entry(CgSequenceKinds.SEQUENCE, "rpgcore:core/melee/melee_6"));
        CgVfxAssets assets = new CgVfxAssets(registry, CgJobLoop.create("clip-markers-test"));
        CgAssetRef<CgSequenceDefinition> move = assets.declare(CgSequenceKinds.SEQUENCE, "rpgcore:move");
        move.request(CgLoadPriority.MOVE);
        for (int i = 0; i < 3000 && !move.ready() && move.failure() == null; i++) {
            assets.drain();
            assets.publish();
            Thread.sleep(1);
        }
        assertTrue("loaded: " + move.failure(), move.ready());
        assertEquals("its section plays the clip's notifies", 1, move.loaded().children().size());
        assertEquals("rpgcore:core/melee/melee_6", move.loaded().children().get(0).definition().id());
    }

    /** Cascade's particle notify suppresses a looping system, which nothing would end; here it fails at load. */
    @Test
    public void aLoopingSystemOnAParticleKeyFailsAtLoadNamingTheKey() throws IOException, InterruptedException {
        Path ns = kit();
        // No emitter_state: Niagara's default, looping for ever
        write(ns, "vfx/systems/slash.yaml", "_v: 1\nemitters:\n  - id: h1\n    name: glow\n    emitter:\n"
                + "      properties: { capacity: 4 }\n"
                + "      emitter_update:\n        - { id: s1, module: crystalgraphics:spawn_rate, inputs: { rate: 10 } }\n");
        CgAssetCompiler<?> was = CgAssetCompiler.of(CgParticleKinds.SYSTEM);
        CgAssetCompiler.register(CgParticleKinds.SYSTEM, SYSTEMS);
        try {
            CgAssetRegistry registry = new CgAssetRegistry();
            registry.registerEngine();
            registry.scanResources(tmp.getRoot());
            CgVfxAssets assets = new CgVfxAssets(registry, CgJobLoop.create("clip-markers-loop"));
            CgAssetRef<CgSequenceDefinition> clip = assets.declare(CgSequenceKinds.SEQUENCE, "rpgcore:core/melee/melee_6");
            clip.request(CgLoadPriority.MOVE);
            for (int i = 0; i < 3000 && !clip.ready() && clip.failure() == null; i++) {
                assets.drain();
                assets.publish();
                Thread.sleep(1);
            }
            assertNotNull("refused", clip.failure());
            assertTrue(clip.failure(), clip.failure().contains("animation melee_6 at 0.3000s: rpgcore:slash loops for ever"));
        } finally {
            CgAssetCompiler.register(CgParticleKinds.SYSTEM, was);
        }
    }

    @Test
    public void aSequencesFileAtTheClipsIdFailsTheScanNamingBoth() throws IOException {
        Path ns = kit();
        write(ns, "sequences/core/melee/melee_6.yaml", SHORT);
        CgAssetRegistry registry = new CgAssetRegistry();
        try {
            registry.scanResources(tmp.getRoot());
            fail("registered twice");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("melee.animation.json") && e.getMessage().contains("melee_6.yaml"));
        }
    }
}
