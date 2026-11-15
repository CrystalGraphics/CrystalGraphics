package com.crystalgraphics.animation.rig;

import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * How a model library describes its models to a rig: which way a model faces, and which bones only rotate. A rig names
 * its adapter; retargeting reads both sides' adapters to put the two models in one frame.
 *
 * <pre>{@code
 * // a library whose models face -x and whose bones never move off their pivots
 * public final class MyLibraryAdapter implements CgRigAdapter {
 *     public String name() { return "mymod:mylib"; }
 *     public Vector3fc facing() { return new Vector3f(-1f, 0f, 0f); }
 *     public boolean rotationOnly(CgSkeleton skeleton, int bone) { return true; }
 * }
 * CgRigAdapters.register(new MyLibraryAdapter());
 * }</pre>
 *
 * <ul>
 *   <li>{@code facing} is in the model's own space, the way its front looks with no clip playing; up is +y.</li>
 *   <li>A retarget chain's translation on a rotation-only bone is refused when the retargeter is built, naming it.</li>
 * </ul>
 */
public interface CgRigAdapter {

    /** Its name, as a rig file's {@code adapter} names it. */
    String name();

    /** Which way the model's front faces in its own space, up being +y. */
    Vector3fc facing();

    /** Whether {@code bone} only rotates about its pivot, never moving. */
    default boolean rotationOnly(CgSkeleton skeleton, int bone) {
        return false;
    }

    /** An adapter of {@code name} whose models face {@code facing}, with no bone limits. */
    static CgRigAdapter of(String name, Vector3fc facing) {
        Vector3f f = new Vector3f(facing).normalize();
        return new CgRigAdapter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Vector3fc facing() {
                return f;
            }
        };
    }
}
