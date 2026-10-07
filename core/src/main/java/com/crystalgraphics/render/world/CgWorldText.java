package com.crystalgraphics.render.world;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgPassRecorder;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.render.context.CgTextRenderContext;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Text in the world: labels {@link CgWorldRenderer#text} queues for the frame, recorded into each transparent world
 * stage after its transparent draws, depth-tested against the scene and never writing depth, as Minecraft's name tags.
 * Each label is a queued {@link CgTextRenderer.Draw} of one renderer, drawn at every firing under that firing's
 * camera, its raster tier chosen by how tall it stands on screen; the renderer hands its quads to the stage's recording.
 */
public final class CgWorldText {

    /** Where a label's point sits on it: the middle of the block, its top or its bottom. */
    private static final float CENTRE = 0.5f;

    private final List<Label> labels = new ArrayList<>();
    private int count;
    private final Consumer<CgTextRenderer.Draw> queue = this::submitted;
    private final CgPassRecorder recorder = new CgPassRecorder();
    private final PoseStack pose = new PoseStack();
    private final Matrix4f projection = new Matrix4f();
    private final Quaternionf facing = new Quaternionf();
    private CgTextRenderer renderer;

    CgWorldText() {
    }

    /** The next label to fill: one of the frame's, reused frame to frame. */
    Label next() {
        if (renderer == null) {
            renderer = CgTextRenderer.createManualSized();
            renderer.context(CgTextRenderContext.world(projection, 1, 1));
        }
        if (count == labels.size()) labels.add(new Label(renderer.queuedDraw(queue)));
        return labels.get(count).reset();
    }

    /** A label's draw submitted: kept if it is the one {@link #next} handed out, else ignored. */
    private void submitted(CgTextRenderer.Draw draw) {
        if (count < labels.size() && labels.get(count).draw == draw) count++;
    }

    void clear() {
        for (int i = 0; i < count; i++) labels.get(i).draw.reset();
        count = 0;
    }

    /** Records the frame's labels into {@code stage}'s target, under its view. Render thread. */
    void record(CgStageFrame stage, CgHostView view) {
        if (count == 0) return;
        int w = Math.max(1, stage.host().width()), h = Math.max(1, stage.host().height());
        projection.set(view.projection());
        renderer.context().updateProjection(projection, w, h);
        recorder.recordInto(stage.recording(), stage.target(), CgLoad.load(), stage.constants());
        renderer.sink(recorder);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "world.text")) {
            renderer.beginBatch();
            for (int i = 0; i < count; i++) draw(labels.get(i), view);
            renderer.endBatch();
        } finally {
            renderer.sink(null);
            recorder.stop();
        }
    }

    private void draw(Label label, CgHostView view) {
        CgTextRenderer.Draw d = label.draw.pose(null);
        int px = d.basePx();
        if (px <= 0) return;
        CgTextLayout layout = d.measure();
        float scale = label.height / px;
        Matrix4f m = pose.last().pose();
        m.set(view.view()).translate((float) (label.x - view.x()), (float) (label.y - view.y()), (float) (label.z - view.z()));
        if (label.rotation != null) {
            m.rotate(label.rotation);
        } else {
            // Undo the view's turn, so the label lies in the eye's plane: a billboard.
            view.view().getNormalizedRotation(facing).conjugate();
            m.rotate(facing);
        }
        m.scale(scale, -scale, scale)
                .translate(-layout.totalWidth() * label.anchorX, -layout.totalHeight() * (1f - label.anchorY), 0f);
        renderer.context().updateProjectedSize(m, projection, px);
        renderer.drawQueued(d.pose(pose));
    }

    /** Drops the frame's labels and the renderer: at context teardown, after the text registry deleted it. */
    void release() {
        clear();
        labels.clear();
        renderer = null;
    }

    /**
     * One label's place in the world: a point, a height, which point of the text stands there, and a turn. How it
     * draws is its {@link CgTextRenderer.Draw}, from {@link #font}, {@link #family} or {@link #draw()}, submitted to
     * queue it. Build it from {@link CgWorldRenderer#text}.
     */
    public static final class Label {
        private final CgTextRenderer.Draw draw;
        double x, y, z;
        float height = 0.25f, anchorX = CENTRE, anchorY = CENTRE;
        Quaternionfc rotation;

        Label(CgTextRenderer.Draw draw) {
            this.draw = draw;
        }

        private Label reset() {
            draw.reset();
            x = y = z = 0.0;
            height = 0.25f;
            anchorX = anchorY = CENTRE;
            rotation = null;
            return this;
        }

        /** Where its anchor stands, absolute, in doubles. The origin unless set. */
        public Label at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** How tall a line of it stands, in blocks: its draw's {@code basePx()} maps to this. 0.25 unless set. */
        public Label height(float blocks) {
            this.height = blocks;
            return this;
        }

        /**
         * Which point of the text its position names: {@code x} 0 its left edge to 1 its right, {@code y} 0 its bottom
         * to 1 its top. The middle unless set.
         */
        public Label anchor(float x, float y) {
            this.anchorX = x;
            this.anchorY = y;
            return this;
        }

        /** Turned by {@code rotation} in the world instead of facing the camera; the text reads along +x, up +y. */
        public Label rotation(Quaternionfc rotation) {
            this.rotation = rotation;
            return this;
        }

        /** Its draw, in {@code font}: every field a {@link CgTextRenderer.Draw} has. Its {@code submit()} queues it. */
        public CgTextRenderer.Draw font(CgFont font) {
            return draw.font(font);
        }

        /** Its draw, in {@code family}, falling back across its faces; size it with {@code targetPx}. */
        public CgTextRenderer.Draw family(CgFontFamily family) {
            return draw.family(family);
        }

        /** Its draw as it stands: for a layout, whose fonts are its own. */
        public CgTextRenderer.Draw draw() {
            return draw;
        }
    }
}
