package com.crystalgraphics.render.view;

import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What a {@link CgView} keeps from one firing to the next and from one frame to the next (Unreal's
 * {@code FSceneViewState}): what the opaque firing hands the transparent one, and every temporal effect's history. A
 * renderer keeps its share in a record of its own, made at its first ask; a temporal effect keeps a {@link #history}.
 * A firing reaches it as {@code frame.view().state()}.
 *
 * <pre>{@code
 * // A renderer's share: one record a view, so a second view never reads the first's
 * static final class Kept { CgGraphTexture opaque; long drawnIn = -1; }
 * static final CgViewState.Key<Kept> KEPT = new CgViewState.Key<>("mymod.kept", Kept::new);
 *
 * Kept kept = frame.view().state().get(KEPT);
 * }</pre>
 *
 * <pre>{@code
 * // A temporal effect's history: this frame's and the last
 * static final CgViewState.HistoryKey TRAIL = new CgViewState.HistoryKey("mymod.trail");
 *
 * CgViewState state = frame.view().state();
 * CgGraphTexture trail = state.history(TRAIL, new CgTextureDesc(w, h, CgFormat.RGBA16_FLOAT), frame.recording());
 * boolean blend = !state.fresh(TRAIL) && frame.historyValid();   // last frame's, of a camera this one continues
 * resolve.sampler("_Last", 1, trail.previous()).set("_Blend", blend ? 1f : 0f);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread.</li>
 *   <li>A key is compared by identity: one constant per record.</li>
 *   <li>A view that {@linkplain #keepsHistory keeps no history} still hands one firing's work to the next of its frame;
 *       every {@link #history} of it is {@link #fresh} each frame.</li>
 * </ul>
 */
public final class CgViewState {

    /** A renderer's record in a view's state: its name, and how one is made. */
    public static final class Key<T> {
        private final String name;
        private final Supplier<? extends T> make;

        public Key(String name, Supplier<? extends T> make) {
            this.name = name;
            this.make = make;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A {@link #history}'s name. Compared by identity. */
    public static final class HistoryKey {
        private final String name;
        private final Key<History> record;

        public HistoryKey(String name) {
            this.name = name;
            this.record = new Key<>(name, History::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A history's texture and the host frame it was made in, which holds nothing of a frame before. */
    private static final class History {
        CgGraphTexture texture;
        long made;
    }

    private final Map<Key<?>, Object> records = new IdentityHashMap<>();
    private final boolean keepsHistory;
    private long frame = Long.MIN_VALUE, previousFrame = Long.MIN_VALUE;
    private int frameIndex = -1;

    CgViewState(boolean keepsHistory) {
        this.keepsHistory = keepsHistory;
    }

    /** {@code key}'s record in this view, made at the first ask. */
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        Object record = records.get(key);
        if (record == null) records.put(key, record = key.make.get());
        return (T) record;
    }

    /**
     * Whether what this view draws one frame is the next frame's history: false for a view drawn as nobody's (a
     * rehearsal's, a second firing of a stage the host named no view for), whose {@link #history}s are always fresh.
     */
    public boolean keepsHistory() {
        return keepsHistory;
    }

    /** The host frame ({@code CgFrameRing.frame()}) this view last drew in. */
    public long frame() {
        return frame;
    }

    /** Whether this view keeps history and drew the host frame before its latest: what it kept is last frame's. */
    public boolean continuous() {
        return keepsHistory && previousFrame == frame - 1;
    }

    /**
     * Frames this view has drawn, from 0: a temporal effect's sequence (a jitter's, a noise's seed), as Unreal's
     * {@code FSceneViewState::GetFrameIndex}.
     */
    public int frameIndex() {
        return frameIndex;
    }

    /**
     * {@code key}'s history in this view, of {@code desc}: its storage kept from frame to frame while the description
     * holds, made again (the old released in {@code recording}) when it changes. {@link CgGraphTexture#history}'s rules
     * hold: raster passes draw its next version, {@code previous()} samples the last.
     */
    public CgGraphTexture history(HistoryKey key, CgTextureDesc desc, CgRecording recording) {
        History h = get(key.record);
        if (h.texture != null && desc.equals(h.texture.desc())) return h.texture;
        if (h.texture != null) recording.release(h.texture);
        h.texture = CgGraphTexture.history(key.name, desc);
        h.made = frame;
        return h.texture;
    }

    /**
     * Whether {@code key}'s history holds nothing of last frame: made this frame, or this view did not draw last frame,
     * or keeps no history. A temporal effect blends by it and by its camera's continuity
     * ({@code CgStageFrame.historyValid()}).
     */
    public boolean fresh(HistoryKey key) {
        History h = get(key.record);
        return h.texture == null || h.made == frame || !continuous();
    }

    /** Every record and stamp dropped: at context teardown, when what they held is gone with the context. */
    void forget() {
        records.clear();
        frame = previousFrame = Long.MIN_VALUE;
        frameIndex = -1;
    }

    /**
     * Engine side, a stage's at each firing: a firing of this view begins in host frame {@code hostFrame}, and the first
     * of that frame advances it.
     */
    public void begin(long hostFrame) {
        if (hostFrame == frame) return;
        previousFrame = frame;
        frame = hostFrame;
        frameIndex++;
    }
}
