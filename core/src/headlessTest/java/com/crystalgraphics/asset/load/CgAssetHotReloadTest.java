package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgEdit;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgPropertyPath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A saved file: diffed into the same edits an editor makes, published by swap; one that no longer decodes kept out. */
public class CgAssetHotReloadTest {

    static final String CHARGE = "mymod:kamehameha/charge", CORE = "mymod:kamehameha/core";
    static final String CHARGE_FILE = "vfx/systems/kamehameha/charge.yaml", CORE_FILE = "vfx/emitters/kamehameha/core.yaml";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path ns;
    private CgVfxAssets assets;
    private CgAssetRef<CgAssetData> charge, core;

    @Before
    public void kit() throws IOException, InterruptedException {
        ns = tmp.newFolder("assets", "mymod").toPath();
        CgVfxAssetsTest.write(ns, CORE_FILE, "_v: 1\nproperties: { capacity: 100 }\n");
        CgVfxAssetsTest.write(ns, CHARGE_FILE, "_v: 1\ndelay: 0.5\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n");
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

    private void save(String file, String text, CgAssetRef<?> ref, int generation) throws IOException, InterruptedException {
        CgVfxAssetsTest.write(ns, file, text);
        assets.saved("mymod:" + file);
        for (int i = 0; i < 2000 && ref.generation() < generation; i++) {
            assets.drain();
            assets.publish();
            Thread.sleep(1);
        }
        for (int i = 0; i < 50; i++) {   // let a refused reload finish failing
            assets.drain();
            assets.publish();
            Thread.sleep(1);
        }
    }

    @Test
    public void aSavedFileAndTheSameEditGiveIdenticalEdits() {
        Map<?, ?> saved = (Map<?, ?>) CgAssetYaml.read(
                "_v: 1\ndelay: 0.25\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n");
        List<CgEdit> diff = CgAssetFileDiff.diff(CHARGE, charge.loaded().delta(), saved);
        List<CgEdit> made = Collections.singletonList(CgEdit.set(CgPropertyPath.of(CHARGE).key("delay"), 0.25));
        assertEquals(made.toString(), diff.toString());
    }

    @Test
    public void anElementAddedOrDisabledIsAnInsertOrADisable() {
        Map<?, ?> saved = (Map<?, ?>) CgAssetYaml.read("_v: 1\ndelay: 0.5\nemitters:\n"
                + "  - { id: h1, name: core, emitter: mymod:kamehameha/core, enabled: false }\n"
                + "  - { id: h2, name: core2, emitter: mymod:kamehameha/core }\n");
        List<CgEdit> diff = CgAssetFileDiff.diff(CHARGE, charge.loaded().delta(), saved);
        assertEquals(2, diff.size());
        assertEquals(CgEdit.Op.DISABLE, diff.get(0).op());
        assertEquals(CgEdit.Op.INSERT, diff.get(1).op());
        assertEquals("h1", diff.get(1).anchor());
    }

    @Test
    public void aSavedValueChangeIsAdoptedAsAValue() throws IOException, InterruptedException {
        CgAssetFollower<CgAssetData> playing = new CgAssetFollower<>(charge);
        save(CHARGE_FILE, "_v: 1\ndelay: 0.25\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n", charge, 2);
        assertEquals(CgAssetFollower.Adoption.VALUE, playing.poll());
        assertEquals(0.25, playing.definition().resolved().get("delay"));
    }

    @Test
    public void aSavedStructuralChangeRestarts() throws IOException, InterruptedException {
        CgAssetFollower<CgAssetData> playing = new CgAssetFollower<>(core);
        save(CORE_FILE, "_v: 1\nproperties: { capacity: 300 }\n", core, 2);
        assertEquals(CgAssetFollower.Adoption.RESTART, playing.poll());
    }

    @Test
    public void aFileThatNoLongerDecodesKeepsTheLastGoodDefinition() throws IOException, InterruptedException {
        CgAssetData before = charge.loaded();
        save(CHARGE_FILE, "_v: 1\ndelay: [0.25\n  emitters: {{\n", charge, 2);
        assertEquals(1, charge.generation());
        assertEquals(before, charge.loaded());
        save(CHARGE_FILE, "_v: 1\ndelay: 0.75\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n", charge, 2);
        assertEquals(2, charge.generation());
        assertEquals(0.75, charge.loaded().resolved().get("delay"));
    }
}
