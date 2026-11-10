package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * Widgets that follow points in the world, drawn over the HUD where those points are on screen: UMG's world widget
 * screen layer, where every screen-space {@code UWidgetComponent} of one {@code SharedLayerName} is placed. Each
 * {@link CgWidgetComponent} sits with its pivot on its attachment's point, at its draw size or its desired size, the
 * nearer drawn over the farther, in its kit's UI scale; its instances come from the layer's {@link CgUserWidgetPool}.
 *
 * <pre>{@code
 * // once, client side: Kakarot's damage numbers, eight at once, sixteen more waiting
 * static final CgWorldWidgetLayer NUMBERS = CgWorldWidgetLayer.builder("mymod:damage_numbers")
 *         .kit("kk")                        // its ui.yaml scales it, as the game's DPI curve does
 *         .retaining(1024f, 1024f, 20f)     // its retainer boxes' image, in widget units
 *         .retainedScale(1.5f)              // its sequences scale it up to 1.5: retained at that, never magnified
 *         .most(8, 16)
 *         .build();
 *
 * // a hit: added, set up, and released when its sequence ends
 * CgWidgetComponent pop = NUMBERS.add(POP.loaded(), CgEntityAttachments.of(targetId));
 * pop.widget().text(NUMBER, "1,250");
 * pop.play(NORMAL_TEXT.loaded(), "number");
 * }</pre>
 *
 * <pre>{@code
 * // a preview of your own: its own world, its sequences on that world's widget sequencer
 * CgWorldWidgetLayer preview = CgWorldWidgetLayer.builder("mymod:preview").world(previewWorld).build();
 * preview.close();                          // off the HUD, its components released
 * }</pre>
 *
 * <ul>
 *   <li>A component starts at the layer's next tick ({@link CgTickGroup#PLAYS}), so its sequence is evaluated before
 *       it is first drawn; under {@link Builder#most} the newest waiting start first.</li>
 *   <li>Its sequences play on its world's widget sequencer: real time, through a pause.</li>
 *   <li>A level change releases every component: their attachments were the old level's.</li>
 *   <li>Its tree's UI materials and text styles' faces are built as the tree loads ({@link CgWidgetPreparer}): add
 *       components of a loaded tree only.</li>
 *   <li>Client, the render thread; it draws on {@link CgRenderStage#HUD} at {@link Builder#order}.</li>
 * </ul>
 */
public final class CgWorldWidgetLayer implements AutoCloseable {

    /** Where a point is on the HUD's canvas: a GUI stage's {@code projectWorld}, or a test's. */
    interface Projector {
        boolean project(double x, double y, double z, Vector2f out);
    }

    private final String id;
    @Nullable
    private final String kit;
    private final float retainedScale;
    private final int mostLive;
    private final int mostWaiting;
    private final float retainedWidth;
    private final float retainedHeight;
    private final float margin;
    /** Made at its first draw: a server or a headless test never makes one. */
    @Nullable
    private CgCanvasWidgetPainter painter;
    @Nullable
    private CgStageFrame frame;
    private final Projector onHud = (x, y, z, out) -> frame.projectWorld(x, y, z, out);
    private final CgUserWidgetPool pool = new CgUserWidgetPool();
    private final List<CgWidgetComponent> queued = new ArrayList<>();
    private final List<CgWidgetComponent> live = new ArrayList<>();
    @Nullable
    private final CgWorld fixedWorld;
    @Nullable
    private final CgRenderStage.Registration drawing;
    @Nullable
    private CgTickFunction tick;
    private int epoch = Integer.MIN_VALUE;

    private CgWidgetComponent[] drawn = new CgWidgetComponent[16];
    private int drawnCount;
    private final double[] point = new double[3];
    private final Vector2f projected = new Vector2f();
    private final Vector2f measured = new Vector2f();
    private final Matrix4f placement = new Matrix4f();

    private CgWorldWidgetLayer(Builder b, boolean onHud) {
        this.id = b.id;
        this.kit = b.kit;
        this.retainedScale = b.retainedScale;
        this.mostLive = b.mostLive;
        this.mostWaiting = b.mostWaiting;
        this.fixedWorld = b.world;
        this.retainedWidth = b.width;
        this.retainedHeight = b.height;
        this.margin = b.margin;
        this.drawing = onHud ? CgRenderStage.HUD.register(b.order, this::draw) : null;
    }

    /** A layer named {@code id} ({@code "<mod>:<name>"}), which names its tick and its retained image in a trace. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Its id. */
    public String id() {
        return id;
    }

    /**
     * Adds a component of {@code tree} following {@code attachment}: queued, and started at the next tick. Under
     * {@link Builder#most}, past its waiting cap the oldest waiting component is released.
     */
    public CgWidgetComponent add(CgWidgetTree tree, CgAttachment attachment) {
        Objects.requireNonNull(tree, "tree");
        if (tick == null) {
            tick = world().schedule().add(CgTickGroup.PLAYS, id, dt -> tick()).whilePaused(true);
        }
        CgWidgetComponent component = new CgWidgetComponent(this, pool.obtain(tree), attachment);
        queued.add(component);
        if (queued.size() > mostWaiting) release(queued.get(0));
        return component;
    }

    /** Components started: drawn each frame their point is in view. */
    public int live() {
        return live.size();
    }

    /** Components waiting for a place. */
    public int waiting() {
        return queued.size();
    }

    /** Its pool. */
    public CgUserWidgetPool pool() {
        return pool;
    }

    /** Releases every component, queued or live. */
    public void clear() {
        while (!queued.isEmpty()) release(queued.get(queued.size() - 1));
        while (!live.isEmpty()) release(live.get(live.size() - 1));
    }

    /** Off the HUD and its world's schedule, every component released. */
    @Override
    public void close() {
        clear();
        if (drawing != null) drawing.close();
        if (tick != null) tick.close();
        tick = null;
    }

    /** {@code component} out of its list and its widget back in the pool. */
    void release(CgWidgetComponent component) {
        if (component.state == CgWidgetComponent.State.RELEASED) return;
        (component.state == CgWidgetComponent.State.QUEUED ? queued : live).remove(component);
        component.stop();
        pool.release(component.widget);
    }

    private CgWorld world() {
        return fixedWorld != null ? fixedWorld : CgWorld.client();
    }

    /** Its tick: releases what played out, then starts what waits, newest first. */
    private void tick() {
        tick(CgWorldQueries.client().levelEpoch(), CgSequencer.widgets(world()));
    }

    void tick(int levelEpoch, CgSequencer sequencer) {
        if (levelEpoch != epoch) {
            boolean first = epoch == Integer.MIN_VALUE;
            epoch = levelEpoch;
            if (!first) clear();
        }
        for (int i = live.size() - 1; i >= 0; i--) {
            if (live.get(i).finished()) release(live.get(i));
        }
        while (live.size() < mostLive && !queued.isEmpty()) {
            CgWidgetComponent next = queued.remove(queued.size() - 1);
            live.add(next);
            next.start(sequencer);
        }
    }

    private void draw(CgStageFrame frame) {
        if (live.isEmpty()) return;
        CgHostView eye = CgRenderStage.WORLD_OPAQUE.host().view();
        this.frame = frame;
        try {
            if (gather(onHud, eye.x(), eye.y(), eye.z()) == 0) return;
        } finally {
            this.frame = null;
        }
        if (painter == null) {
            painter = new CgCanvasWidgetPainter(id, CgCanvasMaterials.SCREEN).retaining(retainedWidth, retainedHeight, margin);
        }
        float scale = CgUiScale.of(kit).scale(frame.host().width(), frame.host().height());
        painter.begin(frame.canvas(), frame.recording(), scale * retainedScale);
        paint(painter, scale);
    }

    /**
     * Places every live component this frame, from its attachment through {@code projector}, and orders those in view
     * far to near from the eye ({@code ex}, {@code ey}, {@code ez}): {@code SWorldWidgetScreenLayer}'s z-order. Their
     * count.
     */
    int gather(Projector projector, double ex, double ey, double ez) {
        drawnCount = 0;
        for (int i = 0; i < live.size(); i++) {
            CgWidgetComponent c = live.get(i);
            if (c.attachment.place(point, null)) {
                c.point[0] = point[0];
                c.point[1] = point[1];
                c.point[2] = point[2];
                c.located = true;
            }
            if (!c.located || !projector.project(c.point[0], c.point[1], c.point[2], projected)) continue;
            c.screenX = projected.x;
            c.screenY = projected.y;
            double dx = c.point[0] - ex, dy = c.point[1] - ey, dz = c.point[2] - ez;
            c.depth = dx * dx + dy * dy + dz * dz;
            if (drawnCount == drawn.length) drawn = Arrays.copyOf(drawn, drawn.length * 2);
            // Insertion by depth, farthest first: a handful a frame.
            int at = drawnCount++;
            while (at > 0 && drawn[at - 1].depth < c.depth) {
                drawn[at] = drawn[at - 1];
                at--;
            }
            drawn[at] = c;
        }
        return drawnCount;
    }

    /**
     * Lays out, retains, then paints what {@link #gather} placed through {@code painter}, {@code scale} canvas pixels
     * a widget unit: each box's pivot on its point.
     */
    void paint(CgWidgetPainter painter, float scale) {
        for (int i = 0; i < drawnCount; i++) {
            CgWidgetComponent c = drawn[i];
            if (c.drawWidth > 0f && c.drawHeight > 0f) c.widget.allot(c.drawWidth, c.drawHeight);
            c.widget.layout(painter, measured);
            c.width = measured.x;
            c.height = measured.y;
            c.widget.retain(painter);
        }
        for (int i = 0; i < drawnCount; i++) {
            CgWidgetComponent c = drawn[i];
            c.widget.paint(painter, placement.scaling(scale).translate(c.screenX / scale - c.width * c.pivotX,
                    c.screenY / scale - c.height * c.pivotY, 0f));
            drawn[i] = null;
        }
        drawnCount = 0;
    }

    /** What a layer is: its kit, its retained image, its caps, where it draws. */
    public static final class Builder {
        private final String id;
        @Nullable
        private String kit;
        private float width = 512f;
        private float height = 512f;
        private float margin = 8f;
        private float retainedScale = 1f;
        private int mostLive = Integer.MAX_VALUE;
        private int mostWaiting = Integer.MAX_VALUE;
        private int order = CgRenderStage.WORLD_WIDGETS;
        @Nullable
        private CgWorld world;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        /** The kit whose {@code ui.yaml} scales it ({@link CgUiScale}); Unreal's default curve without one. */
        public Builder kit(@Nullable String namespace) {
            this.kit = namespace;
            return this;
        }

        /** Its retainer boxes' image: {@code width} x {@code height} widget units, {@code margin} round each region. */
        public Builder retaining(float width, float height, float margin) {
            this.width = width;
            this.height = height;
            this.margin = margin;
            return this;
        }

        /** The most its sequences scale a retainer box: retained at that, so it is never magnified. 1 unless set. */
        public Builder retainedScale(float scale) {
            this.retainedScale = scale;
            return this;
        }

        /**
         * At most {@code live} drawn at once and {@code waiting} queued for a place; past that the oldest waiting is
         * released. Unlimited unless set.
         */
        public Builder most(int live, int waiting) {
            if (live < 1 || waiting < 0) throw new IllegalArgumentException("most(" + live + ", " + waiting + ")");
            this.mostLive = live;
            this.mostWaiting = waiting;
            return this;
        }

        /** Its order on the HUD stage: {@link CgRenderStage#WORLD_WIDGETS} unless set, under the game's interface. */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        /** The world whose schedule and widget sequencer it runs on: the client's unless set. */
        public Builder world(CgWorld world) {
            this.world = Objects.requireNonNull(world, "world");
            return this;
        }

        /** The layer, drawing on the HUD from now. */
        public CgWorldWidgetLayer build() {
            return new CgWorldWidgetLayer(this, true);
        }

        /** The layer drawing nowhere: driven by a test through {@code tick}, {@code gather} and {@code paint}. */
        CgWorldWidgetLayer detached() {
            return new CgWorldWidgetLayer(this, false);
        }
    }
}
