package com.crystalgraphics.animation.warping;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgWarpWindow;

/**
 * A character's motion warping: the targets its moves aim at, by name, and the root motion of a playing montage bent
 * inside its clip's warp windows ({@link CgWarpWindow}) so the move ends on them. A rush authored to cover four metres
 * lands on an opponent two or nine metres off, and turns to face them, with the clip's own pacing kept.
 *
 * <pre>{@code
 * CgMotionWarping warping = anim.motionWarping();
 * warping.target("opponent", enemy.x, enemy.y, enemy.z, enemyYaw);   // world; before and while the window plays
 * anim.montages().play(rushMontage, ...);                            // its clip carries a window toward "opponent"
 * if (anim.montages().consumeRootMotion(1f, step)) mover.move(step); // the warped step, as root motion always is
 * }</pre>
 *
 * <pre>{@code
 * warping.removeTarget("opponent");                                  // its windows play the clip's own motion
 * }</pre>
 *
 * <ul>
 *   <li>Only the root-motion montage's motion is warped (the montages' mode extracting it); a graph player's is not.</li>
 *   <li>A window opens when play enters it and closes when play passes its end, or the clip changes.</li>
 *   <li>Each step's translation is scaled and sheared so the rest of the window's motion ends on the target (a skew
 *       warp); a window whose clip does not move there is pulled to it in a straight line instead.</li>
 *   <li>Targets and placement are read in the world, y up, a yaw about +y from facing +z. Game thread.</li>
 *   <li>Allocates nothing once warm.</li>
 * </ul>
 */
public final class CgMotionWarping {

    /** A step this short is no motion: 2 µm. */
    private static final float NO_MOTION = 2e-6f;

    private String[] names = new String[2];
    private double[] targets = new double[2 * 4];
    private int targetCount;
    private final Matrix4d placement = new Matrix4d();
    @Nullable
    private Consumer<Matrix4d> placedBy;

    // the open window: its clip, index, where it opened, and the motion it holds
    @Nullable
    private CgAnimSequence clip;
    private int window = -1;
    private float actualStart;
    private final Vector3d startAt = new Vector3d();
    private final Vector3f windowMotion = new Vector3f();
    private boolean retarget;

    private final Matrix4f total = new Matrix4f(), delta = new Matrix4f(), extra = new Matrix4f();
    private final Vector3f t = new Vector3f(), d = new Vector3f(), target = new Vector3f(), e = new Vector3f(), x = new Vector3f(),
            y = new Vector3f(), z = new Vector3f(), secondary = new Vector3f();
    private final Vector3d world = new Vector3d(), here = new Vector3d();
    private final Quaternionf qt = new Quaternionf(), qd = new Quaternionf(), qe = new Quaternionf(), goal = new Quaternionf(),
            frame = new Quaternionf();
    private final Quaterniond current = new Quaterniond(), currentInverse = new Quaterniond();
    private final Matrix4d inverse = new Matrix4d();

    /**
     * Sets target {@code name} to stand at ({@code x}, {@code y}, {@code z}) in the world, facing {@code yaw} radians
     * about +y from +z. A target set again moves; a window open toward it re-aims from where the character stands.
     */
    public CgMotionWarping target(String name, double x, double y, double z, float yaw) {
        Objects.requireNonNull(name, "name");
        int i = find(name);
        if (i < 0) {
            if (targetCount == names.length) {
                names = Arrays.copyOf(names, targetCount * 2);
                targets = Arrays.copyOf(targets, targetCount * 2 * 4);
            }
            i = targetCount++;
            names[i] = name;
        } else if (clip != null && window >= 0 && clip.settings().warpWindows().get(window).target().equals(name)
                && (targets[i * 4] != x || targets[i * 4 + 1] != y || targets[i * 4 + 2] != z || targets[i * 4 + 3] != yaw)) {
            retarget = true;
        }
        targets[i * 4] = x;
        targets[i * 4 + 1] = y;
        targets[i * 4 + 2] = z;
        targets[i * 4 + 3] = yaw;
        return this;
    }

    /** Drops target {@code name}: windows toward it play their clip's own motion. */
    public CgMotionWarping removeTarget(String name) {
        int i = find(name);
        if (i < 0) return this;
        targetCount--;
        names[i] = names[targetCount];
        System.arraycopy(targets, targetCount * 4, targets, i * 4, 4);
        names[targetCount] = null;
        return this;
    }

    /** Whether target {@code name} is set. */
    public boolean hasTarget(String name) {
        return find(name) >= 0;
    }

    /**
     * Where the character stands, read as each step is warped: the mesh's world transform, as the instance's
     * {@code placedBy}, which hands the same. Unset, it stands at the origin.
     */
    public CgMotionWarping placedBy(@Nullable Consumer<Matrix4d> placedBy) {
        this.placedBy = placedBy;
        return this;
    }

    /** Whether a window is warping now, and its clip's window. */
    @Nullable
    public CgWarpWindow open() {
        return clip != null && window >= 0 ? clip.settings().warpWindows().get(window) : null;
    }

    /**
     * Bends {@code step}, the root motion {@code clip} moved from {@code previous} to {@code current} ({@code moved}
     * seconds of it) in the mesh's space, where a warp window of the clip is open. Its montage calls it.
     */
    public void process(CgAnimSequence clip, CgSkeleton skeleton, float previous, float current, float moved, Matrix4f step) {
        if (clip.settings().warpWindows().isEmpty()) {
            this.clip = null;
            window = -1;
            return;
        }
        if (moved <= 0f) return;
        if (clip != this.clip) {
            this.clip = clip;
            window = -1;
        }
        if (window >= 0 && previous >= clip.settings().warpWindows().get(window).end()) window = -1;
        if (window < 0) {
            int found = -1;
            var windows = clip.settings().warpWindows();
            for (int i = 0; i < windows.size(); i++) {
                if (previous >= windows.get(i).start() && previous < windows.get(i).end()) {
                    found = i;
                    break;
                }
            }
            if (found < 0) return;
            window = found;
            stand();
            open(skeleton, previous);
        }
        CgWarpWindow w = clip.settings().warpWindows().get(window);
        int ti = find(w.target());
        if (ti < 0) return;
        stand();
        if (retarget) {
            open(skeleton, previous);
            retarget = false;
        }
        float end = w.end();
        clip.extractRootMotion(skeleton, previous, end - previous, false, total);
        clip.extractRootMotion(skeleton, previous, Math.min(current, end) - previous, false, delta);
        if (current > end) clip.extractRootMotion(skeleton, end, current - end, false, extra);
        else extra.identity();

        placement.invertAffine(inverse);
        world.set(targets[ti * 4], targets[ti * 4 + 1], targets[ti * 4 + 2]);
        placement.getTranslation(here);
        if (w.ignoreHeight()) world.y = here.y;
        inverse.transformPosition(world);
        target.set((float) world.x, (float) world.y, (float) world.z);
        if (w.ignoreHeight()) target.y = 0f;

        delta.getTranslation(d);
        total.getTranslation(t);
        extra.getTranslation(e);
        Vector3f out = x;
        if (w.translation()) {
            if (windowMotion.length() > NO_MOTION) {
                if (d.length() > NO_MOTION) {
                    skew(d, t, target, out).add(e);
                    if (w.maxSpeedRatio() > 0f) {
                        float most = d.length() * w.maxSpeedRatio();
                        if (out.length() > most) out.normalize(most);
                    }
                } else {
                    out.set(d);
                }
            } else {
                // a window with no motion of its own: a straight pull from where it opened to the target
                float alpha = Math.max(0f, Math.min(1f, (current - actualStart) / (end - actualStart)));
                inverse.transformPosition(here.set(startAt));
                float nx = (float) here.x + ((target.x - (float) here.x)) * alpha, nz = (float) here.z + (target.z - (float) here.z) * alpha;
                float ny = w.ignoreHeight() ? 0f : (float) here.y + (target.y - (float) here.y) * alpha;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (target.length() > 1e-6f) out.set(target).normalize(length).add(e);
                else out.zero();
            }
        } else {
            out.set(d).add(e);
        }
        delta.getNormalizedRotation(qd);
        if (w.rotation()) rotate(w, previous, end, moved, ti);
        extra.getNormalizedRotation(qe);
        qe.mul(qd, qd);
        step.translationRotate(out.x, out.y, out.z, qd);
    }

    private void stand() {
        if (placedBy != null) placedBy.accept(placement.identity());
        else placement.identity();
    }

    /** The window opening at {@code previous}: where it starts from, and the motion it holds. */
    private void open(CgSkeleton skeleton, float previous) {
        CgWarpWindow w = clip.settings().warpWindows().get(window);
        actualStart = previous;
        placement.getTranslation(startAt);
        clip.extractRootMotion(skeleton, w.start(), w.end() - w.start(), false, total).getTranslation(windowMotion);
    }

    /** The step's turn bent toward the target's, into {@link #qd}. */
    private void rotate(CgWarpWindow w, float previous, float end, float moved, int ti) {
        placement.getNormalizedRotation(current);
        float yaw;
        if (w.facing()) {
            placement.getTranslation(here);
            yaw = (float) Math.atan2(targets[ti * 4] - here.x, targets[ti * 4 + 2] - here.z);
        } else {
            yaw = (float) targets[ti * 4 + 3];
        }
        yaw += (float) Math.toRadians(w.yawOffset());
        // the target's turn seen from the mesh as it stands
        Quaterniond c = current.conjugate(currentInverse);
        goal.set((float) c.x, (float) c.y, (float) c.z, (float) c.w).mul(qt.rotationY(yaw)).normalize();
        total.getNormalizedRotation(qt);
        if (w.method() == CgWarpWindow.Rotation.SCALE) {
            float totalYaw = yaw(qt), targetYaw = yaw(goal), deltaYaw = yaw(qd);
            float diff = unwind(targetYaw - totalYaw);
            float scale = Math.abs(totalYaw) < 1e-6f ? 0f : (totalYaw + diff) / totalYaw;
            float most = (float) Math.toRadians(w.maxRotationRate()) * moved;
            float scaled = deltaYaw * scale;
            if (w.maxRotationRate() > 0f) scaled = Math.max(-most, Math.min(most, scaled));
            qd.rotationY(scaled);
            return;
        }
        float remaining = (end - previous) * w.rotationTime();
        float alpha = remaining > 0f ? Math.max(0f, Math.min(1f, moved / remaining)) : 1f;
        frame.set(qt).slerp(goal, alpha);
        if (w.method() != CgWarpWindow.Rotation.SLERP) {
            float most = (float) Math.toRadians(Math.abs(moved * w.maxRotationRate()));
            float thisFrame = angle(qt, frame), whole = angle(qt, goal);
            if (w.method() == CgWarpWindow.Rotation.CONSTANT_RATE && whole <= most) {
                frame.set(goal);
            } else if (thisFrame > most || w.method() == CgWarpWindow.Rotation.CONSTANT_RATE) {
                float sign = Math.signum(unwind(yaw(goal) - yaw(qt)));
                frame.rotationY(most * sign).mul(qt);
            }
        }
        // the turn this frame takes, over the clip's own toward the window's end
        frame.mul(qt.conjugate(qe), frame);
        frame.mul(qd, qd).normalize();
    }

    /**
     * The skew warp: {@code delta} of {@code total} remaining motion bent so the remaining motion ends at
     * {@code target}, all in the mesh's space. In a frame along {@code total}, the step is scaled along it by the
     * target's reach over the motion's, and sheared sideways and up by the angles between them.
     */
    private Vector3f skew(Vector3f delta, Vector3f total, Vector3f target, Vector3f out) {
        float length = total.length();
        if (length < NO_MOTION) return out.set(delta);
        x.set(total).div(length);
        // the frame's second axis: up, unless the motion runs nearer up than forward
        float best = Math.abs(x.z);
        secondary.set(0f, 1f, 0f);
        if (Math.abs(x.y) > best) {
            secondary.set(0f, 0f, 1f);
            best = Math.abs(x.y);
        }
        if (Math.abs(x.x) > best) secondary.set(0f, 1f, 0f);
        secondary.cross(x, y);
        if (y.lengthSquared() < 1e-12f) return out.set(delta);
        y.normalize();
        x.cross(y, z);
        float rx = delta.dot(x), ry = delta.dot(y), rz = delta.dot(z);
        float wx = target.dot(x), wy = target.dot(y), wz = target.dot(z);
        float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1e-6f) return out.zero();
        float nx = wx / wl, ny = wy / wl, nz = wz / wl;
        // the motion runs along x in this frame: its own direction is (1, 0, 0)
        float aboutZ = angleOf(nx, ny), aboutY = angleOf(nx, nz);
        float projected = wx / length;
        float sx, sy, sz;
        if (projected != 0f) {
            sx = projected * rx;
            sy = ry + sx * (float) Math.tan(aboutZ);
            sz = rz + sx * (float) Math.tan(aboutY);
        } else {
            float scale = wl / length, along = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            sx = nx * scale * along;
            sy = ny * scale * along;
            sz = nz * scale * along;
        }
        return out.set(x).mul(sx).fma(sy, y).fma(sz, z);
    }

    /** The signed angle of ({@code a}, {@code b}) off the a axis, in the plane they span. */
    private static float angleOf(float a, float b) {
        float l = (float) Math.sqrt(a * a + b * b);
        if (l < 1e-8f) return 0f;
        float angle = (float) Math.acos(Math.max(-1f, Math.min(1f, a / l)));
        return b < 0f ? -angle : angle;
    }

    private static float yaw(Quaternionf q) {
        return (float) (2 * Math.atan2(q.y, q.w));
    }

    private static float angle(Quaternionf a, Quaternionf b) {
        float dot = Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);
        return (float) (2 * Math.acos(Math.min(1f, dot)));
    }

    private static float unwind(float r) {
        while (r > Math.PI) r -= (float) (2 * Math.PI);
        while (r < -Math.PI) r += (float) (2 * Math.PI);
        return r;
    }

    private int find(String name) {
        for (int i = 0; i < targetCount; i++) if (names[i].equals(name)) return i;
        return -1;
    }
}
