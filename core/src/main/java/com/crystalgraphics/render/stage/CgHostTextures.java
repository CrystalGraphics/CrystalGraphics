package com.crystalgraphics.render.stage;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.platform.rhi.CgHostImports;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * The host's own textures an effect may sample this frame: Minecraft's lightmap, which turns block and sky light into the
 * colour blocks are lit with, and its block atlas, which {@code CgWorldQuery.spriteRect} cuts a block's particle sprite
 * from. Beside {@link CgCamera} and {@link CgHostEnvironment} on the same {@link CgHostFrame}.
 *
 * <pre>{@code
 * CgHostTextures host = frame.host().textures();
 * pass.texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, host.lightmapTexture());   // what cg_Lightmap reads
 *
 * // a host, every frame: imported only when the handle changes
 * CgRenderStage.WORLD_OPAQUE.host().textures().lightmap(lightTexture, 16, 16).blockAtlas(atlas, w, h);
 * // a host that imports its own (the Vulkan host), or the harness handing a texture of ours
 * textures.importedLightmap(host.importTexture(image), 16, 16);
 * }</pre>
 *
 * <ul>
 *   <li>Borrowed: nothing of ours deletes them. Null where the host gives none (the harness, no level).</li>
 *   <li>A handle is imported once ({@link CgHostImports#texture}): again only when the host hands a different one, by
 *       {@code equals} (a resource reload, the lightmap remade). On the Vulkan host an import is a wrap, so never per
 *       frame.</li>
 *   <li>{@link #version} changes whenever either texture does.</li>
 * </ul>
 */
public final class CgHostTextures {

    private Object lightmapHandle, atlasHandle;
    @Nullable private CgGpuTexture lightmap, atlas;
    private int lightmapWidth, lightmapHeight, atlasWidth, atlasHeight;
    private int version;
    private CgTexture2D lightmapTexture;

    /** The lightmap: u is block light, v sky light, 0 to 15 each across it; null when absent. */
    @Nullable
    public CgGpuTexture lightmap() {
        return lightmap;
    }

    /** The lightmap as a texture to bind, the pass binding of {@code cg_Lightmap}: white where the host gives none. */
    public CgTexture lightmapTexture() {
        if (lightmap == null) return CgFallbackTextures.WHITE_1x1;
        if (lightmapTexture == null || lightmapTexture.rhiTexture() != lightmap) {
            if (lightmapTexture != null) lightmapTexture.delete();
            lightmapTexture = CgTexture2D.borrowed(lightmap);
        }
        return lightmapTexture;
    }

    public int lightmapWidth() {
        return lightmapWidth;
    }

    public int lightmapHeight() {
        return lightmapHeight;
    }

    /** The block atlas; null when absent. */
    @Nullable
    public CgGpuTexture blockAtlas() {
        return atlas;
    }

    public int blockAtlasWidth() {
        return atlasWidth;
    }

    public int blockAtlasHeight() {
        return atlasHeight;
    }

    /** Changes whenever either texture does. */
    public int version() {
        return version;
    }

    /** Host side: this frame's lightmap, the handle {@link CgHostImports#texture} takes on this host; null for none. */
    public CgHostTextures lightmap(Object hostHandle, int width, int height) {
        if (!Objects.equals(hostHandle, lightmapHandle)) {
            lightmapHandle = hostHandle;
            setLightmap(hostHandle == null ? null : CgHostImports.get().texture(hostHandle));
        }
        lightmapWidth = width;
        lightmapHeight = height;
        return this;
    }

    /** Host side: this frame's block atlas, as {@link #lightmap(Object, int, int)}. */
    public CgHostTextures blockAtlas(Object hostHandle, int width, int height) {
        if (!Objects.equals(hostHandle, atlasHandle)) {
            atlasHandle = hostHandle;
            setAtlas(hostHandle == null ? null : CgHostImports.get().texture(hostHandle));
        }
        atlasWidth = width;
        atlasHeight = height;
        return this;
    }

    /**
     * Host side: this frame's lightmap, already a device texture: one the host imported itself (the Vulkan host's
     * {@code importTexture}) or one of ours; null for none.
     */
    public CgHostTextures importedLightmap(@Nullable CgGpuTexture texture, int width, int height) {
        lightmapHandle = null;
        setLightmap(texture);
        lightmapWidth = width;
        lightmapHeight = height;
        return this;
    }

    /** Host side: this frame's block atlas, as {@link #importedLightmap}. */
    public CgHostTextures importedBlockAtlas(@Nullable CgGpuTexture texture, int width, int height) {
        atlasHandle = null;
        setAtlas(texture);
        atlasWidth = width;
        atlasHeight = height;
        return this;
    }

    /** Host side: another frame's textures, as the transparent stage takes the opaque one's. */
    public CgHostTextures set(CgHostTextures o) {
        lightmapHandle = o.lightmapHandle;
        atlasHandle = o.atlasHandle;
        setLightmap(o.lightmap);
        setAtlas(o.atlas);
        lightmapWidth = o.lightmapWidth;
        lightmapHeight = o.lightmapHeight;
        atlasWidth = o.atlasWidth;
        atlasHeight = o.atlasHeight;
        return this;
    }

    private void setLightmap(@Nullable CgGpuTexture texture) {
        if (texture != lightmap) version++;
        lightmap = texture;
    }

    private void setAtlas(@Nullable CgGpuTexture texture) {
        if (texture != atlas) version++;
        atlas = texture;
    }
}
