package com.crystalgraphics.vfx.trail;

/**
 * Where something moving has been in the last {@link #life()} seconds: a fist's ki streak, a sword's swoosh, a dash's
 * wake. An effect moves it every tick and draws it through a trail slot, and every layer on that slot draws it; the
 * strip is built on the GPU from its points ({@code fx_trail.glsl}), never as a mesh.
 *
 * <pre>{@code
 * public static final CgVfxSlot STREAK = CgVfxSlot.trail("mymod:dash/streak");
 * private final CgVfxTrail streak = CgVfxTrail.ribbon(0.35f);
 *
 * @Override protected void tick(float dt) {
 *     streak.tick(dt, handX, handY, handZ);              // world coordinates: it stays behind as the effect moves
 * }
 *
 * @Override protected void submit(CgVfxFrame frame) {
 *     frame.trail(this, STREAK, streak);                  // its head where the hand is this frame
 * }
 * }</pre>
 *
 * <p>A blade's swoosh, from its base and its tip:</p>
 *
 * <pre>{@code
 * private final CgVfxTrail swoosh = CgVfxTrail.blade(0.15f).spacing(0.05f);
 * swoosh.tick(dt, hiltX, hiltY, hiltZ, tipX, tipY, tipZ);
 * }</pre>
 *
 * <p>Lying flat across a normal of its own, as scorch marks along the ground do:</p>
 *
 * <pre>{@code
 * private final CgVfxTrail scorch = CgVfxTrail.flat(2.5f).facing(0, 1, 0);
 * }</pre>
 *
 * <ul>
 *   <li>The newest point follows its thing each tick; a point is kept behind it each {@link #spacing} blocks, so a still
 *       thing leaves none and its trail runs out. Points older than its life are dropped, the last one cut exactly at
 *       its life on the GPU, so the tail shrinks smoothly.</li>
 *   <li>Its head is drawn between its last two ticks, as {@code frame.alpha()} says, so it never lags the thing it
 *       follows; {@link #head} puts it somewhere exact this frame, a model's hand marked as it was drawn.</li>
 *   <li>A step farther than {@link #maxStep} is a teleport: it starts again there rather than streak across.</li>
 *   <li>{@link #width} and {@link #intensity} scale the points kept from then on: a streak swelling with speed.</li>
 *   <li>Allocates nothing once made. One thread: the effect's.</li>
 * </ul>
 */
public final class CgVfxTrail {

    /** How a trail's strip faces: {@code fx_trail.glsl}'s {@code FX_TRAIL_*}. */
    public enum Mode {
        /** A ribbon from one point, turned to face the eye. */
        CAMERA,
        /** A ribbon from one point, lying across the normal {@link #facing} gives. */
        AXIS,
        /** A sheet between two points, a base and a tip. */
        BLADE
    }

    /** The most points a trail keeps: what one draw reads. */
    public static final int MAX_POINTS = 512;

    private final Mode mode;
    private float life;
    private float spacing = 0.12f, maxStep = 8f;
    private int capacity = 128, subdivisions = 4;
    private boolean emitting = true;

    // Kept points, a ring from `first`, oldest first; the head is (prev, cur), never kept until it has moved on.
    private double[] ax = new double[0], ay = new double[0], az = new double[0];
    private double[] bx = new double[0], by = new double[0], bz = new double[0];
    private double[] born = new double[0], along = new double[0];
    private float[] extra = new float[0];
    private int first, count;
    private double time;
    private float lastDt = 1f / 120f;
    private boolean started;
    private double pax, pay, paz, pbx, pby, pbz, cax, cay, caz, cbx, cby, cbz;
    private double prevAlong, curAlong;
    private float nx, ny = 1f, nz, width = 1f, intensity = 1f, user0, user1;
    private boolean headSet;
    private double hax, hay, haz, hbx, hby, hbz;

    private CgVfxTrail(Mode mode, float life) {
        if (!(life > 0f)) throw new IllegalArgumentException("life " + life);
        this.mode = mode;
        this.life = life;
        grow(capacity);
    }

    /** A ribbon facing the eye whose points last {@code life} seconds. */
    public static CgVfxTrail ribbon(float life) {
        return new CgVfxTrail(Mode.CAMERA, life);
    }

    /** A sheet swept between a base and a tip whose points last {@code life} seconds. */
    public static CgVfxTrail blade(float life) {
        return new CgVfxTrail(Mode.BLADE, life);
    }

    /** A ribbon lying across a normal ({@link #facing}, up unless given) whose points last {@code life} seconds. */
    public static CgVfxTrail flat(float life) {
        return new CgVfxTrail(Mode.AXIS, life);
    }

    /** Seconds each point lasts; may change as it plays. */
    public CgVfxTrail life(float seconds) {
        if (!(seconds > 0f)) throw new IllegalArgumentException("life " + seconds);
        life = seconds;
        return this;
    }

    /** Blocks between kept points, 0.12 unless given: smaller is smoother through tight turns, and costs more. */
    public CgVfxTrail spacing(float blocks) {
        if (!(blocks >= 0f)) throw new IllegalArgumentException("spacing " + blocks);
        spacing = blocks;
        return this;
    }

    /** The most points kept, 128 unless given, up to {@link #MAX_POINTS}: past it the oldest go first. */
    public CgVfxTrail maxPoints(int points) {
        if (points < 2 || points > MAX_POINTS) throw new IllegalArgumentException("maxPoints " + points + ", 2 to " + MAX_POINTS);
        if (points != capacity) {
            clear();
            capacity = points;
            grow(points);
        }
        return this;
    }

    /** Quads drawn between two points, 4 unless given: 1 for dense points, more for a fast swing's sparse ones. */
    public CgVfxTrail subdivisions(int quads) {
        if (quads < 1 || quads > 16) throw new IllegalArgumentException("subdivisions " + quads + ", 1 to 16");
        subdivisions = quads;
        return this;
    }

    /** A step past this many blocks starts the trail again where it lands, 8 unless given. */
    public CgVfxTrail maxStep(float blocks) {
        maxStep = blocks;
        return this;
    }

    /** A {@link Mode#AXIS} trail's normal for the points from now. */
    public CgVfxTrail facing(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0e-6f) throw new IllegalArgumentException("facing (0, 0, 0)");
        nx = x / l;
        ny = y / l;
        nz = z / l;
        return this;
    }

    /** The width the points from now are drawn at, times the layer's size; 1 unless given. */
    public CgVfxTrail width(float scale) {
        width = scale;
        return this;
    }

    /** The brightness the points from now carry to the shader; 1 unless given. */
    public CgVfxTrail intensity(float scale) {
        intensity = scale;
        return this;
    }

    /** Two numbers of the effect's own the points from now carry to the shader ({@code FxTrailVertex.extra.zw}). */
    public CgVfxTrail user(float a, float b) {
        user0 = a;
        user1 = b;
        return this;
    }

    /** False stops it keeping points: what it has runs out, and then it is {@link #done}. True starts it again. */
    public CgVfxTrail emitting(boolean on) {
        if (on && !emitting) started = false;
        emitting = on;
        return this;
    }

    /** Ages it {@code dt} seconds, its head staying where it is. */
    public void tick(float dt) {
        advance(dt);
        expire();
    }

    /** Ages it {@code dt} seconds and moves a ribbon's head to {@code (x, y, z)}, in world coordinates. */
    public void tick(float dt, double x, double y, double z) {
        if (mode == Mode.BLADE) throw new IllegalStateException("a blade moves by its base and tip: tick(dt, base, tip)");
        move(dt, x, y, z, x + nx, y + ny, z + nz);
    }

    /** Ages it {@code dt} seconds and moves a blade's base to {@code (bx, by, bz)} and its tip to {@code (tx, ty, tz)}. */
    public void tick(float dt, double bx, double by, double bz, double tx, double ty, double tz) {
        if (mode != Mode.BLADE) throw new IllegalStateException("a " + mode + " trail moves by one point: tick(dt, x, y, z)");
        move(dt, bx, by, bz, tx, ty, tz);
    }

    private void move(float dt, double x, double y, double z, double x2, double y2, double z2) {
        advance(dt);
        if (!emitting) {
            expire();
            return;
        }
        if (!started || dist(x, y, z, cax, cay, caz) > maxStep) {
            clear();
            started = true;
            pax = cax = x; pay = cay = y; paz = caz = z;
            pbx = cbx = x2; pby = cby = y2; pbz = cbz = z2;
            prevAlong = curAlong = 0;
            return;
        }
        // The head moves on; where it was is kept once it is far enough past the last point kept, so a kept point is
        // never ahead of the head drawn between them.
        pax = cax; pay = cay; paz = caz;
        pbx = cbx; pby = cby; pbz = cbz;
        prevAlong = curAlong;
        cax = x; cay = y; caz = z;
        cbx = x2; cby = y2; cbz = z2;
        curAlong = prevAlong + Math.max(dist(pax, pay, paz, cax, cay, caz), dist(pbx, pby, pbz, cbx, cby, cbz));
        if (count == 0 || prevAlong - along[at(count - 1)] >= spacing) keep(time - dt);
        expire();
    }

    private void advance(float dt) {
        time += dt;
        if (dt > 0f) lastDt = dt;
        headSet = false;
    }

    private void keep(double when) {
        if (count == capacity) {
            first = (first + 1) % capacity;
            count--;
        }
        int i = at(count++);
        ax[i] = pax; ay[i] = pay; az[i] = paz;
        bx[i] = mode == Mode.CAMERA ? 0 : pbx; by[i] = mode == Mode.CAMERA ? 0 : pby; bz[i] = mode == Mode.CAMERA ? 0 : pbz;
        born[i] = when;
        along[i] = prevAlong;
        extra[i * 4] = width;
        extra[i * 4 + 1] = intensity;
        extra[i * 4 + 2] = user0;
        extra[i * 4 + 3] = user1;
    }

    /** Drops points past their life, but the newest of them, which the tail is cut against. */
    private void expire() {
        while (count >= 2 && time - born[at(1)] > life) {
            first = (first + 1) % capacity;
            count--;
        }
        if (count == 1 && !emitting && time - born[at(0)] > life) count = 0;
    }

    /** Where a ribbon's head is this frame, exactly, in world coordinates: a hand marked where the model was drawn. */
    public void head(double x, double y, double z) {
        head(x, y, z, x + nx, y + ny, z + nz);
    }

    /** Where a blade's base and tip are this frame, exactly. */
    public void head(double bx, double by, double bz, double tx, double ty, double tz) {
        headSet = true;
        hax = bx; hay = by; haz = bz;
        hbx = tx; hby = ty; hbz = tz;
    }

    /** Forgets every point: it starts again at its next move. */
    public void clear() {
        first = count = 0;
        started = false;
    }

    /** Whether it has stopped emitting and every point has run out. */
    public boolean done() {
        return !emitting && count == 0;
    }

    public Mode mode() {
        return mode;
    }

    public float life() {
        return life;
    }

    public int subdivisions() {
        return subdivisions;
    }

    /** Points kept, the head not counted. */
    public int count() {
        return count;
    }

    private int at(int i) {
        return (first + i) % capacity;
    }

    private void grow(int n) {
        ax = new double[n]; ay = new double[n]; az = new double[n];
        bx = new double[n]; by = new double[n]; bz = new double[n];
        born = new double[n];
        along = new double[n];
        extra = new float[n * 4];
    }

    private static double dist(double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether there is anything to draw: a head and at least one point behind it. */
    boolean drawable() {
        return started && count > 0 || !emitting && count >= 2;
    }

    /**
     * Writes its head at frame position {@code alpha} between its last two ticks, then its points newest first, each
     * from {@code (anchorX, anchorY, anchorZ)}: what {@link CgVfxTrailTexture#add} uploads. Answers the points written;
     * fills {@code out[0..6]} with the bounds of what it wrote, from the anchor, out[6] with the head's distance along
     * and out[7] with the oldest point's.
     */
    int write(CgVfxTrailTexture.Sink sink, float alpha, double anchorX, double anchorY, double anchorZ, float[] out) {
        double now = time - (1.0 - alpha) * lastDt;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        int written = 0;
        double headAlong = 0, oldestAlong = 0;
        if (emitting && started) {
            double hx, hy, hz, tx, ty, tz;
            if (headSet) {
                hx = hax; hy = hay; hz = haz;
                tx = hbx; ty = hby; tz = hbz;
            } else {
                hx = pax + (cax - pax) * alpha; hy = pay + (cay - pay) * alpha; hz = paz + (caz - paz) * alpha;
                tx = pbx + (cbx - pbx) * alpha; ty = pby + (cby - pby) * alpha; tz = pbz + (cbz - pbz) * alpha;
            }
            int last = at(count - 1);
            headAlong = count > 0 ? along[last] + dist(ax[last], ay[last], az[last], hx, hy, hz) : prevAlong;
            float fx = (float) (hx - anchorX), fy = (float) (hy - anchorY), fz = (float) (hz - anchorZ);
            float gx, gy, gz;
            if (mode == Mode.CAMERA) {
                gx = gy = gz = 0f;
            } else if (mode == Mode.AXIS) {
                gx = nx; gy = ny; gz = nz;
            } else {
                gx = (float) (tx - anchorX); gy = (float) (ty - anchorY); gz = (float) (tz - anchorZ);
            }
            sink.point(fx, fy, fz, 0f, gx, gy, gz, (float) headAlong, width, intensity, user0, user1);
            written++;
            minX = Math.min(minX, fx); maxX = Math.max(maxX, fx);
            minY = Math.min(minY, fy); maxY = Math.max(maxY, fy);
            minZ = Math.min(minZ, fz); maxZ = Math.max(maxZ, fz);
            if (mode == Mode.BLADE) {
                minX = Math.min(minX, gx); maxX = Math.max(maxX, gx);
                minY = Math.min(minY, gy); maxY = Math.max(maxY, gy);
                minZ = Math.min(minZ, gz); maxZ = Math.max(maxZ, gz);
            }
        } else if (count > 0) {
            headAlong = along[at(count - 1)];
        }
        for (int k = count - 1; k >= 0; k--) {
            int i = at(k);
            float fx = (float) (ax[i] - anchorX), fy = (float) (ay[i] - anchorY), fz = (float) (az[i] - anchorZ);
            float gx = (float) (mode == Mode.BLADE ? bx[i] - anchorX : bx[i]);
            float gy = (float) (mode == Mode.BLADE ? by[i] - anchorY : by[i]);
            float gz = (float) (mode == Mode.BLADE ? bz[i] - anchorZ : bz[i]);
            sink.point(fx, fy, fz, (float) Math.max(now - born[i], 0.0), gx, gy, gz, (float) along[i],
                    extra[i * 4], extra[i * 4 + 1], extra[i * 4 + 2], extra[i * 4 + 3]);
            oldestAlong = along[i];
            written++;
            minX = Math.min(minX, fx); maxX = Math.max(maxX, fx);
            minY = Math.min(minY, fy); maxY = Math.max(maxY, fy);
            minZ = Math.min(minZ, fz); maxZ = Math.max(maxZ, fz);
            if (mode == Mode.BLADE) {
                minX = Math.min(minX, gx); maxX = Math.max(maxX, gx);
                minY = Math.min(minY, gy); maxY = Math.max(maxY, gy);
                minZ = Math.min(minZ, gz); maxZ = Math.max(maxZ, gz);
            }
        }
        out[0] = minX; out[1] = minY; out[2] = minZ;
        out[3] = maxX; out[4] = maxY; out[5] = maxZ;
        out[6] = (float) headAlong;
        out[7] = (float) oldestAlong;
        return written;
    }

    /** Where its points are measured from this frame, in world coordinates: its head, or its newest point once it stops. */
    public double anchorX() {
        return emitting && started ? cax : count > 0 ? ax[at(count - 1)] : cax;
    }

    public double anchorY() {
        return emitting && started ? cay : count > 0 ? ay[at(count - 1)] : cay;
    }

    public double anchorZ() {
        return emitting && started ? caz : count > 0 ? az[at(count - 1)] : caz;
    }

    /** The widest any point is drawn, times the layer's size: what its bounds reach past its points. */
    public float widest() {
        float w = emitting ? width : 0f;
        for (int k = 0; k < count; k++) w = Math.max(w, extra[at(k) * 4]);
        return w;
    }
}
