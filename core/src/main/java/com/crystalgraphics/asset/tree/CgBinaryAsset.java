package com.crystalgraphics.asset.tree;

/**
 * A texture or mesh made in code, which an asset tree built in Java holds where a file holds its path: Unreal's
 * texture parameter holding the {@code UTexture} itself ({@code FTextureParameterValue}). Nothing registers it; whatever
 * holds the tree holds it.
 *
 * <pre>{@code
 * static final CgTexture2DArray FRAMES = CgTextures.frames("mymod:textures/vfx/aura_%d.png", 3);
 * CgMaterialDefinition.of("mymod:materials/aura", SHADER).texture("_Frames", FRAMES).build();   // the object
 * CgMaterialDefinition.of("mymod:materials/aura", SHADER).texture("_Frames", "mymod:textures/vfx/aura.png").build();
 * }</pre>
 *
 * <ul>
 *   <li>A tree holding one cannot be written to a file: it has no path to write.</li>
 *   <li>Its holder keeps it alive; the engine never releases it.</li>
 * </ul>
 */
public interface CgBinaryAsset {
}
