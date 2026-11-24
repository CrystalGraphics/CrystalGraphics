package com.crystalgraphics.mc.render.item;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.render.atlas.CgIconAtlas;
import com.crystalgraphics.render.CgFrameClock;

/**
 * An item stack's icon, drawn by Minecraft into the icon atlas: a cell shared by every stack that looks alike, drawn
 * once and kept, or drawn again each frame it shows while it animates (a glint, a clock, a mod's renderer).
 *
 * <pre>{@code
 * CgItemIcon sword = CgItemIcons.of(stack);       // any thread; the same handle for an equal stack
 * canvas.picture(sword, x, y, 16, 16);              // at the rect's device pixels: crisp at any scale
 * canvas.picture(sword.overlay(), x, y, 16, 16);    // its count, bar, cooldown and mods' decorators, over it
 * }</pre>
 *
 * <ul>
 *   <li>Shown for the first time, it appears a frame later: Minecraft draws it at the next draw point.</li>
 *   <li>A size it is not shown at for a while gives its cell back; the next show draws it again.</li>
 *   <li>A stack whose drawing throws shows Minecraft's missing texture, logged once; an item the game does not know
 *       shows nothing.</li>
 * </ul>
 */
public final class CgItemIcon implements CgPicture {

    /** Sizes one icon is kept at, at most. */
    static final int MAX_SIZES = 4;

    /** One device-pixel size: the cell it shows. */
    static final class Size {
        final int pixels;
        volatile long shownFrame;
        @Nullable CgIconAtlas.Cell cell;
        /** Which drawing of its cell this size last answered, for {@link #version}. */
        long drawnFrame = Long.MIN_VALUE;

        Size(int pixels) {
            this.pixels = pixels;
        }
    }

    private final CgItemStack stack;
    /** Whether this is a stack's decorations ({@link #overlay}) rather than its icon. */
    final boolean decorations;
    /** What a decorations icon draws in its count's place; null for the stack's own count. */
    @Nullable final String countText;
    @Nullable private volatile CgItemIcon overlay;
    /** Decorations with a text in the count's place, by text. */
    @Nullable private Map<String, CgItemIcon> counted;
    /** The host's stack, made at the first draw point that resolves it; NONE where the game does not know the item. */
    @Nullable Object hostStack;
    boolean unknown;
    final Size[] sizes = new Size[MAX_SIZES];
    int sizeCount;
    boolean held;
    private volatile long version;
    /** Whether the next draw point draws its sizes whatever the budget: {@link CgItemIcons#prewarm}. */
    boolean prewarm;

    CgItemIcon(CgItemStack stack) {
        this(stack, false, null);
    }

    private CgItemIcon(CgItemStack stack, boolean decorations, @Nullable String countText) {
        this.stack = stack;
        this.decorations = decorations;
        this.countText = countText;
    }

    /**
     * The stack's decorations, drawn by Minecraft's own decoration code: its count, durability bar, cooldown and every
     * mod's decorator (Forge's and NeoForge's {@code IItemDecorator}). Shown over the icon in the same rect; the count's
     * text reaches past it, as in vanilla. Nothing for a stack that draws none. An overlay's overlay is itself.
     */
    public CgItemIcon overlay() {
        if (decorations) return this;
        CgItemIcon o = overlay;
        if (o == null) {
            synchronized (this) {
                o = overlay;
                if (o == null) overlay = o = new CgItemIcon(stack, true, null);
            }
        }
        return o;
    }

    /**
     * Its decorations with {@code countText} drawn where the count goes, in Minecraft's font and its formatting codes:
     * what vanilla's drag shows over each slot it shares a stack into, and over the stack it leaves on the pointer.
     *
     * <pre>{@code
     * canvas.picture(icon.overlay("§e64"), x, y, 16, 16);   // a share capped at the stack's limit, yellow
     * canvas.picture(icon.overlay("§e0"), x, y, 16, 16);    // nothing left on the pointer
     * }</pre>
     *
     * Null is {@link #overlay()}. Each text is its own picture, kept while the icon is.
     */
    public CgItemIcon overlay(@Nullable String countText) {
        if (countText == null) return overlay();
        if (decorations && countText.equals(this.countText)) return this;
        synchronized (this) {
            if (counted == null) counted = new HashMap<>();
            return counted.computeIfAbsent(countText, t -> new CgItemIcon(stack, true, t));
        }
    }

    /** The stack it shows. */
    public CgItemStack stack() {
        return stack;
    }

    /**
     * Its icon at {@code deviceWidth} by {@code deviceHeight} pixels (the larger side; an icon is square), marked shown
     * this frame: null until first drawn, the nearest size drawn meanwhile, or the missing texture where it failed.
     * Any thread.
     */
    @Override
    @Nullable
    public CgPicture.Image image(int deviceWidth, int deviceHeight, float scale) {
        int px = Math.max(1, Math.max(deviceWidth, deviceHeight));
        long frame = CgFrameClock.frameIndex();
        boolean register;
        CgPicture.Image shown;
        synchronized (this) {
            Size s = size(px);
            s.shownFrame = frame;
            register = !held;
            held = true;
            CgIconAtlas.Cell c = s.cell;
            shown = c != null && c.image() != null ? c.image() : nearest(px);
        }
        if (register) CgItemIcons.hold(this);
        return shown;
    }

    /**
     * Whether it is drawn again while it shows, at {@link CgItemIcons#ANIMATED_HZ}, as its look was last read: a glint, a custom renderer, an animated
     * sprite, or a look the host cannot classify. False before its first draw.
     */
    public boolean animated() {
        CgIconAtlas.Cell c = latest();
        return c != null && c.animated;
    }

    /** What animates it ({@code "glint"}, {@code "custom renderer"}...); null when still or not yet drawn. */
    @Nullable
    public String why() {
        CgIconAtlas.Cell c = latest();
        return c == null ? null : c.why;
    }

    // The cell of the size shown last
    @Nullable
    private synchronized CgIconAtlas.Cell latest() {
        Size best = null;
        for (int i = 0; i < sizeCount; i++) if (best == null || sizes[i].shownFrame > best.shownFrame) best = sizes[i];
        return best == null ? null : best.cell;
    }

    /** Moves each time a cell it shows draws again or changes. */
    @Override
    public long version() {
        return version;
    }

    /** Draws it at {@code devicePixels} at the next draw point, whatever that frame's budget: a screen opening. */
    public void prewarm(int devicePixels) {
        boolean register;
        synchronized (this) {
            size(Math.max(1, devicePixels)).shownFrame = CgFrameClock.frameIndex();
            prewarm = true;
            register = !held;
            held = true;
        }
        if (register) CgItemIcons.hold(this);
    }

    @Override
    public String toString() {
        return (decorations ? "CgItemIcon.overlay[" : "CgItemIcon[") + stack.item() + "]";
    }

    // Guarded by this
    private Size size(int px) {
        for (int i = 0; i < sizeCount; i++) if (sizes[i].pixels == px) return sizes[i];
        Size s = new Size(px);
        if (sizeCount == MAX_SIZES) {
            int oldest = 0;
            for (int i = 1; i < sizeCount; i++) if (sizes[i].shownFrame < sizes[oldest].shownFrame) oldest = i;
            CgItemIcons.dropped(sizes[oldest]);
            sizes[oldest] = s;
        } else {
            sizes[sizeCount++] = s;
        }
        return s;
    }

    // The drawn size closest to px, meanwhile; guarded by this
    @Nullable
    private CgPicture.Image nearest(int px) {
        CgPicture.Image best = null;
        int gap = Integer.MAX_VALUE;
        for (int i = 0; i < sizeCount; i++) {
            CgIconAtlas.Cell c = sizes[i].cell;
            CgPicture.Image im = c == null ? null : c.image();
            if (im == null) continue;
            int d = Math.abs(sizes[i].pixels - px);
            if (d < gap) {
                gap = d;
                best = im;
            }
        }
        return best;
    }

    void moved() {
        version++;
    }
}
