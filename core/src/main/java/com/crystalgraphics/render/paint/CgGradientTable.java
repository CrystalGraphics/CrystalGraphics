package com.crystalgraphics.render.paint;

import java.util.Arrays;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.gpu.staging.CgBufferWriter;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * A recording's gradients, as a table a shader reads by index: any number of stops, one draw per gradient. A
 * recording owns one, filled as its draws name gradients and uploaded by the executor before a pass that reads it.
 *
 * <pre>{@code
 * CgGradientTable gradients = recording.gradients();
 * int sky = gradients.add(SKY);                      // the same gradient added again answers the same index
 * record.gradient(sky);                              // what a draw's record names
 * }</pre>
 *
 * <p>A shader reads it through the {@code gradient} engine buffer ({@code EngineBuffers { gradient }}):</p>
 *
 * <pre>{@code
 * vec4 c = cg_gradient(index, uv, boxSize);          // premultiplied, its spread applied
 * if (cg_gradient_dithered(index)) c = cg_gradient_dither(c, gl_FragCoord.xy);
 * }</pre>
 *
 * <ul>
 *   <li>An index belongs to its table, and a table to its recording: it means nothing in another recording, or once the
 *       table is reset.</li>
 *   <li>Filled on the recording's thread; nothing here touches the GPU until {@link #prepare}, on the render thread.</li>
 *   <li>Adding allocates nothing once the table has grown to the frame's size: a gradient is looked up by identity in
 *       open addressing, so keep gradients in constants rather than rebuilding equal ones.</li>
 *   <li>A gradient with fewer than two stops fails here, by its value.</li>
 * </ul>
 */
public final class CgGradientTable {

    /** The macro a shader's GLSL reads a word through; the {@code gradient} engine buffer declares it. */
    public static final String MACRO_NAME = "GRADIENT_DATA";
    /** Where its shaders read it. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("gradient");

    private static final CgBufferLayout FORMAT = CgBufferLayout.builder("GradientWord",
            CgBufferLayout.MemoryLayout.STD430).vec4("v").build();

    private static CgShaderBuffer buffer;
    private static CgGradientTable uploaded;
    private static int uploadedVersion = -1;
    private static long uploadedFrame = -1;

    private float[] words = new float[4 * 64];
    private int wordCount;
    private int version;

    /** Open addressing by identity: keys, their indices, and how many are live. */
    private Object[] keys = new Object[32];
    private int[] indices = new int[32];
    private int live;

    /** Each gradient in the order added, and its first word: what {@link #gradient} answers by. */
    private CgGradient[] added = new CgGradient[16];
    private int[] addedAt = new int[16];
    private int addedCount;

    /** The table's buffer: the {@code gradient} engine buffer. */
    public static CgShaderBuffer buffer() {
        if (buffer == null) {
            buffer = CgShaderBufferRegistry.get().getOrCreateInternal("CgGradientTable", FORMAT, SLOT,
                    CgBufferLifetime.FRAME);
        }
        return buffer;
    }

    /** {@code gradient}'s index in this table, adding it on first sight. */
    public int add(CgGradient gradient) {
        int slot = slot(gradient);
        if (keys[slot] == gradient) return indices[slot];
        if (!gradient.isComplete()) {
            throw new IllegalArgumentException("a gradient needs at least two stops to draw: " + gradient);
        }
        int index = wordCount;
        write(gradient);
        keys[slot] = gradient;
        indices[slot] = index;
        if (++live * 2 > keys.length) grow();
        if (addedCount == added.length) {
            added = Arrays.copyOf(added, addedCount * 2);
            addedAt = Arrays.copyOf(addedAt, addedCount * 2);
        }
        added[addedCount] = gradient;
        addedAt[addedCount++] = index;
        version++;
        return index;
    }

    /** The gradient whose index is {@code word}: what a kept paint chunk adds to another recording. */
    public CgGradient gradient(int word) {
        int at = Arrays.binarySearch(addedAt, 0, addedCount, word);
        if (at < 0) throw new IllegalArgumentException("no gradient at word " + word);
        return added[at];
    }

    /** How many words it holds. */
    public int words() {
        return wordCount;
    }

    /** Empties it for the next recording; its storage is kept. */
    public void reset() {
        wordCount = 0;
        Arrays.fill(added, 0, addedCount, null);
        addedCount = 0;
        if (live > 0) {
            Arrays.fill(keys, null);
            live = 0;
        }
        version++;
    }

    /** Makes this table a copy of {@code other}: what a frame keeps of a recording that goes on recording. */
    public void copyFrom(CgGradientTable other) {
        if (words.length < other.wordCount * 4) words = new float[other.words.length];
        System.arraycopy(other.words, 0, words, 0, other.wordCount * 4);
        wordCount = other.wordCount;
        if (added.length < other.addedCount) {
            added = new CgGradient[other.added.length];
            addedAt = new int[other.added.length];
        }
        Arrays.fill(added, 0, addedCount, null);
        System.arraycopy(other.added, 0, added, 0, other.addedCount);
        System.arraycopy(other.addedAt, 0, addedAt, 0, other.addedCount);
        addedCount = other.addedCount;
        if (keys.length < other.keys.length) {
            keys = new Object[other.keys.length];
            indices = new int[other.indices.length];
        }
        if (keys.length == other.keys.length) {
            System.arraycopy(other.keys, 0, keys, 0, keys.length);
            System.arraycopy(other.indices, 0, indices, 0, indices.length);
            live = other.live;
        } else {
            Arrays.fill(keys, null);
            live = 0;
        }
        version++;
    }

    /**
     * Uploads this table unless the buffer already holds it this frame, answering the buffer a pass states. Render
     * thread. A device binds every block a program declares, read or not, so each frame uploads afresh.
     */
    public CgShaderBuffer prepare() {
        CgShaderBuffer target = buffer();
        long frame = CgFrameRing.frame();
        if (uploaded != this || uploadedVersion != version || uploadedFrame != frame) {
            CgBufferWriter w = target.beginWrite(Math.max(1, wordCount));
            for (int i = 0; i < wordCount; i++) {
                int o = i * 4;
                w.beginRecord().vec4("v", words[o], words[o + 1], words[o + 2], words[o + 3]);
                target.endRecord();
            }
            if (wordCount == 0) {
                w.beginRecord().vec4("v", 0f, 0f, 0f, 0f);
                target.endRecord();
            }
            target.endWrite();
            uploaded = this;
            uploadedVersion = version;
            uploadedFrame = frame;
        }
        target.flush();
        return target;
    }

    /** Word {@code word}'s four floats into {@code out}: for tests and replay. */
    public void read(int word, float[] out) {
        System.arraycopy(words, word * 4, out, 0, 4);
    }

    private void write(CgGradient g) {
        int n = g.stopCount();
        ensure(2 + n + (n + 3) / 4);
        word(g.shape().ordinal(), g.spread().ordinal(), n, g.dithered() ? 1f : 0f);
        word(g.geometry(0), g.geometry(1), g.geometry(2), g.geometry(3));
        for (int k = 0; k < n; k++) {
            int argb = g.color(k);
            float a = (argb >>> 24) / 255f;
            word(((argb >>> 16) & 0xFF) / 255f * a, ((argb >>> 8) & 0xFF) / 255f * a, (argb & 0xFF) / 255f * a, a);
        }
        for (int k = 0; k < n; k += 4) {
            word(g.position(k), at(g, k + 1), at(g, k + 2), at(g, k + 3));
        }
    }

    private static float at(CgGradient g, int k) {
        return k < g.stopCount() ? g.position(k) : 1f;
    }

    private void word(float x, float y, float z, float w) {
        int o = wordCount++ * 4;
        words[o] = x;
        words[o + 1] = y;
        words[o + 2] = z;
        words[o + 3] = w;
    }

    private void ensure(int more) {
        if ((wordCount + more) * 4 > words.length) {
            words = Arrays.copyOf(words, Math.max(words.length * 2, (wordCount + more) * 4));
        }
    }

    private int slot(Object key) {
        int mask = keys.length - 1;
        int i = System.identityHashCode(key) * 0x9E3779B9 >>> 16 & mask;
        while (keys[i] != null && keys[i] != key) i = (i + 1) & mask;
        return i;
    }

    private void grow() {
        Object[] oldKeys = keys;
        int[] oldIndices = indices;
        keys = new Object[oldKeys.length * 2];
        indices = new int[oldIndices.length * 2];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != null) {
                int s = slot(oldKeys[i]);
                keys[s] = oldKeys[i];
                indices[s] = oldIndices[i];
            }
        }
    }
}
