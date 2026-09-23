package com.crystalgraphics.mc.modern.platform;

import net.minecraft.resources.ResourceLocation;

/**
 * Builds a {@link ResourceLocation}, in whichever spelling the running Minecraft has.
 *
 * <p>1.21 made both constructors private in favour of static factories. This is the one place that
 * knows, so every call site compiles unchanged on every node of the tree.</p>
 *
 * <pre>
 * ResourceIds.of(CrystalGraphics.MODID, "asset_reload")
 * ResourceIds.parse("minecraft:ui.button.click")
 * </pre>
 *
 * <p>Both throw on an invalid character, as the constructors did.</p>
 */
public final class ResourceIds {

    private ResourceIds() {}

    /** {@code namespace:path}. */
    public static ResourceLocation of(String namespace, String path) {
        //? if >=1.21 {
        /*return ResourceLocation.fromNamespaceAndPath(namespace, path);
        *///?} else {
        return new ResourceLocation(namespace, path);
        //?}
    }

    /** A {@code namespace:path} string; an id with no namespace is {@code minecraft}'s. */
    public static ResourceLocation parse(String id) {
        //? if >=1.21 {
        /*return ResourceLocation.parse(id);
        *///?} else {
        return new ResourceLocation(id);
        //?}
    }
}
