package com.crystalgraphics.mc.render.tooltip;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.render.CgFrameClock;

/**
 * An item's tooltip as data: the lines Minecraft and every mod's tooltip event give it, and the images below them, for
 * a UI that draws the tooltip in its own style. {@link CgTooltipPicture} draws Minecraft's own.
 *
 * <pre>{@code
 * CgItemTooltip tip = CgItemTooltip.of(stack);       // any thread; the same handle for an equal stack
 * for (CgStyledText line : tip.lines()) draw(line);   // empty until the next draw point reads it
 * for (CgPicture image : tip.images()) canvas.picture(image, x, y);   // a bundle's contents, at its own size
 * if (tip.version() != seen) relayout();              // its lines moved: Shift held, the game changed
 * }</pre>
 *
 * <ul>
 *   <li>Read at the draw point, again every frame it was asked for: lines move with the keys held (advanced tooltips,
 *       Shift for more) and with the game.</li>
 *   <li>Lines are styled text: colours, bold, italic, underline and strikethrough; obfuscated text reads plain.</li>
 *   <li>Images are pictures Minecraft draws, each at its own size ({@link CgPicture#naturalWidth}); from 1.20, none
 *       below.</li>
 * </ul>
 */
public final class CgItemTooltip {

    private final CgItemStack stack;
    @Nullable private Object hostStack;
    private boolean unknown;
    private volatile List<CgStyledText> lines = List.of();
    private volatile List<CgPicture> images = List.of();
    private volatile long version;
    private volatile long askedFrame = Long.MIN_VALUE;
    private boolean pending;

    CgItemTooltip(CgItemStack stack) {
        this.stack = stack;
    }

    /** The tooltip of {@code stack}. Any thread. */
    public static CgItemTooltip of(CgItemStack stack) {
        return CgTooltips.data(stack);
    }

    public CgItemStack stack() {
        return stack;
    }

    /** Its lines, the item's name first; empty until first read. Marks it asked for. Any thread. */
    public List<CgStyledText> lines() {
        ask();
        return lines;
    }

    /** Pictures of the images below its lines. Marks it asked for. Any thread. */
    public List<CgPicture> images() {
        ask();
        return images;
    }

    /** Moves whenever its lines or images change. */
    public long version() {
        return version;
    }

    private void ask() {
        long frame = CgFrameClock.frameIndex();
        if (askedFrame == frame) return;
        askedFrame = frame;
        boolean register;
        synchronized (this) {
            register = !pending;
            pending = true;
        }
        if (register) CgTooltips.asked(this);
    }

    /** Read again at the draw point; false once it was not asked for since the last frame, and is let go. */
    boolean resolve(CgTooltips.Host host, long frame) {
        if (askedFrame < frame - 1) {
            synchronized (this) {
                pending = false;
            }
            return false;
        }
        if (host.absent() != null) return true;
        if (hostStack == null && !unknown) {
            hostStack = CgTooltips.toHost(host, stack);
            unknown = hostStack == null;
        }
        if (unknown) return true;
        try {
            List<CgStyledText> l = List.copyOf(host.lines(hostStack));
            if (!l.equals(lines)) {
                lines = l;
                images = List.copyOf(host.images(hostStack));
                version++;
            }
        } catch (RuntimeException e) {
            unknown = true;
            CgTooltips.LOGGER.warn("[cg] the tooltip of {} could not be read", stack, e);
        }
        return true;
    }
}
