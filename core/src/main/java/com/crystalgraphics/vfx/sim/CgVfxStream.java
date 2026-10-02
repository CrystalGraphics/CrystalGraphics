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
 * stream.tick(dt, turnRate, navigation, maxLength);
 * // each frame:
 * int n = stream.points(out, alpha * dt, x, y, z, firing);  // muzzle first, then the samples, then the impact
 * path.build(out, n, spacing);
 * }</pre>
 *
 * <ul>
 *   <li>Every sample homes on the target (or the waypoint until it passes it) by proportional navigation, turning at
 *       most {@code turnRate} radians a second: with the aim still they all trace one smooth curve into the target, and a
 *       moving aim sends a wave down the body.</li>
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
