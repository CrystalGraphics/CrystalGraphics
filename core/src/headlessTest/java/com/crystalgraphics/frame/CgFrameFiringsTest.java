package com.crystalgraphics.frame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgEdit;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.CgPropertyPath;

/**
 * A frame with two world-stage firings (1.7.10's anaglyph draws the world twice) sees one definition in both: a
 * reload that finishes compiling between the firings is published at the next boundary, never between them.
 */
public class CgFrameFiringsTest {

    private static final String CHARGE = "mymod:kamehameha/charge";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CgVfxAssets assets;
    private CgAssetRef<CgAssetData> charge;
    private final List<CgFrameBoundary.Function> added = new ArrayList<>();

    @Before
    public void kit() throws IOException, InterruptedException {
        Path ns = tmp.newFolder("assets", "mymod").toPath();
        Path file = ns.resolve("vfx/systems/kamehameha/charge.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, "_v: 1\ndelay: 0.5\n".getBytes(StandardCharsets.UTF_8));
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        assets = new CgVfxAssets(registry, CgJobLoop.create("firings-test"));
        CgVfxAssets.install(assets);
        charge = assets.declare(CgParticleKinds.SYSTEM, CHARGE);
        CgLoadHandle h = charge.request(CgLoadPriority.MOVE);
        for (int i = 0; i < 2000 && !h.ready(); i++) {
            assets.drain();
            Thread.sleep(1);
        }
        assertTrue(h.ready());
        added.add(CgFrameBoundary.add(CgFrameBoundary.Phase.DRAIN, "test:firings-drain", assets::drain));
        added.add(CgFrameBoundary.add(CgFrameBoundary.Phase.PUBLISH, "test:firings-publish", assets::publish));
    }

    @After
    public void close() {
        for (CgFrameBoundary.Function f : added) f.close();
    }

    @Test
    public void aReloadBetweenTwoFiringsOfAFrameIsSeenByNeitherAndByBothOfTheNext() throws InterruptedException {
        CgFrameBoundary.run();                                           // frame 1's boundary
        CgAssetData first = charge.loaded();                             // firing 1
        assets.edit(charge, Collections.singletonList(CgEdit.set(CgPropertyPath.of(CHARGE).key("delay"), 0.25)));
        Thread.sleep(300);                                               // the compile finishes on the loop, mid-frame
        assertEquals(first, charge.loaded());                            // firing 2: the same definition
        assertEquals(1, charge.generation());

        for (int i = 0; i < 200 && charge.generation() == 1; i++) {      // next frames' boundaries publish it
            CgFrameBoundary.run();
            if (charge.generation() == 1) Thread.sleep(5);
        }
        assertEquals(2, charge.generation());
        CgAssetData next = charge.loaded();                              // both firings of that frame
        assertEquals(0.25, next.resolved().get("delay"));
        assertEquals(next, charge.loaded());
    }
}
