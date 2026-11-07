package com.crystalgraphics.gl;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * GL object names generated ahead, in batches: on a threaded driver every {@code glGen*} waits for its queue to drain,
 * behind whatever the frame queued (23 ms for one name on a cut's first frame, 117 ms behind the loading screen's
 * compiles), so a frame making objects for the first time (a transient's storage, a texture's first upload, a
 * framebuffer, a stream buffer) pays that wait once for all of them, in {@code CgGraphicsLifecycle.tickFrame}, not once
 * each. Every {@code glGen*} of the engine's goes through here.
 *
 * <pre>{@code
 * int buffer = CgGlNames.buffer();          // instead of CgGL.glGenBuffers()
 * int texture = CgGlNames.texture();
 * int framebuffer = CgGlNames.framebuffer();
 * int vertexArray = CgGlNames.vertexArray();
 * int renderbuffer = CgGlNames.renderbuffer();
 * }</pre>
 *
 * <ul>
 *   <li>Wherever {@link CgGL} may be called (a texture's deferred work too). An empty reserve generates one name on the spot, counted as
 *       {@code gl.names-short}: a frame making more of a kind than it held, after which it holds that many more.</li>
 *   <li>A name is a name only: bind and allocate it as one fresh from {@code glGen*}. A vertex array's is the render
 *       context's own, as any vertex array is.</li>
 * </ul>
 */
public final class CgGlNames {

    /**
     * Names of each kind kept in hand at first; a kind a frame ran short of keeps twice the shortfall more. A move's first
     * frames make about 90 buffers (the Kamehameha's pools and range): at 256 that leaves the reserve above half, so a
     * move plays with no refill at all, and the refill waits for play to settle (a refill behind a few queued frames of
     * a move still cost 13-29 ms). The engine's start takes about 300, and its loading frames have the deepest queues of
     * all (a refill there waited 346 ms): 1024 lasts through them. A name with no storage costs the driver nothing.
     * Vertex arrays and renderbuffers are few: 64.
     */
    public static final int RESERVE = 1024, FEW = 64;
    /** The most of a kind it will keep in hand. */
    public static final int MAX_RESERVE = 2048;

    private static final int REFILL = CgTrace.name("gl.names"), SHORT = CgTrace.name("gl.names-short");
    private static final int BUFFERS = 0, TEXTURES = 1, FRAMEBUFFERS = 2, VERTEX_ARRAYS = 3, RENDERBUFFERS = 4, KINDS = 5;
    private static final int[][] reserve = {new int[RESERVE], new int[RESERVE], new int[RESERVE], new int[FEW], new int[FEW]};
    private static final int[] held = new int[KINDS], shortBy = new int[KINDS];
    /** Names taken since the last refill: a frame that took some ended with a deep queue behind it. */
    private static int taken;

    private CgGlNames() {
    }

    public static int buffer() {
        return take(BUFFERS);
    }

    public static int texture() {
        return take(TEXTURES);
    }

    public static int framebuffer() {
        return take(FRAMEBUFFERS);
    }

    public static int vertexArray() {
        return take(VERTEX_ARRAYS);
    }

    public static int renderbuffer() {
        return take(RENDERBUFFERS);
    }

    /**
     * Tops every kind up to what it keeps, grown by what the frame ran short, once any kind is below half, on a frame
     * that took none: the refill waits for the driver's queue, which is deepest after a frame that made objects (a
     * cut's opening frame, 100-200 ms). Below an eighth it refills anyway. Render thread, at the frame boundary.
     */
    public static synchronized void refill() {
        boolean busy = taken > 0;
        taken = 0;
        for (int kind = 0; kind < KINDS; kind++) {
            if (shortBy[kind] > 0) {
                int[] grown = new int[Math.min(MAX_RESERVE, reserve[kind].length + 2 * shortBy[kind])];
                System.arraycopy(reserve[kind], 0, grown, 0, held[kind]);
                reserve[kind] = grown;
                shortBy[kind] = 0;
            }
        }
        // Below half only: a refill is one wait for the driver's queue, so a frame taking a few names costs none.
        if (!below(2)) return;
        if (busy && !below(8)) return;   // a quieter frame refills
        fill();
    }

    /** Fills every kind to what it keeps, now: the engine's start, while the driver's queue is short. Render thread. */
    public static synchronized void fill() {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, REFILL)) {
            // Back to back: the first drains the queue, the rest find it empty.
            for (int kind = 0; kind < KINDS; kind++) {
                while (held[kind] < reserve[kind].length) reserve[kind][held[kind]++] = generate(kind);
            }
        }
    }

    /** Deletes the names held and unused. At context teardown, before the context goes. */
    public static synchronized void release() {
        while (held[BUFFERS] > 0) CgGL.glDeleteBuffers(reserve[BUFFERS][--held[BUFFERS]]);
        while (held[TEXTURES] > 0) CgGL.glDeleteTextures(reserve[TEXTURES][--held[TEXTURES]]);
        while (held[FRAMEBUFFERS] > 0) CgGL.glDeleteFramebuffers(reserve[FRAMEBUFFERS][--held[FRAMEBUFFERS]]);
        while (held[VERTEX_ARRAYS] > 0) CgGL.glDeleteVertexArrays(reserve[VERTEX_ARRAYS][--held[VERTEX_ARRAYS]]);
        while (held[RENDERBUFFERS] > 0) CgGL.glDeleteRenderbuffers(reserve[RENDERBUFFERS][--held[RENDERBUFFERS]]);
    }

    /** Whether any kind holds less than {@code 1 / share} of what it keeps. */
    private static boolean below(int share) {
        for (int kind = 0; kind < KINDS; kind++) if (share * held[kind] < reserve[kind].length) return true;
        return false;
    }

    private static synchronized int take(int kind) {
        taken++;
        if (held[kind] > 0) return reserve[kind][--held[kind]];
        shortBy[kind]++;
        CgTrace.add(CgChannels.GL, SHORT, 1);
        return generate(kind);
    }

    private static int generate(int kind) {
        return switch (kind) {
            case BUFFERS -> CgGL.glGenBuffers();
            case TEXTURES -> CgGL.glGenTextures();
            case FRAMEBUFFERS -> CgGL.glGenFramebuffers();
            case VERTEX_ARRAYS -> CgGL.glGenVertexArrays();
            default -> CgGL.glGenRenderbuffers();
        };
    }
}
