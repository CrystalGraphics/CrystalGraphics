package com.crystalgraphics.vfx.sequence;

import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxTrace;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every clip particles play, baked into one buffer of vec4s the Step and the node renderer read ({@code FX_CLIPS}):
 * per baked node, {@link #ROWS} rows of {@code frames + 1} vec4s, a vec4 a frame. Rows 0 to 2 are the node's matrix
 * from the clip's root (a 3x4, row by row), row 3 its own opacity and the material slots the clip
 * gives it ({@link CgVfxNodeClip#keys}), rows 4 to 7 its sixteen material scalars. A clip is baked once, the first time
 * it is asked, and kept for the run: emitters' rows name it by place. Any thread bakes; the render thread uploads. The
 * buffer is made, or grown, by the bake, so {@link #buffer()} holds a clip's rows from its bake on, filled by the next
 * {@link #upload} (the pools', before anything draws).
 *
 * <pre>{@code
 * CgVfxClipAtlas.Section s = CgVfxClipAtlas.of(clip);   // where its nodes sit
 * int first = s.base(node);                             // node's first vec4, -1 for a node it did not bake
 * CgVfxClipAtlas.upload(recording);                     // what was baked since the last upload
 * CgVfxClipAtlas.bind(dispatch);                        // each kernel or draw reading clips
 * }</pre>
 *
 * <ul>
 *   <li>Only nodes with parts and nodes a burst or stream starts at are baked: an event placed at another node is
 *       refused where the emitter is built.</li>
 *   <li>A row samples between its two frames, or takes the earlier where a {@code CONSTANT} channel feeds it
 *       ({@link Section#stepMask}, a bit a row). A matrix is lerped, so a node turning more than about 30 degrees a
 *       frame shrinks between frames, as AnimToTexture's does.</li>
 * </ul>
 */
public final class CgVfxClipAtlas {

    /** vec4 rows a baked node takes. */
    public static final int ROWS = 8;
    /** The row its opacity and its keyed material slots sit in; its scalars follow, four a row. */
    public static final int OPACITY_ROW = 3, SCALAR_ROW = 4;

    /**
     * A clip's place in the buffer: each node's first vec4 ({@code -1} where unbaked), its rows' length, each node's
     * step mask, and how far its drawn nodes reach from the particle.
     */
    public static final class Section {
        private final int[] base, stepMask;
        private final int rowLength;
        private final float distance, stretch;

        Section(int[] base, int[] stepMask, int rowLength, float distance, float stretch) {
            this.base = base;
            this.stepMask = stepMask;
            this.rowLength = rowLength;
            this.distance = distance;
            this.stretch = stretch;
        }

        /** {@code node}'s first vec4, -1 where the clip did not bake it. */
        public int base(int node) {
            return base[node];
        }

        /** vec4s in each of a node's rows: the clip's frames plus one. */
        public int rowLength() {
            return rowLength;
        }

        /** Which of {@code node}'s {@link #ROWS} rows step rather than interpolate, a bit a row. */
        public int stepMask(int node) {
            return stepMask[node];
        }

        /** The farthest a drawn node's part of radius {@code radius} reaches from its particle, in clip units. */
        public float reach(float radius) {
            return distance + stretch * radius;
        }
    }

    private static final Map<CgVfxNodeClip, Section> SECTIONS = new IdentityHashMap<>();
    private static int[] words = new int[0];
    private static int end, uploaded;
    private static CgGraphBuffer buffer;
    /** Buffers a bake outgrew, released at the next upload. */
    private static final List<CgGraphBuffer> RETIRED = new ArrayList<>();
    private static ByteBuffer staging = ByteBuffer.allocate(0);

    private CgVfxClipAtlas() {
    }

    /** {@code clip}'s section, baked the first time it is asked. */
    public static synchronized Section of(CgVfxNodeClip clip) {
        Section section = SECTIONS.get(clip);
        if (section == null) SECTIONS.put(clip, section = bake(clip));
        return section;
    }

    /** Whether {@code clip} bakes {@code node}: it has parts, or a burst or stream starts there. */
    public static boolean bakes(CgVfxNodeClip clip, int node) {
        CgVfxPrefab prefab = clip.prefab();
        if (prefab.firstPart(node + 1) > prefab.firstPart(node)) return true;
        for (CgVfxNodeClip.Spawn s : clip.spawns()) if (s.node() == node) return true;
        return false;
    }

    /** Uploads what was baked since the last upload into {@link #buffer()}. Render thread. */
    public static synchronized void upload(CgRecording recording) {
        for (int i = 0; i < RETIRED.size(); i++) recording.release(RETIRED.get(i));
        RETIRED.clear();
        fit();
        int from = uploaded;
        if (from == end) return;
        int n = end - from;
        if (staging.capacity() < n * 4) staging = ByteBuffer.allocate(n * 4).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < n; i++) staging.putInt(i * 4, words[from + i]);
        staging.limit(n * 4);
        recording.update(buffer, from * 4L, staging);
        uploaded = end;
    }

    /** Binds the buffer as {@code FX_CLIPS}; {@link #upload} first, this frame. */
    public static synchronized void bind(CgDispatch d) {
        d.bind("FX_CLIPS", buffer);
    }

    /** The buffer, for a material's {@code FX_CLIPS}: every clip baked so far, from the next {@link #upload}. */
    public static synchronized CgGraphBuffer buffer() {
        fit();
        return buffer;
    }

    /** A dying context took the buffer: everything is uploaded again into the next one. */
    public static synchronized void forget() {
        buffer = null;
        RETIRED.clear();
        uploaded = 0;
    }

    /** A buffer holding every baked word, a new one (uploaded from the start) where the last is too small. */
    private static void fit() {
        long bytes = Math.max(256L, end * 4L);
        if (buffer != null && buffer.size() >= bytes) return;
        if (buffer != null) RETIRED.add(buffer);
        buffer = CgGraphBuffer.persistent("vfx.clips", CgBufferDesc.of(Long.highestOneBit(bytes - 1) << 1,
                CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        uploaded = 0;
    }

    /** Lane {@code lane} of vec4 {@code vec4}, as baked. */
    static synchronized float read(int vec4, int lane) {
        return Float.intBitsToFloat(words[vec4 * 4 + lane]);
    }

    private static Section bake(CgVfxNodeClip clip) {
        CgVfxPrefab prefab = clip.prefab();
        int nodes = prefab.nodes(), frames = clip.frames(), rowLength = frames + 1;
        int[] base = new int[nodes], stepMask = new int[nodes];
        int baked = 0;
        for (int i = 0; i < nodes; i++) {
            base[i] = bakes(clip, i) ? end / 4 + baked++ * ROWS * rowLength : -1;
            stepMask[i] = stepMask(clip, i, stepMask);
        }
        int first = end;
        CgVfxTrace.count(BAKES, 1);
        grow(end + baked * ROWS * rowLength * 4);
        end += baked * ROWS * rowLength * 4;

        Matrix4f[] world = new Matrix4f[nodes];
        for (int i = 0; i < nodes; i++) world[i] = new Matrix4f();
        float[] rest = new float[10];
        Matrix4f local = new Matrix4f();
        Vector3f euler = new Vector3f(), column = new Vector3f();
        Quaternionf turn = new Quaternionf();
        float distance = 0f, stretch = 0f;
        for (int f = 0; f < rowLength; f++) {
            for (int i = 0; i < nodes; i++) {
                clip.local(i, f, local, rest, euler, turn);
                int p = prefab.parent(i);
                if (p < 0) world[i].set(local);
                else world[p].mul(local, world[i]);
                if (base[i] < 0) continue;
                Matrix4f m = world[i];
                int at = base[i] * 4 + f * 4;
                put(at, m.m00(), m.m10(), m.m20(), m.m30());
                put(at + rowLength * 4, m.m01(), m.m11(), m.m21(), m.m31());
                put(at + rowLength * 8, m.m02(), m.m12(), m.m22(), m.m32());
                put(at + rowLength * 4 * OPACITY_ROW, clip.opacity(i, f), clip.keys(i), 0f, 0f);
                for (int r = 0; r < 4; r++) {
                    int slot = r * 4;
                    put(at + rowLength * 4 * (SCALAR_ROW + r), clip.material(i, slot, f), clip.material(i, slot + 1, f),
                            clip.material(i, slot + 2, f), clip.material(i, slot + 3, f));
                }
                if (prefab.firstPart(i + 1) > prefab.firstPart(i)) {
                    distance = Math.max(distance, m.getTranslation(column).length());
                    stretch = Math.max(stretch, Math.max(m.getColumn(0, column).length(),
                            Math.max(m.getColumn(1, column).length(), m.getColumn(2, column).length())));
                }
            }
        }
        assert end == first + baked * ROWS * rowLength * 4;
        fit();
        return new Section(base, stepMask, rowLength, distance, stretch);
    }

    /**
     * {@code node}'s rows that step: its matrix where a transform channel of it or an ancestor is constant, its opacity
     * where an opacity is, a scalar row where one of its four is. Parents' masks are already in {@code masks}.
     */
    private static int stepMask(CgVfxNodeClip clip, int node, int[] masks) {
        int mask = 0;
        int p = clip.prefab().parent(node);
        if (p >= 0) mask = masks[p] & 0b111;   // a parent's matrix steps its children's; its opacity is its own
        for (CgVfxNodeClip.Channel c : CgVfxNodeClip.Channel.values()) {
            if (!clip.stepped(node, c.ordinal())) continue;
            mask |= c == CgVfxNodeClip.Channel.OPACITY ? 1 << OPACITY_ROW : 0b111;
        }
        for (int slot = 0; slot < CgVfxNodeClip.MATERIAL_SLOTS; slot++) {
            if (clip.stepped(node, CgVfxNodeClip.Channel.material(slot))) mask |= 1 << (SCALAR_ROW + slot / 4);
        }
        return mask;
    }

    /** Counts a clip baked: a move playing finds its clips baked by its preparing, so a nonzero count is a hitch named. */
    private static final int BAKES = CgTrace.name("vfx.clips.bakes");

    private static void grow(int need) {
        if (words.length < need) words = Arrays.copyOf(words, Math.max(need, words.length * 2));
    }

    private static void put(int at, float x, float y, float z, float w) {
        words[at] = Float.floatToRawIntBits(x);
        words[at + 1] = Float.floatToRawIntBits(y);
        words[at + 2] = Float.floatToRawIntBits(z);
        words[at + 3] = Float.floatToRawIntBits(w);
    }
}
