package com.crystalgraphics.vfx.sim;

/**
 * A stream of samples emitted from a muzzle and flown outward, like water from a hose: the centreline of a beam that
 * bends when its source turns and arcs toward what it homes on.
 *
 * <pre>{@code
 * CgVfxStream stream = new CgVfxStream();
 * // each tick:
 * stream.emit(x, y, z, dirX, dirY, dirZ, speed);       // while the source is firing
 * stream.target(tx, ty, tz);                           // NaN for none
 * stream.tick(dt, turnRate, navigation, maxLength);     // homing; or, steered by a player's aim:
 * stream.steer(dt, x, y, z, dirX, dirY, dirZ, steerRate, maxLength);
 * // each frame:
 * int n = stream.points(out, alpha * dt, x, y, z, firing);  // muzzle first, then the samples, then the impact
 * path.build(out, n, spacing);
 * }</pre>
 *
 * <ul>
 *   <li>Every sample homes on the target (or the waypoint until it passes it) by proportional navigation, turning at
 *       most {@code turnRate} radians a second: with the aim still they all trace one smooth curve into the target, and a
 *       moving aim sends a wave down the body.</li>
 *   <li>Steered instead, the body is a curve leaving the muzzle along the aim into an end that chases the target,
 *       turning at most {@code steerRate} radians a second about the muzzle: a flick bows the body, which straightens as
 *       the end catches up. The head flies out along it, and once it hits the body stretches with the curve.</li>
 *   <li>A sample reaching the target, or passing close by it, is consumed there and the stream reports an impact; one
 *       flown {@code maxLength} blocks dies.</li>
 *   <li>Positions are relative to the owning effect's origin. Nothing allocates once the ring has grown.</li>
 * </ul>
 */
public final class CgVfxStream {

    private static final int FLOATS = 8;   // position, velocity, distance flown, waypoint passed

    private float[] samples = new float[256 * FLOATS];
    private int head, size;
    private float targetX = Float.NaN, targetY, targetZ;
    private float viaX = Float.NaN, viaY, viaZ;
    private float impactX, impactY, impactZ;
    private int sinceImpact = Integer.MAX_VALUE;
    private boolean viaReached;

    /** Steered flight's curve, sampled evenly in its parameter: xyz and the length to each point. */
    private static final int CURVE_POINTS = 33;
    /** How fast a steered end closes on the target, per second, under the turn cap: its lag. */
    private static final float END_EASE = 12f;
    private final float[] curve = new float[CURVE_POINTS * 4];
    /** The steered end, as a direction and a distance from the muzzle; and the curve's length at the last step. */
    private float endDirX, endDirY, endDirZ, endDistance, curveLength;
    private boolean endSet;

    /** Emits one sample at the muzzle, flying along {@code (dirX, dirY, dirZ)} (any length) at {@code speed}. */
    public void emit(float x, float y, float z, float dirX, float dirY, float dirZ, float speed) {
        float inv = speed / Math.max((float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ), 1.0e-6f);
        if (size * FLOATS == samples.length) grow();
        int o = ((head + size) % capacity()) * FLOATS;
        samples[o] = x;
        samples[o + 1] = y;
        samples[o + 2] = z;
        samples[o + 3] = dirX * inv;
        samples[o + 4] = dirY * inv;
        samples[o + 5] = dirZ * inv;
        samples[o + 6] = 0f;
        samples[o + 7] = Float.isNaN(viaX) ? 1f : 0f;
        size++;
    }

    /** Where every sample homes; NaN for straight flight. */
    public void target(float x, float y, float z) {
        targetX = x;
        targetY = y;
        targetZ = z;
    }

    /** A point the samples pass on their way to the target, as the Bending Kamehameha does; NaN for none. */
    public void via(float x, float y, float z) {
        viaX = x;
        viaY = y;
        viaZ = z;
    }

    /**
     * Flies every sample on by {@code dt} seconds, homing by proportional navigation: each turns {@code navigation}
     * times as fast as its line of sight to the goal rotates, never more than {@code turnRate} radians a second. 3
     * curves smoothly into a still target; more turns harder early and runs straighter at the end.
     */
    public void tick(float dt, float turnRate, float navigation, float maxLength) {
        if (sinceImpact < Integer.MAX_VALUE) sinceImpact++;
        int cap = capacity();
        for (int k = 0; k < size; k++) {
            int o = ((head + k) % cap) * FLOATS;
            if (samples[o + 6] < 0f) continue;
            boolean viaFirst = samples[o + 7] == 0f && !Float.isNaN(viaX);
            float gx = viaFirst ? viaX : targetX, gy = viaFirst ? viaY : targetY, gz = viaFirst ? viaZ : targetZ;
            float speed = (float) Math.sqrt(sq(samples[o + 3]) + sq(samples[o + 4]) + sq(samples[o + 5]));
            float step = speed * dt;
            if (!Float.isNaN(gx)) {
                float dx = gx - samples[o], dy = gy - samples[o + 1], dz = gz - samples[o + 2];
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float closing = (samples[o + 3] * dx + samples[o + 4] * dy + samples[o + 5] * dz) / Math.max(speed * distance, 1.0e-6f);
                // Reached, or passed close by: either way it ends here rather than flying on and doubling back.
                if (distance <= step || (closing <= 0f && distance <= 4f * step)) {
                    if (viaFirst) {
                        samples[o + 7] = 1f;
                        viaReached = true;
                    } else {
                        impactX = gx;
                        impactY = gy;
                        impactZ = gz;
                        sinceImpact = 0;
                        samples[o + 6] = -1f;
                        continue;
                    }
                } else {
                    float sight = speed * (float) Math.sqrt(Math.max(0f, 1f - closing * closing)) / distance;
                    float rate = closing > 0f ? Math.min(turnRate, navigation * sight) : turnRate;
                    steer(o, gx, gy, gz, speed, rate * dt);
                }
            }
            samples[o] += samples[o + 3] * dt;
            samples[o + 1] += samples[o + 4] * dt;
            samples[o + 2] += samples[o + 5] * dt;
            samples[o + 6] += step;
            if (samples[o + 6] > maxLength) samples[o + 6] = -1f;
        }
        while (size > 0 && samples[head * FLOATS + 6] < 0f) {
            head = (head + 1) % cap;
            size--;
        }
    }

    /**
     * Flies every sample on by {@code dt} seconds along the steered curve: from the muzzle along {@code (dirX, dirY,
     * dirZ)} (any length) into the end, which turns toward the target at most {@code steerRate} radians a second and
     * eases onto it. With no target the end is {@code maxLength} along the aim and nothing hits. The waypoint is not
     * used.
     */
    public void steer(float dt, float muzzleX, float muzzleY, float muzzleZ, float dirX, float dirY, float dirZ,
                      float steerRate, float maxLength) {
        if (sinceImpact < Integer.MAX_VALUE) sinceImpact++;
        float aimLength = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (aimLength < 1.0e-6f) return;
        float ax = dirX / aimLength, ay = dirY / aimLength, az = dirZ / aimLength;
        boolean hits = !Float.isNaN(targetX);
        float wantX = ax, wantY = ay, wantZ = az, want = maxLength;
        if (hits) {
            float dx = targetX - muzzleX, dy = targetY - muzzleY, dz = targetZ - muzzleZ;
            want = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (want > 1.0e-4f) {
                wantX = dx / want;
                wantY = dy / want;
                wantZ = dz / want;
            }
        }
        // Nothing in flight: the end waits on the target, so the beam launches straight at it.
        if (!endSet || size == 0) {
            endDirX = wantX;
            endDirY = wantY;
            endDirZ = wantZ;
            endDistance = want;
            endSet = true;
        } else {
            float ease = 1f - (float) Math.exp(-END_EASE * dt);
            float cos = Math.max(-1f, Math.min(1f, endDirX * wantX + endDirY * wantY + endDirZ * wantZ));
            float angle = (float) Math.acos(cos);
            if (angle > 1.0e-5f) {
                float t = Math.min(steerRate * dt, angle * ease) / angle;
                float sin = (float) Math.sin(angle);
                float a = sin < 1.0e-4f ? 1f - t : (float) Math.sin((1f - t) * angle) / sin;
                float b = sin < 1.0e-4f ? t : (float) Math.sin(t * angle) / sin;
                float nx = endDirX * a + wantX * b, ny = endDirY * a + wantY * b, nz = endDirZ * a + wantZ * b;
                float inv = 1f / Math.max((float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1.0e-6f);
                endDirX = nx * inv;
                endDirY = ny * inv;
                endDirZ = nz * inv;
            }
            endDistance += (want - endDistance) * ease;
        }
        float ex = muzzleX + endDirX * endDistance, ey = muzzleY + endDirY * endDistance, ez = muzzleZ + endDirZ * endDistance;
        buildCurve(muzzleX, muzzleY, muzzleZ, ax, ay, az, ex, ey, ez);
        float length = curve[(CURVE_POINTS - 1) * 4 + 3];
        // Once it hits, the body is joined to the end: it stretches and shrinks with the curve rather than parting from it.
        float stretch = impacting() && curveLength > 1.0e-4f ? length / curveLength : 1f;
        curveLength = length;

        int cap = capacity(), j = 0;
        for (int k = size - 1; k >= 0; k--) {
            int o = ((head + k) % cap) * FLOATS;
            if (samples[o + 6] < 0f) continue;
            float speed = (float) Math.sqrt(sq(samples[o + 3]) + sq(samples[o + 4]) + sq(samples[o + 5]));
            float s = samples[o + 6] * stretch + speed * dt;
            if (hits && s >= length) {
                impactX = ex;
                impactY = ey;
                impactZ = ez;
                sinceImpact = 0;
                samples[o + 6] = -1f;
                continue;
            }
            if (s > maxLength) {
                samples[o + 6] = -1f;
                continue;
            }
            samples[o + 6] = s;
            if (s < curve[j * 4 + 3]) j = 0;
            while (j < CURVE_POINTS - 2 && curve[(j + 1) * 4 + 3] < s) j++;
            int c = j * 4, n = c + 4;
            float span = curve[n + 3] - curve[c + 3];
            float f = span > 1.0e-6f ? Math.min(1f, (s - curve[c + 3]) / span) : 0f;
            float tx = curve[n] - curve[c], ty = curve[n + 1] - curve[c + 1], tz = curve[n + 2] - curve[c + 2];
            samples[o] = curve[c] + tx * f;
            samples[o + 1] = curve[c + 1] + ty * f;
            samples[o + 2] = curve[c + 2] + tz * f;
            float inv = speed / Math.max(span, 1.0e-6f);
            samples[o + 3] = tx * inv;
            samples[o + 4] = ty * inv;
            samples[o + 5] = tz * inv;
        }
        // The impact rides the end while it hits, not where the last sample landed.
        if (hits && impacting()) {
            impactX = ex;
            impactY = ey;
            impactZ = ez;
        }
        while (size > 0 && samples[head * FLOATS + 6] < 0f) {
            head = (head + 1) % cap;
            size--;
        }
    }

    /**
     * The steered curve: a cubic leaving the muzzle along the aim and arriving along the line from the muzzle to the
     * end, so it is straight when the two agree.
     */
    private void buildCurve(float px, float py, float pz, float ax, float ay, float az, float ex, float ey, float ez) {
        float d = endDistance;
        float p1x = px + ax * d * 0.4f, p1y = py + ay * d * 0.4f, p1z = pz + az * d * 0.4f;
        float p2x = ex - endDirX * d * 0.3f, p2y = ey - endDirY * d * 0.3f, p2z = ez - endDirZ * d * 0.3f;
        float length = 0f, lx = px, ly = py, lz = pz;
        for (int i = 0; i < CURVE_POINTS; i++) {
            float t = i / (float) (CURVE_POINTS - 1), u = 1f - t;
            float b0 = u * u * u, b1 = 3f * u * u * t, b2 = 3f * u * t * t, b3 = t * t * t;
            float x = b0 * px + b1 * p1x + b2 * p2x + b3 * ex;
            float y = b0 * py + b1 * p1y + b2 * p2y + b3 * ey;
            float z = b0 * pz + b1 * p1z + b2 * p2z + b3 * ez;
            length += (float) Math.sqrt(sq(x - lx) + sq(y - ly) + sq(z - lz));
            curve[i * 4] = x;
            curve[i * 4 + 1] = y;
            curve[i * 4 + 2] = z;
            curve[i * 4 + 3] = length;
            lx = x;
            ly = y;
            lz = z;
        }
    }

    /**
     * The centreline, muzzle first, into {@code out} as xyz triples: the muzzle while {@code firing}, each live sample
     * moved on by {@code ahead} seconds, then the impact while the head is at it. Answers the point count.
     */
    public int points(float[] out, float ahead, float muzzleX, float muzzleY, float muzzleZ, boolean firing) {
        int n = 0;
        if (firing) n = put(out, n, muzzleX, muzzleY, muzzleZ);
        int cap = capacity();
        for (int k = size - 1; k >= 0; k--) {
            int o = ((head + k) % cap) * FLOATS;
            if (samples[o + 6] < 0f) continue;
            if (n * 3 + 3 > out.length) break;
            n = put(out, n, samples[o] + samples[o + 3] * ahead, samples[o + 1] + samples[o + 4] * ahead,
                    samples[o + 2] + samples[o + 5] * ahead);
        }
        if (impacting() && n * 3 + 3 <= out.length) n = put(out, n, impactX, impactY, impactZ);
        return n;
    }

    /** Whether the head is at the target: a sample reached it within the last two ticks. */
    public boolean impacting() {
        return sinceImpact <= 2;
    }

    public float impactX() { return impactX; }

    public float impactY() { return impactY; }

    public float impactZ() { return impactZ; }

    /** Whether any sample has passed the waypoint yet. */
    public boolean viaReached() {
        return viaReached;
    }

    /** The oldest live sample, the head, as it stood at the last tick; meaningless when {@link #size} is 0. */
    public float headX() { return samples[head * FLOATS]; }

    public float headY() { return samples[head * FLOATS + 1]; }

    public float headZ() { return samples[head * FLOATS + 2]; }

    /** Live samples, the most {@link #points} can give besides the muzzle and the impact. */
    public int size() {
        return size;
    }

    public void clear() {
        head = 0;
        size = 0;
        sinceImpact = Integer.MAX_VALUE;
        viaReached = false;
        endSet = false;
    }

    /** Turns sample {@code o}'s velocity toward the goal by at most {@code maxTurn} radians, keeping its speed. */
    private void steer(int o, float gx, float gy, float gz, float speed, float maxTurn) {
        float dx = gx - samples[o], dy = gy - samples[o + 1], dz = gz - samples[o + 2];
        float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0e-5f || speed < 1.0e-5f) return;
        dx /= dl;
        dy /= dl;
        dz /= dl;
        float vx = samples[o + 3] / speed, vy = samples[o + 4] / speed, vz = samples[o + 5] / speed;
        float cos = Math.max(-1f, Math.min(1f, vx * dx + vy * dy + vz * dz));
        float angle = (float) Math.acos(cos);
        if (angle < 1.0e-5f) return;
        float t = Math.min(1f, maxTurn / angle);
        // Slerp between the two directions.
        float sin = (float) Math.sin(angle);
        float a = (float) Math.sin((1f - t) * angle) / sin, b = (float) Math.sin(t * angle) / sin;
        if (sin < 1.0e-4f) {
            a = 1f - t;
            b = t;
        }
        float nx = vx * a + dx * b, ny = vy * a + dy * b, nz = vz * a + dz * b;
        float inv = speed / Math.max((float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1.0e-6f);
        samples[o + 3] = nx * inv;
        samples[o + 4] = ny * inv;
        samples[o + 5] = nz * inv;
    }

    private int capacity() {
        return samples.length / FLOATS;
    }

    private void grow() {
        int cap = capacity();
        float[] next = new float[samples.length * 2];
        for (int k = 0; k < size; k++) {
            System.arraycopy(samples, ((head + k) % cap) * FLOATS, next, k * FLOATS, FLOATS);
        }
        samples = next;
        head = 0;
    }

    private static int put(float[] out, int n, float x, float y, float z) {
        out[n * 3] = x;
        out[n * 3 + 1] = y;
        out[n * 3 + 2] = z;
        return n + 1;
    }

    private static float sq(float x) {
        return x * x;
    }
}
