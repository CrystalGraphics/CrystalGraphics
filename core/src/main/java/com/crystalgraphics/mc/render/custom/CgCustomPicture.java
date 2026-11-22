package com.crystalgraphics.mc.render.custom;

import java.util.Objects;

import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.CgPictureRefresh;

/**
 * Anything a mod draws with Minecraft's own API, as a picture a canvas shows: the escape hatch for what no other kind
 * covers. Unreal's {@code UThumbnailRenderer}; Mojang's {@code PictureInPictureRenderer}.
 *
 * <pre>{@code
 * static final CgCustomPicture RADAR = CgCustomPicture.of("mymod:radar")
 *         .refresh(CgPictureRefresh.REALTIME)                        // an animation: drawn every frame it shows
 *         .renderer(ctx -> RadarScreen.draw((GuiGraphics) ctx.gui(), ctx.width(), ctx.height()));
 *
 * canvas.picture(RADAR, x, y, 128, 128);
 * }</pre>
 *
 * <pre>{@code
 * // Static: drawn once at each size; invalidate() when what it shows changes
 * CgCustomPicture badge = CgCustomPicture.of("mymod:badge").renderer(Badge::draw);
 * badge.invalidate();
 * }</pre>
 *
 * <ul>
 *   <li>{@link CgPictureRefresh#ONCE} and {@link CgPictureLighting#ITEMS_3D}, the GUI's own, by default.</li>
 *   <li>Without a renderer it draws nothing, and shows as a transparent picture.</li>
 *   <li>Keep one per thing shown: each holds its targets while it is shown.</li>
 * </ul>
 */
public final class CgCustomPicture extends CgHostPicture {

    private static final CgPictureRenderer NOTHING = ctx -> {
    };

    private volatile CgPictureRenderer renderer = NOTHING;

    private CgCustomPicture(String name) {
        super(name, CgPictureRefresh.ONCE, CgPictureLighting.ITEMS_3D);
    }

    /** A picture named {@code name} (logged if it fails), drawing nothing until {@link #renderer} is set. */
    public static CgCustomPicture of(String name) {
        return new CgCustomPicture(name);
    }

    /** What draws it. Draws it again. */
    public CgCustomPicture renderer(CgPictureRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        invalidate();
        return this;
    }

    @Override
    public CgCustomPicture refresh(CgPictureRefresh refresh) {
        super.refresh(refresh);
        return this;
    }

    @Override
    public CgCustomPicture lighting(CgPictureLighting lighting) {
        super.lighting(lighting);
        return this;
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
