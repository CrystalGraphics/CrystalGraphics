package com.crystalgraphics.camera.node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.camera.CgCameraContext;
import com.crystalgraphics.camera.CgCameraNode;
import com.crystalgraphics.camera.CgCameraPose;
import com.crystalgraphics.camera.CgCameraRig;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.world.CgAttachment;

/**
 * The camera node kinds a rig runs, by the name its file writes: Gameplay Cameras' node evaluators, each following its
 * {@code Nodes/} source. The engine's are {@code array}, {@code attach_to}, {@code attach_to_group}, {@code attach_between} (ours),
 * {@code set_location}, {@code set_rotation}, {@code offset}, {@code boom_arm} and {@code field_of_view}.
 *
 * <pre>{@code
 * // a mod's kind: its fields declared in CgCameraKinds.NODES, its evaluator here
 * CgCameraNodes.register("lift", (node, rig) -> {
 *     CgCameraRig.Value height = rig.value(node, "height");
 *     return (run, pose) -> pose.location().y += height.number();
 * });
 * }</pre>
 *
 * <ul>
 *   <li>A kind declared but not registered here fails when a rig of it is built: it is not played yet.</li>
 *   <li>A field a kind's evaluator does not play ({@code boom_arm}'s {@code interpolator}, an input slot) fails at
 *       build, naming it, rather than playing without it.</li>
 *   <li>Register at init, before any rig is built.</li>
 * </ul>
 */
public final class CgCameraNodes {

    /** Builds a node's evaluator for one rig instance. */
    @FunctionalInterface
    public interface Factory {
        CgCameraNode build(CgCameraRigDefinition.Node node, CgCameraRig rig);
    }

    private static final Map<String, Factory> KINDS = new HashMap<>();
    /** {@code ZERO_ANIMWEIGHT_THRESH}: a weight this near 0 or 1 is 0 or 1 to {@code FTransform::BlendWith}. */
    private static final float ZERO_WEIGHT = 0.00001f;

    static {
        // A container: its children are flattened after it and run in order.
        register("array", (node, rig) -> (run, pose) -> {
        });
        register("attach_to", CgCameraNodes::attachTo);
        register("attach_to_group", CgCameraNodes::attachToGroup);
        register("attach_between", CgCameraNodes::attachBetween);
        register("set_location", CgCameraNodes::setLocation);
        register("set_rotation", CgCameraNodes::setRotation);
        register("offset", CgCameraNodes::offset);
        register("boom_arm", CgCameraNodes::boomArm);
        register("field_of_view", (node, rig) -> {
            CgCameraRig.Value fov = rig.value(node, "fov");
            return (run, pose) -> {
                if (fov != null) pose.fov((float) fov.number());
            };
        });
    }

    private CgCameraNodes() {
    }

    /** Makes {@code kind} played by {@code factory}; a kind registered twice throws. */
    public static void register(String kind, Factory factory) {
        Objects.requireNonNull(factory, "factory");
        if (KINDS.putIfAbsent(kind, factory) != null) throw new IllegalStateException("camera node kind " + kind + " is registered twice");
    }

    /** {@code node}'s evaluator for {@code rig}; throws for a kind no factory plays. */
    public static CgCameraNode build(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        Factory f = KINDS.get(node.kind());
        if (f == null) {
            throw new IllegalArgumentException(rig.definition().id() + ": camera node " + node.id() + " of kind " + node.kind()
                    + " is not played yet; played: " + KINDS.keySet());
        }
        return f.build(node, rig);
    }

    private static String space(CgCameraRigDefinition.Node node, String fallback) {
        Object s = node.fields().get("space");
        return s == null ? fallback : String.valueOf(s);
    }

    private static IllegalArgumentException unplayed(CgCameraRig rig, CgCameraRigDefinition.Node node, String what) {
        return new IllegalArgumentException(rig.definition().id() + ": camera node " + node.id() + " (" + node.kind() + "): "
                + what + " is not played yet");
    }

    /** An attachment ({@code FCameraActorAttachmentInfo}) as written: its role, socket and weight. */
    private record Target(String role, @Nullable String socket, CgCameraRig.Value weight) {
    }

    private static Target target(CgCameraRig rig, CgCameraRigDefinition.Node node, String where, Object written) {
        if (!(written instanceof Map<?, ?> m) || !(m.get("role") instanceof String role)) {
            throw new IllegalArgumentException(rig.definition().id() + ": " + node.id() + "." + where + ": a target names its role");
        }
        Object weight = m.containsKey("weight") ? m.get("weight") : 1.0;
        return new Target(role, m.get("socket") == null ? null : String.valueOf(m.get("socket")),
                rig.valueOf(rig.definition().id() + ": " + node.id() + "." + where + ".weight", weight, "float", CgQuantity.NONE));
    }

    /** Where {@code t} is now, into {@code point} and {@code turn}; false where its role is not there. */
    private static boolean place(CgCameraContext context, Target t, double[] point, Quaternionf turn) {
        CgAttachment a = context.role(t.role);
        if (a == null || !a.place(point, turn)) return false;
        return t.socket == null || a.point(t.socket, point);
    }

    /** {@code AttachToActorCameraNode.cpp}: the pose takes the target's location, and its rotation if asked; held where lost. */
    private static CgCameraNode attachTo(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        Target t = target(rig, node, "target", node.fields().get("target"));
        CgCameraRig.Value location = rig.value(node, "location"), rotation = rig.value(node, "rotation");
        double[] point = new double[3], last = new double[3];
        Quaternionf turn = new Quaternionf(), lastTurn = new Quaternionf();
        boolean[] held = {false};
        return (run, pose) -> {
            if (place(run.context(), t, point, turn)) {
                System.arraycopy(point, 0, last, 0, 3);
                lastTurn.set(turn);
                held[0] = true;
            } else if (!held[0]) {
                return;   // never placed: the camera stays where it is
            }
            if (location == null || location.bool()) pose.location().set(last[0], last[1], last[2]);
            if (rotation != null && rotation.bool()) pose.rotation().set(lastTurn);
        };
    }

    /**
     * {@code AttachToActorGroupCameraNode.cpp} with {@code CameraActorAttachmentInfo.cpp}: the pose's location becomes
     * the targets' transforms blended in turn by {@code FTransform::BlendWith}, each by its weight over the total.
     */
    private static CgCameraNode attachToGroup(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        if (!(node.fields().get("targets") instanceof List<?> written) || written.isEmpty()) return (run, pose) -> {
        };
        Target[] targets = new Target[written.size()];
        for (int i = 0; i < targets.length; i++) targets[i] = target(rig, node, "targets[" + i + "]", written.get(i));
        double[][] points = new double[targets.length][3];
        float[] weights = new float[targets.length];
        Quaternionf turn = new Quaternionf();
        return (run, pose) -> {
            float total = 0;
            for (int i = 0; i < targets.length; i++) {
                weights[i] = place(run.context(), targets[i], points[i], turn) ? (float) targets[i].weight.number() : 0f;
                total += weights[i];
            }
            if (total == 0) return;
            double x = 0, y = 0, z = 0;
            for (int i = 0; i < targets.length; i++) {
                float a = weights[i] / total;
                if (a <= ZERO_WEIGHT) continue;
                if (a >= 1 - ZERO_WEIGHT) {
                    x = points[i][0];
                    y = points[i][1];
                    z = points[i][2];
                } else {
                    x += (points[i][0] - x) * a;
                    y += (points[i][1] - y) * a;
                    z += (points[i][2] - z) * a;
                }
            }
            pose.location().set(x, y, z);
        };
    }

    /**
     * Ours, Kakarot's skill camera pivot: {@code from + clamp(to - from, max_distance) * share}, the clamp scaling the
     * offset to {@code max_distance} only when that is above 0 and the offset longer; the turn {@code from}'s. With one
     * of the two not placed, the other's.
     */
    private static CgCameraNode attachBetween(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        Target from = target(rig, node, "from", node.fields().get("from")), to = target(rig, node, "to", node.fields().get("to"));
        CgCameraRig.Value share = rig.value(node, "share"), max = rig.value(node, "max_distance");
        double[] a = new double[3], b = new double[3];
        Quaternionf ta = new Quaternionf(), tb = new Quaternionf();
        return (run, pose) -> {
            boolean hasA = place(run.context(), from, a, ta), hasB = place(run.context(), to, b, tb);
            if (!hasA && !hasB) return;
            if (!hasA) {
                pose.location().set(b[0], b[1], b[2]);
                pose.rotation().set(tb);
                return;
            }
            double dx = 0, dy = 0, dz = 0;
            if (hasB) {
                dx = b[0] - a[0];
                dy = b[1] - a[1];
                dz = b[2] - a[2];
                double limit = max == null ? 0 : max.number(), length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (limit > 0 && length > limit) {
                    double k = limit / length;
                    dx *= k;
                    dy *= k;
                    dz *= k;
                }
                double s = share == null ? 0 : share.number();
                dx *= s;
                dy *= s;
                dz *= s;
            }
            pose.location().set(a[0] + dx, a[1] + dy, a[2] + dz);
            pose.rotation().set(ta);
        };
    }

    /** {@code SetLocationCameraNode.cpp}: in the camera pose's space, the owning context's (its turn alone), or the world's. */
    private static CgCameraNode setLocation(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        CgCameraRig.Value location = rig.value(node, "location");
        String space = space(node, "owning_context");
        Vector3d v = new Vector3d();
        return (run, pose) -> {
            if (location == null) return;
            location.vector(v);
            switch (space) {
                case "owning_context" -> pose.location().set(run.context().initial().rotation().transform(v));
                case "world" -> pose.location().set(v);
                default -> pose.location().add(pose.rotation().transform(v));
            }
        };
    }

    /** {@code SetRotationCameraNode.cpp}: after the pose's own turn, in the owning context's, or as given. */
    private static CgCameraNode setRotation(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        CgCameraRig.Value rotation = rig.value(node, "rotation");
        String space = space(node, "camera_pose");
        Quaternionf r = new Quaternionf();
        return (run, pose) -> {
            if (rotation == null) return;
            rotation.rotation(r);
            switch (space) {
                case "owning_context" -> pose.rotation().set(run.context().initial().rotation()).mul(r);
                case "world" -> pose.rotation().set(r);
                default -> pose.rotation().mul(r);
            }
        };
    }

    /**
     * {@code OffsetCameraNode.cpp} by {@code CameraNodeSpaceMath::OffsetCameraNodeSpaceTransform}. In the camera pose's
     * space, {@code FTransform(rot, loc) * pose}. In a context's, the offset is turned by that context's turn, and the
     * turn is its yaw about the context's up, its pitch about its right and its roll about its forward. In the world's,
     * added as given.
     */
    private static CgCameraNode offset(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        CgCameraRig.Value translation = rig.value(node, "translation"), rotation = rig.value(node, "rotation");
        String space = space(node, "camera_pose");
        if (space.equals("subject")) throw unplayed(rig, node, "space subject");
        Vector3d t = new Vector3d();
        Quaternionf r = new Quaternionf(), q = new Quaternionf();
        Vector3f up = new Vector3f(), right = new Vector3f(), forward = new Vector3f();
        double[] angles = new double[3];
        return (run, pose) -> {
            if (translation != null) translation.vector(t);
            else t.zero();
            if (rotation != null) rotation.rotation(r);
            else r.identity();
            switch (space) {
                case "camera_pose" -> {
                    pose.location().add(pose.rotation().transform(t));
                    pose.rotation().mul(r);
                }
                case "world" -> {
                    pose.location().add(t);
                    pose.rotation().premul(r);
                }
                default -> {
                    // active_context, owning_context, pivot (no pivot joint here: the active context's)
                    Quaternionf space0 = run.context().initial().rotation();
                    pose.location().add(space0.transform(t));
                    CgCameraPose.rotator(r, angles);
                    space0.transform(up.set(0, 1, 0));
                    space0.transform(right.set(0, 0, 1));
                    space0.transform(forward.set(1, 0, 0));
                    // Unreal's axes through the host's are a reflection: each angle's sense flips with its axis.
                    q.identity().rotateAxis((float) Math.toRadians(-angles[1]), up)
                            .rotateAxis((float) Math.toRadians(angles[0]), right)
                            .rotateAxis((float) Math.toRadians(angles[2]), forward);
                    pose.rotation().premul(q);
                }
            }
        };
    }

    /**
     * {@code BoomArmCameraNode.cpp}: the arm turns by its input's yaw and pitch (none here), plus the pose's own turn
     * when {@code additive_rotation}; the camera is its offset along that arm from the pose's location
     * ({@code FTransform(offset) * FTransform(rotation, location)}).
     */
    private static CgCameraNode boomArm(CgCameraRigDefinition.Node node, CgCameraRig rig) {
        if (node.fields().get("interpolator") != null) throw unplayed(rig, node, "interpolator");
        if (node.fields().get("input") != null) throw unplayed(rig, node, "input");
        CgCameraRig.Value offset = rig.value(node, "offset");
        boolean additive = Boolean.TRUE.equals(node.fields().get("additive_rotation"));
        Vector3d o = new Vector3d();
        return (run, pose) -> {
            if (!additive) pose.rotation().identity();
            if (offset != null) pose.location().add(pose.rotation().transform(offset.vector(o)));
        };
    }
}
