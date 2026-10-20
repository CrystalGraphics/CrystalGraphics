package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.job.CgJobLoop;

import static org.junit.Assert.assertEquals;
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
        assertEquals("enum", bad.issues().get(0).getCode());
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
    public void anIdNotRegisteredFailsAtOnce() {
        CgAssetLoad nope = pipeline.request(CgParticleKinds.SYSTEM, "mymod:nope");
        assertEquals(CgAssetState.FAILED, nope.state());
        assertTrue(nope.failure(), nope.failure().contains("not registered"));
    }
}
