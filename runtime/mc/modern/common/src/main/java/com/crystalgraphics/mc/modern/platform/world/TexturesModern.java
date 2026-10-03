package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.render.stage.CgHostTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
//? if >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
*///?}
//? if >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.GraphicsApi;
import com.crystalgraphics.mc.modern.platform.vulkan.Blaze3dVulkanHost;
*///?}
//? if >=1.21.5 <26.1 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
*///?}

/**
 * Minecraft's lightmap and block atlas at the world stages, into the stage's {@link CgHostTextures}: a GL name on a GL
 * host, a name {@code Blaze3dVulkanHost} imported on the 26.2 Vulkan host.
 * Render thread; allocates only when a name changes.
 *
 * <ul>
 *   <li>The lightmap from 1.21.5, where Minecraft first hands its texture out; absent before, where reaching it would
 *       take an accessor into a private field.</li>
 *   <li>The block atlas everywhere; its size from 1.21.5 (0 before, where UVs are what callers use).</li>
 * </ul>
 */
public final class TexturesModern {

    private static final int LIGHTMAP_SIZE = 16;

    private static int atlasName = -1, lightmapName = -1;
    private static Integer atlasBoxed, lightmapBoxed;

    private TexturesModern() {
    }

    public static void capture(Minecraft mc, CgHostTextures out) {
        //? if >=26.2 {
        /*if (GraphicsApi.vulkan()) {
            // Imported through the Vulkan host, which wraps each image once and keeps its name.
            GpuTexture image = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
            out.importedBlockAtlas(Blaze3dVulkanHost.current().importTexture(image), image.getWidth(0), image.getHeight(0));
            out.importedLightmap(Blaze3dVulkanHost.current().importTexture(mc.gameRenderer.lightmap().texture()),
                    LIGHTMAP_SIZE, LIGHTMAP_SIZE);
            return;
        }
        *///?}
        //? if >=1.21.5 {
        /*GpuTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
        out.blockAtlas(handle(atlas, true), atlas.getWidth(0), atlas.getHeight(0));
        *///?} else {
        out.blockAtlas(boxed(mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId(), true), 0, 0);
        //?}

        //? if >=26.1 {
        /*out.lightmap(handle(mc.gameRenderer.lightmap().texture(), false), LIGHTMAP_SIZE, LIGHTMAP_SIZE);
        *///?} elif >=1.21.6 {
        /*out.lightmap(handle(mc.gameRenderer.lightTexture().getTextureView().texture(), false), LIGHTMAP_SIZE, LIGHTMAP_SIZE);
        *///?} elif >=1.21.5 {
        /*out.lightmap(handle(mc.gameRenderer.lightTexture().getTarget(), false), LIGHTMAP_SIZE, LIGHTMAP_SIZE);
        *///?} else {
        out.lightmap(null, 0, 0);
        //?}
    }

    // What CgGL.importHostTexture takes on a GL host: the GL name, boxed. The Vulkan host imports its own, above.
    //? if >=26.1 {
    /*private static Object handle(GpuTexture texture, boolean atlas) {
        return texture == null ? null : boxed(((GlTexture) texture).glId(), atlas);
    }
    *///?} elif >=1.21.5 {
    /*// Through LifecycleModern.hostTexture: NeoForge's dev runs wrap every texture for validation.
    private static Object handle(GpuTexture texture, boolean atlas) {
        return texture == null ? null : boxed(((GlTexture) LifecycleModern.hostTexture(texture)).glId(), atlas);
    }
    *///?}

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
