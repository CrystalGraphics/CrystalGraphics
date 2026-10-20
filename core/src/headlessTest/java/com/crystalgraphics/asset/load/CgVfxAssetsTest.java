package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.job.CgJobLoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Refs: one per asset, loaded ahead to READY with no sync load, or synchronously and counted; failures. */
public class CgVfxAssetsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CgVfxAssets assets;

    @Before
    public void kit() throws IOException {
        Path ns = tmp.newFolder("assets", "mymod").toPath();
        write(ns, "vfx/emitters/kamehameha/core.yaml", "_v: 1\n");
        write(ns, "vfx/emitters/kamehameha/bad.yaml", "_v: 1\nproperties: { simulation: cpu }\n");
        write(ns, "vfx/systems/kamehameha/charge.yaml", "_v: 1\ndelay: 0.5\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n");
        write(ns, "vfx/systems/kamehameha/broken.yaml", "_v: 1\nemitters:\n  - { id: h1, name: bad, emitter: mymod:kamehameha/bad }\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        assets = new CgVfxAssets(registry, CgJobLoop.create("assets-test"));
    }

    static void write(Path ns, String rel, String text) throws IOException {
        Path p = ns.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private void settle(CgLoadHandle h) throws InterruptedException {
        for (int i = 0; i < 2000 && !(h.ready() || h.failed()) ; i++) {
            assets.drain();
            Thread.sleep(1);
        }
        assets.drain();
    }

    @Test
    public void theSameAssetIsTheSameRef() {
        assertSame(assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge"),
                assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge"));
    }

    @Test
    public void aRequestedAssetIsReadyWithNoSyncLoad() throws InterruptedException {
        CgAssetRef<CgAssetData> charge = assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");
        assertEquals(CgAssetState.REGISTERED, charge.state());
        assertEquals(0, charge.generation());
        boolean[] told = {false};
        CgLoadHandle h = charge.request(CgLoadPriority.MOVE).onReady(() -> told[0] = true);
        settle(h);
        assertTrue(charge.ready());
        assertTrue(told[0]);
        assertEquals(1f, h.progress(), 0f);
        assertEquals(1, charge.generation());
        assertEquals(0.5, charge.get().resolved().get("delay"));
        assertEquals(0, assets.syncLoads());
    }

    @Test
    public void getBeforeALoadLoadsSynchronouslyAndCounts() {
        CgAssetRef<CgAssetData> charge = assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");
        CgAssetData d = charge.get();
        assertEquals(0.5, d.resolved().get("delay"));
        assertEquals(1, assets.syncLoads());
        assertSame(d, charge.get());
        assertEquals(1, assets.syncLoads());
    }

    @Test
    public void aFailureFailsTheHandleAndGetAnswersNull() throws InterruptedException {
        CgAssetRef<CgAssetData> broken = assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/broken");
        CgLoadHandle h = assets.load(Collections.singletonList(broken), CgLoadPriority.MOVE);
        settle(h);
        assertTrue(h.failed());
        assertFalse(h.ready());
        assertTrue(h.failures().get(0), h.failures().get(0).contains("mymod:kamehameha/bad"));
        assertNull(broken.get());
    }
}
