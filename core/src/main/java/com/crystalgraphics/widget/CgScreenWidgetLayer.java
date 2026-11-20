package com.crystalgraphics.widget;

import com.crystalgraphics.api.canvas.CgCanvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;


import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * Widgets on the screen, each filling it in its kit's UI units, higher z-orders over lower: UMG's viewport, where
 * {@code UUserWidget::AddToViewport} puts a widget in a full-screen canvas at its z-order ({@code UserWidget.cpp:1039-1095}).
 * A widget's slots place it on the screen, its animations ({@link CgUserWidget#playAnimation}) play on the layer's world.
 *
 * <pre>{@code
 * // once, client side: a HUD of your own
 * static final CgScreenWidgetLayer HUD = CgScreenWidgetLayer.builder("mymod:hud").kit("mymod").build();
 *
 * // made once, shown and hidden as often as needed
 * CgUserWidget counter = COUNTER.loaded().newInstance();
 * HUD.add(counter, 0);                       // AddToViewport: drawn from this frame
 * counter.text("Number", "12");
 * counter.playAnimation(POP.loaded());
 * HUD.remove(counter);                       // RemoveFromParent
 * }</pre>
 *
 * <pre>{@code
 * // a widget that takes itself off once its last animation ends
 * banner.onAnimationFinished(OUT.loaded(), () -> HUD.remove(banner));
 * banner.playAnimation(OUT.loaded());
 * }</pre>
 *
 * <ul>
 *   <li>Every widget is laid out at the whole screen ({@code allot}), so its root canvas's anchors are fractions of the
 *       screen and hold at any window size.</li>
 *   <li>Equal z-orders draw in the order added, the latest on top.</li>
 *   <li>A level change takes every widget off, as Unreal's {@code OnLevelRemovedFromWorld} does
 *       ({@code UserWidget.cpp:1097-1105}): add it again in the new one.</li>
 *   <li>Its trees' UI materials and styles' faces are built as they load ({@link CgWidgetPreparer}): add instances of a
 *       loaded tree only.</li>
 *   <li>Client, the render thread; it draws on {@link CgRenderStage#HUD} at {@link Builder#order}.</li>
 * </ul>
 */
public final class CgScreenWidgetLayer implements AutoCloseable {

    private static final CgTraceChannel TRACE = CgTrace.channel("crystalgraphics.widget");

    private final String id;
    @Nullable
    private final String kit;
    @Nullable
    private final CgWorld fixedWorld;
    @Nullable
    private final CgRenderStage.Registration drawing;
    /** Bottom first: ascending z-order, equal ones in the order added. */
    private final List<CgUserWidget> widgets = new ArrayList<>();
    private final List<Integer> orders = new ArrayList<>();
    private int epoch = Integer.MIN_VALUE;

    private CgScreenWidgetLayer(Builder b, boolean onHud) {
        this.id = b.id;
        this.kit = b.kit;
        this.fixedWorld = b.world;
        this.drawing = onHud ? CgRenderStage.HUD.register(b.order, this::draw) : null;
    }

    /** A layer named {@code id} ({@code "<mod>:<name>"}). */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Its id. */
    public String id() {
        return id;
    }

    /**
     * Puts {@code widget} on the screen at {@code zOrder}, over every lower one and every equal one already there: UMG's
     * {@code AddToViewport}. Already on it, it moves to {@code zOrder}.
     */
    public CgScreenWidgetLayer add(CgUserWidget widget, int zOrder) {
        Objects.requireNonNull(widget, "widget");
        remove(widget);
        widget.world(world());
        int at = widgets.size();
        while (at > 0 && orders.get(at - 1) > zOrder) at--;
        widgets.add(at, widget);
        orders.add(at, zOrder);
        return this;
    }

    /** Takes {@code widget} off the screen, its animations left as they are: UMG's {@code RemoveFromParent}. */
    public boolean remove(CgUserWidget widget) {
        int at = widgets.indexOf(widget);
        if (at < 0) return false;
        widgets.remove(at);
        orders.remove(at);
        return true;
    }

    /** Whether {@code widget} is on the screen: UMG's {@code IsInViewport}. */
    public boolean contains(CgUserWidget widget) {
        return widgets.contains(widget);
    }

    /** How many widgets are on it. */
    public int size() {
        return widgets.size();
    }

    /** Takes every widget off. */
    public void clear() {
        widgets.clear();
        orders.clear();
    }

    /** Off the HUD, every widget taken off. */
    @Override
    public void close() {
        clear();
        if (drawing != null) drawing.close();
    }

    private CgWorld world() {
        return fixedWorld != null ? fixedWorld : CgWorld.client();
    }

    /** A level change takes every widget off; {@code levelEpoch} the client level's. */
    void level(int levelEpoch) {
        if (levelEpoch == epoch) return;
        boolean first = epoch == Integer.MIN_VALUE;
        epoch = levelEpoch;
        if (!first) clear();
    }

    private void draw(CgStageFrame frame) {
        level(CgWorldQueries.client().levelEpoch());
        if (widgets.isEmpty()) return;
        float scale = CgUiScale.of(kit).scale(frame.width(), frame.height());
        paint(frame.canvas(), frame.width() / scale, frame.height() / scale, scale);
    }

    /**
     * Lays every widget out at {@code width} x {@code height} widget units and paints them bottom first onto
     * {@code canvas}, {@code scale} canvas pixels a widget unit.
     */
    void paint(CgCanvas canvas, float width, float height, float scale) {
        try (CgTrace.Zone ignored = CgTrace.zone(TRACE, "widget.screen.paint")) {
            canvas.save().scale(scale, scale);
            for (int i = 0; i < widgets.size(); i++) {
                CgUserWidget w = widgets.get(i);
                w.allot(width, height);
                w.paint(canvas);
            }
            canvas.restore();
        }
    }

    /** What a layer is: its kit and where it draws. */
    public static final class Builder {
        private final String id;
        @Nullable
        private String kit;
        private int order = CgRenderStage.SCREEN_WIDGETS;
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

        /** Its order on the HUD stage: {@link CgRenderStage#SCREEN_WIDGETS} unless set. */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        /** The world its widgets' animations play on: the client's unless set. */
        public Builder world(CgWorld world) {
            this.world = Objects.requireNonNull(world, "world");
            return this;
        }

        /** The layer, drawing on the HUD from now. */
        public CgScreenWidgetLayer build() {
            return new CgScreenWidgetLayer(this, true);
        }

        /** The layer drawing nowhere: driven by a test through {@code level} and {@code paint}. */
        CgScreenWidgetLayer detached() {
            return new CgScreenWidgetLayer(this, false);
        }
    }
}
