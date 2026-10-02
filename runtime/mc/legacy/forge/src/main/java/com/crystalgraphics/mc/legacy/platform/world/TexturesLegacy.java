package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.mc.legacy.mixin.EntityRendererAccessor;
import com.crystalgraphics.render.stage.CgHostTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;

/**
 * Minecraft 1.8.9 to 1.12.2's lightmap and block atlas at the world stages, as GL names, into the stage's
 * {@link CgHostTextures}. The lightmap is a private field of the entity renderer, reached through an accessor. Render
 * thread; allocates only when a name changes.
 */
public final class TexturesLegacy {

    private static final int LIGHTMAP_SIZE = 16;

    private static int atlasName = -1, lightmapName = -1;
    private static Integer atlasBoxed, lightmapBoxed;

    private TexturesLegacy() {
    }

    public static void capture(Minecraft mc, CgHostTextures out) {
        //? if >=1.9 {
        ResourceLocation atlasId = TextureMap.LOCATION_BLOCKS_TEXTURE;
        //?} else {
        /*ResourceLocation atlasId = TextureMap.locationBlocksTexture;
        *///?}
        ITextureObject atlas = mc.getTextureManager().getTexture(atlasId);
        out.blockAtlas(atlas == null ? null : boxed(atlas.getGlTextureId(), true), 0, 0);
        int lightmap = ((EntityRendererAccessor) mc.entityRenderer).crystalgraphics$lightmap().getGlTextureId();
        out.lightmap(boxed(lightmap, false), LIGHTMAP_SIZE, LIGHTMAP_SIZE);
    }

    /** A GL name boxed once per name, so an unchanged texture hands over the same object. */
    private static Integer boxed(int name, boolean atlas) {
        if (atlas) {
            if (name != atlasName) {
                atlasName = name;
                atlasBoxed = name;
            }
            return atlasBoxed;
        }
        if (name != lightmapName) {
            lightmapName = name;
            lightmapBoxed = name;
        }
        return lightmapBoxed;
    }
}
