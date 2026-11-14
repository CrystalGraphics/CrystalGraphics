package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class CgBindlessTexturesTest {

    /** One for the class: a null device never closes, and the indices are kept per device until it does. */
    private static CgNullRHI rhi;

    @BeforeClass
    public static void install() {
        rhi = new CgNullRHI(64, 64).withoutLog();
        CgRHI.install(rhi);
    }

    @AfterClass
    public static void uninstall() {
        CgRHI.install(null);
    }

    /** A texture whose storage the test sets, as an engine texture's is replaced or a graph texture's pooled. */
    private static final class Moving implements CgTexture {
        final boolean keeps;
        CgTextureView view;

        Moving(boolean keeps) {
            this.keeps = keeps;
        }

        @Override public CgTextureView rhiView() { return view; }
        @Override public boolean keepsStorage() { return keeps; }
        @Override public int getWidth() { return 4; }
        @Override public int getHeight() { return 4; }
    }

    private CgTextureView storage(String label) {
        return CgTextureView.whole(rhi.createTexture(new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM,
                4, 4, 1, 1, 1, CgGpuTexture.Usage.SAMPLED_UPLOADED)));
    }

    @Test
    public void anArenaMaterialNamingAGraphTextureDrawsAloneFromTheFrame() {
        CgMaterialArena arena = new CgMaterialArena("graph_read_test", 4);
        CgBlockVersion.Members members = new CgBlockVersion.Members(new int[]{3}, new CgTexture[]{new Moving(false)},
                new CgSampler[]{null});
        CgBlockVersion version = CgBlockVersion.in(arena, new float[4], 4, members);
        assertNull("no slot of the arena: its index changes every frame", version.arena);
        assertEquals("its frame copy is one slot of the arena", arena.stride(), version.storageBytes);
        assertSame("still made for the arena", arena, version.arena());
    }

    @Test
    public void anEngineTextureKeepsItsIndexWhileItsStorageMoves() {
        Moving atlas = new Moving(true);
        atlas.view = storage("atlas");
        int index = CgBindlessTextures.index(atlas, null);
        atlas.view = storage("atlas grown");
        assertEquals("the same index after its storage moved: no block naming it is rewritten", index,
                CgBindlessTextures.index(atlas, null));
    }

    @Test
    public void aGraphTextureReadsItsStoragesOwnIndex() {
        Moving pooled = new Moving(false);
        pooled.view = storage("frame 1");
        CgGpuSampler sampler = CgDeviceSamplers.of(rhi, null, pooled);
        int first = CgBindlessTextures.index(pooled, null);
        assertEquals("its storage's own index", rhi.bindlessIndex(pooled.view, sampler), first);
        pooled.view = storage("frame 2");
        assertNotEquals("another storage, another index", first, CgBindlessTextures.index(pooled, null));
    }
}
