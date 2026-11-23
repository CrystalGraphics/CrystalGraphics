package com.crystalgraphics.mc.render;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.render.CgFrameClock;

/**
 * A picture Minecraft draws with its own code: made anywhere, holding data, drawn on the render thread at the next
 * draw point ({@link CgHostPictures}) at every size a canvas shows it. The base of every kind: {@code CgCustomPicture}
 * now, items, entities, blocks and tooltips as they land. Mojang's {@code PictureInPictureRenderState} with its
 * renderer; Unreal's {@code FAssetThumbnail}.
 *
 * <pre>{@code
 * CgCustomPicture radar = CgCustomPicture.of("mymod:radar")
 *         .refresh(CgPictureRefresh.REALTIME)
 *         .renderer(ctx -> RadarScreen.draw((GuiGraphics) ctx.gui(), ctx.width(), ctx.height()));
 * canvas.picture(radar, x, y, 128, 128);
 *
 * if (radar.state() == CgHostPicture.State.FAILED) ...   // its renderer threw: the missing texture shows
 * }</pre>
 *
 * <ul>
 *   <li>Nothing shows until its first draw ({@link State#PENDING}): shown in a frame's GUI, it is drawn at the next
 *       frame's draw point. A new size shows the nearest size already drawn until its own is.</li>
 *   <li>Drawn at the device size it is shown at: a picture shown at two sizes is drawn at each, four at most.</li>
 *   <li>A size nobody showed for 300 frames gives its targets back; the next show draws it again.</li>
 * </ul>
 */
public abstract class CgHostPicture implements CgPicture {

    /** Where a picture stands. */
    public enum State {
        /** Not drawn yet at any size. */
        PENDING,
        /** Drawn at least once. */
        READY,
        /** Its draw threw: it shows Minecraft's missing texture until a resource reload retries it. */
        FAILED
    }

    /** Sizes kept at once; showing a fifth gives back the one shown longest ago. */
    static final int MAX_SIZES = 4;

    private final String name;
    private volatile CgPictureRefresh refresh;
    private volatile CgPictureLighting lighting;
    private volatile State state = State.PENDING;
    private volatile long version;
    /** Bumped by {@link #invalidate}; each size draws again when it differs from what it drew at. */
    private volatile long invalidated;
    /** What the draw point calls: {@link #draw}, made once. */
    final CgPictureRenderer drawer = this::draw;

    // Guarded by this: the sizes it is shown at, and whether the draw point holds it
    final Size[] sizes = new Size[MAX_SIZES];
    int sizeCount;
    boolean held;
    boolean logged;

    protected CgHostPicture(String name, CgPictureRefresh refresh, CgPictureLighting lighting) {
        this.name = Objects.requireNonNull(name, "name");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.lighting = Objects.requireNonNull(lighting, "lighting");
    }

    /** Its name, as its maker gave it: in a failure's log line and the statistics. */
    public String name() {
        return name;
    }

    public State state() {
        return state;
    }

    public CgPictureRefresh refresh() {
        return refresh;
    }

    /** When it is drawn again. */
    public CgHostPicture refresh(CgPictureRefresh refresh) {
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        return this;
    }

    public CgPictureLighting lighting() {
        return lighting;
    }

    /** The light Minecraft sets up before it draws; its kind chooses one as Minecraft's own call sites do. */
    public CgHostPicture lighting(CgPictureLighting lighting) {
        this.lighting = Objects.requireNonNull(lighting, "lighting");
        invalidate();
        return this;
    }

    /** Draws it again at every size at the next draw point, whatever its refresh. Any thread. */
    public void invalidate() {
        invalidated++;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    @Nullable
    public CgPicture.Image image(int deviceWidth, int deviceHeight, float scale) {
        long frame = CgFrameClock.frameIndex();
        boolean register;
        CgPicture.Image shown;
        synchronized (this) {
            Size s = size(deviceWidth, deviceHeight, scale);
            s.shownFrame = frame;
            register = !held;
            held = true;
            shown = state == State.FAILED ? CgPicture.Image.MISSING : s.image != null ? s.image : nearest(deviceWidth);
        }
        if (register) CgHostPictures.hold(this);
        return shown;
    }

    /**
     * Draws the picture with Minecraft's code into the target {@code ctx} points the GUI at. Render thread, at the draw
     * point; Minecraft's state is its own.
     */
    protected abstract void draw(CgHostDrawContext ctx);

    /** The trace zone its draws land in, a constant per kind: {@code pictures.entity}, {@code pictures.block}. */
    protected String traceZone() {
        return "pictures.custom";
    }

    /** What draws it, named in a failure's log line: a custom picture's renderer. Itself by default. */
    protected Object drawnBy() {
        return this;
    }

    /**
     * Units its targets reach past the rect on each side, for what Minecraft draws outside the box it is given (a
     * tooltip mod's frame). {@link #draw}'s context covers the margin too: the rect starts at {@code (bleed, bleed)}.
     * 0 by default.
     */
    protected float bleed() {
        return 0f;
    }

    /** Its {@link #version} moves: something a canvas reads of it changed (a kind's natural size). Any thread. */
    protected final void changed() {
        version++;
    }

    /** Moves when what it draws changes, for {@link CgPictureRefresh#ON_CHANGE}. 0 by default: never. */
    protected long stamp() {
        return 0L;
    }

    // ── The draw point's side ────────────────────────────────────────────

    /** Whether size {@code s} draws at {@code frame}: first shown, or due by its refresh. Guarded by this. */
    boolean due(Size s, long frame) {
        if (s.drawnFrame == frame || state == State.FAILED) return false;
        if (s.image == null) return true;
        if (s.drawnInvalidated != invalidated) return true;
        return switch (refresh) {
            case REALTIME -> s.shownFrame >= frame - 1;
            case TICK -> s.shownFrame >= frame - 1 && CgHostPictures.ticked();
            case ON_CHANGE -> s.drawnStamp != stamp();
            case ONCE -> false;
        };
    }

    /** Its padded cell's pixels. */
    static int cellWidth(Size s) {
        return s.width + 2 * s.pad;
    }

    /** @see #cellWidth */
    static int cellHeight(Size s) {
        return s.height + 2 * s.pad;
    }

    /** Size {@code s} was drawn at {@code frame}, showing {@code image}. Guarded by this. */
    void drawn(Size s, long frame, CgPicture.Image image) {
        s.drawnFrame = frame;
        s.drawnStamp = stamp();
        s.drawnInvalidated = invalidated;
        s.image = image;
        if (state == State.PENDING) state = State.READY;
        version++;
    }

    void failed() {
        state = State.FAILED;
        version++;
    }

    /** A resource reload: a failed picture draws again. */
    void retry() {
        if (state == State.FAILED) {
            state = State.PENDING;
            logged = false;
            invalidate();
        }
    }

    private Size size(int w, int h, float scale) {
        for (int i = 0; i < sizeCount; i++) {
            Size s = sizes[i];
            if (s.width == w && s.height == h) return s;
        }
        if (sizeCount == MAX_SIZES) {
            int oldest = 0;
            for (int i = 1; i < sizeCount; i++) if (sizes[i].shownFrame < sizes[oldest].shownFrame) oldest = i;
            CgHostPictures.release(sizes[oldest]);
            sizes[oldest] = sizes[--sizeCount];
        }
        Size s = new Size(this, w, h, scale, Math.round(bleed() * scale));
        sizes[sizeCount++] = s;
        return s;
    }

    @Nullable
    private CgPicture.Image nearest(int width) {
        Size best = null;
        for (int i = 0; i < sizeCount; i++) {
            Size s = sizes[i];
            if (s.image != null && (best == null || Math.abs(s.width - width) < Math.abs(best.width - width))) best = s;
        }
        return best == null ? null : best.image;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + ", " + state + "]";
    }

    /** One size it is shown at: its cell on a page of the draw point's, and what the cell shows. */
    static final class Size {
        final CgHostPicture owner;
        /** The rect's pixels; {@code pad} more on each side for the bleed. */
        final int width, height, pad;
        final float scale;
        long shownFrame = Long.MIN_VALUE, drawnFrame = Long.MIN_VALUE, drawnStamp, drawnInvalidated = -1L;
        /** Render thread: the page it is placed on (null until its first draw), its cell's top-left there in pixels. */
        @Nullable CgHostPictures.Page page;
        int x, y;
        /** Render thread: its page repacked it elsewhere, so it draws this batch whether due or not. */
        boolean moved;
        /** Render thread: the page and place {@link #image} was made for, so a redraw in place keeps it. */
        @Nullable CgHostPictures.Page imagePage;
        int imageX, imageY;
        @Nullable volatile CgPicture.Image image;

        Size(CgHostPicture owner, int width, int height, float scale, int pad) {
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.pad = pad;
        }
    }
}
