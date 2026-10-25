package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureCubemap;
import com.crystalgraphics.platform.gl.CgGL;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** A material's textures are made as their texture assets say (looks.md § Texture), and refused where the file is wrong. */
public class CgMaterialTextureSettingsTest {

    private static final String DIR = "cgtest:textures/";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static void refused(String path, String says) {
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgMaterialRegistry.texture(DIR + path));
        assertTrue(e.getMessage(), e.getMessage().contains(says));
    }

    @Test
    public void aPngAndAnHdrAtOnePathAreRefused() {
        refused("both.png", "both.hdr");
        refused("both.hdr", "both.png");
    }

    @Test
    public void anHdrSaysNoSrgbAndAPngNoHdrCompression() {
        refused("srgb.hdr", "colour: srgb");
        refused("hdr_on_png.png", "compression: hdr");
    }

    @Test
    public void cubeTrueIsACubeAndAnHdrIsFloat() {
        CgTexture cube = CgMaterialRegistry.texture(DIR + "stacked.png");
        assertTrue(cube instanceof CgTextureCubemap);
        CgTexture ramp = CgMaterialRegistry.texture(DIR + "ramp.hdr");
        assertTrue(ramp instanceof CgTexture2D);
        assertEquals(4, ramp.getWidth());   // decoded as half floats: another spec would throw
    }

    @Test
    public void itsSamplerIsTheTexturesOwnFilterAndWrap() {
        CgTextureSpec own = CgMaterialRegistry.textureSpec(DIR + "clamped.png");
        assertEquals(CgGL.GL_NEAREST, own.getMinFilter());
        assertEquals(CgGL.GL_NEAREST, own.getMagFilter());
        assertEquals(CgGL.GL_REPEAT, own.getWrapS());
        assertEquals(CgGL.GL_MIRRORED_REPEAT, own.getWrapT());
        assertFalse(own.getMipmaps().isEnabled());
        CgTextureSpec none = CgMaterialRegistry.textureSpec(DIR + "ramp.hdr");
        assertEquals("trilinear with mips, clamped", CgGL.GL_LINEAR_MIPMAP_LINEAR, none.getMipmaps().getMinFilter());
        assertEquals(CgGL.GL_CLAMP_TO_EDGE, none.getWrapS());
    }

    @Test
    public void aCubeIsSixStackedFacesOrRefusedAtItsFirstUse() {
        assertEquals(4, CgTextureCubemap.createStacked(DIR + "stacked.png",
                CgTextureSpec.RGBA8_LINEAR).getWidth());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CgTextureCubemap.createStacked(DIR + "tall.png",
                        CgTextureSpec.RGBA8_LINEAR).getWidth());
        assertTrue(e.getMessage(), e.getMessage().contains("4 x 8"));
    }

    @Test
    public void aCubeBoundToASampler2DIsRefusedNamingBoth() {
        CgMaterialDefinition d = CgMaterialDefinition.of("test:materials/cube_on_2d", "crystalgraphics:shaders/vfx/particle/sprite.shader")
                .usage(CgMaterialUsage.SPRITES).texture("_Sheet", DIR + "stacked.png").build();
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgMaterial.of(d));
        assertTrue(e.getMessage(), e.getMessage().contains("stacked.png") && e.getMessage().contains("_Sheet"));
    }
}
