package com.crystalgraphics.sequence.definition;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.load.CgAssetLoad;
import com.crystalgraphics.asset.load.CgAssetPipeline;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Sequences compile, or fail naming the sequence and the path; over the kits with -Pkits. */
public class CgSequenceDefinitionTest {

    private static final String HEAD = "_v: 1\ntick_resolution: 60000\ndisplay_rate: 30\nrange: [0, 100]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";

    private static CgSequenceDefinition compile(String id, String yaml) {
        return CgSequenceDefinition.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    private static CgSequenceDefinition compile(String id, String yaml, CgUnits units, Map<String, CgSequenceDefinition> children) {
        CgAssetCompiler.Dependencies deps = new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String ref) {
                return children.get(ref);
            }

            @Override
            public CgUnits units() {
                return units;
            }
        };
        return CgSequenceCompiler.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), deps);
    }

    private static void failsAt(String yaml, String path) {
        try {
            compile("mymod:s", yaml);
            fail("compiled");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("mymod:s: " + path));
        }
    }

    /** The sections active at {@code tick}, as {@code track/section}, a whole track as {@code track/*}. */
    private static Set<String> active(CgSequenceDefinition d, int tick) {
        CgSequenceField f = d.field();
        int s = f.segmentAt(tick);
        Set<String> out = new TreeSet<>();
        for (int k = 0; k < f.entryCount(s); k++) {
            int e = f.entry(s, k);
            CgSequenceTrack t = d.tracks().get(f.track(e));
            out.add(t.id() + "/" + (f.section(e) < 0 ? "*" : t.sections().get(f.section(e)).id()));
        }
        return out;
    }

    @Test
    public void theFieldResolvesRowsAndPriorityAsUnrealDoes() {
        CgSequenceDefinition d = compile("mymod:s", HEAD + "tracks:\n"
                + "  - id: t1\n    kind: visibility\n    binding: { role: caster }\n    sections:\n"
                + "      - { id: s1, range: [0, 60], visible: [[0, true]] }\n"
                + "      - { id: s2, range: [40, 100], overlap_priority: 1, visible: [[40, false]] }\n"
                + "      - { id: s3, range: [20, 50], row: 1, visible: [[20, true]] }\n"
                + "  - id: t2\n    kind: transform\n    binding: { role: caster }\n    sections:\n"
                + "      - { id: s4, range: [0, 60], translation: [[[0, 1]], [[0, 2]], [[0, 3]]] }\n"
                + "      - { id: s5, range: [30, 80], translation: [[[0, 1]], [[0, 2]], [[0, 3]]] }\n"
                + "  - id: t3\n    kind: visibility\n    muted: true\n    binding: { role: caster }\n    sections:\n"
                + "      - { id: s6, range: [0, 100], visible: [[0, true]] }\n");
        // Visibility does not blend: in a row, the higher priority wins its overlap; another row evaluates beside it.
        assertEquals(Set.of("t1/s1", "t2/s4"), active(d, 10));
        assertEquals(Set.of("t1/s1", "t1/s3", "t2/s4", "t2/s5"), active(d, 35));
        assertEquals(Set.of("t1/s2", "t1/s3", "t2/s4", "t2/s5"), active(d, 45));
        assertEquals(Set.of("t1/s2", "t2/s4", "t2/s5"), active(d, 55));
        assertEquals(Set.of("t1/s2", "t2/s5"), active(d, 70));
        assertEquals(Set.of(), active(d, 100));
        assertEquals(Set.of(), active(d, -5));
        assertTrue(d.tracks().get(1).sections().get(0).blend() == CgSequenceSection.Blend.ABSOLUTE);
        assertTrue(d.tracks().get(0).sections().get(0).blend() == null);
    }

    @Test
    public void aSystemsBindSetsItsPositionParametersFromPointsAndAnythingElseFailsByPath() {
        String locators = "locators:\n  - { id: k1, name: palms, parent: { role: caster, socket: aim } }\n";
        CgSequenceDefinition d = compile("mymod:s", HEAD + locators + "spawnables:\n"
                + "  - { id: s1, at: { locator: k1 }, system: mymod:sparks, bind: { point_0: { locator: k1 }, point_1: { role: caster, socket: chest } } }\n",
                CgUnits.HOST, Map.of());
        Map<String, CgSequenceBinding> binds = d.spawnables().get(0).binds();
        assertEquals(List.of("point_0", "point_1"), List.copyOf(binds.keySet()));
        assertEquals(CgSequenceBinding.Subject.LOCATOR, binds.get("point_0").subject());
        assertEquals(CgSequenceBinding.Part.SOCKET, binds.get("point_1").part());
        for (String bad : List.of("{ id: s1, system: mymod:sparks, bind: { point_0: [0, 1, 0] } }",
                "{ id: s1, system: mymod:sparks, bind: { point_0: { role: caster, value: velocity } } }",
                "{ id: s1, prefab: mymod:ring, bind: { point_0: { locator: k1 } } }")) {
            try {
                compile("mymod:s", HEAD + locators + "spawnables:\n  - " + bad + "\n", CgUnits.HOST, Map.of());
                fail(bad);
            } catch (CgAssetException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("spawnables.s1.bind"));
            }
        }
    }

    @Test
    public void heldAndTriggeredChannelsTakeTheirKindsTypes() {
        CgSequenceDefinition d = compile("mymod:s", HEAD + "spawnables:\n  - { id: c1, camera: { cine: { fov: 40 } } }\n"
                + "tracks:\n"
                + "  - id: t1\n    kind: system\n    binding: c1\n    sections:\n"
                + "      - { id: s1, range: [10, 50], keys: [[10, activate], [50, deactivate]] }\n"
                + "  - id: t2\n    kind: visibility\n    binding: { camera: c1 }\n    sections:\n"
                + "      - { id: s2, range: [0, 100], visible: [[20, false], [60, true]] }\n");
        CgSequenceChannel keys = d.tracks().get(0).sections().get(0).channel("keys");
        assertEquals(CgSequenceChannel.Type.PARTICLE, keys.type());
        assertEquals(CgSequenceChannel.Particle.DEACTIVATE.ordinal(), keys.intValue(1));
        // A system track evaluates over all time, its keys read by its evaluator.
        assertEquals(Set.of("t1/*", "t2/s2"), active(d, 80));
        CgSequenceChannel visible = d.tracks().get(1).sections().get(0).channel("visible");
        assertEquals(CgSequenceChannel.Type.BOOL, visible.type());
        assertEquals(false, visible.bool(0));   // before its first key: the first key's
        assertEquals(false, visible.bool(59));
        assertEquals(true, visible.bool(60));
        assertEquals(CgSequenceBinding.Subject.SPAWNABLE, d.tracks().get(0).binding().subject());
        assertEquals(0, d.tracks().get(1).binding().index());
    }

    @Test
    public void aTranslationTurnsThroughTheUnitsAxes() {
        CgSequenceDefinition d = compile("mymod:s", HEAD + "tracks:\n"
                + "  - id: t1\n    kind: transform\n    binding: { role: caster }\n    sections:\n"
                + "      - { id: s1, range: [0, 100], translation: [[[0, 100]], [[0, 200]], [[0, 300]]] }\n",
                CgUnits.UNREAL, Map.of());
        CgSequenceSection s = d.tracks().get(0).sections().get(0);
        // Unreal's z up is the host's y: centimetres to metres.
        assertEquals(1f, s.channel("translation.x").eval(0), 1e-6f);
        assertEquals(3f, s.channel("translation.y").eval(0), 1e-6f);
        assertEquals(2f, s.channel("translation.z").eval(0), 1e-6f);
    }

    @Test
    public void bindingsNextLinksAndRolesResolveOrFailByPath() {
        failsAt(HEAD + "tracks:\n  - id: t1\n    kind: visibility\n    binding: { role: target }\n    sections: []\n",
                "tracks.t1.binding: no role named target");
        failsAt(HEAD + "tracks:\n  - id: t1\n    kind: visibility\n    binding: zz9\n    sections: []\n", "tracks.t1.binding");
        failsAt(HEAD + "composite_sections:\n  - { id: q1, name: charge, range: [0, 40], next: q9 }\n",
                "composite_sections.q1.next");
        CgSequenceDefinition d = compile("mymod:s", HEAD + "composite_sections:\n"
                + "  - { id: q1, name: charge, range: [0, 40], next: q2 }\n  - { id: q2, name: loop, range: [40, 100], next: q2 }\n");
        assertEquals(1, d.compositeSection("charge").next());
        assertEquals(1, d.compositeSection("loop").next());
        assertEquals(0, d.role("caster"));
    }

    @Test
    public void conditionsCompileAndAnRpgEventMayNotHoldOne() {
        CgSequenceDefinition d = compile("mymod:s", HEAD + "tracks:\n"
                + "  - id: t1\n    kind: event\n    side: ours\n    sections:\n"
                + "      - id: s1\n        range: [0, 100]\n        keys: [[10, { event: flash }]]\n"
                + "        condition: { all: [ { kind: crystalgraphics:character, role: caster, is: [Goku] },"
                + " { not: { kind: crystalgraphics:quality, is: [low] } } ] }\n");
        CgSequenceCondition c = d.tracks().get(0).sections().get(0).condition();
        assertTrue(c.test(leaf -> leaf.kind().equals("crystalgraphics:character") ? leaf.is().contains("Goku") : false));
        assertTrue(!c.test(leaf -> true));
        assertEquals("flash", d.tracks().get(0).sections().get(0).channel("keys").string(0));
        failsAt(HEAD + "tracks:\n  - id: t1\n    kind: event\n    side: rpg\n    sections:\n"
                + "      - { id: s1, range: [0, 100], condition: { kind: crystalgraphics:quality, is: [low] } }\n",
                "tracks.t1.sections.s1.condition");
    }

    @Test
    public void subsequencesNestAtMostFourDeep() {
        Map<String, CgSequenceDefinition> defs = new HashMap<>();
        defs.put("mymod:c0", compile("mymod:c0", HEAD));
        for (int i = 1; i <= 5; i++) {
            String yaml = HEAD + "tracks:\n  - id: t1\n    kind: subsequence\n    sections:\n"
                    + "      - { id: s1, range: [0, 100], sequence: \"mymod:c" + (i - 1) + "\" }\n";
            if (i <= CgSequenceDefinition.MAX_DEPTH) {
                CgSequenceDefinition d = compile("mymod:c" + i, yaml, CgUnits.HOST, defs);
                assertEquals(i, d.depth());
                assertEquals(0, d.children().get(0).roles()[0]);   // its caster is ours, by name
                defs.put("mymod:c" + i, d);
            } else {
                try {
                    compile("mymod:c" + i, yaml, CgUnits.HOST, defs);
                    fail("nested past " + CgSequenceDefinition.MAX_DEPTH);
                } catch (CgAssetException e) {
                    assertTrue(e.getMessage(), e.getMessage().startsWith("mymod:c5: tracks: subsequences nest 5 deep"));
                }
            }
        }
    }

    @Test
    public void aSequenceCompilesItsRangeRolesAndRates() {
        CgSequenceDefinition s = compile("mymod:jab", "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\nrange: [0, 40]\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\n");
        assertEquals(40, s.end());
        assertEquals(2.0, s.seconds(s.end()), 0.0);
        assertEquals(CgSequenceDefinition.Evaluation.SUB_FRAMES, s.evaluation());
        assertEquals("caster", s.roles().get(0).name());
    }

    @Test
    public void aMissingRangeFailsNamingIt() {
        try {
            compile("mymod:jab", "_v: 1\ntick_resolution: 20\ndisplay_rate: 20\n");
            fail();
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("mymod:jab: range"));
        }
    }

    @Test
    public void theNovaCannonsSequenceCompilesOrFailsNamingItsPath() throws Exception {
        Path file = Paths.get("../../plan/crystalgraphics/vfx-aaa/examples/novamod/assets/novamod/sequences/nova_cannon.yaml");
        Assume.assumeTrue("the Nova Cannon fixture is absent", Files.isRegularFile(file));
        try {
            compile("novamod:nova_cannon", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("novamod:nova_cannon: "));
        }
    }

    /** -Pkits=<ns>=<dir>;...: every kit sequence reaches COMPILED or FAILED with a reason naming it. */
    @Test
    public void everyKitSequenceCompilesOrFailsNamingItsPath() throws InterruptedException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits", kits != null && !kits.isEmpty());
        CgAssetKinds.register();
        CgSequenceDefinition.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            registry.scan(nd[0], Paths.get(nd[1]), CgAssetSource.DEV);
        }
        List<CgAssetEntry> all = new ArrayList<>();
        for (String ns : registry.namespaces()) {
            for (CgAssetEntry e : registry.entries(ns)) {
                if (e.kind() == CgSequenceKinds.SEQUENCE && e.redirect() == null) all.add(e);
            }
        }
        int compiled = 0, failed = 0;
        StringBuilder report = new StringBuilder();
        // A pipeline a batch, dropped after it: nothing is released, so one over every kit holds every tree.
        for (int from = 0; from < all.size(); from += 100) {
            CgAssetPipeline pipeline = new CgAssetPipeline(registry, CgJobLoop.create("sequences"));
            List<CgAssetLoad> loads = new ArrayList<>();
            for (CgAssetEntry e : all.subList(from, Math.min(all.size(), from + 100))) loads.add(pipeline.request(e.kind(), e.id()));
            for (int i = 0; i < 600_000 && !pipeline.idle(); i++) {
                pipeline.drain();
                Thread.sleep(1);
            }
            pipeline.drain();
            for (CgAssetLoad l : loads) {
                if (l.state() == CgAssetState.COMPILED) compiled++;
                else {
                    failed++;
                    assertEquals(l.toString(), CgAssetState.FAILED, l.state());
                    assertTrue(l.toString(), l.failure() != null && !l.failure().isEmpty());
                    report.append(l).append('\n');
                }
            }
        }
        System.out.println("[sequences] " + all.size() + " sequences: " + compiled + " compiled, " + failed + " failed");
        new File("build/kit-gates").mkdirs();
        try {
            // Every failure, one a line: the file is where they are read.
            Files.write(Paths.get("build/kit-gates/sequences.txt"), (compiled + " compiled, " + failed + " failed\n" + report).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
