package com.crystalgraphics.sequence.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** {@code relation: self}: a section plays on the screen of the viewer whose character the role is, and no other. */
public class CgRelationConditionTest {

    private static final String HEAD = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 800]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";

    /** A fighter with one slot, {@code fov}. */
    private static final class Fighter implements CgSequenceRole {
        float fov;

        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> true;
        }

        @Override public int slot(String name) {
            return name.equals("fov") ? 0 : -1;
        }

        @Override public void set(int slot, float[] v, int mask) {
            fov = v[0];
        }

        @Override public void get(int slot, float[] out) {
            out[0] = fov;
        }
    }

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgSequenceDefinition compile(String is) {
        String yaml = HEAD + "tracks:\n  - id: t1\n    kind: property\n    binding: { role: caster }\n    property: fov\n    sections:\n"
                + "      - { id: s1, range: [0, 800], value: [[0, 50]], condition: { kind: crystalgraphics:relation, role: caster, is: " + is + " } }\n";
        CgAssetCompiler.Dependencies deps = (CgType kind, String id) -> null;
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), deps);
    }

    private static float played(CgSequenceDefinition d, Fighter caster, Fighter me) {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        sequencer.viewer(target -> target == me);
        sequencer.play(d, Map.of("caster", caster));
        sequencer.evaluate(0.1f);
        return caster.fov;
    }

    @Test
    public void selfPlaysOnlyOnTheCastersOwnScreen() {
        CgSequenceDefinition d = compile("[self]");
        Fighter mine = new Fighter();
        assertEquals("the caster's own client", 50f, played(d, mine, mine), 0f);
        Fighter theirs = new Fighter();
        assertEquals("another client: not played", 0f, played(d, theirs, new Fighter()), 0f);
    }

    /** What a spawner made: its name and its keyed weight. */
    private static final class Made implements CgSequenceSpawned {
        float weight = 1f;

        @Override public int slot(String name) {
            return name.equals("weight") ? 0 : -1;
        }

        @Override public void set(int slot, float[] v, int mask) {
            weight = v[0];
        }

        @Override public void get(int slot, float[] out) {
            out[0] = weight;
        }

        @Override public void destroy(boolean graceful) {
        }
    }

    /** The Cut on a client whose player is {@code me}, to {@code seconds}: what it spawned, by name. */
    private static Map<String, Made> cut(CgSequenceDefinition d, Fighter caster, Fighter me, double seconds) {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        sequencer.viewer(target -> target == me);
        Map<String, Made> made = new HashMap<>();
        for (String kind : new String[] {"post", "system"}) {
            sequencer.spawner(kind, (player, spawnable, at) -> {
                Made m = new Made();
                made.put(spawnable.name(), m);
                return m;
            });
        }
        sequencer.play(d, Map.of("caster", caster)).jumpTo(seconds);
        sequencer.evaluate(0f);
        return made;
    }

    /**
     * -Pkits: Kakarot's AG_PostEffectNotifyState on the Kamehameha's cut, as the game plays it only for the local
     * player's character: intensity 3, in over 0.1 s, out over 0.2 s from the notify's end.
     */
    @Test
    public void theCutsFringeIsTheCastersAlone() throws IOException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits", kits != null && kits.contains("kakarot_kit="));
        Path file = null;
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            if (nd[0].equals("kakarot_kit")) file = Paths.get(nd[1], "sequences/Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_Cut.yaml");
        }
        Assume.assumeTrue("the Cut is not in this kit", file != null && Files.exists(file));
        CgAssetCompiler.Dependencies deps = new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String id) {
                return null;
            }

            @Override
            public CgUnits units() {
                return CgUnits.UNREAL;
            }
        };
        // The fringe as the file writes it: its spawnable and the tracks bound to it. The Cut's bodies, materials and
        // systems are a client's to load.
        @SuppressWarnings("unchecked")
        Map<Object, Object> tree = (Map<Object, Object>) CgAssetYaml.read(Files.readString(file));
        List<Object> fringe = new ArrayList<>(), its = new ArrayList<>();
        String id = null;
        for (Object o : (List<?>) tree.get("spawnables")) {
            if ("fringe".equals(((Map<?, ?>) o).get("name"))) {
                fringe.add(o);
                id = String.valueOf(((Map<?, ?>) o).get("id"));
            }
        }
        for (Object o : (List<?>) tree.get("tracks")) {
            if (((Map<?, ?>) o).get("binding") instanceof Map<?, ?> b && id != null && id.equals(b.get("spawnable"))) its.add(o);
        }
        assertEquals("its spawned and weight tracks", 2, its.size());
        tree.put("spawnables", fringe);
        tree.put("tracks", its);
        CgSequenceDefinition d = CgSequenceDefinition.COMPILER.compile(CgAssetData.of("kakarot_kit:AM_C001A_S_SKL001_Cut",
                CgSequenceKinds.SEQUENCE, tree, null), deps);

        Fighter goku = new Fighter();
        double tick = 1 / 60000.0;
        assertEquals("half way in", 0.5f, cut(d, goku, goku, 177226 * tick).get("fringe").weight, 1e-3f);
        assertEquals("held", 1f, cut(d, goku, goku, 220000 * tick).get("fringe").weight, 1e-3f);
        assertEquals("half way out, past the notify's end", 0.5f, cut(d, goku, goku, 275909 * tick).get("fringe").weight, 1e-3f);
        assertTrue("on another player's screen it never spawns", !cut(d, goku, new Fighter(), 220000 * tick).containsKey("fringe"));
    }

    @Test
    public void aRelationNotPlayedFailsAtLoad() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> compile("[ally]"));
        assertTrue(e.getMessage(), e.getMessage().contains("ally"));
    }
}
