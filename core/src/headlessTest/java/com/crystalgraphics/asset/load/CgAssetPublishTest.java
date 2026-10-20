package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgEdit;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgPropertyPath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Publishing by swap at the boundary: VALUE adopted, STRUCTURAL restarted, referrers following, bad edits kept out. */
public class CgAssetPublishTest {

    static final String CHARGE = "mymod:kamehameha/charge", CORE = "mymod:kamehameha/core";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    CgVfxAssets assets;
    CgAssetRef<CgAssetData> charge, core;

    @Before
    public void kit() throws IOException, InterruptedException {
        Path ns = tmp.newFolder("assets", "mymod").toPath();
        CgVfxAssetsTest.write(ns, "vfx/emitters/kamehameha/core.yaml", "_v: 1\nproperties: { capacity: 100 }\n");
        CgVfxAssetsTest.write(ns, "vfx/systems/kamehameha/charge.yaml", "_v: 1\ndelay: 0.5\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        assets = new CgVfxAssets(registry, CgJobLoop.create("assets-test"));
        charge = assets.declare(CgParticleKinds.SYSTEM, CHARGE);
        core = assets.declare(CgParticleKinds.EMITTER, CORE);
        CgLoadHandle h = charge.request(CgLoadPriority.MOVE);
        for (int i = 0; i < 2000 && !h.ready(); i++) {
            assets.drain();
            Thread.sleep(1);
        }
        assertTrue(h.ready());
    }

    /** Frames until every change published: drain, then publish, as the boundary does. */
    void frames() throws InterruptedException {
        for (int i = 0; i < 2000 && assets.swapsPending() > 0; i++) {
            assets.drain();
            assets.publish();
            Thread.sleep(1);
        }
        assertEquals(0, assets.swapsPending());
    }

    @Test
    public void aValueEditIsAdoptedAtTheNextStep() throws InterruptedException {
        CgAssetFollower<CgAssetData> playing = new CgAssetFollower<>(charge);
        assets.edit(charge, Collections.singletonList(CgEdit.set(CgPropertyPath.of(CHARGE).key("delay"), 0.25)));
        assertEquals(CgAssetFollower.Adoption.NONE, playing.poll());   // nothing swaps outside the boundary
        frames();
        assertEquals(2, charge.generation());
        assertEquals(CgAssetFollower.Adoption.VALUE, playing.poll());
        assertEquals(0.25, playing.definition().resolved().get("delay"));
        assertEquals(0.5, playing.previous().resolved().get("delay"));
        assertEquals(CgAssetFollower.Adoption.NONE, playing.poll());
    }

    @Test
    public void aStructuralEditRestartsItAndWhatInlinesIt() throws InterruptedException {
        CgAssetFollower<CgAssetData> emitter = new CgAssetFollower<>(core);
        CgAssetFollower<CgAssetData> system = new CgAssetFollower<>(charge);
        assets.edit(core, Collections.singletonList(
                CgEdit.set(CgPropertyPath.of(CORE).key("properties").key("capacity"), 200)));
        frames();
        assertEquals(CgAssetFollower.Adoption.RESTART, emitter.poll());
        assertEquals(CgAssetFollower.Adoption.RESTART, system.poll());
        assertEquals(200, ((Map<?, ?>) emitter.definition().resolved().get("properties")).get("capacity"));
    }

    @Test
    public void anEditThatNoLongerValidatesKeepsTheLastGoodDefinition() throws InterruptedException {
        CgAssetData before = core.loaded();
        assets.edit(core, Collections.singletonList(
                CgEdit.set(CgPropertyPath.of(CORE).key("properties").key("simulation"), "cpu")));
        frames();
        assertEquals(1, core.generation());
        assertEquals(before, core.loaded());
    }
}
