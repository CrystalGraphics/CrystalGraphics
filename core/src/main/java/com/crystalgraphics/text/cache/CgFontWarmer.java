package com.crystalgraphics.text.cache;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.text.render.context.CgTextScaleResolver;

import java.util.ArrayDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides WHEN a speculative glyph warm is allowed to use a worker, and in what order.
 *
 * <p>Owned by {@link CgFontRegistry}, which exposes it as {@code warmAscii} and feeds it once a
 * frame:</p>
 *
 * <pre>{@code
 * registry.warmAscii(face, 14, 18);                       // queues; schedules nothing
 * warmer.feed(frame, executor.msdfQueueDepth(), this::queueGlyph);   // from tickFrame
 * }</pre>
 *
 * <h3>The rule this class exists to hold</h3>
 *
 * <p><b>Speculation must never be in front of a glyph somebody is looking at.</b> Submitting every
 * resolved face's printable ASCII straight to the pool put over a thousand jobs ahead of whatever was
 * drawn next on a cold gallery, and the distance-field queue never fell below five while they drained.
 * Queue depth is read from the DISTANCE-FIELD pool alone: the registry's pending count spans
 * both pools, and the bitmap fallbacks a cold page raises would hold the gate shut for exactly the
 * window a warm is for.</p>
 *
 * <p>Order is by letter frequency rather than codepoint, because a queue is FIFO and 0x20..0x7E puts
 * every lowercase letter in the last third — behind punctuation most labels never contain.</p>
 *
 * <p>Best-effort throughout: a glyph this never reaches is generated on demand exactly as it would
 * have been.</p>
 */
final class CgFontWarmer {

    private static final Logger LOGGER = Logger.getLogger(CgFontWarmer.class.getName());

    /** How the warmer hands work back to whatever owns the atlases. */
    @FunctionalInterface
    interface GlyphSink {
        void queue(CgFont font, CgGlyphKey key, int effectiveTargetPx, int subPixelBucket, long frame);
    }

    /** Printable ASCII, the set every Latin UI draws before it draws anything else. */
    private static final int ASCII_FIRST = 0x20;

    private static final int ASCII_LAST  = 0x7E;

    /**
     * The order the warm runs in, which is NOT the order the codepoint table lists.
     *
     * <p>A queue is FIFO, so warm order is the order glyphs become drawable. Walking 0x20..0x7E puts
     * every lowercase letter in the last third, behind punctuation and digits most labels never use.
     * Letter frequency first costs nothing and puts the common case at the front.</p>
     *
     * <p>The tail is filled from the full range programmatically, so every printable ASCII character
     * is warmed exactly once however this prefix is edited.</p>
     */
    static final int[] ASCII_ORDER = buildWarmOrder();   // package-private for CgWarmOrderTest

    /** Warm jobs waiting for a frame with nothing better to do. @see #feed */
    private final ArrayDeque<WarmRequest> pendingWarm = new ArrayDeque<>();

    /**
     * Warm jobs handed to the pool on a frame that finds it EMPTY.
     *
     * <p>One round for the workers: four jobs is about one frame's work at ~14 ms a glyph across four
     * threads, so the pool is empty again by the next frame. Starving the render thread was suspected
     * and measured out: frames around a font switch ran 4-10 ms with this feeding.</p>
     *
     * <p><b>Empty, not "short".</b> Topping the queue up whenever it fell to eight kept it at eight to
     * sixteen permanently, which is speculation standing in front of demand the whole time a page is
     * cold -- measured on the gallery, the distance-field queue never once fell below five while a
     * thousand warm jobs drained through it, and its median fell from nine to zero once gated on
     * empty. Gated on empty, the most a demanded glyph waits behind is one round.</p>
     *
     * <p>It makes the warm itself slower, which is the correct direction: nobody is waiting for it.</p>
     */
    private static final int WARM_FEED_PER_FRAME = 4;

    /** Best-effort, so a deque growing faster than it drains drops the newest rather than the UI. */
    private static final int MAX_PENDING_WARM = 4096;

    /** One deferred warm: what {@link CgFontRegistry#queueGlyph} needs, minus the frame it is fed on. */
    private record WarmRequest(CgFont font, CgGlyphKey key, int effectiveTargetPx) {
    }

    /** Hands the pool a little speculative work, but only on a frame that has none outstanding. */
    void feed(long frame, int msdfQueueDepth, GlyphSink sink) {
        // The MSDF queue alone: pendingJobs spans both pools, and the bitmap fallbacks a cold page
        // raises would hold this shut for as long as they take to drain -- which is exactly the window
        // the warm exists to fill.
        if (pendingWarm.isEmpty() || msdfQueueDepth > 0) {
            return;
        }
        for (int i = 0; i < WARM_FEED_PER_FRAME; i++) {
            WarmRequest request = pendingWarm.poll();
            if (request == null) return;
            if (request.font.isDisposed()) continue;
            try {
                sink.queue(request.font, request.key, request.effectiveTargetPx, 0, frame);
            } catch (RuntimeException broken) {
                LOGGER.log(Level.FINE, "deferred warm failed for " + request.key, broken);
            }
        }
    }

    private void enqueueWarm(WarmRequest request) {
        if (pendingWarm.size() < MAX_PENDING_WARM) pendingWarm.add(request);
    }

    private static int[] buildWarmOrder() {
        // Lowercase by English letter frequency, then the punctuation a label really contains, then
        // uppercase, then digits. Everything else follows in codepoint order.
        String preferred = "etaoinsrhldcumfpgwybvkxjqz .,:-()'"
                + "ETAOINSRHLDCUMFPGWYBVKXJQZ0123456789";
        int[] order = new int[ASCII_LAST - ASCII_FIRST + 1];
        boolean[] seen = new boolean[ASCII_LAST + 1];
        int count = 0;
        for (int i = 0; i < preferred.length(); i++) {
            char c = preferred.charAt(i);
            if (c >= ASCII_FIRST && c <= ASCII_LAST && !seen[c]) {
                seen[c] = true;
                order[count++] = c;
            }
        }
        for (int codePoint = ASCII_FIRST; codePoint <= ASCII_LAST; codePoint++) {
            if (!seen[codePoint]) {
                seen[codePoint] = true;
                order[count++] = codePoint;
            }
        }
        return order;
    }

    /**
     * Queues printable ASCII for one face; nothing reaches the pool until {@link #feed} finds it idle.
     * What is queued for which tier is {@link CgFontRegistry#warmAscii}'s contract.
     */
    void enqueueAscii(CgFont font, int... effectiveTargetPx) {
        if (font == null || font.isDisposed()) return;

        for (int codePoint : ASCII_ORDER) {
            int glyphId = font.getGlyphIndex(codePoint);
            // 0 is .notdef -- the font has no drawing for this codepoint, so there is nothing to warm.
            if (glyphId <= 0) continue;
            try {
                // Size-independent, so one submission covers every size asked for below. Queued through
                // the font's own key: submitMsdfGlyphJob carries font.getKey(), so warming this from
                // three differently-sized instances of one face would build three jobs that are not
                // equal, defeat the executor's pendingJobs dedup, and generate the same atlas entry
                // three times.
                enqueueWarm(new WarmRequest(font, new CgGlyphKey(font.getKey(), glyphId, true, 0),
                        font.getTargetPx()));

                for (int px : effectiveTargetPx) {
                    if (px <= 0 || px >= CgTextScaleResolver.MSDF_ENTER_THRESHOLD) continue;
                    enqueueWarm(new WarmRequest(font,
                            new CgGlyphKey(font.getKey(), glyphId, false, 0), px));
                }
            } catch (RuntimeException broken) {
                // An optimisation must never be the thing that fails a context, and one unwarmable
                // glyph says the rest of this face will not warm either. Everything still generates
                // lazily exactly as before.
                LOGGER.log(Level.FINE, "ASCII warm stopped early for " + font.getKey(), broken);
                return;
            }
        }
    }

    /** How much speculation is still waiting to be offered. */
    int pendingCount() {
        return pendingWarm.size();
    }

    /** Drops everything queued, for a context teardown that invalidates the faces it refers to. */
    void clear() {
        pendingWarm.clear();
    }
}
