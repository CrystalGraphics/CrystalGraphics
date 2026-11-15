package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.rig.CgRigAdapter;
import com.crystalgraphics.animation.rig.CgRigAdapters;
import com.crystalgraphics.animation.rig.CgRigs;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The demo's rigs: each cast's default rig, found from its bones, and its chains drawn over a body as lines, a colour a
 * kind of chain.
 *
 * <pre>{@code
 * AnimDemoRigs.Drawn rig = AnimDemoRigs.of(cast);
 * if (rig != null) rig.draw(body);          // each frame it should show
 * }</pre>
 */
final class AnimDemoRigs {

    private AnimDemoRigs() {
    }

    /** The adapter a cast's models are read through: GeckoLib's for the humanoids, the engine's for a kit's. */
    static CgRigAdapter adapter(AnimDemoCast cast) {
        return cast.family.equals("humanoid") ? CgRigAdapters.GECKOLIB : CgRigAdapters.CRYSTALGRAPHICS;
    }

    /** {@code cast}'s default rig with its chains resolved, or null where its bones fit none. */
    @Nullable
    static Drawn of(AnimDemoCast cast) {
        CgRig rig = CgRigs.detect(cast.mesh.skeleton(), adapter(cast));
        return rig == null ? null : new Drawn(rig, cast.mesh.skeleton());
    }

    /** A colour per kind of chain: the spine and head gold, arms cyan and orange, legs green and pink. */
    static int colour(String chain) {
        if (chain.startsWith("arm_")) return chain.endsWith("_l") ? 0xFF40E0FF : 0xFFFF9040;
        if (chain.startsWith("leg_")) return chain.endsWith("_l") ? 0xFF60FF60 : 0xFFFF60C0;
        if (chain.startsWith("spine") || chain.startsWith("neck") || chain.startsWith("head")) return 0xFFFFD040;
        if (chain.startsWith("clavicle")) return 0xFFFFFFFF;
        return 0xFFB0B0FF;
    }

    /** A rig with each chain's bones, start to end, ready to draw. */
    static final class Drawn {
        final CgRig rig;
        final List<int[]> bones = new ArrayList<>();
        private final Matrix4f m = new Matrix4f();
        private final Vector3d a = new Vector3d(), b = new Vector3d();

        Drawn(CgRig rig, CgSkeleton skeleton) {
            this.rig = rig;
            for (CgRig.Chain c : rig.chains()) {
                int start = skeleton.indexOf(c.start()), n = 1;
                int[] chain = new int[0];
                for (int x = skeleton.indexOf(c.end()); x >= 0; x = skeleton.parent(x), n++) {
                    if (x == start) {
                        chain = new int[n];
                        for (int y = skeleton.indexOf(c.end()), i = n - 1; i >= 0; y = skeleton.parent(y), i--) chain[i] = y;
                        break;
                    }
                }
                bones.add(chain);
            }
        }

        /** Bone {@code bone} of {@code body} as last posed, in the world, into {@code out}. */
        Vector3d world(AnimDemoCast.Body body, int bone, Vector3d out) {
            body.instance.pose().component(bone, m);
            return body.component.worldTransform().transformPosition(out.set(m.m30(), m.m31(), m.m32()));
        }

        /**
         * Each chain over {@code body} for the next frame: its bones joined, a one-bone chain as a mark, and from each
         * chain's start a short line to where its parent bone is, so the chains read as one body.
         */
        void draw(AnimDemoCast.Body body) {
            CgSkeleton s = body.cast.mesh.skeleton();
            for (int c = 0; c < bones.size(); c++) {
                int[] chain = bones.get(c);
                if (chain.length == 0) continue;
                int colour = colour(rig.chains().get(c).name());
                for (int i = 1; i < chain.length; i++) {
                    world(body, chain[i - 1], a);
                    world(body, chain[i], b);
                    CgCollisionDebug.line(a.x, a.y, a.z, b.x, b.y, b.z, colour);
                }
                int parent = s.parent(chain[0]);
                world(body, chain[0], a);
                if (parent >= 0) {
                    world(body, parent, b);
                    CgCollisionDebug.line(a.x, a.y, a.z, b.x, b.y, b.z, 0x80FFFFFF);
                }
                if (chain.length == 1) CgCollisionDebug.mark(a.x, a.y, a.z, 0, 1, 0, colour);
            }
        }
    }
}
