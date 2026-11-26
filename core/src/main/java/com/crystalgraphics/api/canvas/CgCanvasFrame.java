package com.crystalgraphics.api.canvas;

import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgPropertyValues;
import javax.annotation.Nullable;

/**
 * One sealed canvas frame: the passes that paint it into its picture, ordered, batched and packed, referring to nothing
 * of the recorder that made it. Built on the thread that recorded it, presented on the render thread by a
 * {@link CgCanvasPresenter}.
 *
 * <pre>{@code
 * CgCanvas canvas = recorder.begin(w, h);          // any thread; no GPU
 * paint(canvas);
 * CgCanvasFrame frame = recorder.seal();           // here: ordered, batched, packed
 * presenter.present(stage, frame);                 // render thread, at a SCREEN or HUD stage
 * }</pre>
 *
 * <p>Its movable nodes ({@link CgCanvas#movable}) are drawn with its {@link #values()}: a compositor moves or fades
 * what it recorded by writing them and {@linkplain CgCanvasPresenter#redraw redrawing}, recording nothing.</p>
 *
 * <pre>{@code
 * frame.values().translate(scroller, 0f, -scrolled);
 * presenter.redraw(stage);
 * }</pre>
 *
 * <ul>
 *   <li>Present each frame once: its buffers go back to the recorder when the next replaces it.</li>
 *   <li>Two frames of one recorder may be in flight; a recorder whose frames are never presented builds new buffers.</li>
 * </ul>
 */
public final class CgCanvasFrame {

    final CgFrame frame;
    private final CgCanvasRecorder recorder;
    @Nullable
    private final CgGraphTexture picture;
    private final CgPropertyValues values;
    private final long frameId;
    private final int width, height;

    CgCanvasFrame(CgFrame frame, CgCanvasRecorder recorder, @Nullable CgGraphTexture picture, CgPropertyValues values,
                  long frameId, int width, int height) {
        this.frame = frame;
        this.recorder = recorder;
        this.picture = picture;
        this.values = values;
        this.frameId = frameId;
        this.width = width;
        this.height = height;
    }

    /** What its movable nodes are drawn with: written by a compositor, read when the frame executes. Render thread. */
    public CgPropertyValues values() {
        return values;
    }

    /** The recorder's count of frames when this one began: what node ids and kept chunks are keyed by. */
    public long frameId() {
        return frameId;
    }

    /**
     * The picture it shows, premultiplied, its row 0 at the bottom: what a presenter composites. Its own, or the last
     * complete one while it is held ({@link CgCanvasRecorder#seal(boolean)}); null while held with none shown yet.
     */
    @Nullable
    public CgGraphTexture picture() {
        return picture;
    }

    /** Whether it shows a picture: false while held with none shown yet. */
    public boolean shows() {
        return picture != null;
    }

    /** The batches it draws: what a frame costs in draw calls. */
    public int batches() {
        return frame.batches();
    }

    /** Hands its buffers and values back to its recorder once it has executed for the last time. */
    void recycle() {
        recorder.recycle(frame, values);
    }

    /** Its size in pixels. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
