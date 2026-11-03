package com.crystalgraphics.sequence.spawn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/** Spawnables made and ended by the spawn phase, placed under their locators, and systems driven by their tracks. */
public class CgSequenceSpawnTest {

    private static final String SEQUENCE = "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 110]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "locators:\n  - { id: k1, name: palms, parent: { role: caster, socket: aim }, location: [0, 0, 0.6] }\n"
            + "spawnables:\n"
            + "  - { id: s1, name: charge, at: { locator: k1 }, system: mymod:charge }\n"
            + "  - { id: s2, name: burst, at: { role: caster, socket: aim, offset: [1, 0, 0] }, system: mymod:burst }\n"
            + "tracks:\n"
            + "  - id: t1\n    kind: system\n    binding: { spawnable: s1 }\n    sections:\n"
            + "      - { id: x1, range: [0, 44], keys: [[0, activate], [42, deactivate]] }\n"
            + "  - id: t2\n    kind: spawned\n    binding: { spawnable: s2 }\n    sections:\n"
            + "      - { id: x2, range: [20, 60], spawned: [[20, true]] }\n";

    /** The caster, its aim socket at (10, 64, 0), turned a quarter about +Y. */
    private static final class Caster implements CgSequenceRole {
        @Override
        public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = 10;
                point[1] = 64;
                point[2] = 0;
                if (turn != null) turn.identity().rotateY((float) Math.toRadians(90));
                return true;
            };
        }

        @Override
        public int slot(String name) {
            return -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
        }

        @Override
        public void get(int slot, float[] out) {
        }
    }

    /** A stand-in system recording what its track asks of it. */
    private static final class System implements CgSequenceSystem {
        final List<String> calls = new ArrayList<>();
        /** Each desired age a track asked for, in seconds. */
        final List<Float> ages = new ArrayList<>();
        final CgAttachment at;
        boolean active, destroyed, graceful;

        System(CgAttachment at) {
            this.at = at;
        }

        @Override public void activate(boolean reset) {
            if (!active || reset) calls.add(reset ? "reset" : "activate");
            active = true;
        }

        @Override public void deactivate() {
            if (active) calls.add("deactivate");
            active = false;
        }

        @Override public void kill() {
            calls.add("kill");
            active = false;
        }

        @Override public void detach() {
            calls.add("detach");
        }

        @Override public boolean active() {
            return active;
        }

        @Override public void age(float seconds) {
            ages.add(seconds);
        }

        @Override public void destroy(boolean graceful) {
            destroyed = true;
            this.graceful = graceful;
        }

        /** Emitters e01 and e02, each a stand-in of its own. */
        final Map<String, System> emitters = new HashMap<>();

        @Override public CgSequenceSystem emitter(String handle) {
            return handle.equals("e01") || handle.equals("e02") ? emitters.computeIfAbsent(handle, h -> new System(at)) : null;
        }
    }

    private final List<System> made = new ArrayList<>();

    private CgSequencer sequencer(boolean server) {
        CgSequencer sequencer = new CgWorld(server).add(new CgSequencer());
        sequencer.spawner("system", (player, spawnable, at) -> {
            System s = new System(at);
            made.add(s);
            return s;
        });
        return sequencer;
    }

    private static CgSequenceDefinition compile() {
        return CgSequenceDefinition.compile(CgAssetData.of("mymod:nova", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(SEQUENCE), null));
    }

    private static final String AT_ORIGIN = "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 40]\n"
            + "spawnables:\n  - { id: s1, name: spark, system: mymod:spark }\n"
            + "tracks:\n  - id: t1\n    kind: transform\n    binding: { spawnable: s1 }\n    sections:\n"
            + "      - { id: x1, range: [null, null], translation: [{ default: 1.0, keys: [] }, { default: 2.0, keys: [] },"
            + " { default: 0.0, keys: [] }] }\n";

    /**
     * An unattached spawnable's keyed transform is relative to the play's origin, Unreal's transform origin: a hit
     * spark played at a cue's point. Without one it is the world's.
     */
    @Test
    public void anUnattachedSpawnablesTransformIsRelativeToThePlaysOrigin() {
        CgSequenceDefinition d = CgSequenceDefinition.compile(CgAssetData.of("mymod:hit", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(AT_ORIGIN), null));
        CgSequencer sequencer = sequencer(false);
        sequencer.play(d, Map.of(), new Caster().attachment(null));
        sequencer.play(d, Map.of());
        sequencer.evaluate(0.05f);
        double[] p = new double[3];
        Quaternionf turn = new Quaternionf();
        assertTrue(made.get(0).at.place(p, turn));
        // (1, 2, 0) turned a quarter about +Y is (0, 2, -1), from the origin at (10, 64, 0)
        assertEquals(10, p[0], 1e-5);
        assertEquals(66, p[1], 1e-5);
        assertEquals(-1, p[2], 1e-5);
        assertEquals(new Quaternionf().rotateY((float) Math.toRadians(90)).w, turn.w, 1e-5);
        assertTrue(made.get(1).at.place(p, null));
        assertEquals(1, p[0], 1e-5);
        assertEquals(2, p[1], 1e-5);
    }

    /** A spawn section with no keys spawns for its whole range: Unreal's spawn section defaults to true. */
    @Test
    public void aSpawnSectionWithNoKeysSpawnsForItsRange() {
        String yaml = "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 40]\n"
                + "spawnables:\n  - { id: s1, name: spark, system: mymod:spark }\n"
                + "tracks:\n  - id: t1\n    kind: spawned\n    binding: { spawnable: s1 }\n    sections:\n"
                + "      - { id: x1, completion: keep_state }\n"
                + "  - id: t2\n    kind: system\n    binding: { spawnable: s1 }\n    sections:\n"
                + "      - { id: x2, keys: [[0, trigger]], completion: keep_state }\n";
        CgSequenceDefinition d = CgSequenceDefinition.compile(CgAssetData.of("mymod:hit", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(yaml), null));
        CgSequencer sequencer = sequencer(false);
        sequencer.play(d, Map.of());
        sequencer.evaluate(0.05f);
        assertEquals(1, made.size());
        assertTrue("its trigger at tick 0 fires the frame it is made", made.get(0).active);
    }

    private static final String TRIGGERED = "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [2, 42]\n"
            + "spawnables:\n  - { id: s1, name: spark, system: mymod:spark }\n"
            + "tracks:\n  - id: t1\n    kind: system\n    binding: { spawnable: s1 }\n    sections:\n"
            + "      - { id: x1, range: [2, 42], keys: [[4, trigger]], completion: keep_state }\n";

    /**
     * A seek, forwards or back, plays a keyed section's system from its age since the key it passed, as a desired-age
     * spawn section seeks: a step back lands on the frame play showed. A seek before the key stops it; a jump, as
     * Unreal's particle track evaluates one, fires nothing it skips and asks no age.
     */
    @Test
    public void aSeekPlaysAKeyedSystemFromItsAgeSinceTheKeyAJumpAsksNone() {
        CgSequenceDefinition d = CgSequenceDefinition.compile(CgAssetData.of("mymod:hit", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(TRIGGERED), null));
        CgSequencer sequencer = sequencer(false);
        CgSequencePlayer p = sequencer.play(d, Map.of());
        for (int i = 0; i < 10; i++) sequencer.evaluate(0.05f);          // tick 12: triggered at 4, playing on
        System spark = made.get(0);
        assertTrue(spark.active);
        assertTrue("forward play asks no age", spark.ages.isEmpty());

        p.seek(0.3);                                                     // back to tick 8: 4 ticks past the key
        sequencer.evaluate(0f);
        assertEquals(List.of(0.2f), spark.ages);
        assertTrue(spark.active);

        p.seek(0.5);                                                     // forward to tick 12
        sequencer.evaluate(0f);
        assertEquals(0.4f, spark.ages.get(1), 1e-6f);

        p.seek(0.05);                                                    // tick 3, before the key: stopped
        sequencer.evaluate(0f);
        assertEquals(2, spark.ages.size());
        assertFalse(spark.active);

        p.seek(0.5);
        sequencer.evaluate(0f);
        p.jumpTo(0.3);                                                   // a jump back: no age, its state kept
        sequencer.evaluate(0f);
        assertEquals(3, spark.ages.size());
        assertTrue(spark.active);
    }

    /** A stopped play's systems end their own way, as an interrupted montage's; a killed one's at once. */
    @Test
    public void aStoppedPlayEndsItsSystemsGracefullyAKilledOneAtOnce() {
        CgSequencer sequencer = new CgWorld(false).add(new CgSequencer());
        Map<CgSequencePlayer, System> charges = new HashMap<>();
        sequencer.spawner("system", (player, spawnable, at) -> {
            System s = new System(at);
            if (spawnable.name().equals("charge")) charges.put(player, s);
            return s;
        });
        CgSequencePlayer stopped = sequencer.play(compile(), Map.of("caster", new Caster()));
        CgSequencePlayer killed = sequencer.play(compile(), Map.of("caster", new Caster()));
        sequencer.evaluate(0.25f);
        stopped.stop();
        killed.kill();
        sequencer.evaluate(0.05f);
        assertTrue(charges.get(stopped).destroyed && charges.get(stopped).graceful);
        assertTrue(charges.get(killed).destroyed && !charges.get(killed).graceful);
    }

    @Test
    public void spawnablesAreMadeAndEndedAndTheirSystemsDriven() {
        CgSequencer sequencer = sequencer(false);
        CgSequencePlayer player = sequencer.play(compile(), Map.of("caster", new Caster()));
        sequencer.evaluate(0.25f);                                   // tick 5
        assertEquals(1, made.size());                                // s1 lives for the play; s2 waits for its track
        System charge = made.get(0);
        assertEquals(List.of("activate"), charge.calls);

        // The charge stands at the palms: the aim socket, then 0.6 forward along the socket's turned z.
        double[] point = new double[3];
        Quaternionf turn = new Quaternionf();
        assertTrue(charge.at.place(point, turn));
        assertEquals(10.6, point[0], 1e-5);
        assertEquals(64, point[1], 1e-5);
        assertEquals(0, point[2], 1e-5);

        sequencer.evaluate(1f);                                      // tick 25: s2 made, at its offset
        assertEquals(2, made.size());
        assertTrue(made.get(1).at.place(point, null));
        assertEquals(-1, point[2], 1e-5);                            // its x, a quarter turn about +Y, is -z
        sequencer.evaluate(1f);                                      // tick 45: the charge's deactivate key crossed
        assertEquals(List.of("activate", "deactivate"), charge.calls);
        sequencer.evaluate(1f);                                      // tick 65: s2's section is over
        assertTrue(made.get(1).destroyed);
        assertNull(player.spawned(1));
        player.stop();
        sequencer.evaluate(0f);
        assertTrue(charge.destroyed);                                // the play's end ends what it spawned
    }

    private static final String HANDLES = "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 25]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "spawnables:\n  - { id: s1, name: shuriken, at: { role: caster, socket: aim }, system: mymod:shuriken }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: system, binding: { spawnable: s1 }, handle: e01, sections: [ { id: a, range: [0, 25], keys: [[1, activate], [13, deactivate]] } ] }\n"
            + "  - { id: t2, kind: system, binding: { spawnable: s1 }, handle: e02, sections: [ { id: b, range: [0, 25], keys: [[1, activate]] } ] }\n";

    @Test
    public void aTrackNamingAHandleDrivesThatEmitterAlone() {
        CgSequencer sequencer = sequencer(false);
        sequencer.play(CgSequenceDefinition.compile(CgAssetData.of("mymod:shuriken", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(HANDLES), null)), Map.of("caster", new Caster()));
        for (int i = 0; i < 40; i++) sequencer.evaluate(0.1f);   // to its end at tick 25, then held there
        System shuriken = made.get(0);
        assertEquals(List.of(), shuriken.calls);
        assertEquals(List.of("activate", "deactivate"), shuriken.emitters.get("e01").calls);
        assertEquals(List.of("activate"), shuriken.emitters.get("e02").calls);

        // A handle the system lacks fails that play alone
        CgSequencer other = sequencer(false);
        CgSequencePlayer bad = other.play(CgSequenceDefinition.compile(CgAssetData.of("mymod:shuriken", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(HANDLES.replace("handle: e02", "handle: e09")), null)), Map.of("caster", new Caster()));
        other.evaluate(0.1f);
        assertTrue(bad.stopped());
        assertTrue(bad.failure() instanceof CgAssetException e && e.getMessage().contains("tracks.t2")
                && e.getMessage().contains("no emitter e09"));
    }

    @Test
    public void aKindWithNoSpawnerFailsAtPlayAndAServerSpawnsNothing() {
        CgSequencer bare = new CgWorld().add(new CgSequencer());
        try {
            bare.play(compile(), Map.of("caster", new Caster()));
            fail("played with no system spawner");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("spawnables.s1") && e.getMessage().contains("system"));
        }
        CgSequencer server = sequencer(true);
        server.play(compile(), Map.of("caster", new Caster()));
        server.evaluate(1f);
        assertTrue(made.isEmpty());
        assertFalse(server.players().isEmpty());
    }
}
