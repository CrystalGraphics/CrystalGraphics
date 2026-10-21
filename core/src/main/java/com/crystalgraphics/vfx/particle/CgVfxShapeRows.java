package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.util.Objects;

/**
 * A module's shape as {@code sim/fx_contact.glsl} reads it: a {@link CgSphere}, {@link CgBox} or {@link CgPlane}. Its
 * volume vec4 is declared ({@code CgVfxModuleTypes}: {@code volume} then {@code shape}); its centre is the instance lane
 * written here.
 */
final class CgVfxShapeRows {

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

    /** The centre vec4: {@code (x, y, z)} from the instance's source, and a plane's radius in w, 0 for the others. */
    static void writeCentre(CgShape s, float x, float y, float z, CgVfxInstanceView instance, CgVfxWords out) {
        float radius = s instanceof CgPlane plane ? (float) plane.radius() : 0f;
        out.vec4(instance.sourceX() + x, instance.sourceY() + y, instance.sourceZ() + z, radius);
    }
}
