package com.crystalgraphics.vfx.particle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.vfx.effect.beam.CgBeamWake;
import com.crystalgraphics.vfx.element.CgVfxExplosion;

/** Today's built-in emitters round-trip through emitter assets: written, resolved, compiled, written again. */
public class CgVfxEmitterAssetsTest {

    private static final List<CgVfxEmitter> BUILT_IN = Arrays.asList(CgVfxExplosion.BILLOWS, CgVfxExplosion.SPECKS,
            CgVfxExplosion.DUST, CgVfxExplosion.SURGE, CgVfxExplosion.SKIRT, CgVfxExplosion.SPARKLES, CgVfxExplosion.INK,
            CgVfxExplosion.RAYS, CgVfxExplosion.RINGS, CgBeamWake.EMITTER);

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Test
    public void builtInEmittersRoundTripThroughAssets() {
        for (CgVfxEmitter e : BUILT_IN) {
            Map<String, Object> tree = CgVfxEmitterAssets.write(e);
            CgAssetData asset = CgAssetData.of("crystalgraphics:test/" + e.name().replace(':', '_'), CgParticleKinds.EMITTER, tree, null);
            CgVfxEmitter back = CgVfxEmitterAssets.compile(e.name(), asset.resolved(), e);
            assertEquals(e.name(), tree.toString(), CgVfxEmitterAssets.write(back).toString());
            assertEquals(e.name(), e.modules.size(), back.modules.size());
            assertArrayEquals(e.name() + "'s module words", e.moduleWords(), back.moduleWords());
            CgVfxEmitter bare = CgVfxEmitterAssets.compile(e.name(), asset.resolved(), null);
            assertEquals(e.name() + " without a template", tree.toString(), CgVfxEmitterAssets.write(bare).toString());
        }
    }
}
