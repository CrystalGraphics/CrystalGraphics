package com.crystalgraphics.mc.fluid;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * What a client shows of a fluid: its name, its still sprite in the block atlas, and its tint. Each loader answers
 * from its own fluid API; the canvas draws the sprite itself, never the host.
 *
 * <pre>{@code
 * String name = CgFluids.name(stack);              // "Lava"
 * String sprite = CgFluids.stillSprite(stack);     // "minecraft:block/lava_still", a block-atlas sprite
 * int argb = CgFluids.tint(stack);                 // 0xFF3F76E4 for water in a plains biome's default
 * }</pre>
 *
 * <ul>
 *   <li>Client only: a dedicated server answers the fluid's id as its name, no sprite and white.</li>
 *   <li>A fluid the loader does not know answers its id, no sprite and white.</li>
 * </ul>
 */
public final class CgFluids {

    /** What each loader answers, client side. */
    public interface Host {

        /** The fluid's display name in the client's language; null where the loader has none. */
        @Nullable
        String name(CgFluidStack stack);

        /** The still texture's block-atlas sprite id ({@code minecraft:block/water_still}); null for none. */
        @Nullable
        String stillSprite(CgFluidStack stack);

        /** ARGB: the colour the sprite is multiplied by. */
        int tint(CgFluidStack stack);

        Host NONE = new Host() {
            @Override public String name(CgFluidStack stack) { return null; }
            @Override public String stillSprite(CgFluidStack stack) { return null; }
            @Override public int tint(CgFluidStack stack) { return 0xFFFFFFFF; }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:fluids", Host.NONE);

    private CgFluids() {
    }

    /** The display name; the fluid's id where none is known. */
    public static String name(CgFluidStack stack) {
        String name = stack.isEmpty() ? null : CgPlatform.get(HOST).name(stack);
        return name != null ? name : stack.fluid();
    }

    @Nullable
    public static String stillSprite(CgFluidStack stack) {
        return stack.isEmpty() ? null : CgPlatform.get(HOST).stillSprite(stack);
    }

    public static int tint(CgFluidStack stack) {
        return stack.isEmpty() ? 0xFFFFFFFF : CgPlatform.get(HOST).tint(stack);
    }
}
