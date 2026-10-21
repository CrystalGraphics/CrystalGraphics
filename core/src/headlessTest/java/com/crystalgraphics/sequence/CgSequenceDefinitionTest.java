package com.crystalgraphics.sequence;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetLoad;
import com.crystalgraphics.asset.load.CgAssetPipeline;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.job.CgJobLoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Sequences compile, or fail naming the sequence and the path; over the kits with -Pkits. */
public class CgSequenceDefinitionTest {

    private static CgSequenceDefinition compile(String id, String yaml) {
        return CgSequenceDefinition.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
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
                    if (failed <= 20) report.append(l).append('\n');
                }
            }
        }
        System.out.println("[sequences] " + all.size() + " sequences: " + compiled + " compiled, " + failed + " failed\n" + report);
        new File("build/kit-gates").mkdirs();
        try {
            Files.write(Paths.get("build/kit-gates/sequences.txt"), (compiled + " compiled, " + failed + " failed\n" + report).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
