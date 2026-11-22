package com.crystalgraphics.mc.render.block;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgService;

/** The slot each era fills to draw a {@link CgBlockPicture}. */
public final class CgBlockPictures {

    /** What an era answers, render thread. */
    public interface Host {

        /** Why this node draws no blocks; null where it does. */
        @Nullable
        String absent();

        /**
         * The game's own objects for {@code state}: its block state and, where it has one, a block entity loaded with
         * the state's data, never in the level. {@code previous} is what this answered last for the same picture.
         *
         * @throws IllegalArgumentException where the game has not got the block or a property
         */
        Object prepare(CgBlockState state, @Nullable Object previous);

        /** The block, its block entity with it, as an inventory shows a block, filling {@code size} units square at {@code x, y}. */
        void draw(CgHostDrawContext ctx, Object prepared, float x, float y, float size);

        Host NONE = new Host() {
            @Override public String absent() { return "no block pictures on this node"; }
            @Override public Object prepare(CgBlockState state, Object previous) { return state; }
            @Override public void draw(CgHostDrawContext ctx, Object prepared, float x, float y, float size) { }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:block_pictures", Host.NONE);

    private CgBlockPictures() {
    }
}
