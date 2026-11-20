package com.crystalgraphics.api.canvas;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgPass;
import javax.annotation.Nullable;

import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * Shows sealed {@link CgCanvasFrame}s at a GUI stage: executes each at its place in the firing and composites its
 * picture onto the stage's target. Keeps the last, so a host frame with no new one shows it again, moved or as it was.
 *
 * <pre>{@code
 * CgCanvasPresenter presenter = new CgCanvasPresenter();
 * CgRenderStage.SCREEN.register(CgRenderStage.SCREEN_WIDGETS, stage -> {
 *     CgCanvasFrame next = frames.poll();
 *     if (next != null) presenter.present(stage, next);     // executed, composited, kept
 *     else if (moved) presenter.redraw(stage);              // the kept frame under its values now: nothing recorded
 *     else presenter.presentAgain(stage);                   // the kept picture, executing nothing
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, inside a renderer's turn at a GUI stage; the frame's passes are timed as {@code gpu:ui}.</li>
 *   <li>{@link #redraw} and {@link #presentAgain} draw nothing once the stage's size differs from the kept frame's.</li>
 *   <li>{@link #release} hands the kept frame's buffers back: with its recorder.</li>
 * </ul>
 */
public final class CgCanvasPresenter {

    private static final int GPU_UI = CgGpuTrace.name("ui");

    @Nullable private CgCanvasFrame shown;
    private boolean composites = true;
    @Nullable private Runnable executed;

    /**
     * Whether a frame's picture is drawn onto the stage's target. Off, the frame still executes in place and its
     * textures hold what it drew, but nothing of it lands on the screen: a document another draws.
     */
    public CgCanvasPresenter composites(boolean composites) {
        this.composites = composites;
        return this;
    }

    /** Runs on the render thread once each frame it presents or redraws has executed: a trace's frame end. */
    public CgCanvasPresenter onExecuted(@Nullable Runnable executed) {
        this.executed = executed;
        return this;
    }

    /**
     * Executes {@code frame} here in {@code stage}'s firing, composites it, and keeps it in place of the last. When the
     * trace's frame images are due, its picture is photographed for them.
     */
    public void present(CgStageFrame stage, CgCanvasFrame frame) {
        CgCanvasFrame last = shown;
        shown = frame;
        // The last executed in an earlier firing: the buffers it holds are free
        if (last != null) last.recycle();
        CgPass.Include step = stage.include(frame.frame).timed(GPU_UI);
        if (executed != null) step.then(executed);
        photograph(stage, frame.picture());
        composite(stage, frame);
    }

    /** The trace's photograph of {@code picture} ({@link CgFrameImages}), when its frame is due; it lands frames later. */
    private static void photograph(CgStageFrame stage, CgGraphTexture picture) {
        long index = CgTrace.currentFrameIndex();
        if (!CgFrameImages.isDue(index)) return;
        CgPhotograph.take(stage.recording(), picture, picture.getWidth(), picture.getHeight(), CgFrameImages.width(),
                (w, h, rgb) -> CgFrameImages.put(index, w, h, rgb));
    }

    /**
     * Executes the kept frame again under its {@link CgCanvasFrame#values()} as they stand, and composites it: a scroll,
     * move or fade with nothing recorded or built. What the recording decided in pixels stays: what was culled, so a
     * scroll past its {@link CgCullRect} wants a new frame.
     *
     * @return whether it drew: false with nothing kept, or at another size than the kept frame's
     */
    public boolean redraw(CgStageFrame stage) {
        CgCanvasFrame frame = shown;
        if (!fits(stage, frame)) return false;
        // Every pass again: the frame's picture is a requested texture, which keeping requested ones would skip
        CgPass.Include step = stage.includeAgain(frame.frame, false).timed(GPU_UI);
        if (executed != null) step.then(executed);
        composite(stage, frame);
        return true;
    }

    /** Composites the kept frame's picture again, executing nothing. False with nothing kept or at another size. */
    public boolean presentAgain(CgStageFrame stage) {
        CgCanvasFrame frame = shown;
        if (!fits(stage, frame)) return false;
        composite(stage, frame);
        return true;
    }

    /** The frame last presented; null before the first. Render thread. */
    @Nullable
    public CgCanvasFrame presented() {
        return shown;
    }

    /** Hands the kept frame's buffers back and forgets it. Render thread. */
    public void release() {
        CgCanvasFrame last = shown;
        shown = null;
        if (last != null) last.recycle();
    }

    private static boolean fits(CgStageFrame stage, @Nullable CgCanvasFrame frame) {
        return frame != null && frame.width() == stage.width() && frame.height() == stage.height();
    }

    /** The picture over the stage's target in window pixels, premultiplied; row 0 of a target is its bottom. */
    private void composite(CgStageFrame stage, CgCanvasFrame frame) {
        if (!composites) return;
        stage.canvas().box(0, 0, frame.width(), frame.height()).fill(frame.picture()).premultiplied()
                .region(0f, 1f, 1f, 0f).draw();
    }
}
