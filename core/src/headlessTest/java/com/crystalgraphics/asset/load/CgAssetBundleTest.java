package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
import static org.junit.Assert.assertTrue;

/** Bundles: closure, READY after uploads split under the chunk and the frame's bytes, warm steps, pools, priorities. */
public class CgAssetBundleTest {

    private static final long MB = 1L << 20;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CgVfxAssets assets;
    private final List<long[]> pieces = new ArrayList<>();

    @Before
    public void kit() throws IOException {
        Path ns = tmp.newFolder("assets", "mymod").toPath();
        CgVfxAssetsTest.write(ns, "vfx/emitters/kamehameha/core.yaml", "_v: 1\n");
        CgVfxAssetsTest.write(ns, "vfx/emitters/kamehameha/spark.yaml", "_v: 1\n");
        CgVfxAssetsTest.write(ns, "vfx/systems/kamehameha/charge.yaml", "_v: 1\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n");
        CgVfxAssetsTest.write(ns, "vfx/systems/kamehameha/fire.yaml", "_v: 1\nemitters:\n  - { id: h1, name: spark, emitter: mymod:kamehameha/spark }\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scanResources(tmp.getRoot());
        assets = new CgVfxAssets(registry, CgJobLoop.create("assets-test"));
        assets.budget().uploadBytesPerFrame(4 * MB).chunkBytes(8 * MB);
        int[] attempts = {0};
        assets.preparer(CgParticleKinds.EMITTER, (definition, plan) -> {
            String id = ((CgAssetData) definition).id();
            plan.upload(20 * MB, (offset, length) -> pieces.add(new long[] {id.endsWith("core") ? 0 : 1, offset, length}));
            plan.warm("kernel", () -> ++attempts[0] >= 3);
        });
    }

    private int settle(CgLoadHandle... hs) throws InterruptedException {
        int drains = 0;
        for (; drains < 4000 && !Arrays.stream(hs).allMatch(CgLoadHandle::ready); drains++) {
            assets.drain();
            Thread.sleep(1);
        }
        return drains;
    }

    @Test
    public void aBundleIsItsReferencesClosure() {
        CgAssetRef<?> charge = assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");
        List<CgAssetRef<?>> bundle = assets.bundle(Collections.singletonList(charge));
        assertEquals(2, bundle.size());
        assertEquals("mymod:kamehameha/core", bundle.get(1).id());
    }

    @Test
    public void readyWaitsOnUploadsSplitUnderTheFramesBytes() throws InterruptedException {
        CgAssetRef<?> charge = assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");
        CgLoadHandle h = assets.load(Collections.singletonList(charge), CgLoadPriority.MOVE);
        settle(h);
        assertTrue(h.ready());
        assertTrue(charge.ready());
        long total = 0;
        for (long[] p : pieces) {
            assertTrue("a piece over the frame's bytes: " + p[2], p[2] <= 4 * MB);
            total += p[2];
        }
        assertEquals(20 * MB, total);
        assertEquals(5, pieces.size());
        assertEquals(1f, h.progress(), 0f);
    }

    @Test
    public void theMoreUrgentLoadUploadsFirst() throws InterruptedException {
        CgAssetRef<?> charge = assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");
        CgAssetRef<?> fire = assets.declare(CgParticleKinds.SYSTEM, "mymod:kamehameha/fire");
        CgLoadHandle later = assets.load(Collections.singletonList(charge), CgLoadPriority.PREFETCH);
        CgLoadHandle now = assets.load(Collections.singletonList(fire), CgLoadPriority.PLAYING);
        settle(now, later);
        assertTrue(now.ready() && later.ready());
        // once both compiled, the spark's pieces take the frame's bytes: the core's finish last
        int lastSpark = -1;
        for (int i = 0; i < pieces.size(); i++) if (pieces.get(i)[0] == 1) lastSpark = i;
        assertTrue("the playing load finished last: " + lastSpark, lastSpark < pieces.size() - 1);
    }
}
