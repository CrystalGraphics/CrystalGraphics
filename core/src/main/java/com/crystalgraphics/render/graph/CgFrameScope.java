package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.resource.CgFrameRing;

import java.util.IdentityHashMap;
import java.util.Map;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

/**
 * What lives for a host frame across its firings: a blackboard a firing publishes to and a later firing of the same
 * frame reads, emptied when the frame changes (Unreal RDG's extraction to the view's frame, ST1). Each firing records,
 * builds and executes a graph of its own, four or more a frame; what one makes for the others goes here.
 *
 * <pre>{@code
 * static final CgFrameScope.Key<CgGraphTexture> PYRAMID = new CgFrameScope.Key<>("stage.pyramid");
 *
 * CgGraphTexture pyramid = CgFrameScope.get(PYRAMID);   // null at the frame's first ask
 * if (pyramid == null) {
 *     pyramid = CgFrameScope.texture(PYRAMID, desc, recording);   // storage kept from frame to frame
 *     ... record the passes writing it ...
 *     CgFrameScope.put(PYRAMID, pyramid);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A texture from {@link #texture} keeps its storage across frames, as a requested texture does; what a frame
 *       wrote is a later firing's to read, in execution order.</li>
 *   <li>Render thread: recordings of the frame's firings are made there.</li>
 * </ul>
 */
public final class CgFrameScope {

    /** A blackboard entry's name and type. Compared by identity: one constant per entry. */
    public static final class Key<T> {
        private final String name;

        public Key(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final Map<Key<?>, Object> BOARD = new IdentityHashMap<>();
    private static final Map<Key<?>, CgGraphTexture> TEXTURES = new IdentityHashMap<>();

    private CgFrameScope() {}

    /** What {@code key} holds this frame, or null. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Key<T> key) {
        roll();
        return (T) BOARD.get(key);
    }

    /** Publishes {@code value} under {@code key} for the rest of this frame. */
    public static <T> void put(Key<T> key, T value) {
        roll();
        BOARD.put(key, value);
    }

    /**
     * A texture of {@code desc} living across this frame's firings, the same object frame after frame while its
     * description holds: its storage is kept, as a requested texture's is. One of another description, after a resize,
     * is released in {@code recording}.
     */
    public static CgGraphTexture texture(Key<CgGraphTexture> key, CgTextureDesc desc, CgRecording recording) {
        CgGraphTexture t = TEXTURES.get(key);
        if (t != null && desc.equals(t.desc())) return t;
        if (t != null) recording.release(t);
        TEXTURES.put(key, t = CgGraphTexture.requested(key.toString(), desc));
        return t;
    }

    private static void roll() {
        long now = CgFrameRing.frame();
        CgGraphDevice device = CgGraphDevice.get();
        if (now == device.scopeFrame) return;
        device.scopeFrame = now;
        BOARD.clear();
    }
}
