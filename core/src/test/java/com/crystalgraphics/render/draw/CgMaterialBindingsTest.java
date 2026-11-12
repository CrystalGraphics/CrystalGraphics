package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import org.junit.Test;

import static org.junit.Assert.*;

/** A version's textures over its material's, and how a frame enters versions and its passes' own entries. */
public class CgMaterialBindingsTest {

    private static final CgTexture IMAGE = texture(), OTHER = texture();

    private static CgTexture texture() {
        return new CgTexture() {
            @Override public CgTextureView rhiView() { return null; }
            @Override public int getWidth() { return 4; }
            @Override public int getHeight() { return 4; }
        };
    }

    @Test
    public void aTextureOverAVersionReplacesItsUnitAndKeepsTheRest() {
        CgMaterialBindings base = CgMaterialBindings.builder().texture(0, IMAGE, CgSampler.NEAREST_WRAP).texture(1, IMAGE).build();
        CgMaterialBindings over = base.with(1, OTHER);
        assertEquals(2, over.textures());
        assertSame("unit 0 kept its sampler", CgSampler.NEAREST_WRAP, over.sampler(0));
        assertSame(OTHER, over.texture(1));
        assertSame("the base is untouched", IMAGE, base.texture(1));
    }

    @Test
    public void theSameTextureOverIsTheSameVersion() {
        CgMaterialBindings base = CgMaterialBindings.builder().texture(0, IMAGE).build();
        CgMaterialBindings over = base.with(0, OTHER, CgSampler.BILINEAR_CLAMP);
        assertSame("kept: a draw repeating it allocates nothing", over, base.with(0, OTHER, CgSampler.BILINEAR_CLAMP));
        assertNotSame("another sampler is another version", over, base.with(0, OTHER, CgSampler.BILINEAR_WRAP));
    }

    @Test
    public void aFrameEntersAVersionOnceByIdentity() {
        CgFrameBindings frame = new CgFrameBindings();
        CgMaterialBindings a = CgMaterialBindings.builder().texture(0, IMAGE).build();
        CgMaterialBindings b = CgMaterialBindings.builder().texture(0, IMAGE).build();
        int id = frame.add(a);
        assertEquals("one id however many draws name it", id, frame.add(a));
        assertNotEquals("another version, another entry", id, frame.add(b));
        frame.reset();
        assertEquals("a reset frame starts again", 0, frame.add(b));
    }

    @Test
    public void aPassEntryIsInternedByContentWithItsSampler() {
        CgFrameBindings frame = new CgFrameBindings();
        int own = frame.begin().texture(0, IMAGE).end();
        int wrapped = frame.begin().texture(0, IMAGE, CgSampler.BILINEAR_WRAP).end();
        int again = frame.begin().texture(0, IMAGE, CgSampler.builder().filter(CgSampler.Filter.BILINEAR)
                .address(CgSampler.Address.WRAP).build()).end();
        assertNotEquals(own, wrapped);
        assertEquals("an equal sampler is the same entry", wrapped, again);
        assertNull(frame.sampler(own, 0));
    }
}
