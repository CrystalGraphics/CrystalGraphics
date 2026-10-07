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
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.util.ArrayList;
import java.util.List;

/**
 * Text in the world: labels {@link CgWorldRenderer#text} queues for the frame, recorded into each transparent world
 * stage after its transparent draws, depth-tested against the scene and never writing depth, as Minecraft's name tags.
 * One {@link CgTextRenderer} of the world renderer's lays them out and resolves their glyphs, its raster tier chosen by
 * how tall each stands on screen, and hands its quads to the stage's recording.
 */
public final class CgWorldText {

    /** Where a label's point sits on it: the middle of the block, its top or its bottom. */
    private static final float CENTRE = 0.5f;

    private final List<Label> labels = new ArrayList<>();
    private int count;
    private final CgPassRecorder recorder = new CgPassRecorder();
    private final PoseStack pose = new PoseStack();
    private final Matrix4f projection = new Matrix4f();
    private final Quaternionf facing = new Quaternionf();
    private CgTextRenderer renderer;

    CgWorldText() {
    }

    /** The next label to fill: one of the frame's, reused frame to frame. */
    Label next() {
        if (count == labels.size()) labels.add(new Label());
        Label label = labels.get(count).reset();
        label.owner = this;
        return label;
    }

    void submitted() {
        count++;
    }

    void clear() {
        for (int i = 0; i < count; i++) labels.get(i).release();
        count = 0;
    }

    /** Records the frame's labels into {@code stage}'s target, under its view. Render thread. */
    void record(CgStageFrame stage, CgHostView view) {
        if (count == 0) return;
        int w = Math.max(1, stage.host().width()), h = Math.max(1, stage.host().height());
        projection.set(view.projection());
        if (renderer == null) {
            renderer = CgTextRenderer.createManualSized();
            renderer.context(CgTextRenderContext.world(projection, w, h));
        }
        renderer.context().updateProjection(projection, w, h);
        recorder.recordInto(stage.recording(), stage.target(), CgLoad.load(), stage.constants());
        renderer.sink(recorder);
        try {
            renderer.beginBatch();
            for (int i = 0; i < count; i++) draw(labels.get(i), view);
            renderer.endBatch();
        } finally {
            renderer.sink(null);
            recorder.stop();
        }
    }

    private void draw(Label label, CgHostView view) {
        CgTextRenderer.Draw d = renderer.draw();
        if (label.family != null) d.family(label.family).targetPx(label.px);
        else d.font(label.font);
        CgTextLayout layout = d.text(label.text).measure();
        int px = label.family != null ? label.px : label.font.getTargetPx();
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
        d = renderer.draw();
        if (label.family != null) d.family(label.family).targetPx(label.px);
        else d.font(label.font);
        d.layout(layout).at(0f, 0f).color(label.argb).pose(pose).submit();
    }

    /** Drops the frame's labels and the renderer: at context teardown, after the text registry deleted it. */
    void release() {
        clear();
        renderer = null;
    }

    /**
     * One label: a line of text at a point in the world, facing the camera unless turned. Build it from
     * {@link CgWorldRenderer#text} and submit it in the same expression.
     */
    public static final class Label {
        private CgWorldText owner;
        String text;
        CgFont font;
        CgFontFamily family;
        int px;
        double x, y, z;
        float height = 0.25f, anchorX = CENTRE, anchorY = CENTRE;
        int argb = 0xFFFFFFFF;
        Quaternionfc rotation;

        Label() {
        }

        private Label reset() {
            text = null;
            font = null;
            family = null;
            px = 0;
            x = y = z = 0.0;
            height = 0.25f;
            anchorX = anchorY = CENTRE;
            argb = 0xFFFFFFFF;
            rotation = null;
            return this;
        }

        private void release() {
            text = null;
            font = null;
            family = null;
            rotation = null;
        }

        /** Where its anchor stands, absolute, in doubles. */
        public Label at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** How tall a line of it stands, in blocks: the font's size maps to this. 0.25 unless set. */
        public Label height(float blocks) {
            this.height = blocks;
            return this;
        }

        /** Straight ARGB; opaque white unless set. */
        public Label color(int argb) {
            this.argb = argb;
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

        /** Queues it for this frame. */
        public void submit() {
            if (text == null || text.isEmpty()) return;
            owner.submitted();
        }
    }
}
