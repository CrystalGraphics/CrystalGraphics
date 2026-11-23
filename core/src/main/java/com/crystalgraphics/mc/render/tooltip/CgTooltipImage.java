package com.crystalgraphics.mc.render.tooltip;

import java.util.Objects;

import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;

/**
 * An image a tooltip shows below its lines (a bundle's contents), as {@link CgItemTooltip#images} gives it: drawn by
 * Minecraft at its own size. A host makes one per image.
 *
 * <pre>{@code
 * for (CgPicture image : CgItemTooltip.of(stack).images()) {
 *     canvas.picture(image, x, y);            // at its natural size
 *     y += image.naturalHeight();
 * }
 * }</pre>
 */
public final class CgTooltipImage extends CgHostPicture {

    private final int width, height;
    private final CgPictureRenderer renderer;

    /** The image {@code renderer} draws at the origin, {@code width} by {@code height} units. For a host. */
    public CgTooltipImage(String name, int width, int height, CgPictureRenderer renderer) {
        super(name, CgPictureRefresh.TICK, CgPictureLighting.ITEMS_3D);
        this.width = width;
        this.height = height;
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public float naturalWidth() {
        return width;
    }

    @Override
    public float naturalHeight() {
        return height;
    }

    @Override
    protected void draw(CgHostDrawContext ctx) {
        renderer.draw(ctx);
    }

    @Override
    protected Object drawnBy() {
        return renderer;
    }
}
