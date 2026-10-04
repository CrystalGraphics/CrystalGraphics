package com.crystalgraphics.compute;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.crystalgraphics.platform.gl.state.CgGlSlot.FBO;

/**
 * The reads of a check that runs GPU work and compares what it wrote, made so the check never waits on the GPU: it
 * runs once with every read requested through {@link CgReadback} and answered with zeros, then again once all have
 * arrived, each read answered with what was read for it the first time. The compute self-test and the ops check read
 * through it, which is what lets them run on a device whose host submits, where a wait would deadlock.
 *
 * <pre>{@code
 * CgReplayedReads reads = CgReplayedReads.requesting();
 * check(reads);                                      // this frame: the work, every read requested
 * // each later frame, after CgReadback.poll():
 * if (!reads.waiting()) report(check(reads.replay()));   // the work again, its reads answered
 *
 * report(check(CgReplayedReads.immediate()));        // where waiting is fine: a harness scene
 * }</pre>
 *
 * <ul>
 *   <li>A check asks for the same reads in the same order both times; a replay asked out of step throws.</li>
 *   <li>Whatever the first run decides from a read, it decides on zeros: only the replay's verdict counts.</li>
 *   <li>Render thread, like {@link CgReadback}.</li>
 * </ul>
 */
public final class CgReplayedReads {

    private enum Mode { IMMEDIATE, REQUESTING, REPLAYING }

    private final Mode mode;
    private final List<String> keys;
    private final List<byte[]> arrived;
    private int next, outstanding;
    private String failure;

    private CgReplayedReads(Mode mode, List<String> keys, List<byte[]> arrived) {
        this.mode = mode;
        this.keys = keys;
        this.arrived = arrived;
    }

    /** Reads at once, waiting on the GPU. */
    public static CgReplayedReads immediate() {
        return new CgReplayedReads(Mode.IMMEDIATE, null, null);
    }

    /** Requests every read and answers zeros; {@link #replay()} answers what arrived. */
    public static CgReplayedReads requesting() {
        return new CgReplayedReads(Mode.REQUESTING, new ArrayList<>(), new ArrayList<>());
    }

    /** Whether a requested read has yet to arrive. */
    public boolean waiting() {
        return outstanding > 0;
    }

    /** Why a requested read never arrived, or null. */
    public String failure() {
        return failure;
    }

    /** Reads answering, in order, what each of these requests read. Once none is {@linkplain #waiting() waiting}. */
    public CgReplayedReads replay() {
        if (mode != Mode.REQUESTING) throw new IllegalStateException("only a requesting reader replays");
        if (waiting()) throw new IllegalStateException(outstanding + " reads have not arrived");
        return new CgReplayedReads(Mode.REPLAYING, keys, arrived);
    }

    /** The first {@code count} words of a GL buffer. */
    public int[] words(int buffer, int count) {
        ByteBuffer bytes = bytes("words " + count, count * 4, () -> {
            CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, buffer);
            ByteBuffer mapped = CgGL.glMapBufferRange(CgGL.GL_COPY_READ_BUFFER, 0, count * 4L, CgGL.GL_MAP_READ_BIT, null);
            ByteBuffer copy = ByteBuffer.allocate(count * 4).order(ByteOrder.nativeOrder());
            copy.put(mapped.order(ByteOrder.nativeOrder())).flip();
            CgGL.glUnmapBuffer(CgGL.GL_COPY_READ_BUFFER);
            CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
            return copy;
        }, sink -> CgReadback.buffer(buffer, 0, count * 4L, sink));
        int[] words = new int[count];
        for (int i = 0; i < count; i++) words[i] = bytes.getInt(i * 4);
        return words;
    }

    /**
     * A {@code width} x {@code height} region at the origin of {@code framebuffer}'s colour attachment 0, in
     * {@code type}'s base format and pixel type, rows bottom first: what {@link CgReadback#pixels} reads.
     */
    public ByteBuffer pixels(int framebuffer, int width, int height, CgTextureType type) {
        int size = width * height * CgReadback.pixelBytes(type);
        return bytes("pixels " + width + "x" + height + " " + type, size, () -> {
            ByteBuffer out = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            try (CgGlScope ignored = CgGlState.save(FBO)) {
                CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, framebuffer);
                CgGL.glPixelStorei(CgGL.GL_PACK_ALIGNMENT, 1);
                CgGL.glReadPixels(0, 0, width, height, type.glBaseFormat, type.glType, out);
                CgGL.glPixelStorei(CgGL.GL_PACK_ALIGNMENT, 4);   // GL's default
            }
            return out;
        }, sink -> CgReadback.pixels(framebuffer, 0, 0, width, height, type, sink));
    }

    /**
     * {@link #pixels} as RGBA floats, as {@code glGetTexImage(GL_RGBA, GL_FLOAT)} gives them: a unorm byte over 255,
     * a channel the type lacks 0, and alpha 1. For an 8-bit, half-float or float colour type.
     */
    public float[] rgba(int framebuffer, int width, int height, CgTextureType type) {
        ByteBuffer bytes = pixels(framebuffer, width, height, type);
        int channels = type.glBaseFormat == CgGL.GL_RED ? 1 : type.glBaseFormat == CgGL.GL_RG ? 2
                : type.glBaseFormat == CgGL.GL_RGB ? 3 : 4;
        int size = type.glType == CgGL.GL_UNSIGNED_BYTE ? 1 : type.glType == CgGL.GL_HALF_FLOAT ? 2 : 4;
        float[] out = new float[width * height * 4];
        for (int p = 0; p < width * height; p++) {
            for (int c = 0; c < 4; c++) {
                float v = c == 3 ? 1f : 0f;
                if (c < channels) {
                    int at = (p * channels + c) * size;
                    v = size == 1 ? (bytes.get(at) & 255) / 255f
                            : size == 2 ? halfToFloat(bytes.getShort(at) & 0xFFFF) : bytes.getFloat(at);
                }
                out[p * 4 + c] = v;
            }
        }
        return out;
    }

    private interface Now {
        ByteBuffer read();
    }

    private interface Request {
        void start(CgReadback.Sink sink);
    }

    private ByteBuffer bytes(String key, int size, Now now, Request request) {
        switch (mode) {
            case IMMEDIATE:
                return now.read();
            case REQUESTING: {
                int slot = keys.size();
                keys.add(key);
                arrived.add(null);
                outstanding++;
                request.start(new CgReadback.Sink() {
                    @Override
                    public void accept(ByteBuffer data) {
                        byte[] copy = new byte[size];
                        data.duplicate().get(copy);
                        arrived.set(slot, copy);
                        outstanding--;
                    }

                    @Override
                    public void failed(String reason) {
                        if (failure == null) failure = key + ": " + reason;
                        arrived.set(slot, new byte[size]);
                        outstanding--;
                    }
                });
                return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
            }
            default: {
                if (next >= keys.size() || !keys.get(next).equals(key)) {
                    throw new IllegalStateException("replayed read " + next + " is " + key + ", not "
                            + (next < keys.size() ? keys.get(next) : "past the " + keys.size() + " requested"));
                }
                return ByteBuffer.wrap(arrived.get(next++)).order(ByteOrder.nativeOrder());
            }
        }
    }

    /** IEEE half-precision bits as a float. Java 8 has no Float.float16ToFloat. */
    private static float halfToFloat(int h) {
        int sign = (h & 0x8000) << 16, e = (h >>> 10) & 0x1F, m = h & 0x3FF;
        if (e == 0) return m == 0 ? Float.intBitsToFloat(sign) : (sign != 0 ? -1f : 1f) * m * 5.9604644775390625e-8f;
        if (e == 31) return Float.intBitsToFloat(sign | 0x7F800000 | (m << 13));
        return Float.intBitsToFloat(sign | ((e + 112) << 23) | (m << 13));
    }
}
