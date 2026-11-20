package com.crystalgraphics.render.debug;

import com.crystalgraphics.render.precache.CgPipelineList;
import java.util.Arrays;
import java.util.Objects;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * Lines, points, boxes, spheres, arrows and text drawn in the world to see what code is doing: Unreal's
 * {@code DrawDebugLine} family. From any thread; shown on the next frame only, or for a duration, or until
 * {@link #clear}.
 *
 * <pre>{@code
 * CgDebugDraw.line(x, y, z, tx, ty, tz, 0xFFFF4040);                     // this frame
 * CgDebugDraw.box(minX, minY, minZ, maxX, maxY, maxZ, 0xFF40FF60, 2f);   // two seconds
 * CgDebugDraw.sphere(x, y, z, 1.5, 0xFF4080FF, CgDebugDraw.FOREVER);     // until clear()
 * CgDebugDraw.arrow(x, y, z, x + vx, y + vy, z + vz, 0xFFFFD040);
 * }</pre>
 *
 * <p>Over everything, the scene's depth ignored: Unreal's {@code SDPG_Foreground}.</p>
 *
 * <pre>{@code
 * CgDebugDraw.foreground().line(x, y, z, tx, ty, tz, 0xFFFFFFFF);
 * }</pre>
 *
 * <p>Text, in a font of yours given once: the engine ships none.</p>
 *
 * <pre>{@code
 * CgDebugDraw.font(font);
 * CgDebugDraw.text(x, y + 2, z, "state: " + state, 0xFFFFFFFF);
 * }</pre>
 *
 * <ul>
 *   <li>Colours are ARGB; durations are seconds of the frame clock, 0 for one frame.</li>
 *   <li>Depth-tested shapes write no depth: one never hides another, nor the world behind it.</li>
 *   <li>Its frame's shapes draw over the world's other transparent draws; text is a label for the frame, depth-tested
 *       on either layer.</li>
 * </ul>
 */
public final class CgDebugDraw {

    /** A duration that lasts until {@link #clear}: Unreal's persistent lines. */
    public static final float FOREVER = Float.POSITIVE_INFINITY;
    /** Its lines' material: one colour a segment, in the vertex; depth-tested, writing no depth. */
    public static final String SHADER = "crystalgraphics:shaders/debug/lines.shader";
    /** {@link #SHADER} drawn over everything: the foreground's. */
    public static final String FOREGROUND_SHADER = "crystalgraphics:shaders/debug/lines_foreground.shader";

    private static final CgDebugDraw WORLD = new CgDebugDraw(false), FOREGROUND = new CgDebugDraw(true);
    private static final int ZONE = CgTrace.name("debug.draw");
    /** Segments a sphere's circle is drawn with, and the circles a sphere is. */
    private static final int ARC = 24, RINGS = 3;
    private static final double[] COS = new double[ARC + 1], SIN = new double[ARC + 1];

    static {
        for (int i = 0; i <= ARC; i++) {
            COS[i] = Math.cos(2 * Math.PI * i / ARC);
            SIN[i] = Math.sin(2 * Math.PI * i / ARC);
        }
    }

    private static boolean installed;
    private static volatile CgFont font;
    private static CgMaterial material;
    private static CgMaterial onTop;

    private final boolean foreground;
    private final CgDebugBatch batch = new CgDebugBatch(2);
    // Segments queued and kept: six doubles each, a colour, and the frame-clock second they go at (-inf: one frame)
    private double[] points = new double[6 * 256];
    private int[] colours = new int[256];
    private double[] until = new double[256];
    private int count;
    // Texts: the same, a string each
    private String[] texts = new String[16];
    private double[] textAt = new double[3 * 16];
    private int[] textColours = new int[16];
    private float[] textHeights = new float[16];
    private double[] textUntil = new double[16];
    private int textCount;

    private CgDebugDraw(boolean foreground) {
        this.foreground = foreground;
    }

    /** What draws over everything, the scene's depth ignored: each method as the static one. */
    public static CgDebugDraw foreground() {
        return FOREGROUND;
    }

    /** What draws against the scene's depth: the static methods' layer. */
    public static CgDebugDraw world() {
        return WORLD;
    }

    /** The font {@link #text} draws in; text before one is set throws. */
    public static void font(CgFont font) {
        CgDebugDraw.font = Objects.requireNonNull(font, "font");
    }

    // ── The world layer ────────────────────────────────────────────────────────────────────────

    public static void line(double ax, double ay, double az, double bx, double by, double bz, int argb) {
        WORLD.segment(ax, ay, az, bx, by, bz, argb, 0f);
    }

    public static void line(double ax, double ay, double az, double bx, double by, double bz, int argb, float seconds) {
        WORLD.segment(ax, ay, az, bx, by, bz, argb, seconds);
    }

    public static void point(double x, double y, double z, double size, int argb) {
        WORLD.drawPoint(x, y, z, size, argb, 0f);
    }

    public static void point(double x, double y, double z, double size, int argb, float seconds) {
        WORLD.drawPoint(x, y, z, size, argb, seconds);
    }

    public static void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb) {
        WORLD.drawBox(minX, minY, minZ, maxX, maxY, maxZ, argb, 0f);
    }

    public static void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb,
                           float seconds) {
        WORLD.drawBox(minX, minY, minZ, maxX, maxY, maxZ, argb, seconds);
    }

    public static void sphere(double x, double y, double z, double radius, int argb) {
        WORLD.drawSphere(x, y, z, radius, argb, 0f);
    }

    public static void sphere(double x, double y, double z, double radius, int argb, float seconds) {
        WORLD.drawSphere(x, y, z, radius, argb, seconds);
    }

    /** A segment from a to b with a head at b a fifth of its length. */
    public static void arrow(double ax, double ay, double az, double bx, double by, double bz, int argb) {
        WORLD.drawArrow(ax, ay, az, bx, by, bz, argb, 0f);
    }

    public static void arrow(double ax, double ay, double az, double bx, double by, double bz, int argb, float seconds) {
        WORLD.drawArrow(ax, ay, az, bx, by, bz, argb, seconds);
    }

    /** {@code text} facing the camera at a point, a line a quarter block tall. */
    public static void text(double x, double y, double z, String text, int argb) {
        WORLD.drawText(x, y, z, text, argb, 0.25f, 0f);
    }

    public static void text(double x, double y, double z, String text, int argb, float height, float seconds) {
        WORLD.drawText(x, y, z, text, argb, height, seconds);
    }

    /** Drops what both layers keep: timed and {@link #FOREVER} shapes and text. */
    public static void clear() {
        WORLD.drop();
        FOREGROUND.drop();
    }

    // ── Either layer ───────────────────────────────────────────────────────────────────────────

    public CgDebugDraw drawLine(double ax, double ay, double az, double bx, double by, double bz, int argb) {
        return segment(ax, ay, az, bx, by, bz, argb, 0f);
    }

    public CgDebugDraw drawLine(double ax, double ay, double az, double bx, double by, double bz, int argb, float seconds) {
        return segment(ax, ay, az, bx, by, bz, argb, seconds);
    }

    /** Three crossing segments {@code size} long. */
    public CgDebugDraw drawPoint(double x, double y, double z, double size, int argb, float seconds) {
        double h = size * 0.5;
        synchronized (this) {
            segment(x - h, y, z, x + h, y, z, argb, seconds);
            segment(x, y - h, z, x, y + h, z, argb, seconds);
            segment(x, y, z - h, x, y, z + h, argb, seconds);
        }
        return this;
    }

    public CgDebugDraw drawBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb,
                               float seconds) {
        synchronized (this) {
            // The four edges along x at each (y, z) corner, then along y, then along z
            for (int k = 0; k < 4; k++) {
                double y = (k & 1) == 0 ? minY : maxY, z = (k & 2) == 0 ? minZ : maxZ;
                double x = (k & 1) == 0 ? minX : maxX, zz = (k & 2) == 0 ? minZ : maxZ;
                double xx = (k & 1) == 0 ? minX : maxX, yy = (k & 2) == 0 ? minY : maxY;
                segment(minX, y, z, maxX, y, z, argb, seconds);
                segment(x, minY, zz, x, maxY, zz, argb, seconds);
                segment(xx, yy, minZ, xx, yy, maxZ, argb, seconds);
            }
        }
        return this;
    }

    /** Three great circles, one about each axis: Unreal's {@code DrawDebugSphere} reduced to what reads. */
    public CgDebugDraw drawSphere(double x, double y, double z, double radius, int argb, float seconds) {
        synchronized (this) {
            for (int ring = 0; ring < RINGS; ring++) {
                for (int i = 0; i < ARC; i++) {
                    double c0 = COS[i] * radius, s0 = SIN[i] * radius, c1 = COS[i + 1] * radius, s1 = SIN[i + 1] * radius;
                    switch (ring) {
                        case 0 -> segment(x + c0, y + s0, z, x + c1, y + s1, z, argb, seconds);
                        case 1 -> segment(x + c0, y, z + s0, x + c1, y, z + s1, argb, seconds);
                        default -> segment(x, y + c0, z + s0, x, y + c1, z + s1, argb, seconds);
                    }
                }
            }
        }
        return this;
    }

    public CgDebugDraw drawArrow(double ax, double ay, double az, double bx, double by, double bz, int argb,
                                 float seconds) {
        double dx = bx - ax, dy = by - ay, dz = bz - az, length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9) return this;
        double fx = dx / length, fy = dy / length, fz = dz / length;
        // A side vector off the arrow, then the head's four barbs back from its tip
        double sx = Math.abs(fy) < 0.9 ? -fz : 0, sy = Math.abs(fy) < 0.9 ? 0 : fz, sz = Math.abs(fy) < 0.9 ? fx : -fy;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl;
        sy /= sl;
        sz /= sl;
        double ux = fy * sz - fz * sy, uy = fz * sx - fx * sz, uz = fx * sy - fy * sx;
        double head = length * 0.2, back = head, wide = head * 0.5;
        double hx = bx - fx * back, hy = by - fy * back, hz = bz - fz * back;
        synchronized (this) {
            segment(ax, ay, az, bx, by, bz, argb, seconds);
            segment(bx, by, bz, hx + sx * wide, hy + sy * wide, hz + sz * wide, argb, seconds);
            segment(bx, by, bz, hx - sx * wide, hy - sy * wide, hz - sz * wide, argb, seconds);
            segment(bx, by, bz, hx + ux * wide, hy + uy * wide, hz + uz * wide, argb, seconds);
            segment(bx, by, bz, hx - ux * wide, hy - uy * wide, hz - uz * wide, argb, seconds);
        }
        return this;
    }

    public synchronized CgDebugDraw drawText(double x, double y, double z, String text, int argb, float height,
                                             float seconds) {
        Objects.requireNonNull(text, "text");
        if (font == null) throw new IllegalStateException("CgDebugDraw.font(...) first: the engine ships no font");
        if (textCount == texts.length) {
            int n = textCount * 2;
            texts = Arrays.copyOf(texts, n);
            textAt = Arrays.copyOf(textAt, 3 * n);
            textColours = Arrays.copyOf(textColours, n);
            textHeights = Arrays.copyOf(textHeights, n);
            textUntil = Arrays.copyOf(textUntil, n);
        }
        int i = textCount++;
        texts[i] = text;
        textAt[3 * i] = x;
        textAt[3 * i + 1] = y;
        textAt[3 * i + 2] = z;
        textColours[i] = argb;
        textHeights[i] = height;
        textUntil[i] = expiry(seconds);
        return this;
    }

    private synchronized CgDebugDraw segment(double ax, double ay, double az, double bx, double by, double bz, int argb,
                                             float seconds) {
        if (count == colours.length) {
            int n = count * 2;
            points = Arrays.copyOf(points, 6 * n);
            colours = Arrays.copyOf(colours, n);
            until = Arrays.copyOf(until, n);
        }
        int at = 6 * count;
        points[at] = ax;
        points[at + 1] = ay;
        points[at + 2] = az;
        points[at + 3] = bx;
        points[at + 4] = by;
        points[at + 5] = bz;
        colours[count] = argb;
        until[count++] = expiry(seconds);
        return this;
    }

    /** When something drawn for {@code seconds} goes: -inf for one frame. */
    private static double expiry(float seconds) {
        return seconds > 0f ? CgFrameClock.seconds() + seconds : Double.NEGATIVE_INFINITY;
    }

    private synchronized void drop() {
        count = 0;
        Arrays.fill(texts, 0, textCount, null);
        textCount = 0;
    }

    // ── Engine side ────────────────────────────────────────────────────────────────────────────

    /** Draws both layers each world frame. Once, at the engine's start. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "crystalgraphics:debug-draw",
                dt -> frame(CgWorld.client().view())).whilePaused(true);
    }

    /** Engine side, the world's precache: both layers' materials. */
    public static void precache(CgPipelineList list) {
        list.add(CgMaterial.load(SHADER), false);
        list.add(CgMaterial.load(FOREGROUND_SHADER), false);
    }

    /** Frees its meshes and material with the context; what it keeps stays queued. */
    public static void release() {
        WORLD.batch.release();
        FOREGROUND.batch.release();
        material = null;
        onTop = null;
    }

    private static void frame(CgCamera view) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, ZONE)) {
            double now = CgFrameClock.seconds();
            WORLD.submit(view, now);
            FOREGROUND.submit(view, now);
        }
    }

    /** This frame's segments and text drawn, and what has expired, one-frame ones included, dropped. */
    private synchronized void submit(CgCamera view, double now) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int at = 6 * i;
            batch.colour(colours[i]).line(points[at], points[at + 1], points[at + 2], points[at + 3], points[at + 4],
                    points[at + 5]);
            if (until[i] > now) {
                if (kept != i) {
                    System.arraycopy(points, at, points, 6 * kept, 6);
                    colours[kept] = colours[i];
                    until[kept] = until[i];
                }
                kept++;
            }
        }
        count = kept;
        if (!batch.isEmpty()) {
            if (material == null) {
                material = CgMaterial.load(SHADER);
                onTop = CgMaterial.load(FOREGROUND_SHADER);
            }
            // After collision's (groups 0 to 2), the foreground after the world's
            batch.draw(view.x(), view.y(), view.z(), foreground ? onTop : material, foreground ? 6 : 3);
        }
        int keptText = 0;
        for (int i = 0; i < textCount; i++) {
            int at = 3 * i;
            CgScene.client().textOnce(texts[i]).at(textAt[at], textAt[at + 1], textAt[at + 2]).height(textHeights[i])
                    .font(font).color(textColours[i]).submit();
            if (textUntil[i] > now) {
                if (keptText != i) {
                    texts[keptText] = texts[i];
                    System.arraycopy(textAt, at, textAt, 3 * keptText, 3);
                    textColours[keptText] = textColours[i];
                    textHeights[keptText] = textHeights[i];
                    textUntil[keptText] = textUntil[i];
                }
                keptText++;
            }
        }
        Arrays.fill(texts, keptText, textCount, null);
        textCount = keptText;
    }
}
