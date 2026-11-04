package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.job.CgJobLoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Loads to COMPILED: a child after its parent, a hard dependency first, failures carried to referrers, a cycle. */
public class CgAssetPipelineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path ns;
    private CgAssetPipeline pipeline;

    @Before
    public void kit() throws IOException {
        ns = tmp.newFolder("assets", "mymod").toPath();
        write("vfx/emitters/kamehameha/core.yaml", "_v: 1\n");
        write("vfx/emitters/kamehameha/bad.yaml", "_v: 1\nproperties: { simulation: cpu }\n");
        write("vfx/systems/kamehameha/charge.yaml", "_v: 1\ndelay: 0.5\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n");
        write("vfx/systems/kamehameha/charge_big.yaml", "_v: 1\nparent: mymod:kamehameha/charge\ndelay: 1.0\n");
        write("vfx/systems/kamehameha/broken.yaml", "_v: 1\nemitters:\n  - { id: h1, name: bad, emitter: mymod:kamehameha/bad }\n");
        write("materials/a.yaml", "_v: 1\nparent: mymod:b\n");
        write("materials/b.yaml", "_v: 1\nparent: mymod:a\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        pipeline = new CgAssetPipeline(registry, CgJobLoop.create("assets-test"));
    }

    private void write(String rel, String text) throws IOException {
        Path p = ns.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private void settle() throws InterruptedException {
        for (int i = 0; i < 2000 && !pipeline.idle(); i++) {
            pipeline.drain();
            Thread.sleep(1);
        }
        pipeline.drain();
        assertTrue("still loading", pipeline.idle());
    }

    @Test
    public void aChildCompilesAfterItsParentAndItsHardDependencies() throws InterruptedException {
        CgAssetLoad big = pipeline.request(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge_big");
        settle();
        assertEquals(big.toString(), CgAssetState.COMPILED, big.state());
        assertEquals(1.0, ((CgAssetData) big.definition()).resolved().get("delay"));
        assertEquals(CgAssetState.COMPILED, pipeline.load(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge").state());
        assertEquals(CgAssetState.COMPILED, pipeline.load(CgParticleKinds.EMITTER, "mymod:kamehameha/core").state());
        assertEquals(0, pipeline.stepsOffWorkers());
    }

    @Test
    public void aDependencysFailureFailsItsReferrerNamingIt() throws InterruptedException {
        CgAssetLoad broken = pipeline.request(CgParticleKinds.SYSTEM, "mymod:kamehameha/broken");
        settle();
        CgAssetLoad bad = pipeline.load(CgParticleKinds.EMITTER, "mymod:kamehameha/bad");
        assertEquals(CgAssetState.FAILED, bad.state());
        assertEquals("enum", bad.issues().get(0).code());
        assertEquals(CgAssetState.FAILED, broken.state());
        assertTrue(broken.failure(), broken.failure().contains("mymod:kamehameha/bad"));
    }

    @Test
    public void aParentCycleFailsBoth() throws InterruptedException {
        CgAssetLoad a = pipeline.request(CgLookKinds.MATERIAL, "mymod:a");
        settle();
        assertEquals(CgAssetState.FAILED, a.state());
        assertEquals(CgAssetState.FAILED, pipeline.load(CgLookKinds.MATERIAL, "mymod:b").state());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void anInlineChildLoadsAsItsOwnFileWould() throws IOException, InterruptedException {
        write("cues/ground_break.yaml", "_v: 1\ntag: mymod:cue.ground_break\nkind: burst\nrange: 32\n"
                + "default_placement: { socket: root, attach: attach_to_target }\n"
                + "children:\n  hard:\n    range: 64\n    default_placement: { socket: feet }\n"
                + "    children:\n      wide: { range: 96 }\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        pipeline = new CgAssetPipeline(registry, CgJobLoop.create("assets-test"));

        CgAssetLoad wide = pipeline.request(CgGameplayKinds.CUE, "mymod:ground_break/hard/wide");
        settle();
        assertEquals(wide.toString(), CgAssetState.COMPILED, wide.state());
        Map<?, ?> resolved = wide.asset().resolved();
        assertEquals(96.0, ((Number) resolved.get("range")).doubleValue(), 0);
        assertEquals("a name field follows the id", "mymod:cue.ground_break.hard.wide", resolved.get("tag"));
        Map<?, ?> placement = (Map<?, ?>) resolved.get("default_placement");
        assertEquals("from its block", "feet", placement.get("socket"));
        assertEquals("from the file", "attach_to_target", placement.get("attach"));
        assertFalse("children are expanded, never resolved", resolved.containsKey("children"));
        CgAssetLoad file = pipeline.load(CgGameplayKinds.CUE, "mymod:ground_break");
        assertFalse(file.asset().resolved().containsKey("children"));
    }

    @Test
    public void anIdNotRegisteredFailsAtOnce() {
        CgAssetLoad nope = pipeline.request(CgParticleKinds.SYSTEM, "mymod:nope");
        assertEquals(CgAssetState.FAILED, nope.state());
        assertTrue(nope.failure(), nope.failure().contains("not registered"));
    }
}
