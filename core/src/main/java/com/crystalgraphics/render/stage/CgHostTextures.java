package com.crystalgraphics.render.stage;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;

import java.util.Objects;

/**
 * The host's own textures an effect may sample this frame: Minecraft's lightmap, which turns block and sky light into the
 * colour blocks are lit with, and its block atlas, which {@code CgWorldQuery.spriteRect} cuts a block's particle sprite
 * from. Beside {@link CgHostView} and {@link CgHostEnvironment} on the same {@link CgHostFrame}.
 *
 * <pre>{@code
 * CgHostTextures host = frame.host().textures();
 * pass.texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, host.lightmapTexture());   // what cg_Lightmap reads
 *
 * // a host, with the camera, every frame: imported only when the texture object changes
 * CgRenderStage.WORLD_OPAQUE.host().textures().lightmap(lightTexture, 16, 16).blockAtlas(atlas, w, h);
 * }</pre>
 *
 * <ul>
 *   <li>Borrowed: nothing of ours deletes them. A name is 0 where the host gives none (the harness, no level).</li>
 *   <li>Imported once per host texture object ({@link CgGL#importHostTexture}): again only when the host hands a
 *       different one, by {@code equals} (a resource reload, the lightmap remade). On the Vulkan host an import is a
 *       wrap, so never per frame.</li>
 *   <li>{@link #version} changes with every import, for a holder that wraps a name in a {@code CgTexture2D}.</li>
 * </ul>
 */
public final class CgHostTextures {

    private Object lightmapHandle, atlasHandle;
    private int lightmap, atlas;
    private int lightmapWidth, lightmapHeight, atlasWidth, atlasHeight;
    private int version;
    private CgTexture2D lightmapTexture;

    /** The lightmap's texture name: u is block light, v sky light, 0 to 15 each across it; 0 when absent. */
    public int lightmap() {
        return lightmap;
    }

    /**
     * The lightmap as a texture to bind, the pass binding of {@code cg_Lightmap}: wrapped once per host texture, and
     * white where the host gives none.
     */
    public CgTexture lightmapTexture() {
        if (lightmap == 0) return CgFallbackTextures.WHITE_1x1;
        if (lightmapTexture == null || lightmapTexture.getId() != lightmap) {
            if (lightmapTexture != null) lightmapTexture.delete();
            lightmapTexture = CgTexture2D.wrap(lightmap, Math.max(lightmapWidth, 1), Math.max(lightmapHeight, 1));
        }
        return lightmapTexture;
    }

    public int lightmapWidth() {
        return lightmapWidth;
    }

    public int lightmapHeight() {
        return lightmapHeight;
    }

    /** The block atlas's texture name; 0 when absent. */
    public int blockAtlas() {
        return atlas;
    }

    public int blockAtlasWidth() {
        return atlasWidth;
    }

    public int blockAtlasHeight() {
        return atlasHeight;
    }

    /** Changes whenever either name does. */
    public int version() {
        return version;
    }

    /** Host side: this frame's lightmap, the handle {@link CgGL#importHostTexture} takes on this host; null for none. */
    public CgHostTextures lightmap(Object hostHandle, int width, int height) {
        if (!Objects.equals(hostHandle, lightmapHandle)) {
            lightmapHandle = hostHandle;
            lightmap = hostHandle == null ? 0 : CgGL.importHostTexture(hostHandle);
            version++;
        }
        lightmapWidth = width;
        lightmapHeight = height;
        return this;
    }

    /** Host side: this frame's block atlas, as {@link #lightmap(Object, int, int)}. */
    public CgHostTextures blockAtlas(Object hostHandle, int width, int height) {
        if (!Objects.equals(hostHandle, atlasHandle)) {
            atlasHandle = hostHandle;
            atlas = hostHandle == null ? 0 : CgGL.importHostTexture(hostHandle);
            version++;
        }
        atlasWidth = width;
        atlasHeight = height;
        return this;
    }

    /**
     * Host side: this frame's lightmap as a name the host already imported, for a host with its own cached import (the
     * Vulkan host's {@code importTexture}, which wraps its image in what the tracked backend takes); 0 for none.
     */
    public CgHostTextures importedLightmap(int name, int width, int height) {
        if (name != lightmap || lightmapHandle != null) {
            lightmapHandle = null;
            lightmap = name;
            version++;
        }
        lightmapWidth = width;
        lightmapHeight = height;
        return this;
    }

    /** Host side: this frame's block atlas, as {@link #importedLightmap}. */
    public CgHostTextures importedBlockAtlas(int name, int width, int height) {
        if (name != atlas || atlasHandle != null) {
            atlasHandle = null;
            atlas = name;
            version++;
        }
        atlasWidth = width;
        atlasHeight = height;
        return this;
    }

    /** Host side: another frame's textures, as the transparent stage takes the opaque one's. */
    public CgHostTextures set(CgHostTextures o) {
        if (!Objects.equals(lightmapHandle, o.lightmapHandle) || !Objects.equals(atlasHandle, o.atlasHandle)) version++;
        lightmapHandle = o.lightmapHandle;
        atlasHandle = o.atlasHandle;
        lightmap = o.lightmap;
        atlas = o.atlas;
        lightmapWidth = o.lightmapWidth;
        lightmapHeight = o.lightmapHeight;
        atlasWidth = o.atlasWidth;
        atlasHeight = o.atlasHeight;
        return this;
    }
}
