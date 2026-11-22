package com.crystalgraphics.mc.render.block;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgPlatform;

/**
 * A block as an inventory shows one: its state through Minecraft's block renderer, its block entity's renderer with it
 * (a chest's lid, a banner's pattern, a sign's text), turned as a block item is in a slot.
 *
 * <pre>{@code
 * CgBlockPicture furnace = CgBlockPicture.of(CgBlockState.parse("furnace[facing=south,lit=true]"));
 * CgBlockPicture chest = CgBlockPicture.of(CgBlockState.parse("chest[facing=south]{Items:[...]}"));
 * canvas.picture(chest, x, y, 32, 32);
 * }</pre>
 *
 * <ul>
 *   <li>Drawn every frame where the state has block entity data, whose renderer may animate; once otherwise.</li>
 *   <li>A block the game has not got shows Minecraft's missing texture, as a picture that fails does.</li>
 *   <li>The block entity is made client-side from the state's data and never joins the level.</li>
 * </ul>
 */
public final class CgBlockPicture extends CgHostPicture {

    private final CgBlockState state;
    @Nullable private Object prepared;

    private CgBlockPicture(CgBlockState state) {
        super("block " + state, state.hasBlockEntity() ? CgPictureRefresh.REALTIME : CgPictureRefresh.ONCE,
                CgPictureLighting.ITEMS_3D);
        this.state = Objects.requireNonNull(state, "state");
    }

    public static CgBlockPicture of(CgBlockState state) {
        return new CgBlockPicture(state);
    }

    /** The block state it shows. */
    public CgBlockState block() {
        return state;
    }

    @Override
    protected void draw(CgHostDrawContext ctx) {
        CgBlockPictures.Host host = CgPlatform.get(CgBlockPictures.HOST);
        if (host.absent() != null) return;
        prepared = host.prepare(state, prepared);
        float size = Math.min(ctx.width(), ctx.height());
        host.draw(ctx, prepared, (ctx.width() - size) / 2, (ctx.height() - size) / 2, size);
    }

    @Override
    protected String traceZone() {
        return "pictures.block";
    }
}
