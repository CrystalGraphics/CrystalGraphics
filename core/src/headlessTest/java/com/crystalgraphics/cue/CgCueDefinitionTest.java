package com.crystalgraphics.cue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.load.CgAssetLoad;
import com.crystalgraphics.asset.load.CgAssetPipeline;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.cue.CgCueDefinition.Placement;
import com.crystalgraphics.cue.CgCueDefinition.SpawnCondition;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CgCueDefinitionTest {

    private static final String BEAM = String.join("\n",
            "_v: 1",
            "tag: cuetest:cue.beam",
            "kind: looping",
            "default_placement: { socket: hands, attach: attach_to_target, rule: keep_relative }",
            "range: 40",
            "application:",
            "  particles:",
            "    - system: cuetest:beam/start",
            "    - system: cuetest:beam/flash",
            "      placement: { socket: head }",
            "looping:",
            "  spawn_condition: { policy: local_only }",
            "  particles: [ { system: cuetest:beam/body } ]",
            "  sounds: [ { sound: cuetest:beam_loop } ]",
            "removal:",
            "  particles: [ { system: cuetest:beam/end, cast_shadow: true } ]",
            "  shake: { shake: cuetest:rumble, inner: 100, outer: 400 }",
            "events:",
            "  - id: e1",
            "    event: hit_world",
            "    particles: [ { system: cuetest:beam/hit } ]",
            "writes:",
            "  BeamLength: the head's distance from the root",
            "  Wobble: nothing binds it",
            "fields: [ { name: charge, type: float, fallback: 0 } ]",
            "prediction: keyed",
            "");

    @BeforeClass
    public static void tags() {
        CgAssetKinds.register();
        CgTagRegistry.global().register("cuetest:cue.beam");
    }

    private static CgCueDefinition compile(String yaml, CgAssetCompiler.Dependencies deps) {
        return CgCueCompiler.compile(CgAssetData.of("cuetest:beam", CgGameplayKinds.CUE, (Map<?, ?>) CgAssetYaml.read(yaml), null), deps);
    }

    private static void failsAt(String yaml, String path) {
        try {
            compile(yaml, (k, id) -> null);
            fail("compiled: " + yaml);
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(path));
        }
    }

    @Test
    public void phasesAndPlacementResolveAsUnrealsOverrides() {
        CgCueDefinition cue = compile(BEAM, (k, id) -> null);
        assertEquals(CgCueDefinition.Kind.LOOPING, cue.kind());
        assertSame(CgTagRegistry.global().require("cuetest:cue.beam"), cue.tag());
        assertTrue(cue.burst().isEmpty());

        Placement hands = Placement.attached("hands");
        assertEquals(hands, cue.application().particles().get(0).where().placement());
        // An entry's own placement replaces the default whole: it is not attached, as Unreal's default.
        assertEquals(new Placement("head", Placement.Attach.DO_NOT_ATTACH, Placement.Rule.KEEP_WORLD, null, null),
                cue.application().particles().get(1).where().placement());
        // A block's condition is its entries', with the cue's placement.
        assertEquals(SpawnCondition.Policy.LOCAL_ONLY, cue.looping().particles().get(0).where().condition().policy());
        assertEquals(hands, cue.looping().sounds().get(0).where().placement());
        assertEquals(0.5f, cue.looping().sounds().get(0).fadeOut(), 0);

        assertTrue(cue.removal().particles().get(0).castShadow());
        assertEquals(1f, cue.removal().shake().scale(), 0);
        assertEquals(List.of("hit_world"), cue.events().stream().map(CgCueDefinition.Event::event).toList());
        assertEquals(5, cue.allParticles().size());
        assertEquals(CgCueDefinition.Prediction.KEYED, cue.prediction());
        assertEquals(CgCueDefinition.Field.Type.FLOAT, cue.fields().get(0).type());
        // Nothing compiled the systems: no name is bound, and none is reported.
        assertEquals(List.of(), cue.unbound());
        assertNull(cue.application().particles().get(0).definition());
    }

    @Test
    public void lengthsAreInTheHostsUnits() {
        CgCueDefinition cue = compile(BEAM, new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String id) {
                return null;
            }

            @Override
            public CgUnits units() {
                return CgUnits.of(Map.of("length", "cm"), CgUnits.HOST);
            }
        });
        assertEquals(0.4f, cue.range(), 1e-6);
        assertEquals(4f, cue.removal().shake().outer(), 1e-6);
    }

    @Test
    public void writesBindEveryPlayedSystemAndReportTheUnbound() {
        CgVfxSchema body = CgVfxSchema.of("cuetest:beam/body");
        CgVfxParam.Scalar bodyLength = body.length("BeamLength", 1f);
        CgVfxSchema end = CgVfxSchema.of("cuetest:beam/end");
        CgVfxParam.Scalar endLength = end.length("BeamLength", 1f);
        CgVfxSystemDefinition bodyDef = CgVfxSystemDefinition.of("cuetest:beam/body", CgVfxLook.builder(body).build());
        CgVfxSystemDefinition endDef = CgVfxSystemDefinition.of("cuetest:beam/end", CgVfxLook.builder(end).build());
        CgVfxSystemDefinition plain = CgVfxSystemDefinition.of("cuetest:beam/start", CgVfxLook.builder(CgVfxSchema.of("cuetest:plain")).build());
        CgCueDefinition cue = compile(BEAM, (kind, id) -> kind != CgParticleKinds.SYSTEM ? null
                : id.endsWith("/body") ? bodyDef : id.endsWith("/end") ? endDef : plain);

        CgCueDefinition.Write length = cue.writes().get("BeamLength");
        assertEquals("the head's distance from the root", length.meaning());
        assertEquals(List.of(new CgCueDefinition.Slot("cuetest:beam/body", bodyDef, bodyLength),
                new CgCueDefinition.Slot("cuetest:beam/end", endDef, endLength)), length.slots());
        assertEquals(List.of("Wobble"), cue.unbound());
        assertEquals(List.of(), cue.writes().get("Wobble").slots());
    }

    @Test
    public void failsAtLoadByName() {
        failsAt(BEAM.replace("tag: cuetest:cue.beam", "tag: cuetest:cue.undeclared"), "tag");
        failsAt(BEAM.replace("kind: looping", "kind: burst"), "application");
        failsAt("_v: 1\nkind: looping\nburst: { particles: [ { system: cuetest:x } ] }\n", "burst");
        failsAt("_v: 1\nkind: burst\nburst: { particles: [ { cast_shadow: true } ] }\n", "system");
    }

    @Test
    public void aCueBuiltInJavaIsItsFile() {
        CgCueDefinition.Builder beam = CgCueDefinition.builder("cuetest:beam").tag("cuetest:cue.beam").looping()
                .defaultPlacement(Placement.attached("hands"))
                .defaultSpawnCondition(new SpawnCondition(SpawnCondition.Source.TARGET, SpawnCondition.Policy.NOT_LOCAL,
                        0.5f, List.of(), List.of("water")))
                .application(e -> e.particles("cuetest:beam/start")
                        .particles("cuetest:beam/flash", new Placement("head", Placement.Attach.ATTACH_TO_TARGET,
                                Placement.Rule.SNAP_TO_TARGET, new Quaternionf().rotateY(0.5f), new Vector3f(2, 2, 2))))
                .looping(e -> e.particles("cuetest:beam/body").sound("cuetest:beam_loop").shake("cuetest:rumble"))
                .removal(e -> e.particles("cuetest:beam/end").decal("cuetest:scorch").sequence("cuetest:beam_end"))
                .event("e1", "hit_world", e -> e.particles("cuetest:beam/hit"))
                .writes("BeamLength", "the head's distance from the root")
                .range(40).authoritative(true).prediction(CgCueDefinition.Prediction.ONE_OFF)
                .field("charge", CgCueDefinition.Field.Type.FLOAT, 0.0)
                .sequence("cuetest:beam_loop");
        String yaml = CgAssetYaml.write(beam.tree(), CgGameplayKinds.CUE);
        CgCueDefinition loaded = CgCueDefinition.compile(CgAssetData.of("cuetest:beam", CgGameplayKinds.CUE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
        assertEquals(yaml, beam.build(), loaded);
        assertEquals(new Quaternionf().rotateY(0.5f), loaded.application().particles().get(1).where().placement().rotation());
    }

    /** {@code -Pkits}: every kit cue compiles, its tag declared by the kit's {@code tags.yaml}, or fails naming its path. */
    @Test
    public void everyKitCueCompilesOrFailsNamingItsPath() throws InterruptedException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits", kits != null && !kits.isEmpty());
        CgCueDefinition.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            registry.scan(nd[0], Paths.get(nd[1]), CgAssetSource.DEV);
        }
        List<CgAssetEntry> all = new ArrayList<>();
        for (String ns : registry.namespaces()) {
            for (CgAssetEntry e : registry.entries(ns)) if (e.kind() == CgGameplayKinds.CUE && e.redirect() == null) all.add(e);
        }
        int compiled = 0, failed = 0, writing = 0;
        StringBuilder report = new StringBuilder();
        for (int from = 0; from < all.size(); from += 100) {
            CgAssetPipeline pipeline = new CgAssetPipeline(registry, CgJobLoop.create("cues"));
            List<CgAssetLoad> loads = new ArrayList<>();
            for (CgAssetEntry e : all.subList(from, Math.min(all.size(), from + 100))) loads.add(pipeline.request(e.kind(), e.id()));
            for (int i = 0; i < 600_000 && !pipeline.idle(); i++) {
                pipeline.drain();
                Thread.sleep(1);
            }
            pipeline.drain();
            for (int k = 0; k < loads.size(); k++) {
                CgAssetLoad l = loads.get(k);
                if (l.state() == CgAssetState.COMPILED) {
                    compiled++;
                    CgCueDefinition cue = (CgCueDefinition) l.definition();
                    if (!cue.writes().isEmpty()) writing++;
                    // What the cue manager routes by, gathered without loading, is the tag the cue compiles to.
                    assertEquals(cue.id(), cue.tag() == null ? null : cue.tag().name(), all.get(from + k).searchable().get("tag"));
                } else {
                    failed++;
                    assertEquals(l.toString(), CgAssetState.FAILED, l.state());
                    assertTrue(l.toString(), l.failure() != null && !l.failure().isEmpty());
                    report.append(l).append('\n');
                }
            }
        }
        System.out.println("[cues] " + all.size() + " cues: " + compiled + " compiled (" + writing + " with writes), " + failed + " failed");
        new File("build/kit-gates").mkdirs();
        try {
            Files.write(Paths.get("build/kit-gates/cues.txt"), (compiled + " compiled, " + failed + " failed\n" + report).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        assertTrue("no kit cue found", !all.isEmpty());
        assertEquals(report.toString(), 0, failed);
    }
}
