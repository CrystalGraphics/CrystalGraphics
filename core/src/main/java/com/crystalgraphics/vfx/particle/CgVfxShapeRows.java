package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.util.Objects;

/**
 * A module's shape as {@code sim/fx_contact.glsl} reads it: a {@link CgSphere}, {@link CgBox} or {@link CgPlane}, as
 * the volume vec4 and the centre vec4.
 */
final class CgVfxShapeRows {

    /** {@code FX_SPHERE}, {@code FX_BOX} and {@code FX_PLANE}. */
    private static final float SPHERE = 0f, BOX = 1f, PLANE = 2f;

    private CgVfxShapeRows() {
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
}
