package com.crystalgraphics.api.canvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgPropertyValues;
import com.crystalgraphics.render.graph.CgRecording;

/**
 * Records canvas frames on any thread into a picture of their own, and seals each into a {@link CgCanvasFrame} a
 * {@link CgCanvasPresenter} shows: a document or a HUD drawn off the render thread. One per thing drawn this way.
 *
 * <pre>{@code
 * CgCanvasRecorder recorder = new CgCanvasRecorder();   // with the document
 *
 * CgCanvas canvas = recorder.begin(w, h);               // the document's thread; touches no GPU
 * try {
 *     paint(canvas);
 * } catch (RuntimeException failed) {
 *     recorder.abort();                                 // what it drew dropped; the next begin starts clean
 *     throw failed;
 * }
 * CgCanvasFrame frame = recorder.seal();                // ordered, batched and packed, here
 *
 * recorder.release();                                   // render thread, when the document goes
 * }</pre>
 *
 * <p>A picture kept across frames, which a recording may not make itself, is requested:</p>
 *
 * <pre>{@code
 * CgGraphTexture thumbnail = recorder.requestTexture("thumb", 192, 108, CgFormat.RGBA8_UNORM);
 * recorder.releaseTexture(thumbnail);                   // freed once what was recorded before has executed
 * }</pre>
 *
 * <ul>
 *   <li>{@code begin} and {@code seal} pair on one thread; a recorder records one frame at a time.</li>
 *   <li>The picture is made when the first frame executes, cleared at creation, and remade when the size changes.</li>
 *   <li>A requested texture is the caller's until released; {@link #release} frees every one still held.</li>
 * </ul>
 */
public final class CgCanvasRecorder {

    /** The picture's format: what a premultiplied UI composites from. */
    public static final CgFormat PICTURE_FORMAT = CgFormat.RGBA8_UNORM;

    private final CgRecording recording = new CgRecording();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgCamera camera = new CgCamera();
    private final CgCanvas canvas = new CgCanvas();
    private final List<CgGraphTexture> pendingReleases = new ArrayList<>();
    /** Values of presented frames, cleared for the next seals: two in flight need two. */
    private CgPropertyValues[] spareValues = new CgPropertyValues[2];
    private int spareCount;
    private CgGraphTexture picture;
    private long frameId;
    private boolean active;
    private int width, height;

    /**
     * Starts a frame of {@code width x height} pixels, answering the canvas to paint it with, in pixels from the
     * top-left. Touches no GPU.
     */
    public CgCanvas begin(int width, int height) {
        return begin(width, height, 1f);
    }

    /** As {@link #begin(int, int)}, the canvas's units {@code scale} pixels each: a DPI or GUI scale. */
    public CgCanvas begin(int width, int height, float scale) {
        if (active) throw new IllegalStateException("begin() without a seal() of the frame before");
        frameId++;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        for (CgGraphTexture released : pendingReleases) recording.release(released);
        pendingReleases.clear();
        if (picture == null || picture.getWidth() != this.width || picture.getHeight() != this.height) {
            if (picture != null) recording.release(picture);
            picture = requestTexture("canvas_picture", this.width, this.height, PICTURE_FORMAT);
        }
        canvas.begin(recording, picture, CgLoad.CLEAR, camera.screenLens(this.width, this.height), scale);
        active = true;
        return canvas;
    }

    /** Ends the frame {@link #begin} started and builds it; the recorder may begin its next at once. Touches no GPU. */
    public CgCanvasFrame seal() {
        if (!active) throw new IllegalStateException("seal() without begin()");
        canvas.end();
        try {
            CgPropertyValues values = takeValues();
            graph.add(recording.seal(), values);
            CgFrame frame = builder.build(graph);
            return new CgCanvasFrame(frame, this, picture, values, frameId, width, height);
        } finally {
            graph.clear();
            recording.reset();
            active = false;
        }
    }

    /** A presented frame's buffers and values back, for the next seal. Any thread. */
    void recycle(CgFrame frame, CgPropertyValues values) {
        builder.recycle(frame);
        values.clear();
        synchronized (this) {
            if (spareCount == spareValues.length) spareValues = Arrays.copyOf(spareValues, spareCount * 2);
            spareValues[spareCount++] = values;
        }
    }

    private synchronized CgPropertyValues takeValues() {
        if (spareCount == 0) return new CgPropertyValues();
        CgPropertyValues values = spareValues[--spareCount];
        spareValues[spareCount] = null;
        return values;
    }

    /** Drops the frame being recorded, so the next {@link #begin} starts clean. A no-op with none open. */
    public void abort() {
        if (!active) return;
        canvas.abandon();
        recording.reset();
        active = false;
    }

    /** The recording the open frame records into: for passes of the caller's own between canvas items. */
    public CgRecording recording() {
        if (!active) throw new IllegalStateException("no frame is open");
        return recording;
    }

    /** Frames begun so far: what keys a frame's node ids. */
    public long frameId() {
        return frameId;
    }

    /** A texture made when a frame executes and kept until {@link #releaseTexture}. Any thread. */
    public CgGraphTexture requestTexture(String name, int width, int height, CgFormat format) {
        return CgGraphTexture.requested(name, CgTextureDesc.of(Math.max(1, width), Math.max(1, height), format));
    }

    /** Frees a requested texture once what was recorded before has executed; between frames, at the next begin. */
    public void releaseTexture(CgGraphTexture requested) {
        if (active) recording.release(requested);
        else pendingReleases.add(requested);
    }

    /**
     * Frees the picture and every texture still waiting to be released: the recorder is done, or the graphics context
     * is going. Render thread; the next {@link #begin} makes the picture again.
     */
    public void release() {
        abort();
        for (CgGraphTexture released : pendingReleases) CgExecutor.freeStorage(released);
        pendingReleases.clear();
        if (picture != null) CgExecutor.freeStorage(picture);
        picture = null;
    }
}
