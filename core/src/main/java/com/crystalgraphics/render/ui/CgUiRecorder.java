package com.crystalgraphics.render.ui;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgMaterialBindings;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The UI canvas's rendering policy: element records into a recording's raster passes, as chunks the frame's batcher
 * joins. One draw per run of records under one material and texture, one chunk per spatial node; the pass orders
 * {@link CgOrder#LOOKBACK}, so a later draw joins an earlier batch of its key where it overlaps nothing between
 * (Skia's bounds-checked batching, {@code CgBatcher}).
 *
 * <pre>{@code
 * recorder.begin(recording, target, CgLoad.CLEAR, CgCamera.screen(w, h));
 * recorder.element(material, texture, sampler, spatial, records, 0, 1, x0, y0, x1, y1);
 * recorder.end();
 * }</pre>
 *
 * <ul>
 *   <li>Bounds are in the spatial node's space, as the records' rects are.</li>
 *   <li>One recorder records one pass at a time, on one thread.</li>
 * </ul>
 */
public final class CgUiRecorder {

    /** Trace counters, per frame: what {@code UiHints} reads. */
    public static final String ELEMENTS = "ui-elements", DRAWS = "ui-draws", PASSES = "ui-passes";

    private final CgChunkBuilder chunk = new CgChunkBuilder();
    @Nullable private CgRecording recording;
    @Nullable private CgRasterPass pass;
    private CgGraphTexture target;
    private CgLoad load;
    private CgCamera camera;

    private boolean chunkOpen, drawOpen;
    private int spatial = -1;
    @Nullable private CgPipeline pipeline;
    @Nullable private CgMaterialBindings binding;
    @Nullable private CgMaterialInterface material;
    private long materialChanges;
    @Nullable private CgTexture texture;
    @Nullable private CgSampler sampler;

    /** Passes opened since made: a paint chunk spanning two is not kept. */
    private long passes;
    /** The chunks landed since {@link #tape} began: what a paint chunk keeps. */
    private CgDrawChunk[] tape = new CgDrawChunk[8];
    private int taped;
    private boolean taping;

    /** Records later elements into {@code target} in {@code recording}, seen through {@code camera}. */
    public void begin(CgRecording recording, CgGraphTexture target, CgLoad load, CgCamera camera) {
        end();
        this.recording = Objects.requireNonNull(recording, "recording");
        this.target = Objects.requireNonNull(target, "target");
        this.load = Objects.requireNonNull(load, "load");
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    /** Whether it records anywhere. */
    public boolean recording() {
        return recording != null;
    }

    /** The recording it records into; null between passes. */
    @Nullable
    public CgRecording recordingNow() {
        return recording;
    }

    /**
     * Appends {@code count} element records from float {@code at} of {@code records}, drawn under {@code material} with
     * {@code texture} at unit 0 (null for the material's own), positioned in spatial node {@code spatial}, inside the
     * rect given in that node's space.
     */
    public void element(CgMaterialInterface material, @Nullable CgTexture texture, @Nullable CgSampler sampler,
                        int spatial, float[] records, int at, int count, float x0, float y0, float x1, float y1) {
        if (recording == null) throw new IllegalStateException("an element with nowhere to record it: begin() first");
        if (spatial != this.spatial) {
            sealChunk();
            this.spatial = spatial;
        }
        if (!drawOpen || material != this.material || material.changes() != materialChanges || texture != this.texture
                || sampler != this.sampler) {
            capture(material, texture, sampler);
        }
        if (pipeline == null) return;
        if (!chunkOpen) {
            chunk.begin(spatial, 0, 0);
            chunkOpen = true;
        }
        if (!drawOpen) {
            chunk.draw(pipeline, binding);
            drawOpen = true;
            CgTrace.add(CgChannels.GL, DRAWS, 1);
        }
        chunk.instances(records, at, count);
        chunk.bounds(x0, y0, x1, y1);
        CgTrace.add(CgChannels.GL, ELEMENTS, count);
    }

    /** Lands what is drawn so far as its own chunk: the next element starts a new one. */
    public void land() {
        sealChunk();
    }

    /** Passes opened since this recorder was made. */
    public long passes() {
        return passes;
    }

    /** Starts keeping every chunk landed from now, forgetting what was kept. */
    public void tape() {
        land();
        Arrays.fill(tape, 0, taped, null);
        taped = 0;
        taping = true;
    }

    /** Stops keeping chunks; answers how many were kept, read by {@link #taped(int)}. */
    public int untape() {
        land();
        taping = false;
        return taped;
    }

    /** The {@code i}th chunk kept since {@link #tape}. */
    public CgDrawChunk taped(int i) {
        return tape[i];
    }

    /** Adds a sealed chunk to the open pass, opening one if none is: a paint chunk's replay. */
    public void add(CgDrawChunk sealed) {
        if (recording == null) throw new IllegalStateException("a chunk with nowhere to record it: begin() first");
        sealChunk();
        openPass();
        pass.add(sealed);
        keep(sealed);
    }

    /** Lands what is drawn and ends the open pass; later elements need {@link #begin}. */
    public void end() {
        sealChunk();
        if (pass != null) {
            pass.end();
            pass = null;
        }
        recording = null;
        spatial = -1;
        material = null;
    }

    /** Forgets what is drawn and not landed: its recording is being discarded. */
    public void abandon() {
        chunk.reset();
        chunkOpen = false;
        drawOpen = false;
        pass = null;
        recording = null;
        spatial = -1;
        material = null;
    }

    private void capture(CgMaterialInterface material, @Nullable CgTexture texture, @Nullable CgSampler sampler) {
        drawOpen = false;
        this.material = material;
        this.materialChanges = material.changes();
        this.texture = texture;
        this.sampler = sampler;
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        pipeline = proxy.pipeline(CgInstanceKind.ELEMENT);
        CgMaterialBindings drawn = proxy.bindings();
        binding = texture != null ? drawn.with(0, texture, sampler) : drawn;
    }

    private void sealChunk() {
        drawOpen = false;
        if (!chunkOpen) return;
        chunkOpen = false;
        openPass();
        CgDrawChunk sealed = chunk.end();
        pass.add(sealed);
        keep(sealed);
    }

    /** Opens a pass on the target now, if none is open: what a paint chunk starts inside. */
    public void openPass() {
        if (pass != null) return;
        pass = recording.raster("ui").color(target, load).camera(camera).order(CgOrder.LOOKBACK);
        load = CgLoad.load();
        passes++;
        CgTrace.add(CgChannels.GL, PASSES, 1);
    }

    private void keep(CgDrawChunk sealed) {
        if (!taping) return;
        if (taped == tape.length) tape = Arrays.copyOf(tape, taped * 2);
        tape[taped++] = sealed;
    }
}
