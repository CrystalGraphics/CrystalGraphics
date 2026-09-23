package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small picture of what was on screen, every so many frames — so scrubbing a frame strip shows the
 * moment rather than only its numbers. Tracy's frame images.
 *
 * <pre>{@code
 * // the painter, after the frame is drawn (GL thread):
 * if (CgFrameImages.isDue(CgTrace.currentFrameIndex())) captureAndLater(pixels ->
 *         CgFrameImages.put(frameIndex, width, height, rgb));
 *
 * // a viewer:
 * CgFrameImages.Image shown = CgFrameImages.atOrBefore(frame.index());   // null when none is held
 * }</pre>
 *
 * <p>Holds no GL and captures nothing itself: the engine stays loadable on a server, and whoever owns
 * the frame's picture decides how to read it back.</p>
 *
 * <h3>Easy to get wrong</h3>
 * <ul>
 *   <li>Off unless the {@link #IMAGES} channel is on — a capture costs a readback, so it is asked for.</li>
 *   <li>An image is a frame's FINISHED picture as its painter saw it: on a host whose world is drawn
 *       outside that painter, the image has the interface and not the world.</li>
 *   <li>The frame asked for may have no image of its own; {@link #atOrBefore} answers the nearest
 *       earlier one, and its {@link Image#frameIndex()} says which.</li>
 * </ul>
 */
public final class CgFrameImages {

    private CgFrameImages() {}

    public static final CgTraceChannel IMAGES = CgTrace.channel("images");

    /** One picture: {@code rgb} is {@code width * height * 3} bytes, top row first. */
    public record Image(long frameIndex, int width, int height, byte[] rgb) {}

    private static volatile int interval = 30;
    private static volatile int width = 256;

    /** Images of the first frames, kept as long as those frames are. */
    private static final TreeMap<Long, Image> HEAD = new TreeMap<>();
    /** Images of the newest frames, oldest dropped first. */
    private static final TreeMap<Long, Image> RING = new TreeMap<>();

    /** Every {@code frames} frames. Takes effect at the next due frame. */
    public static void setInterval(int frames) {
        interval = Math.max(1, frames);
    }

    public static int interval() {
        return interval;
    }

    /** How wide an image is captured, in pixels; the height keeps the frame's aspect. */
    public static void setWidth(int pixels) {
        width = Math.max(16, pixels);
    }

    public static int width() {
        return width;
    }

    /** Whether frame {@code frameIndex} should be photographed — the channel is on and it is its turn. */
    public static boolean isDue(long frameIndex) {
        return CgTrace.isEnabled(IMAGES) && frameIndex >= 0L && frameIndex % interval == 0L;
    }

    /** Stores the picture of frame {@code frameIndex}. Any thread. */
    public static synchronized void put(long frameIndex, int width, int height, byte[] rgb) {
        if (frameIndex < 0L || rgb == null || rgb.length < width * height * 3) return;
        Image image = new Image(frameIndex, width, height, rgb);
        if (frameIndex < CgTrace.firstFrames()) {
            HEAD.put(frameIndex, image);
            return;
        }
        RING.put(frameIndex, image);
        // AS MANY AS THE RING HAS FRAMES FOR, plus one: an image of a frame that is gone cannot be found
        // from the strip, and memory spent on it is memory the viewer cannot show.
        int keep = Math.max(2, CgTrace.newestFrames() / interval + 1);
        while (RING.size() > keep) RING.pollFirstEntry();
    }

    /** The image of {@code frameIndex} itself, or null. */
    public static synchronized Image at(long frameIndex) {
        Image image = RING.get(frameIndex);
        return image != null ? image : HEAD.get(frameIndex);
    }

    /** The newest image at or before {@code frameIndex}, or null when none is held. */
    public static synchronized Image atOrBefore(long frameIndex) {
        Map.Entry<Long, Image> ring = RING.floorEntry(frameIndex);
        if (ring != null) return ring.getValue();
        Map.Entry<Long, Image> head = HEAD.floorEntry(frameIndex);
        return head == null ? null : head.getValue();
    }

    /**
     * Every image of a frame in {@code [from, to]}, oldest first — what a viewer holding a snapshot keeps
     * references to, so a picture the store drops as the ring moves on stays with the frames it shows.
     */
    public static synchronized List<Image> between(long from, long to) {
        List<Image> out = new ArrayList<>(HEAD.subMap(from, true, to, true).values());
        out.addAll(RING.subMap(from, true, to, true).values());
        return out;
    }

    /** How many images are held. */
    public static synchronized int count() {
        return HEAD.size() + RING.size();
    }

    /** Drops every image — the frames they belonged to are gone. @see CgTrace#clear() */
    public static synchronized void clear() {
        HEAD.clear();
        RING.clear();
    }
}
