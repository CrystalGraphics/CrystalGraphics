package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.util.Objects;

/**
 * The CPU side of {@code sim/fx_contact.glsl}, term for term: a module's shape (a {@link CgSphere}, {@link CgBox} or
 * {@link CgPlane}) as the GPU reads it, its reach, inside test and contact, and the rigid collision response.
 *
 * <p>Ported from Godot Engine (MIT, © 2014-present Godot Engine contributors): the attractor falloff and the sphere and
 * box colliders of {@code servers/rendering/renderer_rd/shaders/particles.glsl}, and the rigid response of
 * {@code scene/resources/particle_process_material.cpp}. The plane, the container modes and the guard on a particle
 * already leaving are this project's.</p>
 */
final class CgVfxContacts {

    /** Godot's: a particle just touching counts. */
    static final float EPSILON = 0.001f;
    /** {@code FX_SPHERE}, {@code FX_BOX} and {@code FX_PLANE}. */
    private static final float SPHERE = 0f, BOX = 1f, PLANE = 2f;

    private CgVfxContacts() {
    }

    /** {@code shape}, which a module can take only as a sphere, box or plane. */
    static CgShape check(CgShape shape) {
        Objects.requireNonNull(shape, "shape");
        if (!(shape instanceof CgSphere || shape instanceof CgBox || shape instanceof CgPlane)) {
            throw new IllegalArgumentException("a particle module takes a sphere, box or plane, not " + shape);
        }
        return shape;
    }

    /** The volume vec4: a sphere's radius in x, a box's half extents or a plane's unit normal, and the form in w. */
    static void writeShape(CgShape s, CgVfxWords out) {
        if (s instanceof CgSphere sphere) {
            float r = (float) sphere.radius();
            out.vec4(r, r, r, SPHERE);
        } else if (s instanceof CgBox box) {
            out.vec4((float) box.halfX(), (float) box.halfY(), (float) box.halfZ(), BOX);
        } else {
            CgPlane plane = (CgPlane) s;
            out.vec4((float) plane.nx(), (float) plane.ny(), (float) plane.nz(), PLANE);
        }
    }

    /** The centre vec4: {@code (x, y, z)} from the instance's source, and a plane's radius in w, 0 for the others. */
    static void writeCentre(CgShape s, float x, float y, float z, CgVfxInstanceView instance, CgVfxWords out) {
        float radius = s instanceof CgPlane plane ? (float) plane.radius() : 0f;
        out.vec4(instance.sourceX() + x, instance.sourceY() + y, instance.sourceZ() + z, radius);
    }

    /** Godot's attractor falloff: 0 at the centre, 1 at the edge, past 1 outside. A plane reaches nowhere. */
    static float reach(CgShape s, float rx, float ry, float rz) {
        if (s instanceof CgSphere sphere) return (float) Math.sqrt(rx * rx + ry * ry + rz * rz) / (float) sphere.radius();
        if (s instanceof CgBox box) {
            return Math.max(Math.abs(rx / (float) box.halfX()),
                    Math.max(Math.abs(ry / (float) box.halfY()), Math.abs(rz / (float) box.halfZ())));
        }
        return 2f;
    }

    /** bevy_hanabi's kill test: whether a point is inside, a plane's inside being behind its normal. */
    static boolean inside(CgShape s, float rx, float ry, float rz) {
        if (s instanceof CgSphere sphere) {
            float r = (float) sphere.radius();
            return rx * rx + ry * ry + rz * rz < r * r;
        }
        if (s instanceof CgBox box) {
            return Math.abs(rx) < (float) box.halfX() && Math.abs(ry) < (float) box.halfY() && Math.abs(rz) < (float) box.halfZ();
        }
        CgPlane plane = (CgPlane) s;
        return rx * (float) plane.nx() + ry * (float) plane.ny() + rz * (float) plane.nz() < 0f;
    }

    /** Whether {@code (rx, ry, rz)} from the centre is over the shape: always, but past a plane's radius. */
    static boolean over(CgShape s, float rx, float ry, float rz) {
        if (!(s instanceof CgPlane plane)) return true;
        float nx = (float) plane.nx(), ny = (float) plane.ny(), nz = (float) plane.nz(), r = (float) plane.radius();
        float d = rx * nx + ry * ny + rz * nz;
        float tx = rx - nx * d, ty = ry - ny * d, tz = rz - nz * d;
        return tx * tx + ty * ty + tz * tz <= r * r;
    }

    /**
     * Godot's collider: whether a particle of radius {@code size} at {@code r} touches the volume, or, a
     * {@code container}, its inside wall. The normal out of the surface into {@code n}; answers the depth, NaN for none.
     */
    static float contact(CgShape s, boolean container, float rx, float ry, float rz, float size, float[] n) {
        if (s instanceof CgSphere sphere) {
            float radius = (float) sphere.radius();
            float r = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            float d = container ? radius - size - r : r - (size + radius);
            if (d > EPSILON || r == 0f) return Float.NaN;
            float side = container ? -1f : 1f;
            n[0] = rx / r * side;
            n[1] = ry / r * side;
            n[2] = rz / r * side;
            return -d;
        }
        if (s instanceof CgBox box) {
            float ex = (float) box.halfX(), ey = (float) box.halfY(), ez = (float) box.halfZ();
            float ax = Math.abs(rx), ay = Math.abs(ry), az = Math.abs(rz);
            float sx = Math.signum(rx), sy = Math.signum(ry), sz = Math.signum(rz);
            if (container) {
                float ox = ax + size - ex, oy = ay + size - ey, oz = az + size - ez;
                int axis = ox >= oy && ox >= oz ? 0 : oy >= oz ? 1 : 2;
                float over = axis == 0 ? ox : axis == 1 ? oy : oz;
                if (over < -EPSILON) return Float.NaN;
                n[0] = axis == 0 ? -(sx == 0f ? 1f : sx) : 0f;
                n[1] = axis == 1 ? -(sy == 0f ? 1f : sy) : 0f;
                n[2] = axis == 2 ? -(sz == 0f ? 1f : sz) : 0f;
                return over;
            }
            if (ax > ex || ay > ey || az > ez) {
                float cx = ax - Math.min(ax, ex), cy = ay - Math.min(ay, ey), cz = az - Math.min(az, ez);
                float length = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                float d = length - size;
                if (d > EPSILON) return Float.NaN;
                n[0] = cx / length * sx;
                n[1] = cy / length * sy;
                n[2] = cz / length * sz;
                return -d;
            }
            float lx = ex - ax, ly = ey - ay, lz = ez - az;
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
        CgPlane plane = (CgPlane) s;
        float nx = (float) plane.nx(), ny = (float) plane.ny(), nz = (float) plane.nz();
        float side = container ? -1f : 1f;
        float d = (rx * nx + ry * ny + rz * nz) * side - size;
        if (d > EPSILON) return Float.NaN;
        n[0] = nx * side;
        n[1] = ny * side;
        n[2] = nz * side;
        return -d;
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
