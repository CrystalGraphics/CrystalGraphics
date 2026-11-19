package com.crystalgraphics.api.canvas;

import javax.annotation.Nullable;

import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * Shows sealed {@link CgCanvasFrame}s at a GUI stage: executes each at its place in the firing and composites its
 * picture onto the stage's target. Keeps the last, so a host frame with no new one shows it again, moved or as it was.
 *
 * <pre>{@code
 * CgCanvasPresenter presenter = new CgCanvasPresenter();
 * CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> {
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

    /**
     * Whether a frame's picture is drawn onto the stage's target. Off, the frame still executes in place and its
     * textures hold what it drew, but nothing of it lands on the screen: a document another draws.
     */
    public CgCanvasPresenter composites(boolean composites) {
        this.composites = composites;
        return this;
    }

    /** Executes {@code frame} here in {@code stage}'s firing, composites it, and keeps it in place of the last. */
    public void present(CgStageFrame stage, CgCanvasFrame frame) {
        CgCanvasFrame last = shown;
        shown = frame;
        // The last executed in an earlier firing: the buffers it holds are free
        if (last != null) last.builder.recycle(last.frame);
        stage.include(frame.frame).timed(GPU_UI);
        composite(stage, frame);
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
        stage.includeAgain(frame.frame, false).timed(GPU_UI);
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
        if (last != null) last.builder.recycle(last.frame);
    }

    private static boolean fits(CgStageFrame stage, @Nullable CgCanvasFrame frame) {
        return frame != null && frame.width() == stage.width() && frame.height() == stage.height();
    }

    /** The picture over the stage's target in window pixels, premultiplied; row 0 of a target is its bottom. */
    private void composite(CgStageFrame stage, CgCanvasFrame frame) {
        if (!composites) return;
        CgCanvas.on(stage).box(0, 0, frame.width(), frame.height()).fill(frame.picture()).premultiplied()
                .region(0f, 1f, 1f, 0f).draw();
    }
}
