package com.crystalgraphics.asset.load;

import static org.junit.Assert.assertNotSame;

import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.job.CgJobLoop;

/** Making the process's assets registers every built-in kind's compiler, on a headless (server) classpath too. */
public class CgBuiltInCompilersTest {

    @Test
    public void everyBuiltInKindHasItsCompilerOnceAssetsExist() {
        CgAssetKinds.register();
        new CgVfxAssets(new CgAssetRegistry(), CgJobLoop.create("test-assets"));
        assertNotSame(CgAssetCompiler.RESOLVED, CgAssetCompiler.of(CgLookKinds.MATERIAL));
        assertNotSame(CgAssetCompiler.RESOLVED, CgAssetCompiler.of(CgLookKinds.CURVE_KIND));
        assertNotSame(CgAssetCompiler.RESOLVED, CgAssetCompiler.of(CgCameraKinds.CAMERA_RIG));
        assertNotSame(CgAssetCompiler.RESOLVED, CgAssetCompiler.of(CgSequenceKinds.SEQUENCE));
    }
}
