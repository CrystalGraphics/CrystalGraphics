package com.crystalgraphics.mc.render.tooltip;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgPlatform;

/**
 * An item's whole tooltip as a screen draws it under the pointer: its lines, its images (a bundle's contents) and its
 * background, by Minecraft's tooltip code, so every tooltip mod's restyle shows. Sized by the host's own measure.
 *
 * <pre>{@code
 * CgTooltipPicture tip = CgTooltipPicture.of(stack);          // any thread; the same handle for an equal stack
 * canvas.picture(tip, pointerX + 12, pointerY - 12);           // at its own size, its box's top-left there
 * }</pre>
 *
 * <ul>
 *   <li>It knows its size from its first draw point on: until then {@link #naturalWidth} is 0 and nothing shows.</li>
 *   <li>Drawn every frame it is shown, as vanilla draws its own: a tooltip mod may animate its frame.</li>
 *   <li>A tooltip mod's frame reaching past the box shows too: the picture holds a margin around it, drawn past the
 *       rect.</li>
 *   <li>An item the game does not know, or a stack with no tooltip, shows nothing.</li>
 * </ul>
 */
public final class CgTooltipPicture extends CgHostPicture {

    /** Units held around the box for what a tooltip mod draws past it. */
    static final int BLEED = 12;

    private final CgItemStack stack;
    @Nullable private Object hostStack;
    private boolean unknown;
    private volatile int width, height;

    CgTooltipPicture(CgItemStack stack) {
        super("tooltip " + stack.item(), CgPictureRefresh.REALTIME, CgPictureLighting.ITEMS_3D);
        this.stack = stack;
    }

    /** The tooltip of {@code stack}. Any thread. */
    public static CgTooltipPicture of(CgItemStack stack) {
        return CgTooltips.picture(stack);
    }

    /** The stack it shows. */
    public CgItemStack stack() {
        return stack;
    }

    /** The box's width in units, border included; 0 until measured, or where it shows none. */
    @Override
    public float naturalWidth() {
        return width;
    }

    @Override
    public float naturalHeight() {
        return height;
    }

    @Override
    protected float bleed() {
        return BLEED;
    }

    @Override
    protected void draw(CgHostDrawContext ctx) {
        CgTooltips.Host host = CgPlatform.get(CgTooltips.HOST);
        if (host.absent() != null) return;
        if (hostStack == null && !unknown) {
            hostStack = CgTooltips.toHost(host, stack);
            unknown = hostStack == null;
        }
        if (unknown) return;
        int[] box = host.measure(hostStack);
        int w = box == null ? 0 : box[0], h = box == null ? 0 : box[1];
        if (w != width || h != height) {
            // Shown at its new size from the next frame; this one draws into the size it was asked at
            width = w;
            height = h;
            changed();
        }
        if (w > 0 && h > 0) host.draw(ctx, hostStack, BLEED, BLEED);
    }
}
