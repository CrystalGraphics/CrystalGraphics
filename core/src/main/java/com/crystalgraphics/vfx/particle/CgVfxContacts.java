package com.crystalgraphics.vfx.particle;

/**
 * The CPU side of {@code sim/fx_contact.glsl}, term for term: a {@link CgVfxModule.Volume}'s reach, inside test and
 * contact, and the rigid collision response.
 *
 * <p>Ported from Godot Engine (MIT, © 2014-present Godot Engine contributors): the attractor falloff and the sphere and
 * box colliders of {@code servers/rendering/renderer_rd/shaders/particles.glsl}, and the rigid response of
 * {@code scene/resources/particle_process_material.cpp}. The plane, the container modes and the guard on a particle
 * already leaving are this project's.</p>
 */
final class CgVfxContacts {

    /** Godot's: a particle just touching counts. */
    static final float EPSILON = 0.001f;

    private CgVfxContacts() {
    }

    /** Godot's attractor falloff: 0 at the centre, 1 at the edge, past 1 outside. A plane reaches nowhere. */
    static float reach(CgVfxModule.Volume v, float rx, float ry, float rz) {
        return switch (v.form()) {
            case SPHERE -> (float) Math.sqrt(rx * rx + ry * ry + rz * rz) / v.ex();
            case BOX -> Math.max(Math.abs(rx / v.ex()), Math.max(Math.abs(ry / v.ey()), Math.abs(rz / v.ez())));
            case PLANE -> 2f;
        };
    }

    /** bevy_hanabi's kill test: whether a point is inside, a plane's inside being behind its normal. */
    static boolean inside(CgVfxModule.Volume v, float rx, float ry, float rz) {
        return switch (v.form()) {
            case SPHERE -> rx * rx + ry * ry + rz * rz < v.ex() * v.ex();
            case BOX -> Math.abs(rx) < v.ex() && Math.abs(ry) < v.ey() && Math.abs(rz) < v.ez();
            case PLANE -> rx * v.ex() + ry * v.ey() + rz * v.ez() < 0f;
        };
    }

    /**
     * Godot's collider: whether a particle of radius {@code size} at {@code r} touches the volume, or, a
     * {@code container}, its inside wall. The normal out of the surface into {@code n}; answers the depth, NaN for none.
     */
    static float contact(CgVfxModule.Volume v, boolean container, float rx, float ry, float rz, float size, float[] n) {
        switch (v.form()) {
            case SPHERE -> {
                float r = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
                float d = container ? v.ex() - size - r : r - (size + v.ex());
                if (d > EPSILON || r == 0f) return Float.NaN;
                float s = container ? -1f : 1f;
                n[0] = rx / r * s;
                n[1] = ry / r * s;
                n[2] = rz / r * s;
                return -d;
            }
            case BOX -> {
                float ax = Math.abs(rx), ay = Math.abs(ry), az = Math.abs(rz);
                float sx = Math.signum(rx), sy = Math.signum(ry), sz = Math.signum(rz);
                if (container) {
                    float ox = ax + size - v.ex(), oy = ay + size - v.ey(), oz = az + size - v.ez();
                    int axis = ox >= oy && ox >= oz ? 0 : oy >= oz ? 1 : 2;
                    float over = axis == 0 ? ox : axis == 1 ? oy : oz;
                    if (over < -EPSILON) return Float.NaN;
                    n[0] = axis == 0 ? -(sx == 0f ? 1f : sx) : 0f;
                    n[1] = axis == 1 ? -(sy == 0f ? 1f : sy) : 0f;
                    n[2] = axis == 2 ? -(sz == 0f ? 1f : sz) : 0f;
                    return over;
                }
                if (ax > v.ex() || ay > v.ey() || az > v.ez()) {
                    float cx = ax - Math.min(ax, v.ex()), cy = ay - Math.min(ay, v.ey()), cz = az - Math.min(az, v.ez());
                    float length = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                    float d = length - size;
                    if (d > EPSILON) return Float.NaN;
                    n[0] = cx / length * sx;
                    n[1] = cy / length * sy;
                    n[2] = cz / length * sz;
                    return -d;
                }
                float lx = v.ex() - ax, ly = v.ey() - ay, lz = v.ez() - az;
                if (lx < ly && lx < lz) {
                    n[0] = sx;
                    n[1] = n[2] = 0f;
                    return lx + size;
                }
                if (ly < lx && ly < lz) {
                    n[1] = sy;
                    n[0] = n[2] = 0f;
                    return ly + size;
                }
                n[2] = sz;
                n[0] = n[1] = 0f;
                return lz + size;
            }
            default -> {
                float s = container ? -1f : 1f;
                float d = (rx * v.ex() + ry * v.ey() + rz * v.ez()) * s - size;
                if (d > EPSILON) return Float.NaN;
                n[0] = v.ex() * s;
                n[1] = v.ey() * s;
                n[2] = v.ez() * s;
                return -d;
            }
        }
    }

    /**
     * Godot's rigid response to a contact on particle {@code i}: pushed out by {@code depth}, the speed into the surface
     * taken away, {@code friction} of the rest, and a bounce of {@code bounce} once the impact is fast enough
     * ({@code slide_to_bounce_trigger}), which is the punctual hit a collision event fires on. Slower than {@code rest}
     * after it, the particle rests. With {@code kill} it is Godot's hide on contact: a hit, then death, on the surface
     * so what the hit spawns starts there.
     */
    static void respond(CgVfxParticleSet p, int i, float nx, float ny, float nz, float depth, float bounce, float friction,
                        float rest, boolean kill) {
        p.x[i] += nx * depth;
        p.y[i] += ny * depth;
        p.z[i] += nz * depth;
        if (kill) {
            p.hit(i, nx, ny, nz);
            p.life[i] = p.age[i];
            return;
        }
        float response = nx * p.vx[i] + ny * p.vy[i] + nz * p.vz[i];
        if (response >= 0f) return;
        float trigger = -response < 2f / Math.max(1f, Math.min(bounce + 1f, 2f)) ? 0f : 1f;
        float keep = 1f - Math.max(0f, Math.min(friction, 1f));
        float bounced = bounce * trigger;
        float vx = (p.vx[i] - nx * response) * keep, vy = (p.vy[i] - ny * response) * keep, vz = (p.vz[i] - nz * response) * keep;
        vx -= nx * response * bounced;
        vy -= ny * response * bounced;
        vz -= nz * response * bounced;
        p.vx[i] = vx;
        p.vy[i] = vy;
        p.vz[i] = vz;
        if (trigger > 0f) p.hit(i, nx, ny, nz);
        if ((float) Math.sqrt(vx * vx + vy * vy + vz * vz) < rest) {
            p.vx[i] = p.vy[i] = p.vz[i] = 0f;
            p.spinRate[i] = 0f;
            p.resting[i] = 1f;
        }
    }

    /** GLSL's {@code length(v) > 0 ? v / length(v) : 0}, into {@code out}. */
    static void safeNormalize(float x, float y, float z, float[] out) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length > 0f) {
            out[0] = x / length;
            out[1] = y / length;
            out[2] = z / length;
        } else {
            out[0] = out[1] = out[2] = 0f;
        }
    }
}
